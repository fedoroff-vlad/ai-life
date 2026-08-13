package dev.fedorov.ailife.agents.travel.flow;

import dev.fedorov.ailife.contracts.travel.TripDto;
import dev.fedorov.ailife.contracts.travel.TripExchangeDto;
import dev.fedorov.ailife.contracts.travel.TripExpenseDto;
import dev.fedorov.ailife.contracts.travel.TripFundingDto;
import dev.fedorov.ailife.contracts.travel.TripLedgerDto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The deterministic multi-currency balance engine for the trip wallet (plans/travel.md §Trip wallet /
 * EX-b). Pure Java over the raw {@link TripLedgerDto} rows produced by {@code mcp-travel} (EX-a) — never
 * the LLM: balance math is a correctness/privacy boundary, like {@code libs/sharing}.
 *
 * <p>For each currency it computes <b>remaining = inflows − outflows</b>, where an inflow is a funding
 * or the acquired side of an exchange, and an outflow is an expense or the spent side of an exchange —
 * so an on-site swap (₽→฿) debits the source and credits the target and the ₽ tally is never
 * double-counted. The <b>₽ home-rate</b> of a currency is the weighted-average acquisition rate across
 * its inflows: a funding contributes {@code (amount, rate_to_home)}; an exchange-in contributes
 * {@code (toAmount, fromAmount × fromHomeRate / toAmount)} where {@code fromHomeRate} is the source
 * currency's funding-derived rate (single-level; an unresolvable hop contributes nothing rather than
 * guessing). The home currency itself is rate 1. A currency with <b>no</b> resolvable rate is flagged
 * "курс не задан" and left out of the ₽ total — shown in its own currency, never silently converted.
 */
public final class TripLedger {

    private static final int MONEY_SCALE = 2;    // ₽ display scale
    private static final int RATE_SCALE = 6;     // internal rate precision
    private static final String DEFAULT_HOME = "RUB";

    private TripLedger() {
    }

    public static WalletTally compute(TripLedgerDto ledger) {
        TripDto trip = ledger == null ? null : ledger.trip();
        String home = (trip != null && trip.homeCurrency() != null && !trip.homeCurrency().isBlank())
                ? trip.homeCurrency() : DEFAULT_HOME;

        List<TripFundingDto> fundings = safe(ledger == null ? null : ledger.fundings());
        List<TripExchangeDto> exchanges = safe(ledger == null ? null : ledger.exchanges());
        List<TripExpenseDto> expenses = safe(ledger == null ? null : ledger.expenses());

        // Encounter-ordered set of every currency the wallet touches (home first for a stable lead row).
        LinkedHashSet<String> currencies = new LinkedHashSet<>();
        currencies.add(home);
        for (TripFundingDto f : fundings) currencies.add(f.currency());
        for (TripExchangeDto x : exchanges) { currencies.add(x.fromCurrency()); currencies.add(x.toCurrency()); }
        for (TripExpenseDto e : expenses) currencies.add(e.currency());

        // Pass 1 — a base home-rate per currency from fundings alone (+ home = 1). Used to price the
        // acquired side of an exchange without recursion (single-level).
        Map<String, BigDecimal> baseRate = new LinkedHashMap<>();
        for (String ccy : currencies) {
            baseRate.put(ccy, ccy.equals(home) ? BigDecimal.ONE : fundingRate(ccy, fundings));
        }

        List<WalletTally.CurrencyLine> lines = new ArrayList<>();
        List<String> unrated = new ArrayList<>();
        BigDecimal totalRemainingHome = BigDecimal.ZERO;

        for (String ccy : currencies) {
            BigDecimal inflow = BigDecimal.ZERO;
            BigDecimal outflow = BigDecimal.ZERO;
            for (TripFundingDto f : fundings) {
                if (ccy.equals(f.currency())) inflow = inflow.add(amt(f.amount()));
            }
            for (TripExchangeDto x : exchanges) {
                if (ccy.equals(x.toCurrency())) inflow = inflow.add(amt(x.toAmount()));
                if (ccy.equals(x.fromCurrency())) outflow = outflow.add(amt(x.fromAmount()));
            }
            for (TripExpenseDto e : expenses) {
                if (ccy.equals(e.currency())) outflow = outflow.add(amt(e.amount()));
            }
            BigDecimal remaining = inflow.subtract(outflow);

            BigDecimal homeRate = ccy.equals(home) ? BigDecimal.ONE : weightedRate(ccy, fundings, exchanges, baseRate);
            BigDecimal remainingInHome = null;
            if (homeRate != null) {
                remainingInHome = remaining.multiply(homeRate).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                totalRemainingHome = totalRemainingHome.add(remainingInHome);
            } else {
                unrated.add(ccy);
            }
            lines.add(new WalletTally.CurrencyLine(ccy, inflow, outflow, remaining, homeRate, remainingInHome));
        }

        return new WalletTally(home, lines,
                totalRemainingHome.setScale(MONEY_SCALE, RoundingMode.HALF_UP), unrated);
    }

    /** Weighted-average rate over just this currency's rated fundings; null when none carry a rate. */
    private static BigDecimal fundingRate(String ccy, List<TripFundingDto> fundings) {
        BigDecimal weight = BigDecimal.ZERO;
        BigDecimal weighted = BigDecimal.ZERO;
        for (TripFundingDto f : fundings) {
            if (ccy.equals(f.currency()) && f.rateToHome() != null) {
                BigDecimal w = amt(f.amount());
                weight = weight.add(w);
                weighted = weighted.add(w.multiply(f.rateToHome()));
            }
        }
        return weight.signum() <= 0 ? null : weighted.divide(weight, RATE_SCALE, RoundingMode.HALF_UP);
    }

    /** Full home-rate: rated fundings + exchange-in priced from the source's base rate (single-level). */
    private static BigDecimal weightedRate(String ccy, List<TripFundingDto> fundings,
                                           List<TripExchangeDto> exchanges, Map<String, BigDecimal> baseRate) {
        BigDecimal weight = BigDecimal.ZERO;
        BigDecimal weighted = BigDecimal.ZERO;
        for (TripFundingDto f : fundings) {
            if (ccy.equals(f.currency()) && f.rateToHome() != null) {
                BigDecimal w = amt(f.amount());
                weight = weight.add(w);
                weighted = weighted.add(w.multiply(f.rateToHome()));
            }
        }
        for (TripExchangeDto x : exchanges) {
            if (!ccy.equals(x.toCurrency())) continue;
            BigDecimal to = amt(x.toAmount());
            BigDecimal fromBase = baseRate.get(x.fromCurrency());
            if (fromBase == null || to.signum() <= 0) continue;   // unresolvable hop → contribute nothing
            BigDecimal rate = amt(x.fromAmount()).multiply(fromBase).divide(to, RATE_SCALE, RoundingMode.HALF_UP);
            weight = weight.add(to);
            weighted = weighted.add(to.multiply(rate));
        }
        return weight.signum() <= 0 ? null : weighted.divide(weight, RATE_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal amt(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
