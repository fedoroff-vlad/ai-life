package dev.fedorov.ailife.agents.stylist.catalogue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.stylist.http.CaptionClient;
import dev.fedorov.ailife.agents.stylist.http.WardrobeClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.wardrobe.AddItemInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Turns an inbound garment photo into a catalogued wardrobe item — <b>write-immediately</b> (the
 * owner loads the whole wardrobe at once, so each photo is added without a confirm step; edits go
 * through {@code update_item} later).
 *
 * <p>Pipeline (mirror of finance's {@code receipt-parser}, MP-c): ask the shared
 * {@code mcp-media-processing} {@code caption} tool (over its {@code POST /internal/caption}
 * passthrough) for a structured garment extract — the instruction is the {@code wardrobe-cataloguer}
 * SKILL.md, so the vision call lives once in the capability-MCP → parse the JSON draft → write the
 * item via {@code mcp-wardrobe}'s {@code POST /internal/item}. The photo's media id is stored on the
 * item as {@code imageMediaId}. Any stage failing degrades to a friendly user-facing message.
 */
@Component
public class WardrobeCataloguer {

    private static final Logger log = LoggerFactory.getLogger(WardrobeCataloguer.class);
    private static final String SKILL_NAME = "wardrobe-cataloguer";

    private final CaptionClient caption;
    private final WardrobeClient wardrobe;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public WardrobeCataloguer(CaptionClient caption,
                              WardrobeClient wardrobe,
                              SkillRegistry skills,
                              AgentManifest manifest,
                              ObjectMapper json) {
        this.caption = caption;
        this.wardrobe = wardrobe;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
    }

    public Mono<IntentResponse> catalogue(NormalizedMessage msg, String mediaId) {
        return caption.caption(mediaId, captionInstruction(msg.text()))
                .flatMap(result -> write(msg, mediaId, result.text(), result.model()))
                .onErrorResume(e -> {
                    log.warn("wardrobe catalogue failed for media {}: {}", mediaId, e.toString());
                    return Mono.just(reply(
                            "Не удалось обработать фото вещи. Пришлите ещё раз почётче.", null));
                });
    }

    private Mono<IntentResponse> write(NormalizedMessage msg, String mediaId, String llmContent, String model) {
        Draft draft = parseDraft(llmContent);
        if (draft == null || blankToNull(draft.name()) == null) {
            return Mono.just(reply(
                    "Не понял, что на фото. Пришлите снимок одной вещи на нейтральном фоне.", model));
        }
        AddItemInput input = new AddItemInput(
                msg.householdId(),
                null,                       // ownerId null → household-shared (MVP)
                draft.name(),
                blankToNull(draft.category()),
                blankToNull(draft.colour()),
                blankToNull(draft.material()),
                blankToNull(draft.pattern()),
                blankToNull(draft.season()),
                blankToNull(draft.formality()),
                parseMediaId(mediaId));
        return wardrobe.add(input)
                .map(saved -> reply(successText(saved.name(), saved.category(), saved.colour()), model))
                .onErrorResume(e -> {
                    log.warn("add_item from catalogue failed: {}", e.toString());
                    return Mono.just(reply("Не смог сохранить вещь в гардероб. Попробуйте позже.", null));
                });
    }

    /**
     * The instruction handed to the capability's {@code caption} tool: the {@code wardrobe-cataloguer}
     * SKILL.md (the self-contained strict-JSON extract prompt), plus the user's own caption as a
     * trailing hint when present (e.g. "это моё зимнее пальто").
     */
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
                        "wardrobe-cataloguer SKILL.md not loaded — check skills-classpath"));
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
            return new Draft(
                    text(node, "name"),
                    text(node, "category"),
                    text(node, "colour"),
                    text(node, "material"),
                    text(node, "pattern"),
                    text(node, "season"),
                    text(node, "formality"));
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    /** The media id arrives as the attachment's storageUri (a UUID string); null if unparseable. */
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

    private static String successText(String name, String category, String colour) {
        StringBuilder sb = new StringBuilder("Добавил в гардероб: ").append(name);
        if (category != null && !category.isBlank()) sb.append(" (").append(category).append(")");
        if (colour != null && !colour.isBlank()) sb.append(", ").append(colour);
        sb.append(". Поправьте, если что-то не так.");
        return sb.toString();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }

    private record Draft(String name, String category, String colour, String material,
                         String pattern, String season, String formality) {
    }
}
