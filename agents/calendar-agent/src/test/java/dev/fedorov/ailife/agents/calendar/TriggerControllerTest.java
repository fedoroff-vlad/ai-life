package dev.fedorov.ailife.agents.calendar;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agents.calendar.skill.SkillRegistry;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.schedule.AgentWakeRequest;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trigger flow: scheduler-shaped {@link AgentWakeRequest} → SkillRegistry
 * resolves the trigger kind to a skill → LLM is called with manifest body +
 * skill body as system prompts and the payload as user message → 202.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TriggerControllerTest {

    static MockWebServer llmGateway;

    @BeforeAll
    static void startMockLlm() throws Exception {
        llmGateway = new MockWebServer();
        llmGateway.start();
    }

    @AfterAll
    static void stopMockLlm() throws Exception {
        llmGateway.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("ailife.llm-client.base-url",
                () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;
    @Autowired SkillRegistry skills;

    @Test
    void birthdayGreeterSkillIsRegisteredFromClasspath() {
        assertThat(skills.forTrigger("birthday.greet")).isPresent();
        var skill = skills.forTrigger("birthday.greet").orElseThrow();
        assertThat(skill.name()).isEqualTo("birthday-greeter");
        assertThat(skill.body()).contains("birthday greeting");
    }

    @Test
    void birthdayWakeInvokesSkillAndCallsLlmWithBothPrompts() throws Exception {
        var llmResp = new LlmChatResponse(
                "mock-large", "С днём рождения, Маша!",
                "stop", new LlmUsage(80, 8, 88));
        llmGateway.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(llmResp)));

        var payload = json.createObjectNode()
                .put("personId", UUID.randomUUID().toString())
                .put("householdId", UUID.randomUUID().toString());
        var wake = new AgentWakeRequest(
                UUID.randomUUID(), UUID.randomUUID(),
                "calendar", "birthday.greet", payload);

        http.post().uri("/agents/calendar/triggers/birthday.greet")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(wake)
                .exchange()
                .expectStatus().isAccepted();

        RecordedRequest llmReq = llmGateway.takeRequest();
        assertThat(llmReq.getPath()).isEqualTo("/v1/chat");
        String body = llmReq.getBody().readUtf8();
        assertThat(body).contains("calendar agent");       // AGENT.md body present
        assertThat(body).contains("birthday greeting");    // SKILL.md body present
        assertThat(body).contains("personId");             // payload arrived as user msg
    }

    @Test
    void unknownTriggerKindReturns404() {
        var wake = new AgentWakeRequest(
                UUID.randomUUID(), UUID.randomUUID(),
                "calendar", "no.such.kind", null);
        http.post().uri("/agents/calendar/triggers/no.such.kind")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(wake)
                .exchange()
                .expectStatus().isNotFound();
    }
}
