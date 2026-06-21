package dev.fedorov.ailife.mcp.marketdata.engine;

import dev.fedorov.ailife.contracts.market.Quote;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Default {@link MarketDataSource}: reads a latest quote from <b>Stooq</b>'s CSV light endpoint
 * ({@code GET /q/l/?s={symbol}&f=sd2t2ohlcv&e=csv}) and maps the row to a {@link Quote}. Free, no
 * API key, no quota. Covers world stocks / indices / ETFs / metals (e.g. {@code xauusd}) / forex
 * and some crypto. Selected by {@code marketdata.source=stooq} (the default).
 *
 * <p>Stooq yields {@code N/D} for unknown symbols / missing fields — those map to {@code null}
 * (a {@link Quote} with a null {@code price} means "no data", not an error). A genuine transport
 * failure propagates on the {@link Mono} (the caller's gather soft-fails per symbol).
 */
@Component
@ConditionalOnProperty(name = "marketdata.source", havingValue = "stooq", matchIfMissing = true)
public class StooqMarketDataSource implements MarketDataSource {

    private final WebClient http;

    public StooqMarketDataSource(@Qualifier("stooqWebClient") WebClient http) {
        this.http = http;
    }

    @Override
    public Mono<Quote> quote(String symbol) {
        String s = symbol == null ? "" : symbol.trim();
        return http.get()
                .uri(uri -> uri.path("/q/l/")
                        .queryParam("s", s.toLowerCase())
                        .queryParam("f", "sd2t2ohlcv")
                        .queryParam("e", "csv")
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(10))
                .map(csv -> parse(s, csv));
    }

    /** Parse the last non-header CSV row: Symbol,Date,Time,Open,High,Low,Close,Volume. */
    private static Quote parse(String requested, String csv) {
        if (csv == null || csv.isBlank()) {
            return new Quote(requested, null, null, null, null, null, null);
        }
        String dataLine = null;
        for (String line : csv.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.regionMatches(true, 0, "Symbol,", 0, 7)) {
                continue; // skip blanks + the header row
            }
            dataLine = trimmed;
        }
        if (dataLine == null) {
            return new Quote(requested, null, null, null, null, null, null);
        }
        String[] f = dataLine.split(",", -1);
        String symbol = field(f, 0);
        String date = field(f, 1);
        String time = field(f, 2);
        Double open = num(f, 3);
        Double high = num(f, 4);
        Double low = num(f, 5);
        Double close = num(f, 6);
        Long volume = lng(f, 7);
        return new Quote(
                symbol == null ? requested : symbol,
                close,
                asOf(date, time),
                open, high, low, volume);
    }

    private static String asOf(String date, String time) {
        if (date == null) {
            return null;
        }
        return time == null ? date : date + " " + time;
    }

    private static String field(String[] f, int i) {
        if (i >= f.length) {
            return null;
        }
        String v = f[i].trim();
        return (v.isEmpty() || v.equalsIgnoreCase("N/D")) ? null : v;
    }

    private static Double num(String[] f, int i) {
        String v = field(f, i);
        if (v == null) {
            return null;
        }
        try {
            return Double.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long lng(String[] f, int i) {
        String v = field(f, i);
        if (v == null) {
            return null;
        }
        try {
            return Long.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
