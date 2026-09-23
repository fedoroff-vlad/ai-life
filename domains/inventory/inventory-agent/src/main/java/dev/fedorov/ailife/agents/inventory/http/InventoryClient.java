package dev.fedorov.ailife.agents.inventory.http;

import dev.fedorov.ailife.contracts.inventory.ContainerDto;
import dev.fedorov.ailife.contracts.inventory.ContainerViewDto;
import dev.fedorov.ailife.contracts.inventory.ItemDto;
import dev.fedorov.ailife.contracts.inventory.SaveContainerInput;
import dev.fedorov.ailife.contracts.inventory.SaveItemInput;
import dev.fedorov.ailife.contracts.inventory.SaveZoneInput;
import dev.fedorov.ailife.contracts.inventory.StorageZoneDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
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

    public Mono<ItemDto> saveItem(SaveItemInput input) {
        return http.post()
                .uri("/internal/items")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input)
                .retrieve()
                .bodyToMono(ItemDto.class)
                .timeout(TIMEOUT);
    }
}
