package dev.fedorov.ailife.agents.travel.http;

import dev.fedorov.ailife.contracts.travel.AddFundingInput;
import dev.fedorov.ailife.contracts.travel.CreateTripInput;
import dev.fedorov.ailife.contracts.travel.LogExchangeInput;
import dev.fedorov.ailife.contracts.travel.LogExpenseInput;
import dev.fedorov.ailife.contracts.travel.TripDto;
import dev.fedorov.ailife.contracts.travel.TripExchangeDto;
import dev.fedorov.ailife.contracts.travel.TripExpenseDto;
import dev.fedorov.ailife.contracts.travel.TripFundingDto;
import dev.fedorov.ailife.contracts.travel.TripLedgerDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * Calls the {@code mcp-travel} domain-MCP's {@code /internal/trips/*} passthroughs (EX-a) to drive the
 * trip wallet deterministically over HTTP — the wallet flow (EX-b) has already extracted a concrete
 * action, so it writes/reads directly rather than through an LLM-driven MCP tool call. Mirrors
 * {@link TravelProfileClient}. Reads that 204 (no active trip / no such trip) map to an empty Mono so the
 * flow can tell the owner to create a trip first.
 */
@Component
public class TripWalletClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final WebClient http;

    public TripWalletClient(@Qualifier("mcpTravelWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<TripDto> createTrip(CreateTripInput input) {
        return http.post().uri("/internal/trips")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(input)
                .retrieve().bodyToMono(TripDto.class).timeout(TIMEOUT);
    }

    /** The household's active (most recent non-closed) trip; 204 → empty. */
    public Mono<TripDto> getActiveTrip(UUID householdId) {
        return http.get()
                .uri(b -> b.path("/internal/trips/active").queryParam("householdId", householdId).build())
                .retrieve().bodyToMono(TripDto.class).timeout(TIMEOUT);
    }

    public Mono<TripFundingDto> addFunding(AddFundingInput input) {
        return http.post().uri("/internal/trips/fundings")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(input)
                .retrieve().bodyToMono(TripFundingDto.class).timeout(TIMEOUT);
    }

    public Mono<TripExchangeDto> logExchange(LogExchangeInput input) {
        return http.post().uri("/internal/trips/exchanges")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(input)
                .retrieve().bodyToMono(TripExchangeDto.class).timeout(TIMEOUT);
    }

    public Mono<TripExpenseDto> logExpense(LogExpenseInput input) {
        return http.post().uri("/internal/trips/expenses")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(input)
                .retrieve().bodyToMono(TripExpenseDto.class).timeout(TIMEOUT);
    }

    /** The full wallet (header + roster + raw ledger rows) for the deterministic tally; 204 → empty. */
    public Mono<TripLedgerDto> getTripLedger(UUID tripId, UUID householdId) {
        return http.get()
                .uri(b -> b.path("/internal/trips/" + tripId + "/ledger")
                        .queryParam("householdId", householdId).build())
                .retrieve().bodyToMono(TripLedgerDto.class).timeout(TIMEOUT);
    }
}
