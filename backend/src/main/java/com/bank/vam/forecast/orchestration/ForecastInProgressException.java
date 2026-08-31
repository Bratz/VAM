package com.bank.vam.forecast.orchestration;

import java.util.UUID;

/**
 * Thrown when a forecast run cannot start because another run is already in
 * flight for the same corporate. Mapped to HTTP 409 CONFLICT by
 * {@code GlobalExceptionHandler}.
 */
public class ForecastInProgressException extends RuntimeException {

    private final UUID corporateId;

    public ForecastInProgressException(UUID corporateId) {
        super("A forecast run is already in progress for corporate " + corporateId
                + ". Retry once the current run completes.");
        this.corporateId = corporateId;
    }

    public UUID getCorporateId() {
        return corporateId;
    }
}
