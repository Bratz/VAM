package com.bank.vam.service.pobo;

import com.bank.vam.dto.treasury.IhbDto;
import com.bank.vam.entity.payables.Payable;
import com.bank.vam.entity.pobo.PoboAuthorization;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.payables.PayableRepository;
import com.bank.vam.repository.pobo.PoboAuthorizationRepository;
import com.bank.vam.service.tax.ChargeService;
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
import java.util.*;
import java.util.stream.Collectors;

/**
 * Phase 5 Task 5.5: POBO Preview Service
 * 
 * Provides preview/estimation capabilities before executing POBO:
 * - Calculate all charges (service fee, admin fee, FX markup)
 * - Preview IHB loan terms (interest rate, estimated interest)
 * - Estimate net amount to subsidiary
 * - Show authorization status and limits
 * 
 * Use Cases:
 * - Subsidiary reviews POBO costs before requesting
 * - Treasury reviews batch POBO impact
 * - Finance audits intercompany charges
 * 
 * @see <a href="https://www.capgemini.com/wp-content/uploads/2020/12/Virtual-Acc-Management-in-Transaction-Banking.pdf">
 *      Capgemini VAM Reference - POBO/COBO</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PoboPreviewService {

    private final PayableRepository payableRepository;
    private final PoboAuthorizationRepository authorizationRepository;
    private final IhbUnifiedService ihbUnifiedService;
    private final ChargeService chargeService;

    // Fee constants
    private static final BigDecimal POBO_PROCESSING_FEE = new BigDecimal("25.00");
    private static final BigDecimal POBO_FX_MARKUP_RATE = new BigDecimal("0.0025"); // 0.25%
    private static final BigDecimal DEFAULT_POBO_SERVICE_FEE_RATE = new BigDecimal("0.50"); // 0.50%
    private static final BigDecimal DEFAULT_IHB_INTEREST_RATE = new BigDecimal("5.50"); // %
    private static final int DEFAULT_LOAN_TENOR_DAYS = 30;

    // ========================================================================
    // SINGLE PAYABLE PREVIEW
    // ========================================================================
    
    /**
     * Generate POBO preview for a single payable.
     * 
     * Shows:
     * - Authorization status
     * - Fee breakdown
     * - IHB loan preview (if requested)
     * - Total cost to subsidiary
     */
    @Transactional(readOnly = true)
    public PoboPreviewResponse previewPobo(PoboPreviewRequest request) {
        log.debug("Generating POBO preview for payable: {}", request.getPayableId());
        
        // 1. Load payable
        Payable payable = payableRepository.findById(request.getPayableId())
            .orElseThrow(() -> new ResourceNotFoundException("Payable not found: " + request.getPayableId()));
        
        // 2. Find authorization
        Optional<PoboAuthorization> authOpt = authorizationRepository.findActiveAuthorization(
            request.getTreasuryEntityId(), payable.getOwningEntityId());
        
        AuthorizationPreview authPreview = buildAuthorizationPreview(authOpt, payable.getNetAmount());
        
        // 3. Calculate fees
        FeeBreakdown fees = calculateFees(
            payable.getNetAmount(), 
            payable.getCurrencyCode(),
            authOpt.map(PoboAuthorization::getRechargeServiceFeeRate).orElse(null)
        );
        
        // 4. Calculate IHB loan preview if requested
        IhbLoanPreview ihbPreview = null;
        if (Boolean.TRUE.equals(request.getIncludeIhbPreview())) {
            ihbPreview = calculateIhbLoanPreview(
                fees.getTotalRecharge(),
                payable.getCurrencyCode(),
                request.getTreasuryEntityId(),
                payable.getOwningEntityId(),
                request.getLoanTenorDays() != null ? request.getLoanTenorDays() : DEFAULT_LOAN_TENOR_DAYS
            );
        }
        
        // 5. Calculate total cost to subsidiary
        BigDecimal totalCost = fees.getTotalRecharge();
        if (ihbPreview != null) {
            totalCost = totalCost.add(ihbPreview.getEstimatedInterest());
        }
        
        return PoboPreviewResponse.builder()
            .payableId(payable.getId())
            .payableNumber(payable.getPayableNumber())
            .vendorName(payable.getVendorName())
            .originalAmount(payable.getNetAmount())
            .currency(payable.getCurrencyCode())
            .owningEntityId(payable.getOwningEntityId())
            .owningEntityCode(payable.getOwningEntityCode())
            .owningEntityName(payable.getOwningEntityName())
            .treasuryEntityId(request.getTreasuryEntityId())
            .authorization(authPreview)
            .fees(fees)
            .ihbLoanPreview(ihbPreview)
            .totalCostToSubsidiary(totalCost)
            .effectiveCostPercent(calculateEffectiveCostPercent(payable.getNetAmount(), totalCost))
            .canProceed(authPreview.isAuthorized())
            .warnings(authPreview.getWarnings())
            .build();
    }

    // ========================================================================
    // BATCH PREVIEW
    // ========================================================================
    
    /**
     * Generate POBO preview for multiple payables.
     * 
     * Shows:
     * - Individual previews for each payable
     * - Aggregated totals
     * - Combined authorization check
     */
    @Transactional(readOnly = true)
    public BatchPoboPreviewResponse previewBatchPobo(BatchPoboPreviewRequest request) {
        log.debug("Generating batch POBO preview for {} payables", request.getPayableIds().size());
        
        List<PoboPreviewResponse> previews = new ArrayList<>();
        BigDecimal totalOriginalAmount = BigDecimal.ZERO;
        BigDecimal totalRecharge = BigDecimal.ZERO;
        BigDecimal totalFees = BigDecimal.ZERO;
        BigDecimal totalIhbInterest = BigDecimal.ZERO;
        int eligibleCount = 0;
        int ineligibleCount = 0;
        List<String> batchWarnings = new ArrayList<>();
        
        // Group by owning entity for authorization checks
        Map<UUID, List<UUID>> payablesByEntity = new HashMap<>();
        
        for (UUID payableId : request.getPayableIds()) {
            try {
                PoboPreviewRequest singleRequest = PoboPreviewRequest.builder()
                    .payableId(payableId)
                    .treasuryEntityId(request.getTreasuryEntityId())
                    .includeIhbPreview(request.getIncludeIhbPreview())
                    .loanTenorDays(request.getLoanTenorDays())
                    .build();
                
                PoboPreviewResponse preview = previewPobo(singleRequest);
                previews.add(preview);
                
                if (preview.isCanProceed()) {
                    eligibleCount++;
                    totalOriginalAmount = totalOriginalAmount.add(preview.getOriginalAmount());
                    totalRecharge = totalRecharge.add(preview.getFees().getTotalRecharge());
                    totalFees = totalFees.add(preview.getFees().getTotalFees());
                    if (preview.getIhbLoanPreview() != null) {
                        totalIhbInterest = totalIhbInterest.add(preview.getIhbLoanPreview().getEstimatedInterest());
                    }
                } else {
                    ineligibleCount++;
                }
                
                // Collect warnings
                if (preview.getWarnings() != null) {
                    batchWarnings.addAll(preview.getWarnings());
                }
                
            } catch (Exception e) {
                log.warn("Preview failed for payable {}: {}", payableId, e.getMessage());
                ineligibleCount++;
            }
        }
        
        // Check combined limits
        Optional<PoboAuthorization> auth = authorizationRepository.findActiveAuthorization(
            request.getTreasuryEntityId(), 
            previews.isEmpty() ? null : previews.get(0).getOwningEntityId());
        
        if (auth.isPresent()) {
            BigDecimal remainingDaily = auth.get().getRemainingDailyLimit();
            if (remainingDaily != null && totalOriginalAmount.compareTo(remainingDaily) > 0) {
                batchWarnings.add("Batch total exceeds daily limit");
            }
        }
        
        return BatchPoboPreviewResponse.builder()
            .totalPayables(request.getPayableIds().size())
            .eligiblePayables(eligibleCount)
            .ineligiblePayables(ineligibleCount)
            .totalOriginalAmount(totalOriginalAmount)
            .totalRechargeAmount(totalRecharge)
            .totalFeesAmount(totalFees)
            .totalIhbInterest(totalIhbInterest)
            .grandTotal(totalRecharge.add(totalIhbInterest))
            .effectiveCostPercent(calculateEffectiveCostPercent(totalOriginalAmount, totalRecharge.add(totalIhbInterest)))
            .previews(previews)
            .warnings(batchWarnings.stream().distinct().collect(Collectors.toList()))
            .canProceedAll(ineligibleCount == 0)
            .build();
    }

    // ========================================================================
    // AUTHORIZATION PREVIEW
    // ========================================================================
    
    private AuthorizationPreview buildAuthorizationPreview(Optional<PoboAuthorization> authOpt, BigDecimal amount) {
        List<String> warnings = new ArrayList<>();
        
        if (authOpt.isEmpty()) {
            return AuthorizationPreview.builder()
                .authorized(false)
                .rejectionReason("No active POBO authorization found")
                .build();
        }
        
        PoboAuthorization auth = authOpt.get();
        
        if (!auth.isValid()) {
            return AuthorizationPreview.builder()
                .authorized(false)
                .authorizationCode(auth.getAuthorizationCode())
                .rejectionReason("Authorization is not valid: " + auth.getStatus())
                .build();
        }
        
        // Check limits
        if (!auth.canAuthorize(amount)) {
            return AuthorizationPreview.builder()
                .authorized(false)
                .authorizationCode(auth.getAuthorizationCode())
                .rejectionReason("Amount exceeds available limit")
                .singlePaymentLimit(auth.getSinglePaymentLimit())
                .remainingDailyLimit(auth.getRemainingDailyLimit())
                .remainingMonthlyLimit(auth.getRemainingMonthlyLimit())
                .build();
        }
        
        // Add warnings
        BigDecimal remainingDaily = auth.getRemainingDailyLimit();
        if (remainingDaily != null && amount.compareTo(remainingDaily.multiply(new BigDecimal("0.8"))) > 0) {
            warnings.add("Approaching daily limit (80%)");
        }
        
        BigDecimal remainingMonthly = auth.getRemainingMonthlyLimit();
        if (remainingMonthly != null && amount.compareTo(remainingMonthly.multiply(new BigDecimal("0.9"))) > 0) {
            warnings.add("Approaching monthly limit (90%)");
        }
        
        return AuthorizationPreview.builder()
            .authorized(true)
            .authorizationCode(auth.getAuthorizationCode())
            .authorizationType(auth.getAuthorizationType().name())
            .singlePaymentLimit(auth.getSinglePaymentLimit())
            .remainingDailyLimit(remainingDaily)
            .remainingMonthlyLimit(remainingMonthly)
            .usedToday(auth.getUsedToday())
            .usedThisMonth(auth.getUsedThisMonth())
            .serviceFeeRate(auth.getRechargeServiceFeeRate())
            .requiresApproval(auth.getRequiresApproval())
            .autoRechargeEnabled(auth.getAutoRecharge())
            .warnings(warnings)
            .build();
    }

    // ========================================================================
    // FEE CALCULATION
    // ========================================================================
    
    private FeeBreakdown calculateFees(BigDecimal amount, String currency, BigDecimal customServiceFeeRate) {
        // Service fee
        BigDecimal serviceFee;
        if (customServiceFeeRate != null && customServiceFeeRate.compareTo(BigDecimal.ZERO) > 0) {
            serviceFee = chargeService.calculatePoboFee(
                amount, 
                customServiceFeeRate,
                new BigDecimal("25"),
                new BigDecimal("1000")
            );
        } else {
            serviceFee = chargeService.calculatePoboFee(amount);
        }
        
        // Processing fee (flat)
        BigDecimal processingFee = POBO_PROCESSING_FEE;
        
        // FX markup (if non-base currency)
        BigDecimal fxMarkup = BigDecimal.ZERO;
        if (!"AED".equals(currency)) {
            fxMarkup = amount.multiply(POBO_FX_MARKUP_RATE).setScale(2, RoundingMode.HALF_UP);
        }
        
        BigDecimal totalFees = serviceFee.add(processingFee).add(fxMarkup);
        BigDecimal totalRecharge = amount.add(totalFees);
        
        return FeeBreakdown.builder()
            .originalAmount(amount)
            .serviceFee(serviceFee)
            .serviceFeeRate(customServiceFeeRate != null ? customServiceFeeRate : DEFAULT_POBO_SERVICE_FEE_RATE)
            .processingFee(processingFee)
            .fxMarkup(fxMarkup)
            .fxMarkupRate(POBO_FX_MARKUP_RATE)
            .totalFees(totalFees)
            .totalRecharge(totalRecharge)
            .feesAsPercent(totalFees.divide(amount, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100")))
            .build();
    }

    // ========================================================================
    // IHB LOAN PREVIEW
    // ========================================================================
    
    private IhbLoanPreview calculateIhbLoanPreview(
            BigDecimal principal, 
            String currency,
            UUID treasuryEntityId,
            UUID subsidiaryEntityId,
            int tenorDays) {
        
        try {
            // Try to get indicative rate from IHB service
            IhbDto.IndicativeRateRequest rateRequest = new IhbDto.IndicativeRateRequest();
            rateRequest.setRateType("LENDING");
            rateRequest.setAmount(principal);
            rateRequest.setTenorDays(tenorDays);
            rateRequest.setCurrency(currency);
            
            IhbDto.IndicativeRateResponse rateResponse = ihbUnifiedService.getIndicativeRate(
                treasuryEntityId, subsidiaryEntityId, rateRequest);
            
            BigDecimal interestRate = rateResponse.getEffectiveRate();
            BigDecimal estimatedInterest = rateResponse.getEstimatedInterest();
            
            return IhbLoanPreview.builder()
                .principalAmount(principal)
                .currency(currency)
                .tenorDays(tenorDays)
                .maturityDate(LocalDate.now().plusDays(tenorDays))
                .baseRate(rateResponse.getBaseRate())
                .baseRateType(rateResponse.getBaseRateType())
                .treasurySpread(rateResponse.getTreasurySpread())
                .entitySpread(rateResponse.getEntitySpread())
                .effectiveInterestRate(interestRate)
                .estimatedInterest(estimatedInterest)
                .totalRepayment(principal.add(estimatedInterest))
                .available(true)
                .build();
                
        } catch (Exception e) {
            log.warn("IHB rate lookup failed: {}. Using default rates.", e.getMessage());
            
            // Fall back to default calculation
            BigDecimal estimatedInterest = principal
                .multiply(DEFAULT_IHB_INTEREST_RATE)
                .multiply(new BigDecimal(tenorDays))
                .divide(new BigDecimal("36000"), 2, RoundingMode.HALF_UP);
            
            return IhbLoanPreview.builder()
                .principalAmount(principal)
                .currency(currency)
                .tenorDays(tenorDays)
                .maturityDate(LocalDate.now().plusDays(tenorDays))
                .effectiveInterestRate(DEFAULT_IHB_INTEREST_RATE)
                .estimatedInterest(estimatedInterest)
                .totalRepayment(principal.add(estimatedInterest))
                .available(false)
                .unavailableReason("IHB rate lookup failed - using default estimate")
                .build();
        }
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================
    
    private BigDecimal calculateEffectiveCostPercent(BigDecimal original, BigDecimal total) {
        if (original == null || original.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return total.subtract(original)
            .divide(original, 4, RoundingMode.HALF_UP)
            .multiply(new BigDecimal("100"));
    }

    // ========================================================================
    // DTOs
    // ========================================================================
    
    @Data
    @Builder
    public static class PoboPreviewRequest {
        private UUID payableId;
        private UUID treasuryEntityId;
        private Boolean includeIhbPreview;
        private Integer loanTenorDays;
    }
    
    @Data
    @Builder
    public static class BatchPoboPreviewRequest {
        private List<UUID> payableIds;
        private UUID treasuryEntityId;
        private Boolean includeIhbPreview;
        private Integer loanTenorDays;
    }
    
    @Data
    @Builder
    public static class PoboPreviewResponse {
        private UUID payableId;
        private String payableNumber;
        private String vendorName;
        private BigDecimal originalAmount;
        private String currency;
        private UUID owningEntityId;
        private String owningEntityCode;
        private String owningEntityName;
        private UUID treasuryEntityId;
        private AuthorizationPreview authorization;
        private FeeBreakdown fees;
        private IhbLoanPreview ihbLoanPreview;
        private BigDecimal totalCostToSubsidiary;
        private BigDecimal effectiveCostPercent;
        private boolean canProceed;
        private List<String> warnings;
    }
    
    @Data
    @Builder
    public static class BatchPoboPreviewResponse {
        private int totalPayables;
        private int eligiblePayables;
        private int ineligiblePayables;
        private BigDecimal totalOriginalAmount;
        private BigDecimal totalRechargeAmount;
        private BigDecimal totalFeesAmount;
        private BigDecimal totalIhbInterest;
        private BigDecimal grandTotal;
        private BigDecimal effectiveCostPercent;
        private List<PoboPreviewResponse> previews;
        private List<String> warnings;
        private boolean canProceedAll;
    }
    
    @Data
    @Builder
    public static class AuthorizationPreview {
        private boolean authorized;
        private String authorizationCode;
        private String authorizationType;
        private String rejectionReason;
        private BigDecimal singlePaymentLimit;
        private BigDecimal remainingDailyLimit;
        private BigDecimal remainingMonthlyLimit;
        private BigDecimal usedToday;
        private BigDecimal usedThisMonth;
        private BigDecimal serviceFeeRate;
        private Boolean requiresApproval;
        private Boolean autoRechargeEnabled;
        private List<String> warnings;
    }
    
    @Data
    @Builder
    public static class FeeBreakdown {
        private BigDecimal originalAmount;
        private BigDecimal serviceFee;
        private BigDecimal serviceFeeRate;
        private BigDecimal processingFee;
        private BigDecimal fxMarkup;
        private BigDecimal fxMarkupRate;
        private BigDecimal totalFees;
        private BigDecimal totalRecharge;
        private BigDecimal feesAsPercent;
    }
    
    @Data
    @Builder
    public static class IhbLoanPreview {
        private BigDecimal principalAmount;
        private String currency;
        private int tenorDays;
        private LocalDate maturityDate;
        private BigDecimal baseRate;
        private String baseRateType;
        private BigDecimal treasurySpread;
        private BigDecimal entitySpread;
        private BigDecimal effectiveInterestRate;
        private BigDecimal estimatedInterest;
        private BigDecimal totalRepayment;
        private boolean available;
        private String unavailableReason;
    }
}