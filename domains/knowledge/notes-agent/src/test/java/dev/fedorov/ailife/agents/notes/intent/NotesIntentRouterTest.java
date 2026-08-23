package dev.fedorov.ailife.agents.notes.intent;

import dev.fedorov.ailife.agentruntime.intent.SkillClassifier;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.notes.chat.NotesChat;
import dev.fedorov.ailife.agents.notes.find.NoteFinder;
import dev.fedorov.ailife.agents.notes.list.ListManager;
import dev.fedorov.ailife.agents.notes.write.NoteWriter;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NotesIntentRouter} — a mock {@link LlmClient} drives each classifier branch and
 * the four flows are mocked, so we assert exactly which flow the router dispatched to (the parity check
 * for the cue→classifier migration, #475). Mirrors tasks-agent's {@code IntentRouterTest}.
 */
class NotesIntentRouterTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final NoteWriter writer = mock(NoteWriter.class);
    private final NoteFinder finder = mock(NoteFinder.class);
    private final ListManager lists = mock(ListManager.class);
    private final NoteDeleter deleter = mock(NoteDeleter.class);
    private final NotesChat chat = mock(NotesChat.class);
    private final ObjectMapper json = new ObjectMapper();
    private final SkillClassifier classifier = new SkillClassifier(json);
    private final AgentManifest manifest = new AgentManifest(
            "notes", "test", "0.0.1", 0, List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            "You are the notes agent for the ai-life system.");
    private final SkillRegistry skills = new SkillRegistry(List.of(
            skill("note-writer", "Capture a durable note from what the user wants remembered."),
            skill("note-finder", "Recall an earlier note by meaning."),
            skill("list-manager", "Maintain an everyday checklist (add / check off / clear / show).")));

    private final NotesIntentRouter router =
            new NotesIntentRouter(llm, skills, classifier, manifest, writer, finder, lists, deleter, chat);

    @Test
    void routesToFinderWhenLlmPicksNoteFinder() {
        AtomicReference<LlmChatRequest> seen = new AtomicReference<>();
        when(llm.chat(any(LlmChatRequest.class))).thenAnswer(inv -> {
            seen.set(inv.getArgument(0));
            return Mono.just(reply("{\"action\":\"skill\",\"name\":\"note-finder\"}"));
        });
        when(finder.find(any())).thenReturn(Mono.just(sentinel("finder")));

        StepVerifier.create(router.route(msg("что я записывал про кухню?")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("finder"))
                .verifyComplete();

        verify(finder).find(any());
        verify(writer, never()).capture(any());
        verify(lists, never()).handle(any());
        // The classifier prompt (AGENT.md body + classifier + user) was sent, so the model could pick.
        assertThat(seen.get().messages()).hasSize(3);
    }

    @Test
    void routesToListManagerWhenLlmPicksListManager() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"skill\",\"name\":\"list-manager\"}")));
        when(lists.handle(any())).thenReturn(Mono.just(sentinel("list")));

        StepVerifier.create(router.route(msg("надо купить молоко, добавь в список")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("list"))
                .verifyComplete();

        verify(lists).handle(any());
        verify(finder, never()).find(any());
    }

    @Test
    void routesToWriterWhenLlmPicksNoteWriter() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"skill\",\"name\":\"note-writer\"}")));
        when(writer.capture(any())).thenReturn(Mono.just(sentinel("writer")));

        StepVerifier.create(router.route(msg("запомни, что мама любит пионы")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("writer"))
                .verifyComplete();

        verify(writer).capture(any());
    }

    @Test
    void chatDecisionFallsBackToNotesChat() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"chat\",\"text\":\"Чем помочь?\"}")));
        when(chat.reply(any())).thenReturn(Mono.just(sentinel("chat")));

        StepVerifier.create(router.route(msg("привет")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("chat"))
                .verifyComplete();

        verify(chat).reply(any());
        verify(finder, never()).find(any());
        verify(writer, never()).capture(any());
        verify(lists, never()).handle(any());
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
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"skill\",\"name\":\"ghost\"}")));
        when(chat.reply(any())).thenReturn(Mono.just(sentinel("chat")));

        StepVerifier.create(router.route(msg("что-то")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("chat"))
                .verifyComplete();

        verify(chat).reply(any());
        verify(finder, never()).find(any());
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

        StepVerifier.create(router.route(msg("что я думал про отпуск")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("chat"))
                .verifyComplete();

        verify(chat).reply(any());
    }

    private static Skill skill(String name, String description) {
        return new Skill(name, description, "0.1.0", "notes", List.of(), List.of("en", "ru"), "body");
    }

    private IntentResponse sentinel(String tag) {
        return new IntentResponse("notes", tag, null);
    }

    private static NormalizedMessage msg(String text) {
        return new NormalizedMessage(UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE,
                text, List.of(), "telegram", "1", Instant.now());
    }

    private static LlmChatResponse reply(String text) {
        return new LlmChatResponse("mock-large", text, "stop", new LlmUsage(10, 5, 15));
    }
}
