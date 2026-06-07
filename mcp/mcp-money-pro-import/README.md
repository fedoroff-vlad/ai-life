# mcp-money-pro-import

MCP server: one-time history import of Money Pro (iOS/macOS) CSV exports into
`finance.fin_transaction`. Idempotent on re-import via the
`(household_id, source='moneypro_import', external_ref)` unique constraint that
mcp-finance's `020-finance.yml` already provisions. CRUD over the finance schema
lives in mcp-finance — this module only writes via the importer.

## Tools (MCP)

- `import_moneypro_csv(householdId, csvBase64, encoding?, accountMap, categoryMap?, fileRef?, dryRun?)`
  — decode + parse + insert.
  - **Encoding** is autodetected (UTF-8 strict, with CP-1251 fallback for older
    Russian Money Pro exports). Pass `encoding` (any JVM charset name) to override.
  - **Delimiter** is sniffed from the header line (comma / semicolon / tab).
  - **Columns required**: date, account, amount. Optional: currency, description,
    category, id. RU + EN header aliases are recognised (`Дата` / `Date`,
    `Счёт` / `Account`, `Сумма` / `Amount`, `Описание` / `Description`,
    `Категория` / `Category`, `ID` / `Идентификатор`).
  - **`accountMap`** resolves Money Pro account names (case-insensitive) to
    existing `fin_account.id` UUIDs. Every UUID in the map is verified to belong
    to `householdId` **before** any row is written — a single mismatch is fatal.
    Rows whose account is not in the map fall into the row-error list.
  - **`categoryMap`** is optional. Unmapped categories yield null `category_id`;
    a categorizer skill can back-fill later.
  - **`dryRun=true`** returns the counts an import would produce against the
    current DB state without writing rows.

## Result shape

```json
{
  "dryRun": false,
  "totalRows": 142,
  "created": 138,
  "skipped": 3,
  "errored": 1,
  "errors": [{"row": 17, "message": "Unknown account: 'Old Wallet'"}]
}
```

`errors` is capped at the first 20 problematic rows to keep the MCP response bounded.

## Idempotency

External ref strategy per row:
1. If the CSV has an `ID` / `Идентификатор` column → use it verbatim.
2. Otherwise → `sha1(date | account | amount | description)` (a stable content
   hash, so re-exporting the same Money Pro DB hits the same key).

The DB unique on `(household_id, source, external_ref)` is the actual idempotency
guard; the importer also probes existence client-side so the result's `skipped`
counter is meaningful.

## Env

| Var | Default | Purpose |
|---|---|---|
| `MCP_MONEY_PRO_IMPORT_PORT` | `8094` | HTTP port |
| `MCP_MONEY_PRO_IMPORT_DB_URL` | `jdbc:postgresql://localhost:5432/ailife` | Postgres |
| `MCP_MONEY_PRO_IMPORT_DB_USER` / `MCP_MONEY_PRO_IMPORT_DB_PASSWORD` | `ailife` | DB credentials |

## Key classes

- `McpMoneyProImportApplication`.
- `domain/FinAccount` + `FinAccountRepository` — **read-only** second JPA view over
  `finance.fin_account` (schema owned by mcp-finance's `020-finance.yml`). Used only
  for the cross-household scope check.
- `domain/FinTransaction` + `FinTransactionRepository` — write-side view. Only inserts;
  `existsByHouseholdIdAndSourceAndExternalRef` powers the idempotency probe.
- `importer/MoneyProImporter` — the transactional service. Verifies `accountMap`
  scope, then loops over rows collecting per-row errors without aborting.
- `importer/CsvSniffer` — UTF-8 strict → CP-1251 fallback decode, delimiter vote.
- `importer/CsvReader` — minimal CSV splitter (`""` escape supported; multi-line
  quoted fields are not — Money Pro doesn't produce them).
- `importer/HeaderIndex` — RU + EN header alias resolver.
- `importer/AmountParser` — rightmost of `,` / `.` is the decimal mark; the other
  is treated as a thousands separator.
- `importer/DateParser` — date-time formats first (`dd.MM.yyyy HH:mm:ss`, ISO,
  US slashes), then date-only fallbacks at UTC midnight.
- `tools/MoneyProImportMcpTools` + `tools/ToolsConfig` — Spring AI `@Tool` surface.

## Schema

No new migration — reuses `finance.fin_transaction` / `finance.fin_account` from
[020-finance.yml](../../infra/liquibase/features/020-finance.yml). The
`source='moneypro_import'` slot for `external_ref` was already provisioned by
PR21 with this importer in mind.
