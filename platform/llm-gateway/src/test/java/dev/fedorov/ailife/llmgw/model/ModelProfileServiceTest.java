package dev.fedorov.ailife.llmgw.model;

import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.llmgw.config.LlmGatewayProperties;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LC-4's acceptance criteria, as tests.
 *
 * <p>The plan makes the *ordering* a correctness requirement rather than an optimisation: with
 * only ~14 GB of headroom on the 64 GB box, an incoming model that starts loading while the
 * outgoing one is still resident busts the GPU ceiling. So the two assertions that matter are
 * "eviction is confirmed before anything loads" and "an eviction that never completes fails
 * loudly instead of proceeding" — both of which a fire-and-forget implementation would pass a
 * naive "did it return 200?" test.
 */
class ModelProfileServiceTest {

    private static final String BIG = "qwen3:32b";
    private static final String SMALL = "qwen3:14b";

    private MockWebServer ollama;
    /** Every path the service hit, in order — the view the ordering assertions need. */
    private final List<String> calls = new ArrayList<>();
    /**
     * How many more polls report the big model as still loaded. Counting polls rather than
     * flipping a flag from another thread keeps "it leaves on the second poll" deterministic.
     */
    private final AtomicInteger residentPolls = new AtomicInteger(Integer.MAX_VALUE);

    @BeforeEach
    void start() throws Exception {
        ollama = new MockWebServer();
        ollama.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                String body = request.getBody().readUtf8();
                if (path.equals("/api/ps")) {
                    calls.add("ps");
                    boolean resident = residentPolls.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0;
                    return json("{\"models\":[" + (resident ? "{\"model\":\"" + BIG + "\"}" : "") + "]}");
                }
                if (path.equals("/api/generate")) {
                    // keep_alive:0 is an unload; anything else is a load.
                    calls.add(body.contains("\"keep_alive\":0") ? "unload" : "load");
                    return json("{\"done\":true}");
                }
                calls.add("UNEXPECTED " + path);
                return new MockResponse().setResponseCode(404);
            }
        });
        ollama.start();
    }

    @AfterEach
    void stop() throws Exception {
        ollama.shutdown();
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }

    private LlmGatewayProperties props() {
        LlmGatewayProperties p = new LlmGatewayProperties();
        p.setProvider("openai-compatible");
        p.setBaseUrl(ollama.url("/v1").toString().replaceAll("/$", ""));
        p.setDefaultModel(BIG);
        p.setDefaultModelDownshift(SMALL);
        p.modelProfile().setEnabled(true);
        p.modelProfile().setEvictTimeoutSeconds(5);
        p.modelProfile().setPollIntervalMillis(20);
        return p;
    }

    private ModelProfileService service(LlmGatewayProperties p) {
        return new ModelProfileService(p, WebClient.builder());
    }

    @Test
    void evictionIsConfirmedBeforeTheIncomingModelIsLoaded() {
        LlmGatewayProperties p = props();
        // Still resident on the first poll, gone on the second: an implementation that trusted
        // the unload response instead of the poll would load one poll too early.
        residentPolls.set(1);

        service(p).switchTo(ModelProfileService.CODER_ACTIVE).block();

        assertThat(calls).containsExactly("unload", "ps", "ps", "load");
    }

    @Test
    void theDefaultChannelServesTheDownshiftedModelAfterTheSwitch() {
        LlmGatewayProperties p = props();
        ModelProfileService service = service(p);
        residentPolls.set(0);

        service.switchTo(ModelProfileService.CODER_ACTIVE).block();

        assertThat(service.activeProfile()).isEqualTo(ModelProfileService.CODER_ACTIVE);
        assertThat(p.channelModels().get(LlmChannel.DEFAULT)).isEqualTo(SMALL);
        // A channel with no model of its own follows the DEFAULT one through the downshift.
        assertThat(p.channelModels().get(LlmChannel.VISION)).isEqualTo(SMALL);

        service.switchTo(ModelProfileService.NORMAL).block();

        assertThat(p.channelModels().get(LlmChannel.DEFAULT)).isEqualTo(BIG);
    }

    @Test
    void anEvictionThatNeverCompletesFailsLoudlyAndLoadsNothing() {
        LlmGatewayProperties p = props();
        p.modelProfile().setEvictTimeoutSeconds(1);
        ModelProfileService service = service(p);
        residentPolls.set(Integer.MAX_VALUE); // it never leaves

        assertThatThrownBy(() -> service.switchTo(ModelProfileService.CODER_ACTIVE).block())
                .isInstanceOf(ModelProfileService.EvictionTimeoutException.class)
                .hasMessageContaining(BIG);

        // The three things that must NOT have happened: no load was issued, the gateway is still
        // serving the model it was serving, and the profile did not flip.
        assertThat(calls).doesNotContain("load");
        assertThat(p.channelModels().get(LlmChannel.DEFAULT)).isEqualTo(BIG);
        assertThat(service.activeProfile()).isEqualTo(ModelProfileService.NORMAL);
    }

    @Test
    void reSignallingTheActiveProfileEvictsNothing() {
        ModelProfileService service = service(props());

        service.switchTo(ModelProfileService.NORMAL).block();

        // A coder that restarted and re-announced itself must not cost a model reload.
        assertThat(calls).isEmpty();
    }

    @Test
    void anUnknownProfileIsRejectedWithoutTouchingTheEngine() {
        ModelProfileService service = service(props());

        assertThatThrownBy(() -> service.switchTo("turbo").block())
                .isInstanceOf(ModelProfileService.UnknownProfileException.class);
        assertThat(calls).isEmpty();
    }

    @Test
    void enablingTheProfileWithoutADownshiftTagFailsAtStartup() {
        LlmGatewayProperties p = props();
        p.setDefaultModelDownshift(null);

        // No tag is hardcoded anywhere, so an enabled profile with nothing to step down to is a
        // misconfiguration that must surface at boot, not on the first coder session.
        assertThatThrownBy(() -> service(p))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LLM_DEFAULT_MODEL_DOWNSHIFT");
    }

    @Test
    void theOllamaRootDropsTheOpenAiDialectSuffix() {
        // /api/* is not under /v1, and LLM_BASE_URL must carry /v1 for the chat path.
        assertThat(ModelProfileService.ollamaRoot("http://ollama:11434/v1")).isEqualTo("http://ollama:11434");
        assertThat(ModelProfileService.ollamaRoot("http://ollama:11434/v1/")).isEqualTo("http://ollama:11434");
        assertThat(ModelProfileService.ollamaRoot("http://ollama:11434")).isEqualTo("http://ollama:11434");
    }
}
