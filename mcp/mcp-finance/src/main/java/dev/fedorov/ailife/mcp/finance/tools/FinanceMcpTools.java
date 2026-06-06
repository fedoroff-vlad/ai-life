package dev.fedorov.ailife.mcp.finance.tools;

import dev.fedorov.ailife.contracts.finance.AddTransactionInput;
import dev.fedorov.ailife.contracts.finance.BalanceResult;
import dev.fedorov.ailife.contracts.finance.FinAccountDto;
import dev.fedorov.ailife.contracts.finance.FinCategoryDto;
import dev.fedorov.ailife.contracts.finance.FinTransactionDto;
import dev.fedorov.ailife.contracts.finance.ListTransactionsInput;
import dev.fedorov.ailife.contracts.finance.UpsertAccountInput;
import dev.fedorov.ailife.contracts.finance.UpsertCategoryInput;
import dev.fedorov.ailife.mcp.finance.domain.FinAccount;
import dev.fedorov.ailife.mcp.finance.domain.FinAccountRepository;
import dev.fedorov.ailife.mcp.finance.domain.FinCategory;
import dev.fedorov.ailife.mcp.finance.domain.FinCategoryRepository;
import dev.fedorov.ailife.mcp.finance.domain.FinTransaction;
import dev.fedorov.ailife.mcp.finance.domain.FinTransactionRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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

    private final FinAccountRepository accounts;
    private final FinCategoryRepository categories;
    private final FinTransactionRepository transactions;

    public FinanceMcpTools(FinAccountRepository accounts,
                           FinCategoryRepository categories,
                           FinTransactionRepository transactions) {
        this.accounts = accounts;
        this.categories = categories;
        this.transactions = transactions;
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
                input.source(),
                input.externalRef());
        return transactions.save(entity).toDto();
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

    private static void requireField(Object value, String name) {
        if (value == null) throw new IllegalArgumentException("Missing required field: " + name);
        if (value instanceof String s && s.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + name);
        }
    }
}
