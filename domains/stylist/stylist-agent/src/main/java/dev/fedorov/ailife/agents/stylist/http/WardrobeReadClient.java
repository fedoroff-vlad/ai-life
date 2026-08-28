package dev.fedorov.ailife.agents.stylist.http;

import dev.fedorov.ailife.contracts.wardrobe.StyleProfileDto;
import dev.fedorov.ailife.contracts.wardrobe.WardrobeItemDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Reads the wardrobe over {@code mcp-wardrobe}'s {@code GET /internal/items} passthrough (ST-e), and the
 * person's style profile via the shared {@link StyleProfileClient} (ADR-0005 slice 7 — the style-profile
 * get/set now lives once in the generic {@code PersonalizationProfileClient}; this class delegates its
 * profile read rather than hand-rolling a second copy). The capsule flow gathers the household's garments
 * and the person's style profile from here. Read-only; the deterministic, MockWebServer-testable path
 * (not the SSE transport). A 404 on the profile (none set yet) maps to an empty Mono so the gather just
 * omits it.
 */
@Component
public class WardrobeReadClient {

    private final WebClient http;
    private final StyleProfileClient styleProfiles;

    public WardrobeReadClient(@Qualifier("mcpWardrobeWebClient") WebClient http,
                              StyleProfileClient styleProfiles) {
        this.http = http;
        this.styleProfiles = styleProfiles;
    }

    public Mono<List<WardrobeItemDto>> listItems(UUID householdId, String category) {
        return http.get()
                .uri(b -> {
                    b.path("/internal/items").queryParam("householdId", householdId);
                    if (category != null && !category.isBlank()) b.queryParam("category", category);
                    return b.build();
                })
                .retrieve()
                .bodyToFlux(WardrobeItemDto.class)
                .collectList()
                .timeout(Duration.ofSeconds(10));
    }

    /** Delegates to the shared generic style-profile client (404 → empty). */
    public Mono<StyleProfileDto> getProfile(UUID householdId, UUID ownerId) {
        return styleProfiles.get(householdId, ownerId);
    }
}
