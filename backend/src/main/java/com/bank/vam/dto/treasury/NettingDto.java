package com.bank.vam.dto.treasury;

import com.bank.vam.entity.treasury.NettingCycle.CycleStatus;
import com.bank.vam.entity.treasury.NettingEntry.EntryStatus;
import com.bank.vam.entity.treasury.NettingEntry.FlowDirection;
import com.bank.vam.entity.treasury.NettingEntry.SourceType;
import com.bank.vam.entity.treasury.NettingSettlement.SettlementDirection;
import com.bank.vam.entity.treasury.NettingSettlement.SettlementStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * NettingDto - Phase 4 Enhanced DTOs for Unified Netting Engine
 * 
 * Enhanced for:
 * - Bidirectional flow (PAYABLE/RECEIVABLE)
 * - Source document links
 * - Gross amounts tracking
 * - Entry counts per direction
 */
public class NettingDto {

    // ========================================================================
    // REQUEST DTOs
    // ========================================================================

    @Data
    public static class CreateCycleRequest {
        private String cycleName;
        private LocalDate periodStart;
        private LocalDate periodEnd;
        private LocalDate settlementDate;
        private String baseCurrency;
        private UUID corporateId;
    }

    @Data
    public static class AddEntryRequest {
        // Phase 4: Flow direction
        private FlowDirection flowDirection;
        
        // Entity references
        private UUID payerEntityId;
        private String payerEntityCode;
        private String payerEntityName;
        private UUID payeeEntityId;
        private String payeeEntityCode;
        private String payeeEntityName;
        
        // Amounts
        private BigDecimal grossAmount;
        private String currencyCode;
        private BigDecimal exchangeRate;
        
        // Source tracking
        private SourceType sourceType;
        private String sourceReference;
        
        // Phase 4: Source document links
        private UUID payableId;
        private UUID receivableId;
        private UUID intercompanyRechargeId;
        private UUID ihbTransactionId;
        
        // Due date for aging
        private LocalDate dueDate;
    }
    
    /**
     * Phase 4: Request to populate cycle with all obligations.
     */
    @Data
    public static class PopulateCycleRequest {
        private UUID cycleId;
        private UUID corporateId;
        private boolean includePayables = true;
        private boolean includeReceivables = true;
        private boolean includePoboRecharges = true;
        private boolean includeIhbTransactions = false; // Future
    }

    // ========================================================================
    // RESPONSE DTOs
    // ========================================================================

    @Data
    public static class CycleResponse {
        private UUID id;
        private String cycleReference;
        private String cycleName;
        private LocalDate periodStart;
        private LocalDate periodEnd;
        private LocalDate settlementDate;
        private String baseCurrency;
        private BigDecimal totalGross;
        private BigDecimal totalNet;
        private BigDecimal savingsAmount;
        private BigDecimal savingsPercent;
        private Integer entryCount;
        private Integer participantCount;
        private CycleStatus status;
        private String approvedBy;
        private LocalDateTime approvedAt;
        private LocalDateTime settledAt;
        private LocalDateTime createdAt;
        
        // Phase 4: Entry breakdown by direction
        private Integer payableEntryCount;
        private Integer receivableEntryCount;
        private BigDecimal totalPayables;
        private BigDecimal totalReceivables;
        
        private List<EntryResponse> entries;
        private List<SettlementResponse> settlements;
    }

    @Data
    public static class EntryResponse {
        private UUID id;
        private String entryReference;
        
        // Phase 4: Flow direction
        private FlowDirection flowDirection;
        
        // Entities
        private UUID payerEntityId;
        private String payerEntityCode;
        private String payerEntityName;
        private UUID payeeEntityId;
        private String payeeEntityCode;
        private String payeeEntityName;
        
        // Amounts
        private BigDecimal grossAmount;
        private String currencyCode;
        private BigDecimal exchangeRate;
        private BigDecimal baseAmount;
        
        // Phase 4: Original currency tracking
        private String originalCurrency;
        private BigDecimal originalAmount;
        
        // Source tracking
        private SourceType sourceType;
        private String sourceReference;
        
        // Phase 4: Source document links
        private UUID payableId;
        private UUID receivableId;
        private UUID intercompanyRechargeId;
        private UUID ihbTransactionId;
        private UUID sourceEntityId;
        private String sourceEntityCode;
        
        // Phase 4: Due date
        private LocalDate dueDate;
        
        private EntryStatus status;
        private LocalDateTime createdAt;
    }

    @Data
    public static class SettlementResponse {
        private UUID id;
        private UUID entityId;
        private String entityCode;
        private String entityName;
        
        // Legacy fields (preserved for backward compatibility)
        private BigDecimal totalPayable;
        private BigDecimal totalReceivable;
        private BigDecimal netPosition;
        
