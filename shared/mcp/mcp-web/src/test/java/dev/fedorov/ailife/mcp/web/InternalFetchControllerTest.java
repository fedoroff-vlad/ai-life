package dev.fedorov.ailife.mcp.web;

import dev.fedorov.ailife.contracts.web.FetchUrlInput;
import dev.fedorov.ailife.contracts.web.PageContent;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-b: the {@code POST /internal/fetch} passthrough fetches a page (jsoup) and returns its
 * readable text with boilerplate stripped. A MockWebServer serves the HTML — jsoup connects to
 * it over real HTTP, so no external network is needed. {@code fetch-max-chars} is dropped to a
 * tiny value to exercise truncation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "mcp-web.fetch-max-chars=40")
@AutoConfigureWebTestClient
class InternalFetchControllerTest {

    static MockWebServer site;

    @BeforeAll
    static void start() throws Exception {
        site = new MockWebServer();
        site.start();
    }

    @AfterAll
    static void stop() throws Exception {
        site.shutdown();
    }

    @Autowired WebTestClient web;

    @Test
    void fetchExtractsReadableTextStrippingBoilerplateAndTruncates() {
        site.enqueue(new MockResponse()
                .setHeader("content-type", "text/html; charset=utf-8")
                .setBody("""
                        <html><head><title>Bed Leveling Guide</title></head>
                        <body>
                          <nav>Home About Contact</nav>
                          <script>trackAnalytics();</script>
                          <article>
                            <h1>Leveling</h1>
                            <p>Heat the bed to sixty degrees before you start the paper test.</p>
                          </article>
                          <footer>Copyright 2026 boilerplate</footer>
                        </body></html>"""));

        String url = site.url("/guide").toString();

        PageContent page = web.post().uri("/internal/fetch")
                .bodyValue(new FetchUrlInput(url))
                .exchange()
                .expectStatus().isOk()
                .expectBody(PageContent.class)
                .returnResult().getResponseBody();

        assertThat(page).isNotNull();
        assertThat(page.url()).isEqualTo(url);
        assertThat(page.title()).isEqualTo("Bed Leveling Guide");
        // Article text is kept; nav/script/footer boilerplate is gone.
        assertThat(page.text())
                .contains("Leveling")
                .doesNotContain("Home About Contact")
                .doesNotContain("trackAnalytics")
                .doesNotContain("Copyright 2026");
        // Capped at 40 chars → truncated flag set.
        assertThat(page.truncated()).isTrue();
        assertThat(page.text().length()).isEqualTo(40);
    }

    @Test
    void unreachableUrlReturnsEmptyTextNotError() {
        // A port nothing listens on → jsoup connect fails → best-effort empty text, still 200.
        PageContent page = web.post().uri("/internal/fetch")
                .bodyValue(new FetchUrlInput("http://localhost:1/nope"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(PageContent.class)
                .returnResult().getResponseBody();

        assertThat(page).isNotNull();
        assertThat(page.text()).isEmpty();
        assertThat(page.truncated()).isFalse();
    }
}
