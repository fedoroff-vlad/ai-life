package dev.fedorov.ailife.agents.stylist.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.coordinate.Coordinator;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.stylist.config.StylistAgentProperties;
import dev.fedorov.ailife.agentruntime.http.MediaStoreClient;
import dev.fedorov.ailife.agents.stylist.http.WardrobeReadClient;
import dev.fedorov.ailife.docrender.Doc;
import dev.fedorov.ailife.docrender.DocRenderer;
import dev.fedorov.ailife.docrender.RenderedDoc;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import dev.fedorov.ailife.contracts.wardrobe.WardrobeItemDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The wardrobe gap analysis (ST-j): what to buy to make the wardrobe work for the person's type +
 * lifestyle — each gap with the need it fills, a priority and a price tier — plus a "do not buy"
 * list and a coverage before/after, rendered as the editorial gap board.
 *
 * <p>On the shared {@link Coordinator}: gather the household's {@code wardrobe} + the person's
 * {@code profile}, one LLM synthesis from {@code [AGENT.md, gap-analyst SKILL.md]} → a JSON analysis
 * → build the board (coverage subtitle, "Что докупить" / "Не покупать" / "Фокус" sections, palette)
 * → store → reply summary + link. **Marketplace buy-links stay deferred** (separate line). Runs even
 * on an empty wardrobe (the essentials become the gaps). Synthesis/parse failure → friendly message.
 */
@Component
public class GapAnalyst {

    private static final Logger log = LoggerFactory.getLogger(GapAnalyst.class);
    private static final String SKILL_NAME = "gap-analyst";

    private final Coordinator coordinator;
    private final WardrobeReadClient wardrobe;
    private final MediaStoreClient media;
    private final DocRenderer renderer;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;
    private final StylistAgentProperties props;

    public GapAnalyst(Coordinator coordinator,
                      WardrobeReadClient wardrobe,
                      MediaStoreClient media,
                      DocRenderer renderer,
                      SkillRegistry skills,
                      AgentManifest manifest,
                      ObjectMapper json,
                      StylistAgentProperties props) {
        this.coordinator = coordinator;
        this.wardrobe = wardrobe;
        this.media = media;
        this.renderer = renderer;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
        this.props = props;
    }

    public Mono<IntentResponse> analyse(NormalizedMessage msg) {
        return wardrobe.listItems(msg.householdId(), null)
                .onErrorReturn(List.of())
                .flatMap(items -> synthesize(msg, items))
                .onErrorResume(e -> {
                    log.warn("gap analysis failed: {}", e.toString());
                    return Mono.just(reply("Не получилось собрать список покупок. Попробуйте позже.", null));
                });
    }

    private Mono<IntentResponse> synthesize(NormalizedMessage msg, List<WardrobeItemDto> items) {
        Map<String, Mono<JsonNode>> gather = new LinkedHashMap<>();
        gather.put("wardrobe", Mono.just(json.valueToTree(items)));
        gather.put("profile", wardrobe.getProfile(msg.householdId(), msg.userId())
                .map(p -> (JsonNode) json.valueToTree(p)));

        ObjectNode payload = json.createObjectNode();
        if (msg.text() != null && !msg.text().isBlank()) payload.put("request", msg.text());

        return coordinator.coordinate(
                        List.of(manifest.body(), skillBody()),
                        payload,
                        gather,
                        LlmChannel.DEFAULT)
                .flatMap(result -> {
                    JsonNode gap = parse(result.text());
                    if (gap == null) {
                        return Mono.just(reply("Не смог собрать список покупок. Попробуйте позже.",
                                result.llmModel()));
                    }
                    Doc doc = buildDoc(gap);
                    return store(msg, doc)
                            .map(link -> reply(summary(gap) + "\n\nЧто докупить: " + link, result.llmModel()))
                            .onErrorResume(e -> {
                                log.warn("gap render/store failed: {}", e.toString());
                                return Mono.just(reply(summary(gap), result.llmModel()));
                            });
                });
    }

    private Doc buildDoc(JsonNode gap) {
        Doc.Builder b = Doc.builder("Wardrobe Gap Analysis")
                .kicker("Curated · Strategic · Aligned");
        String coverage = coverage(gap);
        if (coverage != null) b.subtitle(coverage);

        List<String> toBuy = new ArrayList<>();
        for (JsonNode g : array(gap, "gaps")) {
            String name = text(g, "name");
            if (name == null) continue;
            StringBuilder s = new StringBuilder(name);
            if (notBlank(text(g, "fills"))) s.append(" — ").append(text(g, "fills"));
            List<String> tags = new ArrayList<>();
            if (notBlank(text(g, "priority"))) tags.add(text(g, "priority").toUpperCase(Locale.ROOT));
            if (notBlank(text(g, "priceTier"))) tags.add(text(g, "priceTier"));
            if (!tags.isEmpty()) s.append(" (").append(String.join(", ", tags)).append(")");
            toBuy.add(s.toString());
        }
        if (!toBuy.isEmpty()) b.section("Что докупить", toBuy);

        List<String> avoid = new ArrayList<>();
        for (JsonNode d : array(gap, "doNotBuy")) {
            String name = text(d, "name");
            if (name == null) continue;
            avoid.add(notBlank(text(d, "reason")) ? name + " — " + text(d, "reason") : name);
        }
        if (!avoid.isEmpty()) b.section("Не покупать", avoid);

        List<String> focus = new ArrayList<>();
        for (JsonNode f : array(gap, "focusAreas")) {
            if (notBlank(f.asText())) focus.add(f.asText());
        }
        if (!focus.isEmpty()) b.section("Фокус", List.of(String.join(" · ", focus)));

        for (JsonNode s : array(gap, "palette")) {
            if (notBlank(text(s, "hex"))) b.swatch(text(s, "hex"), text(s, "name"));
        }
        return b.build();
    }

    private Mono<String> store(NormalizedMessage msg, Doc doc) {
        RenderedDoc rendered = renderer.render(doc);
        return media.upload(msg.householdId(), msg.userId(),
                        rendered.filename(), rendered.mimeType(), rendered.content())
                .map(this::link);
    }

    private String summary(JsonNode gap) {
        int count = array(gap, "gaps").size();
        String coverage = coverage(gap);
        StringBuilder sb = new StringBuilder("Список покупок готов: " + count + " позиц.");
        if (coverage != null) sb.append(" ").append(coverage).append(".");
        return sb.toString();
    }

    private static String coverage(JsonNode gap) {
        String before = text(gap, "coverageBefore");
        String after = text(gap, "coverageAfter");
        if (before == null && after == null) return null;
        return "Покрытие: " + (before == null ? "?" : before) + " → " + (after == null ? "?" : after);
    }

    private String link(MediaObjectDto stored) {
        String base = props.getPublicMediaBaseUrl();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/v1/media/" + stored.id();
    }

    private JsonNode parse(String content) {
        if (content == null) return null;
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JsonNode node = json.readTree(content.substring(start, end + 1));
            if (!node.isObject() || node.hasNonNull("error")) return null;
            return node;
        } catch (Exception e) {
            return null;
        }
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "gap-analyst SKILL.md not loaded — check skills-classpath"));
    }

    private static JsonNode array(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v != null && v.isArray()) ? v : com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        return (v != null && !v.isNull()) ? v.asText() : null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }
}
