package com.bank.vam.dto.viban;

import com.bank.vam.entity.viban.Viban.VibanType;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * DTOs for VIBAN operations.
 */
public class VibanDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VibanCreateRequest {
        private UUID virtualAccountId;
        private VibanType vibanType;
        private String referenceType;
        private String referenceId;
        private BigDecimal expectedAmount;
        private BigDecimal amountTolerancePercent;
        private BigDecimal minAmount;
        private BigDecimal maxAmount;
        private String currencyCode;
        private LocalDateTime validUntil;
        private Boolean singleUse;
        private String customerName;
        private String customerReference;
        private String purpose;
        private String paymentLink;
        private String qrCodeData;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VibanResponse {
        private UUID id;
        private String viban;
        private UUID virtualAccountId;
        private String vaNumber;
        private String vaName;
        private UUID programId;
        private UUID poolId;
        private String poolCode;
        private VibanType vibanType;
        private Boolean isPrimary;
        private String referenceType;
        private String referenceId;
        private String status;
        private LocalDateTime validFrom;
        private LocalDateTime validUntil;
        private Boolean singleUse;
        private Integer timesUsed;
        private BigDecimal totalAmountReceived;
        private BigDecimal expectedAmount;
        private BigDecimal remainingAmount;
        private String currencyCode;
        private String customerName;
        private String purpose;
        private String paymentLink;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VibanLookupResponse {
        private UUID vibanId;
        private String viban;
        private UUID virtualAccountId;
        private String vaNumber;
        private UUID programId;
        private UUID hierarchyNodeId;
        private Boolean isValid;
        private String validationMessage;
        private String referenceType;
        private String referenceId;
        private BigDecimal expectedAmount;
        private Boolean canAcceptPayment;
        private Long lookupTimeMs;
    }

    // ========================================================================
    // VIBAN Statistics DTO (NEW)
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VibanStatsResponse {
        private Integer total;
        private Integer assigned;
        private Integer available;
        private Integer reserved;
        private Integer expired;
        private Integer totalPools;
        private Integer activePools;
        private Integer lowThresholdPools;
        private Integer totalPaymentsRouted;
        private BigDecimal totalAmountRouted;
    }

    // ========================================================================
    // VIBAN Pool DTOs
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PoolCreateRequest {
        private String poolName;
        private String poolCode;
        private String description;
        private String countryCode;
        private String bankCode;
        private String prefix;
        private Integer suffixLength;
        private Integer poolSize;
        private Integer assignmentTtlMinutes;
        private Boolean autoReturnExpired;
        private Integer lowThresholdPercent;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PoolUpdateRequest {
        private String poolName;
        private String description;
        private Integer assignmentTtlMinutes;
        private Boolean autoReturnExpired;
        private Integer lowThresholdPercent;
        private String status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PoolResponse {
        private UUID id;
        private UUID programId;
        private String programName;
        private String poolName;
        private String poolCode;
        private String description;
        private String countryCode;
        private String bankCode;
        private String prefix;
        private Integer suffixLength;
        private Integer poolSize;
        private Integer availableCount;
        private Integer reservedCount;
        private Integer assignedCount;
        private Double utilizationPercent;  // Changed from Integer to Double to match entity
        private Integer assignmentTtlMinutes;
        private Boolean autoReturnExpired;
        private Integer lowThresholdPercent;
        private String status;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PoolAssignRequest {
        private UUID virtualAccountId;
        private UUID partyId;              // Party Master reference (replaces customerName free text)
        private String referenceType;      // ORDER, INVOICE, TERMINAL, etc.
        private String referenceId;
        private BigDecimal expectedAmount;
        private String customerName;       // Deprecated: Use partyId instead. Kept for backward compatibility
        private Boolean isPrimary;         // If true, creates permanent VIBAN (no TTL)
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PoolAssignResponse {
        private UUID vibanId;
        private String viban;
        private UUID virtualAccountId;
        private String vaNumber;
        private UUID partyId;
        private String partyName;
        private Boolean isPrimary;
        private LocalDateTime assignedAt;
        private LocalDateTime returnScheduledAt;  // null for primary VIBANs
    }

    // ========================================================================
    // BULK ASSIGNMENT DTOs
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkAssignRequest {
        private List<BulkAssignItem> assignments;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkAssignItem {
        private UUID virtualAccountId;
        private UUID partyId;
        private String referenceType;
        private String referenceId;
        private BigDecimal expectedAmount;
        private Boolean isPrimary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkAssignResponse {
        private Integer totalRequested;
        private Integer successCount;
        private Integer failedCount;
        private List<PoolAssignResponse> successful;
        private List<BulkAssignError> failed;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkAssignError {
        private UUID virtualAccountId;
        private String error;
    }

    // ========================================================================
    // INVOICE-VIBAN ASSIGNMENT DTOs (Accounts Receivable)
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InvoiceVibanRequest {
        private UUID invoiceId;
        private UUID virtualAccountId;      // Optional: if not provided, uses default collection VA
        private UUID partyId;               // Customer/Debtor from Party Master
        private String invoiceNumber;
        private BigDecimal invoiceAmount;
        private String currencyCode;
        private LocalDateTime dueDate;
        private String paymentTerms;        // NET30, NET60, etc.
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InvoiceVibanResponse {
        private UUID invoiceId;
        private String invoiceNumber;
        private UUID vibanId;
        private String viban;
        private UUID virtualAccountId;
        private String vaNumber;
        private UUID partyId;
        private String partyName;
        private BigDecimal expectedAmount;
        private String currencyCode;
        private LocalDateTime validUntil;
        private String paymentLink;
        private String qrCodeData;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GenerateVibansRequest {
        private Integer count;
    }
}