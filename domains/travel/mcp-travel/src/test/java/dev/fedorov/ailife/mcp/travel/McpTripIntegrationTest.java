package dev.fedorov.ailife.mcp.travel;

import dev.fedorov.ailife.contracts.travel.AddFundingInput;
import dev.fedorov.ailife.contracts.travel.AddTripMemberInput;
import dev.fedorov.ailife.contracts.travel.CreateTripInput;
import dev.fedorov.ailife.contracts.travel.LogExchangeInput;
import dev.fedorov.ailife.contracts.travel.LogExpenseInput;
import dev.fedorov.ailife.contracts.travel.TripDto;
import dev.fedorov.ailife.contracts.travel.TripLedgerDto;
import dev.fedorov.ailife.contracts.travel.TripMemberDto;
import dev.fedorov.ailife.mcp.travel.tools.TripMcpTools;
import dev.fedorov.ailife.test.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EX-a: the trip-wallet store. Tests share a SpringBootTest context + DB, so each scopes on its own
 * per-test household to stay deterministic (mirrors McpTravelIntegrationTest).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class McpTripIntegrationTest extends AbstractPostgresIntegrationTest {

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registerDataSource(registry);
    }

    @Autowired TripMcpTools tools;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    @BeforeAll
    static void applyOnce() {
        applySchema("test-schema.sql");
    }

    /** Scenario: create + read a trip → round-trips with status=planning. */
    @Test
    void createAndReadTrip() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        UUID owner = seedUser(h);

        TripDto created = tools.createTrip(new CreateTripInput(
                h, owner, "Тайланд", "Phuket", null, null, "RUB"));
        assertThat(created.id()).isNotNull();
        assertThat(created.status()).isEqualTo("planning");
        assertThat(created.homeCurrency()).isEqualTo("RUB");

        TripDto read = tools.getTrip(created.id(), h);
        assertThat(read).isNotNull();
        assertThat(read.id()).isEqualTo(created.id());
        assertThat(read.title()).isEqualTo("Тайланд");
        assertThat(read.destination()).isEqualTo("Phuket");
    }

    /** Scenario: home_currency defaults to RUB when null. */
    @Test
    void createTripDefaultsHomeCurrencyToRub() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        TripDto created = tools.createTrip(new CreateTripInput(h, null, "Trip", null, null, null, null));
        assertThat(created.homeCurrency()).isEqualTo("RUB");
    }

    /** Scenario: roster adds participants of either identity kind, add/remove reflected, no roles. */
    @Test
    void rosterAddsUserAndPersonThenRemove() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        UUID owner = seedUser(h);
        UUID person = seedPerson(h);
        TripDto trip = tools.createTrip(new CreateTripInput(h, owner, "Семейный отпуск", null, null, null, null));

        TripMemberDto asUser = tools.addTripMember(new AddTripMemberInput(trip.id(), owner, null, "Папа"));
        TripMemberDto asPerson = tools.addTripMember(new AddTripMemberInput(trip.id(), null, person, "Дочка"));
        assertThat(asUser.userId()).isEqualTo(owner);
        assertThat(asUser.personId()).isNull();
        assertThat(asPerson.personId()).isEqualTo(person);
        assertThat(asPerson.userId()).isNull();

        TripLedgerDto afterAdd = tools.getTripLedger(trip.id(), h);
        assertThat(afterAdd.members()).hasSize(2);

        assertThat(tools.removeTripMember(asUser.id())).isTrue();
        TripLedgerDto afterRemove = tools.getTripLedger(trip.id(), h);
        assertThat(afterRemove.members()).extracting(TripMemberDto::label).containsExactly("Дочка");

        // removing a non-existent member is a no-op false, not an error.
        assertThat(tools.removeTripMember(UUID.randomUUID())).isFalse();
    }

    /** Scenario: a member cannot carry both a userId and a personId. */
    @Test
    void memberRejectsTwoIdentityRefs() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        UUID owner = seedUser(h);
        UUID person = seedPerson(h);
        TripDto trip = tools.createTrip(new CreateTripInput(h, owner, "T", null, null, null, null));
        assertThatThrownBy(() -> tools.addTripMember(
                new AddTripMemberInput(trip.id(), owner, person, "both")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most one");
    }

    /** Scenario: funding + exchange + expense ledger rows; exchange is a RUB outflow + THB inflow. */
    @Test
    void fundingExchangeExpenseLedgerRows() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        UUID owner = seedUser(h);
        TripDto trip = tools.createTrip(new CreateTripInput(h, owner, "Тайланд", null, null, null, "RUB"));

        tools.addFunding(new AddFundingInput(trip.id(), "RUB", new BigDecimal("100000"), BigDecimal.ONE, null));
        tools.addFunding(new AddFundingInput(trip.id(), "usd", new BigDecimal("500"), new BigDecimal("90"), "cash"));
        tools.logExchange(new LogExchangeInput(trip.id(), "RUB", new BigDecimal("36000"),
                "THB", new BigDecimal("40000"), "аэропорт"));
        tools.logExpense(new LogExpenseInput(trip.id(), "THB", new BigDecimal("2000"), "food", "ужин"));

        TripLedgerDto ledger = tools.getTripLedger(trip.id(), h);
        assertThat(ledger.fundings()).hasSize(2);
        assertThat(ledger.fundings()).extracting(f -> f.currency()).containsExactlyInAnyOrder("RUB", "USD");
        assertThat(ledger.exchanges()).hasSize(1);
        assertThat(ledger.exchanges().getFirst().fromCurrency()).isEqualTo("RUB");
        assertThat(ledger.exchanges().getFirst().toCurrency()).isEqualTo("THB");
        assertThat(ledger.expenses()).hasSize(1);
        assertThat(ledger.expenses().getFirst().currency()).isEqualTo("THB");
        assertThat(ledger.expenses().getFirst().amount()).isEqualByComparingTo("2000");
    }

    /** Scenario: an exchange between identical currencies is rejected. */
    @Test
    void exchangeRejectsSameCurrency() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        TripDto trip = tools.createTrip(new CreateTripInput(h, null, "T", null, null, null, null));
        assertThatThrownBy(() -> tools.logExchange(new LogExchangeInput(
                trip.id(), "USD", BigDecimal.TEN, "usd", BigDecimal.TEN, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different currencies");
    }

    /** getActiveTrip returns the household's most recent non-closed trip (EX-b's "current trip"). */
    @Test
    void getActiveTripReturnsMostRecentOpenTrip() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        assertThat(tools.getActiveTrip(h)).isNull();

        TripDto first = tools.createTrip(new CreateTripInput(h, null, "Первая", null, null, null, null));
        TripDto second = tools.createTrip(new CreateTripInput(h, null, "Вторая", null, null, null, null));
        // The most recently created open trip is active.
        assertThat(tools.getActiveTrip(h).id()).isEqualTo(second.id());

        // Close the newest → the earlier open trip becomes active again.
        jdbc.update("UPDATE travel.trip SET status = 'closed' WHERE id = ?", second.id());
        assertThat(tools.getActiveTrip(h).id()).isEqualTo(first.id());

        // Another household's trip never leaks in.
        UUID other = UUID.randomUUID();
        seedHousehold(other);
        assertThat(tools.getActiveTrip(other)).isNull();
    }

    /** Scenario: reject cross-household trip access → null (tenant isolation). */
    @Test
    void crossHouseholdReadReturnsNull() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        UUID other = UUID.randomUUID();
        seedHousehold(other);
        TripDto trip = tools.createTrip(new CreateTripInput(h, null, "Private", null, null, null, null));

        assertThat(tools.getTrip(trip.id(), other)).isNull();
        assertThat(tools.getTripLedger(trip.id(), other)).isNull();
        assertThat(tools.getTrip(trip.id(), h)).isNotNull();
    }

    /** The /internal passthrough creates a trip, logs a spend and reads the ledger back over HTTP. */
    @Test
    void internalEndpointCreateFundAndLedger() {
        UUID h = UUID.randomUUID();
        seedHousehold(h);
        UUID owner = seedUser(h);
        WebTestClient client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();

        TripDto trip = client.post().uri("/internal/trips")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateTripInput(h, owner, "Тайланд", null, null, null, "RUB"))
                .exchange().expectStatus().isOk()
                .expectBody(TripDto.class).returnResult().getResponseBody();
        assertThat(trip).isNotNull();

        client.post().uri("/internal/trips/expenses")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new LogExpenseInput(trip.id(), "THB", new BigDecimal("500"), null, null))
                .exchange().expectStatus().isOk();

        TripLedgerDto ledger = client.get()
                .uri(b -> b.path("/internal/trips/" + trip.id() + "/ledger")
                        .queryParam("householdId", h).build())
                .exchange().expectStatus().isOk()
                .expectBody(TripLedgerDto.class).returnResult().getResponseBody();
        assertThat(ledger).isNotNull();
        assertThat(ledger.expenses()).hasSize(1);

        // Out-of-tenant read → 204.
        client.get().uri(b -> b.path("/internal/trips/" + trip.id())
                        .queryParam("householdId", UUID.randomUUID()).build())
                .exchange().expectStatus().isNoContent();

        // Missing required field → 400.
        client.post().uri("/internal/trips")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateTripInput(null, null, null, null, null, null, null))
                .exchange().expectStatus().isBadRequest();
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

    private UUID seedPerson(UUID household) {
        UUID personId = UUID.randomUUID();
        jdbc.update("INSERT INTO core.people (id, household_id, display_name) VALUES (?, ?, ?)",
                personId, household, "person-" + personId);
        return personId;
    }
}
