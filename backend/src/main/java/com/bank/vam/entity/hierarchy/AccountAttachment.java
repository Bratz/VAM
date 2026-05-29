package com.bank.vam.entity.hierarchy;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Account Attachment - Links Virtual Accounts to Legal Entities.
 * 
 * UNIFIED ARCHITECTURE v4.2:
 * This entity manages the many-to-many relationship between VirtualAccounts
 * and LegalEntities, allowing:
 * - One VA to be owned by one entity (OWNER relationship)
 * - One VA to be used by multiple entities (BENEFICIARY, AUTHORIZED)
 * - Tracking of relationship validity periods
 * 
 * Relationship Types:
 * - OWNER: Primary owner of the account
 * - BENEFICIARY: Beneficial owner (different from legal owner)
 * - AUTHORIZED: Authorized to transact on the account
 * - GUARANTOR: Provides guarantee for the account
 * - COLLATERAL: Account used as collateral
 * 
 * Domain Model:
 * - VirtualAccount (1) -> AccountAttachment (N)
 * - LegalEntity (1) -> AccountAttachment (N)
 * - AccountAttachment links VA to Entity with relationship metadata
 */
@Entity
@Table(name = "account_attachments",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uq_va_entity_relationship",
            columnNames = {"virtual_account_id", "legal_entity_id", "relationship_type"}
        )
    },
    indexes = {
        @Index(name = "idx_aa_va", columnList = "virtual_account_id"),
        @Index(name = "idx_aa_entity", columnList = "legal_entity_id"),
        @Index(name = "idx_aa_relationship", columnList = "relationship_type"),
        @Index(name = "idx_aa_primary", columnList = "is_primary"),
        @Index(name = "idx_aa_effective", columnList = "effective_from, effective_to")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountAttachment extends BaseEntity {

    // ========================================================================
    // LINKS
    // ========================================================================

    /**
     * Reference to the virtual account.
     */
    @Column(name = "virtual_account_id", nullable = false)
    private UUID virtualAccountId;

    /**
     * Reference to the legal entity.
     */
    @Column(name = "legal_entity_id", nullable = false)
    private UUID legalEntityId;

    // ========================================================================
    // RELATIONSHIP DETAILS
    // ========================================================================

    /**
     * Type of relationship between entity and account.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "relationship_type", nullable = false, length = 30)
    @Builder.Default
    private RelationshipType relationshipType = RelationshipType.OWNER;

    /**
     * Whether this is the primary relationship for this account.
     * Only one OWNER relationship per account should be primary.
     */
    @Column(name = "is_primary")
    @Builder.Default
    private Boolean isPrimary = true;

    /**
     * Description or notes about this relationship.
     */
    @Column(name = "description", length = 500)
    private String description;

    // ========================================================================
    // VALIDITY
    // ========================================================================

    /**
     * Date from which this relationship is effective.
     */
    @Column(name = "effective_from", nullable = false)
    @Builder.Default
    private LocalDate effectiveFrom = LocalDate.now();

    /**
     * Date until which this relationship is effective.
     * Null means indefinite.
     */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    // ========================================================================
    // AUTHORIZATION DETAILS (for AUTHORIZED relationship)
    // ========================================================================

    /**
     * Maximum transaction amount authorized.
     */
    @Column(name = "max_transaction_amount", precision = 19, scale = 4)
    private java.math.BigDecimal maxTransactionAmount;

    /**
     * Daily transaction limit authorized.
     */
    @Column(name = "daily_limit", precision = 19, scale = 4)
    private java.math.BigDecimal dailyLimit;

    /**
     * Types of transactions authorized (JSON array).
     * Example: ["CREDIT", "DEBIT", "TRANSFER"]
     */
    @Column(name = "authorized_transaction_types", length = 200)
    private String authorizedTransactionTypes;

    /**
     * Whether dual authorization is required.
     */
    @Column(name = "requires_dual_auth")
    @Builder.Default
    private Boolean requiresDualAuth = false;

    // ========================================================================
    // COLLATERAL DETAILS (for COLLATERAL relationship)
    // ========================================================================

    /**
     * Collateral value percentage (e.g., 100% means full account balance).
     */
    @Column(name = "collateral_percent", precision = 5, scale = 2)
    private java.math.BigDecimal collateralPercent;

    /**
     * Reference to the loan/facility this collateral secures.
     */
    @Column(name = "secured_facility_id")
    private UUID securedFacilityId;

    // ========================================================================
    // STATUS
    // ========================================================================

    /**
     * Status of this attachment.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    @Builder.Default
    private AttachmentStatus status = AttachmentStatus.ACTIVE;

    // ========================================================================
    // AUDIT
    // ========================================================================

    /**
     * Who approved this attachment.
     */
    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    /**
     * When this attachment was approved.
     */
    @Column(name = "approved_at")
    private java.time.LocalDateTime approvedAt;

    // ========================================================================
    // ENUMS
    // ========================================================================

    /**
     * Type of relationship between entity and account.
     */
    public enum RelationshipType {
        OWNER,          // Primary owner of the account
        BENEFICIARY,    // Beneficial owner (different from legal owner)
        AUTHORIZED,     // Authorized to transact on the account
        GUARANTOR,      // Provides guarantee for the account
        COLLATERAL      // Account used as collateral
    }

    /**
     * Status of the attachment.
     */
    public enum AttachmentStatus {
        ACTIVE,             // Currently active
        PENDING_APPROVAL,   // Waiting for approval
        SUSPENDED,          // Temporarily suspended
        EXPIRED,            // Validity period expired
        TERMINATED          // Manually terminated
    }

    // ========================================================================
    // HELPER METHODS - Status
    // ========================================================================

    /**
     * Check if attachment is active.
     */
    public boolean isActive() {
        return status == AttachmentStatus.ACTIVE;
    }

    /**
     * Check if attachment is pending approval.
     */
    public boolean isPendingApproval() {
        return status == AttachmentStatus.PENDING_APPROVAL;
    }

    /**
     * Check if attachment is suspended.
     */
    public boolean isSuspended() {
        return status == AttachmentStatus.SUSPENDED;
    }

    /**
     * Check if attachment is terminated.
     */
    public boolean isTerminated() {
        return status == AttachmentStatus.TERMINATED;
    }

    // ========================================================================
    // HELPER METHODS - Relationship Type
    // ========================================================================

    /**
     * Check if this is an owner relationship.
     */
    public boolean isOwner() {
        return relationshipType == RelationshipType.OWNER;
    }

    /**
     * Check if this is a beneficiary relationship.
     */
    public boolean isBeneficiary() {
        return relationshipType == RelationshipType.BENEFICIARY;
    }

    /**
     * Check if this is an authorized relationship.
     */
    public boolean isAuthorized() {
        return relationshipType == RelationshipType.AUTHORIZED;
    }

    /**
     * Check if this is a guarantor relationship.
     */
    public boolean isGuarantor() {
        return relationshipType == RelationshipType.GUARANTOR;
    }

    /**
     * Check if this is a collateral relationship.
     */
    public boolean isCollateral() {
        return relationshipType == RelationshipType.COLLATERAL;
    }

    // ========================================================================
    // HELPER METHODS - Validity
    // ========================================================================

    /**
     * Check if attachment is currently valid (within effective dates).
     */
    public boolean isCurrentlyValid() {
        LocalDate today = LocalDate.now();
        if (effectiveFrom != null && today.isBefore(effectiveFrom)) {
            return false;
        }
        if (effectiveTo != null && today.isAfter(effectiveTo)) {
            return false;
        }
        return true;
    }

    /**
     * Check if attachment is effective and active.
     */
    public boolean isEffective() {
        return isActive() && isCurrentlyValid();
    }

    /**
     * Check if attachment will expire within given days.
     */
    public boolean willExpireWithin(int days) {
        if (effectiveTo == null) {
            return false; // No expiry date
        }
        LocalDate expiryThreshold = LocalDate.now().plusDays(days);
        return effectiveTo.isBefore(expiryThreshold) || effectiveTo.isEqual(expiryThreshold);
    }

    // ========================================================================
    // HELPER METHODS - Authorization
    // ========================================================================

    /**
     * Check if transaction amount is within authorized limit.
     */
    public boolean isWithinTransactionLimit(java.math.BigDecimal amount) {
        if (maxTransactionAmount == null) {
            return true; // No limit
        }
        return amount.compareTo(maxTransactionAmount) <= 0;
    }

    /**
     * Check if daily amount is within authorized limit.
     */
    public boolean isWithinDailyLimit(java.math.BigDecimal dailyTotal) {
        if (dailyLimit == null) {
            return true; // No limit
        }
        return dailyTotal.compareTo(dailyLimit) <= 0;
    }

    // ========================================================================
    // LIFECYCLE METHODS
    // ========================================================================

    /**
     * Approve this attachment.
     */
    public void approve(String approver) {
        this.status = AttachmentStatus.ACTIVE;
        this.approvedBy = approver;
        this.approvedAt = java.time.LocalDateTime.now();
    }

    /**
     * Suspend this attachment.
     */
    public void suspend() {
        this.status = AttachmentStatus.SUSPENDED;
    }

    /**
     * Terminate this attachment.
     */
    public void terminate() {
        this.status = AttachmentStatus.TERMINATED;
        this.effectiveTo = LocalDate.now();
    }

    /**
     * Reactivate a suspended attachment.
     */
    public void reactivate() {
        if (status == AttachmentStatus.SUSPENDED) {
            this.status = AttachmentStatus.ACTIVE;
        }
    }

    // ========================================================================
    // BUILDER FACTORY METHODS
    // ========================================================================

    /**
     * Create an owner attachment.
     */
    public static AccountAttachment createOwner(UUID virtualAccountId, UUID legalEntityId) {
        return AccountAttachment.builder()
            .virtualAccountId(virtualAccountId)
            .legalEntityId(legalEntityId)
            .relationshipType(RelationshipType.OWNER)
            .isPrimary(true)
            .status(AttachmentStatus.ACTIVE)
            .effectiveFrom(LocalDate.now())
            .build();
    }

    /**
     * Create a beneficiary attachment.
     */
    public static AccountAttachment createBeneficiary(UUID virtualAccountId, UUID legalEntityId) {
        return AccountAttachment.builder()
            .virtualAccountId(virtualAccountId)
            .legalEntityId(legalEntityId)
            .relationshipType(RelationshipType.BENEFICIARY)
            .isPrimary(false)
            .status(AttachmentStatus.ACTIVE)
            .effectiveFrom(LocalDate.now())
            .build();
    }

    /**
     * Create an authorized attachment with limits.
     */
    public static AccountAttachment createAuthorized(UUID virtualAccountId, UUID legalEntityId,
                                                      java.math.BigDecimal maxTransaction,
                                                      java.math.BigDecimal dailyLimit) {
        return AccountAttachment.builder()
            .virtualAccountId(virtualAccountId)
            .legalEntityId(legalEntityId)
            .relationshipType(RelationshipType.AUTHORIZED)
            .isPrimary(false)
            .maxTransactionAmount(maxTransaction)
            .dailyLimit(dailyLimit)
            .status(AttachmentStatus.PENDING_APPROVAL)
            .effectiveFrom(LocalDate.now())
            .build();
    }

    /**
     * Create a collateral attachment.
     */
    public static AccountAttachment createCollateral(UUID virtualAccountId, UUID legalEntityId,
                                                      UUID securedFacilityId,
                                                      java.math.BigDecimal collateralPercent) {
        return AccountAttachment.builder()
            .virtualAccountId(virtualAccountId)
            .legalEntityId(legalEntityId)
            .relationshipType(RelationshipType.COLLATERAL)
            .isPrimary(false)
            .securedFacilityId(securedFacilityId)
            .collateralPercent(collateralPercent)
            .status(AttachmentStatus.ACTIVE)
            .effectiveFrom(LocalDate.now())
            .build();
    }
}