package com.bank.vam.entity.payables;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * PaymentExecution Entity - Individual payment execution tracking.
 * 
 * Records the actual execution of payments:
 * - Single payable payment
 * - Batch payment item
 * - POBO execution
 * 
 * Domain Model:
 * - Payable (1) -> PaymentExecution (N)
 * - PaymentBatch (1) -> PaymentExecution (N)
 * - Transaction (1) -> PaymentExecution (0..1)
 */
@Entity
@Table(name = "payment_executions", indexes = {
    @Index(name = "idx_exec_payable", columnList = "payable_id"),
    @Index(name = "idx_exec_batch", columnList = "batch_id"),
    @Index(name = "idx_exec_status", columnList = "status"),
    @Index(name = "idx_exec_date", columnList = "execution_date")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentExecution extends BaseEntity {

    // ========================================================================
    // LINKS
    // ========================================================================

    @Column(name = "payable_id")
    private UUID payableId;

    @Column(name = "batch_id")
    private UUID batchId;

    @Column(name = "transaction_id")
    private UUID transactionId;

    // ========================================================================
    // PAYMENT DETAILS
    // ========================================================================

    @Column(name = "payment_reference", length = 100)
    private String paymentReference;

    @Column(name = "execution_date")
    @Builder.Default
    private LocalDateTime executionDate = LocalDateTime.now();

    @Column(name = "value_date")
    private LocalDate valueDate;

    // ========================================================================
    // SOURCE
    // ========================================================================

    @Column(name = "source_va_id")
    private UUID sourceVaId;

    @Column(name = "source_account", length = 50)
    private String sourceAccount;

    // ========================================================================
    // DESTINATION
    // ========================================================================

    @Column(name = "beneficiary_name", length = 200)
    private String beneficiaryName;

    @Column(name = "beneficiary_account", length = 50)
    private String beneficiaryAccount;

    @Column(name = "beneficiary_bank", length = 100)
    private String beneficiaryBank;

    @Column(name = "beneficiary_bank_code", length = 20)
    private String beneficiaryBankCode;

    // ========================================================================
    // AMOUNT
    // ========================================================================

    @Column(name = "amount", precision = 18, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "currency_code", nullable = false, length = 3)
    @Builder.Default
    private String currencyCode = "AED";

    // ========================================================================
    // STATUS
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private ExecutionStatus status = ExecutionStatus.PENDING;

    // ========================================================================
    // PROCESSING
    // ========================================================================

    @Column(name = "bank_reference", length = 100)
    private String bankReference;

    @Column(name = "bank_response_code", length = 20)
    private String bankResponseCode;

    @Column(name = "bank_response_message", length = 500)
    private String bankResponseMessage;

    // ========================================================================
    // POBO TRACKING
    // ========================================================================

    @Column(name = "is_pobo")
    @Builder.Default
    private Boolean isPobo = false;

    @Column(name = "behalf_of_entity", length = 200)
    private String behalfOfEntity;

    // ========================================================================
    // ERROR HANDLING
    // ========================================================================

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "retry_count")
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "last_retry_at")
    private LocalDateTime lastRetryAt;

    // ========================================================================
    // ENUMS
    // ========================================================================

    public enum ExecutionStatus {
        PENDING,        // Awaiting execution
        PROCESSING,     // Being processed
        COMPLETED,      // Successfully completed
        FAILED,         // Failed
        REVERSED,       // Reversed
        CANCELLED       // Cancelled
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    /**
     * Check if execution is successful.
     */
    public boolean isSuccessful() {
        return status == ExecutionStatus.COMPLETED;
    }

    /**
     * Check if execution failed.
     */
    public boolean isFailed() {
        return status == ExecutionStatus.FAILED;
    }

    /**
     * Check if execution can be retried.
     */
    public boolean canRetry() {
        return status == ExecutionStatus.FAILED && retryCount < 3;
    }

    /**
     * Start processing.
     */
    public void startProcessing() {
        this.status = ExecutionStatus.PROCESSING;
    }

    /**
     * Mark as completed.
     */
    public void complete(String bankReference, String responseCode) {
        this.status = ExecutionStatus.COMPLETED;
        this.bankReference = bankReference;
        this.bankResponseCode = responseCode;
    }

    /**
     * Mark as failed.
     */
    public void fail(String errorCode, String errorMessage) {
        this.status = ExecutionStatus.FAILED;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    /**
     * Increment retry.
     */
    public void retry() {
        this.retryCount = (retryCount != null ? retryCount : 0) + 1;
        this.lastRetryAt = LocalDateTime.now();
        this.status = ExecutionStatus.PENDING;
    }

    /**
     * Cancel execution.
     */
    public void cancel() {
        this.status = ExecutionStatus.CANCELLED;
    }

    /**
     * Reverse execution.
     */
    public void reverse() {
        if (status == ExecutionStatus.COMPLETED) {
            this.status = ExecutionStatus.REVERSED;
        }
    }

    // ========================================================================
    // FACTORY METHODS
    // ========================================================================

    /**
     * Create execution for payable.
     */
    public static PaymentExecution forPayable(Payable payable, UUID sourceVaId) {
        return PaymentExecution.builder()
            .payableId(payable.getId())
            .sourceVaId(sourceVaId)
            .beneficiaryName(payable.getVendorName())
            .beneficiaryAccount(payable.getVendorAccount())
            .beneficiaryBank(payable.getVendorBank())
            .beneficiaryBankCode(payable.getVendorBankCode())
            .amount(payable.getOutstandingAmount())
            .currencyCode(payable.getCurrencyCode())
            .isPobo(payable.getIsPobo())
            .behalfOfEntity(payable.getBehalfOfEntity())
            .status(ExecutionStatus.PENDING)
            .build();
    }

    /**
     * Create execution for batch item.
     */
    public static PaymentExecution forBatchItem(UUID batchId, Payable payable, UUID sourceVaId) {
        PaymentExecution execution = forPayable(payable, sourceVaId);
        execution.setBatchId(batchId);
        return execution;
    }

    /**
     * Generate payment reference.
     */
    public static String generateReference() {
        return "PAY-" + System.currentTimeMillis() + "-" + 
               String.format("%04d", (int)(Math.random() * 10000));
    }
}