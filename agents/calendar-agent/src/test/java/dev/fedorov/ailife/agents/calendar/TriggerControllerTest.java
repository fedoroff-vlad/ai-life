package dev.fedorov.ailife.agents.calendar;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agents.calendar.skill.SkillRegistry;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.profile.UserDto;
import dev.fedorov.ailife.contracts.schedule.AgentWakeRequest;
import okhttp3.mockwebserver.Dispatcher;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trigger flow: scheduler-shaped {@link AgentWakeRequest} → SkillRegistry
 * resolves the kind to a skill → LLM is called with manifest+skill prompts →
 * household members are pulled from profile-service → each one receives a
 * notify POST with the generated text.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TriggerControllerTest {

    static MockWebServer llmGateway;
    static MockWebServer profileService;
    static MockWebServer notifier;

    @BeforeAll
    static void start() throws Exception {
        llmGateway = new MockWebServer();
        llmGateway.start();
        profileService = new MockWebServer();
        profileService.start();
        notifier = new MockWebServer();
        notifier.start();
    }

    @AfterAll
    static void stop() throws Exception {
        llmGateway.shutdown();
        profileService.shutdown();
        notifier.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("ailife.llm-client.base-url",
                () -> "http://localhost:" + llmGateway.getPort());
        r.add("calendar-agent.profile-service-url",
                () -> "http://localhost:" + profileService.getPort());
        r.add("calendar-agent.notifier-url",
                () -> "http://localhost:" + notifier.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;
    @Autowired SkillRegistry skills;

    @Test
    void birthdayGreeterSkillIsRegisteredFromClasspath() {
        assertThat(skills.forTrigger("birthday.greet")).isPresent();
        var skill = skills.forTrigger("birthday.greet").orElseThrow();
        assertThat(skill.name()).isEqualTo("birthday-greeter");
    }

    @Test
    void birthdayWakeRunsSkillAndFansGreetingOutToAllHouseholdUsers() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID vladId = UUID.randomUUID();
        UUID wifeId = UUID.randomUUID();

        // LLM returns the greeting.
        var llmResp = new LlmChatResponse(
                "mock-large", "С днём рождения, Маша!",
                "stop", new LlmUsage(80, 8, 88));
        llmGateway.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(llmResp)));

        // Profile returns two users in the household.
        var users = List.of(
                new UserDto(vladId, householdId, "Vlad", "ru-RU", 1L, "admin", Instant.now()),
                new UserDto(wifeId, householdId, "Wife", "ru-RU", 2L, "admin", Instant.now()));
        profileService.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(users)));

        // Notifier accepts both calls.
        notifier.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest req) {
                return new MockResponse().setResponseCode(202);
            }
        });

        var payload = json.createObjectNode().put("personId", UUID.randomUUID().toString());
        var wake = new AgentWakeRequest(
                UUID.randomUUID(), householdId, "calendar", "birthday.greet", payload);

        http.post().uri("/agents/calendar/triggers/birthday.greet")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(wake)
                .exchange()
                .expectStatus().isAccepted();

        // The LLM was called once.
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq).isNotNull();
        assertThat(llmReq.getPath()).isEqualTo("/v1/chat");

        // Profile-service was queried for the household members.
        RecordedRequest profileReq = profileService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(profileReq).isNotNull();
        assertThat(profileReq.getPath()).isEqualTo("/v1/users/by-household/" + householdId);

        // Notifier received exactly one POST per household user, with the greeting text.
        RecordedRequest first = notifier.takeRequest(2, TimeUnit.SECONDS);
        RecordedRequest second = notifier.takeRequest(2, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(first.getPath()).isEqualTo("/v1/notify");
        assertThat(second.getPath()).isEqualTo("/v1/notify");
        String firstBody = first.getBody().readUtf8();
        String secondBody = second.getBody().readUtf8();
        assertThat(firstBody + secondBody)
                .contains(vladId.toString())
                .contains(wifeId.toString())
                .contains("С днём рождения");
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
