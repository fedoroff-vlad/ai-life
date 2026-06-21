package dev.fedorov.ailife.agents.stylist.analyse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.stylist.config.StylistAgentProperties;
import dev.fedorov.ailife.agents.stylist.http.CaptionClient;
import dev.fedorov.ailife.agents.stylist.http.MediaStoreClient;
import dev.fedorov.ailife.agents.stylist.http.StyleProfileClient;
import dev.fedorov.ailife.agents.stylist.render.RenderedDoc;
import dev.fedorov.ailife.agents.stylist.render.StylistDoc;
import dev.fedorov.ailife.agents.stylist.render.StylistRenderer;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import dev.fedorov.ailife.contracts.wardrobe.SetStyleProfileInput;
import dev.fedorov.ailife.contracts.wardrobe.StyleProfileDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The "analyse me" flow (ST-d): a self-photo + the user's typed body params → a style profile +
 * an HTML analysis deliverable.
 *
 * <p>Pipeline: ask the shared {@code mcp-media-processing} {@code caption} tool (instruction = the
 * {@code style-analyst} SKILL.md, with the user's note folded in so the model also picks up any
 * height/weight/measurements they typed) for a structured profile JSON → persist it via
 * {@code mcp-wardrobe}'s {@code POST /internal/profile} (keyed on household+owner=the user) → render
 * the analysis as a responsive HTML page through the {@link StylistRenderer} seam → store it in
 * media-service → reply with a short summary plus a link the user can open on any device.
 *
 * <p>Both param sources land here (owner choice 2026-06-21): the photo drives person/colour type +
 * body shape; the typed note supplies height/weight/measurements (the SKILL copies them through). A
 * second LLM-written "concrete examples" pass is a later enhancement — the MVP page is grounded in
 * the structured profile. Any stage failing degrades to a friendly message.
 */
@Component
public class AnalyseMe {

    private static final Logger log = LoggerFactory.getLogger(AnalyseMe.class);
    private static final String SKILL_NAME = "style-analyst";

    private final CaptionClient caption;
    private final StyleProfileClient profiles;
    private final MediaStoreClient media;
    private final StylistRenderer renderer;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;
    private final StylistAgentProperties props;

    public AnalyseMe(CaptionClient caption,
                     StyleProfileClient profiles,
                     MediaStoreClient media,
                     StylistRenderer renderer,
                     SkillRegistry skills,
                     AgentManifest manifest,
                     ObjectMapper json,
                     StylistAgentProperties props) {
        this.caption = caption;
        this.profiles = profiles;
        this.media = media;
        this.renderer = renderer;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
        this.props = props;
    }

    public Mono<IntentResponse> analyse(NormalizedMessage msg, String mediaId) {
        return caption.caption(mediaId, captionInstruction(msg.text()))
                .flatMap(result -> persistAndRender(msg, mediaId, result.text(), result.model()))
                .onErrorResume(e -> {
                    log.warn("analyse-me failed for media {}: {}", mediaId, e.toString());
                    return Mono.just(reply(
                            "Не удалось разобрать фото для анализа. Пришлите чёткий снимок в полный рост.", null));
                });
    }

    private Mono<IntentResponse> persistAndRender(NormalizedMessage msg, String mediaId,
                                                  String llmContent, String model) {
        JsonNode draft = parseDraft(llmContent);
        if (draft == null) {
            return Mono.just(reply(
                    "Не понял, кто на фото. Пришлите свой снимок в полный рост на нейтральном фоне.", model));
        }
        SetStyleProfileInput input = buildInput(msg, mediaId, draft);
        return profiles.set(input)
                .flatMap(saved -> store(msg, saved)
                        .map(link -> reply(summary(saved) + "\n\nПодробный анализ: " + link, model))
                        .onErrorResume(e -> {
                            log.warn("analysis render/store failed: {}", e.toString());
                            // Profile saved — still give the user the summary even if the page didn't store.
                            return Mono.just(reply(summary(saved)
                                    + "\n\n(HTML-страницу анализа сохранить не удалось — попробуйте позже.)", model));
                        }))
                .onErrorResume(e -> {
                    log.warn("set_style_profile failed: {}", e.toString());
                    return Mono.just(reply("Не смог сохранить профиль стиля. Попробуйте позже.", null));
                });
    }

