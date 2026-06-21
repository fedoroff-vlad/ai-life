package dev.fedorov.ailife.contracts.market;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A latest market quote for one symbol, returned by the {@code mcp-market-data} capability's
 * {@code quote} tool. {@code price} is the most recent close/last (null when the source has no
 * data for the symbol — e.g. Stooq's {@code N/D}); {@code asOf} is the source timestamp
 * ({@code "YYYY-MM-DD HH:MM:SS"}, or just the date) when present; {@code open}/{@code high}/
 * {@code low}/{@code volume} are the day's figures when the source yields them. The capability
 * returns numbers only — deciding anything (a buy/hold/sell *idea*, never an order) is the calling
 * agent's advisory-only skill, not this tool's job. Absent fields stay null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Quote(
        String symbol,
        Double price,
        String asOf,
        Double open,
        Double high,
        Double low,
        Long volume) {
}
