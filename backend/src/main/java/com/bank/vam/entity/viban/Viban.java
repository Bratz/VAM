package com.bank.vam.entity.viban;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Virtual IBAN entity with 1:N relationship to Virtual Accounts.
 * Each VA can have multiple VIBANs for different purposes:
 * - PRIMARY: Main VIBAN for the VA (permanent)
 * - INVOICE: Linked to specific invoice for auto-reconciliation
 * - ORDER: Linked to e-commerce order
 * - CUSTOMER: Permanent VIBAN for a customer
 * - TERMINAL: POS terminal specific
 * - BATCH: Gift card batch
 * - TEMPORARY: Temporary from pool (with TTL)
 * 
 * ROBO (Receive On Behalf Of) flow:
 * 1. Payment arrives at physical account with VIBAN
 * 2. VIBAN lookup returns VA + reference info
 * 3. Credit VA, update hierarchy balances
 * 4. Auto-reconcile if reference linked (invoice/order)
 */
@Entity
@Table(name = "vibans",
    indexes = {
        @Index(name = "idx_vibans_viban", columnList = "viban", unique = true),
        @Index(name = "idx_vibans_virtual_account_id", columnList = "virtual_account_id"),
        @Index(name = "idx_vibans_program_id", columnList = "program_id"),
        @Index(name = "idx_vibans_reference", columnList = "reference_type, reference_id"),
        @Index(name = "idx_vibans_status", columnList = "status"),
        @Index(name = "idx_vibans_type", columnList = "viban_type")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Viban extends BaseEntity {

    // ========================================================================
    // Primary identifier - CRITICAL for fast lookup (<5ms)
    // ========================================================================

    /**
     * Unique VIBAN string - primary lookup key.
     * Format: Country(2) + Check(2) + Bank(4) + Account(up to 26)
     * Example: AE070410001234567890123456
     */
    @Column(name = "viban", unique = true, nullable = false, length = 34)
    private String viban;

    // ========================================================================
    // Relationships
    // ========================================================================

    /**
     * Virtual Account this VIBAN routes payments to.
     */
    @Column(name = "virtual_account_id", nullable = true)
    private UUID virtualAccountId;

    /**
     * Program this VIBAN belongs to.
     */
    @Column(name = "program_id", nullable = false)
    private UUID programId;

    /**
     * Hierarchy node for this VIBAN (optional, for hierarchy-aware routing).
     */
    @Column(name = "hierarchy_node_id")
    private UUID hierarchyNodeId;

    // ========================================================================
    // Type and classification
    // ========================================================================

    /**
     * Type of VIBAN determining its lifecycle and usage.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "viban_type", nullable = false, length = 20)
    private VibanType vibanType;

    /**
     * Whether this is the primary VIBAN for the VA.
     * Only one VIBAN per VA can be primary.
     */
    @Column(name = "is_primary")
    @Builder.Default
    private Boolean isPrimary = false;

    // ========================================================================
    // Reference linking (for auto-reconciliation)
    // ========================================================================

    /**
     * Type of linked entity for auto-reconciliation.
     * Examples: INVOICE, ORDER, CUSTOMER, POLICY, SUBSCRIPTION
     */
    @Column(name = "reference_type", length = 50)
    private String referenceType;

    /**
     * ID of linked entity for auto-reconciliation.
     */
    @Column(name = "reference_id", length = 100)
    private String referenceId;

    /**
     * Description of the reference for display.
     */
    @Column(name = "reference_description", length = 255)
    private String referenceDescription;

    // ========================================================================
    // Validity
    // ========================================================================

    /**
     * Current status of the VIBAN.
     */
    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = STATUS_ACTIVE;

    /**
     * When this VIBAN becomes valid.
     */
    @Column(name = "valid_from")
    private LocalDateTime validFrom;

    /**
     * When this VIBAN expires.
     */
    @Column(name = "valid_until")
    private LocalDateTime validUntil;

    /**
     * Whether this VIBAN can only be used once.
     */
    @Column(name = "single_use")
    @Builder.Default
    private Boolean singleUse = false;

    // ========================================================================
    // Usage tracking
    // ========================================================================

    /**
     * Number of times this VIBAN has been used.
     */
    @Column(name = "times_used")
    @Builder.Default
    private Integer timesUsed = 0;

    /**
     * Total amount received via this VIBAN.
     */
    @Column(name = "total_amount_received", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal totalAmountReceived = BigDecimal.ZERO;

    /**
     * When this VIBAN was last used.
     */
    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    /**
     * Last payment amount received.
     */
    @Column(name = "last_payment_amount", precision = 18, scale = 2)
    private BigDecimal lastPaymentAmount;

    // ========================================================================
    // Expected payment (for matching)
    // ========================================================================

    /**
     * Expected payment amount for matching.
     */
    @Column(name = "expected_amount", precision = 18, scale = 2)
    private BigDecimal expectedAmount;

    /**
     * Tolerance percentage for amount matching.
     * Example: 5.0 means accept payments within +/-5% of expected.
     */
    @Column(name = "amount_tolerance_percent", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal amountTolerancePercent = BigDecimal.ZERO;

    /**
     * Fixed tolerance for amount matching.
     */
    @Column(name = "amount_tolerance_fixed", precision = 18, scale = 2)
    private BigDecimal amountToleranceFixed;

    /**
     * Minimum accepted amount.
     */
    @Column(name = "min_amount", precision = 18, scale = 2)
    private BigDecimal minAmount;

    /**
     * Maximum accepted amount.
     */
    @Column(name = "max_amount", precision = 18, scale = 2)
    private BigDecimal maxAmount;

    /**
     * Currency for the expected amount.
     */
    @Column(name = "currency_code", length = 3)
    private String currencyCode;

    // ========================================================================
    // Pool reference
    // ========================================================================

    /**
     * Pool this VIBAN was assigned from (if from pool).
     */
    @Column(name = "pool_id")
    private UUID poolId;

    /**
     * When this VIBAN was assigned from pool.
     */
    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    /**
     * When this VIBAN should be returned to pool.
     */
    @Column(name = "return_scheduled_at")
    private LocalDateTime returnScheduledAt;

    // ========================================================================
    // Customer info
    // ========================================================================

    /**
     * Reference to Party Master (Customer/Debtor).
     * Preferred over customerName free-text for proper AR integration.
     */
    @Column(name = "party_id")
    private UUID partyId;

    /**
     * Customer name for display.
     * Populated from Party Master if partyId is set, otherwise free-text.
     */
    @Column(name = "customer_name", length = 200)
    private String customerName;

    /**
     * Customer reference for matching.
     */
    @Column(name = "customer_reference", length = 100)
    private String customerReference;

    /**
     * Purpose of this VIBAN.
     */
    @Column(name = "purpose", length = 500)
    private String purpose;

    // ========================================================================
    // QR Code / Payment Link
    // ========================================================================

    /**
     * Payment link URL.
     */
    @Column(name = "payment_link", length = 500)
    private String paymentLink;

    /**
     * QR code data for payment.
     */
    @Column(name = "qr_code_data", columnDefinition = "TEXT")
    private String qrCodeData;

    // ========================================================================
    // Metadata
    // ========================================================================

    /**
     * Additional metadata as JSON.
     */
    @Column(name = "metadata", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String metadata;

    /**
     * Tags for filtering.
     */
    @Column(name = "tags", length = 255)
    private String tags;

    // ========================================================================
    // Enums
    // ========================================================================

    public enum VibanType {
        PRIMARY,      // Main VIBAN for VA (permanent)
        INVOICE,      // Linked to specific invoice
        ORDER,        // Linked to e-commerce order
        CUSTOMER,     // Permanent for customer
        TERMINAL,     // POS terminal specific
        BATCH,        // Gift card batch
        TEMPORARY,    // Temporary from pool
        SUBSCRIPTION, // Recurring payments
        POLICY        // Insurance policy
    }

    // ========================================================================
    // Status constants
    // ========================================================================

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_SUSPENDED = "SUSPENDED";
    public static final String STATUS_RETURNED = "RETURNED";

    // ========================================================================
    // Reference type constants
    // ========================================================================

    public static final String REF_TYPE_INVOICE = "INVOICE";
    public static final String REF_TYPE_ORDER = "ORDER";
    public static final String REF_TYPE_CUSTOMER = "CUSTOMER";
    public static final String REF_TYPE_POLICY = "POLICY";
    public static final String REF_TYPE_SUBSCRIPTION = "SUBSCRIPTION";
    public static final String REF_TYPE_TERMINAL = "TERMINAL";
    public static final String REF_TYPE_BATCH = "BATCH";
    public static final String REF_TYPE_SELLER = "SELLER";
    public static final String REF_TYPE_MERCHANT = "MERCHANT";

    // ========================================================================
    // Helper methods - Status
    // ========================================================================

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status) || STATUS_PARTIAL.equals(status);
    }

    public boolean isPaid() {
        return STATUS_PAID.equals(status);
    }

    public boolean isExpired() {
        if (STATUS_EXPIRED.equals(status)) return true;
        if (validUntil == null) return false;
        return LocalDateTime.now().isAfter(validUntil);
    }

    public boolean isCancelled() {
        return STATUS_CANCELLED.equals(status);
    }

    public boolean isSuspended() {
        return STATUS_SUSPENDED.equals(status);
    }

    public boolean isReturned() {
        return STATUS_RETURNED.equals(status);
    }

    // ========================================================================
    // Helper methods - Type
    // ========================================================================

    public boolean isPermanent() {
        return vibanType == VibanType.PRIMARY || vibanType == VibanType.CUSTOMER;
    }

    public boolean isFromPool() {
        return poolId != null;
    }

    public boolean hasReference() {
        return referenceType != null && referenceId != null;
    }

    // ========================================================================
    // Helper methods - Validation
    // ========================================================================

    /**
     * Check if this VIBAN can accept payments.
     */
    public boolean canAcceptPayment() {
        if (!isActive()) return false;
        if (isExpired()) return false;
        if (singleUse && timesUsed > 0) return false;
        return true;
    }

    /**
     * Check if payment amount matches expected (within tolerance).
     */
    public boolean matchesAmount(BigDecimal paymentAmount) {
        if (expectedAmount == null) return true;
        if (paymentAmount == null) return false;

        // Calculate tolerance
        BigDecimal tolerancePercent = amountTolerancePercent != null ? amountTolerancePercent : BigDecimal.ZERO;
        BigDecimal toleranceFixed = amountToleranceFixed != null ? amountToleranceFixed : BigDecimal.ZERO;

        BigDecimal percentTolerance = expectedAmount.multiply(tolerancePercent)
            .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
        BigDecimal totalTolerance = percentTolerance.max(toleranceFixed);

        BigDecimal lower = expectedAmount.subtract(totalTolerance);
        BigDecimal upper = expectedAmount.add(totalTolerance);

        return paymentAmount.compareTo(lower) >= 0 && paymentAmount.compareTo(upper) <= 0;
    }

    /**
     * Check if payment amount is within min/max bounds.
     */
    public boolean isAmountInBounds(BigDecimal paymentAmount) {
        if (paymentAmount == null) return false;
        if (minAmount != null && paymentAmount.compareTo(minAmount) < 0) return false;
        if (maxAmount != null && paymentAmount.compareTo(maxAmount) > 0) return false;
        return true;
    }

    /**
     * Get remaining amount to reach expected.
     */
    public BigDecimal getRemainingAmount() {
        if (expectedAmount == null) return null;
        BigDecimal received = totalAmountReceived != null ? totalAmountReceived : BigDecimal.ZERO;
        return expectedAmount.subtract(received).max(BigDecimal.ZERO);
    }

    /**
     * Get percentage of expected amount received.
     */
    public BigDecimal getPaymentProgress() {
        if (expectedAmount == null || expectedAmount.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        BigDecimal received = totalAmountReceived != null ? totalAmountReceived : BigDecimal.ZERO;
        return received.multiply(BigDecimal.valueOf(100))
            .divide(expectedAmount, 2, java.math.RoundingMode.HALF_UP);
    }

    // ========================================================================
    // Lifecycle methods
    // ========================================================================

    /**
     * Record a payment received on this VIBAN.
     */
    public void recordPayment(BigDecimal amount) {
        this.timesUsed = (this.timesUsed != null ? this.timesUsed : 0) + 1;
        this.totalAmountReceived = (this.totalAmountReceived != null ? this.totalAmountReceived : BigDecimal.ZERO)
            .add(amount);
        this.lastUsedAt = LocalDateTime.now();
        this.lastPaymentAmount = amount;

        // Update status
        if (singleUse) {
            this.status = STATUS_PAID;
        } else if (expectedAmount != null) {
            if (totalAmountReceived.compareTo(expectedAmount) >= 0) {
                this.status = STATUS_PAID;
            } else if (totalAmountReceived.compareTo(BigDecimal.ZERO) > 0) {
                this.status = STATUS_PARTIAL;
            }
        }
    }

    /**
     * Mark as expired.
     */
    public void expire() {
        this.status = STATUS_EXPIRED;
    }

    /**
     * Return to pool.
     */
    public void returnToPool() {
        this.status = STATUS_RETURNED;
        this.virtualAccountId = null;
        this.referenceType = null;
        this.referenceId = null;
        this.referenceDescription = null;
        this.customerName = null;
        this.customerReference = null;
        this.purpose = null;
        this.timesUsed = 0;
        this.totalAmountReceived = BigDecimal.ZERO;
        this.lastUsedAt = null;
        this.lastPaymentAmount = null;
    }

    // ========================================================================
    // Validation
    // ========================================================================

    @PrePersist
    @PreUpdate
    private void validate() {
        if (viban == null || viban.trim().isEmpty()) {
            throw new IllegalArgumentException("VIBAN is required");
        }

        if (vibanType == null) {
            throw new IllegalArgumentException("VIBAN type is required");
        }

        if (vibanType == VibanType.PRIMARY) {
            isPrimary = true;
        }

        if (isPrimary != null && isPrimary && vibanType != VibanType.PRIMARY) {
            throw new IllegalArgumentException("Only PRIMARY type VIBANs can be marked as primary");
        }

        // Set defaults
        if (status == null) {
            status = STATUS_ACTIVE;
        }
        if (validFrom == null) {
            validFrom = LocalDateTime.now();
        }
        if (timesUsed == null) {
            timesUsed = 0;
        }
        if (totalAmountReceived == null) {
            totalAmountReceived = BigDecimal.ZERO;
        }
        if (amountTolerancePercent == null) {
            amountTolerancePercent = BigDecimal.ZERO;
        }
        if (singleUse == null) {
            singleUse = false;
        }
        if (isPrimary == null) {
            isPrimary = false;
        }
    }

    // ========================================================================
    // Builder factory methods
    // ========================================================================

    /**
     * Create a PRIMARY VIBAN for a VA.
     */
    public static Viban createPrimary(UUID programId, UUID virtualAccountId, 
                                       String vibanString, String currencyCode) {
        return Viban.builder()
            .viban(vibanString)
            .virtualAccountId(virtualAccountId)
            .programId(programId)
            .vibanType(VibanType.PRIMARY)
            .isPrimary(true)
            .currencyCode(currencyCode)
            .status(STATUS_ACTIVE)
            .validFrom(LocalDateTime.now())
            .build();
    }

    /**
     * Create an INVOICE VIBAN for auto-reconciliation.
     */
    public static Viban createForInvoice(UUID programId, UUID virtualAccountId, 
                                          String vibanString, String invoiceId, 
                                          BigDecimal invoiceAmount, String currencyCode,
                                          LocalDateTime expiryDate) {
        return Viban.builder()
            .viban(vibanString)
            .virtualAccountId(virtualAccountId)
            .programId(programId)
            .vibanType(VibanType.INVOICE)
            .referenceType(REF_TYPE_INVOICE)
            .referenceId(invoiceId)
            .expectedAmount(invoiceAmount)
            .currencyCode(currencyCode)
            .status(STATUS_ACTIVE)
            .validFrom(LocalDateTime.now())
            .validUntil(expiryDate)
            .singleUse(false)
            .build();
    }

    /**
     * Create an ORDER VIBAN for e-commerce.
     */
    public static Viban createForOrder(UUID programId, UUID virtualAccountId,
                                        String vibanString, String orderId,
                                        BigDecimal orderAmount, String currencyCode) {
        return Viban.builder()
            .viban(vibanString)
            .virtualAccountId(virtualAccountId)
            .programId(programId)
            .vibanType(VibanType.ORDER)
            .referenceType(REF_TYPE_ORDER)
            .referenceId(orderId)
            .expectedAmount(orderAmount)
            .currencyCode(currencyCode)
            .status(STATUS_ACTIVE)
            .validFrom(LocalDateTime.now())
            .singleUse(true)
            .build();
    }

    /**
     * Create a CUSTOMER VIBAN (permanent).
     */
    public static Viban createForCustomer(UUID programId, UUID virtualAccountId,
                                           String vibanString, String customerId,
                                           String customerName, String currencyCode) {
        return Viban.builder()
            .viban(vibanString)
            .virtualAccountId(virtualAccountId)
            .programId(programId)
            .vibanType(VibanType.CUSTOMER)
            .referenceType(REF_TYPE_CUSTOMER)
            .referenceId(customerId)
            .customerName(customerName)
            .customerReference(customerId)
            .currencyCode(currencyCode)
            .status(STATUS_ACTIVE)
            .validFrom(LocalDateTime.now())
            .build();
    }
}