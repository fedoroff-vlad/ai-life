package dev.fedorov.ailife.orchestrator.routing;

import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.golden.GoldenLlm;
import dev.fedorov.ailife.golden.GoldenLlmTest;
import dev.fedorov.ailife.llm.LlmClient;
import dev.fedorov.ailife.orchestrator.agent.AgentRegistry;
import dev.fedorov.ailife.orchestrator.agent.AgentRegistryProperties;
import dev.fedorov.ailife.orchestrator.memory.MemoryClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Stage 5 <b>golden test</b> (#199) — exercises the orchestrator's <b>top-level agent routing</b>
 * against a <b>real model</b> (local Ollama {@code qwen2.5:7b} via a running llm-gateway), asserting
 * <b>structure, not text</b> (roadmap §Risks). This is the spine the finance {@code GoldenRoutingTest}
 * sits below: before any domain skill runs, the LLM must read the real per-agent manifests (description
 * + few-shot examples) and pick the <em>right agent</em> for a message — the single decision every
 * inbound request passes through.
 *
 * <p><b>Opt-in / gated.</b> Skipped unless {@code GOLDEN_LLM} is set (CI default = unset, so the suite
 * stays green on the mock provider without a model). To run it:
 * <pre>
 *   # 1. a real model — local Ollama with qwen2.5:7b pulled (see project memory / llm-gateway README)
 *   # 2. a llm-gateway pointed at it (FAST channel resolves the classifier model):
 *   LLM_PROVIDER=openai-compatible LLM_BASE_URL=http://localhost:11434/v1 \
 *   LLM_DEFAULT_MODEL=qwen2.5:7b LLM_FAST_MODEL=qwen2.5:7b LLM_GATEWAY_PORT=8081 \
 *     mvn -q -pl platform/llm-gateway spring-boot:run
 *   # 3. the test, pointed at the gateway:
 *   GOLDEN_LLM=true GOLDEN_LLM_GATEWAY_URL=http://localhost:8081 \
 *     mvn -q -pl platform/orchestrator -Dtest=GoldenRoutingTest test
 * </pre>
 *
 * <p>The classifier is wired with the <b>real production prompt builder</b> ({@link LlmIntentClassifier})
 * over an {@link AgentRegistry} carrying the real agents' manifests (kept in sync with each {@code
 * AGENT.md} frontmatter — name + description + intent examples). Memory recall is stubbed empty so the
 * decision is driven only by the message + the manifests, never by injected long-term context.
 */
@GoldenLlmTest
class GoldenRoutingTest {

    /** The eight registered domain agents (mirrors each agent's AGENT.md frontmatter). */
    private static final Set<String> KNOWN_AGENTS = Set.of(
            "echo", "calendar", "finance", "tasks", "researcher",
            "stylist", "nutritionist", "chef", "creator");

    private final MemoryClient memory = mock(MemoryClient.class);
    private final LlmClient llm = GoldenLlm.client();
    private final LlmIntentClassifier classifier = newClassifier();

    GoldenRoutingTest() {
        // No long-term context — routing must be decided by the message + the manifests alone.
        when(memory.recall(any(), any(), any())).thenReturn(Mono.just(List.of()));
    }

    /**
     * BEHAVIOUR — unambiguous, crisp requests must reach the right agent end-to-end through the real
     * {@link LlmIntentClassifier#classify}. {@code normalize()} coerces any non-agent reply (prose,
     * hallucinated names) to {@code echo}, so a mismatch here also catches the model failing to emit a
     * bare agent token. Cases are deliberately crisp — a 7B can mis-route a borderline phrasing.
     */
    @Test
    void routesUnambiguousRequestsToTheRightAgent() {
        assertRoutesTo("Добавь встречу завтра в 15:00 про планирование", "calendar");
        assertRoutesTo("Я потратил 12 евро на кофе сегодня", "finance");
        assertRoutesTo("Напомни мне позвонить стоматологу", "tasks");
        assertRoutesTo("Найди в интернете, как откалибровать стол 3D-принтера", "researcher");
        assertRoutesTo("Собери мне капсулу в стиле smart-casual на осень", "stylist");
        assertRoutesTo("Запиши мой обед — курица с рисом", "nutritionist");
        assertRoutesTo("Дай рецепт курицы с рисом", "chef");
        assertRoutesTo("Что сейчас в тренде в нише «английский для IT»? Дай пару идей для постов", "creator");
    }

    /**
     * BEHAVIOUR (voice) — a voice note reaches the router as its <b>transcript</b> (gateway front-door
     * STT), which is phrased the colloquial, run-on way people <em>speak</em> rather than type. The same
     * classifier must still land a dictated request on the right agent — this is the routing half of the
     * end-to-end voice path (the gateway turns audio into these strings; the orchestrator routes them).
     * Kept crisp in intent (spoken register, but unambiguous domain) so an 8B doesn't mis-route.
     */
    @Test
    void routesTranscribedVoiceStyleRequestsToTheRightAgent() {
        assertRoutesTo("слушай я сегодня потратил тысячу рублей на такси", "finance");
        assertRoutesTo("напомни мне пожалуйста вечером позвонить маме", "tasks");
        assertRoutesTo("поставь мне встречу на завтра в три часа дня", "calendar");
    }

    /**
     * STRUCTURE — for any message (including small talk that belongs to no domain) the classifier must
     * resolve to exactly one of the known agent names and never throw. Greetings / small talk fall
     * through to {@code echo}; an actionable-but-unmatched message falls through to the catch-all
     * ({@code tasks}). This is the "structure, not text" assertion — it never checks wording.
     */
    @Test
    void alwaysResolvesToAKnownAgent() {
        for (String msg : List.of(
                "привет, как дела?",
                "Добавь встречу завтра в 15:00",
                "Сколько я потратил на продукты в этом месяце?",
                "Что мне надеть на собеседование?",
                "спасибо, отлично")) {
            String agent = classifier.classify(GoldenLlm.message(msg)).block(Duration.ofSeconds(90));
            assertThat(agent)
                    .as("classifier returned an unknown agent for «%s»", msg)
                    .isIn(KNOWN_AGENTS);
        }
    }

