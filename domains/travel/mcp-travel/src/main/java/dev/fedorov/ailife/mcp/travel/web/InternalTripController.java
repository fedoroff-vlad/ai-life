package dev.fedorov.ailife.mcp.travel.web;

import dev.fedorov.ailife.contracts.travel.AddFundingInput;
import dev.fedorov.ailife.contracts.travel.AddTripMemberInput;
import dev.fedorov.ailife.contracts.travel.CreateTripInput;
import dev.fedorov.ailife.contracts.travel.LogExchangeInput;
import dev.fedorov.ailife.contracts.travel.LogExpenseInput;
import dev.fedorov.ailife.contracts.travel.TripDto;
import dev.fedorov.ailife.contracts.travel.TripLedgerDto;
import dev.fedorov.ailife.mcp.travel.tools.TripMcpTools;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Non-MCP REST passthrough for the trip wallet (EX-a). The travel-agent (EX-b) drives the wallet flow
 * deterministically over HTTP rather than through LLM-driven MCP tool calls; each endpoint delegates
 * straight to {@link TripMcpTools} so validation and tenant scoping apply identically. Mirrors
 * {@code InternalTravelProfileController}. {@code IllegalArgumentException} → 400; an absent/out-of-tenant
 * trip read → 204.
 */
@RestController
@RequestMapping("/internal/trips")
public class InternalTripController {

    private final TripMcpTools tools;

    public InternalTripController(TripMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateTripInput input) {
        return guard(() -> ResponseEntity.ok(tools.createTrip(input)));
    }

    @PostMapping("/members")
    public ResponseEntity<?> addMember(@RequestBody AddTripMemberInput input) {
        return guard(() -> ResponseEntity.ok(tools.addTripMember(input)));
    }

    @DeleteMapping("/members/{memberId}")
    public ResponseEntity<?> removeMember(@PathVariable UUID memberId) {
        boolean removed = tools.removeTripMember(memberId);
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/fundings")
    public ResponseEntity<?> addFunding(@RequestBody AddFundingInput input) {
        return guard(() -> ResponseEntity.ok(tools.addFunding(input)));
    }

    @PostMapping("/exchanges")
    public ResponseEntity<?> logExchange(@RequestBody LogExchangeInput input) {
        return guard(() -> ResponseEntity.ok(tools.logExchange(input)));
    }

    @PostMapping("/expenses")
    public ResponseEntity<?> logExpense(@RequestBody LogExpenseInput input) {
        return guard(() -> ResponseEntity.ok(tools.logExpense(input)));
    }

    @GetMapping("/active")
    public ResponseEntity<TripDto> active(@RequestParam UUID householdId) {
        TripDto dto = tools.getActiveTrip(householdId);
        return dto == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(dto);
    }

    @PostMapping("/{tripId}/close")
    public ResponseEntity<TripDto> close(@PathVariable UUID tripId, @RequestParam UUID householdId) {
        TripDto dto = tools.closeTrip(tripId, householdId);
        return dto == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(dto);
    }

    @GetMapping("/{tripId}")
    public ResponseEntity<TripDto> get(@PathVariable UUID tripId, @RequestParam UUID householdId) {
        TripDto dto = tools.getTrip(tripId, householdId);
        return dto == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(dto);
    }

    @GetMapping("/{tripId}/ledger")
    public ResponseEntity<TripLedgerDto> ledger(@PathVariable UUID tripId, @RequestParam UUID householdId) {
        TripLedgerDto dto = tools.getTripLedger(tripId, householdId);
        return dto == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(dto);
    }

    private static ResponseEntity<?> guard(java.util.function.Supplier<ResponseEntity<?>> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
