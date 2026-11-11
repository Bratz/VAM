package com.bank.vam.iso20022.service;

import com.bank.vam.dto.TransactionDto;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.fx.deal.FxPaymentService;
import com.bank.vam.iso20022.dto.Iso20022PaymentDto.*;
import com.bank.vam.iso20022.util.Iso20022XmlBuilder;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.service.PaymentRoutingService;
import com.bank.vam.service.TransactionService;
import com.bank.vam.service.pobo.PoboEnrolmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
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
    private final PaymentRoutingService paymentRoutingService;
    private final VirtualAccountRepository virtualAccountRepository;
    private final Iso20022XmlBuilder xmlBuilder;
    private final FxPaymentService fxPayments;
    private final PlatformTransactionManager transactionManager;
    private final PoboEnrolmentService poboEnrolments;

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
        FxPaymentService.Conversion fx = null;
        BigDecimal instructed = request.getAmount();

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

            // POBO by enrolment (spec R4): the paying entity's enrolment for the currency decides, before any FX.
            PoboEnrolmentService.Route pobo = poboRoute(sourceVa, request.isPobo(), request.getBehalfOfVaId(),
                    request.getCurrency());
            // Its pain.001 debtor (D6), refused here when the payment account has no IBAN: before any money moves.
            // Not enrolled, from an IHB current account: the in-house posting too, so the same debtor shape (D6).
            PoboEnrolmentService.PainDebtor onBehalf = pobo != null ? poboEnrolments.painDebtor(pobo)
                    : Boolean.TRUE.equals(sourceVa.getIhbParticipant()) ? legacyDebtor(sourceVa) : null;

            // A payment in another currency is converted first; the legs below then move the debit amount, in the
            // account's currency. The request's amount goes back to the instructed one for the pain.001.
            // POBO: none in the IHB account's currency; cross-currency, the treasury's cover on its payment account
            // after the IHB headroom check on the settle-currency amount (ihb-fx D3, R9).
            FxPaymentService.Instruction requestedFx = FxPaymentService.Instruction.of(request.getFxPriceId(),
                    request.getFxQuoteId(), request.getFxDealId(), request.getFxDeals(), request.getFxAtMarket());
            fx = pobo != null ? pobo.cover(fxPayments, request.getAmount(), requestedFx)
                    : fxPayments.convert(sourceVa, request.getCurrency(), request.getAmount(), requestedFx);
            if (fx != null) {
                request.setAmount(fx.debitAmount());
            }

            Transaction txn;

            // Route to appropriate TransactionService method
            // =====================================================================
            // ROUTING LOGIC:
            // 1. Paying entity enrolled for POBO in the currency (an explicit POBO request names it by behalfOfVaId):
            //    → the subsidiary's IHB current account bears it, the enrolment's payment account pays (D7 limit, no fee)
            // 2. Source VA is an IHB Current Account (not enrolled): the in-house posting as before, payer from
            //    sourceVa.getTreasuryPoolVaId()
            // 3. Otherwise: standard payment flow
            // =====================================================================
            if (pobo != null) {
                txn = postInHouse(pobo.ihbCurrent(), pobo.paymentVa().getId(), request.getAmount(), request.getCreditorName(),
                        request.getCreditorAccount(), request.getCreditorBic(), request.getCreditorBankName(),
                        request.getRemittanceInfo() != null ? request.getRemittanceInfo() : "POBO Payment to " + request.getCreditorName(),
                        request.getRequestedExecutionDate(), "ISO20022_POBO");
            } else if (Boolean.TRUE.equals(sourceVa.getIhbParticipant())) {
                // IHB Current Account detected - auto-route through POBO flow
                log.info("Source VA {} is IHB participant - auto-routing through POBO flow", sourceVa.getVaNumber());
                txn = postInHouse(sourceVa, legacyPayer(sourceVa), request.getAmount(), request.getCreditorName(),
                        request.getCreditorAccount(), request.getCreditorBic(), request.getCreditorBankName(),
                        request.getRemittanceInfo() != null ? request.getRemittanceInfo() : "IHB POBO Payment to " + request.getCreditorName(),
                        request.getRequestedExecutionDate(), "ISO20022_IHB_POBO");
            } else {
                // Standard Payment - delegate to TransactionService.makePayment()
                txn = processStandardPaymentViaTransactionService(request);
            }

            // Every path above converges here, so the route is linked in one place rather than in
            // each of the three. The selection was recorded before the payment was submitted --
            // the transaction id is the half that only exists now.
            if (request.getRouteSelectionId() != null) {
                paymentRoutingService.attachToTransaction(request.getRouteSelectionId(), txn.getId());
            }
            request.setAmount(instructed);
            if (fx != null && pobo != null) {
                transactionService.settlePoboFx(fx, txn, pobo.paymentVa());
            } else if (fx != null) {
                fxPayments.settle(fx, txn, sourceVa);
            }

            // Generate pain.001 XML
            String pain001Xml = xmlBuilder.buildPain001(request, sourceVa, txn, onBehalf);

            long processingTime = System.currentTimeMillis() - startTime;
            log.info("ISO 20022 outward payment completed via TransactionService: txn={}, time={}ms",
                    txn.getReferenceNumber(), processingTime);

            return buildSuccessResponse(txn, pain001Xml, processingTime);

        } catch (BusinessException e) {
            log.warn("ISO 20022 outward payment failed: {}", e.getMessage());
            request.setAmount(instructed);
            // Nothing of a failed payment is kept (its FX booking included). Rolling back here, rather than letting a
            // failed inner call mark the transaction, turns the failure into the RJCT response instead of a 500.
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return buildErrorResponse(request, startTime, "RJCT", e.getMessage());
        } catch (Exception e) {
            log.error("ISO 20022 outward payment error: ", e);
            request.setAmount(instructed);
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
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
     * The POBO route of a payment by its paying entity's enrolment for {@code currency} (spec R4): the owner of
     * {@code behalfOfVaId} for an explicit POBO request, else the source account's owner. An explicit POBO request for an
     * entity that is not enrolled is refused: POBO is set up by enrolment.
     */
    private PoboEnrolmentService.Route poboRoute(VirtualAccount sourceVa, boolean explicitPobo, UUID behalfOfVaId, String currency) {
        VirtualAccount owner = sourceVa;
        if (explicitPobo) {
            if (behalfOfVaId == null) throw new BusinessException("behalfOfVaId is required for POBO payments");
            owner = virtualAccountRepository.findById(behalfOfVaId)
                    .orElseThrow(() -> new BusinessException("Behalf-of account not found: " + behalfOfVaId));
        }
        String ccy = currency != null ? currency : sourceVa.getCurrencyCode();
        PoboEnrolmentService.Route route = poboEnrolments.route(owner.getOwningEntityId(), ccy, LocalDate.now()).orElse(null);
        if (route == null && explicitPobo) {
            throw new BusinessException("Not available: " + (owner.getOwningEntityCode() != null ? owner.getOwningEntityCode()
                    : owner.getVaNumber()) + " is not enrolled for payment on its behalf in " + ccy
                    + "; POBO is set up by enrolment (In-House Bank)");
        }
        return route;
    }

    /** The treasury account an IHB current account that is not enrolled pays through (its treasury link). */
    private static UUID legacyPayer(VirtualAccount ihbCurrentAccount) {
        if (ihbCurrentAccount.getTreasuryPoolVaId() == null) {
            throw new BusinessException("IHB Current Account " + ihbCurrentAccount.getVaNumber() +
                " has no Treasury Settlement VA configured (treasuryPoolVaId is null). Cannot execute POBO payment.");
        }
        return ihbCurrentAccount.getTreasuryPoolVaId();
    }

    /** The pain.001 debtor of an in-house payment not paid by an enrolment: the treasury account it pays through. */
    private PoboEnrolmentService.PainDebtor legacyDebtor(VirtualAccount ihbCurrentAccount) {
        UUID payerId = legacyPayer(ihbCurrentAccount);
        VirtualAccount payer = virtualAccountRepository.findById(payerId)
                .orElseThrow(() -> new BusinessException("Treasury paying account not found: " + payerId));
        return poboEnrolments.inHouseDebtor(ihbCurrentAccount, payer, transactionService.payoutShadow(payer));
    }

    /**
     * The in-house posting: {@code owner} (an IHB current account) bears the payment, {@code payerVaId} pays it out
     * (6-leg; 4-leg when the account lacks its in-house links). TransactionService checks the IHB credit limit first.
     */
    private Transaction postInHouse(VirtualAccount owner, UUID payerVaId, BigDecimal amount, String creditorName,
                                    String creditorAccount, String creditorBic, String creditorBankName, String description,
                                    LocalDate valueDate, String channel) {
        log.info("In-house payment: owner={} payer={} amount={} channel={}", owner.getVaNumber(), payerVaId, amount, channel);
        return transactionService.makeIhb6LegPoboPayment(TransactionDto.PoboPaymentRequest.builder()
                .ownerVaId(owner.getId())
                .payerVaId(payerVaId)
                .amount(amount)
                .beneficiaryName(creditorName)
                .beneficiaryAccount(creditorAccount)
                .beneficiaryBankSwift(creditorBic)
                .beneficiaryBankName(creditorBankName)
                .description(description)
                .valueDate(valueDate)
                .channel(channel)
                .build());
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

            List<PaymentInstructionResult> results = new ArrayList<>();
            int successCount = 0;
            int failedCount = 0;
            BigDecimal totalAmount = BigDecimal.ZERO;
            BigDecimal totalFees = BigDecimal.ZERO;

            // POBO by enrolment per row (each row's currency decides, spec R4); a refused row fails alone.
            List<PaymentInstruction> lines = request.getInstructions();
            List<Object> routes = new ArrayList<>();
            List<PoboEnrolmentService.PainDebtor> debtors = new ArrayList<>(); // per row, D6; null when not on behalf
            for (PaymentInstruction l : lines) {
                try {
                    PoboEnrolmentService.Route r = poboRoute(sourceVa, request.isPobo(), request.getBehalfOfVaId(), l.getCurrency());
                    PoboEnrolmentService.PainDebtor d = r != null ? poboEnrolments.painDebtor(r)
                            : Boolean.TRUE.equals(sourceVa.getIhbParticipant()) ? legacyDebtor(sourceVa) : null;
                    routes.add(r);
                    debtors.add(d);
                } catch (BusinessException e) {
                    routes.add(e);
                    debtors.add(null);
                }
            }
            List<Iso20022XmlBuilder.BulkLine> paidLines = new ArrayList<>();

            // Each instruction in its own transaction: a row that fails (no funds, an FX price outside the streaming
            // limits, a deal used up) rolls back alone, its FX booking with it, and the other rows still pay. In one
            // transaction, one failed inner call marked the whole batch rollback-only.
            TransactionTemplate rowTx = new TransactionTemplate(transactionManager);
            rowTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            // FX for the batch first, committed before any row: per currency, the deals that fit cover the batch's
            // total, one deal is booked for what they leave (within the streaming limit), and rows draw in order.
            // POBO rows convert on the treasury's payment account (ihb-fx D3; same-currency ones need none); refused
            // rows need no FX (amount null leaves them out of the batch's deals).
            List<FxPaymentService.BatchFx> batchFx = rowTx.execute(status -> {
                List<FxPaymentService.BatchRow> rows = new ArrayList<>();
                for (int n = 0; n < lines.size(); n++) {
                    PaymentInstruction l = lines.get(n);
                    rows.add(new FxPaymentService.BatchRow(
                            routes.get(n) instanceof PoboEnrolmentService.Route r ? r.paymentVa() : sourceVa, l.getCurrency(),
                            ownFx(l) || routes.get(n) instanceof BusinessException ? null : l.getAmount()));
                }
                return fxPayments.prepareBatch(rows);
            });
            for (int n = 0; n < lines.size(); n++) {
                PaymentInstruction instruction = lines.get(n);
                FxPaymentService.BatchFx planned = batchFx.get(n);
                Object route = routes.get(n);
                if (route instanceof BusinessException refused) {
                    failedCount++;
                    results.add(PaymentInstructionResult.builder()
                            .instructionId(instruction.getInstructionId())
                            .endToEndId(instruction.getEndToEndId())
                            .success(false)
                            .errorCode("RJCT")
                            .errorMessage(refused.getMessage())
                            .build());
                    continue;
                }
                PoboEnrolmentService.Route pobo = (PoboEnrolmentService.Route) route;
                if (!ownFx(instruction) && planned.instruction() == null) {
                    failedCount++;
                    results.add(PaymentInstructionResult.builder()
                            .instructionId(instruction.getInstructionId())
                            .endToEndId(instruction.getEndToEndId())
                            .success(false)
                            .errorCode("AWAITING_FX")
                            .errorMessage(planned.held())
                            .build());
                    continue;
                }
                FxPaymentService.Instruction fxFor = ownFx(instruction)
                        ? FxPaymentService.Instruction.of(instruction.getFxPriceId(), instruction.getFxQuoteId(), instruction.getFxDealId(),
                                instruction.getFxDeals(), instruction.getFxAtMarket())
                        : planned.instruction();
                try {
                    Paid paid = rowTx.execute(status -> payRow(request, instruction, sourceVa, pobo, fxFor));
                    Transaction txn = paid.txn();
                    BigDecimal debit = paid.debit();
                    paidLines.add(new Iso20022XmlBuilder.BulkLine(txn, instruction, debtors.get(n)));
                    successCount++;
                    totalAmount = totalAmount.add(debit);
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
            String pain001Xml = xmlBuilder.buildBulkPain001(request, sourceVa, paidLines);

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


    private record Paid(Transaction txn, BigDecimal debit) {
    }

    /** A row naming its own FX keeps it; the others take the batch's. */
    private static boolean ownFx(PaymentInstruction l) {
        return l.getFxPriceId() != null || l.getFxQuoteId() != null || l.getFxDealId() != null
                || (l.getFxDeals() != null && !l.getFxDeals().isEmpty()) || l.getFxAtMarket() != null;
    }

    /** One bulk instruction: POBO by enrolment; else converted where its currency is not the account's, then paid. */
    private Paid payRow(BulkPaymentRequest request, PaymentInstruction instruction, VirtualAccount sourceVa,
                        PoboEnrolmentService.Route pobo, FxPaymentService.Instruction fxFor) {
        if (pobo != null) {
            FxPaymentService.Conversion fx = pobo.cover(fxPayments, instruction.getAmount(), fxFor);
            BigDecimal debit = fx == null ? instruction.getAmount() : fx.debitAmount();
            Transaction txn = postInHouse(pobo.ihbCurrent(), pobo.paymentVa().getId(), debit,
                    instruction.getCreditorName(), instruction.getCreditorAccount(), instruction.getCreditorBic(), null,
                    instruction.getRemittanceInfo(), request.getRequestedExecutionDate(), "ISO20022_BULK_POBO");
            if (fx != null) transactionService.settlePoboFx(fx, txn, pobo.paymentVa());
            return new Paid(txn, debit);
        }
        FxPaymentService.Conversion fx = fxPayments.convert(sourceVa, instruction.getCurrency(), instruction.getAmount(), fxFor);
        BigDecimal debit = fx == null ? instruction.getAmount() : fx.debitAmount();
        Transaction txn;

        if (Boolean.TRUE.equals(sourceVa.getIhbParticipant())) {
            // IHB current account not enrolled: the in-house posting as before (payer from its treasury link)
            txn = postInHouse(sourceVa, legacyPayer(sourceVa), debit, instruction.getCreditorName(),
                    instruction.getCreditorAccount(), instruction.getCreditorBic(), null,
                    instruction.getRemittanceInfo(), request.getRequestedExecutionDate(), "ISO20022_BULK_IHB_POBO");
        } else {
            // Standard bulk - each instruction goes through makePayment
            TransactionDto.PaymentRequest paymentRequest = TransactionDto.PaymentRequest.builder()
                    .fromVaId(request.getSourceVaId())
                    .amount(debit)
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

        if (fx != null) {
            fxPayments.settle(fx, txn, sourceVa);
        }
        return new Paid(txn, debit);
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