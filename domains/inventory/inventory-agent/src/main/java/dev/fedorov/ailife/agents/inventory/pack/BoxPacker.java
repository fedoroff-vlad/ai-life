package dev.fedorov.ailife.agents.inventory.pack;

import dev.fedorov.ailife.agentruntime.http.CaptionClient;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.inventory.http.InventoryClient;
import dev.fedorov.ailife.agents.inventory.label.BoxLabeler;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.inventory.ContainerDto;
import dev.fedorov.ailife.contracts.inventory.ItemDto;
import dev.fedorov.ailife.contracts.inventory.SaveContainerInput;
import dev.fedorov.ailife.contracts.inventory.SaveItemInput;
import dev.fedorov.ailife.contracts.inventory.SaveZoneInput;
import dev.fedorov.ailife.contracts.inventory.StorageZoneDto;
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
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The packing session (IN-c): "открой коробку «кухня — посуда» в кладовку" → every following photo
 * becomes an item in <b>that</b> container → "закрой коробку" ends it.
 *
 * <p>The session is the shipped conversation <b>route-lock</b>, not a new mechanism: each turn returns
 * a {@code pendingAction}, so the orchestrator keeps routing the next message straight back to this
 * agent's {@code /resume} until the box is closed. That is what makes a batch of photos work without
 * asking "which box?" per photo — packing is a batch activity, not a form per item.
 *
 * <p>A thing's name comes from the shared vision capability ({@code caption}), so the owner types
 * nothing while their hands are full. Captioning soft-fails: an unnamed item is still recorded with
 * its photo (the photo IS the record) — losing the picture would be the real failure.
 */
@Component
public class BoxPacker {

    /** The pendingAction discriminator this agent's /resume dispatches on. */
    public static final String FLOW = "box-packing";

    private static final String SKILL_NAME = "box-packer";
    private static final String CAPTION_INSTRUCTION = """
            Name the single object in this photo as a person would when looking for it later: a short
            noun phrase of 2-5 words, in Russian, plus its obvious colour or material if visible.
            Answer with the name only — no sentence, no explanation.""";

    private static final Logger log = LoggerFactory.getLogger(BoxPacker.class);

    private final LlmClient llm;
    private final SkillRegistry skills;
    private final InventoryClient inventory;
    private final CaptionClient caption;
    private final BoxLabeler labeler;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public BoxPacker(LlmClient llm, SkillRegistry skills, InventoryClient inventory,
                     CaptionClient caption, BoxLabeler labeler, AgentManifest manifest,
                     ObjectMapper json) {
        this.llm = llm;
        this.skills = skills;
        this.inventory = inventory;
        this.caption = caption;
        this.labeler = labeler;
        this.manifest = manifest;
        this.json = json;
    }

    // ── entry points ─────────────────────────────────────────────────────────────────────────────

    /** A text message routed here with no session open: open a container, or explain. */
    public Mono<IntentResponse> start(NormalizedMessage msg) {
        return classify(msg).flatMap(draft -> {
            String action = text(draft, "action");
            if ("open".equals(action)) {
                return open(msg, draft);
            }
            if ("close".equals(action)) {
                return Mono.just(reply("Сейчас нет открытой коробки. Скажите «открой коробку …», "
                        + "чтобы начать упаковку."));
            }
            return Mono.just(reply("Чтобы начать, скажите «открой коробку «название» в кладовку» — "
                    + "дальше просто шлите фото вещей."));
        }).onErrorResume(e -> {
            log.warn("box-packer start failed: {}", e.toString());
            return Mono.just(reply("Не получилось открыть коробку. Попробуйте ещё раз."));
        });
    }

    /** A photo arrived with no session open — never guess which container it belongs to. */
    public Mono<IntentResponse> photoWithoutSession() {
        return Mono.just(reply("Не знаю, в какую коробку это положить. Сначала скажите "
                + "«открой коробку «название» в кладовку», потом шлите фото."));
    }

