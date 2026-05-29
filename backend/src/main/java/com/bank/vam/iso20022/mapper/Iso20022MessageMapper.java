package com.bank.vam.iso20022.mapper;

import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.iso20022.dto.Iso20022PaymentDto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Mapper between ISO 20022 messages and existing Transaction/VA entities.
 *
 * This mapper does NOT create new domain models - it directly maps ISO 20022
 * message fields to existing Transaction entity fields.
 *
 * Mapping Strategy:
 * - ISO MsgId -> Transaction.referenceNumber
 * - ISO InstrId -> Transaction.externalReference
 * - ISO EndToEndId -> Transaction.correlationId
 * - ISO Amt/Ccy -> Transaction.amount/currencyCode
 * - ISO Dbtr -> Transaction.remitterName/remitterAccount
 * - ISO Cdtr -> Transaction.beneficiaryName/beneficiaryAccount
 * - ISO RmtInf -> Transaction.description
 */
@Component
@Slf4j
public class Iso20022MessageMapper {

    private static final DateTimeFormatter ISO_DATETIME = DateTimeFormatter.ISO_DATE_TIME;
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_DATE;

    // ========================================================================
    // INWARD PAYMENT -> TRANSACTION (for ROBO Credit)
    // ========================================================================

    /**
     * Map inward payment request to Transaction builder (for VIBAN routing).
     * The actual VA details are filled by VibanRoutingService.
     */
    public Transaction.TransactionBuilder mapInwardToTransaction(InwardPaymentRequest request) {
        return Transaction.builder()
                .movementType(Transaction.MovementType.ROBO_CREDIT)
                .amount(request.getAmount())
                .currencyCode(request.getCurrency())
                .referenceNumber(generateReferenceIfEmpty(request.getMessageId(), "ISO"))
                .externalReference(request.getInstructionId())
                .correlationId(request.getEndToEndId())
                .description(buildDescription(request.getRemittanceInfo(), request.getStructuredRef()))
                .channel(request.getChannel() != null ? request.getChannel() : "ISO20022")
                .remitterName(request.getDebtorName())
                .remitterAccount(request.getDebtorAccount())
                .viban(request.getCreditorAccount())
                .routedViaViban(true)
                .isRobo(true)
                .transactionDate(request.getCreationDateTime() != null ? request.getCreationDateTime() : LocalDateTime.now())
                .valueDate(request.getRequestedExecutionDate() != null ? request.getRequestedExecutionDate() : LocalDate.now())
                .status(Transaction.TransactionStatus.PENDING);
    }

    /**
     * Map Transaction to InwardPaymentResponse after processing.
     */
    public InwardPaymentResponse mapTransactionToInwardResponse(Transaction txn, boolean success,
                                                                  long processingTimeMs, String errorCode, String errorMessage) {
        return InwardPaymentResponse.builder()
                .success(success)
                .statusCode(success ? "ACSC" : "RJCT")
                .statusReason(success ? "AcceptedSettlementCompleted" : errorMessage)
                .transactionReference(txn.getReferenceNumber())
                .transactionId(txn.getId())
                .virtualAccountId(txn.getVaId())
                .vibanId(txn.getVibanId())
                .viban(txn.getViban())
                .balanceBefore(txn.getBalanceBefore())
                .balanceAfter(txn.getBalanceAfter())
                .autoReconciled(Boolean.TRUE.equals(txn.getAutoReconciled()))
                .reconciledReferenceType(txn.getReconciledReferenceType())
                .reconciledReferenceId(txn.getReconciledReferenceId())
                .processingTimeMs(processingTimeMs)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }

    // ========================================================================
    // OUTWARD PAYMENT -> TRANSACTION (for POBO Debit)
    // ========================================================================

    /**
     * Map outward payment request to Transaction for POBO debit.
     */
    public Transaction.TransactionBuilder mapOutwardToTransaction(OutwardPaymentRequest request, VirtualAccount sourceVa) {
        Transaction.MovementType movementType = request.isPobo() ?
                Transaction.MovementType.POBO_DEBIT : Transaction.MovementType.DEBIT;

        return Transaction.builder()
                .movementType(movementType)
                .corporateId(sourceVa.getCorporateId())
                .vaId(sourceVa.getId())
                .physicalAccountId(sourceVa.getPhysicalAccountId())
                .programId(sourceVa.getProgramId())
                .amount(request.getAmount())
                .currencyCode(request.getCurrency() != null ? request.getCurrency() : sourceVa.getCurrencyCode())
                .referenceNumber(generateReferenceIfEmpty(request.getMessageId(), "POBO"))
                .externalReference(request.getInstructionId())
                .correlationId(request.getEndToEndId())
                .description(buildDescription(request.getRemittanceInfo(), request.getStructuredRef()))
                .channel("ISO20022")
                .beneficiaryName(request.getCreditorName())
                .beneficiaryAccount(request.getCreditorAccount())
                .isPobo(request.isPobo())
                .behalfOfEntity(request.getBehalfOfEntity())
                .behalfOfVaId(request.getBehalfOfVaId())
                .transactionDate(LocalDateTime.now())
                .valueDate(request.getRequestedExecutionDate() != null ? request.getRequestedExecutionDate() : LocalDate.now())
                .status(Transaction.TransactionStatus.PENDING);
    }

