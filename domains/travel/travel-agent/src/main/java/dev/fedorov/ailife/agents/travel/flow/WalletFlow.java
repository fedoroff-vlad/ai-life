package dev.fedorov.ailife.agents.travel.flow;

import dev.fedorov.ailife.agentruntime.deliver.DeliverablePublisher;
import dev.fedorov.ailife.agents.travel.flow.WalletExtractor.WalletAction;
import dev.fedorov.ailife.agents.travel.http.TripWalletClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.travel.AddFundingInput;
import dev.fedorov.ailife.contracts.travel.CreateTripInput;
import dev.fedorov.ailife.contracts.travel.LogExchangeInput;
import dev.fedorov.ailife.contracts.travel.LogExpenseInput;
import dev.fedorov.ailife.contracts.travel.TripDto;
import dev.fedorov.ailife.docrender.Doc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The trip-wallet flow (EX-b): a cue-routed create / fund / exchange / spend / tally over the persisted
 * multi-currency family trip budget (plans/travel.md §Trip wallet, #437). One {@link WalletExtractor} LLM
 * turn classifies the message into a {@link WalletAction}; fund/exchange/spend/tally attach to the
 * household's <b>active trip</b> (most recent non-closed) without the owner naming it. A tally reads the
 * raw ledger from {@code mcp-travel} (EX-a) and runs the deterministic {@link TripLedger} — per-currency
 * remaining + the owner-rate ₽ total, unset-rate currencies flagged — then renders an HTML <b>wallet
 * board</b> via the shared {@link DeliverablePublisher} (soft-failing to text-only on a render hiccup).
 *
 * <p>Balance math is never the LLM's job (a correctness/privacy boundary). There is no "who owes whom":
 * one family budget, no settlement.
 */
@Component
public class WalletFlow {

    private static final Logger log = LoggerFactory.getLogger(WalletFlow.class);
    private static final String NO_TRIP =
            "Сначала создайте поездку — например: «создай поездку в Тайланд». Потом можно записывать "
            + "валюту, обмены и траты и спрашивать «сколько осталось».";

    private final WalletExtractor extractor;
    private final TripWalletClient wallet;
    private final DeliverablePublisher publisher;
    private final AgentManifest manifest;

    public WalletFlow(WalletExtractor extractor, TripWalletClient wallet,
                      DeliverablePublisher publisher, AgentManifest manifest) {
        this.extractor = extractor;
        this.wallet = wallet;
        this.publisher = publisher;
        this.manifest = manifest;
    }

    public Mono<IntentResponse> handle(NormalizedMessage msg) {
        return extractor.extract(msg.text())
                .flatMap(action -> dispatch(msg, action))
                .onErrorResume(e -> {
                    log.warn("wallet flow failed: {}", e.toString());
                    return Mono.just(reply("Не удалось обработать операцию по кошельку поездки. Попробуйте позже."));
                });
    }

    private Mono<IntentResponse> dispatch(NormalizedMessage msg, WalletAction a) {
        return switch (a.action() == null ? "none" : a.action()) {
            case "create" -> create(msg, a);
            case "fund" -> fund(msg, a);
            case "exchange" -> exchange(msg, a);
            case "spend" -> spend(msg, a);
            case "tally" -> tally(msg);
            default -> Mono.just(reply(
                    "Не понял операцию. Могу: создать поездку, записать валюту («завёл 500 $ по 90»), "
                    + "обмен («поменял 36000 ₽ на 40000 бат»), трату («потратил 2000 бат на ужин») "
                    + "или подвести итог («сколько осталось»)."));
        };
    }

    private Mono<IntentResponse> create(NormalizedMessage msg, WalletAction a) {
        String title = firstNonBlank(a.title(), a.destination(), "Поездка");
        CreateTripInput input = new CreateTripInput(
                msg.householdId(), msg.userId(), title, blankToNull(a.destination()),
                null, null, blankToNull(a.homeCurrency()));
        return wallet.createTrip(input)
                .map(trip -> reply("Создал поездку «" + trip.title() + "» (итог в " + trip.homeCurrency()
                        + "). Записывайте валюту, обмены и траты, а «сколько осталось» подведёт итог."))
                .onErrorResume(storeError("создать поездку"));
    }

    private Mono<IntentResponse> fund(NormalizedMessage msg, WalletAction a) {
        if (a.currency() == null || a.amount() == null) {
            return Mono.just(reply("Уточните сумму и валюту, например: «завёл 500 долларов по 90»."));
        }
        return withActiveTrip(msg, trip -> wallet.addFunding(new AddFundingInput(
                        trip.id(), a.currency(), a.amount(), a.rateToHome(), null))
                .map(f -> reply("Записал приход: +" + money(f.amount()) + " " + f.currency()
                        + (f.rateToHome() != null ? " (курс " + money(f.rateToHome()) + ")" : "")
                        + " в поездку «" + trip.title() + "»."))
                .onErrorResume(storeError("записать приход")));
    }

    private Mono<IntentResponse> exchange(NormalizedMessage msg, WalletAction a) {
        if (a.fromCurrency() == null || a.fromAmount() == null || a.toCurrency() == null || a.toAmount() == null) {
            return Mono.just(reply("Уточните обмен, например: «поменял 36000 рублей на 40000 бат»."));
        }
        return withActiveTrip(msg, trip -> wallet.logExchange(new LogExchangeInput(
                        trip.id(), a.fromCurrency(), a.fromAmount(), a.toCurrency(), a.toAmount(), null))
                .map(x -> reply("Записал обмен: −" + money(x.fromAmount()) + " " + x.fromCurrency()
                        + " → +" + money(x.toAmount()) + " " + x.toCurrency() + "."))
                .onErrorResume(storeError("записать обмен")));
    }

    private Mono<IntentResponse> spend(NormalizedMessage msg, WalletAction a) {
        if (a.currency() == null || a.amount() == null) {
            return Mono.just(reply("Уточните сумму и валюту траты, например: «потратил 2000 бат на ужин»."));
        }
        return withActiveTrip(msg, trip -> wallet.logExpense(new LogExpenseInput(
                        trip.id(), a.currency(), a.amount(), null, blankToNull(a.description())))
                .map(e -> reply("Записал трату: −" + money(e.amount()) + " " + e.currency()
                        + (e.description() != null ? " (" + e.description() + ")" : "") + "."))
                .onErrorResume(storeError("записать трату")));
    }

    private Mono<IntentResponse> tally(NormalizedMessage msg) {
        return withActiveTrip(msg, trip -> wallet.getTripLedger(trip.id(), msg.householdId())
                .map(TripLedger::compute)
                .flatMap(t -> {
                    String text = tallyText(trip, t);
                    return publishBoard(msg, trip, t)
                            .map(link -> reply(withLink(text, link)))
                            .defaultIfEmpty(reply(text))
                            .onErrorResume(e -> {
                                log.warn("wallet board store failed: {}", e.toString());
                                return Mono.just(reply(text));
                            });
                })
                .switchIfEmpty(Mono.just(reply("У поездки «" + trip.title() + "» пока нет записей."))));
    }

    /** Resolve the household's active trip, or tell the owner to create one first. */
    private Mono<IntentResponse> withActiveTrip(NormalizedMessage msg,
                                                java.util.function.Function<TripDto, Mono<IntentResponse>> action) {
        return wallet.getActiveTrip(msg.householdId())
                .flatMap(action)
                .switchIfEmpty(Mono.just(reply(NO_TRIP)));
    }

    // --- tally text + board ---

    private String tallyText(TripDto trip, WalletTally t) {
        StringBuilder sb = new StringBuilder("Кошелёк поездки «").append(trip.title()).append("»:\n");
        for (WalletTally.CurrencyLine c : t.currencies()) {
            sb.append("• ").append(c.currency()).append(" — осталось ").append(money(c.remaining()));
            if (c.rateKnown()) {
                sb.append(" (≈ ").append(money(c.remainingInHome())).append(' ').append(t.homeCurrency()).append(')');
            } else {
                sb.append(" (курс не задан)");
            }
            sb.append('\n');
        }
        sb.append("Всего осталось ≈ ").append(money(t.totalRemainingInHome())).append(' ').append(t.homeCurrency());
        if (t.hasUnrated()) {
            sb.append("\nБез курса (не вошли в итог): ").append(String.join(", ", t.unratedCurrencies()));
        }
        return sb.toString();
    }

    private Mono<String> publishBoard(NormalizedMessage msg, TripDto trip, WalletTally t) {
        Doc.Builder b = Doc.builder("Кошелёк поездки")
                .kicker(trip.destination() == null || trip.destination().isBlank()
                        ? "Поездка · " + trip.title() : "Поездка · " + trip.destination())
                .subtitle("Остаток по валютам · итог в " + t.homeCurrency());

        List<String> rows = new ArrayList<>();
        for (WalletTally.CurrencyLine c : t.currencies()) {
            StringBuilder row = new StringBuilder(c.currency()).append(" — осталось ").append(money(c.remaining()));
            row.append("  (заведено ").append(money(c.funded())).append(", потрачено ").append(money(c.spent())).append(')');
            if (c.rateKnown()) {
                row.append("  ≈ ").append(money(c.remainingInHome())).append(' ').append(t.homeCurrency());
            } else {
                row.append("  · курс не задан");
            }
            rows.add(row.toString());
        }
        b.section("Остаток по валютам", rows);

        List<String> total = new ArrayList<>();
        total.add("Всего осталось ≈ " + money(t.totalRemainingInHome()) + ' ' + t.homeCurrency());
        if (t.hasUnrated()) {
            total.add("Без указанного курса (не в ₽-итоге): " + String.join(", ", t.unratedCurrencies()));
        }
        total.add("Один семейный бюджет · без деления «кто кому должен».");
        b.section("Итог", total);

        return publisher.publish(msg.householdId(), msg.userId(), b.build());
    }

    // --- helpers ---

    private java.util.function.Function<Throwable, Mono<IntentResponse>> storeError(String what) {
        return e -> {
            log.warn("wallet {} failed: {}", what, e.toString());
            return Mono.just(reply("Не смог " + what + ". Проверьте данные или попробуйте позже."));
        };
    }

    private static String withLink(String text, String link) {
        return (link == null || link.isBlank()) ? text : text + "\n\nОткрыть кошелёк: " + link;
    }

    /** Plain money string, trailing zeros stripped (2000.0000 → "2000", 0.9000 → "0.9"). */
    private static String money(BigDecimal v) {
        if (v == null) {
            return "0";
        }
        return v.stripTrailingZeros().toPlainString();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "Поездка";
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private IntentResponse reply(String text) {
        return new IntentResponse(manifest.name(), text, null);
    }
}