    /** The route-locked turn: a photo goes into the open container; text may close it. */
    public Mono<IntentResponse> resume(JsonNode pending, NormalizedMessage msg, String mediaId) {
        UUID containerId = uuid(pending, "containerId");
        if (containerId == null) {
            return Mono.just(reply("Сессия упаковки потерялась. Откройте коробку заново."));
        }
        if (mediaId != null) {
            return addItem(pending, containerId, msg, mediaId);
        }
        return classify(msg).flatMap(draft -> {
            String action = text(draft, "action");
            if ("close".equals(action)) {
                return close(pending, containerId, msg);
            }
            if ("open".equals(action)) {
                return Mono.just(keepPacking(pending,
                        "Сейчас открыта коробка " + label(pending) + ". Закройте её («закрой коробку»), "
                                + "прежде чем начинать новую."));
            }
            return Mono.just(keepPacking(pending,
                    "Коробка " + label(pending) + " открыта — шлите фото вещей или скажите «закрой коробку»."));
        }).onErrorResume(e -> {
            log.warn("box-packer resume failed: {}", e.toString());
            return Mono.just(keepPacking(pending, "Не расслышал. Шлите фото или скажите «закрой коробку»."));
        });
    }

    // ── the three moves ──────────────────────────────────────────────────────────────────────────

    private Mono<IntentResponse> open(NormalizedMessage msg, JsonNode draft) {
        String label = text(draft, "label");
        String zoneName = text(draft, "zone");
        String kind = text(draft, "kind");
        String destination = text(draft, "destination");

        return resolveZone(msg, zoneName)
                .flatMap(zoneId -> inventory.saveContainer(new SaveContainerInput(
                                null, msg.householdId(), msg.userId(), zoneId.orElse(null), null, label,
                                kind, null, destination, null))
                        .map(container -> {
                            ObjectNode pending = pending(container, 0);
                            String where = zoneName == null ? "" : " в «" + zoneName + "»";
                            return new IntentResponse(manifest.name(),
                                    "Открыл коробку " + container.code()
                                            + (label == null ? "" : " «" + label + "»") + where
                                            + ". Шлите фото вещей — каждое попадёт в неё. "
                                            + "Когда закончите, скажите «закрой коробку».",
                                    null, pending)
                                    .withTrace("wrote: opened a storage container");
                        }));
    }

    /**
     * A zone is optional — a container with no zone is still a container (it can be placed later), so a
     * missing name or a zone-service hiccup must not block the packing session from starting.
     */
    private Mono<Optional<UUID>> resolveZone(NormalizedMessage msg, String zoneName) {
        if (zoneName == null || zoneName.isBlank()) {
            return Mono.just(Optional.empty());
        }
        return inventory.saveZone(new SaveZoneInput(
                        msg.householdId(), msg.userId(), zoneName, null, null, null))
                .map(zone -> Optional.of(zone.id()))
                .onErrorResume(e -> {
                    log.debug("zone resolve failed, opening the container without a zone: {}", e.toString());
                    return Mono.just(Optional.empty());
                });
    }

    private Mono<IntentResponse> addItem(JsonNode pending, UUID containerId,
                                         NormalizedMessage msg, String mediaId) {
        String userHint = msg.text() == null || msg.text().isBlank() ? null : msg.text().trim();
        Mono<String> title = userHint != null
                ? Mono.just(userHint)                       // the user named it — trust them over the model
                : caption.caption(mediaId, CAPTION_INSTRUCTION)
                        .map(r -> clean(r.text()))
                        .onErrorResume(e -> {
                            log.debug("caption failed, saving the item unnamed: {}", e.toString());
                            return Mono.just("");
                        });

        return title.flatMap(name -> inventory.saveItem(new SaveItemInput(
                        containerId, mediaId, blankToNull(name), null, null, null))
                .map(item -> {
                    int count = pending.path("count").asInt(0) + 1;
                    return new IntentResponse(manifest.name(), addedText(item, count), null,
                            pending(pending, count))
                            .withTrace("wrote: added an item to the open container");
                }))
                .onErrorResume(e -> {
                    log.warn("saving a packed item failed: {}", e.toString());
                    // Keep the session open — the owner is mid-batch; losing the lock costs more than the item.
                    return Mono.just(keepPacking(pending, "Не смог сохранить это фото. Попробуйте ещё раз."));
                });
    }

