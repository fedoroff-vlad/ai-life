package dev.fedorov.ailife.mcp.finance.tools;

import dev.fedorov.ailife.contracts.finance.AddTransactionInput;
import dev.fedorov.ailife.contracts.finance.BalanceResult;
import dev.fedorov.ailife.contracts.finance.BudgetStatusResult;
import dev.fedorov.ailife.contracts.finance.CsvExportResult;
import dev.fedorov.ailife.contracts.finance.ExportCsvInput;
import dev.fedorov.ailife.contracts.finance.FinAccountDto;
import dev.fedorov.ailife.contracts.finance.FinBudgetDto;
import dev.fedorov.ailife.contracts.finance.FinCategoryDto;
import dev.fedorov.ailife.contracts.finance.FinRecurringDto;
import dev.fedorov.ailife.contracts.finance.FinTransactionDto;
import dev.fedorov.ailife.contracts.finance.GiftBudgetResult;
import dev.fedorov.ailife.contracts.finance.GiftBudgetRuleDto;
import dev.fedorov.ailife.contracts.finance.ListTransactionsInput;
import dev.fedorov.ailife.contracts.finance.MatviewRefreshResult;
import dev.fedorov.ailife.contracts.finance.SetBudgetInput;
import dev.fedorov.ailife.contracts.finance.SetGiftBudgetRuleInput;
import dev.fedorov.ailife.contracts.finance.SpendingByCategoryInput;
import dev.fedorov.ailife.contracts.finance.SpendingByCategoryRow;
import dev.fedorov.ailife.contracts.finance.UpdateTransactionInput;
import dev.fedorov.ailife.contracts.finance.UpsertAccountInput;
import dev.fedorov.ailife.contracts.finance.UpsertCategoryInput;
import dev.fedorov.ailife.contracts.finance.UpsertRecurringInput;
import dev.fedorov.ailife.mcp.finance.domain.FinAccount;
import dev.fedorov.ailife.mcp.finance.domain.FinAccountRepository;
import dev.fedorov.ailife.mcp.finance.domain.FinBudget;
import dev.fedorov.ailife.mcp.finance.domain.FinBudgetRepository;
import dev.fedorov.ailife.mcp.finance.domain.FinCategory;
import dev.fedorov.ailife.mcp.finance.domain.FinCategoryRepository;
import dev.fedorov.ailife.mcp.finance.domain.FinGiftBudgetRule;
import dev.fedorov.ailife.mcp.finance.domain.FinGiftBudgetRuleRepository;
import dev.fedorov.ailife.mcp.finance.domain.FinRecurring;
import dev.fedorov.ailife.mcp.finance.domain.FinRecurringRepository;
import dev.fedorov.ailife.mcp.finance.domain.FinTransaction;
import dev.fedorov.ailife.mcp.finance.domain.FinTransactionRepository;
import dev.fedorov.ailife.mcp.finance.scheduler.SchedulerClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Stage 2 opener: starter CRUD over finance.* (accounts, categories, transactions).
 * Budgets, recurring payments and matview-backed aggregates land in later PRs.
 *
 * Sign convention (plans/finance.md): expense<0, income>0. We do NOT enforce this at the
 * DB level — the agent layer owns sign discipline; this MCP stores what it's given.
 *
 * Scope rule: every tool takes a householdId and reads/writes only within that
 * household. Per-user privacy (owner_id filtering for "private" accounts) is the
 * agent layer's job.
 */
@Component
public class FinanceMcpTools {

    private static final int LIST_HARD_CAP = 200;
    /** export_csv reads more than the interactive list cap, but stays bounded. */
    private static final int EXPORT_HARD_CAP = 10_000;
    private static final Set<String> SUPPORTED_PERIODS = Set.of("month", "week", "year");

    /**
     * D2 gift-recommender envelope (Stage 4 / Track D): the household's gift
     * budget is the active monthly budget on the "Gifts" expense category. MVP
     * convention — the category name is a fixed constant matched
     * case-insensitively; D3 turns gift-budget rules into editable data.
     */
    private static final String GIFT_CATEGORY_NAME = "Gifts";
    private static final String GIFT_BUDGET_PERIOD = "month";

