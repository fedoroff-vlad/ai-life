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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The "analyse me" flow (ST-d/ST-g): a self-photo + the user's typed body params → a style profile
 * + a full luxury-editorial <b>Body &amp; Style Analysis</b> board (HTML), modelled on the owner's
 * reference layout (body analysis · colour strategy · silhouette/fabric strategies · what-not-to-wear
 * · styling principles · style-codes · final direction).
 *
 * <p>Pipeline: ask the shared {@code mcp-media-processing} {@code caption} tool (instruction = the
 * {@code style-analyst} SKILL.md, the user's note folded in so the model also reads typed
 * height/weight/measurements) for a structured analysis JSON → persist the profile fields via
 * {@code mcp-wardrobe}'s {@code POST /internal/profile} (keyed on household+owner=the user) → render
 * the full board through the {@link StylistRenderer} seam → store it in media-service → reply with a
 * short summary plus a link the user can open on any device. Any stage failing degrades to a friendly
 * message; the board renders only the sections the analysis actually filled.
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
                .flatMap(saved -> store(msg, mediaId, draft)
                        .map(link -> reply(summary(draft) + "\n\nПодробный анализ: " + link, model))
                        .onErrorResume(e -> {
                            log.warn("analysis render/store failed: {}", e.toString());
                            return Mono.just(reply(summary(draft)
                                    + "\n\n(HTML-страницу анализа сохранить не удалось — попробуйте позже.)", model));
                        }))
                .onErrorResume(e -> {
                    log.warn("set_style_profile failed: {}", e.toString());
                    return Mono.just(reply("Не смог сохранить профиль стиля. Попробуйте позже.", null));
                });
    }

    /** Render the analysis board, store it in media-service, return the public link. */
    private Mono<String> store(NormalizedMessage msg, String mediaId, JsonNode draft) {
        RenderedDoc doc = renderer.render(buildDoc(mediaId, draft));
        return media.upload(msg.householdId(), msg.userId(), doc.filename(), doc.mimeType(), doc.content())
                .map(this::link);
    }

    /** The schema fields the wardrobe profile persists (the board renders the fuller draft directly). */
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

    /** Build the full editorial board from the analysis draft — each block added only when present. */
    private StylistDoc buildDoc(String mediaId, JsonNode d) {
        StylistDoc.Builder b = StylistDoc.builder("Анализ внешности и стиля")
                .kicker("Structure · Balance · Intention");
        String subtitle = subtitle(d);
        if (subtitle != null) b.subtitle(subtitle);
        String photo = photoUrl(mediaId);
        if (photo != null) b.featured(photo);     // the analysed self-photo anchors the board

        List<String> bodyAnalysis = new ArrayList<>();
        addLabelled(bodyAnalysis, "Тип телосложения", text(d, "bodyType"));
        addLabelled(bodyAnalysis, "Пропорции", text(d, "proportions"));
        addLabelled(bodyAnalysis, "Костяк", text(d, "boneStructure"));
        addLabelled(bodyAnalysis, "Осанка", text(d, "posture"));
        if (!bodyAnalysis.isEmpty()) b.section("Анализ тела", bodyAnalysis);

        if (notBlank(text(d, "bodyShape"))) {
            b.section("Тип фигуры", List.of("Силуэт: " + text(d, "bodyShape") + "."));
        }

        List<String> colour = new ArrayList<>();
        if (notBlank(text(d, "colourType"))) {
            StringBuilder c = new StringBuilder("Цветотип: ").append(text(d, "colourType"));
            if (notBlank(text(d, "undertone"))) c.append(", подтон ").append(text(d, "undertone"));
            if (notBlank(text(d, "contrast"))) c.append(", контраст ").append(text(d, "contrast"));
            colour.add(c.append(".").toString());
        }
        if (notBlank(text(d, "notes"))) colour.add(text(d, "notes"));
        if (!colour.isEmpty()) b.section("Цветотип", colour);
        for (JsonNode s : array(d, "palette")) {
            if (notBlank(text(s, "hex"))) b.swatch(text(s, "hex"), text(s, "name"));
        }

        List<String> silhouettes = new ArrayList<>();
        for (JsonNode n : array(d, "silhouettes")) {
            if (n.isObject()) {
                StringBuilder s = new StringBuilder(text(n, "name") == null ? "" : text(n, "name"));
                if (notBlank(text(n, "note"))) s.append(" — ").append(text(n, "note"));
                if (notBlank(text(n, "harmony"))) s.append(" (гармония: ").append(text(n, "harmony")).append(")");
                if (s.length() > 0) silhouettes.add(s.toString());
            } else if (notBlank(n.asText())) {
                silhouettes.add(n.asText());
            }
        }
        if (notBlank(text(d, "waist"))) silhouettes.add("Талия: " + text(d, "waist") + ".");
        String necklines = joinStrings(array(d, "necklines"));
        if (necklines != null) silhouettes.add("Вырезы: " + necklines + ".");
        if (!silhouettes.isEmpty()) b.section("Силуэты, что работают", silhouettes);

        List<String> fabrics = new ArrayList<>();
        for (JsonNode n : array(d, "fabricLogic")) {
            if (notBlank(n.asText())) fabrics.add(n.asText());
        }
        String suitable = joinStrings(array(d, "suitableFabrics"));
        if (suitable != null) fabrics.add("Вам подходят: " + suitable + ".");
        if (!fabrics.isEmpty()) b.section("Ткани и текстуры", fabrics);

        List<String> avoid = stringList(array(d, "avoid"));
        if (!avoid.isEmpty()) b.section("Чего избегать", avoid);

        List<String> principles = stringList(array(d, "stylingPrinciples"));
        if (!principles.isEmpty()) b.section("Принципы стиля", principles);

        List<String> codes = new ArrayList<>();
        for (JsonNode n : array(d, "styleCodes")) {
            if (n.isObject()) {
                StringBuilder c = new StringBuilder(text(n, "code") == null ? "" : text(n, "code"));
                if (notBlank(text(n, "look"))) c.append(" — ").append(text(n, "look"));
                if (c.length() > 0) codes.add(c.toString());
            } else if (notBlank(n.asText())) {
                codes.add(n.asText());
            }
        }
        if (!codes.isEmpty()) b.section("Стиль-коды", codes);

        String params = bodyParams(d);
        if (params != null) b.section("Параметры тела", List.of(params));

        List<String> finalDir = new ArrayList<>();
        if (notBlank(text(d, "finalDirection"))) finalDir.add(text(d, "finalDirection"));
        if (notBlank(text(d, "philosophy"))) finalDir.add("«" + text(d, "philosophy") + "»");
        if (!finalDir.isEmpty()) b.section("Итог", finalDir);

        return b.build();
    }

    private static String subtitle(JsonNode d) {
        List<String> parts = new ArrayList<>();
        if (notBlank(text(d, "kibbe"))) parts.add("Кибби: " + text(d, "kibbe"));
        if (notBlank(text(d, "colourType"))) parts.add("Цветотип: " + text(d, "colourType"));
        if (notBlank(text(d, "archetype"))) parts.add("Архетип: " + text(d, "archetype"));
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    private static String bodyParams(JsonNode d) {
        List<String> parts = new ArrayList<>();
        if (d.hasNonNull("heightCm")) parts.add("рост " + d.get("heightCm").asInt() + " см");
        if (d.hasNonNull("weightKg")) parts.add("вес " + d.get("weightKg").asText() + " кг");
        JsonNode m = d.get("measurements");
        if (m != null && m.isObject() && !m.isEmpty()) {
            List<String> mm = new ArrayList<>();
            m.fields().forEachRemaining(e -> mm.add(e.getKey() + " " + e.getValue().asText()));
            if (!mm.isEmpty()) parts.add("мерки: " + String.join(", ", mm));
        }
        return parts.isEmpty() ? null : capitalize(String.join(", ", parts)) + ".";
    }

    private String summary(JsonNode d) {
        StringBuilder sb = new StringBuilder("Готов анализ вашего стиля.");
        if (notBlank(text(d, "colourType"))) sb.append(" Цветотип: ").append(text(d, "colourType")).append(".");
        if (notBlank(text(d, "bodyShape"))) sb.append(" Фигура: ").append(text(d, "bodyShape")).append(".");
        return sb.toString();
    }

    private String link(MediaObjectDto stored) {
        return base() + "/v1/media/" + stored.id();
    }

    /** Public URL of the analysed self-photo — the board's centered anchor. */
    private String photoUrl(String mediaId) {
        return (mediaId == null || mediaId.isBlank()) ? null : base() + "/v1/media/" + mediaId.trim();
    }

    private String base() {
        String base = props.getPublicMediaBaseUrl();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
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

    private static JsonNode array(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v != null && v.isArray()) ? v : com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static List<String> stringList(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : array) {
            if (notBlank(n.asText())) out.add(n.asText());
        }
        return out;
    }

    private static String joinStrings(JsonNode array) {
        List<String> out = stringList(array);
        return out.isEmpty() ? null : String.join(", ", out);
    }

    private static void addLabelled(List<String> out, String label, String value) {
        if (notBlank(value)) out.add(label + ": " + value + ".");
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        return (v != null && !v.isNull()) ? v.asText() : null;
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
