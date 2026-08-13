package dev.fedorov.ailife.mcp.travel.tools;

import dev.fedorov.ailife.contracts.travel.AddFundingInput;
import dev.fedorov.ailife.contracts.travel.AddTripMemberInput;
import dev.fedorov.ailife.contracts.travel.CreateTripInput;
import dev.fedorov.ailife.contracts.travel.LogExchangeInput;
import dev.fedorov.ailife.contracts.travel.LogExpenseInput;
import dev.fedorov.ailife.contracts.travel.TripDto;
import dev.fedorov.ailife.contracts.travel.TripExchangeDto;
import dev.fedorov.ailife.contracts.travel.TripExpenseDto;
import dev.fedorov.ailife.contracts.travel.TripFundingDto;
import dev.fedorov.ailife.contracts.travel.TripLedgerDto;
import dev.fedorov.ailife.contracts.travel.TripMemberDto;
import dev.fedorov.ailife.mcp.travel.domain.Trip;
import dev.fedorov.ailife.mcp.travel.domain.TripExchange;
import dev.fedorov.ailife.mcp.travel.domain.TripExchangeRepository;
import dev.fedorov.ailife.mcp.travel.domain.TripExpense;
import dev.fedorov.ailife.mcp.travel.domain.TripExpenseRepository;
import dev.fedorov.ailife.mcp.travel.domain.TripFunding;
import dev.fedorov.ailife.mcp.travel.domain.TripFundingRepository;
import dev.fedorov.ailife.mcp.travel.domain.TripMember;
import dev.fedorov.ailife.mcp.travel.domain.TripMemberRepository;
import dev.fedorov.ailife.mcp.travel.domain.TripRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Trip-wallet store (EX-a): source-of-truth CRUD over the persisted {@code travel.trip*} tables — the
 * multi-currency family trip budget (plans/travel.md §Trip wallet, #437). Persistence only: it records
 * the trip header, its roster, and the ledger rows (fundings = inflows, exchanges = paired out/inflows,
 * expenses = outflows). Balance math (per-currency remaining + the ₽ tally) is <b>not</b> here — it is
 * deterministic Java in the travel-agent's {@code TripLedger} (EX-b) reading these rows.
 *
 * <p>Tenant rule: a trip is scoped to its {@code householdId}; {@code getTrip}/{@code getTripLedger}
 * only return within the matching household. Ledger writes take a {@code tripId} the caller already
 * owns (returned by {@code createTrip}).
 */
@Component
public class TripMcpTools {

    private final TripRepository trips;
    private final TripMemberRepository members;
    private final TripFundingRepository fundings;
    private final TripExchangeRepository exchanges;
    private final TripExpenseRepository expenses;

    public TripMcpTools(TripRepository trips, TripMemberRepository members,
                        TripFundingRepository fundings, TripExchangeRepository exchanges,
                        TripExpenseRepository expenses) {
        this.trips = trips;
        this.members = members;
        this.fundings = fundings;
        this.exchanges = exchanges;
        this.expenses = expenses;
    }

    @Tool(description = """
            Create a family trip (the header of a multi-currency trip wallet). `householdId` and `title`
            are required; `ownerId` is the creating user. `destination`/`startDate`/`endDate` are
            optional; `homeCurrency` defaults to RUB — the currency the ₽ tally converts into. The trip
            starts in status 'planning'.
            """)
    @Transactional
    public TripDto createTrip(CreateTripInput input) {
        requireField(input.householdId(), "householdId");
        requireField(input.title(), "title");
        String home = normalizeCurrency(input.homeCurrency());
        Trip trip = new Trip(UUID.randomUUID(), input.householdId(), input.ownerId(),
                input.title().trim(), blankToNull(input.destination()),
                input.startDate(), input.endDate(), home == null ? "RUB" : home);
        return trips.save(trip).toDto();
    }

    @Tool(description = """
            Add a participant to a trip roster. `tripId` and `label` are required. Supply at most one of
            `userId` (a space member) or `personId` (a recorded person); both null is a label-only
            member. Roster is context only — no role, share or settlement.
            """)
    @Transactional
    public TripMemberDto addTripMember(AddTripMemberInput input) {
        requireField(input.tripId(), "tripId");
        requireField(input.label(), "label");
        if (input.userId() != null && input.personId() != null) {
            throw new IllegalArgumentException("A trip member has at most one of userId/personId");
        }
        requireTrip(input.tripId());
        TripMember member = new TripMember(UUID.randomUUID(), input.tripId(),
                input.userId(), input.personId(), input.label().trim());
        return members.save(member).toDto();
    }

    @Tool(description = """
            Remove a participant from a trip roster by member id. Returns true if a row was removed,
            false if no such member existed.
            """)
    @Transactional
    public boolean removeTripMember(UUID memberId) {
        requireField(memberId, "memberId");
        if (!members.existsById(memberId)) return false;
        members.deleteById(memberId);
        return true;
    }

    @Tool(description = """
            Record a currency acquired for a trip (brought from home) — an inflow. `tripId`, `currency`
            and `amount` (>= 0) are required. `rateToHome` is the optional owner-stated ₽ rate at
            acquisition; null leaves the currency unrated (flagged in the ₽ tally). For an on-site swap
            from another held currency use logExchange instead, so the source balance is debited.
            """)
    @Transactional
    public TripFundingDto addFunding(AddFundingInput input) {
        requireField(input.tripId(), "tripId");
        requireField(input.currency(), "currency");
        requireNonNegative(input.amount(), "amount");
        if (input.rateToHome() != null) requireNonNegative(input.rateToHome(), "rateToHome");
        requireTrip(input.tripId());
        TripFunding funding = new TripFunding(UUID.randomUUID(), input.tripId(),
                normalizeCurrency(input.currency()), input.amount(), input.rateToHome(),
                blankToNull(input.note()));
        return fundings.save(funding).toDto();
    }

    @Tool(description = """
            Log an on-site currency swap: `fromAmount` of `fromCurrency` exchanged for `toAmount` of
            `toCurrency`. One paired op — an outflow of the source and an inflow of the acquired
            currency, so the ₽ tally is not double-counted. All of tripId, fromCurrency, fromAmount,
            toCurrency, toAmount are required (amounts >= 0); the two currencies must differ. No rate
            field — it is implied by the two amounts.
            """)
    @Transactional
    public TripExchangeDto logExchange(LogExchangeInput input) {
        requireField(input.tripId(), "tripId");
        requireField(input.fromCurrency(), "fromCurrency");
        requireField(input.toCurrency(), "toCurrency");
        requireNonNegative(input.fromAmount(), "fromAmount");
        requireNonNegative(input.toAmount(), "toAmount");
        String from = normalizeCurrency(input.fromCurrency());
        String to = normalizeCurrency(input.toCurrency());
        if (from.equals(to)) {
            throw new IllegalArgumentException("An exchange must be between two different currencies");
        }
        requireTrip(input.tripId());
        TripExchange exchange = new TripExchange(UUID.randomUUID(), input.tripId(),
                from, input.fromAmount(), to, input.toAmount(), blankToNull(input.note()));
        return exchanges.save(exchange).toDto();
    }

    @Tool(description = """
            Log a spend on a trip — an outflow. `tripId`, `currency` and `amount` (>= 0) are required;
            `category`/`description` are optional context. There is no paid-by field (settlement is cut).
            """)
    @Transactional
    public TripExpenseDto logExpense(LogExpenseInput input) {
        requireField(input.tripId(), "tripId");
        requireField(input.currency(), "currency");
        requireNonNegative(input.amount(), "amount");
        requireTrip(input.tripId());
        TripExpense expense = new TripExpense(UUID.randomUUID(), input.tripId(),
                normalizeCurrency(input.currency()), input.amount(),
                blankToNull(input.category()), blankToNull(input.description()));
        return expenses.save(expense).toDto();
    }

    @Tool(description = """
            Get a trip header by id, scoped to its household. Returns null if the trip does not exist or
            belongs to another household.
            """)
    @Transactional(readOnly = true)
    public TripDto getTrip(UUID tripId, UUID householdId) {
        requireField(tripId, "tripId");
        requireField(householdId, "householdId");
        return trips.findByIdAndHouseholdId(tripId, householdId).map(Trip::toDto).orElse(null);
    }

    @Tool(description = """
            Get the household's active trip — the most recently created trip that is not yet 'closed'.
            The wallet flow attaches fund/exchange/spend/tally to it without the owner naming a trip.
            Returns null when the household has no open trip.
            """)
    @Transactional(readOnly = true)
    public TripDto getActiveTrip(UUID householdId) {
        requireField(householdId, "householdId");
        return trips.findFirstByHouseholdIdAndStatusNotOrderByCreatedAtDesc(householdId, "closed")
                .map(Trip::toDto).orElse(null);
    }

    @Tool(description = """
            Get the full trip wallet by id, scoped to its household: the trip header, its roster, and the
            raw ledger rows (fundings, exchanges, expenses). Returns null if the trip does not exist or
            belongs to another household. Balance math is computed by the caller (EX-b), not here.
            """)
    @Transactional(readOnly = true)
    public TripLedgerDto getTripLedger(UUID tripId, UUID householdId) {
        requireField(tripId, "tripId");
        requireField(householdId, "householdId");
        Trip trip = trips.findByIdAndHouseholdId(tripId, householdId).orElse(null);
        if (trip == null) return null;
        List<TripMemberDto> roster = members.findByTripIdOrderByCreatedAt(tripId)
                .stream().map(TripMember::toDto).toList();
        List<TripFundingDto> funds = fundings.findByTripIdOrderByAcquiredAt(tripId)
                .stream().map(TripFunding::toDto).toList();
        List<TripExchangeDto> swaps = exchanges.findByTripIdOrderByExchangedAt(tripId)
                .stream().map(TripExchange::toDto).toList();
        List<TripExpenseDto> spends = expenses.findByTripIdOrderBySpentAt(tripId)
                .stream().map(TripExpense::toDto).toList();
        return new TripLedgerDto(trip.toDto(), roster, funds, swaps, spends);
    }

    private void requireTrip(UUID tripId) {
        if (!trips.existsById(tripId)) {
            throw new IllegalArgumentException("Unknown trip: " + tripId);
        }
    }

    private static String normalizeCurrency(String currency) {
        if (currency == null) return null;
        String c = currency.trim();
        return c.isEmpty() ? null : c.toUpperCase();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static void requireField(Object value, String name) {
        if (value == null) throw new IllegalArgumentException("Missing required field: " + name);
        if (value instanceof String s && s.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + name);
        }
    }

    private static void requireNonNegative(BigDecimal value, String name) {
        if (value == null) throw new IllegalArgumentException("Missing required field: " + name);
        if (value.signum() < 0) throw new IllegalArgumentException("Field must be >= 0: " + name);
    }
}
