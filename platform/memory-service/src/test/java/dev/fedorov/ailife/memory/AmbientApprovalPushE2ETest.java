package dev.fedorov.ailife.memory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.memory.CaptureRequest;
import dev.fedorov.ailife.contracts.note.WriteNoteRequest;
import dev.fedorov.ailife.memory.capture.FactExtractor;
import dev.fedorov.ailife.memory.capture.NoteCandidate;
import dev.fedorov.ailife.memory.capture.NoteWorthinessExtractor;
import dev.fedorov.ailife.memory.capture.RelationExtractor;
import dev.fedorov.ailife.memory.http.ProfileClient;
import dev.fedorov.ailife.test.AbstractPostgresIntegrationTest;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * AC-4c — the ambient-approval <b>capture side</b> across real HTTP boundaries. An important-but-inferred
 * candidate arriving through the real {@code POST /v1/capture} must (1) route-lock the conversation on
 * conversation-service with a ready-to-write {@code ambient} note as the pendingAction, and (2) push the
 * "заметил: … — записать?" question to the owner on notifier-service. Both hops are real MockWebServers,
 * so this asserts the outbound wire contracts the resume side (notes-agent {@code AmbientApproveResumeTest})
 * consumes — closing the loop: capture → lock+push here, "да" → an {@code ambient} note there.
 *
 * <p>The LLM decision ({@link NoteWorthinessExtractor}) and person resolution ({@link ProfileClient}) are
 * mocked so the candidate + attribution are deterministic; the inferred path writes nothing to Postgres and
 * embeds nothing, so no llm-gateway is needed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"event-bus.enabled=false", "memory.ambient-capture.enabled=true"})
@AutoConfigureWebTestClient
class AmbientApprovalPushE2ETest extends AbstractPostgresIntegrationTest {

    static MockWebServer conversationService;
    static MockWebServer notifierService;

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) throws IOException {
        registerDataSource(registry);
        conversationService = new MockWebServer();
        notifierService = new MockWebServer();
        conversationService.start();
        notifierService.start();
        registry.add("ailife.llm-client.base-url", () -> "http://127.0.0.1:1");   // unused on the inferred path
        registry.add("memory.conversation-base-url", () -> "http://localhost:" + conversationService.getPort());
        registry.add("memory.notifier-base-url", () -> "http://localhost:" + notifierService.getPort());
    }

    @AfterAll
    static void stop() throws IOException {
        if (conversationService != null) conversationService.shutdown();
        if (notifierService != null) notifierService.shutdown();
    }

    @MockitoBean FactExtractor facts;                 // [] by default
    @MockitoBean RelationExtractor relationExtractor; // [] by default
    @MockitoBean NoteWorthinessExtractor noteExtractor;
    @MockitoBean ProfileClient profile;

    @LocalServerPort int port;
    @Autowired ObjectMapper json;

    UUID household;
    UUID owner;
    UUID mama;

    @BeforeAll
    static void applySchemaOnce() {
        applySchema("test-schema.sql");
    }

    @BeforeEach
    void seed() {
        household = UUID.randomUUID();
        owner = UUID.randomUUID();
        mama = UUID.randomUUID();
        when(profile.resolvePersonId(household, "Мама")).thenReturn(mama);
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void inferredMessage_locksConversationAndPushesQuestion() throws Exception {
        when(noteExtractor.extract(any()))
                .thenReturn(List.of(new NoteCandidate(
                        "Мама — аллергия", "person", "аллергия на орехи", "Мама", "important", false)));
        conversationService.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("content-type", "application/json").setBody("{}"));
        notifierService.enqueue(new MockResponse().setResponseCode(200));

        client().post().uri("/v1/capture")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CaptureRequest(household, owner, null, "у мамы аллергия на орехи", "telegram"))
                .exchange().expectStatus().isOk();

        // (1) conversation-service got a route-lock to notes with a ready ambient note in the pendingAction.
        RecordedRequest lock = conversationService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(lock.getMethod()).isEqualTo("PUT");
        assertThat(lock.getPath()).isEqualTo("/v1/conversation-state");
        JsonNode lockBody = json.readTree(lock.getBody().readUtf8());
        assertThat(lockBody.path("householdId").asText()).isEqualTo(household.toString());
        assertThat(lockBody.path("userId").asText()).isEqualTo(owner.toString());
        assertThat(lockBody.path("channel").asText()).isEqualTo("telegram");
        assertThat(lockBody.path("routeLock").asText()).isEqualTo("notes");
        JsonNode pending = lockBody.path("pendingAction");
        assertThat(pending.path("flow").asText()).isEqualTo("ambient-approve");

        // The stashed note is a valid WriteNoteRequest — exactly what notes-agent's AmbientApprover parses.
        WriteNoteRequest note = json.treeToValue(pending.path("note"), WriteNoteRequest.class);
        assertThat(note.source()).isEqualTo("ambient");
        assertThat(note.personId()).isEqualTo(mama);
        assertThat(note.title()).isEqualTo("Мама — аллергия");
        assertThat(note.bodyMd()).contains("[[Мама]]");

        // (2) notifier-service got the approval question addressed to the owner.
        RecordedRequest push = notifierService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(push.getMethod()).isEqualTo("POST");
        assertThat(push.getPath()).isEqualTo("/v1/notify");
        JsonNode pushBody = json.readTree(push.getBody().readUtf8());
        assertThat(pushBody.path("userId").asText()).isEqualTo(owner.toString());
        assertThat(pushBody.path("text").asText()).contains("записать");
    }
}
