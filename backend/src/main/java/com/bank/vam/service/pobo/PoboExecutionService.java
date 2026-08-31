package com.bank.vam.service.pobo;

import com.bank.vam.dto.pobo.PoboDto.*;
import com.bank.vam.dto.treasury.IhbDto;
import com.bank.vam.entity.payables.Payable;
import com.bank.vam.entity.pobo.IntercompanyRecharge;
import com.bank.vam.entity.pobo.PoboAuthorization;
import com.bank.vam.entity.treasury.IhbLoan;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.payables.PayableRepository;
import com.bank.vam.repository.pobo.IntercompanyRechargeRepository;
import com.bank.vam.repository.pobo.PoboAuthorizationRepository;
import com.bank.vam.service.party.PartyService;
import com.bank.vam.service.tax.ChargeService;
import com.bank.vam.service.treasury.FeePostingService;
import com.bank.vam.service.treasury.IhbUnifiedService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Phase 5 Task 5.1: POBO Execution Service
 * 
 * Complete POBO execution pipeline:
 * - Validates authorization (5.2)
 * - Executes payment on behalf of subsidiary
 * - Creates IntercompanyRecharge record (5.3)
 * - Optionally creates IHB loan for reimbursement (5.4)
 * 
 * Integration Points:
 * - PayablesService: Source payable management
 * - PoboService: Authorization validation
 * - IhbUnifiedService: IHB loan creation
 * - ChargeService: Fee calculation
 * - PartyService: Vendor eligibility
 * 
 * @see <a href="https://www.capgemini.com/wp-content/uploads/2020/12/Virtual-Acc-Management-in-Transaction-Banking.pdf">
 *      Capgemini VAM Reference - POBO/COBO</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PoboExecutionService {

    private final PayableRepository payableRepository;
    private final PoboAuthorizationRepository authorizationRepository;
    private final IntercompanyRechargeRepository rechargeRepository;
    private final IhbUnifiedService ihbUnifiedService;
    private final ChargeService chargeService;
    private final PartyService partyService;
    private final PoboService poboService;
    private final FeePostingService feePostingService;

    private static final AtomicLong executionSequence = new AtomicLong(1);
    
    // Fee constants
    private static final BigDecimal POBO_PROCESSING_FEE = new BigDecimal("25.00");
    private static final BigDecimal POBO_FX_MARKUP_RATE = new BigDecimal("0.0025"); // 0.25%

    // ========================================================================
    // PHASE 5 TASK 5.1: EXECUTE POBO PAYMENT
    // ========================================================================
    
    /**
     * Execute a POBO payment for a single payable.
     * 
     * Flow:
     * 1. Validate POBO authorization
     * 2. Check vendor eligibility
     * 3. Check limit availability
     * 4. Execute payment (via treasury VA)
     * 5. Create intercompany recharge
     * 6. Optionally create IHB loan
     * 
     * @param request Execution request with payable and entity details
     * @return Execution result with recharge and optional IHB loan details
     */
    @Transactional
    public PoboExecutionResult executePoboPayment(PoboExecuteRequest request) {
        log.info("Executing POBO payment for payable: {}", request.getPayableId());
        
        // 1. Load payable
        Payable payable = payableRepository.findById(request.getPayableId())
            .orElseThrow(() -> new ResourceNotFoundException("Payable not found: " + request.getPayableId()));
        
        // Validate payable status
        if (!payable.canRequestPobo()) {
            throw new BusinessException("Payable cannot be processed for POBO: " + payable.getStatus());
        }
        
        // 2. Validate POBO authorization (Task 5.2)
        PoboValidationResult validation = validatePoboExecution(
            request.getTreasuryEntityId(),
            payable.getOwningEntityId(),
            payable.getNetAmount(),
            payable.getCurrencyCode(),
            payable.getPartyId()
        );
        
        if (!validation.isAuthorized()) {
            throw new BusinessException("POBO not authorized: " + validation.getRejectionReason());
        }
        
        // 3. Generate execution reference
        String executionRef = generateExecutionReference();
        UUID executionId = UUID.randomUUID();
        
        // 4. Execute payment (mock - would integrate with payment gateway)
        PaymentExecutionResult paymentResult = executePaymentViaTreasury(
            request.getTreasuryEntityId(),
            request.getTreasuryVaId(),
            payable,
            executionRef
        );
        
        if (!paymentResult.isSuccess()) {
            // Revert payable status
            payable.rejectPobo(request.getExecutedBy(), paymentResult.getErrorMessage());
            payableRepository.save(payable);
            
            return PoboExecutionResult.builder()
                .success(false)
                .payableId(payable.getId())
                .errorMessage(paymentResult.getErrorMessage())
                .build();
        }
        
        // 5. Create intercompany recharge (Task 5.3)
        IntercompanyRecharge recharge = createPoboRecharge(
            payable,
            request.getTreasuryEntityId(),
            request.getTreasuryEntityCode(),
            request.getTreasuryEntityName(),
            request.getTreasuryVaId(),
            paymentResult.getTransactionRef(),
            executionId,
            validation
        );
        
        // 6. Optionally create IHB loan (Task 5.4)
        IhbLoanResult ihbLoanResult = null;
        if (Boolean.TRUE.equals(request.getCreateIhbLoan()) && validation.isIhbEnabled()) {
            ihbLoanResult = createIhbLoanForRecharge(recharge, request);
            
            if (ihbLoanResult != null && ihbLoanResult.isSuccess()) {
                // Link loan to recharge
                recharge.settleViaIhbLoan(ihbLoanResult.getLoanId(), ihbLoanResult.getLoanReference());
                rechargeRepository.save(recharge);
            }
        }
        
        // 7. Update payable status
        payable.markPoboExecuted(
            paymentResult.getTransactionRef(),
            ihbLoanResult != null ? ihbLoanResult.getLoanId() : null,
            recharge.getId()
        );
        payableRepository.save(payable);
        
        // 8. Record usage against authorization
        poboService.recordPoboUsage(
            request.getTreasuryEntityId(),
            payable.getOwningEntityId(),
            payable.getNetAmount()
        );
        
        log.info("POBO payment executed: {} -> recharge: {}, IHB loan: {}", 
            executionRef, 
            recharge.getRechargeReference(),
            ihbLoanResult != null ? ihbLoanResult.getLoanReference() : "N/A");
        
        return PoboExecutionResult.builder()
            .success(true)
            .payableId(payable.getId())
            .payableNumber(payable.getPayableNumber())
            .executionId(executionId)
            .executionReference(executionRef)
            .paymentTransactionRef(paymentResult.getTransactionRef())
            .paidAmount(payable.getNetAmount())
            .currency(payable.getCurrencyCode())
            .rechargeId(recharge.getId())
            .rechargeReference(recharge.getRechargeReference())
            .rechargeAmount(recharge.getRechargeAmount())
            .serviceFee(recharge.getServiceFee())
            .totalRecharge(recharge.getTotalRecharge())
            .ihbLoanCreated(ihbLoanResult != null && ihbLoanResult.isSuccess())
            .ihbLoanId(ihbLoanResult != null ? ihbLoanResult.getLoanId() : null)
            .ihbLoanReference(ihbLoanResult != null ? ihbLoanResult.getLoanReference() : null)
            .executedAt(LocalDateTime.now())
            .executedBy(request.getExecutedBy())
            .build();
    }

    // ========================================================================
    // PHASE 5 TASK 5.2: POBO VALIDATION
    // ========================================================================
    
    /**
     * Comprehensive POBO validation.
     * 
     * Checks:
     * - Authorization exists and is active
     * - Vendor is POBO-eligible
     * - Amount within limits (single, daily, monthly)
     * - Currency is supported
     */
    public PoboValidationResult validatePoboExecution(
            UUID treasuryEntityId, 
            UUID subsidiaryEntityId,
            BigDecimal amount, 
            String currency,
            UUID vendorPartyId) {
        
        List<String> warnings = new ArrayList<>();
        
        // 1. Find authorization
        Optional<PoboAuthorization> authOpt = authorizationRepository.findActiveAuthorization(
            treasuryEntityId, subsidiaryEntityId);
        
        if (authOpt.isEmpty()) {
            return PoboValidationResult.builder()
                .authorized(false)
                .rejectionReason("No active POBO authorization between treasury and subsidiary")
                .build();
        }
        
        PoboAuthorization auth = authOpt.get();
        
        // 2. Check if valid (not expired, not suspended)
        if (!auth.isValid()) {
            return PoboValidationResult.builder()
                .authorized(false)
                .authorizationCode(auth.getAuthorizationCode())
                .rejectionReason("Authorization is invalid: " + auth.getStatus())
                .build();
        }
        
        // 3. Check vendor eligibility
        boolean vendorAllowed = true;
        if (vendorPartyId != null && auth.getAllowedVendorIds() != null && !auth.getAllowedVendorIds().isEmpty()) {
            vendorAllowed = auth.isVendorAllowed(vendorPartyId);
        }
        if (!vendorAllowed) {
            return PoboValidationResult.builder()
                .authorized(false)
                .authorizationCode(auth.getAuthorizationCode())
                .rejectionReason("Vendor not in allowed vendor list")
                .build();
        }
        
        // 4. Check limits
        if (!auth.canAuthorize(amount)) {
            BigDecimal maxAvailable = auth.getMaxAvailable();
            return PoboValidationResult.builder()
                .authorized(false)
                .authorizationCode(auth.getAuthorizationCode())
                .rejectionReason(String.format("Amount %.2f exceeds available limit %.2f", amount, maxAvailable))
                .remainingDailyLimit(auth.getRemainingDailyLimit())
                .remainingMonthlyLimit(auth.getRemainingMonthlyLimit())
                .build();
        }
        
        // 5. Add warnings if approaching limits
        BigDecimal remainingDaily = auth.getRemainingDailyLimit();
        if (remainingDaily != null) {
            BigDecimal threshold = remainingDaily.multiply(new BigDecimal("0.8"));
            if (amount.compareTo(threshold) > 0) {
                warnings.add("Approaching daily limit (80% threshold)");
            }
        }
        
        BigDecimal remainingMonthly = auth.getRemainingMonthlyLimit();
        if (remainingMonthly != null) {
            BigDecimal threshold = remainingMonthly.multiply(new BigDecimal("0.9"));
            if (amount.compareTo(threshold) > 0) {
                warnings.add("Approaching monthly limit (90% threshold)");
            }
        }
        
        // 6. Check IHB availability
        boolean ihbEnabled = auth.getAutoRecharge() != null && auth.getAutoRecharge();
        
        return PoboValidationResult.builder()
            .authorized(true)
            .authorizationId(auth.getId())
            .authorizationCode(auth.getAuthorizationCode())
            .remainingDailyLimit(remainingDaily)
            .remainingMonthlyLimit(remainingMonthly)
            .serviceFeeRate(auth.getRechargeServiceFeeRate())
            .requiresApproval(auth.getRequiresApproval())
            .ihbEnabled(ihbEnabled)
            .warnings(warnings)
            .build();
    }

    // ========================================================================
    // PHASE 5 TASK 5.3: CREATE INTERCOMPANY RECHARGE
    // ========================================================================
    
    /**
     * Create intercompany recharge record after POBO payment.
     */
    private IntercompanyRecharge createPoboRecharge(
            Payable payable,
            UUID treasuryEntityId,
            String treasuryEntityCode,
            String treasuryEntityName,
            UUID treasuryVaId,
            String paymentTransactionRef,
            UUID executionId,
            PoboValidationResult validation) {
        
        // Calculate fees
        BigDecimal serviceFee = calculateServiceFee(payable.getNetAmount(), validation.getServiceFeeRate());
        BigDecimal fxMarkup = calculateFxMarkup(payable.getNetAmount(), payable.getCurrencyCode());
        BigDecimal adminFee = POBO_PROCESSING_FEE;
        
        BigDecimal totalRecharge = payable.getNetAmount()
            .add(serviceFee)
            .add(fxMarkup)
            .add(adminFee);
        
        IntercompanyRecharge recharge = IntercompanyRecharge.builder()
            .rechargeReference(generateRechargeReference())
            .payerEntityId(treasuryEntityId)
            .payerEntityCode(treasuryEntityCode)
            .payerEntityName(treasuryEntityName)
            .payerVaId(treasuryVaId)
            .behalfEntityId(payable.getOwningEntityId())
            .behalfEntityCode(payable.getOwningEntityCode())
            .behalfEntityName(payable.getOwningEntityName())
            .behalfVaId(payable.getVirtualAccountId())
            .originalPayableId(payable.getId())
            .originalPaymentExecutionId(executionId)
            .originalPaymentReference(paymentTransactionRef)
            .originalAmount(payable.getNetAmount())
            .rechargeAmount(payable.getNetAmount())
            .currencyCode(payable.getCurrencyCode())
            .serviceFee(serviceFee)
            .adminFee(adminFee)
            .fxMarkup(fxMarkup)
            .totalRecharge(totalRecharge)
            .approvalRequired(validation.getRequiresApproval())
            .status(IntercompanyRecharge.RechargeStatus.PENDING)
            // POBO-specific: treasury pays vendor on behalf of subsidiary (funds leave the group)
            .rechargeType(IntercompanyRecharge.RechargeType.POBO_PAYMENT)
            .flowDirection(IntercompanyRecharge.FlowDirection.OUTBOUND)
            .build();
        
        // Auto-approve if not requiring approval
        if (!Boolean.TRUE.equals(validation.getRequiresApproval())) {
            recharge.approve("SYSTEM_AUTO");
        }
        
        recharge = rechargeRepository.save(recharge);
        log.info("Created POBO recharge: {} for {} {}",
            recharge.getRechargeReference(),
            recharge.getCurrencyCode(),
            recharge.getTotalRecharge());

        // Post POBO fees as separate ledger entries
        postPoboFees(recharge, payable.getVirtualAccountId(), treasuryVaId);

        return recharge;
    }

    /**
     * Post POBO fee transactions to the ledger.
     * Creates separate FEE debit on subsidiary VA and FEE_CREDIT on settlement VA.
     */
    private void postPoboFees(IntercompanyRecharge recharge, UUID subsidiaryVaId, UUID treasuryVaId) {
        String baseCorrelationId = "POBO-FEE-" + recharge.getRechargeReference();

        // Post service fee
        if (recharge.getServiceFee() != null && recharge.getServiceFee().compareTo(BigDecimal.ZERO) > 0) {
            try {
                feePostingService.postFeePair(
                    subsidiaryVaId,
                    recharge.getServiceFee(),
                    "POBO_SERVICE",
                    recharge.getId(),                           // referenceId (UUID)
                    baseCorrelationId + "-SVC",                 // correlationId (String)
                    "POBO Service Fee for " + recharge.getRechargeReference()
                );
                log.debug("Posted POBO service fee: {} for {}", recharge.getServiceFee(), recharge.getRechargeReference());
            } catch (Exception e) {
                log.warn("Failed to post POBO service fee for {}: {}", recharge.getRechargeReference(), e.getMessage());
            }
        }

        // Post admin/processing fee
        if (recharge.getAdminFee() != null && recharge.getAdminFee().compareTo(BigDecimal.ZERO) > 0) {
            try {
                feePostingService.postFeePair(
                    subsidiaryVaId,
                    recharge.getAdminFee(),
                    "POBO_PROCESSING",
                    recharge.getId(),                           // referenceId (UUID)
                    baseCorrelationId + "-ADM",                 // correlationId (String)
                    "POBO Processing Fee for " + recharge.getRechargeReference()
                );
                log.debug("Posted POBO admin fee: {} for {}", recharge.getAdminFee(), recharge.getRechargeReference());
            } catch (Exception e) {
                log.warn("Failed to post POBO admin fee for {}: {}", recharge.getRechargeReference(), e.getMessage());
            }
        }

        // Post FX markup fee
        if (recharge.getFxMarkup() != null && recharge.getFxMarkup().compareTo(BigDecimal.ZERO) > 0) {
            try {
                feePostingService.postFeePair(
                    subsidiaryVaId,
                    recharge.getFxMarkup(),
                    "POBO_FX_MARKUP",
                    recharge.getId(),                           // referenceId (UUID)
                    baseCorrelationId + "-FX",                  // correlationId (String)
                    "POBO FX Markup for " + recharge.getRechargeReference()
                );
                log.debug("Posted POBO FX markup: {} for {}", recharge.getFxMarkup(), recharge.getRechargeReference());
            } catch (Exception e) {
                log.warn("Failed to post POBO FX markup for {}: {}", recharge.getRechargeReference(), e.getMessage());
            }
        }
    }

    // ========================================================================
    // PHASE 5 TASK 5.4: IHB LOAN INTEGRATION
    // ========================================================================
    
    /**
     * Create IHB loan to finance the POBO recharge.
     * 
     * When treasury pays on behalf of subsidiary:
     * - Subsidiary effectively "borrows" from treasury
     * - IHB loan formalizes this with interest
     */
    private IhbLoanResult createIhbLoanForRecharge(
            IntercompanyRecharge recharge, 
            PoboExecuteRequest request) {
        
        try {
            // Calculate loan maturity based on recharge terms
            LocalDate maturityDate = request.getLoanMaturityDate() != null ?
                request.getLoanMaturityDate() :
                LocalDate.now().plusDays(30); // Default 30-day loan
            
            IhbDto.CreateLoanUnifiedRequest loanRequest = new IhbDto.CreateLoanUnifiedRequest();
            loanRequest.setLenderEntityId(recharge.getPayerEntityId());
            loanRequest.setBorrowerEntityId(recharge.getBehalfEntityId());
            loanRequest.setPrincipalAmount(recharge.getTotalRecharge());
            loanRequest.setCurrencyCode(recharge.getCurrencyCode());
            loanRequest.setDisbursementDate(LocalDate.now());
            loanRequest.setMaturityDate(maturityDate);
            loanRequest.setInterestType(IhbLoan.InterestType.FIXED);
            loanRequest.setRepaymentFrequency(IhbLoan.RepaymentFrequency.BULLET);
            
            // Optional: Use specific interest rate from request
            if (request.getIhbInterestRate() != null) {
                loanRequest.setBaseRate(request.getIhbInterestRate());
            }
            
            IhbDto.LoanResponse loan = ihbUnifiedService.createLoan(loanRequest);
            
            log.info("Created IHB loan {} for POBO recharge {}", 
                loan.getLoanReference(), recharge.getRechargeReference());
            
            return IhbLoanResult.builder()
                .success(true)
                .loanId(loan.getId())
                .loanReference(loan.getLoanReference())
                .principalAmount(loan.getPrincipalAmount())
                .interestRate(loan.getInterestRate())
                .maturityDate(loan.getMaturityDate())
                .build();
                
        } catch (BusinessException e) {
            log.warn("IHB loan creation failed for recharge {}: {}", 
                recharge.getRechargeReference(), e.getMessage());
            return IhbLoanResult.builder()
                .success(false)
                .errorMessage(e.getMessage())
                .build();
        }
    }

    // ========================================================================
    // PHASE 5 TASK 5.6: BATCH POBO PROCESSING
    // ========================================================================
    
    /**
     * Process multiple payables in a single POBO batch.
     * 
     * Benefits:
     * - Single authorization check for batch
     * - Consolidated recharge records
     * - Potential for combined IHB loan
     */
    @Transactional
    public BatchPoboResult executeBatchPobo(BatchPoboExecuteRequest request) {
        log.info("Executing batch POBO for {} payables", request.getPayableIds().size());
        
        List<PoboExecutionResult> results = new ArrayList<>();
        int successCount = 0;
        int failedCount = 0;
        BigDecimal totalPaid = BigDecimal.ZERO;
        BigDecimal totalRecharged = BigDecimal.ZERO;
        
        for (UUID payableId : request.getPayableIds()) {
            try {
                PoboExecuteRequest singleRequest = PoboExecuteRequest.builder()
                    .payableId(payableId)
                    .treasuryEntityId(request.getTreasuryEntityId())
                    .treasuryEntityCode(request.getTreasuryEntityCode())
                    .treasuryEntityName(request.getTreasuryEntityName())
                    .treasuryVaId(request.getTreasuryVaId())
                    .createIhbLoan(request.getCreateIhbLoan())
                    .loanMaturityDate(request.getLoanMaturityDate())
                    .executedBy(request.getExecutedBy())
                    .build();
                
                PoboExecutionResult result = executePoboPayment(singleRequest);
                results.add(result);
                
                if (result.isSuccess()) {
                    successCount++;
                    totalPaid = totalPaid.add(result.getPaidAmount());
                    totalRecharged = totalRecharged.add(result.getTotalRecharge());
                } else {
                    failedCount++;
                }
                
            } catch (Exception e) {
                log.error("Batch POBO failed for payable {}: {}", payableId, e.getMessage());
                results.add(PoboExecutionResult.builder()
                    .success(false)
                    .payableId(payableId)
                    .errorMessage(e.getMessage())
                    .build());
                failedCount++;
            }
        }
        
        log.info("Batch POBO completed: {} success, {} failed, total paid: {}", 
            successCount, failedCount, totalPaid);
        
        return BatchPoboResult.builder()
            .batchId(UUID.randomUUID())
            .totalPayables(request.getPayableIds().size())
            .successCount(successCount)
            .failedCount(failedCount)
            .totalPaidAmount(totalPaid)
            .totalRechargeAmount(totalRecharged)
            .results(results)
            .executedAt(LocalDateTime.now())
            .executedBy(request.getExecutedBy())
            .build();
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================
    
    private String generateExecutionReference() {
        String date = LocalDate.now().toString().replace("-", "");
        return String.format("POBO-EXE-%s-%06d", date, executionSequence.getAndIncrement());
    }
    
    private String generateRechargeReference() {
        String date = LocalDate.now().toString().replace("-", "");
        return String.format("ICR-%s-%06d", date, System.currentTimeMillis() % 1000000);
    }
    
    private BigDecimal calculateServiceFee(BigDecimal amount, BigDecimal feeRate) {
        if (feeRate == null || feeRate.compareTo(BigDecimal.ZERO) <= 0) {
            return chargeService.calculatePoboFee(amount);
        }
        
        return chargeService.calculatePoboFee(
            amount, 
            feeRate,
            new BigDecimal("25"),   // min
            new BigDecimal("1000")  // max
        );
    }
    
    private BigDecimal calculateFxMarkup(BigDecimal amount, String currency) {
        // Only apply FX markup for non-base currencies
        if ("AED".equals(currency)) {
            return BigDecimal.ZERO;
        }
        return amount.multiply(POBO_FX_MARKUP_RATE).setScale(2, RoundingMode.HALF_UP);
    }
    
    /**
     * Execute payment via treasury (mock implementation).
     * In production, this would integrate with payment gateway/CBS.
     */
    private PaymentExecutionResult executePaymentViaTreasury(
            UUID treasuryEntityId,
            UUID treasuryVaId,
            Payable payable,
            String executionRef) {
        
        // TODO: Integrate with actual payment gateway
        // For now, simulate successful payment
        
        String transactionRef = "TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        
        log.info("Executed payment via treasury VA: {} for {} {} to vendor {}", 
            transactionRef, 
            payable.getCurrencyCode(), 
            payable.getNetAmount(),
            payable.getVendorName());
        
        return PaymentExecutionResult.builder()
            .success(true)
            .transactionRef(transactionRef)
            .amount(payable.getNetAmount())
            .currency(payable.getCurrencyCode())
            .build();
    }

    // ========================================================================
    // RESULT CLASSES
    // ========================================================================
    
    @Data
    @Builder
    public static class PoboValidationResult {
        private boolean authorized;
        private UUID authorizationId;
        private String authorizationCode;
        private String rejectionReason;
        private BigDecimal remainingDailyLimit;
        private BigDecimal remainingMonthlyLimit;
        private BigDecimal serviceFeeRate;
        private Boolean requiresApproval;
        private boolean ihbEnabled;
        private List<String> warnings;
    }
    
    @Data
    @Builder
    public static class PoboExecutionResult {
        private boolean success;
        private UUID payableId;
        private String payableNumber;
        private UUID executionId;
        private String executionReference;
        private String paymentTransactionRef;
        private BigDecimal paidAmount;
        private String currency;
        private UUID rechargeId;
        private String rechargeReference;
        private BigDecimal rechargeAmount;
        private BigDecimal serviceFee;
        private BigDecimal totalRecharge;
        private boolean ihbLoanCreated;
        private UUID ihbLoanId;
        private String ihbLoanReference;
        private LocalDateTime executedAt;
        private String executedBy;
        private String errorMessage;
    }
    
    @Data
    @Builder
    public static class BatchPoboResult {
        private UUID batchId;
        private int totalPayables;
        private int successCount;
        private int failedCount;
        private BigDecimal totalPaidAmount;
        private BigDecimal totalRechargeAmount;
        private List<PoboExecutionResult> results;
        private LocalDateTime executedAt;
        private String executedBy;
    }
    
    @Data
    @Builder
    public static class IhbLoanResult {
        private boolean success;
        private UUID loanId;
        private String loanReference;
        private BigDecimal principalAmount;
        private BigDecimal interestRate;
        private LocalDate maturityDate;
        private String errorMessage;
    }
    
    @Data
    @Builder
    public static class PaymentExecutionResult {
        private boolean success;
        private String transactionRef;
        private BigDecimal amount;
        private String currency;
        private String errorMessage;
    }
    
    // ========================================================================
    // REQUEST/RESPONSE DTOs
    // ========================================================================
    
    @Data
    @Builder
    public static class PoboExecuteRequest {
        private UUID payableId;
        private UUID treasuryEntityId;
        private String treasuryEntityCode;
        private String treasuryEntityName;
        private UUID treasuryVaId;
        private Boolean createIhbLoan;
        private LocalDate loanMaturityDate;
        private BigDecimal ihbInterestRate;
        private String executedBy;
    }
    
    @Data
    @Builder
    public static class BatchPoboExecuteRequest {
        private List<UUID> payableIds;
        private UUID treasuryEntityId;
        private String treasuryEntityCode;
        private String treasuryEntityName;
        private UUID treasuryVaId;
        private Boolean createIhbLoan;
        private LocalDate loanMaturityDate;
        private String executedBy;
    }
}