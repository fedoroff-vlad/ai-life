package dev.fedorov.ailife.agents.inventory.pack;

import dev.fedorov.ailife.agentruntime.http.CaptionClient;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.inventory.http.InventoryClient;
import dev.fedorov.ailife.agents.inventory.label.BoxLabeler;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.inventory.ContainerDto;
import dev.fedorov.ailife.contracts.inventory.SaveContainerInput;
import dev.fedorov.ailife.contracts.inventory.SaveZoneInput;
import dev.fedorov.ailife.contracts.inventory.StorageZoneDto;
import dev.fedorov.ailife.golden.GoldenLlm;
import dev.fedorov.ailife.golden.GoldenLlmTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Golden test for the {@code box-packer} <b>strict-JSON extract</b> against a real model: the session's
 * two verbs ({@code open} / {@code close}) and the fields a spoken sentence has to yield before a box
 * can exist — its label and its zone.
 *
 * <p>The defect this catches: the extract is the only thing standing between "открой коробку «кухня —
 * посуда» в кладовку" and a container row. If the model returns the whole sentence as the label, or
 * folds the zone into it, every box in the store is named wrong — and the MockWebServer tests cannot
 * see that, because they feed the parser a JSON answer written by hand.
 *
 * <p>Structure, not text: the label is asserted to name the thing without swallowing the zone, and the
 * zone is asserted by identity (the upserted name), never by the reply's wording.
 *
 * <p>Opt-in / gated via {@link GoldenLlmTest}; run with
 * {@code scripts/golden.sh -pl domains/inventory/inventory-agent -Dtest=GoldenBoxPackerTest}.
 */
@GoldenLlmTest
class GoldenBoxPackerTest {

    private static final UUID CONTAINER_ID = UUID.randomUUID();
    private static final UUID ZONE_ID = UUID.randomUUID();

    private final ObjectMapper json = new ObjectMapper();
    private final InventoryClient inventory = mock(InventoryClient.class);
    private final CaptionClient caption = mock(CaptionClient.class);
    private final BoxLabeler labeler = mock(BoxLabeler.class);
    private final AgentManifest manifest = new AgentManifest(
            "inventory", "inventory agent", "0.1.0", 8128, List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            GoldenLlm.agentBody(GoldenBoxPackerTest.class.getClassLoader()));
    private final SkillRegistry skills = new SkillRegistry(List.of(
            GoldenLlm.skill(GoldenBoxPackerTest.class.getClassLoader(),
                    "skills/inventory/box-packer/SKILL.md")));
    private final BoxPacker packer = new BoxPacker(
            GoldenLlm.client(), skills, inventory, caption, labeler, manifest, json);

    /**
     * OPEN — a spoken "открой коробку «X» в Y" must yield {@code action=open} plus a label that is the
     * box's name and a zone that is the place, each in its own field.
     */
    @Test
    void opensAContainerWithTheSpokenLabelAndZone() {
        ArgumentCaptor<SaveZoneInput> zone = ArgumentCaptor.forClass(SaveZoneInput.class);
        ArgumentCaptor<SaveContainerInput> container = ArgumentCaptor.forClass(SaveContainerInput.class);
        when(inventory.saveZone(any())).thenReturn(Mono.just(new StorageZoneDto(
                ZONE_ID, UUID.randomUUID(), null, "кладовка", "closet", null, null, Instant.now())));
        when(inventory.saveContainer(any())).thenReturn(Mono.just(container("open")));

        IntentResponse resp = packer.start(GoldenLlm.message(UUID.randomUUID(), UUID.randomUUID(),
                        "открой коробку «кухня — посуда» в кладовку"))
                .block(Duration.ofSeconds(180));

        assertThat(resp).as("null result — is llm-gateway up at %s?", GoldenLlm.gatewayUrl()).isNotNull();
        // Reaching saveContainer at all means action=open was extracted.
        verify(inventory).saveContainer(container.capture());
        verify(inventory).saveZone(zone.capture());

        assertThat(zone.getValue().name()).as("zone (degraded reply: %s)", resp.text())
                .containsIgnoringCase("кладов");
        String label = container.getValue().label();
        assertThat(label).as("no label extracted (degraded reply: %s)", resp.text()).isNotBlank();
        // The label names the BOX, not the whole sentence: the zone must not have leaked into it, or
        // every container in the store carries its location in its name.
        assertThat(label.toLowerCase()).as("label '%s' swallowed the zone", label)
                .doesNotContain("кладов");
        assertThat(label).as("label '%s' kept the command verb", label).doesNotContainIgnoringCase("открой");
        assertThat(container.getValue().zoneId()).isEqualTo(ZONE_ID);
    }

    /**
     * CLOSE — inside an open session, "закрой коробку" must yield {@code action=close}, which is what
     * flips the container to {@code packed} and releases the route-lock. A missed close leaves the
     * conversation locked to this agent, so it is the session's most expensive extraction to get wrong.
     */
    @Test
    void closesTheOpenContainer() {
        ArgumentCaptor<SaveContainerInput> saved = ArgumentCaptor.forClass(SaveContainerInput.class);
        when(inventory.saveContainer(any())).thenReturn(Mono.just(container("packed")));
        when(labeler.deliver(any(), any())).thenReturn(Mono.just(new BoxLabeler.Links(null, null)));

        IntentResponse resp = packer.resume(pending(), GoldenLlm.message(UUID.randomUUID(),
                UUID.randomUUID(), "всё, закрой коробку"), null).block(Duration.ofSeconds(180));

        assertThat(resp).as("null result — is llm-gateway up at %s?", GoldenLlm.gatewayUrl()).isNotNull();
        verify(inventory).saveContainer(saved.capture());
        assertThat(saved.getValue().status()).as("close was not extracted (reply: %s)", resp.text())
                .isEqualTo("packed");
        assertThat(saved.getValue().id()).isEqualTo(CONTAINER_ID);
        // The lock must be released, or every later message keeps landing in a box that is already taped.
        assertThat(resp.pendingAction()).as("session stayed locked after close").isNull();
        // Nothing was captioned — a close is text, not a photo.
        verify(caption, never()).caption(any(), any());
    }

    private ObjectNode pending() {
        ObjectNode pending = json.createObjectNode();
        pending.put("flow", BoxPacker.FLOW);
        pending.put("containerId", CONTAINER_ID.toString());
        pending.put("code", "B-07");
        pending.put("label", "кухня — посуда");
        pending.put("count", 2);
        return pending;
    }

    private static ContainerDto container(String status) {
        return new ContainerDto(CONTAINER_ID, UUID.randomUUID(), null, ZONE_ID, "B-07",
                "кухня — посуда", "box", "demo-b07", status, null, null, Instant.now(), null);
    }
}
