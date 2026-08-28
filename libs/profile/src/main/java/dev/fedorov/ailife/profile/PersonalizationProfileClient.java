package dev.fedorov.ailife.profile;

import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * The generic typed {@code /internal/<domain>-profile} client shape (ADR-0005), structurally identical
 * across all five personalization domains (briefing/creator/nutrition/travel/stylist): a {@code GET} by
 * household [+ owner] (404 → empty, so a read falls back to defaults) and a {@code POST} upsert. The
 * profiler flow has already extracted a concrete {@code set}-input (and run any post-step like briefing's
 * geocode), so writes go over deterministic HTTP rather than an LLM-driven MCP tool call.
 *
 * <p>The {@link WebClient} is owned by the consumer (it binds the domain-MCP base URL from its own
 * properties, e.g. {@code mcpBriefingWebClient}); this class is purely the request shape. {@code D} is the
 * domain's profile DTO, {@code I} its set-input.
 *
 * @param <D> the profile DTO returned by {@code get}/{@code set}
 * @param <I> the set-input posted by {@code set}
 */
public class PersonalizationProfileClient<D, I> {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final WebClient http;
    private final String path;
    private final Class<D> dtoType;
    private final Duration timeout;

    /** @param path the MCP internal path, e.g. {@code "/internal/briefing-profile"}. */
    public PersonalizationProfileClient(WebClient http, String path, Class<D> dtoType) {
        this(http, path, dtoType, DEFAULT_TIMEOUT);
    }

    public PersonalizationProfileClient(WebClient http, String path, Class<D> dtoType, Duration timeout) {
        this.http = http;
        this.path = path;
        this.dtoType = dtoType;
        this.timeout = timeout;
    }

    /**
     * Read a member's profile ({@code null} ownerId = the household-default). A 404 (none set yet) maps to
     * an empty Mono, so a read flow falls back to defaults (or the shared {@link ProfileScopeResolver}).
     */
    public Mono<D> get(UUID householdId, UUID ownerId) {
        return http.get()
                .uri(b -> {
                    b.path(path).queryParam("householdId", householdId);
                    if (ownerId != null) {
                        b.queryParam("ownerId", ownerId);
                    }
                    return b.build();
                })
                .retrieve()
                .bodyToMono(dtoType)
                .timeout(timeout)
                .onErrorResume(WebClientResponseException.NotFound.class, e -> Mono.empty());
    }

    /** Upsert the member's profile from an already-built set-input. */
    public Mono<D> set(I input) {
        return http.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input)
                .retrieve()
                .bodyToMono(dtoType)
                .timeout(timeout);
    }
}
