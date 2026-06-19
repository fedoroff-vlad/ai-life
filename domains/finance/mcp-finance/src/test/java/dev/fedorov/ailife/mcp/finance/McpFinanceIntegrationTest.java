package dev.fedorov.ailife.mcp.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.finance.AddTransactionInput;
import dev.fedorov.ailife.contracts.finance.BalanceResult;
import dev.fedorov.ailife.contracts.finance.BudgetStatusResult;
import dev.fedorov.ailife.contracts.finance.CsvExportResult;
import dev.fedorov.ailife.contracts.finance.ExportCsvInput;
import dev.fedorov.ailife.contracts.finance.FinAccountDto;
import dev.fedorov.ailife.contracts.finance.FinBudgetDto;
import dev.fedorov.ailife.contracts.finance.FinCategoryDto;
import dev.fedorov.ailife.contracts.finance.FinRecurringDto;
import dev.fedorov.ailife.contracts.finance.GiftBudgetRuleDto;
import dev.fedorov.ailife.contracts.finance.SetGiftBudgetRuleInput;
import dev.fedorov.ailife.contracts.finance.FinTransactionDto;
import dev.fedorov.ailife.contracts.finance.GiftBudgetResult;
import dev.fedorov.ailife.contracts.finance.ListTransactionsInput;
import dev.fedorov.ailife.contracts.finance.MatviewRefreshResult;
import dev.fedorov.ailife.contracts.finance.SetBudgetInput;
import dev.fedorov.ailife.contracts.finance.SpendingByCategoryInput;
import dev.fedorov.ailife.contracts.finance.SpendingByCategoryRow;
import dev.fedorov.ailife.contracts.finance.UpdateTransactionInput;
import dev.fedorov.ailife.contracts.finance.UpsertAccountInput;
import dev.fedorov.ailife.contracts.finance.UpsertCategoryInput;
import dev.fedorov.ailife.contracts.finance.UpsertRecurringInput;
import dev.fedorov.ailife.contracts.schedule.ScheduleDto;
import dev.fedorov.ailife.mcp.finance.tools.FinanceMcpTools;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class McpFinanceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("ailife")
            .withUsername("ailife")
            .withPassword("ailife")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("test-schema.sql"),
                    "/docker-entrypoint-initdb.d/00-test-schema.sql");

    // Started in a static initializer so the port is known when Spring resolves
    // the @DynamicPropertySource supplier during context refresh (which runs
    // BEFORE @BeforeAll). The dispatcher is reset per test.
    static final MockWebServer scheduler;
    static final SchedulerDispatcher schedulerDispatcher = new SchedulerDispatcher();
    static {
        scheduler = new MockWebServer();
        scheduler.setDispatcher(schedulerDispatcher);
        try {
            scheduler.start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @AfterAll
    static void stopScheduler() throws Exception {
        scheduler.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("mcp-finance.scheduler-url",
                () -> "http://localhost:" + scheduler.getPort());
    }

    @BeforeEach
    void resetSchedulerDispatcher() throws Exception {
        // SpringBootTest reuses the context across methods; drain leftover
        // recorded scheduler requests and reset dispatcher flags so each
        // budget test starts from a clean slate.
        while (scheduler.takeRequest(50, TimeUnit.MILLISECONDS) != null) {
            // discard
        }
        schedulerDispatcher.reset();
    }

    static UUID householdId;
    static UUID otherHouseholdId;

    @Autowired FinanceMcpTools tools;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    @BeforeAll
    static void seedHouseholds(@Autowired JdbcTemplate jdbc) {
        householdId = UUID.randomUUID();
        otherHouseholdId = UUID.randomUUID();
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)",
                householdId, "test household");
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)",
                otherHouseholdId, "other household");
    }

    @Test
    void upsertAccountCreatesThenUpdatesInPlace() {
        FinAccountDto created = tools.upsertAccount(new UpsertAccountInput(
                null, householdId, null, "Main card", "card", "EUR",
                new BigDecimal("100.00"), null));
        assertThat(created.id()).isNotNull();
        assertThat(created.openingBalance()).isEqualByComparingTo("100.00");
        assertThat(created.archived()).isFalse();

        FinAccountDto updated = tools.upsertAccount(new UpsertAccountInput(
                created.id(), householdId, null, "Main card (renamed)", "card", "EUR",
                new BigDecimal("250.00"), true));
        assertThat(updated.id()).isEqualTo(created.id());
        assertThat(updated.name()).isEqualTo("Main card (renamed)");
        assertThat(updated.openingBalance()).isEqualByComparingTo("250.00");
        assertThat(updated.archived()).isTrue();
    }

    @Test
    void upsertCategoryEnforcesUniqueness() {
        FinCategoryDto food = tools.upsertCategory(new UpsertCategoryInput(
                null, householdId, null, "Food", "expense", "🍎"));
        assertThat(food.id()).isNotNull();

        // Duplicate (household, name, kind) — DB unique constraint must fire.
        assertThatThrownBy(() -> tools.upsertCategory(new UpsertCategoryInput(
                null, householdId, null, "Food", "expense", null)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void addTransactionAndListFiltersByCategoryAndTime() {
        // Use a fresh account per test so the listTransactions filter scopes us cleanly —
        // SpringBootTest reuses one DB across methods and JUnit doesn't promise order.
        FinAccountDto acc = tools.upsertAccount(new UpsertAccountInput(
                null, householdId, null, "Wallet-filter-test", "cash", "EUR",
                new BigDecimal("0.00"), null));
        FinCategoryDto coffee = tools.upsertCategory(new UpsertCategoryInput(
                null, householdId, null, "Coffee", "expense", null));
        FinCategoryDto salary = tools.upsertCategory(new UpsertCategoryInput(
                null, householdId, null, "Salary", "income", null));

        Instant t0 = Instant.parse("2026-05-01T10:00:00Z");
        Instant t1 = t0.plus(1, ChronoUnit.DAYS);
        Instant t2 = t0.plus(2, ChronoUnit.DAYS);

        tools.addTransaction(new AddTransactionInput(
                householdId, acc.id(), coffee.id(), null,
                new BigDecimal("-4.50"), null, t0, "Latte", null, null));
        tools.addTransaction(new AddTransactionInput(
                householdId, acc.id(), coffee.id(), null,
                new BigDecimal("-5.00"), null, t1, "Espresso", null, null));
        tools.addTransaction(new AddTransactionInput(
                householdId, acc.id(), salary.id(), null,
                new BigDecimal("3000.00"), null, t2, "Monthly", null, null));

        List<FinTransactionDto> onAccount = tools.listTransactions(new ListTransactionsInput(
                householdId, acc.id(), null, null, null, null));
        assertThat(onAccount).hasSize(3);
        // newest first
        assertThat(onAccount.get(0).note()).isEqualTo("Monthly");

        List<FinTransactionDto> coffeeOnly = tools.listTransactions(new ListTransactionsInput(
                householdId, acc.id(), coffee.id(), null, null, null));
        assertThat(coffeeOnly).hasSize(2);
        assertThat(coffeeOnly).allMatch(t -> coffee.id().equals(t.categoryId()));

        // Date range: only t0 (inclusive) — t1 (exclusive).
        List<FinTransactionDto> day1 = tools.listTransactions(new ListTransactionsInput(
                householdId, acc.id(), null, t0, t1, null));
        assertThat(day1).hasSize(1);
        assertThat(day1.get(0).note()).isEqualTo("Latte");
    }

    @Test
    void getBalanceIncludesOpeningBalancePlusSignedSum() {
        FinAccountDto acc = tools.upsertAccount(new UpsertAccountInput(
                null, householdId, null, "Savings", "deposit", "USD",
                new BigDecimal("1000.00"), null));

        tools.addTransaction(new AddTransactionInput(
                householdId, acc.id(), null, null,
                new BigDecimal("500.00"), null, Instant.now(), "deposit", null, null));
        tools.addTransaction(new AddTransactionInput(
                householdId, acc.id(), null, null,
                new BigDecimal("-200.00"), null, Instant.now(), "withdraw", null, null));

        BalanceResult balance = tools.getBalance(acc.id());
        assertThat(balance.accountId()).isEqualTo(acc.id());
        assertThat(balance.currency()).isEqualTo("USD");
        assertThat(balance.balance()).isEqualByComparingTo("1300.00");
    }

    @Test
    void addTransactionRejectsCrossHouseholdAccount() {
        FinAccountDto acc = tools.upsertAccount(new UpsertAccountInput(
                null, householdId, null, "Cross-household-test", "card", "EUR",
                BigDecimal.ZERO, null));

        assertThatThrownBy(() -> tools.addTransaction(new AddTransactionInput(
                otherHouseholdId, acc.id(), null, null,
                new BigDecimal("-10.00"), null, Instant.now(), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to household");
    }

    @Test
    void setBudgetUpsertClosesPriorActiveAndOnlyOneActiveRemains() throws Exception {
        FinCategoryDto cat = tools.upsertCategory(new UpsertCategoryInput(
                null, householdId, null, "Groceries-budget-upsert", "expense", null));

        FinBudgetDto first = tools.setBudget(new SetBudgetInput(
                householdId, cat.id(), "month", new BigDecimal("300.00"), "EUR"));
        assertThat(first.id()).isNotNull();
        assertThat(first.validTo()).isNull();
        assertThat(first.scheduleId()).isNotNull();

        // First set_budget POSTed exactly one /v1/schedules with cron + payload.
        RecordedRequest firstPost = scheduler.takeRequest(2, TimeUnit.SECONDS);
        assertThat(firstPost).isNotNull();
        assertThat(firstPost.getMethod()).isEqualTo("POST");
        assertThat(firstPost.getPath()).isEqualTo("/v1/schedules");
        String body = firstPost.getBody().readUtf8();
        assertThat(body).contains("\"cron\":\"0 0 9 * * *\"");
        assertThat(body).contains("\"kind\":\"budget.alert\"");
        assertThat(body).contains("\"ownerAgent\":\"finance\"");
        assertThat(body).contains(cat.id().toString());
        assertThat(body).contains("\"period\":\"month\"");

        FinBudgetDto second = tools.setBudget(new SetBudgetInput(
                householdId, cat.id(), "month", new BigDecimal("400.00"), "EUR"));
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.limitAmount()).isEqualByComparingTo("400.00");
        assertThat(second.validTo()).isNull();
        assertThat(second.scheduleId()).isNotNull();
        assertThat(second.scheduleId()).isNotEqualTo(first.scheduleId());

        // Replace flow: register new + delete old, in that order (so a flaky
        // scheduler can't leave the budget cron-less).
        RecordedRequest secondPost = scheduler.takeRequest(2, TimeUnit.SECONDS);
        assertThat(secondPost).isNotNull();
        assertThat(secondPost.getMethod()).isEqualTo("POST");
        assertThat(secondPost.getPath()).isEqualTo("/v1/schedules");

        RecordedRequest oldDelete = scheduler.takeRequest(2, TimeUnit.SECONDS);
        assertThat(oldDelete).isNotNull();
        assertThat(oldDelete.getMethod()).isEqualTo("DELETE");
        assertThat(oldDelete.getPath()).isEqualTo("/v1/schedules/" + first.scheduleId());

        // The original row was closed; partial unique index guarantees only
        // one active row per (household, category, period).
        Long activeCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM finance.fin_budget
                WHERE household_id = ? AND category_id = ? AND period = ? AND valid_to IS NULL
                """, Long.class, householdId, cat.id(), "month");
        assertThat(activeCount).isEqualTo(1L);
        Long historicalCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM finance.fin_budget
                WHERE household_id = ? AND category_id = ? AND period = ? AND valid_to IS NOT NULL
                """, Long.class, householdId, cat.id(), "month");
        assertThat(historicalCount).isEqualTo(1L);
    }

    @Test
    void setBudgetSurvivesSchedulerOutageAndSavesRowWithoutScheduleId() throws Exception {
        FinCategoryDto cat = tools.upsertCategory(new UpsertCategoryInput(
                null, householdId, null, "Scheduler-outage-cat", "expense", null));
        schedulerDispatcher.simulate5xx = true;

        FinBudgetDto saved = tools.setBudget(new SetBudgetInput(
                householdId, cat.id(), "month", new BigDecimal("123.00"), "EUR"));
        // Soft-fail: budget row is created without a schedule_id so a future
        // reconciliation pass can wire the cron later. Tool MUST NOT throw.
        assertThat(saved.id()).isNotNull();
        assertThat(saved.scheduleId()).isNull();

        // The POST was attempted (and 500'd).
        RecordedRequest attempted = scheduler.takeRequest(2, TimeUnit.SECONDS);
        assertThat(attempted).isNotNull();
        assertThat(attempted.getMethod()).isEqualTo("POST");
        assertThat(attempted.getPath()).isEqualTo("/v1/schedules");
    }

    @Test
    void setGiftBudgetRuleUpsertsCaseInsensitiveAndListsOrdered() {
        GiftBudgetRuleDto parent = tools.setGiftBudgetRule(new SetGiftBudgetRuleInput(
                householdId, "parent", new BigDecimal("20000.00"), "RUB"));
        assertThat(parent.id()).isNotNull();
        assertThat(parent.amount()).isEqualByComparingTo("20000.00");

        tools.setGiftBudgetRule(new SetGiftBudgetRuleInput(
                householdId, "friend", new BigDecimal("5000.00"), "RUB"));

        // Same tier, different case → updates in place (one row per tier).
        GiftBudgetRuleDto parentUpdated = tools.setGiftBudgetRule(new SetGiftBudgetRuleInput(
                householdId, "Parent", new BigDecimal("25000.00"), "RUB"));
        assertThat(parentUpdated.id()).isEqualTo(parent.id());
        assertThat(parentUpdated.amount()).isEqualByComparingTo("25000.00");

        List<GiftBudgetRuleDto> rules = tools.listGiftBudgetRules(householdId);
        assertThat(rules).extracting(GiftBudgetRuleDto::relationship)
                .containsExactly("friend", "parent");
        assertThat(rules).filteredOn(r -> r.relationship().equals("parent"))
                .singleElement()
                .satisfies(r -> assertThat(r.amount()).isEqualByComparingTo("25000.00"));

        // Functional unique index guarantees one row per (household, tier).
        Long parentRows = jdbc.queryForObject("""
                SELECT COUNT(*) FROM finance.fin_gift_budget_rule
                WHERE household_id = ? AND lower(relationship) = 'parent'
                """, Long.class, householdId);
        assertThat(parentRows).isEqualTo(1L);
    }

    @Test
    void getBudgetStatusComputesSpentInsideCurrentMonthWindow() {
        FinAccountDto acc = tools.upsertAccount(new UpsertAccountInput(
                null, householdId, null, "Status-test", "card", "EUR",
                BigDecimal.ZERO, null));
        FinCategoryDto cat = tools.upsertCategory(new UpsertCategoryInput(
                null, householdId, null, "Coffee-status-test", "expense", null));
        tools.setBudget(new SetBudgetInput(
                householdId, cat.id(), "month", new BigDecimal("50.00"), "EUR"));

        // Two expenses inside the current UTC month, one refund, one outside
        // the window — only the in-window net spend should count.
        Instant now = Instant.now();
        Instant lastYear = now.minus(400, ChronoUnit.DAYS);
        tools.addTransaction(new AddTransactionInput(
                householdId, acc.id(), cat.id(), null,
                new BigDecimal("-8.00"), null, now, "in-window 1", null, null));
        tools.addTransaction(new AddTransactionInput(
                householdId, acc.id(), cat.id(), null,
                new BigDecimal("-12.00"), null, now, "in-window 2", null, null));
        tools.addTransaction(new AddTransactionInput(
                householdId, acc.id(), cat.id(), null,
                new BigDecimal("2.00"), null, now, "refund", null, null));
        tools.addTransaction(new AddTransactionInput(
                householdId, acc.id(), cat.id(), null,
                new BigDecimal("-999.00"), null, lastYear, "out-of-window", null, null));

        BudgetStatusResult status = tools.getBudgetStatus(householdId, cat.id(), "month");
        assertThat(status.limitAmount()).isEqualByComparingTo("50.00");
        // net in-window = -8 -12 +2 = -18 → spent = 18
        assertThat(status.spent()).isEqualByComparingTo("18.00");
        assertThat(status.currency()).isEqualTo("EUR");
        assertThat(status.categoryName()).isEqualTo("Coffee-status-test");
        assertThat(status.periodTo()).isAfter(status.periodFrom());
        // ratio = 18/50 = 0.3600
        assertThat(status.ratio()).isEqualByComparingTo("0.3600");
    }

    @Test
    void getBudgetStatusThrowsWhenNoActiveBudget() {
        FinCategoryDto cat = tools.upsertCategory(new UpsertCategoryInput(
                null, householdId, null, "No-budget-cat", "expense", null));
        assertThatThrownBy(() -> tools.getBudgetStatus(householdId, cat.id(), "month"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No active budget");
    }

    @Test
    void setBudgetRejectsCrossHouseholdCategoryAndUnsupportedPeriod() {
        FinCategoryDto theirs = tools.upsertCategory(new UpsertCategoryInput(
                null, otherHouseholdId, null, "Their-cat", "expense", null));
        assertThatThrownBy(() -> tools.setBudget(new SetBudgetInput(
                householdId, theirs.id(), "month", new BigDecimal("100.00"), "EUR")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to household");

        FinCategoryDto mine = tools.upsertCategory(new UpsertCategoryInput(
                null, householdId, null, "Bad-period-cat", "expense", null));
        assertThatThrownBy(() -> tools.setBudget(new SetBudgetInput(
                householdId, mine.id(), "decade", new BigDecimal("100.00"), "EUR")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported period");
    }

    @Test
    void spendingByCategoryAggregatesAndDefaultsToExpenseKind() {
        FinAccountDto acc = tools.upsertAccount(new UpsertAccountInput(
                null, householdId, null, "Spending-agg", "card", "EUR",
                BigDecimal.ZERO, null));
        FinCategoryDto groceries = tools.upsertCategory(new UpsertCategoryInput(
                null, householdId, null, "Groceries-agg", "expense", null));
        FinCategoryDto transport = tools.upsertCategory(new UpsertCategoryInput(
                null, householdId, null, "Transport-agg", "expense", null));
        FinCategoryDto salary = tools.upsertCategory(new UpsertCategoryInput(
                null, householdId, null, "Salary-agg", "income", null));

        Instant from = Instant.parse("2026-04-01T00:00:00Z");
        Instant to   = Instant.parse("2026-05-01T00:00:00Z");
        Instant in1  = Instant.parse("2026-04-10T10:00:00Z");
        Instant in2  = Instant.parse("2026-04-15T10:00:00Z");
        Instant out  = Instant.parse("2026-05-02T10:00:00Z");

        tools.addTransaction(new AddTransactionInput(
                householdId, acc.id(), groceries.id(), null,
                new BigDecimal("-40.00"), null, in1, "veg", null, null));
        tools.addTransaction(new AddTransactionInput(
                householdId, acc.id(), groceries.id(), null,
                new BigDecimal("-60.00"), null, in2, "meat", null, null));
        tools.addTransaction(new AddTransactionInput(
                householdId, acc.id(), transport.id(), null,
                new BigDecimal("-25.00"), null, in1, "metro", null, null));
        tools.addTransaction(new AddTransactionInput(
                householdId, acc.id(), salary.id(), null,
                new BigDecimal("3000.00"), null, in1, "salary", null, null));
        tools.addTransaction(new AddTransactionInput(
                householdId, acc.id(), groceries.id(), null,
                new BigDecimal("-9999.00"), null, out, "out-of-window", null, null));

        List<SpendingByCategoryRow> expenseRows = tools.spendingByCategory(
                new SpendingByCategoryInput(householdId, from, to, null));
        // Default kind is expense — salary must not appear.
        assertThat(expenseRows).extracting(SpendingByCategoryRow::categoryId)
                .containsExactlyInAnyOrder(groceries.id(), transport.id());
        // Ordered by absolute spend desc — groceries (100) before transport (25).
        assertThat(expenseRows.get(0).categoryId()).isEqualTo(groceries.id());
        assertThat(expenseRows.get(0).spent()).isEqualByComparingTo("100.00");
        assertThat(expenseRows.get(0).txCount()).isEqualTo(2L);
        assertThat(expenseRows.get(1).categoryId()).isEqualTo(transport.id());
        assertThat(expenseRows.get(1).spent()).isEqualByComparingTo("25.00");

        // Explicit kind=income → only salary.
        List<SpendingByCategoryRow> incomeRows = tools.spendingByCategory(
                new SpendingByCategoryInput(householdId, from, to, "income"));
        assertThat(incomeRows).hasSize(1);
        assertThat(incomeRows.get(0).categoryId()).isEqualTo(salary.id());
        assertThat(incomeRows.get(0).spent()).isEqualByComparingTo("3000.00");
    }

    @Test
    void internalBudgetStatusReturnsLiveSnapshotOver404WhenNoActiveBudget() {
        FinAccountDto acc = tools.upsertAccount(new UpsertAccountInput(
                null, householdId, null, "Internal-budget-test", "card", "EUR",
                BigDecimal.ZERO, null));
        FinCategoryDto cat = tools.upsertCategory(new UpsertCategoryInput(
                null, householdId, null, "Internal-budget-cat", "expense", null));

        WebTestClient client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port).build();

        // No budget yet → 404.
        client.get().uri(uri -> uri.path("/internal/budget-status")
                        .queryParam("householdId", householdId)
                        .queryParam("categoryId", cat.id())
                        .queryParam("period", "month").build())
                .exchange()
                .expectStatus().isNotFound();

        // Set a budget + one in-window expense, then expect the snapshot.
        tools.setBudget(new SetBudgetInput(
                householdId, cat.id(), "month", new BigDecimal("80.00"), "EUR"));
        tools.addTransaction(new AddTransactionInput(
                householdId, acc.id(), cat.id(), null,
                new BigDecimal("-32.00"), null, Instant.now(), "lunch", null, null));

        client.get().uri(uri -> uri.path("/internal/budget-status")
                        .queryParam("householdId", householdId)
                        .queryParam("categoryId", cat.id())
                        .queryParam("period", "month").build())
                .exchange()
                .expectStatus().isOk()
                .expectBody(BudgetStatusResult.class)
                .value(r -> {
                    assertThat(r.categoryId()).isEqualTo(cat.id());
                    assertThat(r.categoryName()).isEqualTo("Internal-budget-cat");
                    assertThat(r.limitAmount()).isEqualByComparingTo("80.00");
                    assertThat(r.spent()).isEqualByComparingTo("32.00");
                    assertThat(r.currency()).isEqualTo("EUR");
                    assertThat(r.period()).isEqualTo("month");
                    assertThat(r.ratio()).isEqualByComparingTo("0.4000");
                });
    }

    /** scheduler-service stub: 201 with a JSON ScheduleDto echoing the request on POST,
     *  204 on DELETE; flips to 500 when {@link #simulate5xx} is set so the soft-fail
     *  path on the tool side can be exercised. */
    static final class SchedulerDispatcher extends Dispatcher {
        private static final ObjectMapper M = new ObjectMapper().findAndRegisterModules();
        volatile boolean simulate5xx;

        void reset() {
            simulate5xx = false;
        }

        @Override
        public MockResponse dispatch(RecordedRequest recordedRequest) {
            String path = recordedRequest.getPath() == null ? "" : recordedRequest.getPath();
            if (simulate5xx) {
                return new MockResponse().setResponseCode(500);
            }
            if ("POST".equals(recordedRequest.getMethod()) && "/v1/schedules".equals(path)) {
                ScheduleDto dto = new ScheduleDto(
                        UUID.randomUUID(), null, "finance", "budget.alert",
                        "0 0 9 * * *", null, true, Instant.now(), null, Instant.now());
                try {
                    return new MockResponse()
                            .setHeader("content-type", "application/json")
                            .setBody(M.writeValueAsString(dto));
                } catch (Exception e) {
                    return new MockResponse().setResponseCode(500);
                }
            }
            if ("DELETE".equals(recordedRequest.getMethod())
                    && path.startsWith("/v1/schedules/")) {
                return new MockResponse().setResponseCode(204);
            }
            return new MockResponse().setResponseCode(404);
        }
    }

    @Test
    void upsertRecurringComputesNextDueAndUpdatePreservesIdRecomputesNextDue() {
        FinAccountDto acc = tools.upsertAccount(new UpsertAccountInput(
                null, householdId, null, "Recurring-test-acc", "card", "EUR",
                BigDecimal.ZERO, null));
        FinCategoryDto rent = tools.upsertCategory(new UpsertCategoryInput(
                null, householdId, null, "Rent-recurring", "expense", null));

        // Every minute — so next_due is at most 60 seconds from now and
        // definitely in the future regardless of how slow CI is.
        FinRecurringDto created = tools.upsertRecurring(new UpsertRecurringInput(
                null, householdId, null, acc.id(), rent.id(),
                "Apartment rent", new BigDecimal("-1500.00"), null,
                "0 * * * * *", "Bank transfer", false));
        assertThat(created.id()).isNotNull();
        assertThat(created.currency()).isEqualTo("EUR"); // defaulted from account
        assertThat(created.nextDue()).isNotNull();
        assertThat(created.nextDue()).isAfter(Instant.now().minusSeconds(2));
        assertThat(created.nextDue()).isBefore(Instant.now().plusSeconds(70));
        assertThat(created.autoRemind()).isFalse();

        // Update: switch to "every day at 09:00" — next_due must move further out.
        FinRecurringDto updated = tools.upsertRecurring(new UpsertRecurringInput(
                created.id(), householdId, null, acc.id(), rent.id(),
                "Apartment rent (updated)", new BigDecimal("-1600.00"), null,
                "0 0 9 * * *", "Bank transfer", true));
        assertThat(updated.id()).isEqualTo(created.id());
        assertThat(updated.amount()).isEqualByComparingTo("-1600.00");
        assertThat(updated.autoRemind()).isTrue();
        assertThat(updated.nextDue()).isNotNull();
        // Daily cron's next_due is at most 24h out — and definitely later than the
        // per-minute cron's next_due was.
        assertThat(updated.nextDue()).isAfter(created.nextDue());
        assertThat(updated.nextDue()).isBefore(Instant.now().plusSeconds(86400 + 60));
    }

    @Test
    void listRecurringFiltersByAccountAndOrdersByNextDueAsc() {
        FinAccountDto acc1 = tools.upsertAccount(new UpsertAccountInput(
                null, householdId, null, "Recurring-list-1", "card", "EUR",
                BigDecimal.ZERO, null));
        FinAccountDto acc2 = tools.upsertAccount(new UpsertAccountInput(
                null, householdId, null, "Recurring-list-2", "card", "EUR",
                BigDecimal.ZERO, null));

        // acc1 has two: one per-minute (soonest), one daily (later).
        tools.upsertRecurring(new UpsertRecurringInput(
                null, householdId, null, acc1.id(), null,
                "soon", new BigDecimal("-1.00"), null, "0 * * * * *", null, false));
        tools.upsertRecurring(new UpsertRecurringInput(
                null, householdId, null, acc1.id(), null,
                "later", new BigDecimal("-2.00"), null, "0 0 9 * * *", null, false));
        // acc2 has one — must NOT appear when we filter by acc1.
        tools.upsertRecurring(new UpsertRecurringInput(
                null, householdId, null, acc2.id(), null,
                "other-acc", new BigDecimal("-3.00"), null, "0 0 9 * * *", null, false));

        List<FinRecurringDto> onAcc1 = tools.listRecurring(householdId, acc1.id(), null);
        assertThat(onAcc1).hasSize(2);
        assertThat(onAcc1.get(0).name()).isEqualTo("soon"); // next_due ASC
        assertThat(onAcc1.get(1).name()).isEqualTo("later");
        assertThat(onAcc1).allMatch(r -> acc1.id().equals(r.accountId()));
    }

    @Test
    void upsertRecurringWithAutoRemindRegistersScheduleAndPropagatesId() throws Exception {
        FinAccountDto acc = tools.upsertAccount(new UpsertAccountInput(
                null, householdId, null, "Recurring-autoremind-acc", "card", "EUR",
                BigDecimal.ZERO, null));

        FinRecurringDto created = tools.upsertRecurring(new UpsertRecurringInput(
                null, householdId, null, acc.id(), null,
                "Spotify", new BigDecimal("-9.99"), null,
                "0 0 9 1 * *", null, true));

        assertThat(created.autoRemind()).isTrue();
        assertThat(created.scheduleId()).isNotNull();

        RecordedRequest post = scheduler.takeRequest(2, TimeUnit.SECONDS);
        assertThat(post).isNotNull();
        assertThat(post.getMethod()).isEqualTo("POST");
        assertThat(post.getPath()).isEqualTo("/v1/schedules");
        String body = post.getBody().readUtf8();
        // Cron comes from the row, not props — that's the key difference vs budgets.
        assertThat(body).contains("\"cron\":\"0 0 9 1 * *\"");
        assertThat(body).contains("\"kind\":\"recurring.due\"");
        assertThat(body).contains("\"ownerAgent\":\"finance\"");
        assertThat(body).contains(created.id().toString());
    }

    @Test
    void upsertRecurringWithoutAutoRemindSkipsRegister() throws Exception {
        FinAccountDto acc = tools.upsertAccount(new UpsertAccountInput(
                null, householdId, null, "Recurring-no-autoremind-acc", "card", "EUR",
                BigDecimal.ZERO, null));

        FinRecurringDto created = tools.upsertRecurring(new UpsertRecurringInput(
                null, householdId, null, acc.id(), null,
                "Manual rent", new BigDecimal("-1500.00"), null,
                "0 0 9 1 * *", null, false));

        assertThat(created.autoRemind()).isFalse();
        assertThat(created.scheduleId()).isNull();
        // No scheduler call at all.
        assertThat(scheduler.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void updateChangingCronWithAutoRemindReplacesSchedule() throws Exception {
        FinAccountDto acc = tools.upsertAccount(new UpsertAccountInput(
                null, householdId, null, "Recurring-replace-acc", "card", "EUR",
                BigDecimal.ZERO, null));

        FinRecurringDto first = tools.upsertRecurring(new UpsertRecurringInput(
                null, householdId, null, acc.id(), null,
                "Subscription", new BigDecimal("-9.99"), null,
                "0 0 9 1 * *", null, true));
        assertThat(first.scheduleId()).isNotNull();
        RecordedRequest firstPost = scheduler.takeRequest(2, TimeUnit.SECONDS);
        assertThat(firstPost).isNotNull();
        assertThat(firstPost.getMethod()).isEqualTo("POST");

        FinRecurringDto updated = tools.upsertRecurring(new UpsertRecurringInput(
                first.id(), householdId, null, acc.id(), null,
                "Subscription (yearly now)", new BigDecimal("-99.99"), null,
                "0 0 9 1 1 *", null, true));
        assertThat(updated.id()).isEqualTo(first.id());
        assertThat(updated.scheduleId()).isNotNull();
        assertThat(updated.scheduleId()).isNotEqualTo(first.scheduleId());

        // Replace order: POST new first, DELETE old after — same flake-safe
        // ordering as setBudget (PR27b).
        RecordedRequest newPost = scheduler.takeRequest(2, TimeUnit.SECONDS);
        assertThat(newPost).isNotNull();
        assertThat(newPost.getMethod()).isEqualTo("POST");
        assertThat(newPost.getBody().readUtf8()).contains("\"cron\":\"0 0 9 1 1 *\"");

        RecordedRequest oldDelete = scheduler.takeRequest(2, TimeUnit.SECONDS);
        assertThat(oldDelete).isNotNull();
        assertThat(oldDelete.getMethod()).isEqualTo("DELETE");
        assertThat(oldDelete.getPath()).isEqualTo("/v1/schedules/" + first.scheduleId());
    }

    @Test
    void updateFlippingAutoRemindToFalseDeletesSchedule() throws Exception {
        FinAccountDto acc = tools.upsertAccount(new UpsertAccountInput(
                null, householdId, null, "Recurring-flip-acc", "card", "EUR",
                BigDecimal.ZERO, null));

        FinRecurringDto first = tools.upsertRecurring(new UpsertRecurringInput(
                null, householdId, null, acc.id(), null,
                "Reminder-on", new BigDecimal("-50.00"), null,
                "0 0 9 1 * *", null, true));
        assertThat(first.scheduleId()).isNotNull();
        UUID priorScheduleId = first.scheduleId();
        assertThat(scheduler.takeRequest(2, TimeUnit.SECONDS).getMethod()).isEqualTo("POST");

        FinRecurringDto disabled = tools.upsertRecurring(new UpsertRecurringInput(
                first.id(), householdId, null, acc.id(), null,
                "Reminder-off", new BigDecimal("-50.00"), null,
                "0 0 9 1 * *", null, false));
        assertThat(disabled.id()).isEqualTo(first.id());
        assertThat(disabled.autoRemind()).isFalse();
        assertThat(disabled.scheduleId()).isNull();

        RecordedRequest delete = scheduler.takeRequest(2, TimeUnit.SECONDS);
        assertThat(delete).isNotNull();
        assertThat(delete.getMethod()).isEqualTo("DELETE");
        assertThat(delete.getPath()).isEqualTo("/v1/schedules/" + priorScheduleId);
        // No POST during the flip-off.
        assertThat(scheduler.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void upsertRecurringRejectsCrossHouseholdAccountAndInvalidCron() {
        FinAccountDto theirAcc = tools.upsertAccount(new UpsertAccountInput(
                null, otherHouseholdId, null, "Their-acc-recurring", "card", "EUR",
                BigDecimal.ZERO, null));
        assertThatThrownBy(() -> tools.upsertRecurring(new UpsertRecurringInput(
                null, householdId, null, theirAcc.id(), null,
                "x", new BigDecimal("-10.00"), null, "0 * * * * *", null, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to household");

        FinAccountDto mine = tools.upsertAccount(new UpsertAccountInput(
                null, householdId, null, "Mine-recurring-bad-cron", "card", "EUR",
                BigDecimal.ZERO, null));
        assertThatThrownBy(() -> tools.upsertRecurring(new UpsertRecurringInput(
                null, householdId, null, mine.id(), null,
                "x", new BigDecimal("-10.00"), null, "not a cron", null, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid cron");
    }

    @Test
    void internalRecurringReturnsRowAfterUpsertAnd404Otherwise() {
        FinAccountDto acc = tools.upsertAccount(new UpsertAccountInput(
                null, householdId, null, "Internal-recurring-acc", "card", "EUR",
                BigDecimal.ZERO, null));

        WebTestClient client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port).build();

        UUID unknown = UUID.randomUUID();
        client.get().uri("/internal/recurring/{id}", unknown)
                .exchange()
                .expectStatus().isNotFound();

        FinRecurringDto created = tools.upsertRecurring(new UpsertRecurringInput(
                null, householdId, null, acc.id(), null,
                "Internal-recurring-test", new BigDecimal("-99.00"), null,
                "0 0 9 * * *", "snapshot", false));

        client.get().uri("/internal/recurring/{id}", created.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(FinRecurringDto.class)
                .value(r -> {
                    assertThat(r.id()).isEqualTo(created.id());
                    assertThat(r.name()).isEqualTo("Internal-recurring-test");
                    assertThat(r.amount()).isEqualByComparingTo("-99.00");
                    assertThat(r.currency()).isEqualTo("EUR");
                    assertThat(r.nextDue()).isNotNull();
                });
    }

    @Test
    void internalRecurringAdvanceRecomputesNextDueAnd404Otherwise() {
        FinAccountDto acc = tools.upsertAccount(new UpsertAccountInput(
                null, householdId, null, "Recurring-advance-acc", "card", "EUR",
                BigDecimal.ZERO, null));

        WebTestClient client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port).build();

        UUID unknown = UUID.randomUUID();
        client.post().uri("/internal/recurring/{id}/advance", unknown)
                .exchange()
                .expectStatus().isNotFound();

        FinRecurringDto created = tools.upsertRecurring(new UpsertRecurringInput(
                null, householdId, null, acc.id(), null,
                "Advance-test", new BigDecimal("-10.00"), null,
                "0 0 9 * * *", null, false));
        assertThat(created.nextDue()).isNotNull();

        client.post().uri("/internal/recurring/{id}/advance", created.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(FinRecurringDto.class)
                .value(r -> {
                    assertThat(r.id()).isEqualTo(created.id());
                    // Invariant: post-advance next_due is strictly after "now" —
                    // CronExpression.next(from) is strictly > from. Comparing to the
                    // pre-advance value is flaky for slow crons (might land on the
                    // same boundary), so we assert the invariant instead.
                    assertThat(r.nextDue()).isAfter(Instant.now());
                });
    }

    @Test
    void addTransactionWithoutCategoryRegistersOneshotSchedule() throws Exception {
        FinAccountDto acc = tools.upsertAccount(new UpsertAccountInput(
                null, householdId, null, "Uncategorised-test-acc", "card", "EUR",
                BigDecimal.ZERO, null));

        FinTransactionDto saved = tools.addTransaction(new AddTransactionInput(
                householdId, acc.id(), null, null,
                new BigDecimal("-4.50"), null, Instant.now(),
                "morning coffee", "telegram", null));
        assertThat(saved.id()).isNotNull();
        assertThat(saved.categoryId()).isNull();

        // The uncategorised row triggers a one-shot wake so the categorizer
        // skill can suggest a category. Payload carries just transactionId;
        // finance-agent's enrichment hydrates the rest at trigger time.
        RecordedRequest post = scheduler.takeRequest(2, TimeUnit.SECONDS);
        assertThat(post).isNotNull();
        assertThat(post.getMethod()).isEqualTo("POST");
        assertThat(post.getPath()).isEqualTo("/v1/schedules");
        String body = post.getBody().readUtf8();
        assertThat(body).contains("\"kind\":\"transaction.uncategorised\"");
        assertThat(body).contains("\"ownerAgent\":\"finance\"");
        assertThat(body).contains(saved.id().toString());
        // One-shot — runAt is set, cron is not.
        assertThat(body).contains("\"runAt\"");
        assertThat(body).doesNotContain("\"cron\":\"");
    }

    @Test
    void addTransactionFromMoneyProImportDoesNotRegisterScheduler() throws Exception {
        FinAccountDto acc = tools.upsertAccount(new UpsertAccountInput(
                null, householdId, null, "MoneyPro-bulk-acc", "card", "EUR",
                BigDecimal.ZERO, null));

        // Bulk import: even though categoryId is null, the categorizer skill is
        // not appropriate — reconciliation is a different code path. No scheduler
        // call must happen.
        FinTransactionDto bulk = tools.addTransaction(new AddTransactionInput(
                householdId, acc.id(), null, null,
                new BigDecimal("-19.99"), null, Instant.now(),
                "imported row", "moneypro_import", "ext-42"));
        assertThat(bulk.id()).isNotNull();
        assertThat(bulk.source()).isEqualTo("moneypro_import");

        assertThat(scheduler.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void internalTransactionReturnsRowAfterAddAnd404Otherwise() {
        FinAccountDto acc = tools.upsertAccount(new UpsertAccountInput(
                null, householdId, null, "Internal-transaction-acc", "card", "EUR",
                BigDecimal.ZERO, null));

        WebTestClient client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port).build();

        UUID unknown = UUID.randomUUID();
        client.get().uri("/internal/transaction/{id}", unknown)
                .exchange()
                .expectStatus().isNotFound();

        FinTransactionDto saved = tools.addTransaction(new AddTransactionInput(
                householdId, acc.id(), null, null,
                new BigDecimal("-7.25"), null, Instant.parse("2026-05-01T09:00:00Z"),
                "internal-test", "manual", null));

        client.get().uri("/internal/transaction/{id}", saved.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(FinTransactionDto.class)
                .value(r -> {
                    assertThat(r.id()).isEqualTo(saved.id());
                    assertThat(r.amount()).isEqualByComparingTo("-7.25");
                    assertThat(r.currency()).isEqualTo("EUR");
                    assertThat(r.note()).isEqualTo("internal-test");
                    assertThat(r.source()).isEqualTo("manual");
                });
    }

    @Test
    void internalAddTransactionPostPersistsRowAnd400OnBadInput() {
        FinAccountDto acc = tools.upsertAccount(new UpsertAccountInput(
                null, householdId, null, "Internal-add-acc", "card", "EUR",
                BigDecimal.ZERO, null));

        WebTestClient client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port).build();

        // Happy path: POST a parsed receipt transaction → persisted, returns the DTO.
        FinTransactionDto created = client.post().uri("/internal/transaction")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AddTransactionInput(
                        householdId, acc.id(), null, null,
                        new BigDecimal("-3.50"), "EUR", Instant.parse("2026-06-01T08:00:00Z"),
                        "coffee from receipt", "telegram", null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(FinTransactionDto.class)
                .returnResult().getResponseBody();
        assertThat(created).isNotNull();
        assertThat(created.id()).isNotNull();
        assertThat(created.amount()).isEqualByComparingTo("-3.50");
        assertThat(created.note()).isEqualTo("coffee from receipt");
        assertThat(created.source()).isEqualTo("telegram");

        // Row really landed (GET round-trips it).
        client.get().uri("/internal/transaction/{id}", created.id())
                .exchange().expectStatus().isOk();

        // Bad input (account from another household) → 400, surfaced from the tool guard.
        client.post().uri("/internal/transaction")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AddTransactionInput(
                        otherHouseholdId, acc.id(), null, null,
                        new BigDecimal("-1.00"), "EUR", Instant.now(), "bad", "telegram", null))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void internalGiftBudgetReturnsMonthlyEnvelopeOver404WhenUnset() {
        // Fresh household keeps the Gifts-category lookup deterministic against
        // the shared test DB.
        UUID hh = UUID.randomUUID();
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", hh, "gift hh");

        FinAccountDto acc = tools.upsertAccount(new UpsertAccountInput(
                null, hh, null, "Gift-budget-acc", "card", "EUR", BigDecimal.ZERO, null));

        WebTestClient client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port).build();

        // No Gifts category yet → 404.
        client.get().uri(uri -> uri.path("/internal/gift-budget")
                        .queryParam("householdId", hh).build())
                .exchange()
                .expectStatus().isNotFound();

        // Gifts category exists (lowercase — exercises the case-insensitive
        // match) but still no budget → 404.
        FinCategoryDto gifts = tools.upsertCategory(new UpsertCategoryInput(
                null, hh, null, "gifts", "expense", null));
        client.get().uri(uri -> uri.path("/internal/gift-budget")
                        .queryParam("householdId", hh).build())
                .exchange()
                .expectStatus().isNotFound();

        // Set a monthly gift budget + one in-window expense → envelope snapshot.
        tools.setBudget(new SetBudgetInput(
                hh, gifts.id(), "month", new BigDecimal("200.00"), "EUR"));
        tools.addTransaction(new AddTransactionInput(
                hh, acc.id(), gifts.id(), null,
                new BigDecimal("-50.00"), null, Instant.now(), "present", null, null));

        client.get().uri(uri -> uri.path("/internal/gift-budget")
                        .queryParam("householdId", hh).build())
                .exchange()
                .expectStatus().isOk()
                .expectBody(GiftBudgetResult.class)
                .value(r -> {
                    assertThat(r.amount()).isEqualByComparingTo("200.00");
                    assertThat(r.currency()).isEqualTo("EUR");
                    // remaining = limit (200) − spent (50) this month.
                    assertThat(r.remaining()).isEqualByComparingTo("150.00");
                });
    }

    @Test
    void internalGiftBudgetRuleReturnsTierRuleOr404() {
        UUID hh = UUID.randomUUID();
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", hh, "gift-rule hh");

        WebTestClient client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port).build();

        // No rule for the tier → 404 (agent falls back to the Gifts envelope).
        client.get().uri(uri -> uri.path("/internal/gift-budget-rule")
                        .queryParam("householdId", hh).queryParam("relationship", "parent").build())
                .exchange()
                .expectStatus().isNotFound();

        tools.setGiftBudgetRule(new SetGiftBudgetRuleInput(
                hh, "parent", new BigDecimal("20000.00"), "RUB"));

        // Case-insensitive lookup returns the tier rule.
        client.get().uri(uri -> uri.path("/internal/gift-budget-rule")
                        .queryParam("householdId", hh).queryParam("relationship", "Parent").build())
                .exchange()
                .expectStatus().isOk()
                .expectBody(GiftBudgetRuleDto.class)
                .value(r -> {
                    assertThat(r.relationship()).isEqualTo("parent");
                    assertThat(r.amount()).isEqualByComparingTo("20000.00");
                    assertThat(r.currency()).isEqualTo("RUB");
                });
    }

    @Test
    void internalAccountsListReturnsHouseholdAccountsOnly() {
        FinAccountDto mine = tools.upsertAccount(new UpsertAccountInput(
                null, householdId, null, "Zeta-receipt-acc", "card", "EUR",
                BigDecimal.ZERO, null));
        FinAccountDto other = tools.upsertAccount(new UpsertAccountInput(
                null, otherHouseholdId, null, "Other-household-acc", "card", "USD",
                BigDecimal.ZERO, null));

        WebTestClient client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port).build();

        List<FinAccountDto> accounts = client.get()
                .uri(uri -> uri.path("/internal/accounts").queryParam("householdId", householdId).build())
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(FinAccountDto.class)
                .returnResult().getResponseBody();

        assertThat(accounts).isNotNull();
        assertThat(accounts).anyMatch(a -> a.id().equals(mine.id()));
        assertThat(accounts).noneMatch(a -> a.id().equals(other.id()));
    }

    @Test
    void updateTransactionAppliesNonNullFieldsAndGuardsCrossHousehold() {
        FinAccountDto acc = tools.upsertAccount(new UpsertAccountInput(
                null, householdId, null, "Update-tx-acc", "card", "EUR",
                BigDecimal.ZERO, null));
        FinCategoryDto coffee = tools.upsertCategory(new UpsertCategoryInput(
                null, householdId, null, "Update-coffee", "expense", null));
        FinCategoryDto snacks = tools.upsertCategory(new UpsertCategoryInput(
                null, householdId, null, "Update-snacks", "expense", null));

        FinTransactionDto tx = tools.addTransaction(new AddTransactionInput(
                householdId, acc.id(), coffee.id(), null,
                new BigDecimal("-4.50"), null, Instant.parse("2026-05-01T10:00:00Z"),
                "latte", "manual", null));

        // Partial update: change amount, note, category — leave the rest.
        FinTransactionDto updated = tools.updateTransaction(new UpdateTransactionInput(
                tx.id(), null, snacks.id(), null,
                new BigDecimal("-5.25"), null, null, "latte + cookie"));
        assertThat(updated.id()).isEqualTo(tx.id());
        assertThat(updated.amount()).isEqualByComparingTo("-5.25");
        assertThat(updated.note()).isEqualTo("latte + cookie");
        assertThat(updated.categoryId()).isEqualTo(snacks.id());
        // Untouched fields preserved.
        assertThat(updated.currency()).isEqualTo("EUR");
        assertThat(updated.ts()).isEqualTo(Instant.parse("2026-05-01T10:00:00Z"));
        assertThat(updated.source()).isEqualTo("manual");

        // Moving to an account in another household is rejected.
        FinAccountDto theirAcc = tools.upsertAccount(new UpsertAccountInput(
                null, otherHouseholdId, null, "Their-update-acc", "card", "EUR",
                BigDecimal.ZERO, null));
        assertThatThrownBy(() -> tools.updateTransaction(new UpdateTransactionInput(
                tx.id(), theirAcc.id(), null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to household");

        // Unknown id → clear error.
        assertThatThrownBy(() -> tools.updateTransaction(new UpdateTransactionInput(
                UUID.randomUUID(), null, null, null, new BigDecimal("-1.00"),
                null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Transaction not found");
    }

    @Test
    void exportCsvRendersOldestFirstWithResolvedNamesAndEscaping() {
        // Fresh household keeps the export deterministic against the shared DB.
        UUID hh = UUID.randomUUID();
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", hh, "export hh");

        FinAccountDto acc = tools.upsertAccount(new UpsertAccountInput(
                null, hh, null, "Main, card", "card", "EUR", BigDecimal.ZERO, null));
        FinCategoryDto coffee = tools.upsertCategory(new UpsertCategoryInput(
                null, hh, null, "Coffee", "expense", null));

        Instant t1 = Instant.parse("2026-02-01T08:00:00Z");
        Instant t2 = Instant.parse("2026-02-02T08:00:00Z");
        // Insert newest first to prove the export re-sorts to oldest-first.
        tools.addTransaction(new AddTransactionInput(
                hh, acc.id(), coffee.id(), null,
                new BigDecimal("-5.00"), null, t2, "note \"with\" quote", "manual", null));
        tools.addTransaction(new AddTransactionInput(
                hh, acc.id(), null, null,
                new BigDecimal("-3.50"), null, t1, "plain", "telegram", null));

        CsvExportResult result = tools.exportCsv(new ExportCsvInput(hh, null, null, null, null));
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.truncated()).isFalse();

        String[] lines = result.csv().split("\n");
        assertThat(lines[0]).isEqualTo("date,amount,currency,category,account,note,source");
        // Oldest-first: t1 (plain) before t2.
        assertThat(lines[1]).isEqualTo(
                "2026-02-01T08:00:00Z,-3.50,EUR,,\"Main, card\",plain,telegram");
        // t2 row: uncategorised? no — coffee; account name has a comma → quoted;
        // note has embedded quotes → doubled + wrapped.
        assertThat(lines[2]).isEqualTo(
                "2026-02-02T08:00:00Z,-5.00,EUR,Coffee,\"Main, card\",\"note \"\"with\"\" quote\",manual");

        // Filtering by category narrows the export.
        CsvExportResult coffeeOnly = tools.exportCsv(
                new ExportCsvInput(hh, null, coffee.id(), null, null));
        assertThat(coffeeOnly.rowCount()).isEqualTo(1);
        assertThat(coffeeOnly.csv()).contains("Coffee").doesNotContain("plain");
    }

    @Test
    void deleteTransactionReturnsDeletedRowAndThrowsOnUnknown() {
        FinAccountDto acc = tools.upsertAccount(new UpsertAccountInput(
                null, householdId, null, "Delete-tx-acc", "card", "EUR",
                BigDecimal.ZERO, null));
        FinTransactionDto tx = tools.addTransaction(new AddTransactionInput(
                householdId, acc.id(), null, null,
                new BigDecimal("-7.00"), null, Instant.now(), "to delete", "manual", null));

        FinTransactionDto deleted = tools.deleteTransaction(tx.id());
        assertThat(deleted.id()).isEqualTo(tx.id());
        assertThat(deleted.note()).isEqualTo("to delete");

        // Row is gone — listing the account no longer shows it.
        List<FinTransactionDto> remaining = tools.listTransactions(new ListTransactionsInput(
                householdId, acc.id(), null, null, null, null));
        assertThat(remaining).noneMatch(t -> tx.id().equals(t.id()));

        // Second delete (now unknown) throws.
        assertThatThrownBy(() -> tools.deleteTransaction(tx.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Transaction not found");
    }

    @Test
    void refreshMatviewsRecomputesBalanceAndMonthlyRollup() {
        // A fresh household keeps the matview assertions deterministic — the
        // shared test DB accumulates rows across methods.
        UUID hh = UUID.randomUUID();
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", hh, "matview hh");

        FinAccountDto acc = tools.upsertAccount(new UpsertAccountInput(
                null, hh, null, "MV-account", "card", "EUR",
                new BigDecimal("100.00"), null));
        FinCategoryDto coffee = tools.upsertCategory(new UpsertCategoryInput(
                null, hh, null, "MV-coffee", "expense", null));

        Instant inMonth = Instant.parse("2026-03-10T10:00:00Z");
        tools.addTransaction(new AddTransactionInput(
                hh, acc.id(), coffee.id(), null,
                new BigDecimal("-30.00"), null, inMonth, "latte", null, null));
        tools.addTransaction(new AddTransactionInput(
                hh, acc.id(), coffee.id(), null,
                new BigDecimal("-20.00"), null, inMonth, "espresso", null, null));

        MatviewRefreshResult result = tools.refreshMatviews();
        assertThat(result.refreshedAt()).isNotNull();
        assertThat(result.refreshed()).containsExactlyInAnyOrder(
                "finance.fin_mv_account_balance", "finance.fin_mv_monthly_by_category");

        // fin_mv_account_balance = opening (100) + sum(-30, -20) = 50.
        BigDecimal balance = jdbc.queryForObject(
                "SELECT balance FROM finance.fin_mv_account_balance WHERE account_id = ?",
                BigDecimal.class, acc.id());
        assertThat(balance).isEqualByComparingTo("50.00");

        // fin_mv_monthly_by_category: one row for the March/coffee/EUR slot,
        // spent = 50 (magnitude of outflow), tx_count = 2.
        BigDecimal spent = jdbc.queryForObject("""
                SELECT spent FROM finance.fin_mv_monthly_by_category
                WHERE household_id = ? AND category_id = ? AND month = DATE '2026-03-01'
                """, BigDecimal.class, hh, coffee.id());
        assertThat(spent).isEqualByComparingTo("50.00");
        Long txCount = jdbc.queryForObject("""
                SELECT tx_count FROM finance.fin_mv_monthly_by_category
                WHERE household_id = ? AND category_id = ? AND month = DATE '2026-03-01'
                """, Long.class, hh, coffee.id());
        assertThat(txCount).isEqualTo(2L);

        // A subsequent write is invisible until the next refresh (proves the
        // matview is a snapshot, not a live view).
        tools.addTransaction(new AddTransactionInput(
                hh, acc.id(), coffee.id(), null,
                new BigDecimal("-10.00"), null, inMonth, "flat white", null, null));
        BigDecimal staleBalance = jdbc.queryForObject(
                "SELECT balance FROM finance.fin_mv_account_balance WHERE account_id = ?",
                BigDecimal.class, acc.id());
        assertThat(staleBalance).isEqualByComparingTo("50.00");

        tools.refreshMatviews();
        BigDecimal freshBalance = jdbc.queryForObject(
                "SELECT balance FROM finance.fin_mv_account_balance WHERE account_id = ?",
                BigDecimal.class, acc.id());
        assertThat(freshBalance).isEqualByComparingTo("40.00");
    }

    @Test
    void listScopedToHouseholdNoCrossLeak() {
        FinAccountDto mine = tools.upsertAccount(new UpsertAccountInput(
                null, householdId, null, "Scoped-test", "card", "EUR",
                BigDecimal.ZERO, null));
        tools.addTransaction(new AddTransactionInput(
                householdId, mine.id(), null, null,
                new BigDecimal("-1.00"), null, Instant.now(), "scoped", null, null));

        // Other household sees nothing of mine.
        List<FinTransactionDto> other = tools.listTransactions(new ListTransactionsInput(
                otherHouseholdId, null, null, null, null, null));
        assertThat(other).noneMatch(t -> "scoped".equals(t.note()));
    }
}
