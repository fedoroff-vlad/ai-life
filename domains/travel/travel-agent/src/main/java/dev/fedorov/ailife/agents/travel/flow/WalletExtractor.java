package dev.fedorov.ailife.agents.travel.flow;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * Turns one short trip-wallet message into a structured {@link WalletAction} — the single LLM seam in the
 * wallet flow (EX-b). One {@code DEFAULT} llm-gateway turn with the {@code trip-wallet} SKILL as system
 * prompt classifies the message (create / fund / exchange / spend / tally) and extracts its numbers and
 * ISO-4217 currency codes. It does <b>no</b> balance math — that is deterministic Java in
 * {@link TripLedger}. Any parse/transport failure degrades to {@link WalletAction#NONE}. Mirrors the
 * profiler's lenient-JSON extract.
 */
@Component
public class WalletExtractor {

    private static final Logger log = LoggerFactory.getLogger(WalletExtractor.class);
    private static final String SKILL_NAME = "trip-wallet";

    private final LlmClient llm;
    private final SkillRegistry skills;
    private final ObjectMapper json;

    public WalletExtractor(LlmClient llm, SkillRegistry skills, ObjectMapper json) {
        this.llm = llm;
        this.skills = skills;
        this.json = json;
    }

    public Mono<WalletAction> extract(String text) {
        if (text == null || text.isBlank()) {
            return Mono.just(WalletAction.NONE);
        }
        // temperature=0: action extraction must be deterministic/faithful, not creative.
        LlmChatRequest request = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(skillBody()), LlmMessage.user(text)), 0.0);
        return llm.chat(request)
                .map(r -> parse(r.content()))
                .onErrorResume(e -> {
                    log.warn("wallet action extract failed: {}", e.toString());
                    return Mono.just(WalletAction.NONE);
                });
    }

    private WalletAction parse(String content) {
        if (content == null) {
            return WalletAction.NONE;
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return WalletAction.NONE;
        }
        try {
            JsonNode n = json.readTree(content.substring(start, end + 1));
            if (!n.isObject()) {
                return WalletAction.NONE;
            }
            String action = text(n, "action");
            if (action == null) {
                return WalletAction.NONE;
            }
            return new WalletAction(
                    action.trim().toLowerCase(Locale.ROOT),
                    text(n, "title"), text(n, "destination"), currency(n, "homeCurrency"),
                    currency(n, "currency"), decimal(n, "amount"), decimal(n, "rateToHome"),
                    currency(n, "fromCurrency"), decimal(n, "fromAmount"),
                    currency(n, "toCurrency"), decimal(n, "toAmount"),
                    text(n, "description"));
        } catch (Exception e) {
            return WalletAction.NONE;
        }
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "trip-wallet SKILL.md not loaded — check skills-classpath"));
    }

    private static String text(JsonNode node, String field) {
        if (!node.hasNonNull(field) || !node.get(field).isString()) {
            return null;
        }
        String v = node.get(field).asString().trim();
        return v.isEmpty() ? null : v;
    }

    /** An ISO-4217 code, upper-cased; null when absent/blank. */
    private static String currency(JsonNode node, String field) {
        String v = text(node, field);
        return v == null ? null : v.toUpperCase(Locale.ROOT);
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        if (!node.hasNonNull(field) || !node.get(field).isNumber()) {
            return null;
        }
        return node.get(field).decimalValue();
    }

    /**
     * The extracted trip-wallet action. {@code action} is one of {@code create|fund|exchange|spend|tally|
     * none}; only the fields relevant to that action are populated (the rest are null).
     */
    public record WalletAction(
            String action,
            String title, String destination, String homeCurrency,
            String currency, BigDecimal amount, BigDecimal rateToHome,
            String fromCurrency, BigDecimal fromAmount, String toCurrency, BigDecimal toAmount,
            String description) {

        public static final WalletAction NONE = new WalletAction(
                "none", null, null, null, null, null, null, null, null, null, null, null);
    }
}
