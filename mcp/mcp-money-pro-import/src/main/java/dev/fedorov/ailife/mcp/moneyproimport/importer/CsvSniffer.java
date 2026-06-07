package dev.fedorov.ailife.mcp.moneyproimport.importer;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Encoding + delimiter sniffing for Money Pro CSV exports.
 *
 * <p>Encoding: Money Pro on iOS/macOS exports UTF-8 (with or without BOM), but
 * Russian Money Pro builds historically shipped Windows-1251 CSVs. Strategy:
 * decode strictly as UTF-8; on the first malformed byte fall back to CP-1251.
 *
 * <p>Delimiter: comma vs semicolon by majority vote on the header line (RU/EU
 * locale exports use {@code ;}; English ones use {@code ,}). Tab is recognised
 * if it dominates both.
 */
final class CsvSniffer {

    static final Charset CP1251 = Charset.forName("windows-1251");

    private CsvSniffer() {
    }

    /**
     * Decode bytes to a string. If {@code forced} is non-null it wins; otherwise
     * UTF-8 is tried strict and CP-1251 is the fallback. A leading UTF-8 BOM is
     * stripped.
     */
    static String decode(byte[] bytes, String forced) {
        Charset charset = resolveForced(forced);
        if (charset != null) {
            return stripBom(new String(bytes, charset));
        }
        try {
            String utf8 = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return stripBom(utf8);
        } catch (CharacterCodingException e) {
            return stripBom(new String(bytes, CP1251));
        }
    }

    private static Charset resolveForced(String forced) {
        if (forced == null || forced.isBlank()) return null;
        try {
            return Charset.forName(forced);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Unknown encoding: " + forced, e);
        }
    }

    private static String stripBom(String text) {
        return (!text.isEmpty() && text.charAt(0) == '﻿') ? text.substring(1) : text;
    }

    /**
     * Pick the delimiter by counting occurrences on the header line. Tie-breaker
     * order: semicolon → comma → tab.
     */
    static char detectDelimiter(String headerLine) {
        int semis = count(headerLine, ';');
        int commas = count(headerLine, ',');
        int tabs = count(headerLine, '\t');
        if (semis == 0 && commas == 0 && tabs == 0) return ',';
        if (tabs > semis && tabs > commas) return '\t';
        return semis >= commas ? ';' : ',';
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }
}