    /** Reporting matviews owned by 024-finance-matviews.yml, in refresh order. */
    private static final List<String> MATVIEWS = List.of(
            "finance.fin_mv_account_balance",
            "finance.fin_mv_monthly_by_category");

    private final FinAccountRepository accounts;
    private final FinCategoryRepository categories;
    private final FinTransactionRepository transactions;
    private final FinBudgetRepository budgets;
    private final FinGiftBudgetRuleRepository giftBudgetRules;
    private final FinRecurringRepository recurring;
    private final SchedulerClient scheduler;
    private final JdbcTemplate jdbc;

    public FinanceMcpTools(FinAccountRepository accounts,
                           FinCategoryRepository categories,
                           FinTransactionRepository transactions,
                           FinBudgetRepository budgets,
                           FinGiftBudgetRuleRepository giftBudgetRules,
                           FinRecurringRepository recurring,
                           SchedulerClient scheduler,
                           JdbcTemplate jdbc) {
        this.accounts = accounts;
        this.categories = categories;
        this.transactions = transactions;
        this.budgets = budgets;
        this.giftBudgetRules = giftBudgetRules;
        this.recurring = recurring;
        this.scheduler = scheduler;
        this.jdbc = jdbc;
    }

    @Tool(description = """
            Create or update a finance account (card, cash, deposit, credit). If `id` is
            supplied an existing row is updated in place; otherwise a new row is created
            with a fresh UUID. `type` must be one of card|cash|deposit|credit. `currency`
            is an ISO-4217 code. Set `ownerId` for a private account (only one household
            member sees it); leave null for a shared household account.
            """)
    @Transactional
    public FinAccountDto upsertAccount(UpsertAccountInput input) {
        requireField(input.householdId(), "householdId");
        requireField(input.name(), "name");
        requireField(input.type(), "type");
        requireField(input.currency(), "currency");
        FinAccount entity = input.id() == null
                ? new FinAccount(UUID.randomUUID(), input.householdId(), input.ownerId(),
                        input.name(), input.type(), input.currency(), input.openingBalance())
                : accounts.findById(input.id()).orElseThrow(
                        () -> new IllegalArgumentException("Account not found: " + input.id()));
        if (input.id() != null) {
            entity.setOwnerId(input.ownerId());
            entity.setName(input.name());
            entity.setType(input.type());
            entity.setCurrency(input.currency());
            if (input.openingBalance() != null) entity.setOpeningBalance(input.openingBalance());
        }
        if (input.archived() != null) entity.setArchived(input.archived());
        return accounts.save(entity).toDto();
    }

    @Tool(description = """
            List all finance accounts in a household, ordered by name. Includes archived
            accounts — callers that want only active accounts should filter on the
            `archived` field.
            """)
    @Transactional(readOnly = true)
    public List<FinAccountDto> listAccounts(UUID householdId) {
        return accounts.findByHouseholdIdOrderByName(householdId).stream()
                .map(FinAccount::toDto)
                .toList();
    }

    @Tool(description = """
            Create or update a finance category. Categories are scoped to a household and
            unique on (household, name, kind). `kind` must be one of
            income|expense|transfer. Supply `parentId` to nest under another category
            (single-level nesting is plenty for budgeting). If `id` is supplied an
            existing row is updated; otherwise a new row is created.
            """)
    @Transactional
    public FinCategoryDto upsertCategory(UpsertCategoryInput input) {
        requireField(input.householdId(), "householdId");
        requireField(input.name(), "name");
        requireField(input.kind(), "kind");
        FinCategory entity = input.id() == null
                ? new FinCategory(UUID.randomUUID(), input.householdId(), input.parentId(),
                        input.name(), input.kind(), input.icon())
                : categories.findById(input.id()).orElseThrow(
                        () -> new IllegalArgumentException("Category not found: " + input.id()));
        if (input.id() != null) {
            entity.setParentId(input.parentId());
            entity.setName(input.name());
            entity.setKind(input.kind());
            entity.setIcon(input.icon());
        }
        return categories.save(entity).toDto();
    }

