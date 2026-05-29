package com.bank.vam.entity.treasury;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * PaymentRequest Entity - Tracks outbound payment requests from payables.
 *
 * This entity represents a payment instruction that:
 * - Links to a Payable being paid
 * - Tracks the payment lifecycle (PENDING → SUBMITTED → PROCESSING → COMPLETED/FAILED)
 * - Stores the generated pain.001 XML
 * - Links to the resulting Transaction
 *
 * Used for:
 * - Direct vendor payments
 * - POBO payments
 * - Bulk payment batches
 */
@Entity
@Table(name = "payment_requests", indexes = {
    @Index(name = "idx_pmtreq_request_number", columnList = "request_number"),
    @Index(name = "idx_pmtreq_payable_id", columnList = "payable_id"),
    @Index(name = "idx_pmtreq_corporate_id", columnList = "corporate_id"),
    @Index(name = "idx_pmtreq_owning_entity_id", columnList = "owning_entity_id"),
    @Index(name = "idx_pmtreq_status", columnList = "status"),
    @Index(name = "idx_pmtreq_created_at", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "request_number", nullable = false, unique = true)
    private String requestNumber;

    // ========================================================================
    // PAYABLE REFERENCE
    // ========================================================================

    @Column(name = "payable_id")
    private UUID payableId;

    @Column(name = "payable_number")
    private String payableNumber;

    // ========================================================================
    // CORPORATE & ENTITY CONTEXT
    // ========================================================================

    @Column(name = "corporate_id")
    private UUID corporateId;

    @Column(name = "owning_entity_id")
    private UUID owningEntityId;

    @Column(name = "owning_entity_code")
    private String owningEntityCode;

    // POBO fields
    @Column(name = "paying_entity_id")
    private UUID payingEntityId;

    @Column(name = "paying_entity_code")
    private String payingEntityCode;

    @Column(name = "is_pobo")
    private Boolean isPobo;

    // ========================================================================
    // SOURCE ACCOUNT
    // ========================================================================

    @Column(name = "source_va_id")
    private UUID sourceVaId;

    @Column(name = "source_va_number")
    private String sourceVaNumber;

    // ========================================================================
    // BENEFICIARY DETAILS
    // ========================================================================

    @Column(name = "beneficiary_name")
    private String beneficiaryName;

    @Column(name = "beneficiary_account")
    private String beneficiaryAccount;

    @Column(name = "beneficiary_bank")
    private String beneficiaryBank;

    @Column(name = "beneficiary_bank_code")
    private String beneficiaryBankCode;

    @Column(name = "beneficiary_country")
    private String beneficiaryCountry;

    // Party reference (if paying a registered party)
    @Column(name = "party_id")
    private UUID partyId;

    @Column(name = "party_bank_account_id")
    private UUID partyBankAccountId;

    // ========================================================================
    // PAYMENT DETAILS
    // ========================================================================

    @Column(name = "amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal amount;

    @Column(name = "currency_code", length = 3, nullable = false)
    private String currencyCode;

    @Column(name = "payment_channel")
    private String paymentChannel;

    @Column(name = "payment_method")
    private String paymentMethod;

    @Column(name = "remittance_info", length = 1000)
    private String remittanceInfo;

    @Column(name = "end_to_end_id")
    private String endToEndId;

    // ========================================================================
    // DATES
    // ========================================================================

    @Column(name = "requested_date")
    private LocalDate requestedDate;

    @Column(name = "value_date")
    private LocalDate valueDate;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    // ========================================================================
    // ISO 20022 (pain.001)
    // ========================================================================

    @Column(name = "pain001_message_id")
    private String pain001MessageId;

    @Column(name = "pain001_xml", columnDefinition = "TEXT")
    private String pain001Xml;

    @Column(name = "pain002_status")
    private String pain002Status;

    @Column(name = "pain002_reason_code")
    private String pain002ReasonCode;

    // ========================================================================
    // TRANSACTION REFERENCE
    // ========================================================================

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "transaction_ref")
    private String transactionRef;

    // ========================================================================
    // BATCH REFERENCE
    // ========================================================================

    @Column(name = "batch_id")
    private UUID batchId;

    @Column(name = "batch_reference")
    private String batchReference;

    // ========================================================================
    // STATUS
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentRequestStatus status;

    @Column(name = "priority")
    private String priority;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "retry_count")
    private Integer retryCount;

    // ========================================================================
    // AUDIT
    // ========================================================================

    @Column(name = "created_by")
    private String createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_by")
    private String updatedBy;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version
    private Long version;

    // ========================================================================
    // ENUMS
    // ========================================================================

    public enum PaymentRequestStatus {
        PENDING,        // Created, awaiting processing
        SUBMITTED,      // pain.001 generated and submitted
        PROCESSING,     // Being processed by payment gateway
        COMPLETED,      // Successfully completed
        FAILED,         // Failed with error
        CANCELLED,      // Cancelled by user
        REJECTED        // Rejected by bank (pain.002)
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    public void markSubmitted(String pain001Xml, String pain001MsgId) {
        this.pain001Xml = pain001Xml;
        this.pain001MessageId = pain001MsgId;
        this.status = PaymentRequestStatus.SUBMITTED;
    }

    public void markProcessing() {
        this.status = PaymentRequestStatus.PROCESSING;
    }

    public void markCompleted(String transactionRef) {
        this.status = PaymentRequestStatus.COMPLETED;
        this.transactionRef = transactionRef;
        this.completedAt = LocalDateTime.now();
    }

    public void markFailed(String errorCode, String errorMessage) {
        this.status = PaymentRequestStatus.FAILED;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public void markRejected(String pain002Status, String reasonCode) {
        this.status = PaymentRequestStatus.REJECTED;
        this.pain002Status = pain002Status;
        this.pain002ReasonCode = reasonCode;
    }

    public static String generateRequestNumber() {
        return "PMT-" + System.currentTimeMillis();
    }
}
