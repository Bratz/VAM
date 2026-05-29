package com.bank.vam.entity.payables;

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
 * Payable Entity - Phase 2 Enhanced with POBO, Intercompany & Netting Support
 * 
 * PHASE 2 ENHANCEMENTS:
 * - Entity context (which legal entity owns this payable)
 * - Payment routing (DIRECT, POBO, INTERCOMPANY, NETTING)
 * - Party integration (links to Party master for vendor details)
 * - POBO workflow (request, approval, execution tracking)
 * - Netting integration (cycle participation, settlement status)
 * - Intercompany identification
 * 
 * @see <a href="https://www.capgemini.com/wp-content/uploads/2020/12/Virtual-Acc-Management-in-Transaction-Banking.pdf">
 *      Capgemini VAM Reference</a>
 */
@Entity
@Table(name = "payables", indexes = {
    // Existing indexes
    @Index(name = "idx_payable_corporate", columnList = "corporate_id"),
    @Index(name = "idx_payable_program", columnList = "program_id"),
    @Index(name = "idx_payable_va", columnList = "virtual_account_id"),
    @Index(name = "idx_payable_status", columnList = "status"),
    @Index(name = "idx_payable_due_date", columnList = "due_date"),
    @Index(name = "idx_payable_vendor", columnList = "vendor_id"),
    @Index(name = "idx_payable_type", columnList = "payable_type"),
    @Index(name = "idx_payable_hierarchy", columnList = "hierarchy_node_id"),
    @Index(name = "idx_payable_batch", columnList = "payment_batch_id"),
    // Phase 2: New indexes for POBO/IC/Netting
    @Index(name = "idx_payable_owning_entity", columnList = "owning_entity_id"),
    @Index(name = "idx_payable_party", columnList = "party_id"),
    @Index(name = "idx_payable_payment_route", columnList = "payment_route"),
    @Index(name = "idx_payable_pobo_status", columnList = "pobo_request_status"),
    @Index(name = "idx_payable_intercompany", columnList = "is_intercompany"),
    @Index(name = "idx_payable_netting_cycle", columnList = "netting_cycle_id"),
    @Index(name = "idx_payable_counterparty", columnList = "counterparty_entity_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payable extends BaseEntity {

    // ========================================================================
    // IDENTIFICATION
    // ========================================================================

    @Column(name = "payable_number", unique = true, nullable = false, length = 50)
    private String payableNumber;

    @Column(name = "external_reference", length = 100)
    private String externalReference;

    @Column(name = "invoice_number", length = 100)
    private String invoiceNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "payable_type", nullable = false, length = 30)
    @Builder.Default
    private PayableType payableType = PayableType.INVOICE;

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
    // PHASE 2: ENTITY CONTEXT (NEW)
    // ========================================================================
    
    /**
     * The legal entity (subsidiary) that "owns" this payable.
     * This is the entity that incurred the expense and is responsible for it.
     * 
     * Example: SUB-DUBAI creates a payable to vendor → owningEntityId = SUB-DUBAI
     * 
     * Required for:
     * - POBO routing (Treasury pays on behalf of this entity)
     * - Proper cost allocation
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
    // PHASE 2: PARTY INTEGRATION (NEW)
    // ========================================================================
    
    /**
     * Link to the Party master record for this vendor.
     * Replaces loose vendor_id field with proper Party entity reference.
     * 
     * Benefits:
     * - POBO eligibility check from Party
     * - Intercompany flag from Party
     * - Bank account selection from Party
     * - KYC/Compliance status from Party
     */
    @Column(name = "party_id")
    private UUID partyId;
    
    /**
     * Selected bank account from the Party's bank accounts.
     * Used for payment execution.
     */
    @Column(name = "party_bank_account_id")
    private UUID partyBankAccountId;

    // ========================================================================
    // PHASE 2: PAYMENT ROUTING (NEW)
    // ========================================================================
    
    /**
     * Determines how this payable will be settled.
     * 
     * DIRECT: Standard payment from owning entity's account
     * POBO: Treasury pays on behalf of owning entity
     * INTERCOMPANY: VA-to-VA transfer between group entities
     * NETTING: Settled via netting cycle (net position only)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_route", length = 30)
    @Builder.Default
    private PaymentRoute paymentRoute = PaymentRoute.DIRECT;
    
    /**
     * Entity that will actually execute the payment (for POBO).
     * Usually corporate HQ treasury.
     * Null for DIRECT payments.
     */
    @Column(name = "payment_via_entity_id")
    private UUID paymentViaEntityId;
    
    /**
     * Code of the paying entity for display.
     */
    @Column(name = "payment_via_entity_code", length = 20)
    private String paymentViaEntityCode;

    // ========================================================================
    // PHASE 2: POBO WORKFLOW (NEW)
    // ========================================================================
    
    /**
     * Reference to the POBO request/authorization record.
     * Created when subsidiary requests treasury to pay on their behalf.
     */
    @Column(name = "pobo_request_id")
    private UUID poboRequestId;
    
    /**
     * POBO request workflow status.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "pobo_request_status", length = 30)
    private PoboRequestStatus poboRequestStatus;
    
    /**
     * Transaction reference for the executed POBO payment.
     */
    @Column(name = "pobo_transaction_ref", length = 50)
    private String poboTransactionRef;
    
    /**
     * IHB loan ID created for the subsidiary as a result of POBO.
     * Treasury pays vendor, creates loan to subsidiary for reimbursement.
     */
    @Column(name = "pobo_ihb_loan_id")
    private UUID poboIhbLoanId;
    
    /**
     * Intercompany recharge record created for POBO settlement.
     */
    @Column(name = "pobo_recharge_id")
    private UUID poboRechargeId;
    
    /**
     * When POBO was requested.
     */
    @Column(name = "pobo_requested_at")
    private LocalDateTime poboRequestedAt;
    
    /**
     * Who requested POBO (user/system).
     */
    @Column(name = "pobo_requested_by", length = 100)
    private String poboRequestedBy;
    
    /**
     * When treasury approved/rejected POBO request.
     */
    @Column(name = "pobo_actioned_at")
    private LocalDateTime poboActionedAt;
    
    /**
     * Who actioned the POBO request at treasury.
     */
    @Column(name = "pobo_actioned_by", length = 100)
    private String poboActionedBy;

    // ========================================================================
    // PHASE 2: INTERCOMPANY IDENTIFICATION (NEW)
    // ========================================================================
    
    /**
     * Whether this payable is to an intercompany entity.
     * True when vendor is actually another group entity.
     * 
     * Determines:
     * - Netting eligibility (IC payables are always netting-eligible)
     * - Settlement method (can use VA-to-VA, netting, or IHB)
     * - Transfer pricing compliance requirements
     */
    @Column(name = "is_intercompany")
    @Builder.Default
    private Boolean isIntercompany = false;
    
    /**
     * If intercompany, which group entity is the counterparty?
     * Links to the legal entity that will receive this payment.
     */
    @Column(name = "counterparty_entity_id")
    private UUID counterpartyEntityId;
    
    /**
     * Code of the counterparty entity for display.
     */
    @Column(name = "counterparty_entity_code", length = 20)
    private String counterpartyEntityCode;
    
    /**
     * Name of the counterparty entity for display.
     */
    @Column(name = "counterparty_entity_name", length = 200)
    private String counterpartyEntityName;
    
    /**
     * Cross-reference to the matching receivable on the counterparty side.
     * For IC transactions, owning entity's payable = counterparty's receivable.
     */
    @Column(name = "counterparty_receivable_id")
    private UUID counterpartyReceivableId;

    // ========================================================================
    // PHASE 2: NETTING INTEGRATION (NEW)
    // ========================================================================
    
    /**
     * Whether this payable can participate in netting cycles.
     * Automatically true for intercompany payables.
     * Can also be enabled for strategic external vendors.
     */
    @Column(name = "netting_eligible")
    @Builder.Default
    private Boolean nettingEligible = false;
    
    /**
     * Current netting cycle this payable is included in.
     * Null if not yet included in any cycle.
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
     * Netting status for this payable.
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
     * When this payable was settled via netting.
     */
    @Column(name = "netting_settled_at")
    private LocalDateTime nettingSettledAt;

    // ========================================================================
    // HIERARCHY CONTEXT (from Week 2)
    // ========================================================================

    @Column(name = "hierarchy_node_id")
    private UUID hierarchyNodeId;

    @Column(name = "hierarchy_path", length = 500)
    private String hierarchyPath;

    // ========================================================================
    // VENDOR/PAYEE INFO (Legacy - kept for backward compatibility)
    // ========================================================================

    @Column(name = "vendor_id")
    private UUID vendorId;

    @Column(name = "vendor_name", length = 200)
    private String vendorName;

    @Column(name = "vendor_account", length = 50)
    private String vendorAccount;

    @Column(name = "vendor_bank", length = 100)
    private String vendorBank;

    @Column(name = "vendor_bank_code", length = 20)
    private String vendorBankCode;

    @Column(name = "vendor_reference", length = 100)
    private String vendorReference;

    // ========================================================================
    // VIBAN INTEGRATION FOR POBO (from Week 2)
    // ========================================================================

    @Column(name = "payment_viban_id")
    private UUID paymentVibanId;

    @Column(name = "payment_viban", length = 34)
    private String paymentViban;

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

    @Column(name = "withholding_tax", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal withholdingTax = BigDecimal.ZERO;

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

    @Column(name = "invoice_date")
    private LocalDate invoiceDate;

    @Column(name = "received_date")
    @Builder.Default
    private LocalDate receivedDate = LocalDate.now();

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
    private PayableStatus status = PayableStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", length = 30)
    @Builder.Default
    private PaymentStatus paymentStatus = PaymentStatus.UNPAID;

    // ========================================================================
    // APPROVAL WORKFLOW
    // ========================================================================

    @Column(name = "approval_required")
    @Builder.Default
    private Boolean approvalRequired = true;

    @Column(name = "approval_level")
    @Builder.Default
    private Integer approvalLevel = 1;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    // ========================================================================
    // PAYMENT SCHEDULING
    // ========================================================================

    @Column(name = "scheduled_date")
    private LocalDate scheduledDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_priority", length = 20)
    @Builder.Default
    private PaymentPriority paymentPriority = PaymentPriority.NORMAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 30)
    private PaymentMethod paymentMethod;

    @Column(name = "payment_batch_id")
    private UUID paymentBatchId;

    // ========================================================================
    // POBO (Legacy - kept for backward compatibility)
    // ========================================================================

    @Column(name = "is_pobo")
    @Builder.Default
    private Boolean isPobo = false;

    @Column(name = "behalf_of_entity", length = 200)
    private String behalfOfEntity;

    @Column(name = "behalf_of_va_id")
    private UUID behalfOfVaId;

    // ========================================================================
    // PAYMENT CHANNEL
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_channel", length = 30)
    private PaymentChannel paymentChannel;

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

    @Column(name = "has_invoice_document")
    @Builder.Default
    private Boolean hasInvoiceDocument = false;

    @Column(name = "document_count")
    @Builder.Default
    private Integer documentCount = 0;

    // ========================================================================
    // AUDIT
    // ========================================================================

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    // ========================================================================
    // ENUMS
    // ========================================================================

    public enum PayableType {
        INVOICE,        // Vendor invoice
        EXPENSE,        // Employee expense
        SALARY,         // Salary payment
        TAX,            // Tax payment
        UTILITY,        // Utility bill
        SUBSCRIPTION,   // Recurring subscription
        REFUND,         // Customer refund
        INTERCOMPANY,   // Intercompany payable (Phase 2)
        OTHER
    }

    public enum PayableStatus {
        DRAFT,              // Created, not yet submitted
        PENDING_APPROVAL,   // Awaiting approval
        APPROVED,           // Approved, ready for payment
        PENDING_POBO,       // Awaiting POBO treasury approval (Phase 2)
        POBO_APPROVED,      // POBO approved by treasury (Phase 2)
        POBO_REJECTED,      // POBO rejected by treasury (Phase 2)
        SCHEDULED,          // Scheduled for future payment
        PROCESSING,         // Payment in progress
        PAID,               // Fully paid
        PARTIAL,            // Partially paid
        REJECTED,           // Rejected by approver
        CANCELLED,          // Cancelled
        ON_HOLD,            // On hold
        PENDING_NETTING,    // Included in netting cycle, awaiting settlement (Phase 2)
        NETTED              // Settled via netting (Phase 2)
    }

    public enum PaymentStatus {
        UNPAID,     // No payment made
        PARTIAL,    // Partial payment made
        PAID        // Fully paid
    }

    public enum PaymentPriority {
        SAME_DAY,   // Same-day payment (maps to urgency fee)
        NEXT_DAY,   // Next business day
        URGENT,     // Immediate payment required
        HIGH,       // High priority
        NORMAL,     // Normal priority
        LOW         // Low priority, can wait
    }

    public enum PaymentMethod {
        BANK_TRANSFER,      // External bank transfer
        INTERNAL_TRANSFER,  // Internal VA transfer
        CHECK,              // Check payment
        CARD,               // Card payment
        CASH                // Cash payment
    }

    public enum PaymentChannel {
        DIRECT,     // Direct single payment
        BATCH,      // Part of batch
        SCHEDULED,  // Scheduled payment
        IMMEDIATE   // Immediate payment
    }
    
    /**
     * Phase 2: Payment routing options.
     */
    public enum PaymentRoute {
        /** Standard payment from owning entity's account */
        DIRECT,
        /** Treasury pays on behalf of owning entity (POBO) */
        POBO,
        /** VA-to-VA transfer between group entities */
        INTERCOMPANY,
        /** Settled via netting cycle */
        NETTING
    }
    
    /**
     * Phase 2: POBO request workflow status.
     */
    public enum PoboRequestStatus {
        /** Not applicable - direct payment */
        NOT_REQUESTED,
        /** Subsidiary has requested POBO, awaiting entity approval */
        PENDING_ENTITY_APPROVAL,
        /** Entity approved, awaiting treasury approval */
        PENDING_TREASURY_APPROVAL,
        /** Treasury approved, ready for execution */
        APPROVED,
        /** Treasury rejected the POBO request */
        REJECTED,
        /** POBO payment executed */
        EXECUTED,
        /** POBO payment failed */
        FAILED
    }
    
    /**
     * Phase 2: Netting participation status.
     */
    public enum NettingStatus {
        /** Not included in any netting cycle */
        NOT_INCLUDED,
        /** Added to cycle, awaiting calculation */
        PENDING,
        /** Included in calculated cycle */
        INCLUDED,
        /** Netting cycle executed, this payable settled */
        SETTLED,
        /** Excluded from netting (e.g., vendor opted out) */
        EXCLUDED
    }

    // ========================================================================
    // HELPER METHODS (Existing)
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

    public boolean canBePaid() {
        return status == PayableStatus.APPROVED 
            || status == PayableStatus.SCHEDULED
            || status == PayableStatus.PARTIAL
            || status == PayableStatus.POBO_APPROVED;
    }

    public boolean needsApproval() {
        return Boolean.TRUE.equals(approvalRequired) 
            && status == PayableStatus.PENDING_APPROVAL;
    }

    public boolean canBeApproved() {
        return status == PayableStatus.PENDING_APPROVAL;
    }

    public void submitForApproval() {
        if (status == PayableStatus.DRAFT) {
            this.status = PayableStatus.PENDING_APPROVAL;
        }
    }

    public void approve(String approvedBy) {
        this.status = PayableStatus.APPROVED;
        this.approvedBy = approvedBy;
        this.approvedAt = LocalDateTime.now();
    }

    public void reject(String rejectedBy, String reason) {
        this.status = PayableStatus.REJECTED;
        this.approvedBy = rejectedBy;
        this.approvedAt = LocalDateTime.now();
        this.rejectionReason = reason;
    }

    public void schedule(LocalDate scheduledDate) {
        if (canBePaid()) {
            this.scheduledDate = scheduledDate;
            this.status = PayableStatus.SCHEDULED;
        }
    }

    public void recordPayment(BigDecimal amount) {
        if (paidAmount == null) paidAmount = BigDecimal.ZERO;
        paidAmount = paidAmount.add(amount);
        calculateOutstanding();
        updateStatusAfterPayment();
    }

    public void updateStatusAfterPayment() {
        if (isFullyPaid()) {
            status = PayableStatus.PAID;
            paymentStatus = PaymentStatus.PAID;
        } else if (paidAmount.compareTo(BigDecimal.ZERO) > 0) {
            status = PayableStatus.PARTIAL;
            paymentStatus = PaymentStatus.PARTIAL;
        }
    }

    public boolean isPayOnBehalfOf() {
        return Boolean.TRUE.equals(isPobo) && behalfOfEntity != null;
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
    // PHASE 2: NEW HELPER METHODS
    // ========================================================================
    
    /**
     * Check if this payable is eligible for POBO routing.
     * Requires: approved status, not already POBO, party is POBO-eligible.
     */
    public boolean canRequestPobo() {
        return status == PayableStatus.APPROVED
            && paymentRoute == PaymentRoute.DIRECT
            && (poboRequestStatus == null || poboRequestStatus == PoboRequestStatus.NOT_REQUESTED);
    }
    
    /**
     * Request POBO for this payable.
     */
    public void requestPobo(UUID payingEntityId, String payingEntityCode, String requestedBy) {
        this.paymentRoute = PaymentRoute.POBO;
        this.paymentViaEntityId = payingEntityId;
        this.paymentViaEntityCode = payingEntityCode;
        this.poboRequestStatus = PoboRequestStatus.PENDING_TREASURY_APPROVAL;
        this.poboRequestedAt = LocalDateTime.now();
        this.poboRequestedBy = requestedBy;
        this.status = PayableStatus.PENDING_POBO;
    }
    
    /**
     * Treasury approves POBO request.
     */
    public void approvePobo(String approvedBy) {
        this.poboRequestStatus = PoboRequestStatus.APPROVED;
        this.poboActionedAt = LocalDateTime.now();
        this.poboActionedBy = approvedBy;
        this.status = PayableStatus.POBO_APPROVED;
    }
    
    /**
     * Treasury rejects POBO request.
     */
    public void rejectPobo(String rejectedBy, String reason) {
        this.poboRequestStatus = PoboRequestStatus.REJECTED;
        this.poboActionedAt = LocalDateTime.now();
        this.poboActionedBy = rejectedBy;
        this.rejectionReason = reason;
        this.status = PayableStatus.POBO_REJECTED;
        // Revert to direct payment route
        this.paymentRoute = PaymentRoute.DIRECT;
        this.paymentViaEntityId = null;
        this.paymentViaEntityCode = null;
    }
    
    /**
     * Mark POBO as executed and record IHB loan.
     */
    public void markPoboExecuted(String transactionRef, UUID ihbLoanId, UUID rechargeId) {
        this.poboRequestStatus = PoboRequestStatus.EXECUTED;
        this.poboTransactionRef = transactionRef;
        this.poboIhbLoanId = ihbLoanId;
        this.poboRechargeId = rechargeId;
        this.status = PayableStatus.PAID;
        this.paymentStatus = PaymentStatus.PAID;
        this.paidAmount = this.netAmount;
        this.outstandingAmount = BigDecimal.ZERO;
    }
    
    /**
     * Check if this payable can be added to a netting cycle.
     */
    public boolean canAddToNetting() {
        // Must be approved and either IC or explicitly netting-eligible
        boolean isApproved = status == PayableStatus.APPROVED 
            || status == PayableStatus.POBO_APPROVED;
        boolean isEligible = Boolean.TRUE.equals(nettingEligible) 
            || Boolean.TRUE.equals(isIntercompany);
        boolean notAlreadyInNetting = nettingCycleId == null 
            && nettingStatus == NettingStatus.NOT_INCLUDED;
        
        return isApproved && isEligible && notAlreadyInNetting;
    }
    
    /**
     * Add this payable to a netting cycle.
     */
    public void addToNettingCycle(UUID cycleId, String cycleRef, UUID entryId) {
        this.nettingCycleId = cycleId;
        this.nettingCycleRef = cycleRef;
        this.nettingEntryId = entryId;
        this.nettingStatus = NettingStatus.INCLUDED;
        this.paymentRoute = PaymentRoute.NETTING;
        this.status = PayableStatus.PENDING_NETTING;
    }
    
    /**
     * Remove from netting cycle (e.g., cycle cancelled).
     */
    public void removeFromNettingCycle() {
        this.nettingCycleId = null;
        this.nettingCycleRef = null;
        this.nettingEntryId = null;
        this.nettingStatus = NettingStatus.NOT_INCLUDED;
        this.paymentRoute = Boolean.TRUE.equals(isIntercompany) 
            ? PaymentRoute.INTERCOMPANY 
            : PaymentRoute.DIRECT;
        this.status = PayableStatus.APPROVED;
    }
    
    /**
     * Mark as settled via netting.
     */
    public void markNettingSettled(String settlementRef) {
        this.nettingStatus = NettingStatus.SETTLED;
        this.nettingSettlementRef = settlementRef;
        this.nettingSettledAt = LocalDateTime.now();
        this.status = PayableStatus.NETTED;
        this.paymentStatus = PaymentStatus.PAID;
        this.paidAmount = this.netAmount;
        this.outstandingAmount = BigDecimal.ZERO;
    }
    
    /**
     * Set as intercompany payable with counterparty details.
     */
    public void setIntercompany(UUID counterpartyEntityId, String counterpartyCode, 
                                 String counterpartyName) {
        this.isIntercompany = true;
        this.counterpartyEntityId = counterpartyEntityId;
        this.counterpartyEntityCode = counterpartyCode;
        this.counterpartyEntityName = counterpartyName;
        this.nettingEligible = true; // IC payables are always netting-eligible
        this.paymentRoute = PaymentRoute.INTERCOMPANY;
        this.payableType = PayableType.INTERCOMPANY;
    }
    
    /**
     * Check if payment is routed through POBO.
     */
    public boolean isPoboRouted() {
        return paymentRoute == PaymentRoute.POBO;
    }
    
    /**
     * Check if payment is routed through netting.
     */
    public boolean isNettingRouted() {
        return paymentRoute == PaymentRoute.NETTING;
    }
    
    /**
     * Check if this is an intercompany payable.
     */
    public boolean isIntercompanyPayable() {
        return Boolean.TRUE.equals(isIntercompany) && counterpartyEntityId != null;
    }
    
    /**
     * Get display-friendly payment route description.
     */
    public String getPaymentRouteDescription() {
        if (paymentRoute == null) return "Direct Payment";
        return switch (paymentRoute) {
            case DIRECT -> "Direct Payment";
            case POBO -> "POBO via " + (paymentViaEntityCode != null ? paymentViaEntityCode : "Treasury");
            case INTERCOMPANY -> "Intercompany to " + (counterpartyEntityCode != null ? counterpartyEntityCode : "Group Entity");
            case NETTING -> "Netting Cycle " + (nettingCycleRef != null ? nettingCycleRef : "");
        };
    }

    // ========================================================================
    // FACTORY METHODS (Enhanced)
    // ========================================================================

    /**
     * Create a vendor invoice payable with entity context.
     */
    public static Payable createVendorInvoice(UUID corporateId, UUID owningEntityId,
                                               String owningEntityCode, String owningEntityName,
                                               UUID partyId, String vendorName, 
                                               String invoiceNumber, BigDecimal amount, 
                                               LocalDate dueDate) {
        return Payable.builder()
            .payableType(PayableType.INVOICE)
            .corporateId(corporateId)
            .owningEntityId(owningEntityId)
            .owningEntityCode(owningEntityCode)
            .owningEntityName(owningEntityName)
            .partyId(partyId)
            .vendorName(vendorName)
            .invoiceNumber(invoiceNumber)
            .grossAmount(amount)
            .netAmount(amount)
            .outstandingAmount(amount)
            .invoiceDate(LocalDate.now())
            .dueDate(dueDate)
            .status(PayableStatus.DRAFT)
            .paymentStatus(PaymentStatus.UNPAID)
            .paymentRoute(PaymentRoute.DIRECT)
            .nettingStatus(NettingStatus.NOT_INCLUDED)
            .build();
    }
    
    /**
     * Create an intercompany payable.
     */
    public static Payable createIntercompanyPayable(UUID corporateId, UUID owningEntityId,
                                                     String owningEntityCode, String owningEntityName,
                                                     UUID counterpartyEntityId, String counterpartyCode,
                                                     String counterpartyName, UUID partyId,
                                                     String description, BigDecimal amount,
                                                     String currencyCode) {
        Payable payable = Payable.builder()
            .payableType(PayableType.INTERCOMPANY)
            .corporateId(corporateId)
            .owningEntityId(owningEntityId)
            .owningEntityCode(owningEntityCode)
            .owningEntityName(owningEntityName)
            .partyId(partyId)
            .vendorName(counterpartyName)
            .description(description)
            .currencyCode(currencyCode)
            .grossAmount(amount)
            .netAmount(amount)
            .outstandingAmount(amount)
            .invoiceDate(LocalDate.now())
            .dueDate(LocalDate.now().plusDays(30))
            .status(PayableStatus.DRAFT)
            .paymentStatus(PaymentStatus.UNPAID)
            .isIntercompany(true)
            .counterpartyEntityId(counterpartyEntityId)
            .counterpartyEntityCode(counterpartyCode)
            .counterpartyEntityName(counterpartyName)
            .nettingEligible(true)
            .paymentRoute(PaymentRoute.INTERCOMPANY)
            .nettingStatus(NettingStatus.NOT_INCLUDED)
            .build();
        return payable;
    }

    /**
     * Create a vendor invoice payable (legacy - backward compatible).
     */
    public static Payable createVendorInvoice(UUID corporateId, String vendorName, 
                                               String invoiceNumber, BigDecimal amount, 
                                               LocalDate dueDate) {
        return Payable.builder()
            .payableType(PayableType.INVOICE)
            .corporateId(corporateId)
            .vendorName(vendorName)
            .invoiceNumber(invoiceNumber)
            .grossAmount(amount)
            .netAmount(amount)
            .outstandingAmount(amount)
            .invoiceDate(LocalDate.now())
            .dueDate(dueDate)
            .status(PayableStatus.DRAFT)
            .paymentStatus(PaymentStatus.UNPAID)
            .paymentRoute(PaymentRoute.DIRECT)
            .nettingStatus(NettingStatus.NOT_INCLUDED)
            .build();
    }

    /**
     * Create an expense payable.
     */
    public static Payable createExpense(UUID corporateId, String description, 
                                         BigDecimal amount, String submittedBy) {
        return Payable.builder()
            .payableType(PayableType.EXPENSE)
            .corporateId(corporateId)
            .description(description)
            .grossAmount(amount)
            .netAmount(amount)
            .outstandingAmount(amount)
            .createdBy(submittedBy)
            .status(PayableStatus.PENDING_APPROVAL)
            .paymentStatus(PaymentStatus.UNPAID)
            .paymentRoute(PaymentRoute.DIRECT)
            .nettingStatus(NettingStatus.NOT_INCLUDED)
            .build();
    }

    /**
     * Create a salary payable.
     */
    public static Payable createSalary(UUID corporateId, String employeeName,
                                        BigDecimal grossAmount, BigDecimal withholdingTax,
                                        LocalDate paymentDate) {
        BigDecimal netAmount = grossAmount.subtract(withholdingTax);
        return Payable.builder()
            .payableType(PayableType.SALARY)
            .corporateId(corporateId)
            .vendorName(employeeName)
            .grossAmount(grossAmount)
            .withholdingTax(withholdingTax)
            .netAmount(netAmount)
            .outstandingAmount(netAmount)
            .scheduledDate(paymentDate)
            .status(PayableStatus.SCHEDULED)
            .paymentStatus(PaymentStatus.UNPAID)
            .paymentPriority(PaymentPriority.HIGH)
            .paymentRoute(PaymentRoute.DIRECT)
            .nettingStatus(NettingStatus.NOT_INCLUDED)
            .build();
    }
}