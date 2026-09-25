package dev.fedorov.ailife.agents.inventory.http;

import dev.fedorov.ailife.contracts.inventory.ContainerDto;
import dev.fedorov.ailife.contracts.inventory.ContainerViewDto;
import dev.fedorov.ailife.contracts.inventory.ItemDto;
import dev.fedorov.ailife.contracts.inventory.ItemLocationDto;
import dev.fedorov.ailife.contracts.inventory.SaveContainerInput;
import dev.fedorov.ailife.contracts.inventory.SaveItemInput;
import dev.fedorov.ailife.contracts.inventory.SaveZoneInput;
import dev.fedorov.ailife.contracts.inventory.StorageZoneDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Calls the {@code mcp-inventory} domain-MCP's {@code /internal} passthroughs to run a packing
 * session (IN-c). The flows have already decided what they want, so they act deterministically over
 * HTTP rather than through an LLM-driven MCP tool call. Mirrors docs-agent's {@code DocumentClient}.
 */
@Component
public class InventoryClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final WebClient http;

    public InventoryClient(@Qualifier("mcpInventoryWebClient") WebClient http) {
        this.http = http;
    }

    /** Create or resolve a storage zone by the name the user said (upserted MCP-side). */
    public Mono<StorageZoneDto> saveZone(SaveZoneInput input) {
        return http.post()
                .uri("/internal/zones")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input)
                .retrieve()
                .bodyToMono(StorageZoneDto.class)
                .timeout(TIMEOUT);
    }

    /** Create a container (null id) or update one in place — its qrToken is never re-minted. */
    public Mono<ContainerDto> saveContainer(SaveContainerInput input) {
        return http.post()
                .uri("/internal/containers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input)
                .retrieve()
                .bodyToMono(ContainerDto.class)
                .timeout(TIMEOUT);
    }

    /** The container with its zone + contents — what a card renders and a scan resolves to. */
    public Mono<ContainerViewDto> getContainer(UUID id) {
        return http.get()
                .uri("/internal/containers/{id}", id)
                .retrieve()
                .bodyToMono(ContainerViewDto.class)
                .timeout(TIMEOUT);
    }

    /**
     * The container a printed label resolves to (IN-f). The {@code qrToken} <b>is</b> the lookup key —
     * it is the only thing the sticker carries — so a scan needs neither a household nor a code, which
     * is what lets the person unpacking open a link and get an answer. An unknown token (a sticker from
     * a box that was deleted, a foreign QR) is {@link Mono#empty()}, not an error: that is a normal
     * answer the caller turns into "не нашёл коробку по этой этикетке".
     */
    public Mono<ContainerViewDto> getContainerByToken(String qrToken) {
        return http.get()
                .uri("/internal/containers/by-token/{qrToken}", qrToken)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, resp -> Mono.empty())
                .bodyToMono(ContainerViewDto.class)
                .timeout(TIMEOUT);
    }

    /**
     * The household's containers — how an on-demand "коробка B-07" is resolved to its id (IN-d). The
     * store has no by-code lookup on purpose: a household has tens of containers, so matching the
     * spoken code or label agent-side costs one call and keeps the MCP surface small.
     */
    public Mono<List<ContainerDto>> listContainers(UUID householdId, Integer limit) {
        return http.get()
                .uri(b -> {
                    b.path("/internal/containers").queryParam("householdId", householdId);
                    if (limit != null) b.queryParam("limit", limit);
                    return b.build();
                })
                .retrieve()
                .bodyToFlux(ContainerDto.class)
                .collectList()
                .timeout(TIMEOUT);
    }

    public Mono<ItemDto> saveItem(SaveItemInput input) {
        return http.post()
                .uri("/internal/items")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input)
                .retrieve()
                .bodyToMono(ItemDto.class)
                .timeout(TIMEOUT);
    }

    /**
     * "Где лежит X" (IN-e): each hit carries its container and zone, so one call answers *where*.
     * An empty result is an empty list, not an error.
     */
    public Mono<List<ItemLocationDto>> searchItems(UUID householdId, String query, Integer limit) {
        return http.get()
                .uri(b -> {
                    b.path("/internal/items/search")
                            .queryParam("householdId", householdId)
                            .queryParam("query", query);
                    if (limit != null) b.queryParam("limit", limit);
                    return b.build();
                })
                .retrieve()
                .bodyToFlux(ItemLocationDto.class)
                .collectList()
                .timeout(TIMEOUT);
    }
}
