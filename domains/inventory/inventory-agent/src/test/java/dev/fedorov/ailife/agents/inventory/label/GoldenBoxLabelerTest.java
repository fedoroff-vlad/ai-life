package dev.fedorov.ailife.agents.inventory.label;

import dev.fedorov.ailife.agentruntime.deliver.DeliverablePublisher;
import dev.fedorov.ailife.agentruntime.http.MediaStoreClient;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.inventory.config.InventoryAgentProperties;
import dev.fedorov.ailife.agents.inventory.http.InventoryClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.inventory.ContainerDto;
import dev.fedorov.ailife.contracts.inventory.ContainerViewDto;
import dev.fedorov.ailife.contracts.inventory.ItemDto;
import dev.fedorov.ailife.contracts.inventory.StorageZoneDto;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import dev.fedorov.ailife.golden.GoldenLlm;
import dev.fedorov.ailife.golden.GoldenLlmTest;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Golden test for the {@code box-label} / {@code box-card} <b>container distil</b> against a real model:
 * <i>which</i> box did the owner mean?
 *
 * <p>The defect this catches is physical and expensive: a wrong distil prints a sticker for the wrong
 * box, or opens the card of a box the owner is not holding. Two shapes have to work — a bare printed
 * **code** ("B-07", which is what a person reads off the side of a box) and a **spoken name** ("кухня",
 * which is what they call it) — and the model must not answer with the whole sentence, because the
 * production matcher compares the distilled string against codes and labels.
 *
 * <p>Structure, not text: the assertion is the <b>identity</b> of the resolved container (which id was
 * read back and rendered), never the reply's wording. Two containers are in the store precisely so a
 * mismatch cannot pass by accident.
 *
 * <p>Opt-in / gated via {@link GoldenLlmTest}; run with
 * {@code scripts/golden.sh -pl domains/inventory/inventory-agent -Dtest=GoldenBoxLabelerTest}.
 */
@GoldenLlmTest
class GoldenBoxLabelerTest {

    private static final UUID B07 = UUID.randomUUID();
    private static final UUID B12 = UUID.randomUUID();

    private final ObjectMapper json = new ObjectMapper();
    private final InventoryClient inventory = mock(InventoryClient.class);
    private final MediaStoreClient media = mock(MediaStoreClient.class);
    private final DeliverablePublisher publisher = mock(DeliverablePublisher.class);
    private final AgentManifest manifest = new AgentManifest(
            "inventory", "inventory agent", "0.1.0", 8128, List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            GoldenLlm.agentBody(GoldenBoxLabelerTest.class.getClassLoader()));
    private final SkillRegistry skills = new SkillRegistry(List.of(
            GoldenLlm.skill(GoldenBoxLabelerTest.class.getClassLoader(),
                    "skills/inventory/box-label/SKILL.md"),
            GoldenLlm.skill(GoldenBoxLabelerTest.class.getClassLoader(),
                    "skills/inventory/box-card/SKILL.md")));
    private final BoxLabeler labeler = new BoxLabeler(
            GoldenLlm.client(), skills, inventory, media, publisher, manifest, json,
            new InventoryAgentProperties());

    /** A bare printed code is what a person reads off the box — it must resolve to that exact box. */
    @Test
    void aBareCodeResolvesToThatContainer() {
        stubStore();
        when(media.upload(any(), any(), any(), any(), any())).thenReturn(Mono.just(stored()));
        when(publisher.mediaUrl(any(UUID.class))).thenReturn("https://media.example/label.png");

        IntentResponse resp = labeler.label(GoldenLlm.message(UUID.randomUUID(), UUID.randomUUID(),
                "распечатай этикетку на B-12")).block(Duration.ofSeconds(180));

        assertThat(resp).as("null result — is llm-gateway up at %s?", GoldenLlm.gatewayUrl()).isNotNull();
        assertThat(resp.text()).as("no label issued (degraded reply: %s)", resp.text())
                .contains("https://media.example/label.png").contains("B-12");
        // Identity, not wording: the sticker rendered is the one whose token belongs to B-12.
        verify(media).upload(any(), any(), eq("box-B-12-label.png"), any(), any());
    }

    /** A spoken name is what the owner actually says — it must match on the label, not the code. */
    @Test
    void aSpokenNameResolvesToTheRightContainer() {
        stubStore();
        when(inventory.getContainer(any())).thenReturn(Mono.just(view()));
        when(publisher.publish(any(), any(), any())).thenReturn(Mono.just("https://media.example/card"));
        when(publisher.mediaUrl(any(String.class))).thenReturn("https://media.example/photo.jpg");

        IntentResponse resp = labeler.card(GoldenLlm.message(UUID.randomUUID(), UUID.randomUUID(),
                "покажи, что лежит в коробке с посудой")).block(Duration.ofSeconds(180));

        assertThat(resp).as("null result — is llm-gateway up at %s?", GoldenLlm.gatewayUrl()).isNotNull();
        assertThat(resp.text()).as("no card rendered (degraded reply: %s)", resp.text())
                .contains("https://media.example/card");
        // The card read back is B-07 («кухня — посуда»), not the other box in the store.
        verify(inventory).getContainer(B07);
        verify(inventory, never()).getContainer(B12);
    }

    /** Two candidates, so a distil that answers with the whole sentence cannot match by luck. */
    private void stubStore() {
        when(inventory.listContainers(any(), any())).thenReturn(Mono.just(List.of(
                container(B07, "B-07", "кухня — посуда"),
                container(B12, "B-12", "инструменты"))));
    }

    private static ContainerDto container(UUID id, String code, String label) {
        return new ContainerDto(id, UUID.randomUUID(), null, UUID.randomUUID(), code, label, "box",
                "demo-" + code.toLowerCase(), "packed", null, null, Instant.now(), null);
    }

    private static ContainerViewDto view() {
        return new ContainerViewDto(
                container(B07, "B-07", "кухня — посуда"),
                new StorageZoneDto(UUID.randomUUID(), UUID.randomUUID(), null, "кладовка", "closet",
                        null, null, Instant.now()),
                List.of(new ItemDto(UUID.randomUUID(), B07, "media-1", "чайный сервиз", null, null, 1,
                        Instant.now())));
    }

    private static MediaObjectDto stored() {
        return new MediaObjectDto(UUID.randomUUID(), UUID.randomUUID(), null, "file", "image/png",
                1024, null, "inventory", Instant.now());
    }
}
