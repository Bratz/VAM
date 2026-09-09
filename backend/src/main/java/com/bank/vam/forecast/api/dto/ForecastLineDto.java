package com.bank.vam.forecast.api.dto;

import com.bank.vam.forecast.domain.ForecastLine;
import com.bank.vam.forecast.domain.enums.ForecastDirection;
import com.bank.vam.forecast.domain.enums.ForecastSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Wire shape for {@link ForecastLine}.
 *
 * <p>Resolves the {@code category} association inline ({@code categoryCode}
 * + {@code categoryLabel}) so the frontend doesn't need a second round trip
 * to render a line. The mapping touches the lazy proxy, so callers must
 * invoke {@link #from(ForecastLine)} inside the JPA transaction.
 *
 * <p>Field names match {@code frontend/src/services/api.ts :: ForecastLine}
 * verbatim — keep them in sync if the shape changes.
 */
public record ForecastLineDto(
        UUID id,
        LocalDate valueDate,
        UUID entityId,
        String currency,
        String categoryCode,
        String categoryLabel,
        ForecastDirection direction,
        BigDecimal amountMid,
        ForecastSource source,
        String sourceRef,
        BigDecimal confidence
) {

    public static ForecastLineDto from(ForecastLine line) {
        return new ForecastLineDto(
                line.getId(),
                line.getValueDate(),
                line.getEntityId(),
                line.getCurrency(),
                line.getCategory() != null ? line.getCategory().getCode() : null,
                line.getCategory() != null ? line.getCategory().getLabel() : null,
                line.getCategory() != null ? line.getCategory().getDirection() : null,
                line.getAmountMid(),
                line.getSource(),
                line.getSourceRef(),
                line.getConfidence()
        );
    }
}
