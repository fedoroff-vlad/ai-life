package dev.fedorov.ailife.agents.inventory.find;

import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.inventory.http.InventoryClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.inventory.ContainerDto;
import dev.fedorov.ailife.contracts.inventory.ItemDto;
import dev.fedorov.ailife.contracts.inventory.ItemLocationDto;
import dev.fedorov.ailife.contracts.inventory.StorageZoneDto;
import dev.fedorov.ailife.golden.GoldenLlm;
import dev.fedorov.ailife.golden.GoldenLlmTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Golden test for the {@code item-finder} <b>query distil</b> against a real model.
 *
 * <p>The defect this catches is the reason the skill exists at all: item names in the store come from
 * photo captions ("ёлочная гирлянда"), so searching the owner's *question* verbatim drags
 * interrogatives and verbs ("где", "лежат", "куда я убрал") into a trigram match against names that
 * contain none of them — diluting or killing the hit. A hand-written MockWebServer answer can never
 * show that; only a real model can.
 *
 * <p>Structure, not text: the distilled query must be non-blank, short, and free of the question
 * scaffolding, while carrying the thing itself. The store is mocked, so the assertion is on the query
 * the production parser built — never on the reply's wording.
 *
 * <p>Opt-in / gated via {@link GoldenLlmTest}; run with
 * {@code scripts/golden.sh -pl domains/inventory/inventory-agent -Dtest=GoldenItemFinderTest}.
 */
@GoldenLlmTest
class GoldenItemFinderTest {

    /** Question scaffolding that must not survive the distil — it appears in no captioned item name. */
    private static final List<String> SCAFFOLDING =
            List.of("где", "куда", "лежит", "лежат", "убрал", "найди", "?");

    private final ObjectMapper json = new ObjectMapper();
    private final InventoryClient inventory = mock(InventoryClient.class);
    private final AgentManifest manifest = new AgentManifest(
            "inventory", "inventory agent", "0.1.0", 8128, List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            GoldenLlm.agentBody(GoldenItemFinderTest.class.getClassLoader()));
    private final SkillRegistry skills = new SkillRegistry(List.of(
            GoldenLlm.skill(GoldenItemFinderTest.class.getClassLoader(),
                    "skills/inventory/item-finder/SKILL.md")));
    private final ItemFinder finder = new ItemFinder(
            new ItemQuery(GoldenLlm.client(), skills, json), inventory, manifest);

    @Test
    void distilsTheThingOutOfTheQuestion() {
        UUID household = UUID.randomUUID();
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        when(inventory.searchItems(any(), any(), any())).thenReturn(Mono.just(List.of(hit())));

        IntentResponse resp = finder.find(GoldenLlm.message(household, UUID.randomUUID(),
                "слушай, а где лежат ёлочные игрушки?")).block(Duration.ofSeconds(180));

        assertThat(resp).as("null result — is llm-gateway up at %s?", GoldenLlm.gatewayUrl()).isNotNull();
        verify(inventory).searchItems(eq(household), query.capture(), any());

        String distilled = query.getValue();
        assertThat(distilled).as("model distilled no query (degraded reply: %s)", resp.text()).isNotBlank();
        // The thing survived…
        assertThat(distilled.toLowerCase()).as("query '%s' lost the thing being looked for", distilled)
                .contains("игрушк");
        // …and the question around it did not: that is the whole job of this skill.
        for (String noise : SCAFFOLDING) {
            assertThat(distilled.toLowerCase()).as("query '%s' kept the question scaffolding '%s'",
                    distilled, noise).doesNotContain(noise);
        }
        // "1-3 words" per the SKILL contract — a long query is the raw question in disguise.
        assertThat(distilled.strip().split("\\s+")).as("query '%s' is not a short phrase", distilled)
                .hasSizeLessThanOrEqualTo(3);
        // The answer names WHERE, which is the domain's whole question.
        assertThat(resp.text()).as("the reply does not name the place").contains("B-07");
    }

    private static ItemLocationDto hit() {
        UUID containerId = UUID.randomUUID();
        return new ItemLocationDto(
                new ItemDto(UUID.randomUUID(), containerId, "media-1", "ёлочная гирлянда", null,
                        null, 1, Instant.now()),
                new ContainerDto(containerId, UUID.randomUUID(), null, UUID.randomUUID(), "B-07",
                        "новый год", "box", "demo-b07", "packed", null, null, Instant.now(), null),
                new StorageZoneDto(UUID.randomUUID(), UUID.randomUUID(), null, "кладовка", "closet",
                        null, null, Instant.now()));
    }
}
