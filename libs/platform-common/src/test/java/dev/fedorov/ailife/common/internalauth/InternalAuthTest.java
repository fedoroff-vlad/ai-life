package dev.fedorov.ailife.common.internalauth;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webclient.WebClientCustomizer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.WebFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared-secret {@code /internal/*} guard (#630): outbound stamps the header, inbound (servlet +
 * reactive) rejects a missing/wrong secret, and everything is a no-op when disabled (empty secret) or
 * the path is not internal.
 */
class InternalAuthTest {

    private static final String SECRET = "s3cr3t";

    private InternalAuthProperties enabled() {
        InternalAuthProperties p = new InternalAuthProperties();
        p.setSharedSecret(SECRET);
        return p;
    }

    // ---- outbound customizer -------------------------------------------------------------------

    private MockWebServer server;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private WebClient clientWith(InternalAuthProperties props) {
        WebClientCustomizer customizer =
                new InternalAuthAutoConfiguration.Outbound().internalAuthWebClientCustomizer(props);
        WebClient.Builder builder = WebClient.builder();
        customizer.customize(builder);
        return builder.baseUrl(server.url("/").toString()).build();
    }

    @Test
    void outboundStampsSecretOnInternalPathOnly() throws Exception {
        server.enqueue(new MockResponse().setBody("ok"));
        server.enqueue(new MockResponse().setBody("ok"));
        WebClient client = clientWith(enabled());

        client.get().uri("/internal/tools/x").retrieve().bodyToMono(String.class).block();
        client.get().uri("/v1/public/y").retrieve().bodyToMono(String.class).block();

        RecordedRequest internal = server.takeRequest();
        RecordedRequest external = server.takeRequest();
        assertThat(internal.getPath()).isEqualTo("/internal/tools/x");
        assertThat(internal.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + SECRET);
        // A non-internal call (e.g. llm-gateway /v1/chat) is untouched.
        assertThat(external.getHeader(HttpHeaders.AUTHORIZATION)).isNull();
    }

    @Test
    void outboundAddsNothingWhenDisabled() throws Exception {
        server.enqueue(new MockResponse().setBody("ok"));
        clientWith(new InternalAuthProperties()) // empty secret = disabled
                .get().uri("/internal/tools/x").retrieve().bodyToMono(String.class).block();

        assertThat(server.takeRequest().getHeader(HttpHeaders.AUTHORIZATION)).isNull();
    }

    // ---- inbound reactive WebFilter ------------------------------------------------------------

    private static Mono<Void> passThrough(MockServerWebExchange ex) {
        WebFilterChain chain = e -> Mono.empty();
        return new InternalAuthWebFilter(exchangeSecret()).filter(ex, chain);
    }

    private static InternalAuthProperties exchangeSecret() {
        InternalAuthProperties p = new InternalAuthProperties();
        p.setSharedSecret(SECRET);
        return p;
    }

    @Test
    void reactiveRejectsInternalWithoutSecret() {
        MockServerWebExchange ex = MockServerWebExchange.from(
                MockServerHttpRequest.post("/internal/tools/x"));
        passThrough(ex).block();
        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void reactiveAllowsInternalWithSecret() {
        MockServerWebExchange ex = MockServerWebExchange.from(
                MockServerHttpRequest.post("/internal/tools/x")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + SECRET));
        passThrough(ex).block();
        assertThat(ex.getResponse().getStatusCode()).isNull(); // untouched → chain handles it
    }

    @Test
    void reactiveIgnoresNonInternalPath() {
        MockServerWebExchange ex = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/health"));
        passThrough(ex).block();
        assertThat(ex.getResponse().getStatusCode()).isNull();
    }

    @Test
    void reactiveDisabledAllowsEverything() {
        MockServerWebExchange ex = MockServerWebExchange.from(
                MockServerHttpRequest.post("/internal/tools/x"));
        new InternalAuthWebFilter(new InternalAuthProperties())
                .filter(ex, e -> Mono.empty()).block();
        assertThat(ex.getResponse().getStatusCode()).isNull();
    }

    // ---- inbound servlet filter ----------------------------------------------------------------

    @Test
    void servletRejectsInternalWithoutSecret() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/send");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        new InternalAuthServletFilter(enabled()).doFilter(req, resp, new MockFilterChain());
        assertThat(resp.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void servletAllowsInternalWithSecret() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/send");
        req.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + SECRET);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new InternalAuthServletFilter(enabled()).doFilter(req, resp, chain);
        assertThat(resp.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(chain.getRequest()).isNotNull(); // chain was invoked
    }

    @Test
    void servletDisabledAllowsEverything() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/internal/send");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new InternalAuthServletFilter(new InternalAuthProperties()).doFilter(req, resp, chain);
        assertThat(chain.getRequest()).isNotNull();
    }
}
