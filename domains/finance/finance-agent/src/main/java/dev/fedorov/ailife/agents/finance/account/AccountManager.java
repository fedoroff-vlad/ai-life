package dev.fedorov.ailife.agents.finance.account;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.finance.http.AccountClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.finance.UpsertAccountInput;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llm.LlmClient;
import dev.fedorov.ailife.sharing.SharingContext;
import dev.fedorov.ailife.sharing.SharingResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * On-request <b>chat-driven account creation</b> (ADR-0002 slice 4b) — turn a plain-language message
 * ("заведи карту Тинькофф в рублях", "создай общий счёт для семьи") into a new finance account. Routed
 * here by the {@link dev.fedorov.ailife.agents.finance.intent.IntentRouter} {@code account} action.
 *
 * <p>This is the finance domain's <b>write path</b> for the sharing capability. Flow: ask the LLM — with
 * the {@code account-manager} SKILL as the instruction — for a strict-JSON plan
 * ({@code {name,type,currency,openingBalance?,joint?}}), then:
 * <ol>
 *   <li>build a {@link SharingContext} whose {@code involvesHouseholdMember} carries the LLM's read of
 *       whether the account is <b>joint</b> (co-owned by a household member);</li>
 *   <li>let the shared {@link SharingResolver} route it to a concrete {@code household_id} — a joint
 *       account lands in the family household, a personal one in the member's own (finance's
 *       {@code FinanceSharingPolicy} default). The routing/fallback rules live once in {@code libs/sharing};
 *       this flow only supplies the signals;</li>
 *   <li>persist it via mcp-finance's {@code POST /internal/account} ({@code AccountClient.upsert}).</li>
 * </ol>
 * mcp-finance stays tenant-agnostic — it writes to whatever household it is handed. Returns a short
 * Russian confirmation naming the account and whether it is shared or personal.
 */
@Component
public class AccountManager {

    private static final Logger log = LoggerFactory.getLogger(AccountManager.class);
    private static final String SKILL_NAME = "account-manager";
    private static final Set<String> TYPES = Set.of("card", "cash", "deposit", "credit");
    private static final String DEFAULT_TYPE = "card";

    private final LlmClient llm;
    private final AccountClient accounts;
    private final SharingResolver sharing;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public AccountManager(LlmClient llm,
                          AccountClient accounts,
                          SharingResolver sharing,
                          SkillRegistry skills,
                          AgentManifest manifest,
                          ObjectMapper json) {
        this.llm = llm;
        this.accounts = accounts;
        this.sharing = sharing;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
    }

    public Mono<AccountResult> create(NormalizedMessage msg) {
        UUID envelopeHousehold = msg == null ? null : msg.householdId();
        UUID userId = msg == null ? null : msg.userId();
        if (envelopeHousehold == null && userId == null) {
            return Mono.just(new AccountResult(
                    "Не вижу, чей это счёт — не могу его создать.", null));
        }
        return planAndCreate(msg, userId, envelopeHousehold)
                .onErrorResume(e -> {
                    log.warn("account-manager failed: {}", e.toString());
                    return Mono.just(new AccountResult(
                            "Не смог создать счёт. Попробуйте ещё раз чуть позже.", null));
                });
    }

    private Mono<AccountResult> planAndCreate(NormalizedMessage msg, UUID userId, UUID envelopeHousehold) {
        ObjectNode userMsg = json.createObjectNode();
        userMsg.put("userText", msg == null || msg.text() == null ? "" : msg.text());

        LlmChatRequest req = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.system(skillBody()),
                LlmMessage.user(userMsg.toString())));

        return llm.chat(req).flatMap(resp -> {
            String model = resp.model();
            Planned plan = parsePlan(resp.content());
            if (plan == null) {
                return Mono.just(new AccountResult(
                        "Не понял, какой счёт создать. Скажите, например: "
                                + "«заведи карту в рублях» или «создай общий счёт в евро».", model));
            }
            if (plan.currency() == null) {
                return Mono.just(new AccountResult(
                        "В какой валюте завести счёт «" + plan.name() + "»?", model));
            }
            // The account is the sharing boundary: a joint account → the family household, a personal one
            // → the member's own. The LLM's "joint" read is the only signal; the resolver + policy own the
            // routing. No explicit scope is threaded — finance's default policy reads the context.
            SharingContext ctx = new SharingContext(List.of(plan.type()), plan.joint(), "account");
            return sharing.resolveHousehold(userId, null, ctx, envelopeHousehold)
                    .switchIfEmpty(Mono.error(new IllegalStateException("no resolvable household")))
                    .flatMap(household -> accounts.upsert(new UpsertAccountInput(
                            null, household, null, plan.name(), plan.type(), plan.currency(),
                            plan.openingBalance(), null))
                            .map(dto -> new AccountResult(summary(dto.name(), plan.joint()), model)));
        });
    }

    /** Parse the single-account LLM plan; null when no account was described. */
    private Planned parsePlan(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        JsonNode node;
        try {
            node = json.readTree(trimmed.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
        String name = node.path("name").asString("").trim();
        if (name.isEmpty()) {
            return null;
        }
        String type = node.path("type").asString(DEFAULT_TYPE).trim().toLowerCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            type = DEFAULT_TYPE;
        }
        String currency = node.hasNonNull("currency")
                ? node.get("currency").asString().trim().toUpperCase(Locale.ROOT) : "";
        BigDecimal openingBalance = null;
        if (node.hasNonNull("openingBalance")) {
            try {
                openingBalance = new BigDecimal(node.get("openingBalance").asString().trim());
            } catch (NumberFormatException ignored) {
                // A malformed balance is non-fatal — the account is created without an opening balance.
            }
        }
        boolean joint = node.path("joint").asBoolean(false);
        return new Planned(name, type, currency.isEmpty() ? null : currency, openingBalance, joint);
    }

    private String summary(String name, boolean joint) {
        return "Готово. " + (joint ? "Общий" : "Личный") + " счёт «" + name + "» создан.";
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "account-manager SKILL.md not loaded — check skills-classpath"));
    }

    /** One planned account: name, type (card|cash|deposit|credit), ISO currency, optional opening balance,
     *  and whether it is joint (co-owned by a household member → shared). */
    private record Planned(String name, String type, String currency, BigDecimal openingBalance, boolean joint) {
    }

    /** The chat reply (a short confirmation) plus the model that produced the plan. */
    public record AccountResult(String text, String model) {
    }
}
