package dev.fedorov.ailife.agents.chef.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.coordinate.Coordinator;
import dev.fedorov.ailife.agentruntime.deliver.DeliverablePublisher;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.chef.http.WebSearchClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.web.WebSearchHit;
import dev.fedorov.ailife.contracts.web.WebSearchResult;
import dev.fedorov.ailife.golden.GoldenLlm;
import dev.fedorov.ailife.golden.GoldenLlmTest;
import dev.fedorov.ailife.llm.LlmClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Stage 5 <b>golden test</b> (#199) — exercises the chef {@code recipe-finder} <b>synthesis skill</b>
 * against a <b>real model</b> (local Ollama {@code qwen2.5:7b} via a running llm-gateway), asserting
 * <b>structure, not text</b> (roadmap §Risks). Like the researcher synthesis test it covers a grounded
 * free-text skill, but the contract here is to <b>pick recipes from the provided search hits and
 * reference them by title</b>: given a fixed set of recipe hits, the real model must write a card that
 * names at least one of the actual recipes — not a dish invented out of thin air.
 *
 * <p><b>Opt-in / gated</b> via {@link GoldenLlmTest} ({@code GOLDEN_LLM}); see
 * {@code platform/llm-gateway/README.md} §Golden tests for the runbook. {@link WebSearchClient} (recipe
 * search) and {@link DeliverablePublisher} (render→store→link) are mocked; the real {@link Coordinator}
 * runs the one synthesis hop over the real AGENT.md + recipe-finder SKILL.md, and we assert the
 * synthesized card text grounds in the supplied recipes, never the wording.
 */
@GoldenLlmTest
class GoldenRecipeCardTest {

    private final ObjectMapper json = new ObjectMapper();
    private final Coordinator coordinator = new Coordinator(GoldenLlm.client(), json);
    private final WebSearchClient web = mock(WebSearchClient.class);
    private final DeliverablePublisher publisher = mock(DeliverablePublisher.class);
    private final AgentManifest manifest = new AgentManifest(
            "chef", "chef agent", "0.1.0", 8106,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            GoldenLlm.agentBody(GoldenRecipeCardTest.class.getClassLoader()));
    private final SkillRegistry skills = new SkillRegistry(List.of(
            GoldenLlm.skill(GoldenRecipeCardTest.class.getClassLoader(), "skills/nutrition/recipe-finder/SKILL.md")));
    private final RecipeFinder finder =
            new RecipeFinder(coordinator, web, publisher, skills, manifest, json);

    // The fixed recipe corpus — distinctive titles the synthesis must reference by name.
    private static final String T1 = "Курица с рисом по-восточному";
    private static final String T2 = "Плов с курицей в казане";
    private static final String T3 = "Рис с курицей и овощами на сковороде";

    /**
     * STRUCTURE — the real model, given the real recipe-finder prompt and concrete hits, must write a
     * non-trivial card that picks from the supplied recipes (references at least one by title). This is
     * the "grounded synthesis" assertion for a free-text skill — it checks the card is built from the
     * provided sources, never the wording.
     */
    @Test
    void recipeCardGroundsInTheProvidedHits() {
        var hits = List.of(
                new WebSearchHit(T1, "https://food.ru/recipes/kuritsa-ris-vostok", "Ароматная курица с рисом и специями."),
                new WebSearchHit(T2, "https://eda.ru/recepty/plov-s-kuricej", "Классический плов в казане."),
                new WebSearchHit(T3, "https://povar.ru/ris-kurica-ovoshchi", "Быстрый ужин на сковороде."));
        when(web.search(anyString(), anyInt()))
                .thenReturn(Mono.just(new WebSearchResult("курица с рисом рецепт", hits)));
        // The render→store→link is mocked away — we assert the synthesized card, not the deliverable.
        when(publisher.publish(any(), any(), any()))
                .thenReturn(Mono.just("https://media-service:8088/v1/media/abc"));

        RecipeFinder.RecipeOutcome r = finder.recommend(
                UUID.randomUUID(), UUID.randomUUID(), "что приготовить из курицы и риса?")
                .block(Duration.ofSeconds(150));

        assertThat(r).as("null outcome — is llm-gateway up at %s?", GoldenLlm.gatewayUrl()).isNotNull();
        assertThat(r.fullText()).as("empty recipe card").isNotBlank();
        assertThat(r.fullText().length())
                .as("recipe card is implausibly short: %s", r.fullText()).isGreaterThan(100);

        // Grounding: the card must pick from the SUPPLIED recipes — assert a distinctive marker from at
        // least one title survives (a 7B may shorten the full title, so we match the characteristic word,
        // not the whole string). "плов"/"казан" / "восточн" / "сковород" appear only in these hits.
        String card = r.fullText().toLowerCase();
        long distinctiveMarkers = List.of("плов", "казан", "восточн", "сковород").stream()
                .filter(card::contains).count();
        assertThat(distinctiveMarkers)
                .as("card referenced none of the provided recipes' distinctive markers (not grounded in the hits):\n%s", r.fullText())
                .isGreaterThanOrEqualTo(1);
    }
}
