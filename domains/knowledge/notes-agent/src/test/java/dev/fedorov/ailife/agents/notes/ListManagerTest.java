package dev.fedorov.ailife.agents.notes;

import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.note.NoteDto;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the LI-a list flow through the agent's HTTP surface ({@code POST /agents/notes/intent}):
 * a "…список…" cue → llm-gateway classifies the op via the {@code list-manager} SKILL → memory-service
 * resolves/creates the {@code type=list} note and its checklist body is mutated + written back.
 * MockWebServers stand in for llm-gateway and memory-service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class ListManagerTest {

    static MockWebServer memoryService;
    static MockWebServer llmGateway;

    @BeforeAll
    static void start() throws Exception {
        memoryService = new MockWebServer();
        llmGateway = new MockWebServer();
        memoryService.start();
        llmGateway.start();
    }

    @AfterAll
    static void stop() throws Exception {
        memoryService.shutdown();
        llmGateway.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("notes-agent.memory-service-url", () -> "http://localhost:" + memoryService.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void addToAbsentListCreatesIt() throws Exception {
        UUID household = UUID.randomUUID();
        UUID user = UUID.randomUUID();

        enqueueOp("add", "список покупок", "молоко");
        memoryService.enqueue(jsonResponse("[]"));                       // no lists yet
        memoryService.enqueue(jsonResponse(json.writeValueAsString(     // POST create echoes the note
                listNote(UUID.randomUUID(), household, "список покупок", "- [ ] молоко"))));

        IntentResponse resp = post(msg(household, user, "добавь молоко в список покупок"));
        assertThat(resp.text()).contains("Создал список").contains("молоко");
        assertThat(resp.trace()).isEqualTo("wrote: created a list and added an item");   // why-trace #485/G2

        llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(memoryService.takeRequest(2, TimeUnit.SECONDS).getPath()).startsWith("/v1/notes?");
        RecordedRequest create = memoryService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(create.getMethod()).isEqualTo("POST");
        String body = create.getBody().readUtf8();
        assertThat(body).contains("\"type\":\"list\"").contains("- [ ] молоко");
    }

    @Test
    void addToExistingListAppendsViaPut() throws Exception {
        UUID household = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID listId = UUID.randomUUID();

        enqueueOp("add", "список покупок", "хлеб");
        memoryService.enqueue(jsonResponse(json.writeValueAsString(List.of(
                listNote(listId, household, "список покупок", "- [ ] молоко")))));
        memoryService.enqueue(jsonResponse(json.writeValueAsString(
                listNote(listId, household, "список покупок", "- [ ] молоко\n- [ ] хлеб"))));

        IntentResponse resp = post(msg(household, user, "добавь хлеб в список покупок"));
        assertThat(resp.text()).contains("Добавил").contains("хлеб");
        assertThat(resp.trace()).isEqualTo("wrote: added an item to a list");   // why-trace #485/G2

        llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(memoryService.takeRequest(2, TimeUnit.SECONDS).getPath()).startsWith("/v1/notes?");
        RecordedRequest put = memoryService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(put.getMethod()).isEqualTo("PUT");
        assertThat(put.getPath()).isEqualTo("/v1/notes/" + listId);
        assertThat(put.getBody().readUtf8()).contains("- [ ] молоко\\n- [ ] хлеб");
    }

    @Test
    void duplicateAddIsANoOp() throws Exception {
        UUID household = UUID.randomUUID();
        UUID user = UUID.randomUUID();

        enqueueOp("add", "список покупок", "молоко");
        memoryService.enqueue(jsonResponse(json.writeValueAsString(List.of(
                listNote(UUID.randomUUID(), household, "список покупок", "- [ ] молоко")))));

        IntentResponse resp = post(msg(household, user, "добавь молоко в список покупок"));
        assertThat(resp.text()).contains("уже в списке");
        assertThat(resp.trace()).as("a no-op add wrote nothing → no trace").isNull();   // why-trace #485/G2

        llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(memoryService.takeRequest(2, TimeUnit.SECONDS).getPath()).startsWith("/v1/notes?");
        // no PUT — nothing more was sent
        assertThat(memoryService.takeRequest(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void checkMarksItemDoneViaPut() throws Exception {
        UUID household = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID listId = UUID.randomUUID();

        enqueueOp("check", "список покупок", "яйца");
        memoryService.enqueue(jsonResponse(json.writeValueAsString(List.of(
                listNote(listId, household, "список покупок", "- [ ] молоко\n- [ ] яйца")))));
        memoryService.enqueue(jsonResponse(json.writeValueAsString(
                listNote(listId, household, "список покупок", "- [ ] молоко\n- [x] яйца"))));

        IntentResponse resp = post(msg(household, user, "вычеркни яйца из списка покупок"));
        assertThat(resp.text()).contains("Вычеркнул").contains("яйца");
        assertThat(resp.trace()).isEqualTo("wrote: checked off a list item");   // why-trace #485/G2

        llmGateway.takeRequest(2, TimeUnit.SECONDS);
        memoryService.takeRequest(2, TimeUnit.SECONDS);   // GET list
        RecordedRequest put = memoryService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(put.getMethod()).isEqualTo("PUT");
        assertThat(put.getBody().readUtf8()).contains("- [x] яйца");
    }

    @Test
    void showRendersTheCurrentItems() throws Exception {
        UUID household = UUID.randomUUID();
        UUID user = UUID.randomUUID();

        enqueueOp("show", "список покупок", null);
        memoryService.enqueue(jsonResponse(json.writeValueAsString(List.of(
                listNote(UUID.randomUUID(), household, "список покупок", "- [ ] молоко\n- [x] яйца")))));

        IntentResponse resp = post(msg(household, user, "покажи список покупок"));
        assertThat(resp.text()).contains("молоко").contains("яйца").contains("✅");
        assertThat(resp.trace()).as("show is a read → no write trace").isNull();   // why-trace #485/G2

        llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(memoryService.takeRequest(2, TimeUnit.SECONDS).getPath()).startsWith("/v1/notes?");
        assertThat(memoryService.takeRequest(1, TimeUnit.SECONDS)).isNull();   // read-only, no write
    }

    @Test
    void checkOnMissingListIsGraceful() throws Exception {
        UUID household = UUID.randomUUID();
        UUID user = UUID.randomUUID();

        enqueueOp("check", "несуществующий", "молоко");
        memoryService.enqueue(jsonResponse("[]"));

        IntentResponse resp = post(msg(household, user, "вычеркни молоко из списка несуществующий"));
        assertThat(resp.text()).contains("Нет списка");

        llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(memoryService.takeRequest(2, TimeUnit.SECONDS).getPath()).startsWith("/v1/notes?");
        assertThat(memoryService.takeRequest(1, TimeUnit.SECONDS)).isNull();   // nothing created/written
    }

    // ---- helpers ------------------------------------------------------------------------

    private void enqueueOp(String op, String list, String item) {
        // First LLM turn = NotesIntentRouter classification (#475) → route to list-manager;
        // second = the list-manager op classification the flow itself does.
        enqueueRouting("list-manager");
        String itemJson = item == null ? "" : ",\"item\":\"" + item + "\"";
        String content = "{\"op\":\"" + op + "\",\"list\":\"" + list + "\"" + itemJson + "}";
        try {
            llmGateway.enqueue(jsonResponse(json.writeValueAsString(
                    new LlmChatResponse("mock-large", content, "stop", new LlmUsage(20, 8, 28)))));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void enqueueRouting(String skill) {
        try {
            llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                    "mock-large", "{\"action\":\"skill\",\"name\":\"" + skill + "\"}",
                    "stop", new LlmUsage(10, 4, 14)))));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static NoteDto listNote(UUID id, UUID household, String title, String body) {
        return new NoteDto(id, household, null, title, "list", List.of("list"),
                "user", null, body, null, Instant.now(), Instant.now());
    }

    private static NormalizedMessage msg(UUID household, UUID user, String text) {
        return new NormalizedMessage(user, household, MessageScope.PRIVATE, text,
                List.of(), "telegram", "20", Instant.now());
    }

    private IntentResponse post(NormalizedMessage msg) {
        return http.post().uri("/agents/notes/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