    /**
     * Closing is also when the two deliverables are issued (IN-d): the owner has a taped-up box in
     * front of them and needs the sticker for it plus the card a scan will show. Both soft-fail inside
     * {@link BoxLabeler#deliver} — the container is already {@code packed} in the store, so a media
     * hiccup may cost a link, never the close.
     */
    private Mono<IntentResponse> close(JsonNode pending, UUID containerId, NormalizedMessage msg) {
        int count = pending.path("count").asInt(0);
        return inventory.saveContainer(new SaveContainerInput(
                        containerId, null, null, null, null, null, null, "packed", null, null))
                .flatMap(container -> labeler.deliver(msg, container)
                        .map(links -> new IntentResponse(manifest.name(),
                                "Коробка " + container.code() + (container.label() == null
                                        ? "" : " «" + container.label() + "»")
                                        + " закрыта. Внутри " + itemsWord(count) + "."
                                        + BoxLabeler.linksText(links),
                                null, null)
                                .withTrace("wrote: closed a storage container")))
                .onErrorResume(e -> {
                    log.warn("closing the container failed: {}", e.toString());
                    return Mono.just(keepPacking(pending, "Не смог закрыть коробку. Попробуйте ещё раз."));
                });
    }

    // ── LLM + helpers ────────────────────────────────────────────────────────────────────────────

    /** One strict-JSON turn over the box-packer SKILL. temperature=0 — this is parsing, not writing. */
    private Mono<JsonNode> classify(NormalizedMessage msg) {
        LlmChatRequest request = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(skillBody()),
                LlmMessage.user(msg.text() == null ? "" : msg.text())), 0.0);
        return llm.chat(request).map(r -> {
            JsonNode draft = parse(r.content());
            return draft == null ? json.createObjectNode() : draft;
        });
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "box-packer SKILL.md not loaded — check skills-classpath"));
    }

    /** Lenient JSON extraction: tolerate markdown fences / leading prose around the object. */
    private JsonNode parse(String content) {
        if (content == null) return null;
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JsonNode node = json.readTree(content.substring(start, end + 1));
            return node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** The session envelope the orchestrator hands back on the next turn. */
    private ObjectNode pending(ContainerDto container, int count) {
        ObjectNode node = json.createObjectNode();
        node.put("flow", FLOW);
        node.put("containerId", container.id().toString());
        node.put("code", container.code());
        if (container.label() != null) node.put("label", container.label());
        node.put("count", count);
        return node;
    }

    /** Same session, new item count — re-locks the route for the next photo. */
    private ObjectNode pending(JsonNode previous, int count) {
        ObjectNode node = previous.isObject()
                ? (ObjectNode) previous.deepCopy()
                : json.createObjectNode();
        node.put("count", count);
        return node;
    }

    private IntentResponse keepPacking(JsonNode pending, String text) {
        return new IntentResponse(manifest.name(), text, null, pending.deepCopy());
    }

    private IntentResponse reply(String text) {
        return new IntentResponse(manifest.name(), text, null);
    }

    private static String addedText(ItemDto item, int count) {
        String what = item.title() == null || item.title().isBlank() ? "фото" : "«" + item.title() + "»";
        return "Добавил " + what + " — в коробке " + itemsWord(count) + ".";
    }

    private static String itemsWord(int count) {
        int tail = count % 100;
        if (tail >= 11 && tail <= 14) return count + " предметов";
        return switch (count % 10) {
            case 1 -> count + " предмет";
            case 2, 3, 4 -> count + " предмета";
            default -> count + " предметов";
        };
    }

    private static String label(JsonNode pending) {
        String label = pending.path("label").asString(null);
        String code = pending.path("code").asString(null);
        if (label == null) return code == null ? "" : code;
        return code == null ? "«" + label + "»" : code + " «" + label + "»";
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        String value = node.path(field).asString(null);
        return blankToNull(value);
    }

    private static UUID uuid(JsonNode node, String field) {
        String raw = node == null ? null : node.path(field).asString(null);
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A caption model sometimes answers with a sentence or quotes — keep the first clean line. */
    private static String clean(String raw) {
        if (raw == null) return "";
        String first = raw.strip().lines().findFirst().orElse("").strip();
        first = first.replaceAll("^[\"«']+|[\"»'.]+$", "").strip();
        return first.length() > 120 ? first.substring(0, 120).strip() : first;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