    @Tool(description = """
            List all finance categories in a household, ordered by name.
            """)
    @Transactional(readOnly = true)
    public List<FinCategoryDto> listCategories(UUID householdId) {
        return categories.findByHouseholdIdOrderByName(householdId).stream()
                .map(FinCategory::toDto)
                .toList();
    }

    @Tool(description = """
            Record a single transaction against an account. Sign convention:
            expense<0, income>0. `ts` is the transaction's effective time (when the
            money moved, not when the row was written). If `currency` is omitted the
            account's currency is used. `source` defaults to "manual"; pass "telegram",
            "moneypro_import" etc. to track provenance. `externalRef` lets re-imports
            stay idempotent — combined with `(household, source)` it's unique.
            When `categoryId` is omitted AND `source` is not "moneypro_import" the
            row triggers a one-shot `transaction.uncategorised` wake so the
            categorizer skill can suggest a category for the user.
            """)
    @Transactional
    public FinTransactionDto addTransaction(AddTransactionInput input) {
        requireField(input.householdId(), "householdId");
        requireField(input.accountId(), "accountId");
        requireField(input.amount(), "amount");
        FinAccount account = accounts.findById(input.accountId()).orElseThrow(
                () -> new IllegalArgumentException("Account not found: " + input.accountId()));
        if (!account.getHouseholdId().equals(input.householdId())) {
            throw new IllegalArgumentException(
                    "Account does not belong to household: " + input.accountId());
        }
        String currency = (input.currency() == null || input.currency().isBlank())
                ? account.getCurrency() : input.currency();
        Instant ts = input.ts() == null ? Instant.now() : input.ts();
        String source = (input.source() == null || input.source().isBlank())
                ? "manual" : input.source();
        FinTransaction entity = new FinTransaction(
                UUID.randomUUID(),
                input.householdId(),
                input.accountId(),
                input.categoryId(),
                input.ownerId(),
                input.amount(),
                currency,
                ts,
                input.note(),
                source,
                input.externalRef());
        FinTransaction saved = transactions.save(entity);

        // Trigger categorisation suggestion when the row landed uncategorised.
        // We deliberately skip moneypro_import: bulk imports would flood the
        // user with N suggestions; the categoriser is for the one-tap "you just
        // bought coffee for 4 EUR" path.
        if (input.categoryId() == null && !"moneypro_import".equals(source)) {
            scheduler.registerTransactionUncategorisedOneshot(
                    input.householdId(), saved.getId(), Instant.now());
        }
        return saved.toDto();
    }

    @Tool(description = """
            List transactions in a household, newest first. All filters are optional:
            `accountId`, `categoryId`, `from` (inclusive), `to` (exclusive). The
            `limit` is capped at 200 to keep responses bounded; default 50.
            """)
    @Transactional(readOnly = true)
    public List<FinTransactionDto> listTransactions(ListTransactionsInput input) {
        requireField(input.householdId(), "householdId");
        int limit = input.limit() == null ? 50 : Math.min(input.limit(), LIST_HARD_CAP);
        return transactions.filter(
                        input.householdId(),
                        input.accountId(),
                        input.categoryId(),
                        input.from(),
                        input.to(),
                        limit).stream()
                .map(FinTransaction::toDto)
                .toList();
    }

    @Tool(description = """
            Compute the current balance of an account = opening_balance + sum of all
            transactions (sign-aware). Returns the account's currency along with the
            balance so callers can render it without a second lookup.
            """)
    @Transactional(readOnly = true)
    public BalanceResult getBalance(UUID accountId) {
        FinAccount account = accounts.findById(accountId).orElseThrow(
                () -> new IllegalArgumentException("Account not found: " + accountId));
        BigDecimal sum = transactions.sumAmountByAccountId(accountId);
        if (sum == null) sum = BigDecimal.ZERO;
        return new BalanceResult(accountId, account.getCurrency(),
                account.getOpeningBalance().add(sum));
    }

