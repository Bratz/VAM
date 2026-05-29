package com.bank.vam.iso20022.service;

import com.bank.vam.dto.TransactionDto;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.iso20022.dto.Iso20022PaymentDto.*;
import com.bank.vam.iso20022.util.Iso20022XmlBuilder;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.service.TransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * ISO 20022 Outward Payment Service - CONVERGED VERSION
 *
 * This service is a FACADE that:
 * 1. Accepts ISO 20022 formatted requests
 * 2. DELEGATES to TransactionService for actual accounting (proper multi-leg flow)
 * 3. Generates ISO 20022 pain.001 XML for the response
 *
 * This ensures:
 * - Single source of truth for payment accounting (TransactionService)
 * - Consistent 4-leg flow: Source VA → Settlement VA → Shadow VA → CBS
 * - Consistent 6-leg POBO flow: Owner VA → Treasury VA → Settlement VA → Shadow VA → CBS
 * - ISO 20022 compliance with proper XML generation
 *
 * Architecture:
 * ┌─────────────────────────────────────────────────────────────┐
 * │                    Iso20022PaymentController                 │
 * │  /iso20022/outward/payment  |  /iso20022/outward/pobo       │
 * └──────────────────────────────┬──────────────────────────────┘
 *                                │
 *                                ▼
 * ┌─────────────────────────────────────────────────────────────┐
 * │              Iso20022OutwardPaymentService                   │
 * │  - Maps ISO 20022 DTO → TransactionDto                      │
 * │  - Delegates to TransactionService                          │
 * │  - Generates pain.001 XML                                   │
 * └──────────────────────────────┬──────────────────────────────┘
 *                                │
 *                                ▼
 * ┌─────────────────────────────────────────────────────────────┐
 * │                    TransactionService                        │
 * │  - makePayment() → 4 legs via Settlement VA                 │
 * │  - makePoboPayment() → 6 legs via Treasury + Settlement VA  │
 * │  - Proper double-entry accounting                           │
 * │  - Fee posting to Settlement VA                             │
 * └─────────────────────────────────────────────────────────────┘
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class Iso20022OutwardPaymentService {

    private final TransactionService transactionService;
    private final VirtualAccountRepository virtualAccountRepository;
    private final Iso20022XmlBuilder xmlBuilder;

    // ========================================================================
    // PROCESS OUTWARD PAYMENT - Delegates to TransactionService
    // ========================================================================

    /**
     * Process outward payment by delegating to TransactionService.
     * 
     * Flow:
     * 1. Map ISO 20022 request to TransactionDto
     * 2. Call TransactionService.makePayment() or makePoboPayment()
     * 3. Generate pain.001 XML from result
     * 4. Return ISO 20022 response
     */
    @Transactional
    public OutwardPaymentResponse processOutwardPayment(OutwardPaymentRequest request) {
        long startTime = System.currentTimeMillis();

        log.info("ISO 20022 outward payment: sourceVa={}, amount={} {}, beneficiary={}, isPobo={}",
                request.getSourceVaId(), request.getAmount(), request.getCurrency(), 
                request.getCreditorName(), request.isPobo());

        try {
            // Validate basic request
            validateRequest(request);

            // Get source VA for XML generation
            VirtualAccount sourceVa = virtualAccountRepository.findById(request.getSourceVaId())
                    .orElseThrow(() -> new BusinessException("Source virtual account not found"));

            if (!sourceVa.isActive()) {
                return buildErrorResponse(request, startTime, "AC04", "Source virtual account is not active");
            }

            Transaction txn;

            // Route to appropriate TransactionService method
            // =====================================================================
            // ROUTING LOGIC:
            // 1. If source VA is IHB Current Account (ihbParticipant=true):
            //    → Auto-route to POBO: IHB Current Account → Treasury Settlement VA → Shadow VA → CBS
            //    → Treasury Settlement VA resolved from sourceVa.getTreasuryPoolVaId()
            // 2. If explicit POBO request with behalfOfVaId:
            //    → Use provided payer/owner VAs
            // 3. Otherwise:
            //    → Standard payment flow
            // =====================================================================
            if (Boolean.TRUE.equals(sourceVa.getIhbParticipant())) {
                // IHB Current Account detected - auto-route through POBO flow
                log.info("Source VA {} is IHB participant - auto-routing through POBO flow", sourceVa.getVaNumber());
                txn = processIhbPoboPayment(sourceVa, request);
            } else if (request.isPobo() && request.getBehalfOfVaId() != null) {
                // Explicit POBO Payment - delegate to TransactionService.makePoboPayment()
                txn = processPoboPaymentViaTransactionService(request);
            } else {
                // Standard Payment - delegate to TransactionService.makePayment()
                txn = processStandardPaymentViaTransactionService(request);
            }

            // Generate pain.001 XML
            String pain001Xml = xmlBuilder.buildPain001(request, sourceVa, txn);

            long processingTime = System.currentTimeMillis() - startTime;
            log.info("ISO 20022 outward payment completed via TransactionService: txn={}, time={}ms",
                    txn.getReferenceNumber(), processingTime);

            return buildSuccessResponse(txn, pain001Xml, processingTime);

        } catch (BusinessException e) {
            log.warn("ISO 20022 outward payment failed: {}", e.getMessage());
            return buildErrorResponse(request, startTime, "RJCT", e.getMessage());
        } catch (Exception e) {
            log.error("ISO 20022 outward payment error: ", e);
            return buildErrorResponse(request, startTime, "TECH", "Technical error: " + e.getMessage());
        }
    }

    /**
     * Standard payment via TransactionService.makePayment()
     * Creates 4 accounting legs: Source VA → Settlement VA → Shadow VA → CBS
     */
    private Transaction processStandardPaymentViaTransactionService(OutwardPaymentRequest request) {
        log.debug("Delegating standard payment to TransactionService.makePayment()");

        // Map ISO 20022 request to TransactionDto.PaymentRequest
        // Note: Field mapping from ISO 20022 to TransactionDto:
        //   - creditorBic → beneficiaryBankSwift
        //   - remittanceInfo → description
        //   - endToEndId → externalReference
        TransactionDto.PaymentRequest paymentRequest = TransactionDto.PaymentRequest.builder()
                .fromVaId(request.getSourceVaId())
                .amount(request.getAmount())
                .beneficiaryName(request.getCreditorName())
                .beneficiaryAccount(request.getCreditorAccount())
                .beneficiaryBankSwift(request.getCreditorBic())       // ISO: creditorBic → beneficiaryBankSwift
                .beneficiaryBankName(request.getCreditorBankName())
                .description(request.getRemittanceInfo())
                .valueDate(request.getRequestedExecutionDate())
                .channel("ISO20022")
                .externalReference(request.getEndToEndId())
                .build();

        // Delegate to TransactionService - this creates proper 4-leg accounting
        return transactionService.makePayment(paymentRequest);
    }

    /**
     * POBO payment via TransactionService.makePoboPayment()
     * Creates 6 accounting legs: Owner VA → Treasury VA → Settlement VA → Shadow VA → CBS
     */
    private Transaction processPoboPaymentViaTransactionService(OutwardPaymentRequest request) {
        log.debug("Delegating POBO payment to TransactionService.makePoboPayment()");

        // Map ISO 20022 request to TransactionDto.PoboPaymentRequest
        // Note: Field mapping from ISO 20022 to TransactionDto:
        //   - creditorBic → beneficiaryBankSwift
        //   - remittanceInfo → description
        //   - sourceVaId → payerVaId (Treasury VA)
        //   - behalfOfVaId → ownerVaId (Subsidiary VA)
        TransactionDto.PoboPaymentRequest poboRequest = TransactionDto.PoboPaymentRequest.builder()
                .ownerVaId(request.getBehalfOfVaId())      // Subsidiary VA that bears cost
                .payerVaId(request.getSourceVaId())        // Treasury VA that makes payment
                .amount(request.getAmount())
                .beneficiaryName(request.getCreditorName())
                .beneficiaryAccount(request.getCreditorAccount())
                .beneficiaryBankSwift(request.getCreditorBic())      // ISO: creditorBic → beneficiaryBankSwift
                .beneficiaryBankName(request.getCreditorBankName())
                .description(request.getRemittanceInfo())
                .valueDate(request.getRequestedExecutionDate())
                .channel("ISO20022_POBO")
                .build();

        // Delegate to TransactionService - this creates proper 6-leg accounting
        return transactionService.makePoboPayment(poboRequest);
    }

    /**
     * IHB Current Account payment via TransactionService.makePoboPayment()
     *
     * When the source VA is an IHB Current Account (ihbParticipant=true):
     * - The IHB Current Account is the OWNER (subsidiary bears cost)
     * - The Treasury Settlement VA is the PAYER (resolved from treasuryPoolVaId)
     * - Payment routes: IHB Current Account → Treasury Settlement VA → Shadow VA → CBS
     *
     * This is auto-detected when source VA has ihbParticipant=true, no need for
     * user to check POBO checkbox or select "behalf of" entity.
     */
    private Transaction processIhbPoboPayment(VirtualAccount ihbCurrentAccount, OutwardPaymentRequest request) {
        log.info("Processing IHB POBO payment: IHB Account={}, Amount={} {}",
                ihbCurrentAccount.getVaNumber(), request.getAmount(), request.getCurrency());

        // Resolve Treasury Settlement VA from IHB Current Account's treasuryPoolVaId
        UUID treasurySettlementVaId = ihbCurrentAccount.getTreasuryPoolVaId();
        if (treasurySettlementVaId == null) {
            throw new BusinessException("IHB Current Account " + ihbCurrentAccount.getVaNumber() +
                " has no Treasury Settlement VA configured (treasuryPoolVaId is null). " +
                "Cannot execute POBO payment. Please ensure the IHB account was created correctly.");
        }

        VirtualAccount treasurySettlementVa = virtualAccountRepository.findById(treasurySettlementVaId)
                .orElseThrow(() -> new BusinessException("Treasury Settlement VA not found: " + treasurySettlementVaId +
                    ". The IHB Current Account's treasuryPoolVaId references a non-existent VA."));

        log.info("IHB POBO routing: Owner={} (IHB Current Account) → Payer={} (Treasury Settlement VA)",
                ihbCurrentAccount.getVaNumber(), treasurySettlementVa.getVaNumber());

        // Build POBO request with correct owner/payer mapping
        // - ownerVaId = IHB Current Account (subsidiary bears cost, balance decreases)
        // - payerVaId = Treasury Settlement VA (makes the actual payment via Shadow VA)
        TransactionDto.PoboPaymentRequest poboRequest = TransactionDto.PoboPaymentRequest.builder()
                .ownerVaId(ihbCurrentAccount.getId())           // IHB Current Account (cost bearer)
                .payerVaId(treasurySettlementVa.getId())        // Treasury Settlement VA (payer)
                .amount(request.getAmount())
                .beneficiaryName(request.getCreditorName())
                .beneficiaryAccount(request.getCreditorAccount())
                .beneficiaryBankSwift(request.getCreditorBic())
                .beneficiaryBankName(request.getCreditorBankName())
                .description(request.getRemittanceInfo() != null ? request.getRemittanceInfo() :
                    "IHB POBO Payment to " + request.getCreditorName())
                .valueDate(request.getRequestedExecutionDate())
                .channel("ISO20022_IHB_POBO")
                .build();

        // Delegate to TransactionService - use 6-leg if configured, otherwise 4-leg
        Transaction txn;
        if (ihbCurrentAccount.isConfiguredFor6LegPobo()) {
            log.info("Using IHB 6-leg POBO flow (Mirror Account Model) for {}", ihbCurrentAccount.getVaNumber());
            txn = transactionService.makeIhb6LegPoboPayment(poboRequest);
        } else {
            log.info("Using standard 4-leg POBO flow for {} (6-leg not configured)", ihbCurrentAccount.getVaNumber());
            txn = transactionService.makePoboPayment(poboRequest);
        }

        log.info("IHB POBO payment completed: Owner={}, Payer={}, TxnRef={}, Amount={}, 6-leg={}",
                ihbCurrentAccount.getVaNumber(), treasurySettlementVa.getVaNumber(),
                txn.getReferenceNumber(), request.getAmount(), ihbCurrentAccount.isConfiguredFor6LegPobo());

        return txn;
    }

    // ========================================================================
    // BULK OUTWARD PAYMENT - Delegates to TransactionService
    // ========================================================================

    /**
     * Process bulk outward payment by delegating each instruction to TransactionService.
     */
    @Transactional
    public BulkPaymentResponse processBulkOutwardPayment(BulkPaymentRequest request) {
        long startTime = System.currentTimeMillis();

        log.info("ISO 20022 bulk outward payment: sourceVa={}, instructionCount={}, isPobo={}",
                request.getSourceVaId(), request.getInstructions().size(), request.isPobo());

        try {
            // Get source VA
            VirtualAccount sourceVa = virtualAccountRepository.findById(request.getSourceVaId())
                    .orElseThrow(() -> new BusinessException("Source virtual account not found"));

            if (!sourceVa.isActive()) {
                throw new BusinessException("Source virtual account is not active");
            }

            List<Transaction> transactions = new ArrayList<>();
            List<PaymentInstructionResult> results = new ArrayList<>();
            int successCount = 0;
            int failedCount = 0;
            BigDecimal totalAmount = BigDecimal.ZERO;
            BigDecimal totalFees = BigDecimal.ZERO;

            // Check if source VA is IHB participant for auto-POBO routing
            boolean isIhbAccount = Boolean.TRUE.equals(sourceVa.getIhbParticipant());
            VirtualAccount treasurySettlementVa = null;

            if (isIhbAccount) {
                // Resolve Treasury Settlement VA for IHB bulk payments
                UUID treasurySettlementVaId = sourceVa.getTreasuryPoolVaId();
                if (treasurySettlementVaId == null) {
                    throw new BusinessException("IHB Current Account " + sourceVa.getVaNumber() +
                        " has no Treasury Settlement VA configured. Cannot execute bulk POBO payment.");
                }
                treasurySettlementVa = virtualAccountRepository.findById(treasurySettlementVaId)
                    .orElseThrow(() -> new BusinessException("Treasury Settlement VA not found: " + treasurySettlementVaId));

                log.info("IHB bulk payment: Owner={}, Payer={}", sourceVa.getVaNumber(), treasurySettlementVa.getVaNumber());
            }

            // Process each instruction via TransactionService
            for (PaymentInstruction instruction : request.getInstructions()) {
                try {
                    Transaction txn;

                    if (isIhbAccount) {
                        // IHB bulk - auto-route through POBO (6-leg if configured)
                        TransactionDto.PoboPaymentRequest poboRequest = TransactionDto.PoboPaymentRequest.builder()
                                .ownerVaId(sourceVa.getId())              // IHB Current Account
                                .payerVaId(treasurySettlementVa.getId())  // Treasury Settlement VA
                                .amount(instruction.getAmount())
                                .beneficiaryName(instruction.getCreditorName())
                                .beneficiaryAccount(instruction.getCreditorAccount())
                                .beneficiaryBankSwift(instruction.getCreditorBic())
                                .description(instruction.getRemittanceInfo())
                                .valueDate(request.getRequestedExecutionDate())
                                .channel("ISO20022_BULK_IHB_POBO")
                                .build();

                        // Use 6-leg if configured, otherwise 4-leg
                        if (sourceVa.isConfiguredFor6LegPobo()) {
                            txn = transactionService.makeIhb6LegPoboPayment(poboRequest);
                        } else {
                            txn = transactionService.makePoboPayment(poboRequest);
                        }
                    } else if (request.isPobo() && request.getBehalfOfVaId() != null) {
                        // Explicit POBO bulk - each instruction goes through makePoboPayment
                        TransactionDto.PoboPaymentRequest poboRequest = TransactionDto.PoboPaymentRequest.builder()
                                .ownerVaId(request.getBehalfOfVaId())
                                .payerVaId(request.getSourceVaId())
                                .amount(instruction.getAmount())
                                .beneficiaryName(instruction.getCreditorName())
                                .beneficiaryAccount(instruction.getCreditorAccount())
                                .beneficiaryBankSwift(instruction.getCreditorBic())
                                .description(instruction.getRemittanceInfo())
                                .valueDate(request.getRequestedExecutionDate())
                                .channel("ISO20022_BULK_POBO")
                                .build();

                        txn = transactionService.makePoboPayment(poboRequest);
                    } else {
                        // Standard bulk - each instruction goes through makePayment
                        TransactionDto.PaymentRequest paymentRequest = TransactionDto.PaymentRequest.builder()
                                .fromVaId(request.getSourceVaId())
                                .amount(instruction.getAmount())
                                .beneficiaryName(instruction.getCreditorName())
                                .beneficiaryAccount(instruction.getCreditorAccount())
                                .beneficiaryBankSwift(instruction.getCreditorBic())
                                .description(instruction.getRemittanceInfo())
                                .valueDate(request.getRequestedExecutionDate())
                                .channel("ISO20022_BULK")
                                .externalReference(instruction.getEndToEndId())
                                .build();

                        txn = transactionService.makePayment(paymentRequest);
                    }

                    transactions.add(txn);
                    successCount++;
                    totalAmount = totalAmount.add(instruction.getAmount());
                    if (txn.getFeeAmount() != null) {
                        totalFees = totalFees.add(txn.getFeeAmount());
                    }

                    results.add(PaymentInstructionResult.builder()
                            .instructionId(instruction.getInstructionId())
                            .endToEndId(instruction.getEndToEndId())
                            .transactionId(txn.getId())
                            .transactionReference(txn.getReferenceNumber())  // Correct field name
                            .success(true)                                     // Use boolean success
                            .build());

                } catch (Exception e) {
                    log.warn("Bulk instruction failed: {} - {}", instruction.getInstructionId(), e.getMessage());
                    failedCount++;

                    results.add(PaymentInstructionResult.builder()
                            .instructionId(instruction.getInstructionId())
                            .endToEndId(instruction.getEndToEndId())
                            .success(false)                                    // Use boolean success
                            .errorCode("RJCT")
                            .errorMessage(e.getMessage())
                            .build());
                }
            }

            // Generate bulk pain.001 XML
            String pain001Xml = xmlBuilder.buildBulkPain001(request, sourceVa, transactions);

            long processingTime = System.currentTimeMillis() - startTime;
            log.info("ISO 20022 bulk payment completed: success={}, failed={}, totalAmount={}, time={}ms",
                    successCount, failedCount, totalAmount, processingTime);

            return BulkPaymentResponse.builder()
                    .success(failedCount == 0)
                    .messageId("BULK-" + System.currentTimeMillis())
                    .totalCount(request.getInstructions().size())
                    .successCount(successCount)
                    .failedCount(failedCount)
                    .totalAmount(totalAmount)
                    .totalFees(totalFees)
                    .results(results)
                    .pain001Xml(pain001Xml)
                    .processingTimeMs(processingTime)
                    .build();

        } catch (BusinessException e) {
            log.warn("ISO 20022 bulk payment failed: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("ISO 20022 bulk payment error: ", e);
            throw new BusinessException("Bulk payment failed: " + e.getMessage());
        }
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private void validateRequest(OutwardPaymentRequest request) {
        if (request.getSourceVaId() == null) {
            throw new BusinessException("Source VA ID is required");
        }
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Invalid amount: must be positive");
        }
        if (request.getCreditorName() == null || request.getCreditorName().isBlank()) {
            throw new BusinessException("Creditor name is required");
        }
        if (request.getCreditorAccount() == null || request.getCreditorAccount().isBlank()) {
            throw new BusinessException("Creditor account is required");
        }
        // For POBO, validate behalfOfVaId
        if (request.isPobo() && request.getBehalfOfVaId() == null) {
            throw new BusinessException("behalfOfVaId is required for POBO payments");
        }
    }

    private OutwardPaymentResponse buildSuccessResponse(Transaction txn, String pain001Xml, long processingTime) {
        return OutwardPaymentResponse.builder()
                .success(true)
                .transactionId(txn.getId())
                .transactionReference(txn.getReferenceNumber())   // Correct field name
                .messageId("MSG" + txn.getReferenceNumber())
                .statusCode("ACCP")
                .statusReason("Accepted")
                .sourceVaId(txn.getVaId())
                .feeAmount(txn.getFeeAmount())
                .netAmount(txn.getAmount())
                .pain001Xml(pain001Xml)
                .processingTimeMs(processingTime)
                .build();
    }

    private OutwardPaymentResponse buildErrorResponse(OutwardPaymentRequest request, long startTime,
                                                        String errorCode, String errorMessage) {
        return OutwardPaymentResponse.builder()
                .success(false)
                .statusCode("RJCT")
                .statusReason(errorMessage)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .processingTimeMs(System.currentTimeMillis() - startTime)
                .build();
    }
}