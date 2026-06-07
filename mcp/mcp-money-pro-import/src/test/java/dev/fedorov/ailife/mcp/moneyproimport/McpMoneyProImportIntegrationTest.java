package dev.fedorov.ailife.mcp.moneyproimport;

import dev.fedorov.ailife.contracts.finance.ImportMoneyProCsvInput;
import dev.fedorov.ailife.contracts.finance.ImportMoneyProCsvResult;
import dev.fedorov.ailife.mcp.moneyproimport.tools.MoneyProImportMcpTools;
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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the Money Pro CSV importer. Covers the four invariants the
 * importer is meant to hold:
 *   1. happy-path UTF-8 import inserts rows with source='moneypro_import',
 *      external_ref taken from the CSV's id column;
 *   2. re-running the same CSV inserts nothing — DB unique + client-side existence
 *      probe make the operation idempotent;
 *   3. dry-run returns sensible counts without touching the DB;
 *   4. Russian CP-1251 + semicolon-delimited CSV + missing id column all work,
 *      and unknown account names land in the row-error list without aborting;
 *   5. accountMap entries pointing at a different household are rejected
 *      fatally before any row is written.
 */
@SpringBootTest
@Testcontainers
class McpMoneyProImportIntegrationTest {

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
    static UUID walletId;
    static UUID cardId;
    static UUID otherHouseholdAccountId;

    @Autowired MoneyProImportMcpTools tools;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void seed(@Autowired JdbcTemplate jdbc) {
        householdId = UUID.randomUUID();
        otherHouseholdId = UUID.randomUUID();
        walletId = UUID.randomUUID();
        cardId = UUID.randomUUID();
        otherHouseholdAccountId = UUID.randomUUID();
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", householdId, "h1");
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", otherHouseholdId, "h2");
        jdbc.update("""
                INSERT INTO finance.fin_account (id, household_id, name, type, currency, opening_balance)
                VALUES (?, ?, 'Wallet', 'cash', 'EUR', 0)""", walletId, householdId);
        jdbc.update("""
                INSERT INTO finance.fin_account (id, household_id, name, type, currency, opening_balance)
                VALUES (?, ?, 'Card',   'card', 'EUR', 0)""", cardId, householdId);
        jdbc.update("""
                INSERT INTO finance.fin_account (id, household_id, name, type, currency, opening_balance)
                VALUES (?, ?, 'Foreign', 'card', 'USD', 0)""", otherHouseholdAccountId, otherHouseholdId);
    }

    private String b64(String csv, Charset charset) {
        return Base64.getEncoder().encodeToString(csv.getBytes(charset));
    }

    private long count(String source) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM finance.fin_transaction WHERE household_id = ? AND source = ?",
                Long.class, householdId, source);
        return n == null ? 0 : n;
    }

    @Test
    void happyPathInsertsThenReimportIsIdempotent() {
        String csv = """
                Date,Account,Category,Amount,Currency,Description,ID
                2026-01-15,Wallet,Food,-12.50,EUR,Lunch,mp-001
                2026-01-16,Card,Salary,3000.00,EUR,Payday,mp-002
                """;
        ImportMoneyProCsvInput in = new ImportMoneyProCsvInput(
                householdId, b64(csv, StandardCharsets.UTF_8), null,
                Map.of("Wallet", walletId, "Card", cardId), null, "test.csv", false);

        ImportMoneyProCsvResult first = tools.importMoneyproCsv(in);
        assertThat(first.totalRows()).isEqualTo(2);
        assertThat(first.created()).isEqualTo(2);
        assertThat(first.skipped()).isZero();
        assertThat(first.errored()).isZero();
        assertThat(count("moneypro_import")).isEqualTo(2);

        BigDecimal lunchAmount = jdbc.queryForObject(
                "SELECT amount FROM finance.fin_transaction WHERE external_ref = 'mp-001'",
                BigDecimal.class);
        assertThat(lunchAmount).isEqualByComparingTo("-12.50");

        // Re-import the same payload — every row already exists → skipped only.
        ImportMoneyProCsvResult second = tools.importMoneyproCsv(in);
        assertThat(second.created()).isZero();
        assertThat(second.skipped()).isEqualTo(2);
        assertThat(count("moneypro_import")).isEqualTo(2);
    }

    @Test
    void dryRunReportsCountsButWritesNothing() {
        String csv = """
                Date;Account;Amount;ID
                2026-02-01;Wallet;-3.20;dry-1
                2026-02-02;Wallet;-4.50;dry-2
                """;
        ImportMoneyProCsvInput in = new ImportMoneyProCsvInput(
                householdId, b64(csv, StandardCharsets.UTF_8), null,
                Map.of("Wallet", walletId), null, null, true);

        long before = count("moneypro_import");
        ImportMoneyProCsvResult result = tools.importMoneyproCsv(in);
        assertThat(result.dryRun()).isTrue();
        assertThat(result.totalRows()).isEqualTo(2);
        assertThat(result.created()).isEqualTo(2);
        assertThat(count("moneypro_import")).isEqualTo(before);
    }

    @Test
    void russianCp1251SemicolonsAutodetectAndUnknownAccountRowErrors() {
        String csv = """
                Дата;Счёт;Категория;Сумма;Валюта;Описание
                15.03.2026;Кошелёк;Кофе;-3,50;EUR;Эспрессо
                16.03.2026;Неизвестный;Кофе;-4,00;EUR;Бар
                """;
        ImportMoneyProCsvInput in = new ImportMoneyProCsvInput(
                householdId, b64(csv, Charset.forName("windows-1251")), null,
                Map.of("Кошелёк", walletId), null, null, false);

        ImportMoneyProCsvResult result = tools.importMoneyproCsv(in);
        assertThat(result.totalRows()).isEqualTo(2);
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.errored()).isEqualTo(1);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).message()).contains("Unknown account");
        // The single successful row uses a content-hash external_ref since there's no id column.
        BigDecimal amount = jdbc.queryForObject(
                "SELECT amount FROM finance.fin_transaction WHERE note = 'Эспрессо' AND source = 'moneypro_import'",
                BigDecimal.class);
        assertThat(amount).isEqualByComparingTo("-3.50");
    }

    @Test
    void rejectsAccountMapEntryFromOtherHousehold() {
        String csv = "Date,Account,Amount,ID\n2026-04-01,X,-1,a\n";
        ImportMoneyProCsvInput in = new ImportMoneyProCsvInput(
                householdId, b64(csv, StandardCharsets.UTF_8), null,
                Map.of("X", otherHouseholdAccountId), null, null, false);

        assertThatThrownBy(() -> tools.importMoneyproCsv(in))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different household");
    }
}
