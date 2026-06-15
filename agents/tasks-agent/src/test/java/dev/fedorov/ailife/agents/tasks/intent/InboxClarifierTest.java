package dev.fedorov.ailife.agents.tasks.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.tasks.http.ClarifyClient;
import dev.fedorov.ailife.agents.tasks.http.TaskReviewClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.tasks.ClarifyTaskInput;
import dev.fedorov.ailife.contracts.tasks.TaskItemDto;
import dev.fedorov.ailife.contracts.tasks.WeeklyReviewResult;
import dev.fedorov.ailife.llm.LlmClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InboxClarifier} — mock {@link LlmClient} / {@link TaskReviewClient} /
 * {@link ClarifyClient} exercise the propose (structured JSON → confirm + pendingAction) and resume
 * (apply-on-confirm / cancel) branches without an external service.
 */
class InboxClarifierTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final TaskReviewClient review = mock(TaskReviewClient.class);
    private final ClarifyClient clarify = mock(ClarifyClient.class);
    // findAndRegisterModules() mirrors Spring's mapper so TaskItemDto's Instant fields serialize.
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final AgentManifest manifest = new AgentManifest(
            "tasks", "test", "0.0.1", 0,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            "You are the tasks agent.");
    private final Skill skill = new Skill("inbox-clarify", "Propose a GTD clarification.",
            "0.2.0", "tasks", List.of(), List.of("en", "ru"), "Clarify the inbox items.");
    private final SkillRegistry skills = new SkillRegistry(List.of(skill));

    private final InboxClarifier clarifier =
            new InboxClarifier(llm, manifest, skills, review, clarify, json);

    @Test
    void proposesStructuredClarificationAndStashesPendingAction() {
        UUID household = UUID.randomUUID();
        UUID milkId = UUID.randomUUID();
        when(review.fetch(household)).thenReturn(Mono.just(new WeeklyReviewResult(
                household, 1, 0, 0, List.of(inboxItem(milkId, "купить молоко")),
                List.of(), List.of())));
        AtomicReference<LlmChatRequest> seen = new AtomicReference<>();
        when(llm.chat(any(LlmChatRequest.class))).thenAnswer(inv -> {
            seen.set(inv.getArgument(0));
            return Mono.just(reply("mock-large",
                    "{\"proposals\":[{\"taskId\":\"" + milkId
                            + "\",\"title\":\"купить молоко\",\"status\":\"next\",\"context\":\"@errand\"}]}"));
        });

        StepVerifier.create(clarifier.clarify(message(household, "разбери инбокс")))
                .assertNext(r -> {
                    assertThat(r.agent()).isEqualTo("tasks");
                    assertThat(r.text()).contains("купить молоко").contains("next").contains("@errand");
                    assertThat(r.text()).contains("Применить");
                    // The proposals are stashed for the resume turn.
                    assertThat(r.pendingAction()).isNotNull();
                    assertThat(r.pendingAction().path("flow").asText()).isEqualTo("inbox-clarify-apply");
                    assertThat(r.pendingAction().path("proposals").get(0).path("taskId").asText())
                            .isEqualTo(milkId.toString());
                })
                .verifyComplete();

        String body = seen.get().messages().toString();
        assertThat(body).contains("Clarify the inbox items.").contains("купить молоко").contains("разбери инбокс");
    }

    @Test
    void resumeAffirmativeAppliesEveryProposal() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        when(clarify.clarify(any(ClarifyTaskInput.class)))
                .thenReturn(Mono.just(inboxItem(t1, "x")));

        ResumeRequest req = resumeReq("да", proposal(t1, "next", "@errand"), proposal(t2, "waiting", null));

        StepVerifier.create(clarifier.resume(req))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Разобрал 2 из 2");
                    assertThat(r.pendingAction()).isNull();
                })
                .verifyComplete();

        verify(clarify, times(2)).clarify(any(ClarifyTaskInput.class));
    }

    @Test
    void resumeNegativeCancelsWithoutApplying() {
        ResumeRequest req = resumeReq("нет", proposal(UUID.randomUUID(), "next", "@home"));

        StepVerifier.create(clarifier.resume(req))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Отменил");
                    assertThat(r.pendingAction()).isNull();
                })
                .verifyComplete();

        verify(clarify, never()).clarify(any());
    }

    @Test
    void emptyInboxRepliesNothingToClarifyWithoutLlmCall() {
        UUID household = UUID.randomUUID();
        when(review.fetch(household)).thenReturn(Mono.just(new WeeklyReviewResult(
                household, 0, 0, 0, List.of(), List.of(), List.of())));

        StepVerifier.create(clarifier.clarify(message(household, "разбери инбокс")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("пуст");
                    assertThat(r.pendingAction()).isNull();
                })
                .verifyComplete();

        verify(llm, never()).chat(any(LlmChatRequest.class));
    }

    @Test
    void reviewFailureDegradesToFriendlyMessage() {
        UUID household = UUID.randomUUID();
        when(review.fetch(household)).thenReturn(Mono.error(new RuntimeException("mcp-tasks down")));

        StepVerifier.create(clarifier.clarify(message(household, "разбери инбокс")))
                .assertNext(r -> assertThat(r.text()).contains("Не удалось"))
                .verifyComplete();

        verify(llm, never()).chat(any(LlmChatRequest.class));
    }

    private ObjectNode proposal(UUID taskId, String status, String context) {
        ObjectNode p = json.createObjectNode();
        p.put("taskId", taskId.toString());
        p.put("title", "task " + taskId);
        p.put("status", status);
        if (context != null) p.put("context", context);
        return p;
    }

    private ResumeRequest resumeReq(String text, ObjectNode... proposals) {
        ObjectNode pending = json.createObjectNode();
        pending.put("flow", "inbox-clarify-apply");
        var arr = pending.putArray("proposals");
        for (ObjectNode p : proposals) arr.add(p);
        var msg = new NormalizedMessage(UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE,
                text, List.of(), "telegram", "9", Instant.now());
        return new ResumeRequest(msg, pending);
    }

    private static NormalizedMessage message(UUID household, String text) {
        return new NormalizedMessage(UUID.randomUUID(), household, MessageScope.PRIVATE,
                text, List.of(), "telegram", "1", Instant.now());
    }

    private static TaskItemDto inboxItem(UUID id, String title) {
        return new TaskItemDto(id, null, null, null, title, "inbox",
                null, null, null, null, null, "manual", null, null, null, Instant.now(), null);
    }

    private static LlmChatResponse reply(String model, String text) {
        return new LlmChatResponse(model, text, "stop", new LlmUsage(10, 5, 15));
    }
}
