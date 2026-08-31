package com.bank.vam.entity.viban;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * Pre-generated pool of VIBANs for high-volume scenarios.
 * Enables fast assignment without real-time IBAN generation.
 * 
 * Use cases:
 * - E-commerce orders: Assign VIBAN from pool for each order
 * - Payment links: Generate payment requests with temporary VIBANs
 * - High-volume invoicing: Pre-allocate VIBANs for batch invoices
 * 
 * Lifecycle:
 * 1. Pool created with specified size
 * 2. VIBANs pre-generated and marked as AVAILABLE
 * 3. On assignment: decrement available_count, link to VA + reference
 * 4. On payment completion or TTL expiry: return to pool
 */
@Entity
@Table(name = "viban_pools",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_pool_code",
        columnNames = {"program_id", "pool_code"}
    ),
    indexes = {
        @Index(name = "idx_viban_pools_program_id", columnList = "program_id"),
        @Index(name = "idx_viban_pools_status", columnList = "status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VibanPool extends BaseEntity {

    // ========================================================================
    // Identification
    // ========================================================================

    /**
     * Program this pool belongs to.
     */
    @Column(name = "program_id", nullable = false)
    private UUID programId;

    /**
     * Display name for the pool.
     */
    @Column(name = "pool_name", nullable = false, length = 100)
    private String poolName;

    /**
     * Unique code for the pool within the program.
     */
    @Column(name = "pool_code", nullable = false, length = 20)
    private String poolCode;

    /**
     * Description of the pool's purpose.
     */
    @Column(name = "description", length = 500)
    private String description;

    // ========================================================================
    // VIBAN generation settings
    // ========================================================================

    /**
     * Country code for VIBANs (e.g., "AE" for UAE).
     */
    @Column(name = "country_code", nullable = false, length = 2)
    @Builder.Default
    private String countryCode = "AE";

    /**
     * Bank code for VIBANs.
     */
    @Column(name = "bank_code", nullable = false, length = 10)
    private String bankCode;

    /**
     * Prefix for generated VIBANs.
     */
    @Column(name = "prefix", nullable = false, length = 20)
    private String prefix;

    /**
     * Length of suffix (sequential number).
     */
    @Column(name = "suffix_length")
    @Builder.Default
    private Integer suffixLength = 10;

    /**
     * Algorithm for check digit calculation.
     */
    @Column(name = "check_digit_algorithm", length = 20)
    @Builder.Default
    private String checkDigitAlgorithm = "MOD97";

    // ========================================================================
    // Capacity tracking
    // ========================================================================

    /**
     * Total size of the pool.
     */
    @Column(name = "pool_size", nullable = false)
    private Integer poolSize;

    /**
     * Number of VIBANs currently available for assignment.
     */
    @Column(name = "available_count", nullable = false)
    private Integer availableCount;

    /**
     * Number of VIBANs reserved but not yet assigned.
     */
    @Column(name = "reserved_count")
    @Builder.Default
    private Integer reservedCount = 0;

    // ========================================================================
    // TTL settings
    // ========================================================================

    /**
     * Time-to-live in minutes before unmatched VIBANs return to pool.
     * Default: 1440 (24 hours)
     */
    @Column(name = "assignment_ttl_minutes")
    @Builder.Default
    private Integer assignmentTtlMinutes = 1440;

    /**
     * Whether to automatically return expired VIBANs to pool.
     */
    @Column(name = "auto_return_expired")
    @Builder.Default
    private Boolean autoReturnExpired = true;

    // ========================================================================
    // Threshold alerts
    // ========================================================================

    /**
     * Alert when available percentage falls below this threshold.
     */
    @Column(name = "low_threshold_percent")
    @Builder.Default
    private Integer lowThresholdPercent = 20;

    /**
     * Email to notify when threshold reached.
     */
    @Column(name = "alert_email", length = 255)
    private String alertEmail;

    // ========================================================================
    // Status
    // ========================================================================

    /**
     * Pool status: ACTIVE, INACTIVE, EXHAUSTED, SUSPENDED.
     */
    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = STATUS_ACTIVE;

    // ========================================================================
    // Status constants
    // ========================================================================

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";
    public static final String STATUS_EXHAUSTED = "EXHAUSTED";
    public static final String STATUS_SUSPENDED = "SUSPENDED";

    // ========================================================================
    // Helper methods - Capacity
    // ========================================================================

    /**
     * Get number of currently assigned VIBANs.
     */
    public int getAssignedCount() {
        return poolSize - availableCount - (reservedCount != null ? reservedCount : 0);
    }

    /**
     * Get utilization percentage.
     */
    public double getUtilizationPercent() {
        if (poolSize == null || poolSize == 0) return 0;
        return (double) getAssignedCount() / poolSize * 100;
    }

    /**
     * Get available percentage.
     */
    public double getAvailablePercent() {
        if (poolSize == null || poolSize == 0) return 0;
        return (double) availableCount / poolSize * 100;
    }

    /**
     * Check if pool has available VIBANs.
     */
    public boolean hasAvailable() {
        return availableCount != null && availableCount > 0;
    }

    /**
     * Check if pool has available VIBANs (count specified).
     */
    public boolean hasAvailable(int count) {
        return availableCount != null && availableCount >= count;
    }

    /**
     * Check if pool is exhausted.
     */
    public boolean isExhausted() {
        return availableCount == null || availableCount == 0;
    }

    /**
     * Check if pool is below low threshold.
     */
    public boolean isBelowThreshold() {
        if (lowThresholdPercent == null) return false;
        return getAvailablePercent() < lowThresholdPercent;
    }

    // ========================================================================
    // Helper methods - Status
    // ========================================================================

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }

    public boolean isInactive() {
        return STATUS_INACTIVE.equals(status);
    }

    public boolean isSuspended() {
        return STATUS_SUSPENDED.equals(status);
    }

    /**
     * Check if pool can accept assignments.
     */
    public boolean canAssign() {
        return isActive() && hasAvailable();
    }

    // ========================================================================
    // Pool operations
    // ========================================================================

    /**
     * Decrements available count when assigning a VIBAN.
     * Returns true if successful, false if pool is exhausted.
     */
    public boolean decrementAvailable() {
        if (availableCount == null || availableCount <= 0) {
            return false;
        }
        availableCount--;
        if (availableCount == 0) {
            status = STATUS_EXHAUSTED;
        }
        return true;
    }

    /**
     * Decrements available count by specified amount.
     * Returns true if successful, false if not enough available.
     */
    public boolean decrementAvailable(int count) {
        if (availableCount == null || availableCount < count) {
            return false;
        }
        availableCount -= count;
        if (availableCount == 0) {
            status = STATUS_EXHAUSTED;
        }
        return true;
    }

    /**
     * Increments available count when returning a VIBAN to pool.
     */
    public void incrementAvailable() {
        if (availableCount == null) {
            availableCount = 0;
        }
        if (availableCount < poolSize) {
            availableCount++;
            if (STATUS_EXHAUSTED.equals(status)) {
                status = STATUS_ACTIVE;
            }
        }
    }

    /**
     * Increments available count by specified amount.
     */
    public void incrementAvailable(int count) {
        if (availableCount == null) {
            availableCount = 0;
        }
        availableCount = Math.min(availableCount + count, poolSize);
        if (STATUS_EXHAUSTED.equals(status) && availableCount > 0) {
            status = STATUS_ACTIVE;
        }
    }

    /**
     * Reserve VIBANs for later assignment.
     */
    public boolean reserve(int count) {
        if (availableCount == null || availableCount < count) {
            return false;
        }
        availableCount -= count;
        reservedCount = (reservedCount != null ? reservedCount : 0) + count;
        return true;
    }

    /**
     * Release reserved VIBANs.
     */
    public void releaseReservation(int count) {
        if (reservedCount == null || reservedCount < count) {
            count = reservedCount != null ? reservedCount : 0;
        }
        reservedCount -= count;
        availableCount = (availableCount != null ? availableCount : 0) + count;
        if (STATUS_EXHAUSTED.equals(status) && availableCount > 0) {
            status = STATUS_ACTIVE;
        }
    }

    /**
     * Convert reservation to assignment.
     */
    public void confirmReservation(int count) {
        if (reservedCount == null || reservedCount < count) {
            count = reservedCount != null ? reservedCount : 0;
        }
        reservedCount -= count;
    }

    // ========================================================================
    // VIBAN generation
    // ========================================================================

    /**
     * Generate next VIBAN for this pool.
     * Note: Actual sequence management done in database or service layer.
     */
    public String generateVibanPattern(long sequenceNumber) {
        String paddedSequence = String.format("%0" + suffixLength + "d", sequenceNumber);
        return countryCode + "00" + bankCode + prefix + paddedSequence;
    }

    // ========================================================================
    // Validation
    // ========================================================================

    @PrePersist
    @PreUpdate
    private void validate() {
        if (poolCode == null || poolCode.trim().isEmpty()) {
            throw new IllegalArgumentException("Pool code is required");
        }

        if (poolName == null || poolName.trim().isEmpty()) {
            throw new IllegalArgumentException("Pool name is required");
        }

        if (poolSize == null || poolSize <= 0) {
            throw new IllegalArgumentException("Pool size must be positive");
        }

        if (availableCount == null) {
            availableCount = poolSize;
        }

        if (availableCount > poolSize) {
            throw new IllegalArgumentException("Available count cannot exceed pool size");
        }

        if (availableCount < 0) {
            throw new IllegalArgumentException("Available count cannot be negative");
        }

        // Set defaults
        if (countryCode == null) {
            countryCode = "AE";
        }
        if (suffixLength == null) {
            suffixLength = 10;
        }
        if (checkDigitAlgorithm == null) {
            checkDigitAlgorithm = "MOD97";
        }
        if (assignmentTtlMinutes == null) {
            assignmentTtlMinutes = 1440;
        }
        if (autoReturnExpired == null) {
            autoReturnExpired = true;
        }
        if (lowThresholdPercent == null) {
            lowThresholdPercent = 20;
        }
        if (status == null) {
            status = STATUS_ACTIVE;
        }
        if (reservedCount == null) {
            reservedCount = 0;
        }

        // Update status if exhausted
        if (availableCount == 0 && !STATUS_EXHAUSTED.equals(status) && !STATUS_INACTIVE.equals(status)) {
            status = STATUS_EXHAUSTED;
        }
    }

    // ========================================================================
    // Builder factory methods
    // ========================================================================

    /**
     * Create a pool for invoice VIBANs.
     */
    public static VibanPool createInvoicePool(UUID programId, String bankCode, 
                                               int poolSize, int ttlMinutes) {
        return VibanPool.builder()
            .programId(programId)
            .poolName("Invoice VIBANs")
            .poolCode("INV-POOL")
            .description("Pool for invoice-level VIBANs")
            .bankCode(bankCode)
            .prefix("INV")
            .poolSize(poolSize)
            .availableCount(poolSize)
            .assignmentTtlMinutes(ttlMinutes)
            .status(STATUS_ACTIVE)
            .build();
    }

    /**
     * Create a pool for e-commerce order VIBANs.
     */
    public static VibanPool createEcommercePool(UUID programId, String bankCode, 
                                                 int poolSize, int ttlMinutes) {
        return VibanPool.builder()
            .programId(programId)
            .poolName("E-commerce Order VIBANs")
            .poolCode("ECOM-POOL")
            .description("Pool for e-commerce order VIBANs")
            .bankCode(bankCode)
            .prefix("ORD")
            .poolSize(poolSize)
            .availableCount(poolSize)
            .assignmentTtlMinutes(ttlMinutes)
            .autoReturnExpired(true)
            .status(STATUS_ACTIVE)
            .build();
    }

    /**
     * Create a pool for POS terminal VIBANs.
     */
    public static VibanPool createPosPool(UUID programId, String bankCode, int poolSize) {
        return VibanPool.builder()
            .programId(programId)
            .poolName("POS Terminal VIBANs")
            .poolCode("POS-POOL")
            .description("Pool for POS terminal VIBANs")
            .bankCode(bankCode)
            .prefix("TRM")
            .poolSize(poolSize)
            .availableCount(poolSize)
            .assignmentTtlMinutes(0) // Permanent assignment
            .autoReturnExpired(false)
            .status(STATUS_ACTIVE)
            .build();
    }
}