    private void assertRoutesTo(String text, String expectedAgent) {
        String agent = classifier.classify(GoldenLlm.message(text)).block(Duration.ofSeconds(90));
        assertThat(agent)
                .as("«%s» should route to '%s' but went to '%s'", text, expectedAgent, agent)
                .isEqualTo(expectedAgent);
    }

    /** A classifier wired exactly like production, over an AgentRegistry of the real agent manifests. */
    private LlmIntentClassifier newClassifier() {
        AgentRegistryProperties props = new AgentRegistryProperties();
        props.setCatchAllAgent("tasks");
        return new LlmIntentClassifier(llm, memory, new AgentRegistry(realManifests()), props);
    }

    /**
     * The real per-agent manifests as the orchestrator would have scraped them at startup — kept in
     * sync with each {@code AGENT.md} frontmatter (name + description + intent examples). The classifier
     * prompt is built from these, so they must mirror the deployed agents for the test to be faithful.
     */
    private static Map<String, AgentManifest> realManifests() {
        Map<String, AgentManifest> m = new LinkedHashMap<>();
        m.put("calendar", manifest("calendar",
                "Manages calendar events, birthdays, anniversaries, and time-based reminders for a household. Owns CalDAV (Radicale) writes via mcp-caldav; reads from the local events_cache mirror.",
                "Add a meeting tomorrow at 15:00 about quarterly planning",
                "When is Maria's birthday?",
                "What's on my schedule this Friday?"));
        m.put("finance", manifest("finance",
                "Manages transactions, accounts, categories, budgets and recurring payments for a household. Owns finance.* via mcp-finance; receipt photos become transactions via the shared mcp-media-processing caption capability; one-shot Money Pro CSV history import (auto-creating accounts) via mcp-money-pro-import.",
                "I spent 12 euros on coffee yesterday",
                "How much did we spend on groceries this month?",
                "What's the balance on my main card?",
                "Set a 500-euro monthly budget for dining out"));
        m.put("tasks", manifest("tasks",
                "Manages a household's GTD task system — captures anything to the inbox, clarifies items into next-actions / waiting-for / scheduled with a context, and tracks projects. Owns tasks.* via mcp-tasks; can turn a hard-deadline task into a calendar event via the calendar agent.",
                "Remind me to call the dentist",
                "What's on my plate today?",
                "Mark the milk task as done",
                "Add \"book flights\" to my vacation project"));
        m.put("researcher", manifest("researcher",
                "Web research specialist. Finds information online, reads the best sources, and returns a concise summary with links (articles and videos). Cheap-first — searches and reads pages before a single LLM synthesis, to save tokens. Use for \"find / look up / research / what's known about / send me articles or videos about …\".",
                "Find how to calibrate a 3D printer bed and send me a couple of videos",
                "What's known about creatine timing? Give me a short summary with sources.",
                "Search for reviews of the Bambu A1 mini"));
        m.put("stylist", manifest("stylist",
                "Personal style & wardrobe advisor. Catalogues the user's garments from photos, builds a personal style profile (\"analyse me\" — colour type/цветотип, body shape, suitable fabrics), and assembles outfit capsules. Use for \"add this to my wardrobe / analyse my style / what should I wear / put together an outfit / what suits me\".",
                "Add this shirt to my wardrobe",
                "Analyse my style and colour type",
                "Put together a smart-casual capsule for autumn",
                "What suits me — warm or cool tones?"));
        m.put("nutritionist", manifest("nutritionist",
                "Personal nutrition advisor. Logs meals, breaks a grocery basket down (КБЖУ, what's good / what to watch), keeps per-person diet profiles (goals, allergies, infant прикорм), and proposes a ration + shopping list for the family. Use for \"log this meal / break down my groceries / what should we eat / make a shopping list / set my diet goals\".",
                "Log my lunch — chicken with rice",
                "Here's my grocery receipt — break it down",
                "Set my goals — 2000 kcal, no nuts",
                "We want to stock up at Lenta — make a ration and shopping list"));
        m.put("chef", manifest("chef",
                "Recipe specialist. Turns a ration or a food request into concrete recipes — web-searched links (food.ru etc.) and a nicely formatted HTML recipe card. Use for \"what can I cook from this / give me a recipe for X / recipes for this week's ration\". Invoked by the nutritionist (ration → recipes) and routable directly.",
                "Give me a recipe for chicken with rice",
                "Recipes for this week's ration"));
        m.put("creator", manifest("creator",
                "Personal content-factory. Monitors trends for a creator's niche (YouTube, Reddit, Telegram, the web) and proposes fresh trends with links, post ideas, ready-to-publish drafts (title/text/CTA/hashtags), and per-platform format recommendations. Keeps a per-person content track. Use for \"find content trends / give me post ideas / draft a post / what should I post about / set my creator profile\".",
                "What's trending in \"English for IT\" this week? Give me a few ideas.",
                "Draft me a YouTube short about git rebase for juniors",
                "My niche is English for IT, audience junior devs, friendly tone"));
        return m;
    }

    private static AgentManifest manifest(String name, String description, String... examples) {
        List<Map<String, String>> intents = java.util.Arrays.stream(examples)
                .map(ex -> Map.of("example", ex, "description", ""))
                .toList();
        return new AgentManifest(name, description, "0.1.0", 0,
                List.of(), List.of(), List.of(), intents, "You are the " + name + " agent.");
    }
}
