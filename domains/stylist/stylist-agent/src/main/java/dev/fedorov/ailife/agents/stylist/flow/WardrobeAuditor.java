package dev.fedorov.ailife.agents.stylist.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.coordinate.Coordinator;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.stylist.config.StylistAgentProperties;
import dev.fedorov.ailife.agents.stylist.http.MediaStoreClient;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The wardrobe audit (ST-h): a KEEP / QUESTION / REMOVE verdict per catalogued garment, the hero
 * pieces, and a one-sentence systemic-pattern diagnosis — rendered as the editorial audit board.
 *
 * <p>On the shared {@link Coordinator}: gather the household's {@code wardrobe} items + the person's
 * {@code profile}, run one LLM synthesis from {@code [AGENT.md, wardrobe-auditor SKILL.md]} → a JSON
 * audit (verdicts by garment name, hero names, systemic pattern, power palette) → build the board
 * (verdict grid with the garment photos matched back by name, gold hero row, palette swatches,
 * "Системная ошибка" section) → store in media-service → reply summary + link. Empty wardrobe → an
 * invite to catalogue. Synthesis/parse failure → a friendly message.
 */
@Component
public class WardrobeAuditor {

    private static final Logger log = LoggerFactory.getLogger(WardrobeAuditor.class);
    private static final String SKILL_NAME = "wardrobe-auditor";

    private final Coordinator coordinator;
    private final WardrobeReadClient wardrobe;
    private final MediaStoreClient media;
    private final DocRenderer renderer;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;
    private final StylistAgentProperties props;

    public WardrobeAuditor(Coordinator coordinator,
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

    public Mono<IntentResponse> audit(NormalizedMessage msg) {
        return wardrobe.listItems(msg.householdId(), null)
                .onErrorReturn(List.of())
                .flatMap(items -> {
                    if (items.isEmpty()) {
                        return Mono.just(reply(
                                "В вашем гардеробе пока нет вещей. Пришлите фото одежды — каталогизирую, "
                                        + "и тогда проведу ревизию.", null));
                    }
                    return synthesize(msg, items);
                })
                .onErrorResume(e -> {
                    log.warn("wardrobe audit failed: {}", e.toString());
                    return Mono.just(reply("Не получилось провести ревизию. Попробуйте позже.", null));
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
                    JsonNode audit = parse(result.text());
                    if (audit == null) {
                        return Mono.just(reply("Не смог собрать ревизию гардероба. Попробуйте позже.",
                                result.llmModel()));
                    }
                    Doc doc = buildDoc(audit, items);
                    return store(msg, doc)
                            .map(link -> reply(summary(audit) + "\n\nРевизия: " + link, result.llmModel()))
                            .onErrorResume(e -> {
                                log.warn("audit render/store failed: {}", e.toString());
                                return Mono.just(reply(summary(audit), result.llmModel()));
                            });
                });
    }

    private Doc buildDoc(JsonNode audit, List<WardrobeItemDto> items) {
        Map<String, UUID> photoByName = new LinkedHashMap<>();
        for (WardrobeItemDto item : items) {
            if (item.name() != null && item.imageMediaId() != null) {
                photoByName.put(item.name().toLowerCase(Locale.ROOT), item.imageMediaId());
            }
        }
        Doc.Builder b = Doc.builder("Wardrobe Audit Board")
                .kicker("Edited · Strategic · Aligned");

        JsonNode verdicts = audit.get("verdicts");
        if (verdicts != null && verdicts.isArray()) {
            for (JsonNode v : verdicts) {
                String name = text(v, "name");
                if (name == null) continue;
                b.verdict(name, parseVerdict(text(v, "verdict")), text(v, "reason"),
                        photoUrl(photoByName.get(name.toLowerCase(Locale.ROOT))));
            }
        }

        JsonNode hero = audit.get("hero");
        if (hero != null && hero.isArray()) {
            for (JsonNode h : hero) {
                String name = h.asText();
                if (name != null && !name.isBlank()) {
                    b.heroPiece(name, photoUrl(photoByName.get(name.toLowerCase(Locale.ROOT))), null);
                }
            }
        }

        JsonNode palette = audit.get("palette");
        if (palette != null && palette.isArray()) {
            for (JsonNode s : palette) {
                if (text(s, "hex") != null) b.swatch(text(s, "hex"), text(s, "name"));
            }
        }

        if (notBlank(text(audit, "systemicPattern"))) {
            b.section("Системная ошибка", List.of(text(audit, "systemicPattern")));
        }
        return b.build();
    }

    private Mono<String> store(NormalizedMessage msg, Doc doc) {
        RenderedDoc rendered = renderer.render(doc);
        return media.upload(msg.householdId(), msg.userId(),
                        rendered.filename(), rendered.mimeType(), rendered.content())
                .map(this::link);
    }

    private String summary(JsonNode audit) {
        int keep = 0, question = 0, remove = 0;
        JsonNode verdicts = audit.get("verdicts");
        if (verdicts != null && verdicts.isArray()) {
            for (JsonNode v : verdicts) {
                switch (parseVerdict(text(v, "verdict"))) {
                    case KEEP -> keep++;
                    case QUESTION -> question++;
                    case REMOVE -> remove++;
                }
            }
        }
        return "Ревизия гардероба готова: оставить " + keep + ", под вопросом " + question
                + ", убрать " + remove + ".";
    }

    private static Doc.Verdict parseVerdict(String v) {
        if (v == null) return Doc.Verdict.KEEP;
        return switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "remove" -> Doc.Verdict.REMOVE;
            case "question" -> Doc.Verdict.QUESTION;
            default -> Doc.Verdict.KEEP;
        };
    }

    private String photoUrl(UUID mediaId) {
        return mediaId == null ? null : trimBase(props.getPublicMediaBaseUrl()) + "/v1/media/" + mediaId;
    }

    private String link(MediaObjectDto stored) {
        return trimBase(props.getPublicMediaBaseUrl()) + "/v1/media/" + stored.id();
    }

    private static String trimBase(String base) {
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
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
                        "wardrobe-auditor SKILL.md not loaded — check skills-classpath"));
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
