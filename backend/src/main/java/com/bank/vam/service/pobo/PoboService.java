package com.bank.vam.service.pobo;

import com.bank.vam.dto.pobo.PoboDto.*;
import com.bank.vam.dto.treasury.IhbDto;
import com.bank.vam.entity.pobo.*;
import com.bank.vam.entity.treasury.IhbLoan;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.pobo.*;
import com.bank.vam.service.tax.ChargeService;
import com.bank.vam.service.treasury.IhbUnifiedService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * POBO Service - Pay On Behalf Of validation and management.
 * 
 * Phase 5 Enhanced Features:
 * - Authorization management between entities
 * - Payment validation against limits
 * - Intercompany recharge creation
 * - IHB integration for settlements (Phase 5)
 * - Transfer pricing compliance
 * 
 * VAM Compliance:
 * - Centralized treasury payments
 * - Arm's length validation
 * - Regulatory documentation
 * 
 * Integration Points:
 * - Payables module for payment processing
 * - IHB module for intercompany loans (Phase 5)
 * - Netting module for settlement cycles
 * 
 * @see <a href="https://www.capgemini.com/wp-content/uploads/2020/12/Virtual-Acc-Management-in-Transaction-Banking.pdf">
 *      Capgemini VAM Reference</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PoboService {

    private final PoboAuthorizationRepository authorizationRepository;
    private final IntercompanyRechargeRepository rechargeRepository;
    private final ChargeService chargeService;
    private final IhbUnifiedService ihbUnifiedService;  // Phase 5: IHB integration
    private final com.bank.vam.config.MarketProfileProperties marketProfile;

    private static final AtomicLong authSequence = new AtomicLong(1);
    private static final AtomicLong rechargeSequence = new AtomicLong(1);

    // ========================================================================
    // AUTHORIZATION MANAGEMENT
    // ========================================================================

    @Transactional(readOnly = true)
    public List<AuthorizationResponse> getAllAuthorizations() {
        return authorizationRepository.findAllActive().stream()
            .map(this::toAuthorizationResponse)
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public AuthorizationResponse getAuthorization(UUID id) {
        PoboAuthorization auth = authorizationRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Authorization not found: " + id));
        return toAuthorizationResponse(auth);
    }

    @Transactional(readOnly = true)
    public AuthorizationResponse getAuthorizationByCode(String code) {
        PoboAuthorization auth = authorizationRepository.findByAuthorizationCode(code)
            .orElseThrow(() -> new ResourceNotFoundException("Authorization not found: " + code));
        return toAuthorizationResponse(auth);
    }

    @Transactional(readOnly = true)
    public List<AuthorizationResponse> getAuthorizationsForPayer(UUID payerEntityId) {
        return authorizationRepository.findActiveByPayer(payerEntityId).stream()
            .map(this::toAuthorizationResponse)
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<AuthorizationResponse> getAuthorizationsForBehalf(UUID behalfEntityId) {
        return authorizationRepository.findActiveByBehalf(behalfEntityId).stream()
            .map(this::toAuthorizationResponse)
            .collect(Collectors.toList());
    }

    @Transactional
    public AuthorizationResponse createAuthorization(CreateAuthorizationRequest request) {
        // Check for existing active authorization
        Optional<PoboAuthorization> existing = authorizationRepository.findActiveAuthorization(
            request.getPayerEntityId(), request.getBehalfEntityId());
        if (existing.isPresent()) {
            throw new BusinessException("Active authorization already exists for this entity pair");
        }

        String authCode = generateAuthorizationCode();

        PoboAuthorization auth = PoboAuthorization.builder()
            .authorizationCode(authCode)
            .payerEntityId(request.getPayerEntityId())
            .payerEntityCode(request.getPayerEntityCode())
            .payerVaId(request.getPayerVaId())
            .behalfEntityId(request.getBehalfEntityId())
            .behalfEntityCode(request.getBehalfEntityCode())
            .behalfVaId(request.getBehalfVaId())
            .authorizationType(request.getAuthorizationType())
            .singlePaymentLimit(request.getSinglePaymentLimit())
            .dailyLimit(request.getDailyLimit())
            .monthlyLimit(request.getMonthlyLimit())
            .currencyCode(request.getCurrencyCode() != null ? request.getCurrencyCode() : marketProfile.getDefaultCurrency())
            .allowedPaymentTypes(request.getAllowedPaymentTypes())
            .allowedVendorIds(request.getAllowedVendorIds())
            .autoRecharge(request.getAutoRecharge())
            .rechargeServiceFeeRate(request.getRechargeServiceFeeRate())
            .requiresApproval(request.getRequiresApproval())
            .effectiveFrom(request.getEffectiveFrom() != null ? request.getEffectiveFrom() : LocalDate.now())
            .effectiveTo(request.getEffectiveTo())
            .status(PoboAuthorization.AuthorizationStatus.ACTIVE)
            .build();

        auth = authorizationRepository.save(auth);
        log.info("Created POBO authorization: {} - {} -> {}", 
            authCode, request.getPayerEntityCode(), request.getBehalfEntityCode());
        
        return toAuthorizationResponse(auth);
    }

    @Transactional
    public AuthorizationResponse updateLimits(UUID authId, UpdateLimitsRequest request) {
        PoboAuthorization auth = authorizationRepository.findById(authId)
            .orElseThrow(() -> new ResourceNotFoundException("Authorization not found: " + authId));

        if (request.getSinglePaymentLimit() != null) {
            auth.setSinglePaymentLimit(request.getSinglePaymentLimit());
        }
        if (request.getDailyLimit() != null) {
            auth.setDailyLimit(request.getDailyLimit());
        }
        if (request.getMonthlyLimit() != null) {
            auth.setMonthlyLimit(request.getMonthlyLimit());
        }

        auth = authorizationRepository.save(auth);
        log.info("Updated limits for authorization: {}", auth.getAuthorizationCode());
        
        return toAuthorizationResponse(auth);
    }

    @Transactional
    public void suspendAuthorization(UUID authId, String reason) {
        PoboAuthorization auth = authorizationRepository.findById(authId)
            .orElseThrow(() -> new ResourceNotFoundException("Authorization not found: " + authId));
        
        auth.suspend(reason);
        authorizationRepository.save(auth);
        log.info("Suspended authorization: {} - {}", auth.getAuthorizationCode(), reason);
    }

    @Transactional
    public void reactivateAuthorization(UUID authId) {
        PoboAuthorization auth = authorizationRepository.findById(authId)
            .orElseThrow(() -> new ResourceNotFoundException("Authorization not found: " + authId));
        
        auth.reactivate();
        authorizationRepository.save(auth);
        log.info("Reactivated authorization: {}", auth.getAuthorizationCode());
    }

    // ========================================================================
    // POBO VALIDATION
    // ========================================================================

    /**
     * Validate if a POBO payment can be made.
     */
    @Transactional(readOnly = true)
    public ValidatePoboResponse validatePoboPayment(ValidatePoboRequest request) {
        List<String> warnings = new ArrayList<>();
        
        // Find authorization
        Optional<PoboAuthorization> authOpt = authorizationRepository.findActiveAuthorization(
            request.getPayerEntityId(), request.getBehalfEntityId());

        if (authOpt.isEmpty()) {
            return ValidatePoboResponse.builder()
                .isAuthorized(false)
                .rejectionReason("No active POBO authorization found for this entity pair")
                .build();
        }

        PoboAuthorization auth = authOpt.get();

        // Check if valid
        if (!auth.isValid()) {
            return ValidatePoboResponse.builder()
                .isAuthorized(false)
                .authorizationCode(auth.getAuthorizationCode())
                .rejectionReason("Authorization is not valid (expired or suspended)")
                .build();
        }

        // Check payment type
        boolean paymentTypeAllowed = auth.isPaymentTypeAllowed(request.getPaymentType());
        if (!paymentTypeAllowed) {
            return ValidatePoboResponse.builder()
                .isAuthorized(false)
                .authorizationCode(auth.getAuthorizationCode())
                .paymentTypeAllowed(false)
                .rejectionReason("Payment type not allowed: " + request.getPaymentType())
                .build();
        }

        // Check vendor
        boolean vendorAllowed = request.getVendorId() == null || auth.isVendorAllowed(request.getVendorId());
        if (!vendorAllowed) {
            return ValidatePoboResponse.builder()
                .isAuthorized(false)
                .authorizationCode(auth.getAuthorizationCode())
                .vendorAllowed(false)
                .rejectionReason("Vendor not in allowed list")
                .build();
        }

        // Check limits
        boolean withinLimits = auth.canAuthorize(request.getAmount());
        if (!withinLimits) {
            BigDecimal maxAvailable = auth.getMaxAvailable();
            return ValidatePoboResponse.builder()
                .isAuthorized(false)
                .authorizationCode(auth.getAuthorizationCode())
                .withinLimits(false)
                .maxAvailable(maxAvailable)
                .remainingDailyLimit(auth.getRemainingDailyLimit())
                .remainingMonthlyLimit(auth.getRemainingMonthlyLimit())
                .rejectionReason(String.format("Amount %.2f exceeds available limit %.2f", 
                    request.getAmount(), maxAvailable))
                .build();
        }

        // Add warnings if approaching limits
        BigDecimal remainingDaily = auth.getRemainingDailyLimit();
        if (remainingDaily != null && request.getAmount().compareTo(remainingDaily.multiply(new BigDecimal("0.8"))) > 0) {
            warnings.add("Approaching daily limit");
        }
        BigDecimal remainingMonthly = auth.getRemainingMonthlyLimit();
        if (remainingMonthly != null && request.getAmount().compareTo(remainingMonthly.multiply(new BigDecimal("0.9"))) > 0) {
            warnings.add("Approaching monthly limit");
        }

        return ValidatePoboResponse.builder()
            .isAuthorized(true)
            .authorizationCode(auth.getAuthorizationCode())
            .withinLimits(true)
            .paymentTypeAllowed(true)
            .vendorAllowed(true)
            .maxAvailable(auth.getMaxAvailable())
            .remainingDailyLimit(remainingDaily)
            .remainingMonthlyLimit(remainingMonthly)
            .requiresApproval(auth.getRequiresApproval())
            .autoRechargeEnabled(auth.getAutoRecharge())
            .serviceFeeRate(auth.getRechargeServiceFeeRate())
            .warnings(warnings)
            .build();
    }

    /**
     * Record POBO payment usage.
     */
    @Transactional
    public void recordPoboUsage(UUID payerEntityId, UUID behalfEntityId, BigDecimal amount) {
        PoboAuthorization auth = authorizationRepository.findActiveAuthorization(payerEntityId, behalfEntityId)
            .orElseThrow(() -> new BusinessException("No active authorization for POBO payment"));
        
        auth.recordUsage(amount);
        authorizationRepository.save(auth);
        
        log.info("Recorded POBO usage: {} -> {}, amount={}", 
            auth.getPayerEntityCode(), auth.getBehalfEntityCode(), amount);
    }

    // ========================================================================
    // INTERCOMPANY RECHARGE
    // ========================================================================

    @Transactional(readOnly = true)
    public List<RechargeResponse> getAllRecharges() {
        return rechargeRepository.findAll().stream()
            .map(this::toRechargeResponse)
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<RechargeResponse> getPendingRecharges() {
        return rechargeRepository.findPendingRecharges().stream()
            .map(this::toRechargeResponse)
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<RechargeResponse> getRechargesForEntity(UUID entityId) {
        List<IntercompanyRecharge> asPayerEntity = rechargeRepository.findByPayerEntityId(entityId);
        List<IntercompanyRecharge> asBehalfEntity = rechargeRepository.findByBehalfEntityId(entityId);
        
        Set<IntercompanyRecharge> all = new HashSet<>();
        all.addAll(asPayerEntity);
        all.addAll(asBehalfEntity);
        
        return all.stream()
            .map(this::toRechargeResponse)
            .collect(Collectors.toList());
    }

    /**
     * Create intercompany recharge record.
     * Called after POBO payment is executed.
     */
    @Transactional
    public RechargeResponse createRecharge(CreateRechargeRequest request) {
        String rechargeRef = generateRechargeReference();

        // Calculate service fee
        BigDecimal serviceFee = BigDecimal.ZERO;
        if (request.getServiceFeeRate() != null && request.getServiceFeeRate().compareTo(BigDecimal.ZERO) > 0) {
            serviceFee = chargeService.calculatePoboFee(
                request.getOriginalAmount(), 
                request.getServiceFeeRate(),
                new BigDecimal("25"),   // min fee
                new BigDecimal("1000")  // max fee
            );
        } else {
            serviceFee = chargeService.calculatePoboFee(request.getOriginalAmount());
        }

        BigDecimal adminFee = request.getAdminFee() != null ? request.getAdminFee() : BigDecimal.ZERO;
        BigDecimal totalRecharge = request.getOriginalAmount().add(serviceFee).add(adminFee);

        IntercompanyRecharge recharge = IntercompanyRecharge.builder()
            .rechargeReference(rechargeRef)
            .payerEntityId(request.getPayerEntityId())
            .payerEntityCode(request.getPayerEntityCode())
            .payerEntityName(request.getPayerEntityName())
            .payerVaId(request.getPayerVaId())
            .behalfEntityId(request.getBehalfEntityId())
            .behalfEntityCode(request.getBehalfEntityCode())
            .behalfEntityName(request.getBehalfEntityName())
            .behalfVaId(request.getBehalfVaId())
            .originalPayableId(request.getOriginalPayableId())
            .originalPaymentExecutionId(request.getOriginalPaymentExecutionId())
            .originalPaymentReference(request.getOriginalPaymentReference())
            .originalAmount(request.getOriginalAmount())
            .rechargeAmount(request.getOriginalAmount())
            .currencyCode(request.getCurrencyCode() != null ? request.getCurrencyCode() : marketProfile.getDefaultCurrency())
            .serviceFee(serviceFee)
            .adminFee(adminFee)
            .totalRecharge(totalRecharge)
            .approvalRequired(request.getRequiresApproval())
            .status(IntercompanyRecharge.RechargeStatus.PENDING)
            .build();

        recharge = rechargeRepository.save(recharge);
        log.info("Created intercompany recharge: {} - {} -> {}, total={}", 
            rechargeRef, request.getPayerEntityCode(), request.getBehalfEntityCode(), totalRecharge);

        return toRechargeResponse(recharge);
    }

    /**
     * Approve intercompany recharge.
     */
    @Transactional
    public RechargeResponse approveRecharge(UUID rechargeId, String approver) {
        IntercompanyRecharge recharge = rechargeRepository.findById(rechargeId)
            .orElseThrow(() -> new ResourceNotFoundException("Recharge not found: " + rechargeId));

        if (recharge.getStatus() != IntercompanyRecharge.RechargeStatus.PENDING) {
            throw new BusinessException("Recharge is not pending approval");
        }

        recharge.approve(approver);
        recharge = rechargeRepository.save(recharge);

        log.info("Approved recharge: {} by {}", recharge.getRechargeReference(), approver);
        return toRechargeResponse(recharge);
    }

    /**
     * Reject intercompany recharge.
     */
    @Transactional
    public RechargeResponse rejectRecharge(UUID rechargeId, String rejector, String reason) {
        IntercompanyRecharge recharge = rechargeRepository.findById(rechargeId)
            .orElseThrow(() -> new ResourceNotFoundException("Recharge not found: " + rechargeId));

        if (recharge.getStatus() != IntercompanyRecharge.RechargeStatus.PENDING) {
            throw new BusinessException("Recharge is not pending approval");
        }

        recharge.reject(rejector, reason);
        recharge = rechargeRepository.save(recharge);

        log.info("Rejected recharge: {} by {} - Reason: {}", recharge.getRechargeReference(), rejector, reason);
        return toRechargeResponse(recharge);
    }

    /**
     * Settle recharge via IHB loan.
     */
    @Transactional
    public RechargeResponse settleViaIhbLoan(UUID rechargeId, UUID ihbLoanId, String loanReference) {
        IntercompanyRecharge recharge = rechargeRepository.findById(rechargeId)
            .orElseThrow(() -> new ResourceNotFoundException("Recharge not found: " + rechargeId));

        if (!recharge.canBeSettled()) {
            throw new BusinessException("Recharge cannot be settled in current status: " + recharge.getStatus());
        }

        recharge.settleViaIhbLoan(ihbLoanId, loanReference);
        recharge = rechargeRepository.save(recharge);
        
        log.info("Settled recharge {} via IHB loan: {}", recharge.getRechargeReference(), loanReference);
        return toRechargeResponse(recharge);
    }

    /**
     * Settle recharge via netting.
     */
    @Transactional
    public RechargeResponse settleViaNetting(UUID rechargeId, String nettingReference) {
        IntercompanyRecharge recharge = rechargeRepository.findById(rechargeId)
            .orElseThrow(() -> new ResourceNotFoundException("Recharge not found: " + rechargeId));

        recharge.settle(IntercompanyRecharge.SettlementMethod.NETTING, nettingReference);
        recharge = rechargeRepository.save(recharge);
        
        log.info("Settled recharge {} via netting: {}", recharge.getRechargeReference(), nettingReference);
        return toRechargeResponse(recharge);
    }

    /**
     * Validate arm's length pricing.
     */
    @Transactional
    public RechargeResponse validateArmLength(UUID rechargeId, String notes) {
        IntercompanyRecharge recharge = rechargeRepository.findById(rechargeId)
            .orElseThrow(() -> new ResourceNotFoundException("Recharge not found: " + rechargeId));

        recharge.validateArmLength(notes);
        recharge = rechargeRepository.save(recharge);
        
        log.info("Validated arm's length for recharge: {}", recharge.getRechargeReference());
        return toRechargeResponse(recharge);
    }

    // ========================================================================
    // POBO PAYMENT PROCESSING (Full Flow) - Phase 5 Enhanced
    // ========================================================================

    /**
     * Process POBO payment with automatic recharge and optional IHB loan creation.
     * This is the main entry point for POBO payments.
     * 
     * Phase 5 Enhancement: Added IHB loan integration
     */
    @Transactional
    public PoboPaymentResponse processPoboPayment(PoboPaymentRequest request) {
        // 1. Validate authorization
        ValidatePoboRequest validateRequest = ValidatePoboRequest.builder()
            .payerEntityId(request.getPayerEntityId())
            .payerEntityCode(request.getPayerEntityCode())
            .behalfEntityId(request.getBehalfEntityId())
            .behalfEntityCode(request.getBehalfEntityCode())
            .amount(request.getAmount())
            .currencyCode(request.getCurrencyCode())
            .build();

        ValidatePoboResponse validation = validatePoboPayment(validateRequest);
        
        if (!Boolean.TRUE.equals(validation.getIsAuthorized())) {
            throw new BusinessException("POBO payment not authorized: " + validation.getRejectionReason());
        }

        // 2. Record usage
        recordPoboUsage(request.getPayerEntityId(), request.getBehalfEntityId(), request.getAmount());

        // 3. Create payment execution (this would normally call PayablesService)
        String paymentRef = "POBO-" + System.currentTimeMillis();
        UUID executionId = UUID.randomUUID();

        // 4. Create intercompany recharge if enabled
        RechargeResponse recharge = null;
        UUID ihbLoanId = null;
        String ihbLoanReference = null;
        
        if (Boolean.TRUE.equals(request.getCreateRecharge())) {
            CreateRechargeRequest rechargeRequest = CreateRechargeRequest.builder()
                .payerEntityId(request.getPayerEntityId())
                .payerEntityCode(request.getPayerEntityCode())
                .behalfEntityId(request.getBehalfEntityId())
                .behalfEntityCode(request.getBehalfEntityCode())
                .originalPayableId(request.getPayableId())
                .originalPaymentExecutionId(executionId)
                .originalPaymentReference(paymentRef)
                .originalAmount(request.getAmount())
                .currencyCode(request.getCurrencyCode())
                .serviceFeeRate(validation.getServiceFeeRate())
                .requiresApproval(validation.getRequiresApproval())
                .build();

            recharge = createRecharge(rechargeRequest);

            // Auto-approve if not requiring approval
            if (!Boolean.TRUE.equals(validation.getRequiresApproval())) {
                recharge = approveRecharge(recharge.getId(), "SYSTEM_AUTO");
            }
            
            // 5. Create IHB loan if requested (Phase 5 enhancement)
            if (Boolean.TRUE.equals(request.getCreateIhbLoan())) {
                IhbLoanResult loanResult = createIhbLoanForRecharge(
                    recharge.getId(),
                    request.getPayerEntityId(),
                    request.getBehalfEntityId(),
                    recharge.getTotalRecharge(),
                    request.getCurrencyCode()
                );
                
                if (loanResult != null && loanResult.isSuccess()) {
                    ihbLoanId = loanResult.getLoanId();
                    ihbLoanReference = loanResult.getLoanReference();
                    
                    // Settle recharge via IHB loan
                    try {
                        settleViaIhbLoan(recharge.getId(), ihbLoanId, ihbLoanReference);
                        log.info("Created IHB loan {} for POBO recharge {}", 
                            ihbLoanReference, recharge.getRechargeReference());
                    } catch (Exception e) {
                        log.warn("Failed to settle recharge via IHB loan: {}", e.getMessage());
                        // Loan was created but settlement failed - manual intervention needed
                    }
                } else if (loanResult != null) {
                    log.warn("Failed to create IHB loan for recharge {}: {}", 
                        recharge.getRechargeReference(), loanResult.getErrorMessage());
                    // Continue without IHB loan - recharge will need manual settlement
                }
            }
        }

        log.info("Processed POBO payment: {} -> {}, amount={}, recharge={}, ihbLoan={}", 
            request.getPayerEntityCode(), request.getBehalfEntityCode(), 
            request.getAmount(), 
            recharge != null ? recharge.getRechargeReference() : "N/A",
            ihbLoanReference != null ? ihbLoanReference : "N/A");

        return PoboPaymentResponse.builder()
            .paymentExecutionId(executionId)
            .paymentReference(paymentRef)
            .status("PROCESSED")
            .paidAmount(request.getAmount())
            .rechargeId(recharge != null ? recharge.getId() : null)
            .rechargeReference(recharge != null ? recharge.getRechargeReference() : null)
            .rechargeAmount(recharge != null ? recharge.getRechargeAmount() : null)
            .serviceFee(recharge != null ? recharge.getServiceFee() : null)
            .totalRecharge(recharge != null ? recharge.getTotalRecharge() : null)
            .ihbLoanId(ihbLoanId)
            .ihbLoanReference(ihbLoanReference)
            .processedAt(LocalDateTime.now())
            .build();
    }

    // ========================================================================
    // PHASE 5: IHB LOAN INTEGRATION
    // ========================================================================
    
    /**
     * Phase 5: Create IHB loan for POBO recharge settlement.
     * 
     * When treasury pays on behalf of subsidiary:
     * - Subsidiary effectively "borrows" from treasury
     * - IHB loan formalizes this with interest
     * - Loan is linked to recharge for tracking
     */
    private IhbLoanResult createIhbLoanForRecharge(
            UUID rechargeId,
            UUID lenderEntityId,
            UUID borrowerEntityId,
            BigDecimal amount,
            String currency) {
        
        try {
            // Create loan request
            IhbDto.CreateLoanUnifiedRequest loanRequest = new IhbDto.CreateLoanUnifiedRequest();
            loanRequest.setLenderEntityId(lenderEntityId);
            loanRequest.setBorrowerEntityId(borrowerEntityId);
            loanRequest.setPrincipalAmount(amount);
            loanRequest.setCurrencyCode(currency != null ? currency : marketProfile.getDefaultCurrency());
            loanRequest.setDisbursementDate(LocalDate.now());
            loanRequest.setMaturityDate(LocalDate.now().plusDays(30)); // Default 30-day loan
            loanRequest.setInterestType(IhbLoan.InterestType.FIXED);
            loanRequest.setRepaymentFrequency(IhbLoan.RepaymentFrequency.BULLET);
            
            // Create the loan via IHB service
            IhbDto.LoanResponse loan = ihbUnifiedService.createLoan(loanRequest);
            
            log.info("Created IHB loan {} for POBO recharge, amount: {} {}", 
                loan.getLoanReference(), currency, amount);
            
            return IhbLoanResult.builder()
                .success(true)
                .loanId(loan.getId())
                .loanReference(loan.getLoanReference())
                .principalAmount(loan.getPrincipalAmount())
                .interestRate(loan.getInterestRate())
                .maturityDate(loan.getMaturityDate())
                .build();
                
        } catch (Exception e) {
            log.warn("IHB loan creation failed for recharge {}: {}", rechargeId, e.getMessage());
            return IhbLoanResult.builder()
                .success(false)
                .errorMessage(e.getMessage())
                .build();
        }
    }

    // ========================================================================
    // STATISTICS
    // ========================================================================

    @Transactional(readOnly = true)
    public PoboStatsResponse getStats() {
        List<PoboAuthorization> activeAuths = authorizationRepository.findAllActive();
        List<IntercompanyRecharge> pendingRecharges = rechargeRepository.findPendingRecharges();
        List<IntercompanyRecharge> settledToday = rechargeRepository.findBySettlementDateRange(
            LocalDate.now(), LocalDate.now());
        
        BigDecimal totalOutstanding = rechargeRepository.findUnsettledRecharges().stream()
            .map(IntercompanyRecharge::getTotalRecharge)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalServiceFees = rechargeRepository.findAll().stream()
            .filter(r -> r.getStatus() == IntercompanyRecharge.RechargeStatus.SETTLED)
            .map(IntercompanyRecharge::getServiceFee)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        int ihbLoans = (int) rechargeRepository.findWithIhbLoans().size();
        int pendingArmLength = rechargeRepository.findPendingArmLengthValidation().size();

        return PoboStatsResponse.builder()
            .activeAuthorizations(activeAuths.size())
            .pendingRecharges(pendingRecharges.size())
            .settledRecharges(settledToday.size())
            .totalRechargesOutstanding(totalOutstanding)
            .totalServiceFeesCollected(totalServiceFees)
            .ihbLoansCreated(ihbLoans)
            .armLengthValidationsPending(pendingArmLength)
            .asOfDate(LocalDateTime.now())
            .build();
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private String generateAuthorizationCode() {
        return String.format("POBO-AUTH-%06d", authSequence.getAndIncrement());
    }

    private String generateRechargeReference() {
        String date = LocalDate.now().toString().replace("-", "");
        return String.format("ICR-%s-%06d", date, rechargeSequence.getAndIncrement());
    }

    // ========================================================================
    // MAPPERS
    // ========================================================================

    private AuthorizationResponse toAuthorizationResponse(PoboAuthorization a) {
        return AuthorizationResponse.builder()
            .id(a.getId())
            .authorizationCode(a.getAuthorizationCode())
            .payerEntityId(a.getPayerEntityId())
            .payerEntityCode(a.getPayerEntityCode())
            .behalfEntityId(a.getBehalfEntityId())
            .behalfEntityCode(a.getBehalfEntityCode())
            .authorizationType(a.getAuthorizationType())
            .singlePaymentLimit(a.getSinglePaymentLimit())
            .dailyLimit(a.getDailyLimit())
            .monthlyLimit(a.getMonthlyLimit())
            .currencyCode(a.getCurrencyCode())
            .usedToday(a.getUsedToday())
            .usedThisMonth(a.getUsedThisMonth())
            .remainingDailyLimit(a.getRemainingDailyLimit())
            .remainingMonthlyLimit(a.getRemainingMonthlyLimit())
            .autoRecharge(a.getAutoRecharge())
            .rechargeServiceFeeRate(a.getRechargeServiceFeeRate())
            .requiresApproval(a.getRequiresApproval())
            .status(a.getStatus().name())
            .effectiveFrom(a.getEffectiveFrom())
            .effectiveTo(a.getEffectiveTo())
            .approvedBy(a.getApprovedBy())
            .approvedAt(a.getApprovedAt())
            .createdAt(a.getCreatedAt())
            .build();
    }

    private RechargeResponse toRechargeResponse(IntercompanyRecharge r) {
        return RechargeResponse.builder()
            .id(r.getId())
            .rechargeReference(r.getRechargeReference())
            .payerEntityId(r.getPayerEntityId())
            .payerEntityCode(r.getPayerEntityCode())
            .payerEntityName(r.getPayerEntityName())
            .behalfEntityId(r.getBehalfEntityId())
            .behalfEntityCode(r.getBehalfEntityCode())
            .behalfEntityName(r.getBehalfEntityName())
            .originalPayableId(r.getOriginalPayableId())
            .originalPaymentReference(r.getOriginalPaymentReference())
            .originalAmount(r.getOriginalAmount())
            .rechargeAmount(r.getRechargeAmount())
            .serviceFee(r.getServiceFee())
            .adminFee(r.getAdminFee())
            .fxMarkup(r.getFxMarkup())
            .totalRecharge(r.getTotalRecharge())
            .currencyCode(r.getCurrencyCode())
            .armLengthValidated(r.getArmLengthValidated())
            .armLengthNotes(r.getArmLengthNotes())
            .status(r.getStatus())
            .settlementDate(r.getSettlementDate())
            .settlementReference(r.getSettlementReference())
            .settledVia(r.getSettledVia())
            .ihbLoanId(r.getIhbLoanId())
            .approvedBy(r.getApprovedBy())
            .approvedAt(r.getApprovedAt())
            .createdAt(r.getCreatedAt())
            .build();
    }

    // ========================================================================
    // PHASE 5: INNER CLASSES
    // ========================================================================
    
    /**
     * Phase 5: Result class for IHB loan creation
     */
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
}