        // Phase 4: Enhanced gross tracking
        private BigDecimal grossPayables;
        private BigDecimal grossReceivables;
        
        // Phase 4: Entry counts
        private Integer payableEntryCount;
        private Integer receivableEntryCount;
        
        private SettlementDirection settlementDirection;
        private String settlementAccount;
        
        // Phase 4: VA and transaction links
        private UUID settlementVaId;
        private UUID settlementTransactionId;
        
        private SettlementStatus settlementStatus;
        private LocalDateTime settledAt;
        private String settlementReference;
    }

    @Data
    public static class CalculateNettingResponse {
        private UUID cycleId;
        private BigDecimal totalGross;
        private BigDecimal totalNet;
        private BigDecimal savingsAmount;
        private BigDecimal savingsPercent;
        private Integer participantCount;
        
        // Phase 4: Breakdown
        private BigDecimal totalPayables;
        private BigDecimal totalReceivables;
        private Integer payableEntryCount;
        private Integer receivableEntryCount;
        
        private List<SettlementResponse> settlements;
    }

    @Data
    public static class SettleCycleResponse {
        private UUID cycleId;
        private String cycleReference;
        private LocalDateTime settledAt;
        private Integer successfulSettlements;
        private Integer failedSettlements;
        private BigDecimal totalSettled;
        
        // Phase 4: Settlement breakdown
        private BigDecimal totalPaid;
        private BigDecimal totalReceived;
        private Integer documentsUpdated;
    }
    
    /**
     * Phase 4: Response from cycle population.
     */
    @Data
    public static class PopulateCycleResponse {
        private UUID cycleId;
        private String cycleReference;
        private Integer payablesAdded;
        private Integer receivablesAdded;
        private Integer rechargesAdded;           // Total recharges (POBO + COBO)
        private Integer poboRechargesAdded;       // POBO recharges (subsidiary owes treasury)
        private Integer coboRechargesAdded;       // COBO recharges (treasury owes subsidiary)
        private Integer ihbTransactionsAdded;
        private Integer totalAdded;
        private Integer totalEntries;
        private BigDecimal totalGross;
    }
    
    /**
     * Phase 4: Summary of entity's netting position.
     */
    @Data
    public static class EntityNettingPositionResponse {
        private UUID entityId;
        private String entityCode;
        private String entityName;
        
        // Current cycle participation
        private UUID currentCycleId;
        private String currentCycleReference;
        
        // Position summary
        private BigDecimal grossPayables;
        private BigDecimal grossReceivables;
        private BigDecimal netPosition;
        private SettlementDirection direction;
        
        // Entry counts
        private Integer payableEntryCount;
        private Integer receivableEntryCount;
        
        // Eligible but not yet included
        private Integer eligiblePayablesCount;
        private Integer eligibleReceivablesCount;
        private BigDecimal eligiblePayablesAmount;
        private BigDecimal eligibleReceivablesAmount;
    }
    
    /**
     * Phase 4: Request to add single payable/receivable to netting.
     */
    @Data
    public static class AddToNettingRequest {
        private UUID cycleId;
        private UUID documentId;  // payableId or receivableId
        private String documentType; // PAYABLE or RECEIVABLE
    }
    
    /**
     * Phase 4: Request to remove from netting.
     */
    @Data
    public static class RemoveFromNettingRequest {
        private UUID cycleId;
        private UUID entryId;
        private String reason;
    }
    
    /**
     * Phase 4: Netting cycle summary for dashboard.
     */
    @Data
    public static class NettingCycleSummary {
        private UUID cycleId;
        private String cycleReference;
        private String cycleName;
        private CycleStatus status;
        private LocalDate settlementDate;
        
        // Entry breakdown
        private Integer totalEntries;
        private Integer payableEntries;
        private Integer receivableEntries;
        private Integer intercompanyEntries;
        private Integer poboRechargeEntries;
        
        // Amount summary
        private BigDecimal totalGross;
        private BigDecimal totalNet;
        private BigDecimal savingsAmount;
        private BigDecimal savingsPercent;
        
        // Participants
        private Integer participantCount;
        private Integer payersCount;
        private Integer receiversCount;
        private Integer neutralCount;
    }
    
    /**
     * Phase 4: Netting eligibility check response.
     */
    @Data
    public static class NettingEligibilityResponse {
        private UUID documentId;
        private String documentType;
        private String documentReference;
        private boolean eligible;
        private String reason;
        
        // If eligible, suggested cycle
        private UUID suggestedCycleId;
        private String suggestedCycleReference;
        
        // Document details for confirmation
        private UUID owningEntityId;
        private String owningEntityCode;
        private UUID counterpartyEntityId;
        private String counterpartyEntityCode;
        private BigDecimal amount;
        private String currency;
        private LocalDate dueDate;
    }
}