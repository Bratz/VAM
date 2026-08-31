package com.bank.vam.entity.party;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Party Entity - Enhanced with POBO/COBO and Intercompany Support
 * 
 * Phase 1 Enhancement: Enables Payment Factory capabilities:
 * - Entity context (which legal entity owns this party relationship)
 * - POBO eligibility (can treasury pay on behalf of subsidiary for this vendor?)
 * - Intercompany identification (is this party actually a group entity?)
 * - Netting eligibility (can transactions with this party be netted?)
 * 
 * @see <a href="https://www.capgemini.com/wp-content/uploads/2020/12/Virtual-Acc-Management-in-Transaction-Banking.pdf">
 *      Capgemini VAM Reference</a>
 */
@Entity
@Table(name = "parties", indexes = {
    @Index(name = "idx_party_code", columnList = "party_code"),
    @Index(name = "idx_party_corporate", columnList = "corporate_id"),
    @Index(name = "idx_party_status", columnList = "status"),
    @Index(name = "idx_party_kyc_status", columnList = "kyc_status"),
    // Phase 1: POBO/Intercompany indexes
    @Index(name = "idx_party_owning_entity", columnList = "owning_entity_id"),
    @Index(name = "idx_party_intercompany", columnList = "is_intercompany"),
    @Index(name = "idx_party_linked_entity", columnList = "linked_legal_entity_id"),
    @Index(name = "idx_party_pobo_eligible", columnList = "pobo_eligible"),
    @Index(name = "idx_party_netting_eligible", columnList = "netting_eligible")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Party extends BaseEntity {

    @Column(name = "corporate_id", nullable = false)
    private UUID corporateId;

    @Column(name = "party_code", nullable = false, unique = true, length = 30)
    private String partyCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "party_type", nullable = false, length = 30)
    private PartyType partyType;

    @Column(name = "legal_name", nullable = false)
    private String legalName;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "trade_name")
    private String tradeName;

    // ========================================================================
    // PHASE 1: ENTITY CONTEXT & POBO FIELDS
    // ========================================================================
    
    /**
     * The legal entity (subsidiary) that "owns" this party relationship.
     * For example, if SUB-DUBAI has a vendor relationship with "Emirates Steel",
     * owningEntityId = SUB-DUBAI's legal entity ID.
     * 
     * This enables:
     * - Multi-entity corporate structures
     * - POBO routing (Treasury pays on behalf of owning entity)
     * - Proper cost allocation
     */
    @Column(name = "owning_entity_id")
    private UUID owningEntityId;
    
    /**
     * Code of the owning legal entity for display purposes.
     * Denormalized for query performance.
     */
    @Column(name = "owning_entity_code", length = 20)
    private String owningEntityCode;
    
    /**
     * Whether this party (vendor/customer) is eligible for POBO/COBO.
     * Treasury must approve POBO-eligible vendors.
     * 
     * For vendors: Can treasury pay this vendor on behalf of subsidiaries?
     * For customers: Can treasury collect from this customer on behalf of subsidiaries?
     */
    @Column(name = "pobo_eligible")
    @Builder.Default
    private Boolean poboEligible = false;
    
    /**
     * Default paying entity for POBO payments to this vendor.
     * Usually the corporate HQ treasury.
     * If null, requires explicit selection during payment creation.
     */
    @Column(name = "pobo_default_payer_entity_id")
    private UUID poboDefaultPayerEntityId;
    
    /**
     * Default paying entity code for display.
     */
    @Column(name = "pobo_default_payer_entity_code", length = 20)
    private String poboDefaultPayerEntityCode;
    
    /**
     * Whether this party is actually a group entity (intercompany).
     * When true, this party represents another legal entity within the corporate group.
     * 
     * Use cases:
     * - SUB-SINGAPORE creates a payable to SUB-DUBAI → is_intercompany = true
     * - Any entity creates a payable to external vendor → is_intercompany = false
     * 
     * Intercompany parties are automatically netting-eligible.
     */
    @Column(name = "is_intercompany")
    @Builder.Default
    private Boolean isIntercompany = false;
    
    /**
     * If this party is intercompany (is_intercompany=true), this links to the
     * actual legal entity it represents.
     * 
     * Example: Party "SUB-DUBAI (Vendor)" → linkedLegalEntityId = SUB-DUBAI's entity ID
     * 
     * This enables:
     * - Proper netting between entities
     * - Automatic IC receivable/payable matching
     * - Transfer pricing compliance
     */
    @Column(name = "linked_legal_entity_id")
    private UUID linkedLegalEntityId;
    
    /**
     * Code of the linked legal entity for display.
     */
    @Column(name = "linked_legal_entity_code", length = 20)
    private String linkedLegalEntityCode;
    
    /**
     * Whether transactions with this party can participate in netting cycles.
     * Automatically true for intercompany parties.
     * Can also be enabled for strategic external vendors with bilateral trade.
     */
    @Column(name = "netting_eligible")
    @Builder.Default
    private Boolean nettingEligible = false;
    
    /**
     * Default settlement method for intercompany transactions.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "ic_settlement_method", length = 30)
    @Builder.Default
    private IntercompanySettlementMethod icSettlementMethod = IntercompanySettlementMethod.NETTING;
    
    /**
     * Credit limit for intercompany exposure with this party.
     * Only applicable when is_intercompany = true.
     */
    @Column(name = "ic_credit_limit", precision = 19, scale = 4)
    private BigDecimal icCreditLimit;
    
    /**
     * Current intercompany exposure (outstanding payables - outstanding receivables).
     * Updated by triggers/batch jobs.
     */
    @Column(name = "ic_current_exposure", precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal icCurrentExposure = BigDecimal.ZERO;
    
    /**
     * Intercompany position currency.
     */
    @Column(name = "ic_currency", length = 3)
    private String icCurrency;
    
    // ========================================================================
    // EXISTING FIELDS (Preserved for backward compatibility)
    // ========================================================================

    // Tax & Registration
    @Column(name = "tax_id", length = 50)
    private String taxId;

    @Column(name = "registration_number", length = 50)
    private String registrationNumber;

    @Column(name = "registration_country", length = 2, nullable = false)
    private String registrationCountry;

    // Contact Information
    @Column(name = "contact_name")
    private String contactName;

    @Column(name = "contact_email")
    private String contactEmail;

    @Column(name = "contact_phone", length = 30)
    private String contactPhone;

    // Address
    @Column(name = "address_line1")
    private String addressLine1;

    @Column(name = "address_line2")
    private String addressLine2;

    @Column(name = "city")
    private String city;

    @Column(name = "state")
    private String state;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    @Column(name = "country", length = 2, nullable = false)
    private String country;

    // E-commerce
    @Column(name = "ecommerce_enabled")
    @Builder.Default
    private Boolean ecommerceEnabled = false;

    @Column(name = "ecommerce_platforms")
    private String ecommercePlatforms; // Comma-separated: AMAZON,NOON,SHOPIFY

    // Employee-specific
    @Column(name = "employee_id", length = 30)
    private String employeeId;

    @Column(name = "department")
    private String department;

    // Compliance
    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_status", nullable = false, length = 20)
    @Builder.Default
    private KycStatus kycStatus = KycStatus.PENDING;

    @Column(name = "kyc_expires_at")
    private LocalDate kycExpiresAt;

    @Column(name = "kyc_verified_at")
    private LocalDateTime kycVerifiedAt;

    @Column(name = "kyc_verified_by")
    private String kycVerifiedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_rating", nullable = false, length = 20)
    @Builder.Default
    private RiskRating riskRating = RiskRating.MEDIUM;

    @Column(name = "risk_score")
    private Integer riskScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "sanctions_status", length = 20)
    @Builder.Default
    private SanctionsStatus sanctionsStatus = SanctionsStatus.CLEAR;

    @Column(name = "sanctions_last_checked")
    private LocalDateTime sanctionsLastChecked;

    @Column(name = "pep_status")
    @Builder.Default
    private Boolean pepStatus = false;

    @Column(name = "adverse_media_status")
    @Builder.Default
    private Boolean adverseMediaStatus = false;

    // Status
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private PartyStatus status = PartyStatus.ACTIVE;

    // Activity tracking
    @Column(name = "onboarded_at")
    private LocalDateTime onboardedAt;

    @Column(name = "last_transaction_at")
    private LocalDateTime lastTransactionAt;

    // Roles (stored as comma-separated for simplicity, or use join table)
    @Column(name = "roles", nullable = false)
    private String roles; // Comma-separated: CUSTOMER,VENDOR

    // Relationships
    @OneToMany(mappedBy = "party", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private Set<PartyBankAccount> bankAccounts = new HashSet<>();

    @OneToMany(mappedBy = "party", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private Set<PartyDocument> documents = new HashSet<>();

    // ========================================================================
    // ENUMS
    // ========================================================================
    
    public enum PartyType {
        INDIVIDUAL, 
        COMPANY, 
        GOVERNMENT, 
        FINANCIAL_INSTITUTION
    }

    public enum PartyStatus {
        ACTIVE, 
        SUSPENDED, 
        BLOCKED, 
        INACTIVE
    }

    public enum KycStatus {
        PENDING, 
        IN_PROGRESS, 
        VERIFIED, 
        EXPIRED, 
        REJECTED, 
        EXEMPTED
    }

    public enum RiskRating {
        LOW, 
        MEDIUM, 
        HIGH, 
        PROHIBITED
    }

    public enum SanctionsStatus {
        CLEAR, 
        POTENTIAL_MATCH, 
        FALSE_POSITIVE, 
        CONFIRMED_MATCH
    }

    public enum PartyRole {
        CUSTOMER, 
        VENDOR, 
        EMPLOYEE, 
        GOVERNMENT, 
        FINANCIAL
    }
    
    /**
     * Phase 1: Intercompany settlement methods
     */
    public enum IntercompanySettlementMethod {
        /** Include in monthly/quarterly netting cycle */
        NETTING,
        /** Direct VA-to-VA transfer */
        DIRECT_TRANSFER,
        /** Create IHB loan/deposit */
        IHB,
        /** Manual settlement outside system */
        MANUAL
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================
    
    public Set<PartyRole> getRolesSet() {
        Set<PartyRole> roleSet = new HashSet<>();
        if (roles != null && !roles.isEmpty()) {
            for (String role : roles.split(",")) {
                try {
                    roleSet.add(PartyRole.valueOf(role.trim()));
                } catch (IllegalArgumentException ignored) {}
            }
        }
        return roleSet;
    }

    public void setRolesFromSet(Set<PartyRole> roleSet) {
        if (roleSet == null || roleSet.isEmpty()) {
            this.roles = "";
        } else {
            this.roles = roleSet.stream()
                .map(Enum::name)
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        }
    }

    public int getBankAccountsCount() {
        return bankAccounts != null ? bankAccounts.size() : 0;
    }

    public int getDocumentsCount() {
        return documents != null ? documents.size() : 0;
    }
    
    // ========================================================================
    // PHASE 1: POBO/IC HELPER METHODS
    // ========================================================================
    
    /**
     * Check if this party is eligible for POBO payments.
     * Must be active, KYC verified, and explicitly marked as POBO eligible.
     */
    public boolean canReceivePoboPayment() {
        return Boolean.TRUE.equals(poboEligible) 
            && status == PartyStatus.ACTIVE 
            && (kycStatus == KycStatus.VERIFIED || kycStatus == KycStatus.EXEMPTED);
    }
    
    /**
     * Check if this party represents an intercompany relationship.
     */
    public boolean isGroupEntity() {
        return Boolean.TRUE.equals(isIntercompany) && linkedLegalEntityId != null;
    }
    
    /**
     * Check if transactions with this party can be included in netting.
     * Intercompany parties are always netting-eligible.
     */
    public boolean canParticipateInNetting() {
        return Boolean.TRUE.equals(nettingEligible) || isGroupEntity();
    }
    
    /**
     * Check if intercompany credit limit is exceeded.
     * Only applicable for intercompany parties with credit limits.
     */
    public boolean isIcCreditLimitExceeded() {
        if (!isGroupEntity() || icCreditLimit == null) {
            return false;
        }
        return icCurrentExposure != null && icCurrentExposure.compareTo(icCreditLimit) > 0;
    }
    
    /**
     * Get available intercompany credit.
     */
    public BigDecimal getAvailableIcCredit() {
        if (!isGroupEntity() || icCreditLimit == null) {
            return null;
        }
        BigDecimal exposure = icCurrentExposure != null ? icCurrentExposure : BigDecimal.ZERO;
        return icCreditLimit.subtract(exposure);
    }
    
    /**
     * Check if this is a vendor party.
     */
    public boolean isVendor() {
        return roles != null && roles.contains(PartyRole.VENDOR.name());
    }
    
    /**
     * Check if this is a customer party.
     */
    public boolean isCustomer() {
        return roles != null && roles.contains(PartyRole.CUSTOMER.name());
    }
}