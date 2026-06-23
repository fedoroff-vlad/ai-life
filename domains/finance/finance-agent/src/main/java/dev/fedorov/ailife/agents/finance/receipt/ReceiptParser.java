package dev.fedorov.ailife.agents.finance.receipt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.http.MemoryClient;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.finance.http.AccountClient;
import dev.fedorov.ailife.agents.finance.http.BasketCapturedClient;
import dev.fedorov.ailife.agents.finance.http.CaptionClient;
import dev.fedorov.ailife.agents.finance.http.TransactionClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.basket.BasketCapturedEvent;
import dev.fedorov.ailife.contracts.finance.AddTransactionInput;
import dev.fedorov.ailife.contracts.finance.FinAccountDto;
import dev.fedorov.ailife.contracts.nutrition.BasketItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns an inbound receipt photo into a finance transaction, <b>confirm-before-write</b>
 * (Stage 4 / A4 — closes the owner-flagged deferred debt).
 *
 * <p>Pipeline: ask the shared {@code mcp-media-processing} capability's {@code caption} tool (over
 * its {@code POST /internal/caption} passthrough) to extract a structured draft
 * (amount / currency / merchant / date) — the instruction is the {@code receipt-parser} SKILL.md, so
 * the vision call itself lives once in the capability-MCP (MP-c) rather than being embedded here →
 * resolve a target account (first non-archived) → reply with the parsed draft plus a
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
    /** Cap on grocery line items fanned out to nutrition (a receipt shouldn't blow up the event). */
    private static final int MAX_BASKET_ITEMS = 100;
    private static final java.util.Set<String> AFFIRMATIVE = java.util.Set.of(
            "да", "ага", "верно", "сохрани", "сохранить", "ок", "окей", "давай", "+",
            "yes", "y", "ok", "save", "confirm");

    private final CaptionClient caption;
    private final AccountClient accounts;
    private final TransactionClient transactions;
    private final BasketCapturedClient basketCaptured;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;
    private final MemoryClient memory;

    public ReceiptParser(CaptionClient caption,
                         AccountClient accounts,
                         TransactionClient transactions,
                         BasketCapturedClient basketCaptured,
                         SkillRegistry skills,
                         AgentManifest manifest,
                         ObjectMapper json,
                         MemoryClient memory) {
        this.caption = caption;
        this.accounts = accounts;
        this.transactions = transactions;
        this.basketCaptured = basketCaptured;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
        this.memory = memory;
    }

    public Mono<IntentResponse> parse(NormalizedMessage msg, String mediaId) {
        return caption.caption(mediaId, captionInstruction(msg.text()))
                .flatMap(result -> confirm(msg, mediaId, result.text(), result.model()))
                .onErrorResume(e -> {
                    log.warn("receipt parse failed for media {}: {}", mediaId, e.toString());
                    return Mono.just(reply(
                            "Не удалось обработать фото чека. Попробуйте ещё раз или запишите вручную.",
                            null));
                });
    }

    /** Parse the draft, resolve an account, and ask the user to confirm — stash the write as a pendingAction. */
    private Mono<IntentResponse> confirm(NormalizedMessage msg, String mediaId, String llmContent, String model) {
        Draft draft = parseDraft(llmContent);
        captureReceiptObservation(msg, draft);
        publishBasketIfGrocery(msg, mediaId, draft);
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

    /**
     * Fan a recognised <b>grocery</b> receipt out to the nutrition domain (IA-a, case-1). When the
     * receipt is a grocery basket with readable line items, publish a {@link BasketCapturedEvent} to
     * mcp-finance's bus drop-point; nutritionist-agent consumes it and runs its breakdown — so the
     * receipt's vision work, done once here, reaches both agents. Fire-and-forget + soft-fail inside
     * {@link BasketCapturedClient#publish} (the breakdown is best-effort and must not affect the
     * expense-confirmation reply). Emitted at parse time (like the MFC-c observation), independent of
     * whether the user later confirms the expense write. Non-grocery / itemless receipts emit nothing.
     */
    private void publishBasketIfGrocery(NormalizedMessage msg, String mediaId, Draft draft) {
        if (draft == null || !draft.isGrocery()) {
            return;
        }
        List<BasketItem> items = parseItems(draft.items());
        if (items.isEmpty()) {
            return;
        }
        basketCaptured.publish(new BasketCapturedEvent(
                msg.householdId(),
                null,                       // household-shared (matches the MVP transaction ownerId)
                blankToNull(draft.merchant()),
                items,
                parseMediaId(mediaId),
                Instant.now()));
    }

    /** Receipt line items → basket items (name + qty only; nutrition re-estimates the КБЖУ). */
    private List<BasketItem> parseItems(JsonNode arr) {
        List<BasketItem> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode n : arr) {
            String name = text(n, "name");
            if (name == null || name.isBlank()) {
                continue;
            }
            out.add(new BasketItem(name, text(n, "qty"), null, null, null, null));
            if (out.size() >= MAX_BASKET_ITEMS) {
                break;
            }
        }
        return out;
    }

    private static UUID parseMediaId(String mediaId) {
        if (mediaId == null || mediaId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(mediaId.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
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
            boolean isGrocery = node.hasNonNull("is_grocery") && node.get("is_grocery").asBoolean(false);
            JsonNode items = node.hasNonNull("items") ? node.get("items") : null;
            return new Draft(
                    amount,
                    text(node, "currency"),
                    text(node, "merchant"),
                    text(node, "date"),
                    text(node, "note"),
                    isGrocery,
                    items);
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

    /**
     * The instruction handed to the capability's {@code caption} tool: the {@code receipt-parser}
     * SKILL.md (the self-contained strict-JSON extract prompt), plus the user's own caption as a
     * trailing hint when present (e.g. "вот чек за кофе").
     */
    private String captionInstruction(String userText) {
        String note = blankToNull(userText);
        return note == null ? skillBody() : skillBody() + "\n\nUser note: " + note;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }

    private record Draft(BigDecimal amount, String currency, String merchant, String date, String note,
                         boolean isGrocery, JsonNode items) {
    }
}
