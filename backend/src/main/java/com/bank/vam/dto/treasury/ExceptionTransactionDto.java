package com.bank.vam.dto.treasury;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ExceptionTransactionDto {

    // ========================================================================
    // REQUESTS
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AllocateExceptionRequest {
        private UUID targetVaId;
        private String notes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WriteOffExceptionRequest {
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StartInvestigationRequest {
        private String investigator;
    }

    // ========================================================================
    // RESPONSES
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExceptionResponse {
        private UUID id;
        private String exceptionNumber;
        private String exceptionType;
        private String exceptionTypeDisplay;
        private BigDecimal amount;
        private String currency;
        private String status;
        
        // Context
        private String bankReference;
        private String remitterInfo;
        private String description;
        
        // Source
        private UUID originalVaId;
        private String originalVaNumber;
        private UUID originalTransactionId;
        
        // Exception VA
        private UUID exceptionVaId;
        private String exceptionVaNumber;
        
        // Resolution
        private UUID targetVaId;
        private String targetVaNumber;
        private String resolutionNotes;
        private String resolvedBy;
        private LocalDateTime resolvedAt;
        
        // Aging
        private long daysSinceCreated;
        private String agingBucket;
        
        // Audit
        private LocalDateTime createdAt;
        private String createdBy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExceptionListResponse {
        private List<ExceptionResponse> exceptions;
        private ExceptionSummary summary;
        private int totalElements;
        private int totalPages;
        private int currentPage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExceptionSummary {
        private long openCount;
        private long inProgressCount;
        private long resolvedCount;
        private long writtenOffCount;
        private long returnedCount;
        private BigDecimal openAmount;
        private BigDecimal inProgressAmount;
        private BigDecimal resolvedAmount;
        private Map<String, Long> countByType;
        private Map<String, BigDecimal> amountByType;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SuggestedAllocationResponse {
        private UUID vaId;
        private String vaNumber;
        private String vaName;
        private String matchReason;
        private int confidence;
    }
}