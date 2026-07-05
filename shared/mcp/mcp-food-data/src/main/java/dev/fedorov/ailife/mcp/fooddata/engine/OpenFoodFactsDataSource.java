package dev.fedorov.ailife.mcp.fooddata.engine;

import tools.jackson.databind.JsonNode;
import dev.fedorov.ailife.contracts.food.FoodFacts;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Default {@link FoodDataSource}: reads nutrition facts from <b>Open Food Facts</b>. Free, no API
 * key, no quota. A <b>numeric</b> query (EAN/UPC) is the precise path
 * ({@code GET /api/v2/product/{barcode}.json}); a free-text query is a best-effort name search
 * ({@code GET /cgi/search.pl?...&json=1&page_size=1}) returning the first hit. Selected by
 * {@code fooddata.source=openfoodfacts} (the default).
 *
 * <p>Macros are read <b>per 100 g/ml</b> from {@code nutriments} as Open Food Facts stores them. A
 * missing product / missing fields map to {@code null} (a {@link FoodFacts} with a null {@code name}
 * means "no data", not an error). A genuine transport failure propagates on the {@link Mono} (the
 * caller's analysis soft-fails per item).
 */
@Component
@ConditionalOnProperty(name = "fooddata.source", havingValue = "openfoodfacts", matchIfMissing = true)
public class OpenFoodFactsDataSource implements FoodDataSource {

    /** Only the fields we map — keeps the response small and the parse focused. */
    private static final String FIELDS = "code,product_name,brands,quantity,nutriscore_grade,nutriments";

    private final WebClient http;

    public OpenFoodFactsDataSource(@Qualifier("openFoodFactsWebClient") WebClient http) {
        this.http = http;
    }

    @Override
    public Mono<FoodFacts> lookup(String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return Mono.just(empty(q));
        }
        return isBarcode(q) ? byBarcode(q) : byName(q);
    }

    /** EAN-8 / UPC-A / EAN-13 are 8+ digit codes — treat an all-digits query as a barcode. */
    private static boolean isBarcode(String q) {
        return q.matches("\\d{8,}");
    }

    private Mono<FoodFacts> byBarcode(String barcode) {
        return http.get()
                .uri(uri -> uri.path("/api/v2/product/{code}.json")
                        .queryParam("fields", FIELDS)
                        .build(barcode))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(10))
                .map(body -> parseProduct(barcode, body == null ? null : body.get("product")));
    }

    private Mono<FoodFacts> byName(String query) {
        return http.get()
                .uri(uri -> uri.path("/cgi/search.pl")
                        .queryParam("search_terms", query)
                        .queryParam("search_simple", 1)
                        .queryParam("action", "process")
                        .queryParam("json", 1)
                        .queryParam("page_size", 1)
                        .queryParam("fields", FIELDS)
                        .build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(10))
                .map(body -> {
                    JsonNode products = body == null ? null : body.get("products");
                    JsonNode first = (products != null && products.isArray() && !products.isEmpty())
                            ? products.get(0) : null;
                    return parseProduct(query, first);
                });
    }

    /** Map an Open Food Facts product node (or null = not found) to {@link FoodFacts}. */
    private static FoodFacts parseProduct(String query, JsonNode product) {
        if (product == null || product.isNull()) {
            return empty(query);
        }
        JsonNode n = product.get("nutriments");
        return new FoodFacts(
                query,
                text(product, "product_name"),
                text(product, "brands"),
                text(product, "code"),
                text(product, "quantity"),
                text(product, "nutriscore_grade"),
                intVal(n, "energy-kcal_100g"),
                bigDecimal(n, "proteins_100g"),
                bigDecimal(n, "fat_100g"),
                bigDecimal(n, "carbohydrates_100g"));
    }

    private static FoodFacts empty(String query) {
        return new FoodFacts(query, null, null, null, null, null, null, null, null, null);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private static Integer intVal(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.isNumber()) {
            return null;
        }
        return (int) Math.round(v.asDouble());
    }

    private static BigDecimal bigDecimal(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.isNumber()) {
            return null;
        }
        return BigDecimal.valueOf(v.asDouble());
    }
}
