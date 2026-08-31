package com.bank.vam.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

/**
 * Exception thrown when Settlement VA resolution fails.
 *
 * This exception provides user-friendly error messages with context about:
 * - Why the Settlement VA was not found
 * - What exception was raised (for tracking)
 * - What action the user/operations team should take
 *
 * HTTP Status: 422 UNPROCESSABLE_ENTITY (not 500)
 * - The request was valid, but the transaction cannot be processed
 * - Due to missing configuration (Settlement VA)
 */
@Getter
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class SettlementVaException extends RuntimeException {

    /**
     * Error codes for different Settlement VA failure scenarios.
     */
    public enum ErrorCode {
        MISSING_SETTLEMENT_VA("MISSING_SETTLEMENT_VA",
            "Settlement VA not configured. Transaction has been parked for review."),
        ENTITY_MISMATCH("ENTITY_MISMATCH",
            "Legal entity mismatch detected. Transaction has been parked for review."),
        CROSS_CURRENCY_PARTIAL("CROSS_CURRENCY_PARTIAL",
            "Cross-currency Settlement VA partially configured. Transaction has been parked."),
        HIERARCHY_NOT_FOUND("HIERARCHY_NOT_FOUND",
            "No Settlement VA found in account hierarchy."),
        CONFIGURATION_ERROR("CONFIGURATION_ERROR",
            "Settlement VA configuration error. Please contact support.");

        private final String code;
        private final String defaultMessage;

        ErrorCode(String code, String defaultMessage) {
            this.code = code;
            this.defaultMessage = defaultMessage;
        }

        public String getCode() { return code; }
        public String getDefaultMessage() { return defaultMessage; }
    }

    private final ErrorCode errorCode;
    private final String exceptionNumber;
    private final UUID exceptionVaId;
    private final UUID programId;
    private final String currencyCode;
    private final String userMessage;

    /**
     * Create exception with full context.
     */
    public SettlementVaException(ErrorCode errorCode, String exceptionNumber,
                                  UUID exceptionVaId, UUID programId, String currencyCode,
                                  String userMessage) {
        super(userMessage);
        this.errorCode = errorCode;
        this.exceptionNumber = exceptionNumber;
        this.exceptionVaId = exceptionVaId;
        this.programId = programId;
        this.currencyCode = currencyCode;
        this.userMessage = userMessage;
    }

    /**
     * Create exception for missing Settlement VA.
     */
    public static SettlementVaException missingSettlementVa(
            String exceptionNumber, UUID exceptionVaId, UUID programId, String currencyCode) {
        String message = String.format(
            "Settlement VA is not configured for this program and currency (%s). " +
            "Your transaction has been safely parked for review. " +
            "Reference: %s. Please contact your administrator to set up the Settlement VA.",
            currencyCode, exceptionNumber);

        return new SettlementVaException(
            ErrorCode.MISSING_SETTLEMENT_VA, exceptionNumber, exceptionVaId, programId, currencyCode, message);
    }

    /**
     * Create exception for legal entity mismatch.
     */
    public static SettlementVaException entityMismatch(
            String exceptionNumber, UUID exceptionVaId, UUID programId, String currencyCode,
            String sourceEntity, String contraEntity) {
        String message = String.format(
            "This transaction involves accounts from different legal entities " +
            "(Source: %s, Destination: %s) which require separate settlement processing. " +
            "Your transaction has been parked for review. Reference: %s.",
            sourceEntity, contraEntity, exceptionNumber);

        return new SettlementVaException(
            ErrorCode.ENTITY_MISMATCH, exceptionNumber, exceptionVaId, programId, currencyCode, message);
    }

    /**
     * Create exception for cross-currency partial configuration.
     */
    public static SettlementVaException crossCurrencyPartial(
            String exceptionNumber, UUID exceptionVaId, UUID programId,
            String sourceCurrency, String destCurrency, boolean sourceConfigured, boolean destConfigured) {
        String missingCurrency = !sourceConfigured ? sourceCurrency : destCurrency;
        String message = String.format(
            "Cross-currency transfer (%s → %s) cannot proceed. " +
            "Settlement VA for %s is not configured. " +
            "Your transaction has been parked for review. Reference: %s.",
            sourceCurrency, destCurrency, missingCurrency, exceptionNumber);

        return new SettlementVaException(
            ErrorCode.CROSS_CURRENCY_PARTIAL, exceptionNumber, exceptionVaId, programId, missingCurrency, message);
    }

    /**
     * Get the error code string for API response.
     */
    public String getCode() {
        return errorCode.getCode();
    }
}
