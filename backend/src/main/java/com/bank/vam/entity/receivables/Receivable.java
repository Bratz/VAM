package com.bank.vam.entity.receivables;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Receivable Entity - Phase 3 Enhanced with COBO, Intercompany & Netting Support
 * 
 * PHASE 3 ENHANCEMENTS:
 * - Entity context (which legal entity owns this receivable)
 * - Party integration (links to Party master for customer details)
 * - Intercompany identification (receivable from group entity)
 * - COBO workflow (Collect On Behalf Of - centralized collections)
 * - Netting integration (cycle participation, settlement status)
 * - Collection routing (DIRECT, COBO, INTERCOMPANY, NETTING)
 * 
 * BACKWARD COMPATIBLE:
 * - All existing fields preserved
 * - New fields have defaults
 * - Existing methods unchanged
 * 
 * @see <a href="https://www.capgemini.com/wp-content/uploads/2020/12/Virtual-Acc-Management-in-Transaction-Banking.pdf">
 *      Capgemini VAM Reference</a>
 */
@Entity
@Table(name = "receivables", indexes = {
    // Existing indexes
    @Index(name = "idx_receivable_corporate", columnList = "corporate_id"),
    @Index(name = "idx_receivable_program", columnList = "program_id"),
    @Index(name = "idx_receivable_va", columnList = "virtual_account_id"),
    @Index(name = "idx_receivable_viban", columnList = "primary_viban_id"),
    @Index(name = "idx_receivable_status", columnList = "status"),
    @Index(name = "idx_receivable_due_date", columnList = "due_date"),
    @Index(name = "idx_receivable_type", columnList = "receivable_type"),
    @Index(name = "idx_receivable_hierarchy", columnList = "hierarchy_node_id"),
    @Index(name = "idx_receivable_channel", columnList = "collection_channel"),
    // Phase 3: New indexes for COBO/IC/Netting
    @Index(name = "idx_receivable_owning_entity", columnList = "owning_entity_id"),
    @Index(name = "idx_receivable_customer_party", columnList = "customer_party_id"),
    @Index(name = "idx_receivable_intercompany", columnList = "is_intercompany"),
    @Index(name = "idx_receivable_intercompany_entity", columnList = "intercompany_entity_id"),
    @Index(name = "idx_receivable_cobo", columnList = "is_cobo"),
    @Index(name = "idx_receivable_cobo_collector", columnList = "cobo_collector_entity_id"),
    @Index(name = "idx_receivable_cobo_status", columnList = "cobo_request_status"),
    @Index(name = "idx_receivable_netting_cycle", columnList = "netting_cycle_id"),
    @Index(name = "idx_receivable_netting_status", columnList = "netting_status"),
    @Index(name = "idx_receivable_collection_route", columnList = "collection_route")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Receivable extends BaseEntity {

    // ========================================================================
    // IDENTIFICATION
    // ========================================================================

    @Column(name = "receivable_number", unique = true, nullable = false, length = 50)
    private String receivableNumber;

    @Column(name = "external_reference", length = 100)
    private String externalReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "receivable_type", nullable = false, length = 30)
    @Builder.Default
    private ReceivableType receivableType = ReceivableType.INVOICE;

    // ========================================================================
    // OWNERSHIP
    // ========================================================================

    @Column(name = "corporate_id", nullable = false)
    private UUID corporateId;

    @Column(name = "program_id")
    private UUID programId;

    @Column(name = "virtual_account_id")
    private UUID virtualAccountId;

    // ========================================================================
    // PHASE 3: ENTITY CONTEXT (NEW)
    // ========================================================================
    
    /**
     * The legal entity (subsidiary) that "owns" this receivable.
     * This is the entity that issued the invoice and is owed the money.
     * 
     * Example: SUB-DUBAI invoices a customer → owningEntityId = SUB-DUBAI
     * 
     * Required for:
     * - COBO routing (Treasury collects on behalf of this entity)
     * - Proper revenue allocation
     * - Multi-entity corporate structures
     */
    @Column(name = "owning_entity_id")
    private UUID owningEntityId;
    
    /**
     * Code of the owning legal entity for display purposes.
     */
    @Column(name = "owning_entity_code", length = 20)
    private String owningEntityCode;
    
    /**
     * Name of the owning legal entity for display purposes.
     */
    @Column(name = "owning_entity_name", length = 200)
    private String owningEntityName;

    // ========================================================================
    // PHASE 3: CUSTOMER PARTY INTEGRATION (NEW)
    // ========================================================================
    
    /**
     * Link to the Party master record for this customer.
     * Provides:
     * - COBO eligibility
     * - Intercompany flag
     * - KYC/Compliance status
     * - Bank account for refunds
     */
    @Column(name = "customer_party_id")
    private UUID customerPartyId;
    
    /**
     * Customer bank account for refunds/returns.
     */
    @Column(name = "customer_party_bank_account_id")
    private UUID customerPartyBankAccountId;

    // ========================================================================
    // PHASE 3: INTERCOMPANY IDENTIFICATION (NEW)
    // ========================================================================
    
    /**
     * Whether this receivable is from an intercompany entity (intercompany sale).
     * True when customer is actually another group entity.
     * 
     * Determines:
     * - Netting eligibility (IC receivables are always netting-eligible)
     * - Settlement method (can use VA-to-VA, netting, or IHB)
     * - Transfer pricing compliance requirements
     */
    @Column(name = "is_intercompany")
    @Builder.Default
    private Boolean isIntercompany = false;
    
    /**
     * If intercompany, which group entity is the customer/debtor?
     * Links to the legal entity that owes this receivable.
     */
    @Column(name = "intercompany_entity_id")
    private UUID intercompanyEntityId;
    
    /**
     * Code of the intercompany entity for display.
     */
    @Column(name = "intercompany_entity_code", length = 20)
    private String intercompanyEntityCode;
    
    /**
     * Name of the intercompany entity for display.
     */
    @Column(name = "intercompany_entity_name", length = 200)
    private String intercompanyEntityName;
    
    /**
     * Cross-reference to the matching payable on the counterparty side.
     * For IC transactions, owning entity's receivable = counterparty's payable.
     */
    @Column(name = "counterparty_payable_id")
    private UUID counterpartyPayableId;

    // ========================================================================
    // PHASE 3: COBO (COLLECT ON BEHALF OF) WORKFLOW (NEW)
    // ========================================================================
    
    /**
     * Whether this receivable is collected via COBO (centralized collection).
     * When true, treasury collects on behalf of the owning entity.
     */
    @Column(name = "is_cobo")
    @Builder.Default
    private Boolean isCobo = false;
    
    /**
     * Entity that will collect on behalf (usually HQ treasury).
     */
    @Column(name = "cobo_collector_entity_id")
    private UUID coboCollectorEntityId;
    
    /**
     * Code of the collecting entity for display.
     */
    @Column(name = "cobo_collector_entity_code", length = 20)
    private String coboCollectorEntityCode;
    
    /**
     * Name of the collecting entity for display.
     */
    @Column(name = "cobo_collector_entity_name", length = 200)
    private String coboCollectorEntityName;
    
    /**
     * Reference to the COBO request/authorization record.
     */
    @Column(name = "cobo_request_id")
    private UUID coboRequestId;
    
    /**
     * COBO request workflow status.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "cobo_request_status", length = 30)
    private CoboRequestStatus coboRequestStatus;
    
    /**
     * Transaction reference for the executed COBO collection.
     */
    @Column(name = "cobo_transaction_ref", length = 50)
    private String coboTransactionRef;
    
    /**
     * Intercompany recharge record created for COBO settlement.
     * Treasury collects from customer, creates recharge to forward to subsidiary.
     */
    @Column(name = "cobo_recharge_id")
    private UUID coboRechargeId;
    
    /**
     * IHB deposit ID created when treasury forwards collection to subsidiary.
     */
    @Column(name = "cobo_ihb_deposit_id")
    private UUID coboIhbDepositId;
    
    /**
     * When COBO was requested.
     */
    @Column(name = "cobo_requested_at")
    private LocalDateTime coboRequestedAt;
    
    /**
     * Who requested COBO (user/system).
     */
    @Column(name = "cobo_requested_by", length = 100)
    private String coboRequestedBy;
    
    /**
     * When treasury approved/rejected COBO request.
     */
    @Column(name = "cobo_actioned_at")
    private LocalDateTime coboActionedAt;
    
    /**
     * Who actioned the COBO request at treasury.
     */
    @Column(name = "cobo_actioned_by", length = 100)
    private String coboActionedBy;

    // ========================================================================
    // PHASE 3: NETTING INTEGRATION (NEW)
    // ========================================================================
    
    /**
     * Whether this receivable can participate in netting cycles.
     * Automatically true for intercompany receivables.
     */
    @Column(name = "netting_eligible")
    @Builder.Default
    private Boolean nettingEligible = false;
    
    /**
     * Current netting cycle this receivable is included in.
     */
    @Column(name = "netting_cycle_id")
    private UUID nettingCycleId;
    
    /**
     * Reference number of the netting cycle for display.
     */
    @Column(name = "netting_cycle_ref", length = 50)
    private String nettingCycleRef;
    
    /**
     * Entry ID within the netting cycle.
     */
    @Column(name = "netting_entry_id")
    private UUID nettingEntryId;
    
    /**
     * Netting status for this receivable.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "netting_status", length = 30)
    @Builder.Default
    private NettingStatus nettingStatus = NettingStatus.NOT_INCLUDED;
    
    /**
     * Settlement reference from netting cycle execution.
     */
    @Column(name = "netting_settlement_ref", length = 50)
    private String nettingSettlementRef;
    
    /**
     * When this receivable was settled via netting.
     */
    @Column(name = "netting_settled_at")
    private LocalDateTime nettingSettledAt;

    // ========================================================================
    // PHASE 3: COLLECTION ROUTING (NEW)
    // ========================================================================
    
    /**
     * Determines how this receivable will be collected.
     * 
     * DIRECT: Standard collection to owning entity's account
     * COBO: Treasury collects on behalf of owning entity
     * INTERCOMPANY: VA-to-VA transfer between group entities
     * NETTING: Settled via netting cycle (net position only)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "collection_route", length = 30)
    @Builder.Default
    private CollectionRoute collectionRoute = CollectionRoute.DIRECT;

    // ========================================================================
    // HIERARCHY CONTEXT (from Week 2 - Preserved)
    // ========================================================================

    @Column(name = "hierarchy_node_id")
    private UUID hierarchyNodeId;

    @Column(name = "hierarchy_path", length = 500)
    private String hierarchyPath;

    // ========================================================================
    // CUSTOMER/PAYER INFO (Legacy - Preserved)
    // ========================================================================

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "customer_name", length = 200)
    private String customerName;

    @Column(name = "customer_email", length = 200)
    private String customerEmail;

    @Column(name = "customer_mobile", length = 30)
    private String customerMobile;

    @Column(name = "customer_reference", length = 100)
    private String customerReference;

    // ========================================================================
    // VIBAN INTEGRATION (from Week 2 - Preserved)
    // ========================================================================

    @Column(name = "primary_viban_id")
    private UUID primaryVibanId;

    @Column(name = "viban", length = 34)
    private String viban;

    // ========================================================================
    // AMOUNTS
    // ========================================================================

    @Column(name = "currency_code", nullable = false, length = 3)
    @Builder.Default
    private String currencyCode = "AED";

    @Column(name = "gross_amount", precision = 18, scale = 2, nullable = false)
    private BigDecimal grossAmount;

    @Column(name = "discount_amount", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "tax_amount", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(name = "net_amount", precision = 18, scale = 2, nullable = false)
    private BigDecimal netAmount;

    @Column(name = "paid_amount", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Column(name = "outstanding_amount", precision = 18, scale = 2)
    private BigDecimal outstandingAmount;

    // ========================================================================
    // DATES
    // ========================================================================

    @Column(name = "issue_date", nullable = false)
    @Builder.Default
    private LocalDate issueDate = LocalDate.now();

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "payment_terms_days")
    private Integer paymentTermsDays;

    // ========================================================================
    // STATUS
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private ReceivableStatus status = ReceivableStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", length = 30)
    @Builder.Default
    private PaymentStatus paymentStatus = PaymentStatus.PENDING;

    // ========================================================================
    // AUTO-RECONCILIATION SETTINGS (Preserved)
    // ========================================================================

    @Column(name = "auto_reconcile")
    @Builder.Default
    private Boolean autoReconcile = true;

    @Column(name = "amount_tolerance_percent", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal amountTolerancePercent = BigDecimal.ZERO;

    @Column(name = "min_acceptable_amount", precision = 18, scale = 2)
    private BigDecimal minAcceptableAmount;

    @Column(name = "max_acceptable_amount", precision = 18, scale = 2)
    private BigDecimal maxAcceptableAmount;

    @Column(name = "allow_partial_payment")
    @Builder.Default
    private Boolean allowPartialPayment = true;

    @Column(name = "allow_overpayment")
    @Builder.Default
    private Boolean allowOverpayment = false;

    // ========================================================================
    // COLLECTION CHANNEL (Preserved)
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "collection_channel", length = 30)
    private CollectionChannel collectionChannel;

    // ========================================================================
    // E-COMMERCE SPECIFIC (Preserved)
    // ========================================================================

    @Column(name = "platform", length = 50)
    private String platform;

    @Column(name = "platform_order_id", length = 100)
    private String platformOrderId;

    @Column(name = "platform_fee", precision = 18, scale = 2)
    private BigDecimal platformFee;

    @Enumerated(EnumType.STRING)
    @Column(name = "escrow_status", length = 30)
    private EscrowStatus escrowStatus;

    // ========================================================================
    // POS SPECIFIC (Preserved)
    // ========================================================================

    @Column(name = "merchant_id", length = 30)
    private String merchantId;

    @Column(name = "terminal_id", length = 20)
    private String terminalId;

    // ========================================================================
    // METADATA
    // ========================================================================

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "metadata", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String metadata;

    // ========================================================================
    // AUDIT
    // ========================================================================

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    // ========================================================================
    // ENUMS - EXISTING (Preserved)
    // ========================================================================

    public enum ReceivableType {
        INVOICE,        // Traditional invoice
        ORDER,          // E-commerce/marketplace order
        SUBSCRIPTION,   // Recurring payment
        INSTALLMENT,    // Payment plan installment
        DEPOSIT,        // Security deposit
        FEE,            // Service fee
        INTERCOMPANY,   // Intercompany receivable (Phase 3)
        OTHER
    }

    public enum ReceivableStatus {
        DRAFT,              // Not yet issued
        OPEN,               // Issued, awaiting payment
        PARTIAL,            // Partially paid
        PAID,               // Fully paid
        OVERDUE,            // Past due date
        DISPUTED,           // Under dispute
        CANCELLED,          // Cancelled
        WRITTEN_OFF,        // Written off as bad debt
        // Phase 3: COBO workflow statuses
        PENDING_COBO,       // Awaiting COBO treasury approval
        COBO_APPROVED,      // COBO approved, awaiting collection
        COBO_REJECTED,      // COBO rejected by treasury
        // Phase 3: Netting workflow statuses
        PENDING_NETTING,    // Included in netting cycle, awaiting settlement
        NETTED              // Settled via netting
    }

    public enum PaymentStatus {
        PENDING,        // No payment received
        PARTIAL,        // Partial payment received
        COMPLETE,       // Full payment received
        OVERPAID        // Paid more than due
    }

    public enum CollectionChannel {
        INVOICE,        // Invoice payment
        ECOMMERCE,      // E-commerce platform
        POS,            // Point of sale
        DIRECT,         // Direct bank transfer
        SUBSCRIPTION,   // Recurring debit
        QR_CODE,        // QR code payment
        WALLET,         // Wallet payment
        AGENT,          // Agent collection
        COBO            // COBO centralized collection (Phase 3)
    }

    public enum EscrowStatus {
        AWAITING_PAYMENT,   // Waiting for buyer payment
        HELD,               // Funds held in escrow
        RELEASED,           // Funds released to seller
        DISPUTED,           // Under dispute
        REFUNDED            // Refunded to buyer
    }

    // ========================================================================
    // PHASE 3: NEW ENUMS
    // ========================================================================
    
    /**
     * Collection routing options.
     */
    public enum CollectionRoute {
        /** Standard collection to owning entity's account */
        DIRECT,
        /** Treasury collects on behalf of owning entity (COBO) */
        COBO,
        /** VA-to-VA transfer between group entities */
        INTERCOMPANY,
        /** Settled via netting cycle */
        NETTING
    }
    
    /**
     * COBO request workflow status.
     */
    public enum CoboRequestStatus {
        /** Not applicable - direct collection */
        NOT_REQUESTED,
        /** Subsidiary has requested COBO, awaiting entity approval */
        PENDING_ENTITY_APPROVAL,
        /** Entity approved, awaiting treasury approval */
        PENDING_TREASURY_APPROVAL,
        /** Treasury approved, ready for collection */
        APPROVED,
        /** Treasury rejected the COBO request */
        REJECTED,
        /** COBO collection executed */
        COLLECTED,
        /** COBO collection failed */
        FAILED
    }
    
    /**
     * Netting participation status.
     */
    public enum NettingStatus {
        /** Not included in any netting cycle */
        NOT_INCLUDED,
        /** Added to cycle, awaiting calculation */
        PENDING,
        /** Included in calculated cycle */
        INCLUDED,
        /** Netting cycle executed, this receivable settled */
        SETTLED,
        /** Excluded from netting (e.g., customer opted out) */
        EXCLUDED
    }

    // ========================================================================
    // EXISTING HELPER METHODS (Preserved)
    // ========================================================================

    public void calculateOutstanding() {
        this.outstandingAmount = this.netAmount.subtract(
            this.paidAmount != null ? this.paidAmount : BigDecimal.ZERO
        );
    }

    public boolean isFullyPaid() {
        return paidAmount != null && paidAmount.compareTo(netAmount) >= 0;
    }

    public boolean isOverdue() {
        if (dueDate == null || isFullyPaid()) return false;
        return LocalDate.now().isAfter(dueDate);
    }

    public boolean canAcceptPayment() {
        return status != ReceivableStatus.CANCELLED 
            && status != ReceivableStatus.WRITTEN_OFF
            && status != ReceivableStatus.PAID
            && status != ReceivableStatus.NETTED;
    }

    public boolean isPaymentAmountAcceptable(BigDecimal paymentAmount) {
        if (paymentAmount == null || paymentAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        
        if (minAcceptableAmount != null && paymentAmount.compareTo(minAcceptableAmount) < 0) {
            return false;
        }
        
        if (!Boolean.TRUE.equals(allowOverpayment)) {
            BigDecimal remaining = outstandingAmount != null ? outstandingAmount : netAmount;
            if (paymentAmount.compareTo(remaining) > 0) {
                return false;
            }
        }
        
        if (amountTolerancePercent != null && amountTolerancePercent.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal tolerance = netAmount.multiply(amountTolerancePercent).divide(BigDecimal.valueOf(100));
            BigDecimal minAllowed = netAmount.subtract(tolerance);
            BigDecimal maxAllowed = netAmount.add(tolerance);
            
            if (paymentAmount.compareTo(minAllowed) >= 0 && paymentAmount.compareTo(maxAllowed) <= 0) {
                return true;
            }
        }
        
        if (!Boolean.TRUE.equals(allowPartialPayment) && paymentAmount.compareTo(outstandingAmount) < 0) {
            return false;
        }
        
        return true;
    }

    public void recordPayment(BigDecimal amount) {
        if (paidAmount == null) paidAmount = BigDecimal.ZERO;
        paidAmount = paidAmount.add(amount);
        calculateOutstanding();
        updateStatusAfterPayment();
    }

    public void updateStatusAfterPayment() {
        if (isFullyPaid()) {
            status = ReceivableStatus.PAID;
            paymentStatus = paidAmount.compareTo(netAmount) > 0 
                ? PaymentStatus.OVERPAID 
                : PaymentStatus.COMPLETE;
        } else if (paidAmount.compareTo(BigDecimal.ZERO) > 0) {
            if (status == ReceivableStatus.OPEN || status == ReceivableStatus.OVERDUE) {
                status = ReceivableStatus.PARTIAL;
            }
            paymentStatus = PaymentStatus.PARTIAL;
        }
    }

    public boolean hasLinkedViban() {
        return primaryVibanId != null || (viban != null && !viban.isEmpty());
    }

    public boolean hasHierarchyContext() {
        return hierarchyNodeId != null;
    }

    public Integer getDaysUntilDue() {
        if (dueDate == null) return null;
        return (int) java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), dueDate);
    }

    public String getAgingBucket() {
        if (isFullyPaid()) return "PAID";
        if (dueDate == null) return "NO_DUE_DATE";
        
        int daysOverdue = -getDaysUntilDue();
        if (daysOverdue <= 0) return "CURRENT";
        if (daysOverdue <= 30) return "1_30_DAYS";
        if (daysOverdue <= 60) return "31_60_DAYS";
        if (daysOverdue <= 90) return "61_90_DAYS";
        return "OVER_90_DAYS";
    }

    // ========================================================================
    // PHASE 3: NEW HELPER METHODS
    // ========================================================================
    
    /**
     * Check if this receivable is eligible for COBO routing.
     */
    public boolean canRequestCobo() {
        return status == ReceivableStatus.OPEN
            && collectionRoute == CollectionRoute.DIRECT
            && (coboRequestStatus == null || coboRequestStatus == CoboRequestStatus.NOT_REQUESTED);
    }
    
    /**
     * Request COBO for this receivable.
     */
    public void requestCobo(UUID collectorEntityId, String collectorEntityCode, 
                            String collectorEntityName, String requestedBy) {
        this.collectionRoute = CollectionRoute.COBO;
        this.isCobo = true;
        this.coboCollectorEntityId = collectorEntityId;
        this.coboCollectorEntityCode = collectorEntityCode;
        this.coboCollectorEntityName = collectorEntityName;
        this.coboRequestStatus = CoboRequestStatus.PENDING_TREASURY_APPROVAL;
        this.coboRequestedAt = LocalDateTime.now();
        this.coboRequestedBy = requestedBy;
        this.status = ReceivableStatus.PENDING_COBO;
    }
    
    /**
     * Treasury approves COBO request.
     */
    public void approveCobo(String approvedBy) {
        this.coboRequestStatus = CoboRequestStatus.APPROVED;
        this.coboActionedAt = LocalDateTime.now();
        this.coboActionedBy = approvedBy;
        this.status = ReceivableStatus.COBO_APPROVED;
    }
    
    /**
     * Treasury rejects COBO request.
     */
    public void rejectCobo(String rejectedBy) {
        this.coboRequestStatus = CoboRequestStatus.REJECTED;
        this.coboActionedAt = LocalDateTime.now();
        this.coboActionedBy = rejectedBy;
        this.status = ReceivableStatus.COBO_REJECTED;
        // Revert to direct collection route
        this.collectionRoute = CollectionRoute.DIRECT;
        this.isCobo = false;
        this.coboCollectorEntityId = null;
        this.coboCollectorEntityCode = null;
        this.coboCollectorEntityName = null;
    }
    
    /**
     * Mark COBO as collected and record IHB deposit.
     */
    public void markCoboCollected(String transactionRef, UUID ihbDepositId, UUID rechargeId) {
        this.coboRequestStatus = CoboRequestStatus.COLLECTED;
        this.coboTransactionRef = transactionRef;
        this.coboIhbDepositId = ihbDepositId;
        this.coboRechargeId = rechargeId;
        this.status = ReceivableStatus.PAID;
        this.paymentStatus = PaymentStatus.COMPLETE;
        this.paidAmount = this.netAmount;
        this.outstandingAmount = BigDecimal.ZERO;
    }
    
    /**
     * Check if this receivable can be added to a netting cycle.
     */
    public boolean canAddToNetting() {
        boolean isEligibleStatus = status == ReceivableStatus.OPEN 
            || status == ReceivableStatus.COBO_APPROVED;
        boolean isEligible = Boolean.TRUE.equals(nettingEligible) 
            || Boolean.TRUE.equals(isIntercompany);
        boolean notAlreadyInNetting = nettingCycleId == null 
            && nettingStatus == NettingStatus.NOT_INCLUDED;
        
        return isEligibleStatus && isEligible && notAlreadyInNetting;
    }
    
    /**
     * Add this receivable to a netting cycle.
     */
    public void addToNettingCycle(UUID cycleId, String cycleRef, UUID entryId) {
        this.nettingCycleId = cycleId;
        this.nettingCycleRef = cycleRef;
        this.nettingEntryId = entryId;
        this.nettingStatus = NettingStatus.INCLUDED;
        this.collectionRoute = CollectionRoute.NETTING;
        this.status = ReceivableStatus.PENDING_NETTING;
    }
    
    /**
     * Remove from netting cycle (e.g., cycle cancelled).
     */
    public void removeFromNettingCycle() {
        this.nettingCycleId = null;
        this.nettingCycleRef = null;
        this.nettingEntryId = null;
        this.nettingStatus = NettingStatus.NOT_INCLUDED;
        this.collectionRoute = Boolean.TRUE.equals(isIntercompany) 
            ? CollectionRoute.INTERCOMPANY 
            : CollectionRoute.DIRECT;
        this.status = ReceivableStatus.OPEN;
    }
    
    /**
     * Mark as settled via netting.
     */
    public void markNettingSettled(String settlementRef) {
        this.nettingStatus = NettingStatus.SETTLED;
        this.nettingSettlementRef = settlementRef;
        this.nettingSettledAt = LocalDateTime.now();
        this.status = ReceivableStatus.NETTED;
        this.paymentStatus = PaymentStatus.COMPLETE;
        this.paidAmount = this.netAmount;
        this.outstandingAmount = BigDecimal.ZERO;
    }
    
    /**
     * Set as intercompany receivable with counterparty details.
     */
    public void setIntercompany(UUID counterpartyEntityId, String counterpartyCode, 
                                 String counterpartyName) {
        this.isIntercompany = true;
        this.intercompanyEntityId = counterpartyEntityId;
        this.intercompanyEntityCode = counterpartyCode;
        this.intercompanyEntityName = counterpartyName;
        this.nettingEligible = true; // IC receivables are always netting-eligible
        this.collectionRoute = CollectionRoute.INTERCOMPANY;
        this.receivableType = ReceivableType.INTERCOMPANY;
    }
    
    /**
     * Check if collection is routed through COBO.
     */
    public boolean isCoboRouted() {
        return collectionRoute == CollectionRoute.COBO;
    }
    
    /**
     * Check if collection is routed through netting.
     */
    public boolean isNettingRouted() {
        return collectionRoute == CollectionRoute.NETTING;
    }
    
    /**
     * Check if this is an intercompany receivable.
     */
    public boolean isIntercompanyReceivable() {
        return Boolean.TRUE.equals(isIntercompany) && intercompanyEntityId != null;
    }
    
    /**
     * Get display-friendly collection route description.
     */
    public String getCollectionRouteDescription() {
        if (collectionRoute == null) return "Direct Collection";
        return switch (collectionRoute) {
            case DIRECT -> "Direct Collection";
            case COBO -> "COBO via " + (coboCollectorEntityCode != null ? coboCollectorEntityCode : "Treasury");
            case INTERCOMPANY -> "Intercompany from " + (intercompanyEntityCode != null ? intercompanyEntityCode : "Group Entity");
            case NETTING -> "Netting Cycle " + (nettingCycleRef != null ? nettingCycleRef : "");
        };
    }

    // ========================================================================
    // FACTORY METHODS - ENHANCED
    // ========================================================================

    /**
     * Create an invoice receivable with entity context.
     */
    public static Receivable createInvoice(UUID corporateId, UUID owningEntityId,
                                            String owningEntityCode, String owningEntityName,
                                            UUID customerPartyId, String customerName, 
                                            BigDecimal amount, LocalDate dueDate) {
        return Receivable.builder()
            .receivableType(ReceivableType.INVOICE)
            .corporateId(corporateId)
            .owningEntityId(owningEntityId)
            .owningEntityCode(owningEntityCode)
            .owningEntityName(owningEntityName)
            .customerPartyId(customerPartyId)
            .customerName(customerName)
            .grossAmount(amount)
            .netAmount(amount)
            .outstandingAmount(amount)
            .dueDate(dueDate)
            .collectionChannel(CollectionChannel.INVOICE)
            .status(ReceivableStatus.OPEN)
            .paymentStatus(PaymentStatus.PENDING)
            .collectionRoute(CollectionRoute.DIRECT)
            .nettingStatus(NettingStatus.NOT_INCLUDED)
            .build();
    }
    
    /**
     * Create an intercompany receivable (intercompany sale).
     */
    public static Receivable createIntercompanyReceivable(UUID corporateId, UUID owningEntityId,
                                                           String owningEntityCode, String owningEntityName,
                                                           UUID counterpartyEntityId, String counterpartyCode,
                                                           String counterpartyName, UUID customerPartyId,
                                                           String description, BigDecimal amount,
                                                           String currencyCode) {
        Receivable receivable = Receivable.builder()
            .receivableType(ReceivableType.INTERCOMPANY)
            .corporateId(corporateId)
            .owningEntityId(owningEntityId)
            .owningEntityCode(owningEntityCode)
            .owningEntityName(owningEntityName)
            .customerPartyId(customerPartyId)
            .customerName(counterpartyName)
            .description(description)
            .currencyCode(currencyCode)
            .grossAmount(amount)
            .netAmount(amount)
            .outstandingAmount(amount)
            .issueDate(LocalDate.now())
            .dueDate(LocalDate.now().plusDays(30))
            .status(ReceivableStatus.OPEN)
            .paymentStatus(PaymentStatus.PENDING)
            .isIntercompany(true)
            .intercompanyEntityId(counterpartyEntityId)
            .intercompanyEntityCode(counterpartyCode)
            .intercompanyEntityName(counterpartyName)
            .nettingEligible(true)
            .collectionRoute(CollectionRoute.INTERCOMPANY)
            .nettingStatus(NettingStatus.NOT_INCLUDED)
            .build();
        return receivable;
    }

    /**
     * Create an invoice receivable (legacy - backward compatible).
     */
    public static Receivable createInvoice(UUID corporateId, String customerName, 
                                            BigDecimal amount, LocalDate dueDate) {
        return Receivable.builder()
            .receivableType(ReceivableType.INVOICE)
            .corporateId(corporateId)
            .customerName(customerName)
            .grossAmount(amount)
            .netAmount(amount)
            .outstandingAmount(amount)
            .dueDate(dueDate)
            .collectionChannel(CollectionChannel.INVOICE)
            .status(ReceivableStatus.OPEN)
            .paymentStatus(PaymentStatus.PENDING)
            .collectionRoute(CollectionRoute.DIRECT)
            .nettingStatus(NettingStatus.NOT_INCLUDED)
            .build();
    }

    /**
     * Create an e-commerce order receivable.
     */
    public static Receivable createEcommerceOrder(UUID corporateId, String platform, 
                                                   String orderId, BigDecimal amount, 
                                                   BigDecimal platformFee) {
        BigDecimal netAmount = amount.subtract(platformFee != null ? platformFee : BigDecimal.ZERO);
        return Receivable.builder()
            .receivableType(ReceivableType.ORDER)
            .corporateId(corporateId)
            .platform(platform)
            .platformOrderId(orderId)
            .grossAmount(amount)
            .platformFee(platformFee)
            .netAmount(netAmount)
            .outstandingAmount(netAmount)
            .collectionChannel(CollectionChannel.ECOMMERCE)
            .escrowStatus(EscrowStatus.AWAITING_PAYMENT)
            .status(ReceivableStatus.OPEN)
            .paymentStatus(PaymentStatus.PENDING)
            .collectionRoute(CollectionRoute.DIRECT)
            .nettingStatus(NettingStatus.NOT_INCLUDED)
            .build();
    }
}