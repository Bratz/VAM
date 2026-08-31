package com.bank.vam.service.treasury;

import com.bank.vam.dto.VirtualAccountDto;
import com.bank.vam.dto.treasury.IhbDto;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.credit.InterestConfiguration;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.treasury.*;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.credit.InterestConfigurationRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.treasury.*;
import com.bank.vam.service.tax.TaxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Unified IHB Service - Uses LegalEntity instead of separate IhbEntity.
 * 
 * PHASE 2 UNIFICATION:
 * - LegalEntity is the single source of truth for entity information
 * - IHB-specific fields added directly to LegalEntity
 * - Automatic VA resolution through entity→VA relationships
 * - Integrated with Interest Configuration and Credit Limits
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IhbUnifiedService {

    private final LegalEntityRepository legalEntityRepository;
    private final IhbLoanRepository loanRepository;
    private final IhbDepositRepository depositRepository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final FeePostingService feePostingService;
    // ENHANCED: For IHB→Sweep integration
    private final SweepRuleRepository sweepRuleRepository;
    // ENHANCED: For Interest Configuration attachment
    private final InterestConfigurationRepository interestConfigRepository;
    // ENHANCED: For WHT calculation on cross-border interest
    private final TaxService taxService;
    private final com.bank.vam.config.MarketProfileProperties marketProfile;

    private static final AtomicInteger loanSequence = new AtomicInteger(1);
    private static final AtomicInteger depositSequence = new AtomicInteger(1);
    
    private static final BigDecimal LOAN_ARRANGEMENT_FEE_RATE = new BigDecimal("0.001");
    private static final BigDecimal LOAN_ARRANGEMENT_FEE_MIN = new BigDecimal("100.00");
    private static final BigDecimal LOAN_ARRANGEMENT_FEE_MAX = new BigDecimal("5000.00");
    private static final BigDecimal DEPOSIT_ARRANGEMENT_FEE_RATE = new BigDecimal("0.0005");
    private static final BigDecimal DEPOSIT_ARRANGEMENT_FEE_MIN = new BigDecimal("50.00");

    // ========================================================================
    // IHB ENTITY MANAGEMENT
    // ========================================================================

    @Transactional(readOnly = true)
    public List<IhbDto.EntityResponse> getIhbEntities(UUID corporateId) {
        return legalEntityRepository.findByCorporateIdAndIhbEnabledTrue(corporateId)
                .stream().map(this::toEntityResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<IhbDto.EntityResponse> getAllIhbEntities() {
        return legalEntityRepository.findByIhbEnabledTrue()
                .stream().map(this::toEntityResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public IhbDto.EntityResponse getIhbEntityById(UUID entityId) {
        LegalEntity entity = legalEntityRepository.findById(entityId)
                .orElseThrow(() -> new ResourceNotFoundException("Entity not found: " + entityId));
        if (!entity.isIhbEnabled()) {
            throw new BusinessException("Entity is not IHB-enabled: " + entity.getEntityCode());
        }
        return toEntityResponse(entity);
    }

    @Transactional
    public IhbDto.EntityResponse enableIhb(UUID entityId, IhbDto.EnableIhbRequest request) {
        LegalEntity entity = legalEntityRepository.findById(entityId)
                .orElseThrow(() -> new ResourceNotFoundException("Entity not found: " + entityId));
        if (entity.isIhbEnabled()) {
            throw new BusinessException("IHB already enabled for entity: " + entity.getEntityCode());
        }
        entity.enableIhb(request.getCreditLimit(), request.isCanLend(), request.isCanBorrow(),
            request.getLendingRateSpread(), request.getBorrowingRateSpread());
        if (request.getSettlementVaId() != null) {
            validateSettlementVa(request.getSettlementVaId(), entity);
            entity.setSettlementVaId(request.getSettlementVaId());
        }
        
        entity = legalEntityRepository.save(entity);
        log.info("IHB enabled for entity: {} with limit {}", entity.getEntityCode(), request.getCreditLimit());
        
        // ENHANCED: Auto-create sweep rules if enabled
        if (request.isAutoSweepEnabled()) {
            createIhbSweepRules(entity, request);
        }
        
        // ENHANCED: Auto-enroll entity's virtual accounts
        if (request.isAutoEnrollAccounts()) {
            enrollEntityAccounts(entity, request);
        }
        
        return toEntityResponse(entity);
    }
    
    /**
     * ENHANCED: Create sweep rules for IHB participation.
     * Links entity's accounts to Treasury Center for automated sweeping.
     */
    private void createIhbSweepRules(LegalEntity entity, IhbDto.EnableIhbRequest request) {
        // Find treasury center (entity that canLend)
        LegalEntity treasuryCenter = legalEntityRepository.findByCanLendTrue()
                .stream()
                .findFirst()
                .orElse(null);
        
        if (treasuryCenter == null) {
            log.warn("No Treasury Center found for IHB sweep setup. Sweep rules not created for entity: {}", 
                    entity.getEntityCode());
            return;
        }
        
        // Find treasury center's settlement VA
        UUID treasuryVaId = treasuryCenter.getSettlementVaId();
        VirtualAccount treasuryVa = null;
        if (treasuryVaId != null) {
            treasuryVa = virtualAccountRepository.findById(treasuryVaId).orElse(null);
        }
        if (treasuryVa == null) {
            // Try to find a VA owned by treasury center
            List<VirtualAccount> treasuryVas = virtualAccountRepository.findByOwningEntityId(treasuryCenter.getId());
            if (!treasuryVas.isEmpty()) {
                treasuryVa = treasuryVas.get(0);
            }
        }
        
        if (treasuryVa == null) {
            log.warn("Treasury Center {} has no settlement VA. Sweep rules not created.", 
                    treasuryCenter.getEntityCode());
            return;
        }
        
        // Find entity's virtual accounts
        List<VirtualAccount> entityVas = virtualAccountRepository.findByOwningEntityId(entity.getId());
        if (entityVas.isEmpty()) {
            log.info("Entity {} has no virtual accounts. Sweep rules will be created when VAs are assigned.", 
                    entity.getEntityCode());
            return;
        }
        
        // Get target balance from request (applied to each account)
        BigDecimal targetBalance = request.getTargetCashBalance() != null ? 
                request.getTargetCashBalance() : BigDecimal.ZERO;
        
        // Create a single sweep rule with all entity VAs as sources
        // Generate rule reference that fits VARCHAR(20): IHB + 6 char hash
        String hash = String.valueOf(Math.abs((entity.getEntityCode() + System.currentTimeMillis()).hashCode()));
        String ruleRef = "IHB" + hash.substring(0, Math.min(hash.length(), 10));
        if (ruleRef.length() > 20) ruleRef = ruleRef.substring(0, 20);
        
        SweepRule rule = new SweepRule();
        rule.setRuleReference(ruleRef);
        rule.setRuleName("IHB Sweep: " + entity.getEntityName() + " → Treasury");
        rule.setSweepType(targetBalance.compareTo(BigDecimal.ZERO) == 0 ? 
                SweepRule.SweepType.ZERO_BALANCE : SweepRule.SweepType.TARGET_BALANCE);
        rule.setTargetAmount(targetBalance);
        rule.setTargetAccountId(treasuryVa.getId());
        rule.setTargetAccountNumber(treasuryVa.getVaNumber());
        rule.setTargetEntityCode(treasuryCenter.getEntityCode());
        rule.setFrequency(mapSweepFrequency(request.getSweepFrequency()));
        rule.setPriority(10);
        rule.setStatus(SweepRule.SweepStatus.ACTIVE);
        rule.setCurrencyCode(entity.getFunctionalCurrency() != null ? entity.getFunctionalCurrency() : marketProfile.getDefaultCurrency());
        
        // Add source accounts
        List<SweepRuleSource> sources = new ArrayList<>();
        for (VirtualAccount va : entityVas) {
            SweepRuleSource source = new SweepRuleSource();
            source.setRule(rule);
            source.setAccountId(va.getId());
            source.setAccountNumber(va.getVaNumber());
            source.setEntityCode(entity.getEntityCode());
            source.setEntityName(entity.getEntityName());
            source.setCurrencyCode(va.getCurrencyCode());
            sources.add(source);
            
            // Also update VA with IHB sweep config
            va.setTargetCashBalance(targetBalance);
            va.setIhbSweepEnabled(true);
            va.setIhbSweepFrequency(request.getSweepFrequency() != null ? request.getSweepFrequency() : "DAILY");
            virtualAccountRepository.save(va);
        }
        rule.setSourceAccounts(sources);
        
        sweepRuleRepository.save(rule);
        log.info("Created IHB sweep rule {} with {} source accounts for entity {} → Treasury Center", 
                ruleRef, sources.size(), entity.getEntityCode());
    }
    
    /**
     * Map sweep frequency string to enum
     */
    private SweepRule.SweepFrequency mapSweepFrequency(String frequency) {
        if (frequency == null) return SweepRule.SweepFrequency.DAILY;
        try {
            return SweepRule.SweepFrequency.valueOf(frequency.toUpperCase());
        } catch (IllegalArgumentException e) {
            return SweepRule.SweepFrequency.DAILY;
        }
    }
    
    /**
     * ENHANCED: Mark entity's VAs as participating in IHB.
     * Sets account-level IHB sweep configuration.
     */
    private void enrollEntityAccounts(LegalEntity entity, IhbDto.EnableIhbRequest request) {
        List<VirtualAccount> entityVas = virtualAccountRepository.findByOwningEntityId(entity.getId());
        int enrolled = 0;
        
        BigDecimal targetBalance = request.getTargetCashBalance() != null ? 
                request.getTargetCashBalance() : BigDecimal.ZERO;
        String sweepFrequency = request.getSweepFrequency() != null ? 
                request.getSweepFrequency() : "DAILY";
        
        for (VirtualAccount va : entityVas) {
            // Update VA with IHB sweep configuration at account level
            va.setIhbSweepEnabled(true);
            va.setTargetCashBalance(targetBalance);
            va.setIhbSweepFrequency(sweepFrequency);
            virtualAccountRepository.save(va);
            enrolled++;
        }
        
        log.info("Enrolled {} virtual accounts for entity {} in IHB pool with target balance {}", 
                enrolled, entity.getEntityCode(), targetBalance);
    }

    @Transactional
    public IhbDto.EntityResponse updateIhbSettings(UUID entityId, IhbDto.UpdateIhbSettingsRequest request) {
        LegalEntity entity = legalEntityRepository.findById(entityId)
                .orElseThrow(() -> new ResourceNotFoundException("Entity not found: " + entityId));
        if (!entity.isIhbEnabled()) {
            throw new BusinessException("IHB not enabled for entity: " + entity.getEntityCode());
        }
        if (request.getCreditLimit() != null) {
            if (request.getCreditLimit().compareTo(entity.getIhbCurrentExposure()) < 0) {
                throw new BusinessException("New limit cannot be less than current exposure: " + entity.getIhbCurrentExposure());
            }
            entity.setIhbCreditLimit(request.getCreditLimit());
            entity.setIhbAvailableLimit(entity.getAvailableIhbLimit());
        }
        if (request.getLendingRateSpread() != null) entity.setLendingRateSpread(request.getLendingRateSpread());
        if (request.getBorrowingRateSpread() != null) entity.setBorrowingRateSpread(request.getBorrowingRateSpread());
        if (request.getCanLend() != null) entity.setCanLend(request.getCanLend());
        if (request.getCanBorrow() != null) entity.setCanBorrow(request.getCanBorrow());
        if (request.getSettlementVaId() != null) {
            validateSettlementVa(request.getSettlementVaId(), entity);
            entity.setSettlementVaId(request.getSettlementVaId());
        }
        entity = legalEntityRepository.save(entity);
        log.info("Updated IHB settings for entity: {}", entity.getEntityCode());
        return toEntityResponse(entity);
    }

    @Transactional
    public void disableIhb(UUID entityId) {
        LegalEntity entity = legalEntityRepository.findById(entityId)
                .orElseThrow(() -> new ResourceNotFoundException("Entity not found: " + entityId));
        if (entity.getIhbCurrentExposure() != null && entity.getIhbCurrentExposure().compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException("Cannot disable IHB: outstanding borrowings: " + entity.getIhbCurrentExposure());
        }
        if (entity.getTotalLentOut() != null && entity.getTotalLentOut().compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException("Cannot disable IHB: outstanding loans: " + entity.getTotalLentOut());
        }
        entity.disableIhb();
        legalEntityRepository.save(entity);
        log.info("IHB disabled for entity: {}", entity.getEntityCode());
    }

    // ========================================================================
    // TREASURY RATES (for IHB participation visibility)
    // ========================================================================

    /**
     * Get Treasury Center's offered rates for IHB participation.
     * This allows entities to see lending/deposit rates before joining.
     */
    @Transactional(readOnly = true)
    public IhbDto.TreasuryRatesResponse getTreasuryRates(UUID corporateId) {
        // Find treasury center for this corporate
        LegalEntity treasuryCenter = legalEntityRepository.findByCanLendTrue()
                .stream()
                .filter(e -> e.getCorporateId().equals(corporateId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                    "No Treasury Center found for corporate: " + corporateId));
        
        return buildTreasuryRatesResponse(treasuryCenter);
    }

    /**
     * Get Treasury Center's rates by treasury entity ID.
     */
    @Transactional(readOnly = true)
    public IhbDto.TreasuryRatesResponse getTreasuryRatesById(UUID treasuryCenterId) {
        LegalEntity treasuryCenter = legalEntityRepository.findById(treasuryCenterId)
                .orElseThrow(() -> new ResourceNotFoundException(
                    "Treasury Center not found: " + treasuryCenterId));
        
        if (!treasuryCenter.canLend()) {
            throw new BusinessException("Entity is not a Treasury Center: " + treasuryCenter.getEntityCode());
        }
        
        return buildTreasuryRatesResponse(treasuryCenter);
    }

    /**
     * Calculate indicative rate for a specific loan/deposit request.
     */
    @Transactional(readOnly = true)
    public IhbDto.IndicativeRateResponse getIndicativeRate(
            UUID treasuryCenterId, UUID entityId, IhbDto.IndicativeRateRequest request) {
        
        LegalEntity treasuryCenter = legalEntityRepository.findById(treasuryCenterId)
                .orElseThrow(() -> new ResourceNotFoundException("Treasury Center not found"));
        
        LegalEntity entity = legalEntityRepository.findById(entityId)
                .orElseThrow(() -> new ResourceNotFoundException("Entity not found"));
        
        boolean isLending = "LENDING".equalsIgnoreCase(request.getRateType());
        
        // Base rate (could come from interest config or default)
        BigDecimal baseRate = new BigDecimal("5.00"); // Default reference-rate level
        String baseRateType = marketProfile.getDefaultBaseRateType();
        
        // Treasury spread
        BigDecimal treasurySpread = isLending ? 
                treasuryCenter.getLendingRateSpread() : 
                treasuryCenter.getBorrowingRateSpread().negate(); // Negative for deposits
        
        // Entity spread (borrower pays extra, depositor gets less)
        BigDecimal entitySpread = isLending ?
                entity.getBorrowingRateSpread() :
                entity.getLendingRateSpread().negate();
        
        // Effective rate
        BigDecimal effectiveRate = baseRate.add(treasurySpread).add(entitySpread);
        
        // Estimated interest for tenor
        BigDecimal estimatedInterest = BigDecimal.ZERO;
        if (request.getAmount() != null && request.getTenorDays() != null) {
            // Simple interest: Principal * Rate * Days / 360
            estimatedInterest = request.getAmount()
                    .multiply(effectiveRate)
                    .multiply(new BigDecimal(request.getTenorDays()))
                    .divide(new BigDecimal("36000"), 2, RoundingMode.HALF_UP);
        }
        
        return IhbDto.IndicativeRateResponse.builder()
                .baseRate(baseRate)
                .baseRateType(baseRateType)
                .treasurySpread(treasurySpread)
                .entitySpread(entitySpread)
                .effectiveRate(effectiveRate)
                .estimatedInterest(estimatedInterest)
                .currency(request.getCurrency() != null ? request.getCurrency() : treasuryCenter.getEffectiveIhbCurrency())
                .tenorDays(request.getTenorDays())
                .rateType(request.getRateType())
                .build();
    }

    /**
     * Build Treasury rates response from InterestConfiguration if available,
     * otherwise fall back to entity spreads with default base rate.
     */
    private IhbDto.TreasuryRatesResponse buildTreasuryRatesResponse(LegalEntity treasuryCenter) {
        // Try to get rates from attached InterestConfiguration
        UUID configId = treasuryCenter.getIhbInterestConfigId();
        
        BigDecimal baseRate;
        String baseRateType;
        BigDecimal creditSpread;
        BigDecimal debitSpread;
        BigDecimal indicativeLendingRate;
        BigDecimal indicativeDepositRate;
        String dayCountConvention = "ACT/360";
        String compoundingFrequency = "DAILY";
        String settlementFrequency = "MONTHLY";
        BigDecimal minLoanAmount = new BigDecimal("10000");
        BigDecimal minDepositAmount = new BigDecimal("10000");
        
        if (configId != null) {
            // Load rates from InterestConfiguration
            InterestConfiguration config = interestConfigRepository.findById(configId)
                    .orElse(null);
            
            if (config != null && config.isActive()) {
                log.debug("Using InterestConfiguration {} for Treasury {}", 
                        config.getConfigName(), treasuryCenter.getEntityCode());
                
                // Get base rate and type
                baseRate = config.getDebitBaseRate() != null ? 
                        config.getDebitBaseRate() : 
                        (config.getCreditBaseRate() != null ? config.getCreditBaseRate() : new BigDecimal("5.00"));
                baseRateType = config.getDebitBaseRateType() != null ?
                        config.getDebitBaseRateType() :
                        (config.getCreditBaseRateType() != null ? config.getCreditBaseRateType() : marketProfile.getDefaultBaseRateType());
                
                // Get spreads
                debitSpread = config.getDebitSpread() != null ? config.getDebitSpread() : BigDecimal.ZERO;
                creditSpread = config.getCreditSpread() != null ? config.getCreditSpread() : BigDecimal.ZERO;
                
                // Get effective rates (pre-calculated or calculate)
                if (config.getEffectiveDebitRate() != null) {
                    indicativeLendingRate = config.getEffectiveDebitRate();
                } else {
                    indicativeLendingRate = baseRate.add(debitSpread);
                }
                
                if (config.getEffectiveCreditRate() != null) {
                    indicativeDepositRate = config.getEffectiveCreditRate();
                } else {
                    indicativeDepositRate = baseRate.add(creditSpread);
                }
                
                // Get calculation parameters
                if (config.getDayCountConvention() != null) {
                    dayCountConvention = config.getDayCountConvention();
                }
                if (config.getCompoundingFrequency() != null) {
                    compoundingFrequency = config.getCompoundingFrequency().name();
                }
                if (config.getPostingFrequency() != null) {
                    settlementFrequency = config.getPostingFrequency().name();
                }
                if (config.getCreditMinBalance() != null) {
                    minDepositAmount = config.getCreditMinBalance();
                }
                
            } else {
                // Config not found or inactive, use defaults
                log.warn("InterestConfiguration {} not found or inactive for Treasury {}, using defaults", 
                        configId, treasuryCenter.getEntityCode());
                baseRate = new BigDecimal("5.00");
                baseRateType = marketProfile.getDefaultBaseRateType();
                debitSpread = treasuryCenter.getLendingRateSpread();
                creditSpread = treasuryCenter.getLendingRateSpread().negate();
                indicativeLendingRate = baseRate.add(debitSpread);
                indicativeDepositRate = baseRate.add(creditSpread);
            }
        } else {
            // No config attached, use legacy entity spreads with default base rate
            log.debug("No InterestConfiguration attached for Treasury {}, using entity spreads", 
                    treasuryCenter.getEntityCode());
            baseRate = new BigDecimal("5.00");
            baseRateType = "EIBOR";
            debitSpread = treasuryCenter.getLendingRateSpread();
            creditSpread = treasuryCenter.getLendingRateSpread().negate(); // Depositors get base minus spread
            indicativeLendingRate = baseRate.add(debitSpread);
            indicativeDepositRate = baseRate.add(creditSpread);
        }
        
        return IhbDto.TreasuryRatesResponse.builder()
                .treasuryCenterId(treasuryCenter.getId())
                .treasuryCenterCode(treasuryCenter.getEntityCode())
                .treasuryCenterName(treasuryCenter.getEntityName())
                .ihbCurrency(treasuryCenter.getEffectiveIhbCurrency())
                // Lending rates (what borrowers pay)
                .lendingBaseRate(baseRate)
                .lendingBaseRateType(baseRateType)
                .treasuryLendingSpread(debitSpread)
                .indicativeLendingRate(indicativeLendingRate)
                // Deposit rates (what depositors earn)
                .depositBaseRate(baseRate)
                .depositBaseRateType(baseRateType)
                .treasuryDepositSpread(creditSpread)
                .indicativeDepositRate(indicativeDepositRate)
                // Terms
                .dayCountConvention(dayCountConvention)
                .compoundingFrequency(compoundingFrequency)
                .settlementFrequency(settlementFrequency)
                // Limits
                .minLoanAmount(minLoanAmount)
                .maxLoanAmount(treasuryCenter.getIhbCreditLimit())
                .minDepositAmount(minDepositAmount)
                // Config reference
                .interestConfigId(configId)
                .hasInterestConfig(configId != null)
                // Validity
                .effectiveFrom(LocalDate.now())
                .build();
    }

    // ========================================================================
    // LOAN OPERATIONS
    // ========================================================================

    @Transactional(readOnly = true)
    public List<IhbDto.LoanResponse> getLoans(UUID corporateId) {
        return loanRepository.findByCorporateId(corporateId).stream()
                .map(this::toLoanResponse).collect(Collectors.toList());
    }

    /**
     * All loans across every corporate. Backs {@code GET /api/v1/ihb/loans},
     * which the frontend's legacy {@code ihbApi.getAllLoans()} and the
     * cockpit's loan-rollover producer call without a corporate scope.
     */
    @Transactional(readOnly = true)
    public List<IhbDto.LoanResponse> getAllLoans() {
        return loanRepository.findAll().stream()
                .map(this::toLoanResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<IhbDto.LoanResponse> getActiveLoans(UUID corporateId) {
        return loanRepository.findByCorporateIdAndStatus(corporateId, IhbLoan.LoanStatus.ACTIVE)
                .stream().map(this::toLoanResponse).collect(Collectors.toList());
    }

    @Transactional
    public IhbDto.LoanResponse createLoan(IhbDto.CreateLoanUnifiedRequest request) {
        LegalEntity lender = legalEntityRepository.findById(request.getLenderEntityId())
                .orElseThrow(() -> new ResourceNotFoundException("Lender entity not found"));
        if (!lender.canLend()) throw new BusinessException("Entity cannot lend: " + lender.getEntityCode());

        LegalEntity borrower = legalEntityRepository.findById(request.getBorrowerEntityId())
                .orElseThrow(() -> new ResourceNotFoundException("Borrower entity not found"));
        if (!borrower.canBorrow()) throw new BusinessException("Entity cannot borrow: " + borrower.getEntityCode());

        if (!lender.getCorporateId().equals(borrower.getCorporateId())) {
            throw new BusinessException("Lender and borrower must be from the same corporate");
        }
        if (!borrower.canBorrowAmount(request.getPrincipalAmount())) {
            throw new BusinessException("Borrower credit limit exceeded. Available: " + borrower.getAvailableIhbLimit());
        }

        // ENHANCED: Currency validation
        String loanCurrency = request.getCurrencyCode() != null ? 
                request.getCurrencyCode() : lender.getEffectiveIhbCurrency();
        
        // Validate currency is supported by Treasury Center
        String treasuryCurrency = lender.getEffectiveIhbCurrency();
        if (!loanCurrency.equals(treasuryCurrency)) {
            // Cross-currency IHB loans require FX - warn but allow
            log.warn("Cross-currency IHB loan: {} loan from {} treasury. FX conversion may apply.", 
                    loanCurrency, treasuryCurrency);
        }
        
        // Validate borrower has VA in the loan currency
        List<VirtualAccount> borrowerVas = virtualAccountRepository.findByOwningEntityIdAndCurrencyCode(
                borrower.getId(), loanCurrency);
        if (borrowerVas.isEmpty()) {
            throw new BusinessException("Borrower has no account in currency: " + loanCurrency + 
                    ". Create a " + loanCurrency + " virtual account first.");
        }

        UUID lenderVaId = resolveSettlementVa(lender);
        UUID borrowerVaId = resolveSettlementVaForCurrency(borrower, loanCurrency);
        
        // ENHANCED: Determine effective rate - from InterestConfig or spreads
        BigDecimal effectiveRate;
        UUID interestConfigId = request.getInterestConfigId();
        
        if (interestConfigId != null) {
            // Use rate from attached interest configuration
            effectiveRate = lookupRateFromConfig(interestConfigId, request.getPrincipalAmount());
            log.info("Using interest config {} for loan rate: {}", interestConfigId, effectiveRate);
        } else if (request.getBaseRate() != null) {
            // Calculate from base rate + spreads
            effectiveRate = request.getBaseRate()
                .add(lender.getLendingRateSpread())
                .add(borrower.getBorrowingRateSpread());
        } else {
            // Default rate from entity spreads
            effectiveRate = new BigDecimal("5.00")  // Default base
                .add(lender.getLendingRateSpread())
                .add(borrower.getBorrowingRateSpread());
        }

        IhbLoan loan = new IhbLoan();
        loan.setLoanReference("IHB-L-" + String.format("%06d", loanSequence.getAndIncrement()));
        loan.setLenderLegalEntityId(lender.getId());
        loan.setLenderEntityCode(lender.getEntityCode());
        loan.setBorrowerLegalEntityId(borrower.getId());
        loan.setBorrowerEntityCode(borrower.getEntityCode());
        loan.setCorporateId(lender.getCorporateId());
        loan.setLenderVaId(lenderVaId);
        loan.setBorrowerVaId(borrowerVaId);
        loan.setPrincipalAmount(request.getPrincipalAmount());
        loan.setCurrencyCode(request.getCurrencyCode() != null ? request.getCurrencyCode() : lender.getEffectiveIhbCurrency());
        loan.setOutstandingAmount(request.getPrincipalAmount());
        loan.setInterestRate(effectiveRate);
        loan.setInterestType(request.getInterestType() != null ? request.getInterestType() : IhbLoan.InterestType.FIXED);
        loan.setBaseRateType(request.getBaseRateType());
        loan.setSpread(lender.getLendingRateSpread().add(borrower.getBorrowingRateSpread()));
        loan.setDisbursementDate(request.getDisbursementDate() != null ? request.getDisbursementDate() : LocalDate.now());
        loan.setMaturityDate(request.getMaturityDate());
        loan.setRepaymentFrequency(request.getRepaymentFrequency() != null ? request.getRepaymentFrequency() : IhbLoan.RepaymentFrequency.MONTHLY);
        loan.setStatus(IhbLoan.LoanStatus.ACTIVE);
        loan.setAccruedInterest(BigDecimal.ZERO);
        loan.setTotalInterestPaid(BigDecimal.ZERO);
        loan.setInterestConfigId(interestConfigId);  // Store the config reference

        borrower.utilizeIhbLimit(request.getPrincipalAmount());
        lender.addLentAmount(request.getPrincipalAmount());
        legalEntityRepository.save(borrower);
        legalEntityRepository.save(lender);
        loan = loanRepository.save(loan);
        postLoanArrangementFee(loan, borrowerVaId);

        log.info("Created IHB loan: {} from {} to {} for {} {} at {}%", 
            loan.getLoanReference(), lender.getEntityCode(), borrower.getEntityCode(),
            loan.getCurrencyCode(), loan.getPrincipalAmount(), effectiveRate);
        return toLoanResponse(loan);
    }

    @Transactional
    public IhbDto.LoanResponse repayLoan(UUID loanId, IhbDto.LoanRepaymentRequest request) {
        IhbLoan loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan not found: " + loanId));
        if (loan.getStatus() != IhbLoan.LoanStatus.ACTIVE) {
            throw new BusinessException("Loan is not active: " + loan.getLoanReference());
        }

        BigDecimal repayment = request.getAmount().min(loan.getOutstandingAmount());
        loan.setOutstandingAmount(loan.getOutstandingAmount().subtract(repayment));

        LegalEntity borrower = legalEntityRepository.findById(loan.getBorrowerLegalEntityId())
                .orElseThrow(() -> new ResourceNotFoundException("Borrower not found"));
        borrower.releaseIhbLimit(repayment);
        legalEntityRepository.save(borrower);

        LegalEntity lender = legalEntityRepository.findById(loan.getLenderLegalEntityId())
                .orElseThrow(() -> new ResourceNotFoundException("Lender not found"));
        lender.reduceLentAmount(repayment);
        legalEntityRepository.save(lender);

        if (loan.getOutstandingAmount().compareTo(BigDecimal.ZERO) <= 0) {
            loan.setStatus(IhbLoan.LoanStatus.MATURED);
            log.info("Loan {} fully repaid", loan.getLoanReference());
        }
        loan.setUpdatedAt(LocalDateTime.now());
        loan = loanRepository.save(loan);
        log.info("Repaid {} on loan {}", repayment, loan.getLoanReference());
        return toLoanResponse(loan);
    }

    // ========================================================================
    // DEPOSIT OPERATIONS
    // ========================================================================

    @Transactional(readOnly = true)
    public List<IhbDto.DepositResponse> getDeposits(UUID corporateId) {
        return depositRepository.findByCorporateId(corporateId).stream()
                .map(this::toDepositResponse).collect(Collectors.toList());
    }

    /**
     * All deposits across every corporate. Backs {@code GET /api/v1/ihb/deposits}
     * — see {@link #getAllLoans()} for the caller rationale.
     */
    @Transactional(readOnly = true)
    public List<IhbDto.DepositResponse> getAllDeposits() {
        return depositRepository.findAll().stream()
                .map(this::toDepositResponse).collect(Collectors.toList());
    }

    @Transactional
    public IhbDto.DepositResponse createDeposit(IhbDto.CreateDepositUnifiedRequest request) {
        LegalEntity depositor = legalEntityRepository.findById(request.getDepositorEntityId())
                .orElseThrow(() -> new ResourceNotFoundException("Depositor entity not found"));
        if (!depositor.isIhbEnabled()) {
            throw new BusinessException("Entity is not IHB-enabled: " + depositor.getEntityCode());
        }

        LegalEntity treasury = request.getTreasuryEntityId() != null ?
            legalEntityRepository.findById(request.getTreasuryEntityId())
                .orElseThrow(() -> new ResourceNotFoundException("Treasury entity not found")) :
            findTreasuryCenter(depositor.getCorporateId());

        UUID depositorVaId = resolveSettlementVa(depositor);
        UUID treasuryVaId = treasury != null ? resolveSettlementVa(treasury) : null;

        BigDecimal interestRate = request.getInterestRate();
        if (interestRate == null && request.getBaseRate() != null) {
            interestRate = request.getBaseRate().subtract(depositor.getLendingRateSpread()).max(BigDecimal.ZERO);
        }

        IhbDeposit deposit = new IhbDeposit();
        deposit.setDepositReference("IHB-D-" + String.format("%06d", depositSequence.getAndIncrement()));
        deposit.setDepositorLegalEntityId(depositor.getId());
        deposit.setDepositorEntityCode(depositor.getEntityCode());
        deposit.setTreasuryLegalEntityId(treasury != null ? treasury.getId() : null);
        deposit.setCorporateId(depositor.getCorporateId());
        deposit.setDepositorVaId(depositorVaId);
        deposit.setTreasuryVaId(treasuryVaId);
        deposit.setPrincipalAmount(request.getPrincipalAmount());
        deposit.setCurrencyCode(request.getCurrencyCode() != null ? request.getCurrencyCode() : depositor.getEffectiveIhbCurrency());
        deposit.setCurrentBalance(request.getPrincipalAmount());
        deposit.setInterestRate(interestRate != null ? interestRate : BigDecimal.ZERO);
        deposit.setDepositDate(request.getDepositDate() != null ? request.getDepositDate() : LocalDate.now());
        deposit.setMaturityDate(request.getMaturityDate());
        deposit.setDepositType(request.getDepositType() != null ? request.getDepositType() : IhbDeposit.DepositType.CALL);
        deposit.setNoticePeriodDays(request.getNoticePeriodDays());
        deposit.setStatus(IhbDeposit.DepositStatus.ACTIVE);
        deposit.setAccruedInterest(BigDecimal.ZERO);
        deposit.setTotalInterestEarned(BigDecimal.ZERO);

        depositor.addDepositedAmount(request.getPrincipalAmount());
        legalEntityRepository.save(depositor);
        deposit = depositRepository.save(deposit);
        postDepositArrangementFee(deposit, depositorVaId);

        log.info("Created IHB deposit: {} from {} for {} {}", 
            deposit.getDepositReference(), depositor.getEntityCode(),
            deposit.getCurrencyCode(), deposit.getPrincipalAmount());
        return toDepositResponse(deposit);
    }

    @Transactional
    public IhbDto.DepositResponse withdrawDeposit(UUID depositId, IhbDto.WithdrawRequest request) {
        IhbDeposit deposit = depositRepository.findById(depositId)
                .orElseThrow(() -> new ResourceNotFoundException("Deposit not found: " + depositId));
        if (deposit.getStatus() != IhbDeposit.DepositStatus.ACTIVE) {
            throw new BusinessException("Deposit is not active: " + deposit.getDepositReference());
        }
        if (deposit.getDepositType() == IhbDeposit.DepositType.FIXED && LocalDate.now().isBefore(deposit.getMaturityDate())) {
            throw new BusinessException("Cannot withdraw fixed-term deposit before maturity");
        }

        BigDecimal withdrawal = request.getAmount().min(deposit.getCurrentBalance());
        deposit.setCurrentBalance(deposit.getCurrentBalance().subtract(withdrawal));

        LegalEntity depositor = legalEntityRepository.findById(deposit.getDepositorLegalEntityId())
                .orElseThrow(() -> new ResourceNotFoundException("Depositor not found"));
        depositor.reduceDepositedAmount(withdrawal);
        legalEntityRepository.save(depositor);

        if (deposit.getCurrentBalance().compareTo(BigDecimal.ZERO) <= 0) {
            deposit.setStatus(IhbDeposit.DepositStatus.WITHDRAWN);
        }
        deposit.setUpdatedAt(LocalDateTime.now());
        deposit = depositRepository.save(deposit);
        log.info("Withdrew {} from deposit {}", withdrawal, deposit.getDepositReference());
        return toDepositResponse(deposit);
    }

    // ========================================================================
    // INTEREST CALCULATION
    // ========================================================================

    @Transactional
    public IhbDto.CalculateInterestResponse calculateDailyInterest(UUID corporateId) {
        LocalDate today = LocalDate.now();
        List<IhbLoan> activeLoans = loanRepository.findByCorporateIdAndStatus(corporateId, IhbLoan.LoanStatus.ACTIVE);
        List<IhbDeposit> activeDeposits = depositRepository.findByCorporateIdAndStatus(corporateId, IhbDeposit.DepositStatus.ACTIVE);

        BigDecimal totalLoanInterest = BigDecimal.ZERO;
        BigDecimal totalDepositInterest = BigDecimal.ZERO;
        BigDecimal totalSpread = BigDecimal.ZERO;

        for (IhbLoan loan : activeLoans) {
            BigDecimal dailyRate = loan.getInterestRate().divide(BigDecimal.valueOf(36500), 10, RoundingMode.HALF_UP);
            BigDecimal interest = loan.getOutstandingAmount().multiply(dailyRate).setScale(2, RoundingMode.HALF_UP);
            loan.setAccruedInterest(loan.getAccruedInterest().add(interest));
            totalLoanInterest = totalLoanInterest.add(interest);

            if (loan.getSpread() != null && loan.getSpread().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal spread = calculateInterestSpread(loan, interest);
                if (spread.compareTo(BigDecimal.ZERO) > 0 && loan.getBorrowerVaId() != null) {
                    postInterestSpread(loan, spread);
                    totalSpread = totalSpread.add(spread);
                }
            }
        }
        loanRepository.saveAll(activeLoans);

        for (IhbDeposit deposit : activeDeposits) {
            BigDecimal dailyRate = deposit.getInterestRate().divide(BigDecimal.valueOf(36500), 10, RoundingMode.HALF_UP);
            BigDecimal interest = deposit.getCurrentBalance().multiply(dailyRate).setScale(2, RoundingMode.HALF_UP);
            deposit.setAccruedInterest(deposit.getAccruedInterest().add(interest));
            totalDepositInterest = totalDepositInterest.add(interest);
        }
        depositRepository.saveAll(activeDeposits);

        IhbDto.CalculateInterestResponse response = new IhbDto.CalculateInterestResponse();
        response.setCalculationDate(today);
        response.setLoansProcessed(activeLoans.size());
        response.setDepositsProcessed(activeDeposits.size());
        response.setTotalLoanInterest(totalLoanInterest);
        response.setTotalDepositInterest(totalDepositInterest);
        response.setTotalSpread(totalSpread);
        response.setNetInterest(totalLoanInterest.subtract(totalDepositInterest));

        log.info("Daily interest for corporate {}: {} loans, {} deposits", corporateId, activeLoans.size(), activeDeposits.size());
        return response;
    }

    // ========================================================================
    // STATISTICS
    // ========================================================================

    @Transactional(readOnly = true)
    public IhbDto.IhbStatsResponse getStats(UUID corporateId) {
        List<LegalEntity> ihbEntities = legalEntityRepository.findByCorporateIdAndIhbEnabledTrue(corporateId);
        List<IhbLoan> activeLoans = loanRepository.findByCorporateIdAndStatus(corporateId, IhbLoan.LoanStatus.ACTIVE);
        List<IhbDeposit> activeDeposits = depositRepository.findByCorporateIdAndStatus(corporateId, IhbDeposit.DepositStatus.ACTIVE);

        BigDecimal totalOutstanding = activeLoans.stream().map(IhbLoan::getOutstandingAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalDeposits = activeDeposits.stream().map(IhbDeposit::getCurrentBalance).reduce(BigDecimal.ZERO, BigDecimal::add);

        IhbDto.IhbStatsResponse stats = new IhbDto.IhbStatsResponse();
        stats.setTotalEntities((long) ihbEntities.size());
        stats.setActiveLoans(activeLoans.size());
        stats.setActiveDeposits(activeDeposits.size());
        stats.setTotalOutstandingLoans(totalOutstanding);
        stats.setTotalDepositsBalance(totalDeposits);
        stats.setNetPosition(totalDeposits.subtract(totalOutstanding));
        return stats;
    }

    @Transactional(readOnly = true)
    public IhbDto.EntityPositionResponse getEntityPosition(UUID entityId) {
        LegalEntity entity = legalEntityRepository.findById(entityId)
                .orElseThrow(() -> new ResourceNotFoundException("Entity not found: " + entityId));

        List<IhbLoan> loansAsLender = loanRepository.findByLenderLegalEntityIdAndStatus(entityId, IhbLoan.LoanStatus.ACTIVE);
        List<IhbLoan> loansAsBorrower = loanRepository.findByBorrowerLegalEntityIdAndStatus(entityId, IhbLoan.LoanStatus.ACTIVE);
        List<IhbDeposit> deposits = depositRepository.findByDepositorLegalEntityIdAndStatus(entityId, IhbDeposit.DepositStatus.ACTIVE);

        BigDecimal totalLent = loansAsLender.stream().map(IhbLoan::getOutstandingAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalBorrowed = loansAsBorrower.stream().map(IhbLoan::getOutstandingAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalDeposited = deposits.stream().map(IhbDeposit::getCurrentBalance).reduce(BigDecimal.ZERO, BigDecimal::add);

        IhbDto.EntityPositionResponse response = new IhbDto.EntityPositionResponse();
        response.setEntityId(entityId);
        response.setEntityCode(entity.getEntityCode());
        response.setEntityName(entity.getEntityName());
        response.setTotalLentOut(totalLent);
        response.setTotalBorrowed(totalBorrowed);
        response.setTotalDeposited(totalDeposited);
        response.setNetPosition(totalLent.add(totalDeposited).subtract(totalBorrowed));
        response.setIhbCreditLimit(entity.getIhbCreditLimit());
        response.setIhbAvailableLimit(entity.getIhbAvailableLimit());
        response.setUtilizationPercent(entity.getIhbUtilizationPercent());
        response.setLoansAsLender(loansAsLender.stream().map(this::toLoanResponse).collect(Collectors.toList()));
        response.setLoansAsBorrower(loansAsBorrower.stream().map(this::toLoanResponse).collect(Collectors.toList()));
        response.setDeposits(deposits.stream().map(this::toDepositResponse).collect(Collectors.toList()));
        return response;
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    /**
     * Resolve Settlement VA for an entity.
     *
     * Priority:
     * 1. IHB Current Account (ihbParticipant=true) - preferred for IHB operations
     * 2. Entity's configured settlementVaId
     * 3. Any TRANSACTION VA in entity's IHB currency
     *
     * @param entity The legal entity
     * @return VA ID for settlements, or null if not found
     */
    private UUID resolveSettlementVa(LegalEntity entity) {
        String currency = entity.getEffectiveIhbCurrency();
        return resolveSettlementVaForCurrency(entity, currency);
    }

    /**
     * Resolve Settlement VA for a specific currency.
     *
     * For IHB operations (loans, deposits, sweeps), we prefer the IHB Current Account
     * (ihbParticipant=true) as this is the designated account for treasury operations
     * with proper interest accrual, sweep configuration, and hierarchy linking.
     *
     * Priority:
     * 1. IHB Current Account in requested currency (ihbParticipant=true)
     * 2. Entity's configured settlementVaId (if matching currency)
     * 3. Any TRANSACTION VA in requested currency
     * 4. Entity's default settlementVaId (fallback, may be different currency)
     *
     * @param entity The legal entity
     * @param currency The currency code
     * @return VA ID for settlements, or null if not found
     */
    private UUID resolveSettlementVaForCurrency(LegalEntity entity, String currency) {
        // 1. Prefer IHB Current Account - designed for treasury operations
        Optional<VirtualAccount> ihbVa = virtualAccountRepository
            .findByOwningEntityIdAndIhbParticipantTrueAndCurrencyCode(entity.getId(), currency);
        if (ihbVa.isPresent()) {
            UUID vaId = ihbVa.get().getId();
            log.debug("Resolved IHB Current Account {} for entity {} in {}",
                ihbVa.get().getVaNumber(), entity.getEntityCode(), currency);
            return vaId;
        }

        // 2. Check entity's configured settlement VA
        if (entity.hasSettlementVa()) {
            Optional<VirtualAccount> configuredVa = virtualAccountRepository.findById(entity.getSettlementVaId());
            if (configuredVa.isPresent() && configuredVa.get().getCurrencyCode().equals(currency)) {
                log.debug("Using entity's configured settlement VA {} for {}",
                    configuredVa.get().getVaNumber(), entity.getEntityCode());
                return entity.getSettlementVaId();
            }
        }

        // 3. Look for any TRANSACTION VA in requested currency
        List<VirtualAccount> vas = virtualAccountRepository.findByOwningEntityIdAndCurrencyCodeAndAccountCategory(
            entity.getId(), currency, VirtualAccount.AccountCategory.TRANSACTION);
        if (!vas.isEmpty()) {
            UUID vaId = vas.get(0).getId();
            log.debug("Resolved TRANSACTION VA {} for entity {} in {}", vaId, entity.getEntityCode(), currency);
            return vaId;
        }

        // 4. Fall back to entity's default settlement VA (may be different currency)
        if (entity.hasSettlementVa()) {
            log.warn("No {} VA found for entity {}, using default settlement VA (may be different currency)",
                currency, entity.getEntityCode());
            return entity.getSettlementVaId();
        }

        log.warn("No VA found for entity {} with currency {}", entity.getEntityCode(), currency);
        return null;
    }

    private void validateSettlementVa(UUID vaId, LegalEntity entity) {
        VirtualAccount va = virtualAccountRepository.findById(vaId)
                .orElseThrow(() -> new ResourceNotFoundException("VA not found: " + vaId));
        if (!entity.getId().equals(va.getOwningEntityId())) {
            throw new BusinessException("VA " + va.getVaNumber() + " does not belong to entity " + entity.getEntityCode());
        }
    }

    private LegalEntity findTreasuryCenter(UUID corporateId) {
        return legalEntityRepository.findByCorporateIdAndIsTreasuryCenterTrue(corporateId)
                .stream().findFirst().orElse(null);
    }

    private BigDecimal calculateInterestSpread(IhbLoan loan, BigDecimal totalInterest) {
        if (loan.getSpread() == null || loan.getSpread().compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        BigDecimal spreadRatio = loan.getSpread().divide(loan.getInterestRate(), 10, RoundingMode.HALF_UP);
        return totalInterest.multiply(spreadRatio).setScale(2, RoundingMode.HALF_UP);
    }

    private void postLoanArrangementFee(IhbLoan loan, UUID borrowerVaId) {
        if (borrowerVaId == null) return;
        BigDecimal fee = loan.getPrincipalAmount().multiply(LOAN_ARRANGEMENT_FEE_RATE).setScale(2, RoundingMode.HALF_UP);
        fee = fee.max(LOAN_ARRANGEMENT_FEE_MIN).min(LOAN_ARRANGEMENT_FEE_MAX);
        try {
            feePostingService.postFee(borrowerVaId, fee, "IHB_LOAN_ARRANGEMENT_FEE", loan.getId(),
                "IHB loan " + loan.getLoanReference() + " arrangement fee");
            log.info("Posted loan fee {} for {}", fee, loan.getLoanReference());
        } catch (Exception e) {
            log.error("Failed to post loan fee for {}: {}", loan.getLoanReference(), e.getMessage());
        }
    }

    private void postDepositArrangementFee(IhbDeposit deposit, UUID depositorVaId) {
        if (depositorVaId == null) return;
        BigDecimal fee = deposit.getPrincipalAmount().multiply(DEPOSIT_ARRANGEMENT_FEE_RATE).setScale(2, RoundingMode.HALF_UP);
        fee = fee.max(DEPOSIT_ARRANGEMENT_FEE_MIN);
        try {
            feePostingService.postFee(depositorVaId, fee, "IHB_DEPOSIT_ARRANGEMENT_FEE", deposit.getId(),
                "IHB deposit " + deposit.getDepositReference() + " arrangement fee");
            log.info("Posted deposit fee {} for {}", fee, deposit.getDepositReference());
        } catch (Exception e) {
            log.error("Failed to post deposit fee for {}: {}", deposit.getDepositReference(), e.getMessage());
        }
    }

    private void postInterestSpread(IhbLoan loan, BigDecimal spread) {
        if (loan.getBorrowerVaId() == null) return;
        try {
            feePostingService.postFee(loan.getBorrowerVaId(), spread, "IHB_INTEREST_SPREAD", loan.getId(),
                "IHB loan " + loan.getLoanReference() + " interest spread");
        } catch (Exception e) {
            log.error("Failed to post spread for {}: {}", loan.getLoanReference(), e.getMessage());
        }
    }

    // ========================================================================
    // MAPPERS
    // ========================================================================

    private IhbDto.EntityResponse toEntityResponse(LegalEntity entity) {
        IhbDto.EntityResponse dto = new IhbDto.EntityResponse();
        dto.setId(entity.getId());
        dto.setEntityCode(entity.getEntityCode());
        dto.setEntityName(entity.getEntityName());
        dto.setEntityType(mapEntityType(entity.getEntityType()));
        dto.setCreditLimit(entity.getIhbCreditLimit());
        dto.setCurrentExposure(entity.getIhbCurrentExposure());
        dto.setAvailableLimit(entity.getIhbAvailableLimit());
        dto.setLendingRateSpread(entity.getLendingRateSpread());
        dto.setBorrowingRateSpread(entity.getBorrowingRateSpread());
        dto.setCanLend(entity.getCanLend());
        dto.setCanBorrow(entity.getCanBorrow());
        dto.setContactEmail(entity.getContactEmail());
        dto.setStatus(mapEntityStatus(entity.getStatus()));
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setTotalLentOut(entity.getTotalLentOut());
        dto.setTotalDeposited(entity.getTotalDeposited());
        dto.setNetIhbPosition(entity.getNetIhbPosition());
        dto.setSettlementVaId(entity.getSettlementVaId());
        dto.setIhbCurrency(entity.getEffectiveIhbCurrency());
        dto.setUtilizationPercent(entity.getIhbUtilizationPercent());
        dto.setLimitWarning(entity.isIhbLimitWarning());
        dto.setLimitBreached(entity.isIhbLimitBreached());
        return dto;
    }

    private IhbDto.LoanResponse toLoanResponse(IhbLoan loan) {
        IhbDto.LoanResponse dto = new IhbDto.LoanResponse();
        dto.setId(loan.getId());
        dto.setLoanReference(loan.getLoanReference());
        dto.setLenderEntityId(loan.getLenderLegalEntityId());
        dto.setLenderEntityCode(loan.getLenderEntityCode());
        dto.setBorrowerEntityId(loan.getBorrowerLegalEntityId());
        dto.setBorrowerEntityCode(loan.getBorrowerEntityCode());
        dto.setPrincipalAmount(loan.getPrincipalAmount());
        dto.setCurrencyCode(loan.getCurrencyCode());
        dto.setOutstandingAmount(loan.getOutstandingAmount());
        dto.setInterestRate(loan.getInterestRate());
        dto.setInterestType(loan.getInterestType());
        dto.setBaseRateType(loan.getBaseRateType());
        dto.setSpread(loan.getSpread());
        dto.setAccruedInterest(loan.getAccruedInterest());
        dto.setTotalInterestPaid(loan.getTotalInterestPaid());
        dto.setDisbursementDate(loan.getDisbursementDate());
        dto.setMaturityDate(loan.getMaturityDate());
        dto.setRepaymentFrequency(loan.getRepaymentFrequency());
        dto.setStatus(loan.getStatus());
        dto.setCreatedAt(loan.getCreatedAt());
        legalEntityRepository.findById(loan.getLenderLegalEntityId()).ifPresent(e -> dto.setLenderEntityName(e.getEntityName()));
        legalEntityRepository.findById(loan.getBorrowerLegalEntityId()).ifPresent(e -> dto.setBorrowerEntityName(e.getEntityName()));
        return dto;
    }

    private IhbDto.DepositResponse toDepositResponse(IhbDeposit deposit) {
        IhbDto.DepositResponse dto = new IhbDto.DepositResponse();
        dto.setId(deposit.getId());
        dto.setDepositReference(deposit.getDepositReference());
        dto.setDepositorEntityId(deposit.getDepositorLegalEntityId());
        dto.setDepositorEntityCode(deposit.getDepositorEntityCode());
        dto.setPrincipalAmount(deposit.getPrincipalAmount());
        dto.setCurrencyCode(deposit.getCurrencyCode());
        dto.setCurrentBalance(deposit.getCurrentBalance());
        dto.setInterestRate(deposit.getInterestRate());
        dto.setAccruedInterest(deposit.getAccruedInterest());
        dto.setTotalInterestEarned(deposit.getTotalInterestEarned());
        dto.setDepositDate(deposit.getDepositDate());
        dto.setMaturityDate(deposit.getMaturityDate());
        dto.setDepositType(deposit.getDepositType());
        dto.setNoticePeriodDays(deposit.getNoticePeriodDays());
        dto.setStatus(deposit.getStatus());
        dto.setCreatedAt(deposit.getCreatedAt());
        legalEntityRepository.findById(deposit.getDepositorLegalEntityId()).ifPresent(e -> dto.setDepositorEntityName(e.getEntityName()));
        return dto;
    }

    private IhbEntity.EntityType mapEntityType(LegalEntity.EntityType type) {
        if (type == null) return IhbEntity.EntityType.SUBSIDIARY;
        return switch (type) {
            case HOLDING, TREASURY_CENTER -> IhbEntity.EntityType.HEADQUARTERS;
            case BRANCH -> IhbEntity.EntityType.BRANCH;
            default -> IhbEntity.EntityType.SUBSIDIARY;
        };
    }

    private IhbEntity.EntityStatus mapEntityStatus(LegalEntity.EntityStatus status) {
        if (status == null) return IhbEntity.EntityStatus.ACTIVE;
        return switch (status) {
            case ACTIVE -> IhbEntity.EntityStatus.ACTIVE;
            case SUSPENDED -> IhbEntity.EntityStatus.SUSPENDED;
            default -> IhbEntity.EntityStatus.INACTIVE;
        };
    }

    // ========================================================================
    // INTEREST CONFIGURATION INTEGRATION
    // ========================================================================

    /**
     * Look up interest rate from attached InterestConfiguration.
     * For loans, uses the effective debit rate (what borrower pays).
     * For deposits, uses the effective credit rate (what depositor earns).
     */
    private BigDecimal lookupRateFromConfig(UUID configId, BigDecimal amount) {
        if (configId == null) {
            return new BigDecimal("5.00"); // Default rate
        }
        
        try {
            InterestConfiguration config = interestConfigRepository.findById(configId)
                .orElseThrow(() -> new ResourceNotFoundException("Interest config not found: " + configId));
            
            // For IHB loans, use debit rate (what borrower pays)
            // This is the rate charged on borrowed funds
            BigDecimal rate = config.getEffectiveDebitRate();
            
            if (rate == null) {
                // Fall back to credit rate if debit not set
                rate = config.getEffectiveCreditRate();
            }
            
            if (rate == null) {
                // Calculate from base + spread if effective not set
                BigDecimal baseRate = config.getDebitBaseRate() != null ? 
                        config.getDebitBaseRate() : config.getCreditBaseRate();
                BigDecimal spread = config.getDebitSpread() != null ? 
                        config.getDebitSpread() : BigDecimal.ZERO;
                rate = baseRate != null ? baseRate.add(spread) : new BigDecimal("5.00");
            }
            
            log.debug("Looked up rate {} from config {} for amount {}", rate, configId, amount);
            return rate;
            
        } catch (ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to lookup rate from config {}: {}", configId, e.getMessage());
            return new BigDecimal("5.00"); // Default on error
        }
    }

    /**
     * Look up deposit rate from attached InterestConfiguration.
     * Uses the effective credit rate (what depositor earns).
     */
    private BigDecimal lookupDepositRateFromConfig(UUID configId, BigDecimal amount) {
        if (configId == null) {
            return new BigDecimal("3.00"); // Default deposit rate
        }
        
        try {
            InterestConfiguration config = interestConfigRepository.findById(configId)
                .orElseThrow(() -> new ResourceNotFoundException("Interest config not found: " + configId));
            
            // For IHB deposits, use credit rate (what depositor earns)
            BigDecimal rate = config.getEffectiveCreditRate();
            
            if (rate == null) {
                // Calculate from base + spread if effective not set
                BigDecimal baseRate = config.getCreditBaseRate();
                BigDecimal spread = config.getCreditSpread() != null ? 
                        config.getCreditSpread() : BigDecimal.ZERO;
                rate = baseRate != null ? baseRate.add(spread) : new BigDecimal("3.00");
            }
            
            log.debug("Looked up deposit rate {} from config {} for amount {}", rate, configId, amount);
            return rate;
            
        } catch (ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to lookup deposit rate from config {}: {}", configId, e.getMessage());
            return new BigDecimal("3.00"); // Default on error
        }
    }

    // ========================================================================
    // IHB CURRENT ACCOUNT OPERATIONS
    // ========================================================================

    /**
     * Get IHB Current Account position.
     */
    public VirtualAccountDto.IhbPositionResponse getIhbCurrentAccountPosition(UUID accountId) {
        VirtualAccount va = virtualAccountRepository.findById(accountId)
            .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountId));

        if (!va.isIhbCurrentAccount()) {
            throw new BusinessException("Account is not an IHB Current Account: " + va.getVaNumber());
        }

        return buildIhbPositionResponse(va);
    }

    /**
     * Get all IHB Current Accounts for a corporate.
     * Now uses ihbParticipant flag instead of INTERCOMPANY category.
     */
    public List<VirtualAccountDto.IhbPositionResponse> getIhbCurrentAccountsByCorporate(UUID corporateId) {
        // Use the new ihbParticipant flag based query
        List<VirtualAccount> accounts = virtualAccountRepository.findIhbParticipantsByCorporate(corporateId);
        return accounts.stream()
            .map(this::buildIhbPositionResponse)
            .collect(Collectors.toList());
    }

    /**
     * Deposit to IHB Current Account.
     */
    @Transactional
    public VirtualAccountDto.IhbPositionResponse depositToCurrentAccount(
            UUID accountId, IhbDto.CurrentAccountTransactionRequest request) {

        VirtualAccount va = virtualAccountRepository.findById(accountId)
            .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountId));

        if (!va.isIhbCurrentAccount()) {
            throw new BusinessException("Account is not an IHB Current Account");
        }

        BigDecimal amount = request.getAmount();
        va.setCurrentBalance(va.getCurrentBalance().add(amount));
        va.setAvailableBalance(va.getIhbAvailableBalance());
        va.setLastActivityDate(LocalDate.now());
        virtualAccountRepository.save(va);

        log.info("Deposited {} to IHB account {}, new balance: {}",
            amount, va.getVaNumber(), va.getCurrentBalance());

        return buildIhbPositionResponse(va);
    }

    /**
     * Withdraw from IHB Current Account.
     */
    @Transactional
    public VirtualAccountDto.IhbPositionResponse withdrawFromCurrentAccount(
            UUID accountId, IhbDto.CurrentAccountTransactionRequest request) {

        VirtualAccount va = virtualAccountRepository.findById(accountId)
            .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountId));

        if (!va.isIhbCurrentAccount()) {
            throw new BusinessException("Account is not an IHB Current Account");
        }

        BigDecimal amount = request.getAmount();

        if (!va.canWithdrawIhb(amount)) {
            throw new BusinessException("Insufficient funds. Available: " + va.getIhbAvailableBalance() +
                ", Requested: " + amount);
        }

        va.setCurrentBalance(va.getCurrentBalance().subtract(amount));
        va.setAvailableBalance(va.getIhbAvailableBalance());
        va.setLastActivityDate(LocalDate.now());
        virtualAccountRepository.save(va);

        log.info("Withdrew {} from IHB account {}, new balance: {}",
            amount, va.getVaNumber(), va.getCurrentBalance());

        return buildIhbPositionResponse(va);
    }

    /**
     * Transfer between IHB Current Accounts.
     */
    @Transactional
    public IhbDto.CurrentAccountTransferResponse transferBetweenCurrentAccounts(
            IhbDto.CurrentAccountTransferRequest request) {

        VirtualAccount fromVa = virtualAccountRepository.findById(request.getFromAccountId())
            .orElseThrow(() -> new ResourceNotFoundException("From account not found"));
        VirtualAccount toVa = virtualAccountRepository.findById(request.getToAccountId())
            .orElseThrow(() -> new ResourceNotFoundException("To account not found"));

        if (!fromVa.isIhbCurrentAccount()) {
            throw new BusinessException("Source is not an IHB Current Account");
        }
        if (!toVa.isIhbCurrentAccount()) {
            throw new BusinessException("Destination is not an IHB Current Account");
        }
        if (!fromVa.getCurrencyCode().equals(toVa.getCurrencyCode())) {
            throw new BusinessException("Cross-currency IHB transfers not supported. " +
                "From: " + fromVa.getCurrencyCode() + ", To: " + toVa.getCurrencyCode());
        }

        BigDecimal amount = request.getAmount();

        if (!fromVa.canWithdrawIhb(amount)) {
            throw new BusinessException("Insufficient funds in source account. Available: " +
                fromVa.getIhbAvailableBalance());
        }

        // Execute transfer
        String correlationId = UUID.randomUUID().toString();

        fromVa.setCurrentBalance(fromVa.getCurrentBalance().subtract(amount));
        fromVa.setAvailableBalance(fromVa.getIhbAvailableBalance());
        fromVa.setLastActivityDate(LocalDate.now());

        toVa.setCurrentBalance(toVa.getCurrentBalance().add(amount));
        toVa.setAvailableBalance(toVa.getIhbAvailableBalance());
        toVa.setLastActivityDate(LocalDate.now());

        virtualAccountRepository.save(fromVa);
        virtualAccountRepository.save(toVa);

        log.info("IHB transfer {} {} from {} to {}",
            amount, fromVa.getCurrencyCode(), fromVa.getVaNumber(), toVa.getVaNumber());

        return IhbDto.CurrentAccountTransferResponse.builder()
            .correlationId(correlationId)
            .fromAccountId(fromVa.getId())
            .fromAccountNumber(fromVa.getVaNumber())
            .toAccountId(toVa.getId())
            .toAccountNumber(toVa.getVaNumber())
            .amount(amount)
            .currencyCode(fromVa.getCurrencyCode())
            .fromAccountNewBalance(fromVa.getCurrentBalance())
            .toAccountNewBalance(toVa.getCurrentBalance())
            .transferDate(LocalDateTime.now())
            .build();
    }

    /**
     * Calculate daily interest for all IHB Current Accounts.
     * Now uses ihbParticipant flag instead of INTERCOMPANY category.
     */
    @Transactional
    public List<VirtualAccountDto.IhbInterestCalcResult> calculateDailyInterestForCurrentAccounts() {
        LocalDate today = LocalDate.now();
        // Use the new ihbParticipant flag based query
        List<VirtualAccount> accounts = virtualAccountRepository.findIhbParticipantsNeedingInterestCalc(today);
        List<VirtualAccountDto.IhbInterestCalcResult> results = new ArrayList<>();

        for (VirtualAccount va : accounts) {
            try {
                VirtualAccountDto.IhbInterestCalcResult result = calculateInterestForAccount(va, today);
                if (result != null) {
                    results.add(result);
                }
            } catch (Exception e) {
                log.error("Failed to calculate interest for account {}: {}", va.getVaNumber(), e.getMessage());
            }
        }

        log.info("Calculated daily interest for {} IHB participant accounts", results.size());
        return results;
    }

    /**
     * Post accrued interest to IHB Current Accounts.
     * Now uses ihbParticipant flag instead of INTERCOMPANY category.
     */
    @Transactional
    public List<VirtualAccountDto.IhbInterestPostingResult> postInterestForCurrentAccounts() {
        LocalDate lastDayOfPreviousMonth = LocalDate.now().withDayOfMonth(1).minusDays(1);
        // Use the new ihbParticipant flag based query
        List<VirtualAccount> accounts = virtualAccountRepository.findIhbParticipantsNeedingInterestPosting(lastDayOfPreviousMonth);
        List<VirtualAccountDto.IhbInterestPostingResult> results = new ArrayList<>();

        for (VirtualAccount va : accounts) {
            try {
                VirtualAccountDto.IhbInterestPostingResult result = postInterestForAccount(va);
                if (result != null) {
                    results.add(result);
                }
            } catch (Exception e) {
                log.error("Failed to post interest for account {}: {}", va.getVaNumber(), e.getMessage());
            }
        }

        log.info("Posted interest for {} IHB participant accounts", results.size());
        return results;
    }

    // ========================================================================
    // IHB CURRENT ACCOUNT HELPER METHODS
    // ========================================================================

    private VirtualAccountDto.IhbPositionResponse buildIhbPositionResponse(VirtualAccount va) {
        LegalEntity entity = va.getOwningEntityId() != null ?
            legalEntityRepository.findById(va.getOwningEntityId()).orElse(null) : null;
        LegalEntity treasury = va.getTreasuryPoolVaId() != null ?
            virtualAccountRepository.findById(va.getTreasuryPoolVaId())
                .map(pool -> legalEntityRepository.findById(pool.getOwningEntityId()).orElse(null))
                .orElse(null) : null;

        BigDecimal creditLimit = va.getEffectiveCreditLimit() != null ? va.getEffectiveCreditLimit() : BigDecimal.ZERO;
        BigDecimal overdraftUsed = va.isInOverdraft() ? va.getOverdraftAmount() : BigDecimal.ZERO;

        // Get effective rates (from VA or defaults)
        BigDecimal effectiveCreditRate = va.getEffectiveCreditRate() != null ?
            va.getEffectiveCreditRate() : BigDecimal.ZERO;
        BigDecimal effectiveDebitRate = va.getEffectiveDebitRate() != null ?
            va.getEffectiveDebitRate() : BigDecimal.ZERO;
        BigDecimal penaltyRate = va.getPenaltyRate() != null ?
            va.getPenaltyRate() : BigDecimal.ZERO;

        return VirtualAccountDto.IhbPositionResponse.builder()
            // Identity
            .accountId(va.getId())
            .accountNumber(va.getVaNumber())
            .accountName(va.getVaName())
            .currencyCode(va.getCurrencyCode())
            // IHB Participation flags (NEW)
            .ihbParticipant(va.isIhbParticipant())
            .ihbEnabledAt(va.getIhbEnabledAt())
            .ihbEnabledBy(va.getIhbEnabledBy())
            .accountCategory(va.getAccountCategory() != null ? va.getAccountCategory().name() : "TRANSACTION")
            .status(va.getStatus() != null ? va.getStatus().name() : "ACTIVE")
            // Participant info
            .participantEntityId(va.getOwningEntityId())
            .participantEntityCode(entity != null ? entity.getEntityCode() : va.getOwningEntityCode())
            .participantEntityName(entity != null ? entity.getEntityName() : null)
            // Balances
            .currentBalance(va.getCurrentBalance() != null ? va.getCurrentBalance() : BigDecimal.ZERO)
            .availableBalance(va.getIhbAvailableBalance())
            .positionType(va.getIhbPositionType())
            // Credit/Overdraft
            .creditLimit(creditLimit)
            .creditLimitUsed(overdraftUsed)
            .creditLimitAvailable(creditLimit.subtract(overdraftUsed).max(BigDecimal.ZERO))
            // Accrued Interest
            .accruedCreditInterest(va.getAccruedCreditInterest() != null ? va.getAccruedCreditInterest() : BigDecimal.ZERO)
            .accruedDebitInterest(va.getAccruedDebitInterest() != null ? va.getAccruedDebitInterest() : BigDecimal.ZERO)
            .netAccruedInterest(va.getNetAccruedInterest() != null ? va.getNetAccruedInterest() : BigDecimal.ZERO)
            // Interest Rates (both field names for frontend compatibility)
            .effectiveCreditRate(effectiveCreditRate)
            .effectiveDebitRate(effectiveDebitRate)
            .creditRate(effectiveCreditRate)   // Alias for frontend
            .debitRate(effectiveDebitRate)     // Alias for frontend
            .penaltyRate(penaltyRate)
            // Interest Configuration
            .internalInterestConfigId(va.getInternalInterestConfigId())
            // IHB Sweep Configuration (NEW)
            .ihbSweepEnabled(va.getIhbSweepEnabled())
            .targetCashBalance(va.getTargetCashBalance())
            .ihbSweepFrequency(va.getIhbSweepFrequency())
            // Dates
            .lastInterestCalcDate(va.getLastInterestCalcDate())
            .lastInterestPostingDate(va.getLastInterestPostingDate())
            // Treasury link (parentAccountId is the new preferred field)
            .parentAccountId(va.getParentAccountId())
            .treasuryPoolVaId(va.getTreasuryPoolVaId())  // Deprecated, kept for compatibility
            .treasuryEntityCode(treasury != null ? treasury.getEntityCode() : null)
            .build();
    }

    private VirtualAccountDto.IhbInterestCalcResult calculateInterestForAccount(VirtualAccount va, LocalDate calcDate) {
        BigDecimal balance = va.getCurrentBalance();
        if (balance == null || balance.compareTo(BigDecimal.ZERO) == 0) {
            va.setLastInterestCalcDate(calcDate);
            virtualAccountRepository.save(va);
            return null;
        }

        // Get interest configuration
        int dayBasis = 360; // Default ACT/360
        BigDecimal rate;
        String interestType;

        if (balance.compareTo(BigDecimal.ZERO) > 0) {
            // Credit position - earns interest
            rate = va.getEffectiveCreditRate() != null ? va.getEffectiveCreditRate() : new BigDecimal("2.50");
            interestType = "CREDIT";
        } else {
            // Debit position - pays interest
            rate = va.getEffectiveDebitRate() != null ? va.getEffectiveDebitRate() : new BigDecimal("5.00");
            interestType = "DEBIT";
            balance = balance.abs();
        }

        // Daily interest = Balance * Rate / (100 * DayBasis)
        BigDecimal dailyInterest = balance
            .multiply(rate)
            .divide(BigDecimal.valueOf(100L * dayBasis), 6, RoundingMode.HALF_UP);

        // Accrue interest
        if ("CREDIT".equals(interestType)) {
            va.accrueCredit(dailyInterest);
        } else {
            va.accrueDebit(dailyInterest);
        }
        va.setLastInterestCalcDate(calcDate);
        virtualAccountRepository.save(va);

        return VirtualAccountDto.IhbInterestCalcResult.builder()
            .accountId(va.getId())
            .accountNumber(va.getVaNumber())
            .calcDate(calcDate)
            .eodBalance(va.getCurrentBalance())
            .positionType(va.getIhbPositionType())
            .rateApplied(rate)
            .interestAmount(dailyInterest)
            .interestType(interestType)
            .accruedCreditInterest(va.getAccruedCreditInterest())
            .accruedDebitInterest(va.getAccruedDebitInterest())
            .build();
    }

    private VirtualAccountDto.IhbInterestPostingResult postInterestForAccount(VirtualAccount va) {
        BigDecimal creditGross = va.getAccruedCreditInterest();
        BigDecimal debitGross = va.getAccruedDebitInterest();

        if (creditGross.compareTo(BigDecimal.ZERO) == 0 && debitGross.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }

        // Get participant and treasury jurisdictions for WHT calculation
        LegalEntity participant = legalEntityRepository.findById(va.getOwningEntityId()).orElse(null);
        LegalEntity treasury = getTreasuryForAccount(va);

        String participantJurisdiction = participant != null ? participant.getJurisdiction() : null;
        String treasuryJurisdiction = treasury != null ? treasury.getJurisdiction() : null;
        boolean crossBorder = participantJurisdiction != null && treasuryJurisdiction != null
            && !participantJurisdiction.equalsIgnoreCase(treasuryJurisdiction);

        BigDecimal creditWht = BigDecimal.ZERO;
        BigDecimal debitWht = BigDecimal.ZERO;
        BigDecimal whtRate = BigDecimal.ZERO;
        String whtTreatyCode = null;
        boolean whtApplied = false;

        // Apply WHT if cross-border
        if (crossBorder) {
            // Credit interest: Treasury pays participant - Treasury withholds
            if (creditGross.compareTo(BigDecimal.ZERO) > 0) {
                TaxService.IhbWhtResult creditWhtResult = taxService.calculateIhbInterestWht(
                    treasuryJurisdiction, participantJurisdiction, creditGross);
                creditWht = creditWhtResult.getWhtAmount();
                if (creditWhtResult.isTreatyApplied()) {
                    whtRate = creditWhtResult.getWhtRate();
                    whtTreatyCode = creditWhtResult.getTreatyCode();
                    whtApplied = true;
                }
            }

            // Debit interest: Participant pays Treasury - Participant withholds
            if (debitGross.compareTo(BigDecimal.ZERO) > 0) {
                TaxService.IhbWhtResult debitWhtResult = taxService.calculateIhbInterestWht(
                    participantJurisdiction, treasuryJurisdiction, debitGross);
                debitWht = debitWhtResult.getWhtAmount();
                if (debitWhtResult.isTreatyApplied() && !whtApplied) {
                    whtRate = debitWhtResult.getWhtRate();
                    whtTreatyCode = debitWhtResult.getTreatyCode();
                    whtApplied = true;
                }
            }
        }

        // Calculate net amounts after WHT
        BigDecimal creditNet = creditGross.subtract(creditWht);
        BigDecimal debitNet = debitGross.subtract(debitWht);

        // Net interest = credit received - debit paid (both after WHT)
        BigDecimal netInterest = creditNet.subtract(debitNet);

        String postingType;
        if (netInterest.compareTo(BigDecimal.ZERO) > 0) {
            postingType = "CREDIT";
        } else if (netInterest.compareTo(BigDecimal.ZERO) < 0) {
            postingType = "DEBIT";
        } else {
            postingType = "NONE";
        }

        // Apply net interest to balance
        va.setCurrentBalance(va.getCurrentBalance().add(netInterest));
        va.setAvailableBalance(va.getIhbAvailableBalance());

        // Reset accrued interest
        va.resetAccruedInterest();
        virtualAccountRepository.save(va);

        log.info("Posted interest to IHB account {}: gross credit={}, gross debit={}, " +
                "WHT credit={}, WHT debit={}, net={}, crossBorder={}, treaty={}",
            va.getVaNumber(), creditGross, debitGross, creditWht, debitWht,
            netInterest, crossBorder, whtTreatyCode);

        return VirtualAccountDto.IhbInterestPostingResult.builder()
            .accountId(va.getId())
            .accountNumber(va.getVaNumber())
            .postingDate(LocalDate.now())
            // Gross amounts
            .creditInterestGross(creditGross)
            .debitInterestGross(debitGross)
            // WHT amounts
            .creditInterestWht(creditWht)
            .debitInterestWht(debitWht)
            .whtRate(whtRate)
            .whtTreatyCode(whtTreatyCode)
            .whtApplied(whtApplied)
            // Net amounts (after WHT)
            .creditInterestPosted(creditNet)
            .debitInterestPosted(debitNet)
            .netInterestPosted(netInterest)
            .postingType(postingType)
            // Jurisdiction info
            .participantJurisdiction(participantJurisdiction)
            .treasuryJurisdiction(treasuryJurisdiction)
            .crossBorder(crossBorder)
            .build();
    }

    /**
     * Get the Treasury entity for an IHB account.
     */
    private LegalEntity getTreasuryForAccount(VirtualAccount va) {
        if (va.getTreasuryPoolVaId() != null) {
            return virtualAccountRepository.findById(va.getTreasuryPoolVaId())
                .map(poolVa -> legalEntityRepository.findById(poolVa.getOwningEntityId()).orElse(null))
                .orElse(null);
        }
        // Fallback: find treasury center for this corporate
        return legalEntityRepository.findByCorporateIdAndCanLendTrue(va.getCorporateId())
            .stream()
            .findFirst()
            .orElse(null);
    }
}