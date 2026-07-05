package dev.fedorov.ailife.agents.finance.advisor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.coordinate.Coordinator;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.finance.http.MarketDataClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reactive on-request <b>investment advisory</b> (the finance recorded-vision leg). When the user
 * asks "что думаешь про мои акции X, Y?" / "стоит ли смотреть на золото?", the
 * {@link dev.fedorov.ailife.agents.finance.intent.IntentRouter} classifier maps the mentioned
 * tickers to source-native symbols and routes here.
 *
 * <p><b>Advisory only — this never executes a trade, places an order, or moves money.</b> It reads
 * quotes (read-only) and reasons over them; the user decides. The {@code mcp-market-data} capability
 * it gathers from has no order/trade tool by design.
 *
 * <p>Built on the shared {@link Coordinator} substrate (gather → synthesize), like
 * {@link FinancialAdvisor}: it gathers a {@code quote} per named symbol in parallel (each step
 * soft-fails independently — a bad symbol just drops out), folds them into {@code context}, and asks
 * the LLM to synthesize considerations from {@code [AGENT.md, investment-advisor SKILL.md] +
 * {payload(userText, symbols), context}}. Text-first.
 */
@Component
public class InvestmentAdvisor {

    private static final Logger log = LoggerFactory.getLogger(InvestmentAdvisor.class);
    private static final String SKILL_NAME = "investment-advisor";
    /** Bound the fan-out so a noisy message can't trigger an unbounded quote storm. */
    private static final int MAX_SYMBOLS = 12;

    private final Coordinator coordinator;
    private final MarketDataClient marketData;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public InvestmentAdvisor(Coordinator coordinator,
                             MarketDataClient marketData,
                             SkillRegistry skills,
                             AgentManifest manifest,
                             ObjectMapper json) {
        this.coordinator = coordinator;
        this.marketData = marketData;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
    }

    public Mono<AdviceResult> advise(NormalizedMessage msg, List<String> symbols) {
        List<String> syms = normalize(symbols);
        if (syms.isEmpty()) {
            return Mono.just(new AdviceResult(
                    "Назовите тикеры или активы (например AAPL, золото, BTC) — я соберу котировки и "
                            + "дам соображения. Решение всегда остаётся за вами: я не торгую.", null));
        }

        Map<String, Mono<JsonNode>> gather = new LinkedHashMap<>();
        for (String symbol : syms) {
            gather.put(symbol, quoteNode(symbol));
        }

        ObjectNode payload = json.createObjectNode();
        if (msg != null && msg.text() != null && !msg.text().isBlank()) {
            payload.put("userText", msg.text());
        }
        ArrayNode arr = payload.putArray("symbols");
        syms.forEach(arr::add);

        return coordinator.coordinate(
                        List.of(manifest.body(), skillBody()),
                        payload,
                        gather,
                        LlmChannel.DEFAULT)
                .map(r -> new AdviceResult(r.text(), r.llmModel()))
                .onErrorResume(e -> {
                    log.warn("investment-advisor failed for symbols {}: {}", syms, e.toString());
                    return Mono.just(new AdviceResult(
                            "Не смог получить котировки. Попробуйте позже.", null));
                });
    }

    /** One quote as JSON; a failed quote is soft-failed by the Coordinator (drops out of context). */
    private Mono<JsonNode> quoteNode(String symbol) {
        return marketData.quote(symbol).map(q -> (JsonNode) json.valueToTree(q));
    }

    private static List<String> normalize(List<String> symbols) {
        List<String> out = new ArrayList<>();
        if (symbols == null) {
            return out;
        }
        for (String s : symbols) {
            if (s == null) {
                continue;
            }
            String t = s.trim();
            if (!t.isEmpty() && !out.contains(t) && out.size() < MAX_SYMBOLS) {
                out.add(t);
            }
        }
        return out;
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "investment-advisor SKILL.md not loaded — check skills-classpath"));
    }

    /** The synthesized advisory text plus the model that produced it (for the response contract). */
    public record AdviceResult(String text, String model) {
    }
}
