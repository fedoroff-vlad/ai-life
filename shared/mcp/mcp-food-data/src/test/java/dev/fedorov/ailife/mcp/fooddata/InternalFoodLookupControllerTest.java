package dev.fedorov.ailife.mcp.fooddata;

import dev.fedorov.ailife.contracts.food.FoodFacts;
import dev.fedorov.ailife.contracts.food.FoodLookupInput;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FD-a: the {@code POST /internal/food-lookup} passthrough drives the same Open Food Facts read →
 * JSON parse logic as the {@code food_lookup} tool, over a MockWebServer-testable transport (the
 * MCP/SSE transport can't be mocked). A MockWebServer stands in for Open Food Facts; no external
 * network. Full MCP context boots with the one registered tool.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class InternalFoodLookupControllerTest {

    static MockWebServer off;

    @BeforeAll
    static void start() throws Exception {
        off = new MockWebServer();
        off.start();
    }

    @AfterAll
    static void stop() throws Exception {
        off.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("fooddata.open-food-facts-url", () -> "http://localhost:" + off.getPort());
    }

    @Autowired WebTestClient web;

    @Test
    void barcodeQueryHitsProductApiAndParsesMacros() throws Exception {
        off.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody("""
                        {
                          "status": 1,
                          "product": {
                            "code": "3017620422003",
                            "product_name": "Nutella",
                            "brands": "Ferrero",
                            "quantity": "400 g",
                            "nutriscore_grade": "e",
                            "nutriments": {
                              "energy-kcal_100g": 539,
                              "proteins_100g": 6.3,
                              "fat_100g": 30.9,
                              "carbohydrates_100g": 57.5
                            }
                          }
                        }
                        """));

        FoodFacts facts = web.post().uri("/internal/food-lookup")
                .bodyValue(new FoodLookupInput("3017620422003"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(FoodFacts.class)
                .returnResult().getResponseBody();

        assertThat(facts).isNotNull();
        assertThat(facts.name()).isEqualTo("Nutella");
        assertThat(facts.brand()).isEqualTo("Ferrero");
        assertThat(facts.barcode()).isEqualTo("3017620422003");
        assertThat(facts.quantity()).isEqualTo("400 g");
        assertThat(facts.nutriScore()).isEqualTo("e");
        assertThat(facts.kcal100g()).isEqualTo(539);
        assertThat(facts.protein100g()).isEqualByComparingTo(BigDecimal.valueOf(6.3));
        assertThat(facts.fat100g()).isEqualByComparingTo(BigDecimal.valueOf(30.9));
        assertThat(facts.carbs100g()).isEqualByComparingTo(BigDecimal.valueOf(57.5));

        RecordedRequest req = off.takeRequest();
        assertThat(req.getPath())
                .startsWith("/api/v2/product/3017620422003.json")
                .contains("fields=");
    }

    @Test
    void nameQueryHitsSearchApiAndTakesFirstHit() throws Exception {
        off.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody("""
                        {
                          "count": 1,
                          "products": [
                            {
                              "code": "0000000000017",
                              "product_name": "Banana",
                              "nutriments": {
                                "energy-kcal_100g": 89,
                                "proteins_100g": 1.1,
                                "fat_100g": 0.3,
                                "carbohydrates_100g": 23
                              }
                            }
                          ]
                        }
                        """));

        FoodFacts facts = web.post().uri("/internal/food-lookup")
                .bodyValue(new FoodLookupInput("banana"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(FoodFacts.class)
                .returnResult().getResponseBody();

        assertThat(facts).isNotNull();
        assertThat(facts.name()).isEqualTo("Banana");
        assertThat(facts.kcal100g()).isEqualTo(89);
        assertThat(facts.carbs100g()).isEqualByComparingTo(BigDecimal.valueOf(23));

        RecordedRequest req = off.takeRequest();
        assertThat(req.getPath())
                .startsWith("/cgi/search.pl")
                .contains("search_terms=banana")
                .contains("json=1");
    }

    @Test
    void unknownProductYieldsNullNameNotAnError() throws Exception {
        // Open Food Facts returns status 0 / no products for an unknown query — "no data", not a failure.
        off.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody("""
                        { "count": 0, "products": [] }
                        """));

        FoodFacts facts = web.post().uri("/internal/food-lookup")
                .bodyValue(new FoodLookupInput("zzzznotafood"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(FoodFacts.class)
                .returnResult().getResponseBody();

        assertThat(facts).isNotNull();
        assertThat(facts.query()).isEqualTo("zzzznotafood");
        assertThat(facts.name()).isNull();
        assertThat(facts.kcal100g()).isNull();

        off.takeRequest();
    }
}
