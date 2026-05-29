package com.bank.vam.entity.pobo;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * POBO Authorization Entity - Manages Pay On Behalf Of authorizations.
 * 
 * Controls:
 * - Which entities can pay on behalf of which subsidiaries
 * - Payment limits (single, daily, monthly)
 * - Allowed payment types and vendors
 * - Recharge settings
 * 
 * VAM Integration:
 * - Validated before POBO payment execution
 * - Tracks usage against limits
 * - Auto-reset of daily/monthly limits
 */
@Entity
@Table(name = "pobo_authorizations", indexes = {
    @Index(name = "idx_pobo_auth_payer", columnList = "payer_entity_id"),
    @Index(name = "idx_pobo_auth_behalf", columnList = "behalf_entity_id"),
    @Index(name = "idx_pobo_auth_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PoboAuthorization extends BaseEntity {

    // ========================================================================
    // IDENTIFICATION
    // ========================================================================

    @Column(name = "authorization_code", nullable = false, unique = true, length = 50)
    private String authorizationCode;

    // ========================================================================
    // PAYER (Who Can Pay On Behalf)
    // ========================================================================

    @Column(name = "payer_entity_id", nullable = false)
    private UUID payerEntityId;

    @Column(name = "payer_entity_code", nullable = false, length = 50)
    private String payerEntityCode;

    @Column(name = "payer_va_id")
    private UUID payerVaId;

    // ========================================================================
    // BEHALF (On Whose Behalf)
    // ========================================================================

    @Column(name = "behalf_entity_id", nullable = false)
    private UUID behalfEntityId;

    @Column(name = "behalf_entity_code", nullable = false, length = 50)
    private String behalfEntityCode;

    @Column(name = "behalf_va_id")
    private UUID behalfVaId;

    // ========================================================================
    // LIMITS
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "authorization_type", length = 30)
    @Builder.Default
    private AuthorizationType authorizationType = AuthorizationType.FULL;

    @Column(name = "single_payment_limit", precision = 18, scale = 2)
    private BigDecimal singlePaymentLimit;

    @Column(name = "daily_limit", precision = 18, scale = 2)
    private BigDecimal dailyLimit;

    @Column(name = "monthly_limit", precision = 18, scale = 2)
    private BigDecimal monthlyLimit;

    @Column(name = "currency_code", length = 3)
    @Builder.Default
    private String currencyCode = "AED";

    // ========================================================================
    // USAGE TRACKING
    // ========================================================================

    @Column(name = "used_today", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal usedToday = BigDecimal.ZERO;

    @Column(name = "used_this_month", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal usedThisMonth = BigDecimal.ZERO;

    @Column(name = "last_reset_date")
    @Builder.Default
    private LocalDate lastResetDate = LocalDate.now();

    // ========================================================================
    // ALLOWED TYPES
    // ========================================================================

    @Column(name = "allowed_payment_types", length = 200)
    private String allowedPaymentTypes;

    @Column(name = "allowed_vendor_ids", columnDefinition = "TEXT")
    private String allowedVendorIds;

    // ========================================================================
    // RECHARGE SETTINGS
    // ========================================================================

    @Column(name = "auto_recharge")
    @Builder.Default
    private Boolean autoRecharge = true;

    @Column(name = "recharge_service_fee_rate", precision = 8, scale = 4)
    @Builder.Default
    private BigDecimal rechargeServiceFeeRate = BigDecimal.ZERO;

    @Column(name = "requires_approval")
    @Builder.Default
    private Boolean requiresApproval = true;

    // ========================================================================
    // VALIDITY
    // ========================================================================

    @Column(name = "effective_from", nullable = false)
    @Builder.Default
    private LocalDate effectiveFrom = LocalDate.now();

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    @Builder.Default
    private AuthorizationStatus status = AuthorizationStatus.ACTIVE;

    // ========================================================================
    // APPROVAL
    // ========================================================================

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    // ========================================================================
    // ENUMS
    // ========================================================================

    public enum AuthorizationType {
        FULL,               // Can pay anything
        LIMITED,            // Limited by amount/type
        SPECIFIC_VENDOR,    // Only specific vendors
        SPECIFIC_TYPE       // Only specific payment types
    }

    public enum AuthorizationStatus {
        ACTIVE,
        SUSPENDED,
        EXPIRED,
        REVOKED
    }

    // ========================================================================
    // VALIDATION METHODS
    // ========================================================================

    /**
     * Check if authorization is valid.
     */
    public boolean isValid() {
        if (status != AuthorizationStatus.ACTIVE) return false;
        
        LocalDate today = LocalDate.now();
        if (today.isBefore(effectiveFrom)) return false;
        if (effectiveTo != null && today.isAfter(effectiveTo)) return false;
        
        return true;
    }

    /**
     * Check if payment amount is within limits.
     */
    public boolean canAuthorize(BigDecimal amount) {
        if (!isValid()) return false;
        
        // Check single payment limit
        if (singlePaymentLimit != null && amount.compareTo(singlePaymentLimit) > 0) {
            return false;
        }
        
        // Reset if needed
        resetIfNeeded();
        
        // Check daily limit
        if (dailyLimit != null) {
            BigDecimal newDaily = usedToday.add(amount);
            if (newDaily.compareTo(dailyLimit) > 0) return false;
        }
        
        // Check monthly limit
        if (monthlyLimit != null) {
            BigDecimal newMonthly = usedThisMonth.add(amount);
            if (newMonthly.compareTo(monthlyLimit) > 0) return false;
        }
        
        return true;
    }

    /**
     * Check if payment type is allowed.
     */
    public boolean isPaymentTypeAllowed(String paymentType) {
        if (authorizationType == AuthorizationType.FULL) return true;
        if (allowedPaymentTypes == null || allowedPaymentTypes.isEmpty()) return true;
        
        return allowedPaymentTypes.contains(paymentType);
    }

    /**
     * Check if vendor is allowed.
     */
    public boolean isVendorAllowed(UUID vendorId) {
        if (authorizationType == AuthorizationType.FULL) return true;
        if (authorizationType != AuthorizationType.SPECIFIC_VENDOR) return true;
        if (allowedVendorIds == null || allowedVendorIds.isEmpty()) return true;
        
        return allowedVendorIds.contains(vendorId.toString());
    }

    /**
     * Record usage after successful payment.
     */
    public void recordUsage(BigDecimal amount) {
        resetIfNeeded();
        this.usedToday = usedToday.add(amount);
        this.usedThisMonth = usedThisMonth.add(amount);
    }

    /**
     * Reset counters if needed.
     */
    public void resetIfNeeded() {
        LocalDate today = LocalDate.now();
        
        // Reset daily
        if (!today.equals(lastResetDate)) {
            usedToday = BigDecimal.ZERO;
            lastResetDate = today;
        }
        
        // Reset monthly (first day of month)
        if (today.getDayOfMonth() == 1 && usedThisMonth.compareTo(BigDecimal.ZERO) > 0) {
            usedThisMonth = BigDecimal.ZERO;
        }
    }

    /**
     * Force reset all counters.
     */
    public void resetCounters() {
        this.usedToday = BigDecimal.ZERO;
        this.usedThisMonth = BigDecimal.ZERO;
        this.lastResetDate = LocalDate.now();
    }

    // ========================================================================
    // AVAILABLE LIMITS
    // ========================================================================

    /**
     * Get remaining daily limit.
     */
    public BigDecimal getRemainingDailyLimit() {
        if (dailyLimit == null) return null;
        resetIfNeeded();
        return dailyLimit.subtract(usedToday);
    }

    /**
     * Get remaining monthly limit.
     */
    public BigDecimal getRemainingMonthlyLimit() {
        if (monthlyLimit == null) return null;
        resetIfNeeded();
        return monthlyLimit.subtract(usedThisMonth);
    }

    /**
     * Get maximum available for next payment.
     */
    public BigDecimal getMaxAvailable() {
        BigDecimal max = singlePaymentLimit;
        
        BigDecimal dailyRemaining = getRemainingDailyLimit();
        if (dailyRemaining != null) {
            max = max == null ? dailyRemaining : max.min(dailyRemaining);
        }
        
        BigDecimal monthlyRemaining = getRemainingMonthlyLimit();
        if (monthlyRemaining != null) {
            max = max == null ? monthlyRemaining : max.min(monthlyRemaining);
        }
        
        return max;
    }

    // ========================================================================
    // STATUS METHODS
    // ========================================================================

    public void suspend(String reason) {
        this.status = AuthorizationStatus.SUSPENDED;
    }

    public void reactivate() {
        this.status = AuthorizationStatus.ACTIVE;
    }

    public void revoke(String reason) {
        this.status = AuthorizationStatus.REVOKED;
    }

    public void expire() {
        this.status = AuthorizationStatus.EXPIRED;
    }

    // ========================================================================
    // FACTORY METHODS
    // ========================================================================

    public static PoboAuthorization createFullAuthorization(
            String authCode,
            UUID payerEntityId, String payerCode,
            UUID behalfEntityId, String behalfCode) {
        
        return PoboAuthorization.builder()
            .authorizationCode(authCode)
            .payerEntityId(payerEntityId)
            .payerEntityCode(payerCode)
            .behalfEntityId(behalfEntityId)
            .behalfEntityCode(behalfCode)
            .authorizationType(AuthorizationType.FULL)
            .autoRecharge(true)
            .requiresApproval(false)
            .status(AuthorizationStatus.ACTIVE)
            .build();
    }

    public static PoboAuthorization createLimitedAuthorization(
            String authCode,
            UUID payerEntityId, String payerCode,
            UUID behalfEntityId, String behalfCode,
            BigDecimal singleLimit, BigDecimal dailyLimit, BigDecimal monthlyLimit) {
        
        return PoboAuthorization.builder()
            .authorizationCode(authCode)
            .payerEntityId(payerEntityId)
            .payerEntityCode(payerCode)
            .behalfEntityId(behalfEntityId)
            .behalfEntityCode(behalfCode)
            .authorizationType(AuthorizationType.LIMITED)
            .singlePaymentLimit(singleLimit)
            .dailyLimit(dailyLimit)
            .monthlyLimit(monthlyLimit)
            .autoRecharge(true)
            .requiresApproval(true)
            .status(AuthorizationStatus.ACTIVE)
            .build();
    }
}