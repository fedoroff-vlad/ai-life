package dev.fedorov.ailife.mcp.icsimport.ics;

import dev.fedorov.ailife.mcp.icsimport.config.McpIcsImportProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * GETs an ICS body from a user-supplied URL. Apple/Google subscription URLs commonly
 * use {@code webcal://}; we normalise that to {@code https://} which both providers
 * accept. The body is capped via {@code maxIcsBytes} to avoid pulling pathological
 * feeds.
 */
@Component
public class IcsFetcher {

    private static final Logger log = LoggerFactory.getLogger(IcsFetcher.class);

    private final WebClient http;
    private final McpIcsImportProperties props;

    public IcsFetcher(WebClient icsHttpClient, McpIcsImportProperties props) {
        this.http = icsHttpClient;
        this.props = props;
    }

    public String fetch(String url) {
        String normalised = normalise(url);
        log.debug("GET {}", normalised);
        try {
            return http.get()
                    .uri(normalised)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();
        } catch (DataBufferLimitException e) {
            throw new IllegalStateException(
                    "ICS body exceeds max " + props.getMaxIcsBytes() + " bytes: " + normalised, e);
        }
    }

    private static String normalise(String url) {
        if (url == null) {
            throw new IllegalArgumentException("ICS url is null");
        }
        if (url.startsWith("webcal://")) {
            return "https://" + url.substring("webcal://".length());
        }
        return url;
    }
}