    @Tool(description = """
            Update an existing transaction. `id` is required; every other field is
            optional and applied only when non-null (null = leave unchanged), so
            this tool can re-categorise or fix an amount but cannot clear an
            already-set field. Provenance (household, source, externalRef) and
            createdAt are immutable. Moving the row to a different `accountId` is
            allowed only within the same household. Sign convention is unchanged:
            expense<0, income>0.
            """)
    @Transactional
    public FinTransactionDto updateTransaction(UpdateTransactionInput input) {
        requireField(input.id(), "id");
        FinTransaction entity = transactions.findById(input.id()).orElseThrow(
                () -> new IllegalArgumentException("Transaction not found: " + input.id()));
        if (input.accountId() != null && !input.accountId().equals(entity.getAccountId())) {
            FinAccount target = accounts.findById(input.accountId()).orElseThrow(
                    () -> new IllegalArgumentException("Account not found: " + input.accountId()));
            if (!target.getHouseholdId().equals(entity.getHouseholdId())) {
                throw new IllegalArgumentException(
                        "Account does not belong to household: " + input.accountId());
            }
            entity.setAccountId(input.accountId());
        }
        if (input.categoryId() != null) entity.setCategoryId(input.categoryId());
        if (input.ownerId() != null) entity.setOwnerId(input.ownerId());
        if (input.amount() != null) entity.setAmount(input.amount());
        if (input.currency() != null && !input.currency().isBlank()) {
            entity.setCurrency(input.currency());
        }
        if (input.ts() != null) entity.setTs(input.ts());
        if (input.note() != null) entity.setNote(input.note());
        return transactions.save(entity).toDto();
    }

    @Tool(description = """
            Delete a transaction by id and return the deleted row so the caller can
            confirm or offer an undo. Throws if the id does not exist. The agent
            layer is responsible for confirming destructive actions with the user
            before calling this.
            """)
    @Transactional
    public FinTransactionDto deleteTransaction(UUID id) {
        requireField(id, "id");
        FinTransaction entity = transactions.findById(id).orElseThrow(
                () -> new IllegalArgumentException("Transaction not found: " + id));
        FinTransactionDto deleted = entity.toDto();
        transactions.delete(entity);
        return deleted;
    }

    @Tool(description = """
            Export transactions as a CSV document for the user to download/keep.
            Same optional filters as list_transactions (`accountId`, `categoryId`,
            `from` inclusive, `to` exclusive) but ordered oldest-first (ledger
            style) and bounded by a higher cap (10000 rows). Columns: date,
            amount, currency, category, account, note, source. Account and
            category names are resolved (not UUIDs). Returns the CSV text, the
            data-row count, and a `truncated` flag set when the cap was hit.
            """)
    @Transactional(readOnly = true)
    public CsvExportResult exportCsv(ExportCsvInput input) {
        requireField(input.householdId(), "householdId");
        // Pull one extra row to detect truncation without a second count query.
        List<FinTransaction> rows = transactions.filter(
                input.householdId(), input.accountId(), input.categoryId(),
                input.from(), input.to(), EXPORT_HARD_CAP + 1);
        boolean truncated = rows.size() > EXPORT_HARD_CAP;
        if (truncated) {
            rows = rows.subList(0, EXPORT_HARD_CAP);
        }
        // Resolve account / category names once for the whole export.
        Map<UUID, String> accountNames = accounts.findByHouseholdIdOrderByName(input.householdId())
                .stream().collect(Collectors.toMap(FinAccount::getId, FinAccount::getName));
        Map<UUID, String> categoryNames = categories.findByHouseholdIdOrderByName(input.householdId())
                .stream().collect(Collectors.toMap(FinCategory::getId, FinCategory::getName));

        // filter() returns newest-first; a ledger export reads oldest-first.
        List<FinTransaction> ordered = rows.stream()
                .sorted(Comparator.comparing(FinTransaction::getTs))
                .toList();

        StringBuilder sb = new StringBuilder("date,amount,currency,category,account,note,source\n");
        for (FinTransaction t : ordered) {
            String category = t.getCategoryId() == null ? "" : categoryNames.getOrDefault(t.getCategoryId(), "");
            String account = accountNames.getOrDefault(t.getAccountId(), "");
            sb.append(csvField(t.getTs().toString())).append(',')
                    .append(csvField(t.getAmount().toPlainString())).append(',')
                    .append(csvField(t.getCurrency())).append(',')
                    .append(csvField(category)).append(',')
                    .append(csvField(account)).append(',')
                    .append(csvField(t.getNote())).append(',')
                    .append(csvField(t.getSource())).append('\n');
        }
        return new CsvExportResult(sb.toString(), ordered.size(), truncated);
    }

