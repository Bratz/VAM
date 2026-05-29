package com.bank.vam.entity.treasury;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * FX Rate - Stores foreign exchange rates for currency conversion.
 * 
 * UNIFIED ARCHITECTURE v4.2:
 * FX rates are used for:
 * - Currency mirror balance conversion to base currency
 * - Multi-currency aggregation at ROOT level
 * - Cross-border payment FX calculations
 * - Treasury position reporting
 * 
 * Rate Types (matches DB constraint):
 * - BID: Bid rate (bank buying rate)
 * - ASK: Ask rate (bank selling rate)
 * - MID: Mid-market rate (spot/default)
 * 
 * Rate Sources (matches DB constraint):
 * - CENTRAL_BANK: Central bank published rate
 * - REUTERS: Thomson Reuters
 * - BLOOMBERG: Bloomberg Terminal
 * - MANUAL: Manual entry
 * - INTERNAL: Internal transfer pricing rate
 * 
 * FIXED v5.1.2:
 * - Does NOT extend BaseEntity (fx_rates table lacks 'version' column)
 * - All audit fields defined directly
 * - Aligned column mappings with actual database schema
 * - Column name: 'source' (not 'rate_source')
 * - Added all factory methods: createSpotRate, createFixingRate, createInternalRate
 */
@Entity
@Table(name = "fx_rates",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "fx_rates_unique_rate",
            columnNames = {"from_currency", "to_currency", "rate_date", "rate_type", "source"}
        )
    },
    indexes = {
        @Index(name = "idx_fx_rates_active", columnList = "from_currency, to_currency, is_active"),
        @Index(name = "idx_fx_rates_approved_by", columnList = "approved_by"),
        @Index(name = "idx_fx_rates_corporate", columnList = "corporate_id, from_currency, to_currency"),
        @Index(name = "idx_fx_rates_currency_pair", columnList = "from_currency, to_currency, rate_date"),
        @Index(name = "idx_fx_rates_date_range", columnList = "rate_date, effective_from, effective_to"),
        @Index(name = "idx_fx_rates_source", columnList = "source, rate_date")
    }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FxRate {

    // ========================================================================
    // PRIMARY KEY - NOT from BaseEntity
    // ========================================================================

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // ========================================================================
    // CURRENCY PAIR
    // ========================================================================

    /**
     * Source currency (e.g., USD).
     */
    @Column(name = "from_currency", nullable = false, length = 3)
    private String fromCurrency;

    /**
     * Target currency (e.g., EUR).
     */
    @Column(name = "to_currency", nullable = false, length = 3)
    private String toCurrency;

    // ========================================================================
    // RATE VALUES
    // ========================================================================

    /**
     * Exchange rate (1 fromCurrency = rate toCurrency).
     * Example: 1 USD = 0.92 EUR means rate = 0.92
     */
    @Column(name = "rate", nullable = false, precision = 19, scale = 8)
    private BigDecimal rate;

    /**
     * Bid rate (buy rate - bank buying rate).
     */
    @Column(name = "bid_rate", precision = 19, scale = 8)
    private BigDecimal bidRate;

    /**
     * Ask rate (sell rate - bank selling rate).
     */
    @Column(name = "ask_rate", precision = 19, scale = 8)
    private BigDecimal askRate;

    // ========================================================================
    // RATE METADATA
    // ========================================================================

    /**
     * Date of the rate.
     */
    @Column(name = "rate_date", nullable = false)
    private LocalDate rateDate;

    /**
     * Time when rate was captured (optional).
     */
    @Column(name = "rate_time")
    private LocalTime rateTime;

    /**
     * Type of rate - BID, ASK, or MID.
     * MID is used as the "spot" rate for general conversions.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "rate_type", length = 10)
    @Builder.Default
    private RateType rateType = RateType.MID;

    /**
     * Source of the rate - matches DB constraint.
     * NOTE: Database column is 'source', not 'rate_source'
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 50)
    @Builder.Default
    private RateSource rateSource = RateSource.MANUAL;

    /**
     * Reference from the source (optional).
     */
    @Column(name = "source_reference", length = 100)
    private String sourceReference;

    // ========================================================================
    // VALIDITY
    // ========================================================================

    /**
     * When this rate becomes effective.
     */
    @Column(name = "effective_from")
    private LocalDateTime effectiveFrom;

    /**
     * When this rate expires.
     */
    @Column(name = "effective_to")
    private LocalDateTime effectiveTo;

    /**
     * Whether rate is currently active.
     */
    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    /**
     * Spread in basis points (optional).
     */
    @Column(name = "spread_bps", precision = 10, scale = 4)
    private BigDecimal spreadBps;

    // ========================================================================
    // SCOPE (Optional - for corporate-specific rates)
    // ========================================================================

    /**
     * Corporate ID if this is a corporate-specific rate.
     */
    @Column(name = "corporate_id")
    private UUID corporateId;

    /**
     * Program ID if this is a program-specific rate.
     */
    @Column(name = "program_id")
    private UUID programId;

    // ========================================================================
    // AUDIT FIELDS - Defined directly (not from BaseEntity)
    // ========================================================================

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    /**
     * Who approved this rate (for audit trail).
     */
    @Column(name = "approved_by", length = 255)
    private String approvedBy;

    // ========================================================================
    // NOTE: NO VERSION FIELD - fx_rates table doesn't have version column
    // ========================================================================

    // ========================================================================
    // TRANSIENT FIELDS (Calculated, not stored)
    // ========================================================================

    /**
     * Inverse rate (calculated: 1/rate).
     * Transient - not stored in DB.
     */
    @Transient
    private BigDecimal inverseRate;

    /**
     * Mid rate calculated from bid/ask.
     * Transient - not stored in DB.
     */
    @Transient
    private BigDecimal midRate;

    // ========================================================================
    // ENUMS (Matching database constraints)
    // ========================================================================

    /**
     * Rate type enum - matches DB constraint:
     * CHECK (rate_type IN ('BID', 'ASK', 'MID'))
     */
    public enum RateType {
        BID,    // Bank buying rate
        ASK,    // Bank selling rate
        MID     // Mid-market rate (used as spot/default)
    }

    /**
     * Rate source enum - matches DB constraint:
     * CHECK (source IN ('CENTRAL_BANK', 'REUTERS', 'BLOOMBERG', 'MANUAL', 'INTERNAL'))
     */
    public enum RateSource {
        CENTRAL_BANK,   // Central bank official rate
        REUTERS,        // Thomson Reuters
        BLOOMBERG,      // Bloomberg Terminal
        MANUAL,         // Manual entry
        INTERNAL        // Internal transfer pricing
    }

    // ========================================================================
    // BUSINESS METHODS
    // ========================================================================

    /**
     * Get the inverse rate (1/rate).
     */
    public BigDecimal getInverseRate() {
        if (inverseRate != null) {
            return inverseRate;
        }
        if (rate == null || rate.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ONE;
        }
        return BigDecimal.ONE.divide(rate, 8, RoundingMode.HALF_UP);
    }

    /**
     * Calculate inverse rate.
     */
    public void calculateInverseRate() {
        if (rate != null && rate.compareTo(BigDecimal.ZERO) > 0) {
            this.inverseRate = BigDecimal.ONE.divide(rate, 8, RoundingMode.HALF_UP);
        }
    }

    /**
     * Get or calculate mid rate from bid/ask.
     */
    public BigDecimal getMidRate() {
        if (midRate != null) {
            return midRate;
        }
        if (bidRate != null && askRate != null) {
            return bidRate.add(askRate).divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
        }
        return rate; // Fall back to the main rate
    }

    /**
     * Calculate mid rate from bid/ask if available.
     */
    public void calculateMidRate() {
        if (bidRate != null && askRate != null) {
            this.midRate = bidRate.add(askRate).divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
        }
    }

    /**
     * Check if rate is currently valid.
     */
    public boolean isCurrentlyValid() {
        if (!Boolean.TRUE.equals(isActive)) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        if (effectiveFrom != null && now.isBefore(effectiveFrom)) {
            return false;
        }
        if (effectiveTo != null && now.isAfter(effectiveTo)) {
            return false;
        }
        return true;
    }

    /**
     * Check if rate is stale (older than specified hours).
     */
    public boolean isStale(int maxHours) {
        if (rateDate == null) {
            return true;
        }
        LocalDateTime rateDateTime = rateTime != null 
            ? LocalDateTime.of(rateDate, rateTime)
            : rateDate.atStartOfDay();
        return rateDateTime.plusHours(maxHours).isBefore(LocalDateTime.now());
    }

    /**
     * Get rate timestamp combining date and time.
     */
    public LocalDateTime getRateTimestamp() {
        if (rateDate == null) {
            return null;
        }
        return rateTime != null 
            ? LocalDateTime.of(rateDate, rateTime)
            : rateDate.atStartOfDay();
    }

    /**
     * Set rate timestamp (splits into date and time).
     */
    public void setRateTimestamp(LocalDateTime timestamp) {
        if (timestamp != null) {
            this.rateDate = timestamp.toLocalDate();
            this.rateTime = timestamp.toLocalTime();
        }
    }

    /**
     * Convert amount using this rate.
     */
    public BigDecimal convert(BigDecimal amount) {
        if (amount == null || rate == null) {
            return amount;
        }
        return amount.multiply(rate).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Reverse convert amount using inverse rate.
     */
    public BigDecimal reverseConvert(BigDecimal amount) {
        if (amount == null || rate == null || rate.compareTo(BigDecimal.ZERO) == 0) {
            return amount;
        }
        return amount.divide(rate, 4, RoundingMode.HALF_UP);
    }

    /**
     * Get the spread between bid and ask in basis points.
     */
    public BigDecimal calculateSpreadBps() {
        if (bidRate == null || askRate == null || bidRate.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        // Spread = (Ask - Bid) / Mid * 10000
        BigDecimal mid = getMidRate();
        if (mid.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return askRate.subtract(bidRate)
            .divide(mid, 8, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(10000))
            .setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Create currency pair string.
     */
    public String getCurrencyPair() {
        return fromCurrency + "/" + toCurrency;
    }

    // ========================================================================
    // BUILDER HELPER
    // ========================================================================

    /**
     * Pre-persist hook to set defaults.
     */
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
        if (rateDate == null) {
            rateDate = LocalDate.now();
        }
        if (effectiveFrom == null) {
            effectiveFrom = LocalDateTime.now();
        }
        if (isActive == null) {
            isActive = true;
        }
        if (rateType == null) {
            rateType = RateType.MID;
        }
        if (rateSource == null) {
            rateSource = RateSource.MANUAL;
        }
    }

    /**
     * Pre-update hook.
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ========================================================================
    // STATIC FACTORY METHODS
    // ========================================================================

    /**
     * Create a MID rate entry.
     */
    public static FxRate createMidRate(String fromCurrency, String toCurrency, 
                                        BigDecimal rate, RateSource source) {
        return FxRate.builder()
            .fromCurrency(fromCurrency)
            .toCurrency(toCurrency)
            .rate(rate)
            .rateType(RateType.MID)
            .rateSource(source)
            .rateDate(LocalDate.now())
            .rateTime(LocalTime.now())
            .effectiveFrom(LocalDateTime.now())
            .isActive(true)
            .build();
    }

    /**
     * Create a spot rate entry (alias for MID rate).
     * This is the most common rate type for currency conversions.
     */
    public static FxRate createSpotRate(String fromCurrency, String toCurrency,
                                         BigDecimal rate, RateSource source) {
        return FxRate.builder()
            .fromCurrency(fromCurrency)
            .toCurrency(toCurrency)
            .rate(rate)
            .rateType(RateType.MID)  // MID is used as spot
            .rateSource(source)
            .rateDate(LocalDate.now())
            .rateTime(LocalTime.now())
            .effectiveFrom(LocalDateTime.now())
            .isActive(true)
            .build();
    }

    /**
     * Create a fixing rate entry (rate for a specific date).
     * Used for end-of-day or benchmark rates.
     */
    public static FxRate createFixingRate(String fromCurrency, String toCurrency,
                                           BigDecimal rate, LocalDate rateDate,
                                           RateSource source) {
        return FxRate.builder()
            .fromCurrency(fromCurrency)
            .toCurrency(toCurrency)
            .rate(rate)
            .rateType(RateType.MID)  // Fixing rates are typically MID
            .rateSource(source)
            .rateDate(rateDate)
            .rateTime(LocalTime.of(16, 0))  // Common fixing time (4 PM)
            .effectiveFrom(rateDate.atStartOfDay())
            .effectiveTo(rateDate.plusDays(1).atStartOfDay())
            .isActive(true)
            .build();
    }

    /**
     * Create an internal transfer pricing rate.
     * Used for intercompany transactions within a corporate.
     */
    public static FxRate createInternalRate(String fromCurrency, String toCurrency,
                                             BigDecimal rate, UUID corporateId,
                                             BigDecimal spreadBps) {
        return FxRate.builder()
            .fromCurrency(fromCurrency)
            .toCurrency(toCurrency)
            .rate(rate)
            .rateType(RateType.MID)
            .rateSource(RateSource.INTERNAL)
            .rateDate(LocalDate.now())
            .rateTime(LocalTime.now())
            .effectiveFrom(LocalDateTime.now())
            .corporateId(corporateId)
            .spreadBps(spreadBps)
            .isActive(true)
            .build();
    }

    /**
     * Create a BID/ASK rate entry with spread.
     */
    public static FxRate createBidAskRate(String fromCurrency, String toCurrency,
                                           BigDecimal bidRate, BigDecimal askRate,
                                           RateSource source) {
        BigDecimal midRate = bidRate.add(askRate).divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
        BigDecimal spreadBps = askRate.subtract(bidRate)
            .divide(midRate, 8, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(10000));
        
        return FxRate.builder()
            .fromCurrency(fromCurrency)
            .toCurrency(toCurrency)
            .rate(midRate)
            .bidRate(bidRate)
            .askRate(askRate)
            .rateType(RateType.MID)
            .rateSource(source)
            .rateDate(LocalDate.now())
            .rateTime(LocalTime.now())
            .effectiveFrom(LocalDateTime.now())
            .spreadBps(spreadBps)
            .isActive(true)
            .build();
    }

    /**
     * Create a corporate-specific rate.
     * Used when a corporate has negotiated special FX rates.
     */
    public static FxRate createCorporateRate(String fromCurrency, String toCurrency,
                                              BigDecimal rate, UUID corporateId,
                                              UUID programId, RateSource source) {
        return FxRate.builder()
            .fromCurrency(fromCurrency)
            .toCurrency(toCurrency)
            .rate(rate)
            .rateType(RateType.MID)
            .rateSource(source)
            .rateDate(LocalDate.now())
            .rateTime(LocalTime.now())
            .effectiveFrom(LocalDateTime.now())
            .corporateId(corporateId)
            .programId(programId)
            .isActive(true)
            .build();
    }

    @Override
    public String toString() {
        return "FxRate{" +
            "id=" + id +
            ", pair=" + getCurrencyPair() +
            ", rate=" + rate +
            ", type=" + rateType +
            ", source=" + rateSource +
            ", date=" + rateDate +
            ", active=" + isActive +
            '}';
    }
}