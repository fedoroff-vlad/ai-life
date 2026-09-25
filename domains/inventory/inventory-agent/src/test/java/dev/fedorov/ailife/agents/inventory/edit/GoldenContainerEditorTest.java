package dev.fedorov.ailife.agents.inventory.edit;

import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.inventory.http.InventoryClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.inventory.ContainerDto;
import dev.fedorov.ailife.golden.GoldenLlm;
import dev.fedorov.ailife.golden.GoldenLlmTest;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Golden test for the {@code box-editor} <b>pick + changed-fields extract</b> against a real model — the
 * fifth LLM seam of the domain (IN-g1).
 *
 * <p>The defects this catches are the two that would make a correction silently do nothing, and neither
 * is visible to a MockWebServer test that hands the parser a JSON answer written by hand:
 * <ul>
 *   <li>the model answering with a <b>Russian</b> state ("распакована") instead of one of the store's
 *       four — the adapter drops an unknown status on purpose, so the owner would be told there is
 *       "nothing to change" after explicitly saying they unpacked the box;</li>
 *   <li>the model writing a rename into <b>{@code label}</b> (how the box is called today) instead of
 *       {@code newLabel} — which the runner also uses for the display label, so a plain move would look
 *       like a rename to its own name.</li>
 * </ul>
 *
 * <p>Structure, not text: the assertions are on the {@code pendingAction} the production runner built —
 * which box was picked (identity, with two candidates so a mismatch cannot pass by luck) and which field
 * carries the change. The confirm turn writes nothing, so no store mock beyond the candidate read is
 * needed.
 *
 * <p>Opt-in / gated via {@link GoldenLlmTest}; run with
 * {@code scripts/golden.sh -pl domains/inventory/inventory-agent -Dtest=GoldenContainerEditorTest}.
 */
@GoldenLlmTest
class GoldenContainerEditorTest {

    // Deliberately NOT the boxes the SKILL's examples name: a fixture that reuses the example's own
    // literals can be passed by copying it, which is how a golden ends up proving nothing.
    private static final UUID TOYS = UUID.randomUUID();
    private static final UUID CLOTHES = UUID.randomUUID();

    private final ObjectMapper json = new ObjectMapper();
    private final InventoryClient inventory = mock(InventoryClient.class);
    private final AgentManifest manifest = new AgentManifest(
            "inventory", "inventory agent", "0.1.0", 8128, List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            GoldenLlm.agentBody(GoldenContainerEditorTest.class.getClassLoader()));
    private final SkillRegistry skills = new SkillRegistry(List.of(
            GoldenLlm.skill(GoldenContainerEditorTest.class.getClassLoader(),
                    "skills/inventory/box-editor/SKILL.md")));
    private final ContainerEditor editor = new ContainerEditor(
            GoldenLlm.client(), inventory, skills, manifest, json);

    /** A move: the right box, the zone as its own field, and nothing else invented. */
    @Test
    void aMoveExtractsTheZoneForTheRightBox() {
        JsonNode pending = confirm("коробка B-09 теперь стоит в гараже");

        assertThat(pending.path("targetId").asString()).as("picked the wrong box")
                .isEqualTo(CLOTHES.toString());
        assertThat(pending.path("zone").asString().toLowerCase()).contains("гараж");
        // A move is not a rename: inventing a new name here would quietly rename the box on confirm.
        assertThat(pending.hasNonNull("newLabel")).as("a move was extracted as a rename").isFalse();
    }

    /**
     * "Распаковал" is the after-the-move verb, said in Russian. The store's states are English, and the
     * adapter drops anything else — so the model has to map it, or the correction silently does nothing.
     */
    @Test
    void unpackedMapsOntoAStatusTheStoreAccepts() {
        JsonNode pending = confirm("распаковал коробку с ёлочными игрушками");

        assertThat(pending.path("targetId").asString()).as("picked the wrong box")
                .isEqualTo(TOYS.toString());
        assertThat(pending.path("status").asString()).isEqualTo("unpacked");
    }

    /** A rename must land in {@code newLabel}, never in the display {@code label} the runner owns. */
    @Test
    void aRenameLandsInNewLabel() {
        JsonNode pending = confirm("переименуй B-03 в «новогоднее»");

        assertThat(pending.path("targetId").asString()).isEqualTo(TOYS.toString());
        assertThat(pending.path("newLabel").asString().toLowerCase()).contains("новогод");
    }

    /** One real pick turn; returns the pendingAction the production runner built (nothing is written). */
    private JsonNode confirm(String text) {
        when(inventory.listContainers(any(), any())).thenReturn(Mono.just(List.of(
                container(TOYS, "B-03", "ёлочные игрушки"),
                container(CLOTHES, "B-09", "зимняя одежда"))));

        IntentResponse resp = editor.edit(GoldenLlm.message(UUID.randomUUID(), UUID.randomUUID(), text))
                .block(Duration.ofSeconds(180));

        assertThat(resp).as("null result — is llm-gateway up at %s?", GoldenLlm.gatewayUrl()).isNotNull();
        assertThat(resp.pendingAction())
                .as("no confirm produced for «%s» — the reply was: %s", text, resp.text())
                .isNotNull();
        return resp.pendingAction();
    }

    private static ContainerDto container(UUID id, String code, String label) {
        return new ContainerDto(id, UUID.randomUUID(), null, UUID.randomUUID(), code, label, "box",
                "demo-" + code.toLowerCase(), "packed", null, null, Instant.now(), null);
    }
}
