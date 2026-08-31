package com.bank.vam.exception;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.forecast.orchestration.ForecastInProgressException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceNotFound(ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ex.getMessage(), "NOT_FOUND"));
    }

    /**
     * A request that matched no controller mapping falls through to Spring's
     * static-resource handler, which throws this. Without an explicit handler
     * the generic catch-all below converted it to HTTP 500 — masking simple
     * "no such route" mistakes (wrong path prefix, endpoint not implemented)
     * as server errors and making them needlessly hard to diagnose from the
     * browser console. Surface as a plain 404.
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(
            org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        log.warn("No route matched: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("No endpoint exists at this path: " + ex.getResourcePath(), "NOT_FOUND"));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        log.warn("Business error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ex.getMessage(), ex.getCode() != null ? ex.getCode() : "BAD_REQUEST"));
    }

    /**
     * Concurrent forecast runs for the same corporate are rejected at the
     * orchestrator / scheduler boundary. Surface as HTTP 409 CONFLICT so the
     * Forecasting page can show a "another run in flight" toast and offer a
     * retry rather than a generic error.
     */
    @ExceptionHandler(ForecastInProgressException.class)
    public ResponseEntity<ApiResponse<Void>> handleForecastInProgress(ForecastInProgressException ex) {
        log.warn("Forecast already in progress for corporate {}: {}", ex.getCorporateId(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage(), "FORECAST_IN_PROGRESS"));
    }

    /**
     * Handle Settlement VA resolution failures with user-friendly messages.
     * Returns 422 UNPROCESSABLE_ENTITY with exception details.
     */
    @ExceptionHandler(SettlementVaException.class)
    public ResponseEntity<ApiResponse<SettlementVaExceptionDetails>> handleSettlementVaException(SettlementVaException ex) {
        log.warn("Settlement VA resolution failed: {} - Exception: {}", ex.getCode(), ex.getExceptionNumber());

        SettlementVaExceptionDetails details = SettlementVaExceptionDetails.builder()
                .exceptionNumber(ex.getExceptionNumber())
                .exceptionVaId(ex.getExceptionVaId())
                .programId(ex.getProgramId())
                .currencyCode(ex.getCurrencyCode())
                .errorCode(ex.getCode())
                .build();

        ApiResponse<SettlementVaExceptionDetails> response = ApiResponse.<SettlementVaExceptionDetails>builder()
                .success(false)
                .message(ex.getUserMessage())
                .data(details)
                .error(ApiResponse.ErrorInfo.builder()
                        .code(ex.getCode())
                        .message(ex.getUserMessage())
                        .build())
                .build();

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
    }

    /**
     * Details returned to user when Settlement VA exception occurs.
     */
    @lombok.Data
    @lombok.Builder
    public static class SettlementVaExceptionDetails {
        private String exceptionNumber;
        private java.util.UUID exceptionVaId;
        private java.util.UUID programId;
        private String currencyCode;
        private String errorCode;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException ex) {
        List<ApiResponse.FieldError> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> ApiResponse.FieldError.builder()
                        .field(error.getField())
                        .message(error.getDefaultMessage())
                        .rejectedValue(error.getRejectedValue())
                        .build())
                .collect(Collectors.toList());

        ApiResponse<Void> response = ApiResponse.<Void>builder()
                .success(false)
                .message("Validation failed")
                .error(ApiResponse.ErrorInfo.builder()
                        .code("VALIDATION_ERROR")
                        .message("Validation failed")
                        .fieldErrors(fieldErrors)
                        .build())
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("An unexpected error occurred", "INTERNAL_ERROR"));
    }
}
