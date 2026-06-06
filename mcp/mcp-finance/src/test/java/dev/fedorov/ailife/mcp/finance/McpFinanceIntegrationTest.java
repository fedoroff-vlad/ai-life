package dev.fedorov.ailife.mcp.finance;

import dev.fedorov.ailife.contracts.finance.AddTransactionInput;
import dev.fedorov.ailife.contracts.finance.BalanceResult;
import dev.fedorov.ailife.contracts.finance.FinAccountDto;
import dev.fedorov.ailife.contracts.finance.FinCategoryDto;
import dev.fedorov.ailife.contracts.finance.FinTransactionDto;
import dev.fedorov.ailife.contracts.finance.ListTransactionsInput;
import dev.fedorov.ailife.contracts.finance.UpsertAccountInput;
import dev.fedorov.ailife.contracts.finance.UpsertCategoryInput;
import dev.fedorov.ailife.mcp.finance.tools.FinanceMcpTools;
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

@SpringBootTest
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

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    static UUID householdId;
    static UUID otherHouseholdId;

    @Autowired FinanceMcpTools tools;
    @Autowired JdbcTemplate jdbc;

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