    /** Render the analysis HTML, store it in media-service, return the public link. */
    private Mono<String> store(NormalizedMessage msg, StyleProfileDto profile) {
        RenderedDoc doc = renderer.render(buildDoc(profile));
        return media.upload(msg.householdId(), msg.userId(), doc.filename(), doc.mimeType(), doc.content())
                .map(this::link);
    }

    private SetStyleProfileInput buildInput(NormalizedMessage msg, String mediaId, JsonNode draft) {
        return new SetStyleProfileInput(
                msg.householdId(),
                msg.userId(),                       // a person analyses themselves → owner = the user
                text(draft, "personType"),
                text(draft, "bodyShape"),
                text(draft, "colourType"),
                draft.has("suitableFabrics") && draft.get("suitableFabrics").isArray()
                        ? draft.get("suitableFabrics") : null,
                draft.hasNonNull("heightCm") ? draft.get("heightCm").asInt() : null,
                draft.hasNonNull("weightKg") ? new BigDecimal(draft.get("weightKg").asText()) : null,
                draft.has("measurements") && draft.get("measurements").isObject()
                        ? draft.get("measurements") : null,
                text(draft, "notes"),
                parseMediaId(mediaId));
    }

    private StylistDoc buildDoc(StyleProfileDto p) {
        List<StylistDoc.Section> sections = new ArrayList<>();
        if (notBlank(p.colourType())) {
            List<String> body = new ArrayList<>();
            body.add("Ваш цветотип: " + p.colourType() + ".");
            if (notBlank(p.notes())) body.add(p.notes());
            sections.add(new StylistDoc.Section("Цветотип", body));
        }
        if (notBlank(p.bodyShape())) {
            sections.add(new StylistDoc.Section("Тип фигуры",
                    List.of("Силуэт: " + p.bodyShape() + ".")));
        }
        if (p.suitableFabrics() != null && p.suitableFabrics().isArray() && !p.suitableFabrics().isEmpty()) {
            List<String> fabrics = new ArrayList<>();
            p.suitableFabrics().forEach(n -> fabrics.add(n.asText()));
            sections.add(new StylistDoc.Section("Ткани и текстуры",
                    List.of("Вам подходят: " + String.join(", ", fabrics) + ".")));
        }
        String params = bodyParams(p);
        if (params != null) {
            sections.add(new StylistDoc.Section("Параметры тела", List.of(params)));
        }
        String subtitle = notBlank(p.personType()) ? "Тип внешности: " + p.personType() : null;
        return new StylistDoc("Анализ стиля", subtitle, sections);
    }

    private static String bodyParams(StyleProfileDto p) {
        List<String> parts = new ArrayList<>();
        if (p.heightCm() != null) parts.add("рост " + p.heightCm() + " см");
        if (p.weightKg() != null) parts.add("вес " + p.weightKg().stripTrailingZeros().toPlainString() + " кг");
        if (p.measurements() != null && p.measurements().isObject() && !p.measurements().isEmpty()) {
            List<String> m = new ArrayList<>();
            p.measurements().fields().forEachRemaining(e -> m.add(e.getKey() + " " + e.getValue().asText()));
            if (!m.isEmpty()) parts.add("мерки: " + String.join(", ", m));
        }
        return parts.isEmpty() ? null : capitalize(String.join(", ", parts)) + ".";
    }

    private String summary(StyleProfileDto p) {
        StringBuilder sb = new StringBuilder("Готов анализ вашего стиля.");
        if (notBlank(p.colourType())) sb.append(" Цветотип: ").append(p.colourType()).append(".");
        if (notBlank(p.bodyShape())) sb.append(" Фигура: ").append(p.bodyShape()).append(".");
        return sb.toString();
    }

    private String link(MediaObjectDto stored) {
        String base = props.getPublicMediaBaseUrl();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/v1/media/" + stored.id();
    }

    private String captionInstruction(String userText) {
        String note = blankToNull(userText);
        return note == null ? skillBody() : skillBody() + "\n\nUser note: " + note;
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "style-analyst SKILL.md not loaded — check skills-classpath"));
    }

    /** Lenient JSON extraction: tolerate markdown fences / leading prose around the object. */
    private JsonNode parseDraft(String content) {
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
            return node;
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static java.util.UUID parseMediaId(String mediaId) {
        if (mediaId == null || mediaId.isBlank()) {
            return null;
        }
        try {
            return java.util.UUID.fromString(mediaId.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }
}
