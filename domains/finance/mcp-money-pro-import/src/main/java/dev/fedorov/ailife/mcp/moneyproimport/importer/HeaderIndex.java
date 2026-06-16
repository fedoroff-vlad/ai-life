package dev.fedorov.ailife.mcp.moneyproimport.importer;

import java.util.List;
import java.util.Locale;

/**
 * Resolves Money Pro CSV header positions for the columns we care about. Header
 * names are matched case-insensitively against a set of RU + EN aliases — Money
 * Pro labels the same column "Date" / "Дата" / "Description" / "Описание"
 * depending on the export's UI language.
 *
 * <p>Required columns: date, account, amount. Currency, note, category and id are
 * optional; missing columns yield index {@code -1}, callers fall back to defaults.
 */
final class HeaderIndex {

    private static final List<String> DATE = List.of("date", "дата", "datetime");
    private static final List<String> ACCOUNT = List.of("account", "счёт", "счет", "from", "from account");
    private static final List<String> CATEGORY = List.of("category", "категория");
    private static final List<String> AMOUNT = List.of("amount", "сумма", "value");
    private static final List<String> CURRENCY = List.of("currency", "валюта");
    private static final List<String> NOTE = List.of("description", "note", "memo", "описание", "примечание", "комментарий");
    private static final List<String> ID = List.of("id", "uuid", "transaction id", "идентификатор");

    final int date, account, category, amount, currency, note, id;

    private HeaderIndex(int date, int account, int category, int amount,
                        int currency, int note, int id) {
        this.date = date;
        this.account = account;
        this.category = category;
        this.amount = amount;
        this.currency = currency;
        this.note = note;
        this.id = id;
    }

    static HeaderIndex from(List<String> headers) {
        int d = find(headers, DATE);
        int a = find(headers, ACCOUNT);
        int amt = find(headers, AMOUNT);
        if (d < 0 || a < 0 || amt < 0) {
            throw new IllegalArgumentException(
                    "Missing required CSV header — need at least date, account and amount. Saw: " + headers);
        }
        return new HeaderIndex(d, a, find(headers, CATEGORY), amt,
                find(headers, CURRENCY), find(headers, NOTE), find(headers, ID));
    }

    private static int find(List<String> headers, List<String> aliases) {
        for (int i = 0; i < headers.size(); i++) {
            String h = headers.get(i).trim().toLowerCase(Locale.ROOT);
            if (aliases.contains(h)) return i;
        }
        return -1;
    }
}
