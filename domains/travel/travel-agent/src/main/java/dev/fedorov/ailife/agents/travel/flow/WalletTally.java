package dev.fedorov.ailife.agents.travel.flow;

import java.math.BigDecimal;
import java.util.List;

/**
 * The deterministic result of {@link TripLedger} over a trip's ledger rows — a multi-currency family
 * trip wallet snapshot (plans/travel.md §Trip wallet / EX-b). Per-currency <b>remaining</b> balances +
 * a single <b>₽ tally</b> converted by the owner's stated acquisition rates; a currency whose home-rate
 * can't be resolved is listed in {@link #unratedCurrencies()} and left out of the ₽ total (shown in its
 * own currency, never silently converted). There is deliberately <b>no</b> per-member "who owes whom" —
 * this is one family budget, not a settlement.
 */
public record WalletTally(
        String homeCurrency,
        List<CurrencyLine> currencies,
        BigDecimal totalRemainingInHome,
        List<String> unratedCurrencies) {

    /**
     * One currency's line: total {@code funded} (inflows: fundings + exchange-in), total {@code spent}
     * (outflows: expenses + exchange-out), {@code remaining = funded - spent}, the weighted-average
     * {@code homeRate} to the home currency (null when unresolved), and {@code remainingInHome} (null
     * when the rate is unresolved).
     */
    public record CurrencyLine(
            String currency,
            BigDecimal funded,
            BigDecimal spent,
            BigDecimal remaining,
            BigDecimal homeRate,
            BigDecimal remainingInHome) {

        public boolean rateKnown() {
            return homeRate != null;
        }
    }

    public boolean hasUnrated() {
        return unratedCurrencies != null && !unratedCurrencies.isEmpty();
    }
}
