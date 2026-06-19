package dev.fedorov.ailife.agents.finance.receipt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.http.MemoryClient;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.finance.http.AccountClient;
import dev.fedorov.ailife.agents.finance.http.MediaClient;
import dev.fedorov.ailife.agents.finance.http.TransactionClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.finance.AddTransactionInput;
import dev.fedorov.ailife.contracts.finance.FinAccountDto;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmImage;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Turns an inbound receipt photo into a finance transaction, <b>confirm-before-write</b>
 * (Stage 4 / A4 — closes the owner-flagged deferred debt).
 *
 * <p>Pipeline: fetch the image bytes from media-service → ask the {@code vision} channel to
 * extract a structured draft (amount / currency / merchant / date) using the {@code receipt-parser}
 * SKILL.md → resolve a target account (first non-archived) → reply with the parsed draft plus a
 * {@code pendingAction}, so the orchestrator locks the conversation to finance. On the user's reply
 * the orchestrator calls {@link #resume} with that pendingAction: an affirmative ("да") writes the
 * transaction via mcp-finance's {@code POST /internal/transaction}; anything else cancels.
 *
 * <p>Category is left null so mcp-finance's one-shot {@code transaction.uncategorised} hook fires
 * the categorizer skill after the write. Failures at any stage degrade to a friendly user-facing
 * message (with no pendingAction → no lock) rather than an exception.
 */
@Component
public class ReceiptParser {

    private static final Logger log = LoggerFactory.getLogger(ReceiptParser.class);
    private static final String SKILL_NAME = "receipt-parser";
    /** pendingAction discriminator the finance ResumeController dispatches on. */
    public static final String FLOW = "receipt-confirm";
    /** Provenance for receipt-derived observations dropped at memory-service (MFC-c). */
    private static final String OBSERVE_SOURCE = "telegram-receipt";
    private static final java.util.Set<String> AFFIRMATIVE = java.util.Set.of(
            "да", "ага", "верно", "сохрани", "сохранить", "ок", "окей", "давай", "+",
            "yes", "y", "ok", "save", "confirm");

    private final MediaClient media;
    private final AccountClient accounts;
    private final TransactionClient transactions;
    private final LlmClient llm;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;
    private final MemoryClient memory;

    public ReceiptParser(MediaClient media,
                         AccountClient accounts,
                         TransactionClient transactions,
                         LlmClient llm,
                         SkillRegistry skills,
                         AgentManifest manifest,
                         ObjectMapper json,
                         MemoryClient memory) {
        this.media = media;
        this.accounts = accounts;
        this.transactions = transactions;
        this.llm = llm;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
        this.memory = memory;
    }

    public Mono<IntentResponse> parse(NormalizedMessage msg, String mediaId) {
        String skillBody = skillBody();
        return media.fetch(mediaId)
                .flatMap(img -> {
                    String base64 = Base64.getEncoder().encodeToString(img.bytes());
                    LlmChatRequest req = LlmChatRequest.of(LlmChannel.VISION, List.of(
                            LlmMessage.system(manifest.body()),
                            LlmMessage.system(skillBody),
                            LlmMessage.userWithImages(
                                    captionOr(msg.text()),
                                    List.of(new LlmImage(img.mimeType(), base64)))));
                    return llm.chat(req).flatMap(resp -> confirm(msg, resp.content(), resp.model()));
                })
                .onErrorResume(e -> {
                    log.warn("receipt parse failed for media {}: {}", mediaId, e.toString());
                    return Mono.just(reply(
                            "Не удалось обработать фото чека. Попробуйте ещё раз или запишите вручную.",
                            null));
                });
    }

    /** Parse the draft, resolve an account, and ask the user to confirm — stash the write as a pendingAction. */
    private Mono<IntentResponse> confirm(NormalizedMessage msg, String llmContent, String model) {
        Draft draft = parseDraft(llmContent);
        captureReceiptObservation(msg, draft);
        if (draft == null || draft.amount() == null) {
            return Mono.just(reply(
                    "Не удалось распознать сумму на чеке. Пришлите фото почётче или запишите вручную.",
                    model));
        }
        return accounts.list(msg.householdId()).map(list -> {
            Optional<FinAccountDto> account = list.stream()
                    .filter(a -> !a.archived())
                    .findFirst();
            if (account.isEmpty()) {
                return reply("Сначала добавьте счёт, чтобы я мог записать расход с чека.", model);
            }
            AddTransactionInput input = buildInput(msg, draft, account.get());
            String currency = blankToNull(draft.currency()) != null
                    ? draft.currency() : account.get().currency();
            String confirmText = confirmText(draft, currency, account.get().name());
            return new IntentResponse(manifest.name(), confirmText, model,
                    pendingAction(input, account.get().name()));
        });
    }

    /**
     * Resume after the user replies to the confirmation. Affirmative → write the stashed draft;
     * anything else → cancel. Either reply carries no pendingAction, so the orchestrator clears the
     * conversation lock.
     */
    public Mono<IntentResponse> resume(ResumeRequest req) {
        JsonNode pending = req.pendingAction();
        if (pending == null || !pending.path("input").isObject()) {
            return Mono.just(reply("Нечего подтверждать — пришлите фото чека заново.", null));
        }
        String text = req.message() == null ? null : req.message().text();
        if (!isAffirmative(text)) {
            return Mono.just(reply("Отменил — ничего не записал. Пришлите чек заново, если нужно.", null));
        }
        AddTransactionInput input;
        try {
            input = json.treeToValue(pending.get("input"), AddTransactionInput.class);
        } catch (Exception e) {
            log.warn("receipt resume: bad pendingAction input: {}", e.toString());
            return Mono.just(reply("Не смог восстановить черновик чека. Пришлите фото заново.", null));
        }
        String accountName = pending.path("accountName").asText("счёт");
        return transactions.add(input)
                .map(saved -> reply(successText(saved.amount(), saved.currency(),
                        input.note(), accountName), null))
                .onErrorResume(e -> {
                    log.warn("add_transaction from receipt confirm failed: {}", e.toString());
                    return Mono.just(reply(
                            "Не смог записать транзакцию. Попробуйте позже.", null));
                });
    }

    /**
     * Feed durable facts from the receipt to memory-from-chat. The user's caption is the
     * fact-bearing part — and it arrives as an attachment-only message, which the orchestrator's
     * text capture skips by design, so the agent processing the attachment must emit it. We ground
     * the caption with the parsed merchant when we have one. A captionless receipt carries no durable
     * personal fact (it's a pure transaction, already in the finance DB), so we emit nothing.
     * Fire-and-forget + soft-fail inside {@link MemoryClient#observe}.
     */
    private void captureReceiptObservation(NormalizedMessage msg, Draft draft) {
        String caption = blankToNull(msg.text());
        if (caption == null) {
            return;
        }
        String merchant = draft == null ? null : blankToNull(draft.merchant());
        String observation = merchant == null ? caption : caption + " (чек: " + merchant + ")";
        memory.observe(msg.householdId(), msg.userId(), observation, OBSERVE_SOURCE);
    }

    private JsonNode pendingAction(AddTransactionInput input, String accountName) {
        ObjectNode node = json.createObjectNode();
        node.put("flow", FLOW);
        node.set("input", json.valueToTree(input));
        node.put("accountName", accountName);
        return node;
    }

    private static boolean isAffirmative(String text) {
        if (text == null) return false;
        return AFFIRMATIVE.contains(text.trim().toLowerCase());
    }

    private static String confirmText(Draft draft, String currency, String accountName) {
        String merchant = (draft.merchant() != null && !draft.merchant().isBlank())
                ? draft.merchant() : "расход";
        String cur = (currency == null || currency.isBlank()) ? "" : " " + currency;
        return "Записать: " + merchant + " " + draft.amount().abs() + cur
                + " (счёт «" + accountName + "»)? Ответьте «да», чтобы сохранить.";
    }

    private AddTransactionInput buildInput(NormalizedMessage msg, Draft draft, FinAccountDto account) {
        // Receipt = expense → store a negative amount regardless of the sign the LLM returned.
        BigDecimal amount = draft.amount().abs().negate();
        String note = (draft.merchant() != null && !draft.merchant().isBlank())
                ? draft.merchant()
                : blankToNull(draft.note());
        return new AddTransactionInput(
                msg.householdId(),
                account.id(),
                null,                       // categoryId null → categorizer one-shot fires
                null,                       // ownerId null → household-shared (MVP)
                amount,
                blankToNull(draft.currency()),  // null → mcp-finance defaults to account currency
                parseTs(draft.date()),          // null → mcp-finance defaults to now
                note,
                "telegram",
                null);
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "receipt-parser SKILL.md not loaded — check skills-classpath"));
    }

    /** Lenient JSON extraction: tolerate markdown fences / leading prose around the object. */
    private Draft parseDraft(String content) {
        if (content == null) {
            return null;
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode node = json.readTree(content.substring(start, end + 1));
            if (!node.isObject() || node.hasNonNull("error")) {
                return null;
            }
            BigDecimal amount = node.hasNonNull("amount") ? node.get("amount").decimalValue() : null;
            return new Draft(
                    amount,
                    text(node, "currency"),
                    text(node, "merchant"),
                    text(node, "date"),
                    text(node, "note"));
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static Instant parseTs(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date.trim()).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    private static String successText(BigDecimal amount, String currency, String note, String accountName) {
        String merchant = (note == null || note.isBlank()) ? "расход" : note;
        return "Добавил: " + merchant + " " + amount + " " + currency
                + " (счёт «" + accountName + "»). Категорию предложу отдельно — поправьте, если неверно.";
    }

    private static String captionOr(String text) {
        return (text == null || text.isBlank()) ? "Receipt photo — extract the transaction." : text;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }

    private record Draft(BigDecimal amount, String currency, String merchant, String date, String note) {
    }
}
