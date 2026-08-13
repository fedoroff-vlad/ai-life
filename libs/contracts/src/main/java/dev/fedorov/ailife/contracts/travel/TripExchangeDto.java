package dev.fedorov.ailife.contracts.travel;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An on-site currency swap during a trip (plans/travel.md §Trip wallet, #437) — one paired op that is
 * both an <b>outflow</b> of {@code fromCurrency} and an <b>inflow</b> of {@code toCurrency}, so the ₽
 * tally stays honest (recording only a bare funding of the acquired currency would double-count the
 * source). No stored rate: the effective swap rate is {@code toAmount/fromAmount}, and EX-b derives the
 * acquired currency's ₽ home-rate from the source spend ({@code fromAmount × fromHomeRate / toAmount}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TripExchangeDto(
        UUID id,
        UUID tripId,
        String fromCurrency,
        BigDecimal fromAmount,
        String toCurrency,
        BigDecimal toAmount,
        Instant exchangedAt,
        String note) {
}
