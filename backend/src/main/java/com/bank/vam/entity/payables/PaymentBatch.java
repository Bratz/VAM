package com.bank.vam.entity.payables;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * PaymentBatch Entity - Batch processing for bulk payments.
 * 
 * Supports:
 * - Grouping multiple payables for single approval
 * - Scheduled batch execution
 * - Bulk payment processing
 * - Status tracking per batch
 * 
 * Domain Model:
 * - Corporate (1) -> PaymentBatch (N)
 * - PaymentBatch (1) -> Payable (N)
 * - PaymentBatch (1) -> PaymentExecution (N)
 */
@Entity
@Table(name = "payment_batches", indexes = {
    @Index(name = "idx_batch_corporate", columnList = "corporate_id"),
    @Index(name = "idx_batch_status", columnList = "status"),
    @Index(name = "idx_batch_scheduled", columnList = "scheduled_date")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentBatch extends BaseEntity {

    // ========================================================================
    // IDENTIFICATION
    // ========================================================================

    @Column(name = "batch_reference", unique = true, nullable = false, length = 50)
    private String batchReference;

    @Column(name = "batch_name", length = 200)
    private String batchName;

    // ========================================================================
    // OWNERSHIP
    // ========================================================================

    @Column(name = "corporate_id", nullable = false)
    private UUID corporateId;

    @Column(name = "program_id")
    private UUID programId;

    @Column(name = "source_va_id")
    private UUID sourceVaId;

    // ========================================================================
    // BATCH DETAILS
    // ========================================================================

    @Column(name = "payment_count")
    @Builder.Default
    private Integer paymentCount = 0;

    @Column(name = "total_amount", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "currency_code", nullable = false, length = 3)
    @Builder.Default
    private String currencyCode = "AED";

    // ========================================================================
    // STATUS
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private BatchStatus status = BatchStatus.DRAFT;

    // ========================================================================
    // COUNTS
    // ========================================================================

    @Column(name = "pending_count")
    @Builder.Default
    private Integer pendingCount = 0;

    @Column(name = "success_count")
    @Builder.Default
    private Integer successCount = 0;

    @Column(name = "failed_count")
    @Builder.Default
    private Integer failedCount = 0;

    // ========================================================================
    // APPROVAL
    // ========================================================================

    @Column(name = "approval_required")
    @Builder.Default
    private Boolean approvalRequired = true;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    // ========================================================================
    // SCHEDULING
    // ========================================================================

    @Column(name = "scheduled_date")
    private LocalDate scheduledDate;

    @Column(name = "execution_start")
    private LocalDateTime executionStart;

    @Column(name = "execution_end")
    private LocalDateTime executionEnd;

    // ========================================================================
    // PROCESSING
    // ========================================================================

    @Column(name = "processing_notes", columnDefinition = "TEXT")
    private String processingNotes;

    @Column(name = "error_summary", columnDefinition = "TEXT")
    private String errorSummary;

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

    public enum BatchStatus {
        DRAFT,              // Created, items being added
        PENDING_APPROVAL,   // Submitted for approval
        APPROVED,           // Approved, ready for execution
        PROCESSING,         // Execution in progress
        COMPLETED,          // All payments successful
        PARTIAL,            // Some payments failed
        FAILED,             // All payments failed
        CANCELLED           // Batch cancelled
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    /**
     * Check if batch can accept new items.
     */
    public boolean canAddItems() {
        return status == BatchStatus.DRAFT;
    }

    /**
     * Check if batch can be submitted.
     */
    public boolean canBeSubmitted() {
        return status == BatchStatus.DRAFT && paymentCount > 0;
    }

    /**
     * Check if batch can be approved.
     */
    public boolean canBeApproved() {
        return status == BatchStatus.PENDING_APPROVAL;
    }

    /**
     * Check if batch can be executed.
     */
    public boolean canBeExecuted() {
        return status == BatchStatus.APPROVED;
    }

    /**
     * Submit for approval.
     */
    public void submitForApproval() {
        if (canBeSubmitted()) {
            this.status = BatchStatus.PENDING_APPROVAL;
        }
    }

    /**
     * Approve the batch.
     */
    public void approve(String approvedBy) {
        if (canBeApproved()) {
            this.status = BatchStatus.APPROVED;
            this.approvedBy = approvedBy;
            this.approvedAt = LocalDateTime.now();
        }
    }

    /**
     * Reject the batch.
     */
    public void reject(String rejectedBy, String reason) {
        if (canBeApproved()) {
            this.status = BatchStatus.CANCELLED;
            this.approvedBy = rejectedBy;
            this.approvedAt = LocalDateTime.now();
            this.rejectionReason = reason;
        }
    }

    /**
     * Start execution.
     */
    public void startExecution() {
        if (canBeExecuted()) {
            this.status = BatchStatus.PROCESSING;
            this.executionStart = LocalDateTime.now();
            this.pendingCount = this.paymentCount;
        }
    }

    /**
     * Record execution result.
     */
    public void recordResult(boolean success) {
        if (success) {
            this.successCount = (successCount != null ? successCount : 0) + 1;
        } else {
            this.failedCount = (failedCount != null ? failedCount : 0) + 1;
        }
        this.pendingCount = (pendingCount != null ? pendingCount : 0) - 1;
        
        // Check if complete
        if (pendingCount <= 0) {
            completeExecution();
        }
    }

    /**
     * Complete execution.
     */
    public void completeExecution() {
        this.executionEnd = LocalDateTime.now();
        
        if (failedCount == 0) {
            this.status = BatchStatus.COMPLETED;
        } else if (successCount == 0) {
            this.status = BatchStatus.FAILED;
        } else {
            this.status = BatchStatus.PARTIAL;
        }
    }

    /**
     * Add payment to batch.
     */
    public void addPayment(BigDecimal amount) {
        this.paymentCount = (paymentCount != null ? paymentCount : 0) + 1;
        this.totalAmount = (totalAmount != null ? totalAmount : BigDecimal.ZERO).add(amount);
    }

    /**
     * Remove payment from batch.
     */
    public void removePayment(BigDecimal amount) {
        this.paymentCount = Math.max(0, (paymentCount != null ? paymentCount : 0) - 1);
        this.totalAmount = (totalAmount != null ? totalAmount : BigDecimal.ZERO).subtract(amount);
        if (this.totalAmount.compareTo(BigDecimal.ZERO) < 0) {
            this.totalAmount = BigDecimal.ZERO;
        }
    }

    /**
     * Get execution duration in milliseconds.
     */
    public Long getExecutionDurationMs() {
        if (executionStart == null || executionEnd == null) return null;
        return java.time.Duration.between(executionStart, executionEnd).toMillis();
    }

    /**
     * Get success rate as percentage.
     */
    public Double getSuccessRate() {
        if (paymentCount == null || paymentCount == 0) return 0.0;
        return (successCount != null ? successCount : 0) * 100.0 / paymentCount;
    }

    // ========================================================================
    // FACTORY METHODS
    // ========================================================================

    /**
     * Create a new payment batch.
     */
    public static PaymentBatch create(UUID corporateId, String batchName, String createdBy) {
        return PaymentBatch.builder()
            .corporateId(corporateId)
            .batchName(batchName)
            .status(BatchStatus.DRAFT)
            .createdBy(createdBy)
            .build();
    }

    /**
     * Create a scheduled batch.
     */
    public static PaymentBatch createScheduled(UUID corporateId, String batchName, 
                                                LocalDate scheduledDate, String createdBy) {
        return PaymentBatch.builder()
            .corporateId(corporateId)
            .batchName(batchName)
            .scheduledDate(scheduledDate)
            .status(BatchStatus.DRAFT)
            .createdBy(createdBy)
            .build();
    }
}