    /** RFC-4180 field escaping: quote when the value holds a comma, quote, CR or LF. */
    private static String csvField(String value) {
        if (value == null) return "";
        boolean needsQuote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needsQuote) return value;
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    @Tool(description = """
            Upsert the currently-active budget for a (household, category, period).
            `period` ∈ month|week|year. If a row with valid_to IS NULL already
            exists for the same key, it is closed (valid_to = now()) and a new
            active row is inserted so historical budgets stay queryable. The
            category must belong to the same household; cross-household input is
            rejected.
            """)
    @Transactional
    public FinBudgetDto setBudget(SetBudgetInput input) {
        requireField(input.householdId(), "householdId");
        requireField(input.categoryId(), "categoryId");
        requireField(input.period(), "period");
        requireField(input.limitAmount(), "limitAmount");
        requireField(input.currency(), "currency");
        String period = input.period().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_PERIODS.contains(period)) {
            throw new IllegalArgumentException(
                    "Unsupported period: " + input.period() + " (expected month|week|year)");
        }
        FinCategory category = categories.findById(input.categoryId()).orElseThrow(
                () -> new IllegalArgumentException("Category not found: " + input.categoryId()));
        if (!category.getHouseholdId().equals(input.householdId())) {
            throw new IllegalArgumentException(
                    "Category does not belong to household: " + input.categoryId());
        }
        Instant now = Instant.now();
        // Close the prior active row first. saveAndFlush so the UPDATE
        // clearing the partial unique slot executes before the INSERT below —
        // otherwise Hibernate may reorder and uq_fin_budget_active fires.
        UUID oldScheduleId = budgets.findActive(input.householdId(), input.categoryId(), period)
                .map(active -> {
                    active.setValidTo(now);
                    budgets.saveAndFlush(active);
                    return active.getScheduleId();
                }).orElse(null);

        FinBudget fresh = new FinBudget(UUID.randomUUID(), input.householdId(), input.categoryId(),
                period, input.limitAmount(), input.currency(), now);
        // Register the new recurring schedule BEFORE persisting — we want the
        // schedule_id on the saved row so the next set_budget knows what to
        // delete. A scheduler outage returns null and we save the row
        // anyway; a reconciliation pass can wire the cron later.
        UUID newScheduleId = scheduler.register(input.householdId(), input.categoryId(), period);
        fresh.setScheduleId(newScheduleId);
        FinBudgetDto saved = budgets.save(fresh).toDto();

