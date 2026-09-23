package dev.fedorov.ailife.agents.inventory.find;

import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.inventory.http.InventoryClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.inventory.ContainerDto;
import dev.fedorov.ailife.contracts.inventory.ItemLocationDto;
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

import java.util.List;

/**
 * "Где лежит X" (IN-e): answers <b>where</b> a stored thing is, not just whether it exists.
 *
 * <p>One LLM turn distils the thing out of the question (the {@code item-finder} SKILL, strict JSON,
 * temperature 0) because the stored names came from photo captions — "где лежат ёлочные игрушки"
 * must search {@code ёлочные игрушки}, not the interrogatives around it. The search then returns each
 * hit already carrying its container and zone, so the reply is a place, in one round-trip.
 *
 * <p>Semantic recall over the second brain is IN-e2; this is the literal half. Scope is the envelope
 * household — the personal ∪ shared widening comes with the sharing retrofit.
 */
@Component
public class ItemFinder {

    private static final String SKILL_NAME = "item-finder";
    private static final int LIMIT = 10;
    /** Beyond a handful the reply stops being an answer and becomes a list to read. */
    private static final int SHOWN = 5;

    private static final Logger log = LoggerFactory.getLogger(ItemFinder.class);

    private final LlmClient llm;
    private final SkillRegistry skills;
    private final InventoryClient inventory;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public ItemFinder(LlmClient llm, SkillRegistry skills, InventoryClient inventory,
                      AgentManifest manifest, ObjectMapper json) {
        this.llm = llm;
        this.skills = skills;
        this.inventory = inventory;
        this.manifest = manifest;
        this.json = json;
    }

    public Mono<IntentResponse> find(NormalizedMessage msg) {
        return distil(msg)
                .flatMap(query -> inventory.searchItems(msg.householdId(), query, LIMIT)
                        .map(hits -> new IntentResponse(manifest.name(), answer(query, hits), null)
                                .withTrace("read: searched stored items")))
                .onErrorResume(e -> {
                    log.warn("item search failed: {}", e.toString());
                    return Mono.just(new IntentResponse(manifest.name(),
                            "Не получилось поискать прямо сейчас. Попробуйте ещё раз.", null));
                });
    }

    /** The search phrase — falls back to the raw message when the model returns nothing usable. */
    private Mono<String> distil(NormalizedMessage msg) {
        String raw = msg.text() == null ? "" : msg.text().trim();
        if (raw.isBlank()) {
            return Mono.just(raw);
        }
        LlmChatRequest request = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(skillBody()),
                LlmMessage.user(raw)), 0.0);
        return llm.chat(request)
                .map(r -> {
                    JsonNode draft = parse(r.content());
                    String query = draft == null ? null : draft.path("query").asString(null);
                    return (query == null || query.isBlank()) ? raw : query.trim();
                })
                .onErrorResume(e -> {
                    log.debug("query distil failed, searching the raw text: {}", e.toString());
                    return Mono.just(raw);
                });
    }

    private static String answer(String query, List<ItemLocationDto> hits) {
        if (hits == null || hits.isEmpty()) {
            return "Не нашёл «" + query + "» ни в одной коробке. "
                    + "Возможно, вещь ещё не упакована или названа иначе.";
        }
        StringBuilder sb = new StringBuilder();
        ItemLocationDto first = hits.get(0);
        sb.append(name(first)).append(" — ").append(place(first)).append('.');

        List<ItemLocationDto> rest = hits.subList(1, Math.min(hits.size(), SHOWN));
        if (!rest.isEmpty()) {
            sb.append("\nТакже похоже:");
            rest.forEach(h -> sb.append("\n• ").append(name(h)).append(" — ").append(place(h)));
        }
        if (hits.size() > SHOWN) {
            sb.append("\n… и ещё ").append(hits.size() - SHOWN);
        }
        return sb.toString();
    }

    private static String name(ItemLocationDto hit) {
        String title = hit.item() == null ? null : hit.item().title();
        return (title == null || title.isBlank()) ? "Вещь без названия" : title;
    }

    /** A container may not be placed in a zone yet — say where it is anyway. */
    private static String place(ItemLocationDto hit) {
        ContainerDto container = hit.container();
        if (container == null) {
            return "место неизвестно";
        }
        StringBuilder sb = new StringBuilder("коробка ").append(container.code());
        if (container.label() != null && !container.label().isBlank()) {
            sb.append(" «").append(container.label()).append('»');
        }
        StorageZoneDto zone = hit.zone();
        if (zone != null && zone.name() != null && !zone.name().isBlank()) {
            sb.append(", ").append(zone.name());
        } else {
            sb.append(", зона не указана");
        }
        return sb.toString();
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "item-finder SKILL.md not loaded — check skills-classpath"));
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
}
