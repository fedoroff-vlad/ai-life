package dev.fedorov.ailife.mcp.nutrition;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.nutrition.BasketDto;
import dev.fedorov.ailife.contracts.nutrition.BasketItem;
import dev.fedorov.ailife.contracts.nutrition.DietProfileDto;
import dev.fedorov.ailife.contracts.nutrition.LogMealInput;
import dev.fedorov.ailife.contracts.nutrition.MealLogDto;
import dev.fedorov.ailife.contracts.nutrition.SaveBasketInput;
import dev.fedorov.ailife.contracts.nutrition.SetDietProfileInput;
import dev.fedorov.ailife.mcp.nutrition.tools.NutritionMcpTools;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests aren't isolated across methods (shared SpringBootTest context + DB) — assertions
 * scope on per-test households to stay deterministic (mirrors mcp-wardrobe).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class McpNutritionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("ailife")
            .withUsername("ailife")
            .withPassword("ailife")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("test-schema.sql"),
                    "/docker-entrypoint-initdb.d/00-test-schema.sql");

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static UUID householdId;

    @Autowired NutritionMcpTools tools;
    @Autowired JdbcTemplate jdbc;
    @org.springframework.boot.test.web.server.LocalServerPort int port;

    @BeforeAll
    static void seedHousehold(@Autowired JdbcTemplate jdbc) {
        householdId = UUID.randomUUID();
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)",
                householdId, "test household");
    }

    @Test
    void logMealStoresFieldsAndDefaults() throws Exception {
        MealLogDto meal = tools.logMeal(new LogMealInput(
                householdId, null, null, null, "овсянка с бананом",
                MAPPER.readTree("[{\"name\":\"oats\"},{\"name\":\"banana\"}]"),
                420, new BigDecimal("12.5"), new BigDecimal("8.0"), new BigDecimal("70.0"),
                UUID.randomUUID()));
        assertThat(meal.id()).isNotNull();
        assertThat(meal.description()).isEqualTo("овсянка с бананом");
        assertThat(meal.source()).isEqualTo("text");      // defaulted
        assertThat(meal.eatenAt()).isNotNull();           // defaulted to now
        assertThat(meal.kcal()).isEqualTo(420);
        assertThat(meal.proteinG()).isEqualByComparingTo("12.5");
        assertThat(meal.items().get(0).get("name").asText()).isEqualTo("oats");
        assertThat(meal.createdAt()).isNotNull();
    }

    @Test
    void logMealRequiresHouseholdAndDescription() {
        assertThatThrownBy(() -> tools.logMeal(new LogMealInput(
                null, null, null, null, "x", null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("householdId");
        assertThatThrownBy(() -> tools.logMeal(new LogMealInput(
                householdId, null, null, null, " ", null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");
    }

    @Test
    void listMealsScopesToHouseholdAndOwnerNewestFirst() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        UUID owner = seedUser(h);
        Instant base = Instant.now().minus(2, ChronoUnit.DAYS);
        tools.logMeal(new LogMealInput(h, owner, base, "text", "breakfast", null, null, null, null, null, null));
        tools.logMeal(new LogMealInput(h, owner, base.plus(1, ChronoUnit.DAYS), "text", "lunch", null, null, null, null, null, null));
        // A household-shared meal (no owner).
        tools.logMeal(new LogMealInput(h, null, base, "text", "shared snack", null, null, null, null, null, null));

        List<MealLogDto> all = tools.listMeals(h, null, null);
        assertThat(all).hasSize(3);
        assertThat(all.get(0).description()).isEqualTo("lunch");   // newest eaten first

        // Owner scope drops the shared meal.
        assertThat(tools.listMeals(h, owner, null)).hasSize(2);

        // limit caps the result.
        assertThat(tools.listMeals(h, null, 1)).hasSize(1);

        // Another household doesn't leak in.
        UUID other = UUID.randomUUID();
        seedHousehold(other);
        tools.logMeal(new LogMealInput(other, null, base, "text", "elsewhere", null, null, null, null, null, null));
        assertThat(tools.listMeals(h, null, null)).hasSize(3);
    }

    @Test
    void deleteMealReturnsRowAndThrowsOnUnknown() {
        MealLogDto meal = tools.logMeal(new LogMealInput(
                householdId, null, null, null, "temp meal", null, null, null, null, null, null));
        MealLogDto deleted = tools.deleteMeal(meal.id());
        assertThat(deleted.id()).isEqualTo(meal.id());
        assertThatThrownBy(() -> tools.deleteMeal(meal.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void setDietProfileUpsertsInPlaceWithJsonFields() throws Exception {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        UUID owner = seedUser(h);

        DietProfileDto created = tools.setDietProfile(new SetDietProfileInput(
                h, owner, 2200, new BigDecimal("150.0"), new BigDecimal("70.0"), new BigDecimal("220.0"),
                MAPPER.readTree("[\"halal\",\"no-nuts\"]"),
                MAPPER.readTree("{\"likes\":[\"fish\"]}"), "cutting"));
        assertThat(created.id()).isNotNull();
        assertThat(created.goalKcal()).isEqualTo(2200);
        assertThat(created.goalProteinG()).isEqualByComparingTo("150.0");
        assertThat(created.restrictions().get(0).asText()).isEqualTo("halal");
        assertThat(created.tastes().get("likes").get(0).asText()).isEqualTo("fish");

        // Same (household, owner) → updates the same row.
        DietProfileDto updated = tools.setDietProfile(new SetDietProfileInput(
                h, owner, 1800, null, null, null, null, null, "maintenance"));
        assertThat(updated.id()).isEqualTo(created.id());
        assertThat(updated.goalKcal()).isEqualTo(1800);
        assertThat(updated.notes()).isEqualTo("maintenance");

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM nutrition.diet_profile WHERE household_id = ? AND owner_id = ?",
                Integer.class, h, owner);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void getDietProfileReturnsNullWhenAbsentThenProfile() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        assertThat(tools.getDietProfile(h, null)).isNull();

        tools.setDietProfile(new SetDietProfileInput(
                h, null, 2000, null, null, null, null, null, "household default"));
        DietProfileDto got = tools.getDietProfile(h, null);
        assertThat(got).isNotNull();
        assertThat(got.goalKcal()).isEqualTo(2000);
    }

    @Test
    void saveBasketRoundTripsItemsAndAnalysisAndLists() throws Exception {
        UUID h = UUID.randomUUID();
        seedHousehold(h);

        BasketDto saved = tools.saveBasket(new SaveBasketInput(
                h, null, null, "Лента", "receipt", UUID.randomUUID(),
                List.of(new BasketItem("молоко", "1 л", 60, new BigDecimal("3.0"), new BigDecimal("3.2"), new BigDecimal("4.7")),
                        new BasketItem("яблоки", "1 кг", 520, new BigDecimal("0.4"), new BigDecimal("0.4"), new BigDecimal("100.0"))),
                580, new BigDecimal("3.4"), new BigDecimal("3.6"), new BigDecimal("104.7"),
                MAPPER.readTree("{\"good\":[\"яблоки\"],\"watch\":[]}")));
        assertThat(saved.id()).isNotNull();
        assertThat(saved.merchant()).isEqualTo("Лента");
        assertThat(saved.source()).isEqualTo("receipt");
        assertThat(saved.items()).hasSize(2);
        assertThat(saved.items().get(0).name()).isEqualTo("молоко");
        assertThat(saved.items().get(0).qty()).isEqualTo("1 л");
        assertThat(saved.kcal()).isEqualTo(580);
        assertThat(saved.analysis().get("good").get(0).asText()).isEqualTo("яблоки");
        assertThat(saved.createdAt()).isNotNull();

        // get by id round-trips the items.
        BasketDto got = tools.getBasket(saved.id());
        assertThat(got).isNotNull();
        assertThat(got.items().get(1).name()).isEqualTo("яблоки");

        // list returns it; another household doesn't leak.
        assertThat(tools.listBaskets(h, null)).hasSize(1);
        assertThat(tools.getBasket(UUID.randomUUID())).isNull();
    }

    @Test
    void internalMealEndpointLogsAnd400OnMissingDescription() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);

        var client = org.springframework.test.web.reactive.server.WebTestClient
                .bindToServer().baseUrl("http://localhost:" + port).build();

        MealLogDto added = client.post().uri("/internal/meal")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(new LogMealInput(h, null, null, "photo", "куриный салат",
                        null, 350, new BigDecimal("30.0"), null, null, UUID.randomUUID()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(MealLogDto.class)
                .returnResult().getResponseBody();
        assertThat(added).isNotNull();
        assertThat(added.description()).isEqualTo("куриный салат");
        assertThat(added.source()).isEqualTo("photo");
        assertThat(added.kcal()).isEqualTo(350);

        // Missing description → the tool's required-field guard surfaces as 400.
        client.post().uri("/internal/meal")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(new LogMealInput(h, null, null, null, null, null, null, null, null, null, null))
                .exchange()
                .expectStatus().isBadRequest();
    }

    private void seedHousehold(UUID id) {
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", id, "h-" + id);
    }

    private UUID seedUser(UUID household) {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO core.users (id, household_id, display_name) VALUES (?, ?, ?)",
                userId, household, "owner-" + userId);
        return userId;
    }
}
