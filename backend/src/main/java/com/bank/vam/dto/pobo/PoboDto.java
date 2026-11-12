package com.bank.vam.dto.pobo;

import com.bank.vam.entity.pobo.IntercompanyRecharge;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** POBO DTOs: recharges, statistics, execution history. */
public class PoboDto {

    // ========================================================================
    // RECHARGE DTOs
    // ========================================================================
    
    @Data
    @Builder
    public static class CreateRechargeRequest {
        private UUID payerEntityId;
        private String payerEntityCode;
        private String payerEntityName;
        private UUID payerVaId;
        private UUID behalfEntityId;
        private String behalfEntityCode;
        private String behalfEntityName;
        private UUID behalfVaId;
        private UUID originalPayableId;
        private UUID originalPaymentExecutionId;
        private String originalPaymentReference;
        private BigDecimal originalAmount;
        private String currencyCode;
        private BigDecimal serviceFeeRate;
        private BigDecimal adminFee;
        private Boolean requiresApproval;
    }
    
    @Data
    @Builder
    public static class RechargeResponse {
        private UUID id;
        private String rechargeReference;
        private UUID payerEntityId;
        private String payerEntityCode;
        private String payerEntityName;
        private UUID behalfEntityId;
        private String behalfEntityCode;
        private String behalfEntityName;
        private UUID originalPayableId;
        private String originalPaymentReference;
        private BigDecimal originalAmount;
        private BigDecimal rechargeAmount;
        private BigDecimal serviceFee;
        private BigDecimal adminFee;
        private BigDecimal fxMarkup;
        private BigDecimal totalRecharge;
        private String currencyCode;
        private Boolean armLengthValidated;
        private String armLengthNotes;
        private IntercompanyRecharge.RechargeStatus status;
        private LocalDate settlementDate;
        private String settlementReference;
        private IntercompanyRecharge.SettlementMethod settledVia;
        private String approvedBy;
        private LocalDateTime approvedAt;
        private LocalDateTime createdAt;
    }
    
    // ========================================================================
    // STATISTICS DTOs
    // ========================================================================
    
    @Data
    @Builder
    public static class PoboStatsResponse {
        private int pendingRecharges;
        private int settledRecharges;
        private BigDecimal totalRechargesOutstanding;
        private BigDecimal totalServiceFeesCollected;
        private int armLengthValidationsPending;
        private LocalDateTime asOfDate;
    }

    // ========================================================================
    // PHASE 5: HISTORY DTOs
    // ========================================================================
    
    @Data
    @Builder
    public static class PoboHistoryResponse {
        private Long totalElements;
        private int totalPages;
        private int page;
        private int size;
        private List<PoboExecutionSummary> executions;
    }
    
    @Data
    @Builder
    public static class PoboExecutionSummary {
        private UUID executionId;
        private String executionReference;
        private UUID payableId;
        private String payableNumber;
        private String vendorName;
        private UUID treasuryEntityId;
        private String treasuryEntityCode;
        private UUID subsidiaryEntityId;
        private String subsidiaryEntityCode;
        private BigDecimal paidAmount;
        private String currency;
        private UUID rechargeId;
        private String rechargeReference;
        private BigDecimal totalRecharge;
        private String status;
        private LocalDateTime executedAt;
        private String executedBy;
    }
    
    @Data
    @Builder
    public static class PoboExecutionDetailResponse {
        private UUID executionId;
        private String executionReference;
        
        // Payable details
        private UUID payableId;
        private String payableNumber;
        private String vendorName;
        private String vendorAccount;
        private BigDecimal payableAmount;
        
        // Entity details
        private UUID treasuryEntityId;
        private String treasuryEntityCode;
        private String treasuryEntityName;
        private UUID subsidiaryEntityId;
        private String subsidiaryEntityCode;
        private String subsidiaryEntityName;
        
        // Payment details
        private String paymentTransactionRef;
        private BigDecimal paidAmount;
        private String currency;
        private LocalDateTime paymentTimestamp;
        
        // Recharge details
        private UUID rechargeId;
        private String rechargeReference;
        private BigDecimal rechargeAmount;
        private BigDecimal serviceFee;
        private BigDecimal processingFee;
        private BigDecimal fxMarkup;
        private BigDecimal totalRecharge;
        private String rechargeStatus;
        
        // Audit
        private String executedBy;
        private LocalDateTime executedAt;
        private String approvedBy;
        private LocalDateTime approvedAt;
    }

}