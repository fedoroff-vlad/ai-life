package dev.fedorov.ailife.contracts.travel;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The full aggregate read of a trip wallet (plans/travel.md §Trip wallet, #437): the trip header, its
 * roster, and the raw ledger rows — fundings (inflows), exchanges (paired out/inflows) and expenses
 * (outflows). EX-a returns the rows as-is; the deterministic per-currency balance and ₽ tally are
 * computed over these rows in EX-b (no balance math here).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TripLedgerDto(
        TripDto trip,
        List<TripMemberDto> members,
        List<TripFundingDto> fundings,
        List<TripExchangeDto> exchanges,
        List<TripExpenseDto> expenses) {
}
