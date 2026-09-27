package dev.fedorov.ailife.agents.inventory.pack;

import dev.fedorov.ailife.agentruntime.http.CaptionClient;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.inventory.container.ContainerResolver;
import dev.fedorov.ailife.agents.inventory.find.ItemNotes;
import dev.fedorov.ailife.agents.inventory.http.InventoryClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.inventory.ContainerDto;
import dev.fedorov.ailife.contracts.inventory.ItemDto;
import dev.fedorov.ailife.contracts.inventory.SaveItemInput;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Putting one thing into a box that is already closed (IN-g3): a photo captioned "добавь в B-07
 * гирлянду", with <b>no packing session</b>.
 *
 * <p>Why it is not just "reopen the box": after a move most boxes are taped shut, and the owner adds to
 * them one thing at a time — a charger found behind the sofa, a lid that turned up later. Opening a
 * session for a single item would route-lock the conversation to this agent until the owner remembered
 * to close it again, which is the opposite of hands-free. So this path is deliberately <b>stateless</b>:
 * one photo, one caption, one item, no lock.
 *
 * <p>The caption carries both halves, so one strict-JSON turn splits it (the {@code box-append} SKILL):
 * <b>which</b> container, and <b>what</b> the thing is. The container is matched by the shared
 * {@link ContainerResolver} (code first, label second). A caption that names no box, or one that matches
 * nothing, is <b>answered, never guessed</b> — a thing filed into the wrong box is found months later by
 * accident, which is the failure this whole domain exists to prevent.
 *
 * <p>Naming follows the session's rule: the owner's own words win, and vision only fills the gap when
 * the caption just points at a box ("это в B-07"). Captioning soft-fails to an unnamed item — the photo
 * IS the record, so losing it would be the real failure.
 */
@Component
public class BoxAppender {

    private static final String SKILL_NAME = "box-append";
    private static final String CAPTION_INSTRUCTION = """
            Name the single object in this photo as a person would when looking for it later: a short
            noun phrase of 2-5 words, in Russian, plus its obvious colour or material if visible.
            Answer with the name only — no sentence, no explanation.""";

    private static final Logger log = LoggerFactory.getLogger(BoxAppender.class);

    private final LlmClient llm;
    private final SkillRegistry skills;
    private final InventoryClient inventory;
    private final ContainerResolver containers;
    private final CaptionClient caption;
    private final ItemNotes notes;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public BoxAppender(LlmClient llm, SkillRegistry skills, InventoryClient inventory,
                       ContainerResolver containers, CaptionClient caption, ItemNotes notes,
                       AgentManifest manifest, ObjectMapper json) {
        this.llm = llm;
        this.skills = skills;
        this.inventory = inventory;
        this.containers = containers;
        this.caption = caption;
        this.notes = notes;
        this.manifest = manifest;
        this.json = json;
    }

    /**
     * File a captioned photo into the box its caption names. {@code mediaId} is the photo; the caller has
     * already established there is no open session (an open one route-locks photos to {@code /resume}).
     */
    public Mono<IntentResponse> append(NormalizedMessage msg, String mediaId) {
        return split(msg)
                .flatMap(parts -> containers.byPhrase(msg.householdId(), parts.container())
                        .flatMap(found -> found
                                .map(container -> save(container, mediaId, parts.title()))
                                .orElseGet(() -> Mono.just(reply(askWhich(parts.container()))))))
                .onErrorResume(e -> {
                    log.warn("box-append failed: {}", e.toString());
                    return Mono.just(reply("Не получилось добавить это в коробку. Попробуйте ещё раз."));
                });
    }

    private Mono<IntentResponse> save(ContainerDto container, String mediaId, String title) {
        return titleFor(mediaId, title)
                .flatMap(name -> inventory.saveItem(new SaveItemInput(
                                container.id(), mediaId, blankToNull(name), null, null, null))
                        // IN-e2: seed the thing into the second brain so it is findable by meaning too.
                        // Filed under the *container's* household — a family box's contents belong to it,
                        // not to whoever happened to photograph them. Soft-fails inside.
                        .flatMap(item -> notes.seed(container.householdId(), container.ownerId(), item,
                                container.id()).thenReturn(item))
                        .map(item -> reply(addedText(item, container))
                                .withTrace("wrote: added an item to an existing container")))
                .onErrorResume(e -> {
                    log.warn("saving an appended item failed: {}", e.toString());
                    return Mono.just(reply("Не смог сохранить это фото. Попробуйте ещё раз."));
                });
    }

    /** The owner's words win; vision fills the gap and soft-fails to an unnamed item. */
    private Mono<String> titleFor(String mediaId, String fromCaption) {
        if (fromCaption != null && !fromCaption.isBlank()) {
            return Mono.just(fromCaption.trim());
        }
        return caption.caption(mediaId, CAPTION_INSTRUCTION)
                .map(r -> r.text() == null ? "" : r.text().strip())
                .onErrorResume(e -> {
                    log.debug("caption failed, saving the item unnamed: {}", e.toString());
                    return Mono.just("");
                });
    }

    /** One strict-JSON turn: the caption split into the box and the thing. */
    private Mono<Parts> split(NormalizedMessage msg) {
        String raw = msg.text() == null ? "" : msg.text().trim();
        if (raw.isBlank()) {
            return Mono.just(new Parts(null, null));
        }
        LlmChatRequest request = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(skillBody()),
                LlmMessage.user(raw)), 0.0);
        return llm.chat(request)
                .map(r -> {
                    JsonNode draft = parse(r.content());
                    return new Parts(text(draft, "container"), text(draft, "title"));
                })
                .onErrorResume(e -> {
                    log.debug("append split failed: {}", e.toString());
                    return Mono.just(new Parts(null, null));
                });
    }

    /** The caption's two halves; either may be absent (no box named / the box only). */
    private record Parts(String container, String title) {
    }

    private static String askWhich(String needle) {
        return needle == null || needle.isBlank()
                ? "В какую коробку это положить? Назовите её код (например B-07) или название — "
                        + "или скажите «открой коробку …», если собираете новую."
                : "Не нашёл коробку «" + needle + "». Назовите её код (например B-07) или название.";
    }

    private static String addedText(ItemDto item, ContainerDto container) {
        String name = (item.title() == null || item.title().isBlank())
                ? "Вещь без названия" : item.title();
        return "Добавил «" + name + "» в коробку " + name(container) + ".";
    }

    private static String name(ContainerDto container) {
        String code = container.code() == null ? "" : container.code();
        return (container.label() == null || container.label().isBlank())
                ? code : code + " «" + container.label() + "»";
    }

    private IntentResponse reply(String text) {
        return new IntentResponse(manifest.name(), text, null);
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        SKILL_NAME + " SKILL.md not loaded — check skills-classpath"));
    }

    /** Lenient JSON extraction: tolerate markdown fences / leading prose around the object. */
    private JsonNode parse(String content) {
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
            return node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String s = node.get(field).asString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
