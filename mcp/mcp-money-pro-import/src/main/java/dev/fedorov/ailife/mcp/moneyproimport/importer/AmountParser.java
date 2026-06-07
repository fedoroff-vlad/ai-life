package dev.fedorov.ailife.mcp.moneyproimport.importer;

import java.math.BigDecimal;

/**
 * Locale-tolerant amount parser. Money Pro exports use either dot or comma as the
 * decimal separator depending on locale; thousands separators may be spaces,
 * non-breaking spaces, apostrophes or absent. We normalise: strip all whitespace
 * and apostrophes, then — when both {@code .} and {@code ,} are present — treat
 * the rightmost as the decimal mark and discard the other as a thousands
 * separator. Sign is preserved (expense<0, income>0).
 */
final class AmountParser {

    private AmountParser() {
    }

    static BigDecimal parse(String raw) {
        if (raw == null) throw new IllegalArgumentException("amount is null");
        String s = raw.replace(" ", "").replace(" ", "").replace("'", "").trim();
        if (s.isEmpty()) throw new IllegalArgumentException("amount is blank");

        int lastComma = s.lastIndexOf(',');
        int lastDot = s.lastIndexOf('.');
        if (lastComma >= 0 && lastDot >= 0) {
            if (lastComma > lastDot) {
                s = s.replace(".", "");
                int idx = s.lastIndexOf(',');
                s = s.substring(0, idx) + "." + s.substring(idx + 1);
            } else {
                s = s.replace(",", "");
            }
        } else if (lastComma >= 0) {
            s = s.replace(',', '.');
        }
        return new BigDecimal(s);
    }
}
