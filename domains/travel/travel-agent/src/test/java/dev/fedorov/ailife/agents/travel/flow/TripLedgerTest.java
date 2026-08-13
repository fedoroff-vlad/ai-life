package dev.fedorov.ailife.agents.travel.flow;

import dev.fedorov.ailife.contracts.travel.TripDto;
import dev.fedorov.ailife.contracts.travel.TripExchangeDto;
import dev.fedorov.ailife.contracts.travel.TripExpenseDto;
import dev.fedorov.ailife.contracts.travel.TripFundingDto;
import dev.fedorov.ailife.contracts.travel.TripLedgerDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deterministic wallet math (EX-b), tested in isolation — no Spring, no HTTP, no LLM. Covers the
 * §EX-b WHEN/THEN scenarios: per-currency remaining, the ₽ tally by owner rates, on-site exchange
 * honesty (no double-count), the unresolved-rate flag, and the "no who-owes-whom" shape.
 */
class TripLedgerTest {

    private static final UUID TRIP = UUID.randomUUID();

    /** Scenario: per-currency remaining = inflows − outflows, independent per currency. */
    @Test
    void perCurrencyRemaining() {
        TripLedgerDto ledger = ledger("RUB",
                List.of(fund("RUB", "100000", "1"), fund("USD", "500", "90"),
                        fund("EUR", "300", "100"), fund("THB", "40000", null)),
                List.of(),
                List.of(expense("THB", "39800"), expense("USD", "450"), expense("EUR", "285")));

        WalletTally tally = TripLedger.compute(ledger);
        assertThat(remaining(tally, "THB")).isEqualByComparingTo("200");
        assertThat(remaining(tally, "USD")).isEqualByComparingTo("50");
        assertThat(remaining(tally, "EUR")).isEqualByComparingTo("15");
        assertThat(remaining(tally, "RUB")).isEqualByComparingTo("100000");
    }

    /** Scenario: the ₽ tally converts each currency by the owner's stated acquisition rate. */
    @Test
    void rubTallyWithOwnerRates() {
        TripLedgerDto ledger = ledger("RUB",
                List.of(fund("RUB", "100000", "1"), fund("USD", "500", "90"), fund("EUR", "300", "100")),
                List.of(), List.of());

        WalletTally tally = TripLedger.compute(ledger);
        // 100000 + 500*90 + 300*100 = 100000 + 45000 + 30000
        assertThat(tally.totalRemainingInHome()).isEqualByComparingTo("175000");
        assertThat(tally.hasUnrated()).isFalse();
    }

    /** Scenario: an on-site exchange keeps the ₽ tally honest — no double-count of the source. */
    @Test
    void onSiteExchangeKeepsRubTallyHonest() {
        TripLedgerDto ledger = ledger("RUB",
                List.of(fund("RUB", "100000", "1")),
                List.of(exchange("RUB", "36000", "THB", "40000")),
                List.of(expense("THB", "39800")));

        WalletTally tally = TripLedger.compute(ledger);
        // RUB debited by the exchange: 100000 - 36000 = 64000.
        assertThat(remaining(tally, "RUB")).isEqualByComparingTo("64000");
        // THB credited then mostly spent: 40000 - 39800 = 200, at a derived 0.9 ₽ rate.
        assertThat(remaining(tally, "THB")).isEqualByComparingTo("200");
        assertThat(line(tally, "THB").homeRate()).isEqualByComparingTo("0.9");
        assertThat(line(tally, "THB").remainingInHome()).isEqualByComparingTo("180");
        // ₽ total = 64000 (RUB) + 180 (THB) — the 36000 ₽ is not counted twice.
        assertThat(tally.totalRemainingInHome()).isEqualByComparingTo("64180");
    }

    /** Scenario: a currency with no resolvable rate is flagged, shown in its own currency, not converted. */
    @Test
    void currencyWithNoRateIsFlaggedNotConverted() {
        TripLedgerDto ledger = ledger("RUB",
                List.of(fund("RUB", "100000", "1"), fund("USD", "500", null)),
                List.of(), List.of());

        WalletTally tally = TripLedger.compute(ledger);
        assertThat(tally.unratedCurrencies()).contains("USD");
        assertThat(line(tally, "USD").rateKnown()).isFalse();
        assertThat(line(tally, "USD").remaining()).isEqualByComparingTo("500");
        assertThat(line(tally, "USD").remainingInHome()).isNull();
        // The ₽ total counts only the rated RUB, never a made-up USD conversion.
        assertThat(tally.totalRemainingInHome()).isEqualByComparingTo("100000");
    }

    /** Scenario: an exchange out of an unrated source currency stays unresolved (no guessing). */
    @Test
    void exchangeFromUnratedSourceStaysUnresolved() {
        TripLedgerDto ledger = ledger("RUB",
                List.of(fund("USD", "1000", null)),          // USD has no ₽ rate
                List.of(exchange("USD", "100", "THB", "3000")),
                List.of());

        WalletTally tally = TripLedger.compute(ledger);
        assertThat(line(tally, "THB").rateKnown()).isFalse();
        assertThat(tally.unratedCurrencies()).contains("USD", "THB");
    }

    /** Scenario: the tally is a set of per-currency family balances + one ₽ total — no per-member debt. */
    @Test
    void tallyIsCurrencyKeyedFamilyBalanceNoSettlement() {
        TripLedgerDto ledger = ledger("RUB",
                List.of(fund("RUB", "50000", "1")),
                List.of(),
                List.of(expense("RUB", "12000"), expense("RUB", "8000")));

        WalletTally tally = TripLedger.compute(ledger);
        assertThat(tally.currencies()).extracting(WalletTally.CurrencyLine::currency).containsExactly("RUB");
        assertThat(remaining(tally, "RUB")).isEqualByComparingTo("30000");
        assertThat(tally.totalRemainingInHome()).isEqualByComparingTo("30000");
    }

    // --- helpers ---

    private static TripLedgerDto ledger(String home, List<TripFundingDto> f,
                                        List<TripExchangeDto> x, List<TripExpenseDto> e) {
        TripDto trip = new TripDto(TRIP, UUID.randomUUID(), null, "Trip", null, null, null,
                home, "active", null, null);
        return new TripLedgerDto(trip, List.of(), f, x, e);
    }

    private static TripFundingDto fund(String ccy, String amount, String rate) {
        return new TripFundingDto(UUID.randomUUID(), TRIP, ccy, new BigDecimal(amount),
                rate == null ? null : new BigDecimal(rate), null, null);
    }

    private static TripExchangeDto exchange(String from, String fromAmt, String to, String toAmt) {
        return new TripExchangeDto(UUID.randomUUID(), TRIP, from, new BigDecimal(fromAmt),
                to, new BigDecimal(toAmt), null, null);
    }

    private static TripExpenseDto expense(String ccy, String amount) {
        return new TripExpenseDto(UUID.randomUUID(), TRIP, ccy, new BigDecimal(amount), null, null, null, null);
    }

    private static WalletTally.CurrencyLine line(WalletTally tally, String ccy) {
        return tally.currencies().stream().filter(c -> c.currency().equals(ccy)).findFirst().orElseThrow();
    }

    private static BigDecimal remaining(WalletTally tally, String ccy) {
        return line(tally, ccy).remaining();
    }
}
