package dev.fedorov.ailife.agents.docs.intent;

import dev.fedorov.ailife.agentruntime.intent.SkillClassifier;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.docs.chat.DocsChat;
import dev.fedorov.ailife.agents.docs.find.DocFinder;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DocsIntentRouter} — a mock {@link LlmClient} drives each classifier branch and the
 * flows are mocked, so we assert exactly which flow the router dispatched to (the parity check for the
 * cue→classifier migration, #475). Mirrors notes/creator router tests, plus the docs-specific FAMILY_CUES
 * read-scope flag (still deterministic, applied inside the finder dispatch lambda) and the doc-archiver
 * exclusion (attachment-gated, never a text intent).
 */
class DocsIntentRouterTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final DocFinder finder = mock(DocFinder.class);
    private final DocsChat chat = mock(DocsChat.class);
    private final ObjectMapper json = new ObjectMapper();
    private final SkillClassifier classifier = new SkillClassifier(json);
    private final AgentManifest manifest = new AgentManifest(
            "docs", "test", "0.0.1", 0, List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            "You are the docs agent for the ai-life system.");
    // doc-archiver is loaded (empty triggers, attachment-gated) to prove it is NOT advertised/dispatched.
    private final SkillRegistry skills = new SkillRegistry(List.of(
            skill("doc-finder", "Turns a \"find my X\" request into a search query over the archive."),
            skill("doc-archiver", "Extracts a document's metadata from OCR text; triggered by a photo.")));

    private final DocsIntentRouter router = new DocsIntentRouter(llm, skills, classifier, manifest, finder, chat);

    @Test
    void routesToFinderOwnCutWhenLlmPicksDocFinder() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"skill\",\"name\":\"doc-finder\"}")));
        when(finder.find(any(), eq(false))).thenReturn(Mono.just(sentinel("finder")));

        StepVerifier.create(router.route(msg("найди мой договор аренды")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("finder"))
                .verifyComplete();

        verify(finder).find(any(), eq(false));           // no family cue → own archive
        verify(chat, never()).reply(any());
    }

    @Test
    void familyCueWidensTheFinderScope() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"skill\",\"name\":\"doc-finder\"}")));
        when(finder.find(any(), eq(true))).thenReturn(Mono.just(sentinel("finder-shared")));

        StepVerifier.create(router.route(msg("покажи наши документы по квартире")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("finder-shared"))
                .verifyComplete();

        verify(finder).find(any(), eq(true));            // "наши документы" → personal ∪ shared
    }

    @Test
    void chatDecisionFallsBackToDocsChat() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"chat\",\"text\":\"Чем помочь с документами?\"}")));
        when(chat.reply(any())).thenReturn(Mono.just(sentinel("chat")));

        StepVerifier.create(router.route(msg("привет")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("chat"))
                .verifyComplete();

        verify(chat).reply(any());
        verify(finder, never()).find(any(), any(Boolean.class));
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
    void archiverSkillNameFallsBackToChat() {
        // doc-archiver is attachment-gated, not in the dispatch map → the router must not dispatch it.
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"skill\",\"name\":\"doc-archiver\"}")));
        when(chat.reply(any())).thenReturn(Mono.just(sentinel("chat")));

        StepVerifier.create(router.route(msg("сохрани это")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("chat"))
                .verifyComplete();

        verify(chat).reply(any());
        verify(finder, never()).find(any(), any(Boolean.class));
        // doc-archiver must not be advertised either (attachment-gated, excluded from the route set).
        assertThat(router.buildClassifierPrompt())
                .contains("doc-finder").doesNotContain("doc-archiver");
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

        StepVerifier.create(router.route(msg("где мой договор")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("chat"))
                .verifyComplete();

        verify(chat).reply(any());
    }

    private static Skill skill(String name, String description) {
        return new Skill(name, description, "0.1.0", "docs", List.of(), List.of("en", "ru"), "body");
    }

    private IntentResponse sentinel(String tag) {
        return new IntentResponse("docs", tag, null);
    }

    private static NormalizedMessage msg(String text) {
        return new NormalizedMessage(UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE,
                text, List.of(), "telegram", "1", Instant.now());
    }

    private static LlmChatResponse reply(String text) {
        return new LlmChatResponse("mock-large", text, "stop", new LlmUsage(10, 5, 15));
    }
}
