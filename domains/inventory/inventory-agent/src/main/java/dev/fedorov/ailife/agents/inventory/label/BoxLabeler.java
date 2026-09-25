package dev.fedorov.ailife.agents.inventory.label;

import dev.fedorov.ailife.agentruntime.deliver.DeliverablePublisher;
import dev.fedorov.ailife.agentruntime.http.MediaStoreClient;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.inventory.config.InventoryAgentProperties;
import dev.fedorov.ailife.agents.inventory.http.InventoryClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.inventory.ContainerDto;
import dev.fedorov.ailife.contracts.inventory.ContainerViewDto;
import dev.fedorov.ailife.contracts.inventory.ItemDto;
import dev.fedorov.ailife.contracts.inventory.StorageZoneDto;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.docrender.Doc;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * A container's two deliverables (IN-d): the <b>printable QR label</b> and the <b>container card</b>.
 *
 * <p>Both are issued when a box closes — the moment the owner has a taped-up box in front of them and
 * needs something to stick on it — and on demand afterwards ("распечатай этикетку на B-07", "что в
 * коробке B-07"). The label is a pure {@link BoxLabelImage} render stored in media-service; the card
 * is a {@code libs/doc-render} board published through the shared
 * {@link DeliverablePublisher} seam. No new renderer, no new store.
 *
 * <p>The two are deliberately separate artifacts: the label is a small thermal sticker whose whole job
 * is to carry an id, and the card is the page a scan opens. Keeping the contents off the label is what
 * makes <b>repacking a box never invalidate its printed identity</b>.
 *
 * <p>Both soft-fail on the closing path: the container is already {@code packed} in the store by then,
 * so a media/render hiccup must cost the owner a link, never the close.
 */
@Component
public class BoxLabeler {

    private static final String LABEL_SKILL = "box-label";
    private static final String CARD_SKILL = "box-card";
    /** A household has tens of containers, not thousands — one page is the whole set to match over. */
    private static final int CONTAINER_SCAN_LIMIT = 500;

    private static final Logger log = LoggerFactory.getLogger(BoxLabeler.class);

    private final LlmClient llm;
    private final SkillRegistry skills;
    private final InventoryClient inventory;
    private final MediaStoreClient media;
    private final DeliverablePublisher publisher;
    private final AgentManifest manifest;
    private final ObjectMapper json;
    private final String botUsername;

    public BoxLabeler(LlmClient llm, SkillRegistry skills, InventoryClient inventory,
                      MediaStoreClient media, DeliverablePublisher publisher, AgentManifest manifest,
                      ObjectMapper json, InventoryAgentProperties props) {
        this.llm = llm;
        this.skills = skills;
        this.inventory = inventory;
        this.media = media;
        this.publisher = publisher;
        this.manifest = manifest;
        this.json = json;
        this.botUsername = props.getTelegramBotUsername();
    }

    /** What a closing box hands the owner; either half may be null (it soft-failed). */
    public record Links(String labelUrl, String cardUrl) {
    }

    // ── the closing path (called by BoxPacker) ───────────────────────────────────────────────────

    /**
     * Issue both deliverables for a just-closed container. Never fails: a missing link degrades the
     * reply, an exception would lose the close.
     */
    public Mono<Links> deliver(NormalizedMessage msg, ContainerDto container) {
        Mono<String> label = labelUrl(msg, container).onErrorResume(e -> {
            log.warn("label issue failed for {}: {}", container.code(), e.toString());
            return Mono.empty();
        });
        Mono<String> card = view(container.id())
                .flatMap(view -> cardUrl(msg.householdId(), msg.userId(), view))
                .onErrorResume(e -> {
                    log.warn("card render failed for {}: {}", container.code(), e.toString());
                    return Mono.empty();
                });
        return Mono.zip(label.defaultIfEmpty(""), card.defaultIfEmpty(""))
                .map(t -> new Links(blankToNull(t.getT1()), blankToNull(t.getT2())));
    }

    /** The links line appended to a reply — empty when neither half made it. */
    public static String linksText(Links links) {
        StringBuilder sb = new StringBuilder();
        if (links.labelUrl() != null) {
            sb.append("\nЭтикетка для печати: ").append(links.labelUrl());
        }
        if (links.cardUrl() != null) {
            sb.append("\nЧто внутри: ").append(links.cardUrl());
        }
        return sb.toString();
    }

    // ── the on-demand paths (routed skills) ──────────────────────────────────────────────────────

    /** "распечатай этикетку на B-07" → the QR image to print. */
    public Mono<IntentResponse> label(NormalizedMessage msg) {
        return resolve(msg, LABEL_SKILL)
                .flatMap(container -> labelUrl(msg, container)
                        .map(url -> reply("Этикетка коробки " + name(container) + ": " + url
                                + "\nНаклейте её на коробку — при сканировании откроется её карточка.")
                                .withTrace("read: issued a container label")))
                .onErrorResume(NotFound.class, e -> Mono.just(reply(notFoundText(e.needle))))
                .onErrorResume(e -> {
                    log.warn("box-label failed: {}", e.toString());
                    return Mono.just(reply("Не получилось сделать этикетку. Попробуйте ещё раз."));
                });
    }

    /** "что в коробке B-07" → the card with its contents. */
    public Mono<IntentResponse> card(NormalizedMessage msg) {
        return resolve(msg, CARD_SKILL)
                .flatMap(container -> view(container.id())
                        .flatMap(view -> cardUrl(msg.householdId(), msg.userId(), view)
                                .map(url -> reply(cardText(view) + ": " + url)
                                        .withTrace("read: rendered a container card"))))
                .onErrorResume(NotFound.class, e -> Mono.just(reply(notFoundText(e.needle))))
                .onErrorResume(e -> {
                    log.warn("box-card failed: {}", e.toString());
                    return Mono.just(reply("Не получилось показать коробку. Попробуйте ещё раз."));
                });
    }

    // ── rendering ────────────────────────────────────────────────────────────────────────────────

    private Mono<String> labelUrl(NormalizedMessage msg, ContainerDto container) {
        if (container.qrToken() == null || container.qrToken().isBlank()) {
            return Mono.error(new IllegalStateException(
                    "container " + container.code() + " has no qrToken"));
        }
        byte[] png = BoxLabelImage.png(BoxLabelImage.deepLink(botUsername, container.qrToken()));
        return media.upload(msg.householdId(), msg.userId(),
                        "box-" + container.code() + "-label.png", "image/png", png)
                .map(stored -> publisher.mediaUrl(stored.id()));
    }

    /**
     * Render + publish a container's card. Takes the ids rather than a {@link NormalizedMessage} because
     * a <b>scan</b> (IN-f) has no message behind it — it arrives as an inter-agent action — and the card
     * must be the same artifact whether it was asked for in chat or opened from a sticker.
     */
    public Mono<String> cardUrl(UUID householdId, UUID userId, ContainerViewDto view) {
        return publisher.publish(householdId, userId, board(view));
    }

    /**
     * The card: the container's identity in the header, its contents as a list, and every item's photo
     * in the gallery — the photo IS the record, so the picture is what makes the list recognisable.
     */
    private Doc board(ContainerViewDto view) {
        ContainerDto container = view.container();
        List<ItemDto> items = view.items() == null ? List.of() : view.items();

        Doc.Builder doc = Doc.builder(name(container))
                .kicker(zoneName(view.zone()))
                .subtitle(subtitle(container, items.size()));

        List<String> lines = new ArrayList<>();
        int n = 0;
        for (ItemDto item : items) {
            lines.add(++n + ". " + itemTitle(item));
            String photo = publisher.mediaUrl(item.mediaId());
            if (photo != null) doc.galleryImage(photo);
        }
        if (lines.isEmpty()) lines.add("Пока пусто — в эту коробку ещё ничего не сложили.");
        doc.section("Что внутри", lines);

        if (container.note() != null && !container.note().isBlank()) {
            doc.section("Заметка", List.of(container.note().strip()));
        }
        return doc.build();
    }

    // ── container resolution ─────────────────────────────────────────────────────────────────────

    /**
     * Which container the owner meant: one strict-JSON turn distils the code or name out of the
     * question, then the household's containers are matched on the code first (it is the printed id)
     * and on the label second. An unresolvable ask is answered, not guessed — acting on the wrong box
     * would print a wrong sticker.
     */
    private Mono<ContainerDto> resolve(NormalizedMessage msg, String skill) {
        return distil(msg, skill).flatMap(needle -> inventory
                .listContainers(msg.householdId(), CONTAINER_SCAN_LIMIT)
                .flatMap(all -> match(all, needle)
                        .map(Mono::just)
                        .orElseGet(() -> Mono.error(new NotFound(needle)))));
    }

    private static Optional<ContainerDto> match(List<ContainerDto> all, String needle) {
        String want = needle.toLowerCase(Locale.ROOT).strip();
        return all.stream()
                .filter(c -> c.code() != null && c.code().equalsIgnoreCase(want))
                .findFirst()
                .or(() -> all.stream()
                        .filter(c -> c.label() != null
                                && c.label().toLowerCase(Locale.ROOT).contains(want))
                        .findFirst());
    }

    /** The code or name the user said — falls back to the raw message when the model gives nothing. */
    private Mono<String> distil(NormalizedMessage msg, String skill) {
        String raw = msg.text() == null ? "" : msg.text().trim();
        if (raw.isBlank()) {
            return Mono.error(new NotFound(""));
        }
        LlmChatRequest request = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(skillBody(skill)),
                LlmMessage.user(raw)), 0.0);
        return llm.chat(request)
                .map(r -> {
                    JsonNode draft = parse(r.content());
                    String container = draft == null ? null : draft.path("container").asString(null);
                    return (container == null || container.isBlank()) ? raw : container.trim();
                })
                .onErrorResume(e -> {
                    log.debug("container distil failed, matching the raw text: {}", e.toString());
                    return Mono.just(raw);
                });
    }

    private Mono<ContainerViewDto> view(UUID containerId) {
        return inventory.getContainer(containerId);
    }

    /** An unresolvable container is a normal answer, not a failure — mapped to a reply below. */
    private static final class NotFound extends RuntimeException {
        private final String needle;

        NotFound(String needle) {
            super("no container matching " + needle);
            this.needle = needle;
        }
    }

    // ── text ─────────────────────────────────────────────────────────────────────────────────────

    private static String notFoundText(String needle) {
        return needle == null || needle.isBlank()
                ? "Не понял, о какой коробке речь. Назовите её код (например B-07) или название."
                : "Не нашёл коробку «" + needle + "». Назовите её код (например B-07) или название.";
    }

    private static String name(ContainerDto container) {
        String code = container.code() == null ? "" : container.code();
        return (container.label() == null || container.label().isBlank())
                ? code : code + " «" + container.label() + "»";
    }

    private static String zoneName(StorageZoneDto zone) {
        return (zone == null || zone.name() == null || zone.name().isBlank())
                ? "зона не указана" : zone.name();
    }

    private static String subtitle(ContainerDto container, int items) {
        StringBuilder sb = new StringBuilder(status(container.status())).append(" · ")
                .append(itemsWord(items));
        if (container.destination() != null && !container.destination().isBlank()) {
            sb.append(" · переезд: ").append(container.destination());
        }
        return sb.toString();
    }

    /** The one-line "which box, where, what state" header both the chat card and a scan reply use. */
    public static String cardText(ContainerViewDto view) {
        int items = view.items() == null ? 0 : view.items().size();
        return "Коробка " + name(view.container()) + ", " + zoneName(view.zone()) + ", "
                + status(view.container().status()) + ", " + itemsWord(items);
    }

    private static String status(String status) {
        if (status == null) return "статус неизвестен";
        return switch (status) {
            case "open" -> "открыта";
            case "packed" -> "упакована";
            case "in_transit" -> "в пути";
            case "unpacked" -> "распакована";
            default -> status;
        };
    }

    private static String itemTitle(ItemDto item) {
        String title = item.title();
        String name = (title == null || title.isBlank()) ? "Вещь без названия" : title.strip();
        return (item.qty() != null && item.qty() > 1) ? name + " ×" + item.qty() : name;
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

    private IntentResponse reply(String text) {
        return new IntentResponse(manifest.name(), text, null);
    }

    private String skillBody(String skillName) {
        return skills.all().stream()
                .filter(s -> skillName.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        skillName + " SKILL.md not loaded — check skills-classpath"));
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

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