    /**
     * Map Transaction to OutwardPaymentResponse.
     */
    public OutwardPaymentResponse mapTransactionToOutwardResponse(Transaction txn, String pain001Xml,
                                                                    long processingTimeMs, String errorCode, String errorMessage) {
        boolean success = errorCode == null;
        return OutwardPaymentResponse.builder()
                .success(success)
                .statusCode(success ? "ACSC" : "RJCT")
                .statusReason(success ? "AcceptedSettlementCompleted" : errorMessage)
                .transactionReference(txn.getReferenceNumber())
                .transactionId(txn.getId())
                .messageId(txn.getReferenceNumber())
                .pain001Xml(pain001Xml)
                .sourceVaId(txn.getVaId())
                .balanceBefore(txn.getBalanceBefore())
                .balanceAfter(txn.getBalanceAfter())
                .feeAmount(txn.getFeeAmount())
                .netAmount(txn.getNetAmount())
                .processingTimeMs(processingTimeMs)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }

    // ========================================================================
    // TRANSACTION -> PAYMENT STATUS (pain.002)
    // ========================================================================

    /**
     * Map Transaction status to PaymentStatusResponse.
     */
    public PaymentStatusResponse mapTransactionToStatus(Transaction txn) {
        return PaymentStatusResponse.builder()
                .messageId(generateMessageId("STAT"))
                .originalMessageId(txn.getReferenceNumber())
                .originalInstructionId(txn.getExternalReference())
                .originalEndToEndId(txn.getCorrelationId())
                .transactionStatus(mapTransactionStatus(txn.getStatus()))
                .statusReasonCode(getStatusReasonCode(txn))
                .statusReasonDescription(getStatusReasonDescription(txn))
                .transactionId(txn.getId())
                .transactionReference(txn.getReferenceNumber())
                .amount(txn.getAmount())
                .currency(txn.getCurrencyCode())
                .transactionDate(txn.getTransactionDate())
                .valueDate(txn.getValueDate())
                .build();
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private String generateReferenceIfEmpty(String provided, String prefix) {
        if (provided != null && !provided.isBlank()) {
            return provided;
        }
        return prefix + System.currentTimeMillis() + String.format("%04d", (int) (Math.random() * 10000));
    }

    private String generateMessageId(String prefix) {
        return prefix + "-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String buildDescription(String remittanceInfo, String structuredRef) {
        if (structuredRef != null && !structuredRef.isBlank()) {
            return "REF:" + structuredRef + (remittanceInfo != null ? " - " + remittanceInfo : "");
        }
        return remittanceInfo != null ? remittanceInfo : "ISO 20022 Payment";
    }

    /**
     * Map internal Transaction.TransactionStatus to ISO 20022 status code.
     * ACCP - Accepted Customer Profile
     * ACSP - Accepted Settlement In Progress
     * ACSC - Accepted Settlement Completed
     * RJCT - Rejected
     * PDNG - Pending
     */
    private String mapTransactionStatus(Transaction.TransactionStatus status) {
        return switch (status) {
            case COMPLETED -> "ACSC";
            case PROCESSING -> "ACSP";
            case PENDING, ON_HOLD -> "PDNG";
            case FAILED, CANCELLED, REVERSED -> "RJCT";
            case UNMATCHED, PARTIAL -> "PDNG";
            default -> "PDNG";
        };
    }

    private String getStatusReasonCode(Transaction txn) {
        if (txn.getStatus() == Transaction.TransactionStatus.COMPLETED) {
            return null;
        }
        if (txn.getStatus() == Transaction.TransactionStatus.FAILED) {
            return "AM04";  // InsufficientFunds or generic failure
        }
        if (txn.getStatus() == Transaction.TransactionStatus.CANCELLED) {
            return "CUST";  // RequestedByCustomer
        }
        if (txn.getStatus() == Transaction.TransactionStatus.REVERSED) {
            return "FOCR";  // FollowingCancellationRequest
        }
        return null;
    }

    private String getStatusReasonDescription(Transaction txn) {
        return switch (txn.getStatus()) {
            case COMPLETED -> "Payment completed successfully";
            case PROCESSING -> "Payment is being processed";
            case PENDING -> "Payment is pending execution";
            case FAILED -> "Payment failed";
            case CANCELLED -> "Payment cancelled by customer";
            case REVERSED -> "Payment has been reversed";
            case ON_HOLD -> "Payment is on hold for review";
            case UNMATCHED -> "Payment could not be matched to a recipient";
            case PARTIAL -> "Partial payment applied";
            default -> "Unknown status";
        };
    }

    // ========================================================================
    // BULK PAYMENT HELPERS
    // ========================================================================

    /**
     * Map list of transactions to bulk payment response.
     */
    public BulkPaymentResponse mapToBulkResponse(List<Transaction> transactions, String pain001Xml,
                                                   BigDecimal totalFees, long processingTimeMs) {
        int successCount = 0;
        int failedCount = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;

        var results = new java.util.ArrayList<PaymentInstructionResult>();

        for (Transaction txn : transactions) {
            boolean success = txn.getStatus() == Transaction.TransactionStatus.COMPLETED;
            if (success) {
                successCount++;
                totalAmount = totalAmount.add(txn.getAmount());
            } else {
                failedCount++;
            }

            results.add(PaymentInstructionResult.builder()
                    .instructionId(txn.getExternalReference())
                    .endToEndId(txn.getCorrelationId())
                    .success(success)
                    .transactionReference(txn.getReferenceNumber())
                    .transactionId(txn.getId())
                    .errorCode(success ? null : "PROC_ERR")
                    .errorMessage(success ? null : txn.getProcessingNotes())
                    .build());
        }

        return BulkPaymentResponse.builder()
                .success(failedCount == 0)
                .messageId(generateMessageId("BULK"))
                .pain001Xml(pain001Xml)
                .totalCount(transactions.size())
                .successCount(successCount)
                .failedCount(failedCount)
                .totalAmount(totalAmount)
                .totalFees(totalFees)
                .results(results)
                .processingTimeMs(processingTimeMs)
                .build();
    }
}
