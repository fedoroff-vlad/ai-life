package dev.fedorov.ailife.agents.inventory.edit;

import dev.fedorov.ailife.agentruntime.intent.CandidateView;
import dev.fedorov.ailife.agentruntime.intent.Nouns;
import dev.fedorov.ailife.agentruntime.intent.PickConfirmActRunner;
import dev.fedorov.ailife.agentruntime.intent.TargetedActionFlow;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.inventory.find.ItemQuery;
import dev.fedorov.ailife.agents.inventory.http.InventoryClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.inventory.ItemLocationDto;
import dev.fedorov.ailife.llm.LlmClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.UUID;

/**
 * Taking a thing out of its box (IN-g2): "убери оттуда гирлянду", "выкинул старый чайник — удали его".
 *
 * <p>The counterpart of the packing session: things leave boxes too (thrown out, given away, simply
 * mis-filed), and an inventory that can only grow stops matching the shelf it describes. A plain
 * <b>delete</b> flow on the shared {@link PickConfirmActRunner} (ADR-0004), so the wording comes free
 * from the runner's {@code NounPhrasing} — this adapter is the candidate pool, the view and the act.
 *
 * <p>The candidate pool is the <b>trigram search over the distilled phrase</b>, not the whole store: a
 * household after a move holds hundreds of things, which neither fits the runner's candidate cap nor a
 * model's attention. The distil is the shared {@link ItemQuery} — the same reason {@code item-finder}
 * needs it (names came from photo captions, so the sentence's verbs match nothing).
 *
 * <p>Deletion stays confirm-gated because it is the one irreversible act here: an item deleted by
 * mistake takes its photo with it, and the owner finds out months later when the box no longer lists
 * what is in it.
 */
@Component
public class ItemRemover
        implements TargetedActionFlow<ItemLocationDto>, CandidateView<ItemLocationDto> {

    public static final String SKILL_NAME = "item-remover";
    /** pendingAction discriminator the inventory ResumeController dispatches on. */
    public static final String FLOW = "item-remove-confirm";

    /** The runner caps candidates at 40; ask for a comparable slice of the search. */
    private static final int SEARCH_LIMIT = 40;

    private final ItemQuery query;
    private final InventoryClient inventory;
    private final PickConfirmActRunner<ItemLocationDto> runner;

    public ItemRemover(LlmClient llm, ItemQuery query, InventoryClient inventory, SkillRegistry skills,
                       AgentManifest manifest, ObjectMapper json) {
        this.query = query;
        this.inventory = inventory;
        this.runner = new PickConfirmActRunner<>(llm, manifest, skills, json, this);
    }

    /** Turn 1: search for the thing, let the LLM pick which one, and ask to confirm. */
    public Mono<IntentResponse> remove(NormalizedMessage msg) {
        return runner.pick(msg);
    }

    /** Turn 2: an affirmative deletes the item; anything else leaves it in its box. */
    public Mono<IntentResponse> resume(ResumeRequest req) {
        return runner.resume(req);
    }

    // ----- TargetedActionFlow -----------------------------------------------------------------------

    @Override
    public String skillName() {
        return SKILL_NAME;
    }

    @Override
    public String flow() {
        return FLOW;
    }

    @Override
    public Nouns nouns() {
        return new Nouns("вещь", "вещей", "вещь");
    }

    /** Only the things whose names match what the owner said — the store is far too big to hand over whole. */
    @Override
    public Mono<List<ItemLocationDto>> candidates(NormalizedMessage msg) {
        return query.distil(msg)
                .flatMap(phrase -> inventory.searchItems(msg.householdId(), phrase, SEARCH_LIMIT));
    }

    @Override
    public CandidateView<ItemLocationDto> view() {
        return this;
    }

    @Override
    public Mono<Void> act(UUID targetId, JsonNode pending) {
        return inventory.deleteItem(targetId);
    }

    // ----- CandidateView ----------------------------------------------------------------------------

    @Override
    public UUID id(ItemLocationDto hit) {
        return hit.item().id();
    }

    /** The label the confirm question shows: the thing, plus the box it is in (two boxes can hold one name). */
    @Override
    public String label(ItemLocationDto hit) {
        String code = hit.container() == null ? null : hit.container().code();
        return title(hit) + (code == null ? "" : " из " + code);
    }

    @Override
    public void describe(ObjectNode node, ItemLocationDto hit) {
        node.put("title", title(hit));
        if (hit.container() != null && hit.container().code() != null) {
            node.put("container", hit.container().code());
        }
        if (hit.zone() != null && hit.zone().name() != null) {
            node.put("zone", hit.zone().name());
        }
    }

    private static String title(ItemLocationDto hit) {
        String title = hit.item() == null ? null : hit.item().title();
        return (title == null || title.isBlank()) ? "вещь без названия" : title.strip();
    }
}
