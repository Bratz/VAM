package com.bank.vam.dto.treasury;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class SettlementVaDto {

    // ========================================================================
    // REQUESTS
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateSettlementVaRequest {
        private UUID programId;
        private UUID parentNodeId;
        private String currency;
        private String vaName;
        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InitializeHierarchyRequest {
        private UUID programId;
        private String templateType;
        private List<String> currencies; // Optional: for multi-currency programs
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResolveSettlementVaRequest {
        private UUID sourceVaId;
    }

    // ========================================================================
    // RESPONSES
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SettlementVaResponse {
        private UUID id;
        private String vaNumber;
        private String vaName;
        private String specialType; // SETTLEMENT or EXCEPTION
        private String currency;
        private BigDecimal currentBalance;
        private BigDecimal availableBalance;
        private String status;
        
        // Hierarchy info
        private UUID hierarchyNodeId;
        private String hierarchyPath;
        private Integer hierarchyLevel;
        private String parentNodeName;
        
        // Program info
        private UUID programId;
        private String programCode;
        private String programName;
        
        // Audit
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SettlementVaListResponse {
        private List<SettlementVaResponse> settlementVas;
        private List<SettlementVaResponse> exceptionVas;
        private SettlementVaSummary summary;
        
        // Program hierarchy status - CRITICAL for UI to know if initialization is needed
        private Boolean hierarchyInitialized;
        private String programName;
        private String programCode;
        private String currencyCode;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SettlementVaSummary {
        private int totalSettlementVas;
        private int totalExceptionVas;
        private BigDecimal totalSettlementBalance;
        private BigDecimal totalExceptionBalance;
        private int openExceptionCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SettlementVaDetailResponse {
        private SettlementVaResponse va;
        private List<RecentTransactionResponse> recentTransactions;
        private List<CoveredVaResponse> coveredVas; // VAs that post fees to this Settlement VA
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecentTransactionResponse {
        private UUID id;
        private String referenceNumber;
        private String movementType;
        private BigDecimal amount;
        private String currency;
        private BigDecimal balanceAfter;
        private String sourceVaNumber;
        private String description;
        private String correlationId;
        private LocalDateTime transactionDate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CoveredVaResponse {
        private UUID vaId;
        private String vaNumber;
        private String vaName;
        private String hierarchyPath;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResolveSettlementVaResponse {
        private SettlementVaResponse settlementVa;
        private String resolutionPath;
        private boolean isExceptionFallback;
    }
}