        // Delete the old schedule after the new one is in place so a flaky
        // scheduler can't leave us cron-less.
        if (oldScheduleId != null) {
            scheduler.delete(oldScheduleId);
        }
        return saved;
    }

    @Tool(description = """
            Status of the active budget for a (household, category, period) over
            the current period window. Returns the budget limit, spending so far
            (signed so a refund reduces it), the [periodFrom, periodTo) window in
            UTC, and the ratio = spent / limit. 404-style if no active budget
            exists. Period boundaries are UTC: month = first day of current
            month, week = Monday 00:00 of current week, year = Jan 1.
            """)
    @Transactional(readOnly = true)
    public BudgetStatusResult getBudgetStatus(UUID householdId, UUID categoryId, String period) {
        requireField(householdId, "householdId");
        requireField(categoryId, "categoryId");
        requireField(period, "period");
        String p = period.toLowerCase(Locale.ROOT);
        if (!SUPPORTED_PERIODS.contains(p)) {
            throw new IllegalArgumentException(
                    "Unsupported period: " + period + " (expected month|week|year)");
        }
        FinBudget budget = budgets.findActive(householdId, categoryId, p).orElseThrow(
                () -> new IllegalArgumentException(
                        "No active budget for category " + categoryId + " / period " + p));
        FinCategory category = categories.findById(categoryId).orElseThrow(
                () -> new IllegalArgumentException("Category not found: " + categoryId));
        Instant[] window = periodWindow(p, Instant.now());
        BigDecimal signedSum = transactions.sumAmountByCategoryInWindow(
                householdId, categoryId, window[0], window[1]);
        if (signedSum == null) signedSum = BigDecimal.ZERO;
        // Sign convention: expense<0, income>0. "spent" is the magnitude of net
        // outflow — refunds (positive on expense) reduce it; income categories
        // mirror this via absolute value so the field is always non-negative.
        BigDecimal spent = signedSum.abs();
        BigDecimal ratio = budget.getLimitAmount().signum() == 0
                ? null
                : spent.divide(budget.getLimitAmount(), 4, RoundingMode.HALF_UP);
        return new BudgetStatusResult(householdId, categoryId, category.getName(), p,
                budget.getLimitAmount(), spent, budget.getCurrency(),
                window[0], window[1], ratio);
    }

    /**
     * The household's gift-spending envelope for the budget-aware
     * gift-recommender flow (Stage 4 / Track D). Composes the existing
     * category lookup + {@link #getBudgetStatus} read over the household's
     * "Gifts" expense category for the current month — no new persistence and
     * not an MCP tool (this is an internal read for the coordinator, exposed
     * via {@code GET /internal/gift-budget}). Throws
     * {@link IllegalArgumentException} when there is no Gifts category or no
     * active monthly budget on it, which the passthrough maps to 404.
     */
    @Transactional(readOnly = true)
    public GiftBudgetResult getGiftBudget(UUID householdId) {
        requireField(householdId, "householdId");
        FinCategory gifts = categories.findByHouseholdIdOrderByName(householdId).stream()
                .filter(c -> GIFT_CATEGORY_NAME.equalsIgnoreCase(c.getName())
                        && "expense".equalsIgnoreCase(c.getKind()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No '" + GIFT_CATEGORY_NAME + "' expense category for household " + householdId));
        BudgetStatusResult status = getBudgetStatus(householdId, gifts.getId(), GIFT_BUDGET_PERIOD);
        BigDecimal remaining = status.limitAmount().subtract(status.spent());
        return new GiftBudgetResult(status.limitAmount(), status.currency(), remaining);
    }

    @Tool(description = """
            Set (upsert) how much the household budgets for a gift to someone of a
            given relationship tier — e.g. "родителям 20000 на подарок" →
            relationship "parent", amount 20000. `relationship` is a free-text tier
            label matching a person's relationship (parent, friend, sibling,
            colleague, …); the rule is keyed by (household, relationship)
            case-insensitively, so setting the same tier again updates it in place.
            These are editable preferences the gift-recommender reads to size a
            person's gift budget by their relationship tier.
            """)
    @Transactional
    public GiftBudgetRuleDto setGiftBudgetRule(SetGiftBudgetRuleInput input) {
        requireField(input.householdId(), "householdId");
        requireField(input.relationship(), "relationship");
        requireField(input.amount(), "amount");
        requireField(input.currency(), "currency");
        String relationship = input.relationship().trim();
        // Tier identity is case-insensitive; on update we keep the originally
        // stored label casing and only change the amount/currency (deterministic,
        // independent of the DB's ORDER BY collation).
        FinGiftBudgetRule rule = giftBudgetRules
                .findByHouseholdIdAndRelationshipIgnoreCase(input.householdId(), relationship)
                .orElseGet(() -> new FinGiftBudgetRule(UUID.randomUUID(),
                        input.householdId(), relationship, input.amount(), input.currency()));
        rule.setAmount(input.amount());
        rule.setCurrency(input.currency());
        return giftBudgetRules.save(rule).toDto();
    }

    @Tool(description = """
            List the household's relationship-tiered gift-budget rules, ordered by
            relationship. Each rule is the budget for a gift to someone of that
            relationship tier.
            """)
    @Transactional(readOnly = true)
    public List<GiftBudgetRuleDto> listGiftBudgetRules(UUID householdId) {
        requireField(householdId, "householdId");
        return giftBudgetRules.findByHouseholdIdOrderByRelationship(householdId).stream()
                .map(FinGiftBudgetRule::toDto)
                .toList();
    }

    @Tool(description = """
            Aggregate spending grouped by category over [from, to). `kind`
            filters categories (default `expense`); pass null to include every
            kind. Returns rows ordered by absolute spend descending; one row per
            (category, currency) pair — a multi-currency category produces
            multiple rows. `spent` is non-negative; `txCount` is the number of
            transactions in the row.
            """)
    @Transactional(readOnly = true)
    public List<SpendingByCategoryRow> spendingByCategory(SpendingByCategoryInput input) {
        requireField(input.householdId(), "householdId");
        requireField(input.from(), "from");
        requireField(input.to(), "to");
        if (!input.to().isAfter(input.from())) {
            throw new IllegalArgumentException("`to` must be strictly after `from`");
        }
        String kind = input.kind() == null ? "expense"
                : (input.kind().isBlank() ? null : input.kind().toLowerCase(Locale.ROOT));
        return transactions.spendingByCategory(input.householdId(), input.from(), input.to(), kind)
                .stream()
                .map(row -> new SpendingByCategoryRow(
                        (UUID) row[0],
                        (String) row[1],
                        (String) row[2],
                        ((BigDecimal) row[3]).abs(),
                        ((Number) row[4]).longValue()))
                .toList();
    }

    @Tool(description = """
            Create or update a recurring payment/income template. Each row is
            one "rent" / "spotify" / "salary" definition — the actual
            transactions are still booked elsewhere; this tool lets the agent
            track cadence and suggest the next instance. `cron` is a Spring
            CronExpression (UTC); `next_due` is recomputed on every upsert so
            a changed cadence takes effect immediately. Sign convention follows
            transactions: expense<0, income>0 — the tool stores what's given.
            Rejects accounts from a different household than `householdId`.
            `autoRemind` is a column-level flag in this PR; scheduler-side
            auto-registration is a follow-up.
            """)
    @Transactional
    public FinRecurringDto upsertRecurring(UpsertRecurringInput input) {
        requireField(input.householdId(), "householdId");
        requireField(input.accountId(), "accountId");
        requireField(input.name(), "name");
        requireField(input.amount(), "amount");
        requireField(input.cron(), "cron");
        FinAccount account = accounts.findById(input.accountId()).orElseThrow(
                () -> new IllegalArgumentException("Account not found: " + input.accountId()));
        if (!account.getHouseholdId().equals(input.householdId())) {
            throw new IllegalArgumentException(
                    "Account does not belong to household: " + input.accountId());
        }
        if (!CronExpression.isValidExpression(input.cron())) {
            throw new IllegalArgumentException("Invalid cron expression: " + input.cron());
        }
        Instant nextDue = nextDueFromCron(input.cron(), Instant.now());
        String currency = (input.currency() == null || input.currency().isBlank())
                ? account.getCurrency() : input.currency();
        boolean autoRemind = input.autoRemind() != null && input.autoRemind();

        FinRecurring entity;
        String oldCron;
        UUID oldScheduleId;
        if (input.id() == null) {
            oldCron = null;
            oldScheduleId = null;
            entity = new FinRecurring(UUID.randomUUID(), input.householdId(), input.ownerId(),
                    input.accountId(), input.categoryId(), input.name(), input.amount(), currency,
                    input.cron(), nextDue, input.note(), autoRemind);
        } else {
            entity = recurring.findById(input.id()).orElseThrow(
                    () -> new IllegalArgumentException("Recurring not found: " + input.id()));
            oldCron = entity.getCron();
            oldScheduleId = entity.getScheduleId();
            entity.setOwnerId(input.ownerId());
            entity.setAccountId(input.accountId());
            entity.setCategoryId(input.categoryId());
            entity.setName(input.name());
            entity.setAmount(input.amount());
            entity.setCurrency(currency);
            entity.setCron(input.cron());
            entity.setNextDue(nextDue);
            entity.setNote(input.note());
            entity.setAutoRemind(autoRemind);
        }

        // Lifecycle reconciliation:
        //   autoRemind=true  + no schedule        → register
        //   autoRemind=false + had schedule       → delete + null
        //   autoRemind=true  + had schedule + cron changed → register new + delete old
        //   otherwise                                                  → no-op
        // Register-before-delete ordering matches PR27b's flake-safe pattern:
        // if the new register fails (returns null) we'd rather lose the cron
        // entirely than keep an old one pointing at stale numbers.
        UUID newScheduleId = reconcileRecurringSchedule(
                entity, oldScheduleId, oldCron, autoRemind);
        entity.setScheduleId(newScheduleId);

        return recurring.save(entity).toDto();
    }

    private UUID reconcileRecurringSchedule(FinRecurring row, UUID oldScheduleId,
                                            String oldCron, boolean autoRemind) {
        boolean hadSchedule = oldScheduleId != null;
        if (autoRemind && !hadSchedule) {
            return scheduler.registerRecurring(row.getHouseholdId(), row.getId(), row.getCron());
        }
        if (!autoRemind && hadSchedule) {
            scheduler.delete(oldScheduleId);
            return null;
        }
        if (autoRemind && hadSchedule && !row.getCron().equals(oldCron)) {
            UUID fresh = scheduler.registerRecurring(
                    row.getHouseholdId(), row.getId(), row.getCron());
            scheduler.delete(oldScheduleId);
            return fresh;
        }
        return oldScheduleId;
    }

    @Tool(description = """
            List recurring payment templates in a household, ordered by
            `next_due` ascending (soonest first; nulls last). Optional filters
            `accountId` / `categoryId` narrow the list. Returns every column
            including the computed `next_due` so the agent can pick which row
            to surface.
            """)
    @Transactional(readOnly = true)
    public List<FinRecurringDto> listRecurring(UUID householdId, UUID accountId, UUID categoryId) {
        requireField(householdId, "householdId");
        return recurring.filter(householdId, accountId, categoryId).stream()
                .map(FinRecurring::toDto)
                .toList();
    }

    @Tool(description = """
            Recompute the finance reporting matviews (fin_mv_account_balance,
            fin_mv_monthly_by_category) so the dashboard layer (Metabase/Grafana,
            which read them directly) sees current numbers. Run after a batch of
            writes (e.g. a Money Pro import) or on a schedule. Returns the
            refreshed matview names and the completion time. Not CONCURRENTLY —
            a brief refresh lock is acceptable for personal-finance volumes.
            """)
    @Transactional
    public MatviewRefreshResult refreshMatviews() {
        for (String mv : MATVIEWS) {
            jdbc.execute("REFRESH MATERIALIZED VIEW " + mv);
        }
        return new MatviewRefreshResult(MATVIEWS, Instant.now());
    }

    /**
     * Spring CronExpression's {@code next} works on {@link LocalDateTime}; we
     * anchor on UTC so the stored {@code next_due} is comparable across
     * household time zones (agent does any local-time presentation).
     *
     * <p>Public so {@code InternalRecurringController#advance} can reuse the
     * same anchor logic when a successful tick recomputes {@code next_due}.
     */
    public static Instant nextDueFromCron(String cron, Instant from) {
        CronExpression expr = CronExpression.parse(cron);
        LocalDateTime anchor = from.atOffset(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime next = expr.next(anchor);
        if (next == null) {
            throw new IllegalArgumentException("Cron expression never fires: " + cron);
        }
        return next.toInstant(ZoneOffset.UTC);
    }

    /**
     * UTC-anchored period boundaries. {@code month} → first day of the month at
     * 00:00:00Z; {@code week} → ISO Monday 00:00:00Z; {@code year} → Jan 1
     * 00:00:00Z. End is the next period's start (half-open).
     */
    static Instant[] periodWindow(String period, Instant now) {
        OffsetDateTime t = now.atOffset(ZoneOffset.UTC);
        LocalDate today = t.toLocalDate();
        LocalDate start;
        LocalDate end;
        switch (period) {
            case "month" -> {
                start = today.withDayOfMonth(1);
                end = start.plusMonths(1);
            }
            case "week" -> {
                start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                end = start.plusWeeks(1);
            }
            case "year" -> {
                start = today.withDayOfYear(1);
                end = start.plusYears(1);
            }
            default -> throw new IllegalStateException("Unsupported period: " + period);
        }
        return new Instant[]{
                start.atStartOfDay(ZoneOffset.UTC).toInstant(),
                end.atStartOfDay(ZoneOffset.UTC).toInstant()};
    }

    private static void requireField(Object value, String name) {
        if (value == null) throw new IllegalArgumentException("Missing required field: " + name);
        if (value instanceof String s && s.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + name);
        }
    }
}
