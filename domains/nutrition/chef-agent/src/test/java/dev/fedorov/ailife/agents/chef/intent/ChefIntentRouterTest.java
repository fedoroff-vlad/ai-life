package dev.fedorov.ailife.agents.chef.intent;

import dev.fedorov.ailife.agentruntime.intent.SkillClassifier;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.chef.chat.ChefChat;
import dev.fedorov.ailife.agents.chef.flow.RecipeFinder;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.llm.LlmClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

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
 * Unit tests for {@link ChefIntentRouter} — a mock {@link LlmClient} drives each classifier branch and the
 * flows are mocked, so we assert exactly which flow the router dispatched to (the parity check for the
 * cue→classifier migration, #475). Chef has a single routable intent skill ({@code recipe-finder}); the
 * router still makes the LLM recipe-vs-chat decision (replacing the old {@code RECIPE_CUES} heuristic) and
 * soft-fails to chat on anything else.
 */
class ChefIntentRouterTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final RecipeFinder recipeFinder = mock(RecipeFinder.class);
    private final ChefChat chat = mock(ChefChat.class);
    private final ObjectMapper json = new ObjectMapper();
    private final SkillClassifier classifier = new SkillClassifier(json);
    private final AgentManifest manifest = new AgentManifest(
            "chef", "test", "0.0.1", 0, List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            "You are the chef agent for the ai-life system.");
    private final SkillRegistry skills = new SkillRegistry(List.of(
            skill("recipe-finder", "Turn a recipe request or a ration plus web hits into a short recipe card.")));

    private final ChefIntentRouter router = new ChefIntentRouter(
            llm, skills, classifier, manifest, recipeFinder, chat);

    @Test
    void routesToRecipeFinder() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"skill\",\"name\":\"recipe-finder\"}")));
        when(recipeFinder.findRecipes(any())).thenReturn(Mono.just(sentinel("recipes")));

        StepVerifier.create(router.route(msg("что приготовить из курицы?")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("recipes"))
                .verifyComplete();
        verify(recipeFinder).findRecipes(any());
        verify(chat, never()).reply(any());
    }

    @Test
    void chatDecisionFallsBackToChefChat() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"chat\",\"text\":\"Чем помочь на кухне?\"}")));
        when(chat.reply(any())).thenReturn(Mono.just(sentinel("chat")));

        StepVerifier.create(router.route(msg("спасибо, вкусно получилось!")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("chat"))
                .verifyComplete();
        verify(chat).reply(any());
        verify(recipeFinder, never()).findRecipes(any());
    }

    @Test
    void nonJsonProseFallsBackToChat() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(reply("Извините, не понял.")));
        when(chat.reply(any())).thenReturn(Mono.just(sentinel("chat")));

        StepVerifier.create(router.route(msg("???")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("chat"))
                .verifyComplete();
        verify(chat).reply(any());
    }

    @Test
    void unknownSkillNameFallsBackToChat() {
        // A skill name not in the dispatch map → the router must not dispatch it (stays total).
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"skill\",\"name\":\"meal-planner\"}")));
        when(chat.reply(any())).thenReturn(Mono.just(sentinel("chat")));

        StepVerifier.create(router.route(msg("составь рацион на неделю")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("chat"))
                .verifyComplete();
        verify(chat).reply(any());
        assertThat(router.buildClassifierPrompt())
                .contains("recipe-finder").doesNotContain("meal-planner");
    }

    @Test
    void blankMessageSkipsTheLlmAndChats() {
        when(chat.reply(any())).thenReturn(Mono.just(sentinel("chat")));

        StepVerifier.create(router.route(msg("   ")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("chat"))
                .verifyComplete();
        verify(chat).reply(any());
        verify(llm, never()).chat(any());
    }

    @Test
    void llmErrorFallsBackToChat() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.error(new RuntimeException("gateway down")));
        when(chat.reply(any())).thenReturn(Mono.just(sentinel("chat")));

        StepVerifier.create(router.route(msg("дай рецепт супа")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("chat"))
                .verifyComplete();
        verify(chat).reply(any());
    }

    private static Skill skill(String name, String description) {
        return new Skill(name, description, "0.1.0", "nutrition", List.of(), List.of("en", "ru"), "body");
    }

    private IntentResponse sentinel(String tag) {
        return new IntentResponse("chef", tag, null);
    }

    private static NormalizedMessage msg(String text) {
        return new NormalizedMessage(UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE,
                text, List.of(), "telegram", "1", Instant.now());
    }

    private static LlmChatResponse reply(String text) {
        return new LlmChatResponse("mock-large", text, "stop", new LlmUsage(10, 5, 15));
    }
}
