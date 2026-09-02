package dev.fedorov.ailife.agents.researcher;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.Attachment;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.media.CaptionResult;
import dev.fedorov.ailife.contracts.media.FramesResult;
import dev.fedorov.ailife.contracts.media.TranscriptResult;
import dev.fedorov.ailife.contracts.mediafetch.VideoTranscript;
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
 * The multimodal video-understanding flow (V-c) end-to-end through the agent's HTTP surface
 * ({@code POST /agents/researcher/intent}). mcp-media-fetch, mcp-media-processing and llm-gateway are
 * MockWebServers. Covers the three cheap-first tiers: a captioned link (tier 1 only), a spoken file
 * (tier 2 STT), and a speechless file (tier 3 frames → caption). {@code video-frames=2} keeps the
 * visual scenario deterministic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "researcher-agent.video-frames=2")
@AutoConfigureWebTestClient
class VideoUnderstandingFlowTest {

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
    void captionedLinkUsesTier1Only() throws Exception {
        // mcp-media-fetch transcribe_video returns captions → the answer; no audio/visual tier.
        mediaFetch.enqueue(jsonResponse(new VideoTranscript(
                "https://youtube.com/watch?v=abc", "Bed Leveling",
                "First heat the bed, then run the paper test at each corner.", "en", false)));
        llmGateway.enqueue(llm("Видео о калибровке стола: нагрев + тест бумагой по углам."));

        IntentResponse resp = intent(new NormalizedMessage(
                UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE,
                "https://youtube.com/watch?v=abc о чём это?", List.of(),
                "telegram", "1", Instant.now()));

        assertThat(resp).isNotNull();
        assertThat(resp.agent()).isEqualTo("researcher");
        assertThat(resp.text()).contains("калибровке");

        RecordedRequest t1 = mediaFetch.takeRequest(2, TimeUnit.SECONDS);
        assertThat(t1.getPath()).isEqualTo("/internal/transcribe");
        // No fetch-audio tier — captions were enough.
        assertThat(mediaFetch.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
        // No id-based understanding either.
        assertThat(mediaProcessing.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();

        // The synthesis prompt carries the captions, framed as untrusted data.
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq.getPath()).isEqualTo("/v1/chat");
        assertThat(llmReq.getBody().readUtf8())
                .contains("paper test").contains("captions");
    }

    @Test
    void spokenFileUsesTier2Stt() throws Exception {
        // A video-file attachment: skip captions, run STT directly on the media id.
        mediaProcessing.enqueue(jsonResponse(new TranscriptResult(
                "Today we compare two filaments under a bridge test.", "en", 42.0)));
        llmGateway.enqueue(llm("В видео сравнивают два филамента в тесте на мосты."));

        IntentResponse resp = intent(new NormalizedMessage(
                UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE,
                "что тут?", List.of(new Attachment("video", "video/mp4", "vid-1", null)),
                "telegram", "2", Instant.now()));

        assertThat(resp.text()).contains("филамента");

        RecordedRequest t = mediaProcessing.takeRequest(2, TimeUnit.SECONDS);
        assertThat(t.getPath()).isEqualTo("/internal/transcribe");
        assertThat(t.getBody().readUtf8()).contains("vid-1");
        // Speech found → no visual tier.
        assertThat(mediaProcessing.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
        // A file never touches the acquisition capability.
        assertThat(mediaFetch.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();

        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq.getBody().readUtf8()).contains("bridge test").contains("speech");
    }

    @Test
    void silentFileFallsBackToFramesAndCaption() throws Exception {
        // No speech → frames (2) → caption each → a visual scene description.
        mediaProcessing.enqueue(jsonResponse(new TranscriptResult("", null, null)));
        mediaProcessing.enqueue(jsonResponse(new FramesResult(List.of("frame-0", "frame-1"))));
        mediaProcessing.enqueue(jsonResponse(new CaptionResult("A calm forest stream over rocks.", "m")));
        mediaProcessing.enqueue(jsonResponse(new CaptionResult("Close-up of water splashing.", "m")));
        llmGateway.enqueue(llm("Похоже на ASMR-видео: лесной ручей по камням, брызги воды крупным планом."));

        IntentResponse resp = intent(new NormalizedMessage(
                UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE,
                null, List.of(new Attachment("video", "video/mp4", "vid-2", null)),
                "telegram", "3", Instant.now()));

        assertThat(resp.text()).contains("ручей");

        assertThat(mediaProcessing.takeRequest(2, TimeUnit.SECONDS).getPath())
                .isEqualTo("/internal/transcribe");
        assertThat(mediaProcessing.takeRequest(2, TimeUnit.SECONDS).getPath())
                .isEqualTo("/internal/frames");
        RecordedRequest cap1 = mediaProcessing.takeRequest(2, TimeUnit.SECONDS);
        assertThat(cap1.getPath()).isEqualTo("/internal/caption");
        assertThat(cap1.getBody().readUtf8()).contains("frame-0");
        RecordedRequest cap2 = mediaProcessing.takeRequest(2, TimeUnit.SECONDS);
        assertThat(cap2.getPath()).isEqualTo("/internal/caption");
        assertThat(cap2.getBody().readUtf8()).contains("frame-1");

        // The synthesis carries the joined frame captions, marked as the visual channel.
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq.getBody().readUtf8())
                .contains("forest stream").contains("splashing").contains("visual");
    }

    private IntentResponse intent(NormalizedMessage msg) {
        return http.post().uri("/agents/researcher/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();
    }

    private MockResponse jsonResponse(Object body) {
        return new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(writeJson(body));
    }

    private MockResponse llm(String content) {
        return new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(writeJson(new LlmChatResponse("mock-large", content, "stop",
                        new LlmUsage(40, 20, 60))));
    }

    private String writeJson(Object body) {
        return json.writeValueAsString(body);
    }
}
