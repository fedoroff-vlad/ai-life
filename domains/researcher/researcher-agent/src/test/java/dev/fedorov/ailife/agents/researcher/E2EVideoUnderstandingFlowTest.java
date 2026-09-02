package dev.fedorov.ailife.agents.researcher;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.mediafetch.AudioFetchInput;
import dev.fedorov.ailife.contracts.mediafetch.AudioFetchResult;
import dev.fedorov.ailife.contracts.mediafetch.VideoTranscript;
import dev.fedorov.ailife.contracts.media.TranscriptResult;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V-d stage-closer for the video-understanding feature ({@code #294}): the pivotal service
 * (researcher-agent) runs in ONE real Spring context; mcp-media-fetch, mcp-media-processing and
 * llm-gateway are MockWebServers that <b>forward between hops</b> (the monorepo's fat-jar packaging
 * blocks a true all-real-services module — see CLAUDE.md §Test strategy). It drives the <b>link path's
 * cross-capability chain</b> (captions empty → {@code fetch_audio} → STT) end-to-end through the agent's
 * HTTP surface and asserts the {@code libs/contracts} DTOs survive serialisation each hop — in
 * particular that {@code AudioFetchResult.mediaId} (from the acquisition capability) reappears as
 * {@code media.TranscribeInput.mediaId} at the understanding capability, and the acting scope
 * ({@code householdId}) propagates into {@code AudioFetchInput}. The single-context unit
 * {@code VideoUnderstandingFlowTest} covers the file/visual tiers; this proves the wire contracts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class E2EVideoUnderstandingFlowTest {

    static MockWebServer mediaFetch;
    static MockWebServer mediaProcessing;
    static MockWebServer llmGateway;

    @BeforeAll
    static void start() throws Exception {
        mediaFetch = new MockWebServer();
        mediaProcessing = new MockWebServer();
        llmGateway = new MockWebServer();
        mediaFetch.start();
        mediaProcessing.start();
        llmGateway.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mediaFetch.shutdown();
        mediaProcessing.shutdown();
        llmGateway.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("researcher-agent.mcp-media-fetch-url", () -> "http://localhost:" + mediaFetch.getPort());
        r.add("researcher-agent.mcp-media-processing-url",
                () -> "http://localhost:" + mediaProcessing.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void linkChainSurvivesEveryHopContract() throws Exception {
        UUID household = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        String videoUrl = "https://youtube.com/watch?v=abc";

        // Hop 1: no captions → fall through. Hop 2: audio acquired → mediaId. Hop 3: STT text.
        mediaFetch.enqueue(jsonResponse(new VideoTranscript(videoUrl, null, "", null, false)));
        mediaFetch.enqueue(jsonResponse(new AudioFetchResult("aud-77", "Bread recipe", "youtube", 95)));
        mediaProcessing.enqueue(jsonResponse(new TranscriptResult(
                "In this video I bake a simple no-knead bread in a Dutch oven.", "en", 95.0)));
        llmGateway.enqueue(llm("Видео о выпечке хлеба без замеса в казане."));

        IntentResponse resp = http.post().uri("/agents/researcher/intent")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new NormalizedMessage(user, household, MessageScope.PRIVATE,
                        videoUrl + " о чём это видео?", List.of(), "telegram", "1", Instant.now()))
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.agent()).isEqualTo("researcher");
        assertThat(resp.text()).contains("хлеб");

        // Hop 1 — transcribe_video (mediafetch.TranscribeInput survives).
        RecordedRequest h1 = mediaFetch.takeRequest(2, TimeUnit.SECONDS);
        assertThat(h1.getPath()).isEqualTo("/internal/transcribe");
        var t1 = json.readValue(h1.getBody().readUtf8(),
                dev.fedorov.ailife.contracts.mediafetch.TranscribeInput.class);
        assertThat(t1.url()).isEqualTo(videoUrl);

        // Hop 2 — fetch_audio: the acting scope (householdId) propagated into AudioFetchInput.
        RecordedRequest h2 = mediaFetch.takeRequest(2, TimeUnit.SECONDS);
        assertThat(h2.getPath()).isEqualTo("/internal/fetch-audio");
        AudioFetchInput af = json.readValue(h2.getBody().readUtf8(), AudioFetchInput.class);
        assertThat(af.url()).isEqualTo(videoUrl);
        assertThat(af.householdId()).isEqualTo(household);

        // Hop 3 — STT: the mediaId from AudioFetchResult reappears in media.TranscribeInput (the key handoff).
        RecordedRequest h3 = mediaProcessing.takeRequest(2, TimeUnit.SECONDS);
        assertThat(h3.getPath()).isEqualTo("/internal/transcribe");
        var t3 = json.readValue(h3.getBody().readUtf8(),
                dev.fedorov.ailife.contracts.media.TranscribeInput.class);
        assertThat(t3.mediaId()).isEqualTo("aud-77");

        // Hop 4 — synthesis carries the STT text on the speech channel; exactly one LLM call.
        RecordedRequest h4 = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(h4.getPath()).isEqualTo("/v1/chat");
        assertThat(h4.getBody().readUtf8()).contains("no-knead bread").contains("speech");
        assertThat(llmGateway.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    private MockResponse jsonResponse(Object body) {
        return new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(body));
    }

    private MockResponse llm(String content) {
        return new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new LlmChatResponse("mock-large", content, "stop",
                        new LlmUsage(40, 20, 60))));
    }
}
