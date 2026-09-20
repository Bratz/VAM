package com.bank.vam.service.treasury;

import com.bank.vam.dto.VirtualAccountDto;
import com.bank.vam.dto.treasury.IhbDto;
import com.bank.vam.entity.PhysicalAccount;
import com.bank.vam.entity.Program;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.credit.InterestConfiguration;
import com.bank.vam.entity.hierarchy.HierarchyNode;
import com.bank.vam.entity.hierarchy.HierarchyNode.HierarchyNodeType;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.treasury.*;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.PhysicalAccountRepository;
import com.bank.vam.repository.ProgramRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.credit.InterestConfigurationRepository;
import com.bank.vam.repository.hierarchy.HierarchyNodeRepository;
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
    private final VirtualAccountRepository virtualAccountRepository;
    private final FeePostingService feePostingService;
    // ENHANCED: For IHB→Sweep integration
    private final SweepRuleRepository sweepRuleRepository;
    // ENHANCED: For Interest Configuration attachment
    private final InterestConfigurationRepository interestConfigRepository;
    // ENHANCED: For WHT calculation on cross-border interest
    private final TaxService taxService;
    private final com.bank.vam.config.MarketProfileProperties marketProfile;
    // Moved from VirtualAccountService 2026-09-15 along with IHB Current
    // Account creation — this domain logic belongs here, not in a generic
    // VA service. See the VAM Context Ledger artifact.
    private final HierarchyNodeRepository hierarchyNodeRepository;
    private final ProgramRepository programRepository;
    private final PhysicalAccountRepository physicalAccountRepository;

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
    // STATISTICS
    // ========================================================================

    @Transactional(readOnly = true)
    public IhbDto.IhbStatsResponse getStats(UUID corporateId) {
        List<LegalEntity> ihbEntities = legalEntityRepository.findByCorporateIdAndIhbEnabledTrue(corporateId);

        IhbDto.IhbStatsResponse stats = new IhbDto.IhbStatsResponse();
        stats.setTotalEntities((long) ihbEntities.size());
        stats.setNetPosition(ihbEntities.stream()
            .map(LegalEntity::getNetIhbPosition)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add));
        return stats;
    }

    @Transactional(readOnly = true)
    public IhbDto.EntityPositionResponse getEntityPosition(UUID entityId) {
        LegalEntity entity = legalEntityRepository.findById(entityId)
                .orElseThrow(() -> new ResourceNotFoundException("Entity not found: " + entityId));

        IhbDto.EntityPositionResponse response = new IhbDto.EntityPositionResponse();
        response.setEntityId(entityId);
        response.setEntityCode(entity.getEntityCode());
        response.setEntityName(entity.getEntityName());
        response.setTotalLentOut(entity.getTotalLentOut());
        response.setTotalDeposited(entity.getTotalDeposited());
        response.setNetPosition(entity.getNetIhbPosition());
        response.setIhbCreditLimit(entity.getIhbCreditLimit());
        response.setIhbAvailableLimit(entity.getIhbAvailableLimit());
        response.setUtilizationPercent(entity.getIhbUtilizationPercent());
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

    // ========================================================================
    // IHB CURRENT ACCOUNT CREATION
    // ========================================================================

    /**
     * Create IHB Current Account for a participant entity.
     *
     * This creates an INTERCOMPANY VA that functions as the participant's
     * current account at the In-House Bank (Treasury Center).
     *
     * Features:
     * - Running balance (can go negative for overdraft)
     * - Credit interest on positive balance
     * - Debit interest on negative balance
     * - Overdraft limit from entity's IHB credit limit
     *
     * KEY DESIGN: Uses TRANSACTION category with ihbParticipant=true.
     * This allows any operational VA to participate in IHB interest schemes,
     * rather than requiring a separate INTERCOMPANY category.
     *
     * @param request IHB Current Account creation request
     * @return Created VirtualAccount with accountCategory=TRANSACTION and ihbParticipant=true
     */
    @Transactional
    public VirtualAccount createIhbCurrentAccount(VirtualAccountDto.IhbCurrentAccountRequest request) {
        log.info("Creating IHB Current Account for entity: {}, parentNodeId={}, programId={}",
            request.getParticipantEntityId(), request.getParentNodeId(), request.getProgramId());

        // 1. Validate participant entity is IHB-enabled
        LegalEntity participant = legalEntityRepository.findById(request.getParticipantEntityId())
            .orElseThrow(() -> new ResourceNotFoundException(
                "Participant entity not found: " + request.getParticipantEntityId()));

        if (!participant.isIhbEnabled()) {
            throw new BusinessException("Entity is not IHB-enabled: " + participant.getEntityCode());
        }

        // 2. Resolve or validate IHB Program
        // IHB is a Program Type, so IHB accounts MUST belong to an IHB program
        UUID ihbProgramId = resolveIhbProgram(participant.getCorporateId(), request.getProgramId());

        // 3. Get Treasury Center for this corporate
        LegalEntity treasury = legalEntityRepository
            .findByCorporateIdAndCanLendTrue(participant.getCorporateId())
            .stream()
            .findFirst()
            .orElseThrow(() -> new BusinessException(
                "No Treasury Center found for corporate: " + participant.getCorporateId()));

        if (!treasury.canLend()) {
            throw new BusinessException("Treasury Center cannot lend: " + treasury.getEntityCode());
        }

        // 5. Multiple IHB accounts per entity/currency are allowed
        // Use case: Entity may have separate IHB accounts for Operating, Payroll, Treasury Reserve, etc.
        // Each account participates independently in interest schemes.
        // Entity's total IHB position = sum of all their IHB-enabled VAs.
        long existingCount = virtualAccountRepository
            .countByOwningEntityIdAndIhbParticipantTrueAndCurrencyCode(
                participant.getId(),
                request.getCurrencyCode());

        // 6. Resolve parent hierarchy node and VA
        // ============================================================================
        // IHB CONCEPTUAL MODEL:
        // - Treasury Center IS the In-House Bank (offers accounts to subsidiaries)
        // - Subsidiary is the CUSTOMER (has account AT the Treasury)
        // - IHB Current Account should automatically appear under Treasury's hierarchy
        //
        // HIERARCHY RESOLUTION:
        // 1. If parentNodeId provided: Use the explicitly selected parent (backward compatibility)
        // 2. If NOT provided: Auto-discover Treasury's aggregation VA for the given currency
        //    The IHB Current Account is placed under Treasury because:
        //    - Treasury is the "bank" offering IHB accounts
        //    - Subsidiaries are "customers" with accounts at this internal bank
        // ============================================================================
        HierarchyNode parentHierarchyNode = null;
        VirtualAccount parentVa = null;

        if (request.getParentNodeId() != null) {
            // EXPLICIT PARENT: User selected a specific parent node
            // Frontend sends VA ID (not hierarchy node ID) because balance structure API returns VA IDs
            parentHierarchyNode = hierarchyNodeRepository.findByVirtualAccountId(request.getParentNodeId())
                .orElseGet(() -> hierarchyNodeRepository.findById(request.getParentNodeId()).orElse(null));

            if (parentHierarchyNode != null && parentHierarchyNode.getVirtualAccountId() != null) {
                parentVa = virtualAccountRepository.findById(parentHierarchyNode.getVirtualAccountId()).orElse(null);
                log.info("Found parent hierarchy node: {} with VA: {}",
                    parentHierarchyNode.getNodeCode(), parentVa != null ? parentVa.getVaNumber() : "N/A");
            }

            // Fallback: If no hierarchy node found, try to find the VA directly by the provided ID
            if (parentVa == null) {
                parentVa = virtualAccountRepository.findById(request.getParentNodeId()).orElse(null);
                if (parentVa != null) {
                    log.info("Found parent VA directly (no hierarchy node): {}", parentVa.getVaNumber());
                }
            }
        }

        // AUTO-DISCOVERY: If no explicit parent, place under Treasury's hierarchy
        // Since Treasury IS the In-House Bank, IHB Current Accounts belong under Treasury
        if (parentVa == null) {
            log.info("No explicit parent provided. Auto-discovering Treasury {} hierarchy for currency {}",
                treasury.getEntityCode(), request.getCurrencyCode());

            // Find Treasury's aggregation/root VA for this currency
            List<VirtualAccount> treasuryVas = virtualAccountRepository.findByOwningEntityIdAndCurrencyCode(
                treasury.getId(), request.getCurrencyCode());

            // Prefer AGGREGATION, then ROOT, then any VA with a hierarchy node
            parentVa = treasuryVas.stream()
                .filter(va -> va.getAccountCategory() == VirtualAccount.AccountCategory.AGGREGATION)
                .findFirst()
                .orElseGet(() -> treasuryVas.stream()
                    .filter(va -> va.getAccountCategory() == VirtualAccount.AccountCategory.ROOT)
                    .findFirst()
                    .orElseGet(() -> treasuryVas.stream()
                        .filter(va -> va.getHierarchyNodeId() != null)
                        .findFirst()
                        .orElse(null)));

            if (parentVa != null) {
                // Try to find the hierarchy node for this Treasury VA
                if (parentVa.getHierarchyNodeId() != null) {
                    parentHierarchyNode = hierarchyNodeRepository.findById(parentVa.getHierarchyNodeId()).orElse(null);
                } else {
                    parentHierarchyNode = hierarchyNodeRepository.findByVirtualAccountId(parentVa.getId()).orElse(null);
                }

                log.info("Auto-discovered Treasury parent: VA={}, hierarchyNode={}",
                    parentVa.getVaNumber(),
                    parentHierarchyNode != null ? parentHierarchyNode.getNodeCode() : "N/A");
            } else {
                log.warn("No suitable Treasury VA found for currency {} to auto-place IHB Current Account. " +
                    "The account will be created without hierarchy linking.", request.getCurrencyCode());
            }
        }

        // 7. Ensure Treasury has a Settlement VA for this currency (required for IHB settlement)
        // ============================================================================
        // The Treasury Settlement VA is the counterparty for all IHB transactions:
        // - Deposits: Subsidiary → Treasury Settlement VA
        // - Loans: Treasury Settlement VA → Subsidiary
        // - Interest Settlement: Posted via Treasury Settlement VA
        // If Treasury doesn't have one for this currency, auto-create it.
        // ============================================================================
        VirtualAccount treasurySettlementVa = ensureTreasurySettlementVa(
            treasury, request.getCurrencyCode(), ihbProgramId, parentVa, parentHierarchyNode);

        // 8. IHB Current Accounts are NOTIONAL/VIRTUAL - no physical account needed
        // ============================================================================
        // IHB (In-House Bank) Flow:
        // 1. Subsidiary deposits surplus cash → IHB Current Account balance increases (credit)
        // 2. Subsidiary borrows from Treasury → IHB Current Account balance decreases (debit)
        // 3. Daily interest accrues based on balance and configured rates
        // 4. Monthly interest posting settles accrued amounts
        //
        // This is purely internal bookkeeping between Treasury and subsidiaries.
        // No physical bank account involvement - physicalAccountId is intentionally NULL.
        // ============================================================================

        // 9. Get or create Interest Configuration for participant
        InterestConfiguration interestConfig = getOrCreateIhbInterestConfig(
            participant, treasury, request);

        // 10. Determine credit limit
        BigDecimal creditLimit = request.getCreditLimit() != null
            ? request.getCreditLimit()
            : (participant.getIhbCreditLimit() != null ? participant.getIhbCreditLimit() : BigDecimal.ZERO);

        // 11. Generate IHB account number
        String accountNumber = generateIhbCurrentAccountNumber(participant, request.getCurrencyCode());

        // 12. Determine VA name
        // If user provides a name, use it. Otherwise generate with sequence number for uniqueness.
        String vaName;
        if (request.getVaName() != null && !request.getVaName().isBlank()) {
            vaName = request.getVaName();
        } else if (existingCount > 0) {
            // Add sequence number for additional accounts
            vaName = "IHB Current - " + participant.getEntityName() + " (" + (existingCount + 1) + ")";
        } else {
            vaName = "IHB Current - " + participant.getEntityName();
        }

        // 13. Build the VA
        // KEY: TRANSACTION category with ihbParticipant=true, always linked to IHB program
        // NOTE: physicalAccountId is intentionally NULL - IHB accounts are notional/virtual
        VirtualAccount va = VirtualAccount.builder()
            // Identity
            .vaNumber(accountNumber)
            .vaName(vaName)
            .corporateId(participant.getCorporateId())
            .programId(ihbProgramId)  // Always set to resolved IHB program
            // physicalAccountId intentionally not set - IHB is notional, no physical bank account
            .currencyCode(request.getCurrencyCode())

            // Account Classification - KEY: TRANSACTION with IHB flag
            .accountCategory(VirtualAccount.AccountCategory.TRANSACTION)
            .accountType(VirtualAccount.AccountType.VIRTUAL)

            // IHB PARTICIPATION FLAG - The key change!
            .ihbParticipant(true)
            .ihbEnabledAt(LocalDateTime.now())
            .ihbEnabledBy("SYSTEM")

            // Ownership & Hierarchy
            .owningEntityId(participant.getId())
            .owningEntityCode(participant.getEntityCode())

            // VA Hierarchy: Link to Treasury's aggregation VA
            // parentVa is auto-discovered from Treasury's hierarchy if not explicitly selected
            // This places the IHB Current Account under Treasury (the In-House Bank)
            .parentAccountId(parentVa != null ? parentVa.getId() : null)

            // Treasury Settlement VA: Omnibus Settlement VA for all IHB transactions
            // This is the counterparty for 4-leg accounting: IHB Current Account → Treasury Settlement VA → Shadow VA
            // Using treasuryPoolVaId field to store this reference for transaction routing
            .treasuryPoolVaId(treasurySettlementVa != null ? treasurySettlementVa.getId() : null)

            // Balances - Start at zero
            .currentBalance(BigDecimal.ZERO)
            .availableBalance(creditLimit)
            .heldBalance(BigDecimal.ZERO)

            // Interest Configuration
            .internalInterestConfigId(interestConfig.getId())
            .effectiveCreditRate(interestConfig.getEffectiveCreditRate())
            .effectiveDebitRate(interestConfig.getEffectiveDebitRate())
            .penaltyRate(interestConfig.getPenaltyRate())

            // Credit/Overdraft Limit
            .effectiveCreditLimit(creditLimit)
            .creditLimitAvailable(creditLimit)
            .creditLimitUtilized(BigDecimal.ZERO)

            // Interest Accrual - Start at zero
            .accruedCreditInterest(BigDecimal.ZERO)
            .accruedDebitInterest(BigDecimal.ZERO)

            // IHB Sweep Configuration (from request or defaults)
            .ihbSweepEnabled(Boolean.TRUE.equals(request.getIhbSweepEnabled()))
            .targetCashBalance(request.getTargetCashBalance() != null
                ? request.getTargetCashBalance() : BigDecimal.ZERO)
            .ihbSweepFrequency(request.getIhbSweepFrequency() != null
                ? request.getIhbSweepFrequency() : "DAILY")

            // Status
            .status(VirtualAccount.VaStatus.ACTIVE)

            // Metadata
            .externalReference(participant.getEntityCode() + "-IHB-" + request.getCurrencyCode())
            .build();

        va = virtualAccountRepository.save(va);
        log.info("Created IHB Current Account: {} for entity {} (ihbParticipant=true) -> Treasury Settlement VA: {}",
            va.getVaNumber(), participant.getEntityCode(),
            treasurySettlementVa != null ? treasurySettlementVa.getVaNumber() : "N/A");

        // 14. Create hierarchy node if parent hierarchy node was resolved earlier
        // This enables the IHB Current Account to appear in the Treasury Hierarchy view
        // IMPORTANT: Use parent node's programId (not IHB programId) so the VA appears in the correct hierarchy
        if (parentHierarchyNode != null) {
            log.info("Creating hierarchy node for IHB Current Account under parent: {} (program: {})",
                parentHierarchyNode.getNodeCode(), parentHierarchyNode.getProgramId());

            // Calculate hierarchy level
            int newLevel = parentHierarchyNode.getLevelNumber() + 1;

            // Generate unique node code
            String nodeCode = "IHB-" + participant.getEntityCode() + "-" + request.getCurrencyCode();
            // Check if node code already exists under this parent, add suffix if needed
            long existingNodeCount = hierarchyNodeRepository.countByParentIdAndNodeCodeStartingWith(
                parentHierarchyNode.getId(), nodeCode);
            if (existingNodeCount > 0) {
                nodeCode = nodeCode + "-" + (existingNodeCount + 1);
            }
            String materializedPath = parentHierarchyNode.getMaterializedPath() + "/" + nodeCode;

            // Create hierarchy node - USE PARENT'S PROGRAM ID
            HierarchyNode node = HierarchyNode.builder()
                .programId(parentHierarchyNode.getProgramId())  // Use parent's program, not IHB program
                .parentId(parentHierarchyNode.getId())
                .levelNumber(newLevel)
                .nodeCode(nodeCode)
                .nodeName(va.getVaName())
                .nodeType(HierarchyNodeType.VIRTUAL_ACCOUNT)
                .currencyCode(request.getCurrencyCode())
                .dimensionValue("IHB_CURRENT_ACCOUNT")
                .materializedPath(materializedPath)
                .isLeaf(true)
                .childCount(0)
                .status("ACTIVE")
                .aggregatedBalance(BigDecimal.ZERO)
                .availableBalance(BigDecimal.ZERO)
                .virtualAccountId(va.getId())
                .build();

            node = hierarchyNodeRepository.save(node);
            log.debug("Created hierarchy node for IHB Current Account: {} at path: {} (program: {})",
                node.getNodeCode(), node.getMaterializedPath(), node.getProgramId());

            // Update VA with hierarchy node ID, path, and PARENT'S PROGRAM
            va.setHierarchyNodeId(node.getId());
            va.setHierarchyPath(materializedPath);
            va.setHierarchyLevel(newLevel - 1); // parentHierarchyNode.getLevelNumber() is 1-indexed, va.hierarchyLevel is 0-indexed
            va.setProgramId(parentHierarchyNode.getProgramId());  // Update VA to use parent's program
            va = virtualAccountRepository.save(va);

            // The parent's child_count and is_leaf are maintained by the
            // trg_hierarchy_nodes_child_count trigger on the insert above.

            log.info("✓ Linked IHB Current Account {} to hierarchy at level {} under {} (program: {})",
                va.getVaNumber(), newLevel, parentHierarchyNode.getNodeCode(), parentHierarchyNode.getProgramId());
        } else if (parentVa != null) {
            // Parent VA found but no hierarchy node - still set parentAccountId for VA hierarchy
            log.info("Parent VA {} found but no hierarchy node. IHB Current Account will be linked via parentAccountId.",
                parentVa.getVaNumber());
        } else {
            log.warn("Could not resolve parent hierarchy for IHB Current Account. " +
                "Ensure Treasury {} has an aggregation VA for currency {} with a hierarchy node.",
                treasury.getEntityCode(), request.getCurrencyCode());
        }

        // 15. Track participant's primary IHB Current Account
        // NOTE: This stores the IHB Current Account ID (not a Settlement VA) on the participant entity.
        // The actual Settlement VA for IHB transactions is Treasury's Settlement VA stored in va.treasuryPoolVaId.
        // This field is used to quickly find the participant's IHB position for reporting.
        // Only set for the FIRST IHB account created (existingCount == 0).
        if (participant.getSettlementVaId() == null && existingCount == 0) {
            participant.setSettlementVaId(va.getId());
            legalEntityRepository.save(participant);
            log.info("Set {} as primary IHB account for entity {}", va.getVaNumber(), participant.getEntityCode());
        }

        // 16. Create Cash Concentration Sweep Rule if sweep is enabled
        // Sweep target is Treasury's Settlement VA (ensured in step 7)
        if (Boolean.TRUE.equals(request.getIhbSweepEnabled()) && treasurySettlementVa != null) {
            createIhbSweepRule(va, participant, treasury, treasurySettlementVa, request);
        } else if (Boolean.TRUE.equals(request.getIhbSweepEnabled())) {
            log.warn("Sweep enabled but no Treasury Settlement VA found for IHB Current Account {} - sweep rule not created",
                va.getVaNumber());
        }

        // =====================================================================
        // 17. MIRROR ACCOUNT MODEL (Option A - 6-leg POBO)
        // Create IHB Settlement VA and IC Receivable VA for proper intercompany accounting
        // =====================================================================

        // Set mirror account type on IHB Current Account
        va.setMirrorAccountType(VirtualAccount.MirrorAccountType.IHB_CURRENT);
        va = virtualAccountRepository.save(va);

        // 17a. Create IHB Settlement VA (sibling to IHB Current Account)
        // This is the per-subsidiary routing point for POBO payments
        VirtualAccount ihbSettlementVa = ensureIhbSettlementVa(va, participant, ihbProgramId, parentHierarchyNode);
        log.info("IHB Settlement VA: {} linked to IHB Current Account {}",
            ihbSettlementVa.getVaNumber(), va.getVaNumber());

        // 17b. Create IC Receivable VA at Treasury (tracks Treasury's claim on subsidiary)
        // This enables proper intercompany accounting and reconciliation
        if (treasurySettlementVa != null) {
            VirtualAccount icReceivableVa = ensureIcReceivableVa(va, participant, treasury, treasurySettlementVa, ihbProgramId);
            log.info("IC Receivable VA: {} at Treasury for subsidiary {}",
                icReceivableVa.getVaNumber(), participant.getEntityCode());

            // 17c. Create IC Payable VA at Treasury (tracks Treasury's obligation to subsidiary)
            // COBO counterpart of 17b — enables the same intercompany accounting for the
            // opposite flow direction (Treasury collecting externally on the subsidiary's behalf)
            VirtualAccount icPayableVa = ensureIcPayableVa(va, participant, treasury, treasurySettlementVa, ihbProgramId);
            log.info("IC Payable VA: {} at Treasury for subsidiary {}",
                icPayableVa.getVaNumber(), participant.getEntityCode());

            // Mark Treasury Settlement VA as TREASURY_SETTLEMENT type if not already set
            if (treasurySettlementVa.getMirrorAccountType() == null ||
                treasurySettlementVa.getMirrorAccountType() == VirtualAccount.MirrorAccountType.NONE) {
                treasurySettlementVa.setMirrorAccountType(VirtualAccount.MirrorAccountType.TREASURY_SETTLEMENT);
                virtualAccountRepository.save(treasurySettlementVa);
            }
        } else {
            log.warn("Treasury Settlement VA not available - IC Receivable VA not created for {}. " +
                "6-leg POBO will fall back to 4-leg flow.", va.getVaNumber());
        }

        log.info("✓ IHB Current Account {} configured for Mirror Account Model (6-leg POBO ready: {})",
            va.getVaNumber(), va.isConfiguredFor6LegPobo());

        return va;
    }

    /**
     * Create Cash Concentration Sweep Rule for IHB Current Account.
     * Links the IHB participant account to Treasury's Settlement VA for automated sweeping.
     *
     * @param va The IHB current account (source)
     * @param participant The participant entity
     * @param treasury The treasury center entity
     * @param treasurySettlementVa The treasury's settlement VA (target)
     * @param request The creation request with sweep configuration
     */
    private void createIhbSweepRule(
            VirtualAccount va,
            LegalEntity participant,
            LegalEntity treasury,
            VirtualAccount treasurySettlementVa,
            VirtualAccountDto.IhbCurrentAccountRequest request) {

        BigDecimal targetBalance = request.getTargetCashBalance() != null ?
            request.getTargetCashBalance() : BigDecimal.ZERO;

        // Generate unique rule reference: IHB-{entityCode}-{currency}-{seq}
        String ruleRef = "IHB-" + participant.getEntityCode() + "-" + va.getCurrencyCode();
        if (ruleRef.length() > 20) {
            // Truncate and add hash for uniqueness
            String hash = String.valueOf(Math.abs(ruleRef.hashCode()) % 10000);
            ruleRef = ruleRef.substring(0, 15) + hash;
        }

        // Check if rule already exists for this account
        boolean ruleExists = sweepRuleRepository.existsByRuleReference(ruleRef);
        if (ruleExists) {
            log.info("Sweep rule {} already exists for IHB account {}", ruleRef, va.getVaNumber());
            return;
        }

        // Create the sweep rule
        SweepRule rule = new SweepRule();
        rule.setRuleReference(ruleRef);
        rule.setRuleName("IHB Sweep: " + participant.getEntityName() + " → Treasury (" + va.getCurrencyCode() + ")");
        rule.setSweepType(targetBalance.compareTo(BigDecimal.ZERO) == 0 ?
            SweepRule.SweepType.ZERO_BALANCE : SweepRule.SweepType.TARGET_BALANCE);
        rule.setTargetAmount(targetBalance);
        rule.setTargetAccountId(treasurySettlementVa.getId());
        rule.setTargetAccountNumber(treasurySettlementVa.getVaNumber());
        rule.setTargetEntityCode(treasury.getEntityCode());
        rule.setFrequency(mapSweepFrequency(request.getIhbSweepFrequency()));
        rule.setPriority(10);  // Default priority
        rule.setStatus(SweepRule.SweepStatus.ACTIVE);
        rule.setCurrencyCode(va.getCurrencyCode());

        // Add source account
        List<SweepRuleSource> sources = new ArrayList<>();
        SweepRuleSource source = new SweepRuleSource();
        source.setRule(rule);
        source.setAccountId(va.getId());
        source.setAccountNumber(va.getVaNumber());
        source.setEntityCode(participant.getEntityCode());
        source.setEntityName(participant.getEntityName());
        source.setCurrencyCode(va.getCurrencyCode());
        sources.add(source);
        rule.setSourceAccounts(sources);

        sweepRuleRepository.save(rule);
        log.info("Created IHB Cash Concentration Sweep Rule: {} for account {} → Treasury {}",
            ruleRef, va.getVaNumber(), treasurySettlementVa.getVaNumber());
    }

    // ========================================================================
    // IHB MIRROR ACCOUNT MODEL (Option A - 6-leg POBO)
    // ========================================================================

    /**
     * Ensure IHB Settlement VA exists for the IHB Current Account.
     *
     * IHB Settlement VA is a sibling to the IHB Current Account that:
     * - Acts as the per-subsidiary routing/settlement point
     * - Receives funds from IHB Current Account for POBO payments
     * - Routes funds to Treasury Settlement VA
     *
     * 6-leg POBO flow:
     * IHB Current Account → IHB Settlement VA → Treasury Settlement VA → IC Receivable → Shadow VA → CBS
     *
     * @param ihbCurrentAccount The IHB Current Account to create settlement VA for
     * @param participant The subsidiary legal entity
     * @param ihbProgramId The IHB program ID
     * @param parentHierarchyNode The parent hierarchy node (same as IHB Current Account's parent)
     * @return The IHB Settlement VA
     */
    @Transactional
    public VirtualAccount ensureIhbSettlementVa(
            VirtualAccount ihbCurrentAccount,
            LegalEntity participant,
            UUID ihbProgramId,
            HierarchyNode parentHierarchyNode) {

        // 1. Check if IHB Settlement VA already exists
        if (ihbCurrentAccount.getIhbSettlementVaId() != null) {
            Optional<VirtualAccount> existing = virtualAccountRepository.findById(ihbCurrentAccount.getIhbSettlementVaId());
            if (existing.isPresent()) {
                log.debug("IHB Settlement VA already exists: {}", existing.get().getVaNumber());
                return existing.get();
            }
        }

        // 2. Look for existing IHB Settlement VA by naming convention
        String expectedVaNumber = "IHBS-" + participant.getEntityCode() + "-" + ihbCurrentAccount.getCurrencyCode();
        Optional<VirtualAccount> existingByNumber = virtualAccountRepository.findByVaNumber(expectedVaNumber);
        if (existingByNumber.isPresent()) {
            // Link it to the IHB Current Account
            ihbCurrentAccount.setIhbSettlementVaId(existingByNumber.get().getId());
            virtualAccountRepository.save(ihbCurrentAccount);
            log.debug("Found existing IHB Settlement VA: {}", existingByNumber.get().getVaNumber());
            return existingByNumber.get();
        }

        // 3. Create new IHB Settlement VA
        log.info("Creating IHB Settlement VA for {} in {}", participant.getEntityCode(), ihbCurrentAccount.getCurrencyCode());

        String currency = ihbCurrentAccount.getCurrencyCode();
        String vaNumber = expectedVaNumber;
        String vaName = "IHB Settlement - " + participant.getEntityName() + " " + currency;

        VirtualAccount settlementVa = VirtualAccount.builder()
            .vaNumber(vaNumber)
            .vaName(vaName)
            .corporateId(participant.getCorporateId())
            .programId(parentHierarchyNode != null ? parentHierarchyNode.getProgramId() : ihbProgramId)
            .physicalAccountId(ihbCurrentAccount.getPhysicalAccountId())
            .currencyCode(currency)
            .accountCategory(VirtualAccount.AccountCategory.TRANSACTION)
            .accountType(VirtualAccount.AccountType.VIRTUAL)
            .mirrorAccountType(VirtualAccount.MirrorAccountType.IHB_SETTLEMENT)
            .owningEntityId(participant.getId())
            .owningEntityCode(participant.getEntityCode())
            .parentAccountId(ihbCurrentAccount.getParentAccountId())  // Same parent as IHB Current Account
            .currentBalance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO)
            .status(VirtualAccount.VaStatus.ACTIVE)
            .externalReference("IHB-SETTLEMENT-" + participant.getEntityCode() + "-" + currency)
            .build();

        settlementVa = virtualAccountRepository.save(settlementVa);

        // 4. Create hierarchy node if parent hierarchy exists
        if (parentHierarchyNode != null) {
            String nodeCode = "IHBS-" + participant.getEntityCode() + "-" + currency;
            String materializedPath = parentHierarchyNode.getMaterializedPath() + "/" + nodeCode;
            int newLevel = parentHierarchyNode.getLevelNumber() + 1;

            HierarchyNode node = HierarchyNode.builder()
                .programId(parentHierarchyNode.getProgramId())
                .parentId(parentHierarchyNode.getId())
                .levelNumber(newLevel)
                .nodeCode(nodeCode)
                .nodeName(vaName)
                .nodeType(HierarchyNodeType.VIRTUAL_ACCOUNT)
                .currencyCode(currency)
                .dimensionValue("IHB_SETTLEMENT")
                .materializedPath(materializedPath)
                .isLeaf(true)
                .childCount(0)
                .status("ACTIVE")
                .aggregatedBalance(BigDecimal.ZERO)
                .availableBalance(BigDecimal.ZERO)
                .virtualAccountId(settlementVa.getId())
                .build();

            node = hierarchyNodeRepository.save(node);

            settlementVa.setHierarchyNodeId(node.getId());
            settlementVa.setHierarchyPath(materializedPath);
            settlementVa.setHierarchyLevel(newLevel - 1); // parentHierarchyNode.getLevelNumber() is 1-indexed, va.hierarchyLevel is 0-indexed
            settlementVa = virtualAccountRepository.save(settlementVa);
        }

        // 5. Link IHB Settlement VA to the IHB Current Account
        ihbCurrentAccount.setIhbSettlementVaId(settlementVa.getId());
        virtualAccountRepository.save(ihbCurrentAccount);

        log.info("Created IHB Settlement VA: {} for {} (linked to IHB Current Account {})",
            settlementVa.getVaNumber(), participant.getEntityCode(), ihbCurrentAccount.getVaNumber());

        return settlementVa;
    }

    /**
     * Ensure IC Receivable VA exists at Treasury for the subsidiary.
     *
     * IC Receivable VA is Treasury's intercompany receivable that:
     * - Tracks Treasury's financial claim on the subsidiary
     * - Mirrors the subsidiary's IHB Current Account balance (opposite sign)
     * - Enables proper intercompany accounting and reconciliation
     *
     * When subsidiary uses POBO: IC Receivable balance INCREASES (Treasury is owed more)
     * When subsidiary funds IHB: IC Receivable balance DECREASES (Treasury is owed less)
     *
     * @param ihbCurrentAccount The subsidiary's IHB Current Account
     * @param participant The subsidiary legal entity
     * @param treasury The treasury center entity
     * @param treasurySettlementVa Treasury's settlement VA (for linking)
     * @param ihbProgramId The IHB program ID
     * @return The IC Receivable VA at Treasury
     */
    @Transactional
    public VirtualAccount ensureIcReceivableVa(
            VirtualAccount ihbCurrentAccount,
            LegalEntity participant,
            LegalEntity treasury,
            VirtualAccount treasurySettlementVa,
            UUID ihbProgramId) {

        // 1. Check if IC Receivable VA already exists
        if (ihbCurrentAccount.getIcReceivableVaId() != null) {
            Optional<VirtualAccount> existing = virtualAccountRepository.findById(ihbCurrentAccount.getIcReceivableVaId());
            if (existing.isPresent()) {
                log.debug("IC Receivable VA already exists: {}", existing.get().getVaNumber());
                return existing.get();
            }
        }

        String currency = ihbCurrentAccount.getCurrencyCode();

        // 2. Look for existing IC Receivable VA by naming convention
        String expectedVaNumber = "ICR-" + treasury.getEntityCode() + "-" + participant.getEntityCode() + "-" + currency;
        Optional<VirtualAccount> existingByNumber = virtualAccountRepository.findByVaNumber(expectedVaNumber);
        if (existingByNumber.isPresent()) {
            // Link it to the IHB Current Account
            ihbCurrentAccount.setIcReceivableVaId(existingByNumber.get().getId());
            virtualAccountRepository.save(ihbCurrentAccount);
            log.debug("Found existing IC Receivable VA: {}", existingByNumber.get().getVaNumber());
            return existingByNumber.get();
        }

        // 3. Create new IC Receivable VA at Treasury
        log.info("Creating IC Receivable VA at {} for subsidiary {} in {}",
            treasury.getEntityCode(), participant.getEntityCode(), currency);

        String vaNumber = expectedVaNumber;
        String vaName = "IC Receivable - " + participant.getEntityName() + " " + currency;

        // Get Treasury's hierarchy node for placement
        HierarchyNode treasuryHierarchyNode = null;
        if (treasurySettlementVa.getHierarchyNodeId() != null) {
            treasuryHierarchyNode = hierarchyNodeRepository.findById(treasurySettlementVa.getHierarchyNodeId()).orElse(null);
        }

        VirtualAccount icReceivableVa = VirtualAccount.builder()
            .vaNumber(vaNumber)
            .vaName(vaName)
            .corporateId(treasury.getCorporateId())
            .programId(treasuryHierarchyNode != null ? treasuryHierarchyNode.getProgramId() : ihbProgramId)
            .physicalAccountId(treasurySettlementVa.getPhysicalAccountId())
            .currencyCode(currency)
            .accountCategory(VirtualAccount.AccountCategory.INTERCOMPANY)
            .accountType(VirtualAccount.AccountType.VIRTUAL)
            .mirrorAccountType(VirtualAccount.MirrorAccountType.IC_RECEIVABLE)
            .mirrorsVaId(ihbCurrentAccount.getId())  // Points to the subsidiary's IHB Current Account
            .owningEntityId(treasury.getId())
            .owningEntityCode(treasury.getEntityCode())
            .parentAccountId(treasurySettlementVa.getId())  // Child of Treasury Settlement VA
            .currentBalance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO)
            .status(VirtualAccount.VaStatus.ACTIVE)
            .externalReference("IC-RECEIVABLE-" + participant.getEntityCode() + "-" + currency)
            .build();

        icReceivableVa = virtualAccountRepository.save(icReceivableVa);

        // 4. Create hierarchy node under Treasury if hierarchy exists
        if (treasuryHierarchyNode != null) {
            String nodeCode = "ICR-" + participant.getEntityCode() + "-" + currency;
            String materializedPath = treasuryHierarchyNode.getMaterializedPath() + "/" + nodeCode;
            int newLevel = treasuryHierarchyNode.getLevelNumber() + 1;

            HierarchyNode node = HierarchyNode.builder()
                .programId(treasuryHierarchyNode.getProgramId())
                .parentId(treasuryHierarchyNode.getId())
                .levelNumber(newLevel)
                .nodeCode(nodeCode)
                .nodeName(vaName)
                .nodeType(HierarchyNodeType.VIRTUAL_ACCOUNT)
                .currencyCode(currency)
                .dimensionValue("IC_RECEIVABLE")
                .materializedPath(materializedPath)
                .isLeaf(true)
                .childCount(0)
                .status("ACTIVE")
                .aggregatedBalance(BigDecimal.ZERO)
                .availableBalance(BigDecimal.ZERO)
                .virtualAccountId(icReceivableVa.getId())
                .build();

            node = hierarchyNodeRepository.save(node);

            icReceivableVa.setHierarchyNodeId(node.getId());
            icReceivableVa.setHierarchyPath(materializedPath);
            icReceivableVa.setHierarchyLevel(newLevel - 1); // treasuryHierarchyNode.getLevelNumber() is 1-indexed, va.hierarchyLevel is 0-indexed
            icReceivableVa = virtualAccountRepository.save(icReceivableVa);
        }

        // 5. Link IC Receivable VA to the IHB Current Account
        ihbCurrentAccount.setIcReceivableVaId(icReceivableVa.getId());
        virtualAccountRepository.save(ihbCurrentAccount);

        log.info("Created IC Receivable VA: {} at Treasury {} for subsidiary {} (linked to IHB Current Account {})",
            icReceivableVa.getVaNumber(), treasury.getEntityCode(), participant.getEntityCode(), ihbCurrentAccount.getVaNumber());

        return icReceivableVa;
    }

    /**
     * Ensure Treasury has an IC Payable VA for a subsidiary (COBO counterpart of
     * {@link #ensureIcReceivableVa}).
     *
     * Mirrors ensureIcReceivableVa exactly, but tracks Treasury's OBLIGATION to the
     * subsidiary (COBO: Treasury collects externally on the subsidiary's behalf) rather
     * than its claim (POBO: Treasury pays externally on the subsidiary's behalf).
     *
     * @param ihbCurrentAccount The subsidiary's IHB Current Account
     * @param participant The subsidiary legal entity
     * @param treasury The treasury center entity
     * @param treasurySettlementVa Treasury's settlement VA (for linking)
     * @param ihbProgramId The IHB program ID
     * @return The IC Payable VA at Treasury
     */
    @Transactional
    public VirtualAccount ensureIcPayableVa(
            VirtualAccount ihbCurrentAccount,
            LegalEntity participant,
            LegalEntity treasury,
            VirtualAccount treasurySettlementVa,
            UUID ihbProgramId) {

        // 1. Check if IC Payable VA already exists
        if (ihbCurrentAccount.getIcPayableVaId() != null) {
            Optional<VirtualAccount> existing = virtualAccountRepository.findById(ihbCurrentAccount.getIcPayableVaId());
            if (existing.isPresent()) {
                log.debug("IC Payable VA already exists: {}", existing.get().getVaNumber());
                return existing.get();
            }
        }

        String currency = ihbCurrentAccount.getCurrencyCode();

        // 2. Look for existing IC Payable VA by naming convention
        String expectedVaNumber = "ICP-" + treasury.getEntityCode() + "-" + participant.getEntityCode() + "-" + currency;
        Optional<VirtualAccount> existingByNumber = virtualAccountRepository.findByVaNumber(expectedVaNumber);
        if (existingByNumber.isPresent()) {
            // Link it to the IHB Current Account
            ihbCurrentAccount.setIcPayableVaId(existingByNumber.get().getId());
            virtualAccountRepository.save(ihbCurrentAccount);
            log.debug("Found existing IC Payable VA: {}", existingByNumber.get().getVaNumber());
            return existingByNumber.get();
        }

        // 3. Create new IC Payable VA at Treasury
        log.info("Creating IC Payable VA at {} for subsidiary {} in {}",
            treasury.getEntityCode(), participant.getEntityCode(), currency);

        String vaNumber = expectedVaNumber;
        String vaName = "IC Payable - " + participant.getEntityName() + " " + currency;

        // Get Treasury's hierarchy node for placement
        HierarchyNode treasuryHierarchyNode = null;
        if (treasurySettlementVa.getHierarchyNodeId() != null) {
            treasuryHierarchyNode = hierarchyNodeRepository.findById(treasurySettlementVa.getHierarchyNodeId()).orElse(null);
        }

        VirtualAccount icPayableVa = VirtualAccount.builder()
            .vaNumber(vaNumber)
            .vaName(vaName)
            .corporateId(treasury.getCorporateId())
            .programId(treasuryHierarchyNode != null ? treasuryHierarchyNode.getProgramId() : ihbProgramId)
            .physicalAccountId(treasurySettlementVa.getPhysicalAccountId())
            .currencyCode(currency)
            .accountCategory(VirtualAccount.AccountCategory.INTERCOMPANY)
            .accountType(VirtualAccount.AccountType.VIRTUAL)
            .mirrorAccountType(VirtualAccount.MirrorAccountType.IC_PAYABLE)
            .mirrorsVaId(ihbCurrentAccount.getId())  // Points to the subsidiary's IHB Current Account
            .owningEntityId(treasury.getId())
            .owningEntityCode(treasury.getEntityCode())
            .parentAccountId(treasurySettlementVa.getId())  // Child of Treasury Settlement VA
            .currentBalance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO)
            .status(VirtualAccount.VaStatus.ACTIVE)
            .externalReference("IC-PAYABLE-" + participant.getEntityCode() + "-" + currency)
            .build();

        icPayableVa = virtualAccountRepository.save(icPayableVa);

        // 4. Create hierarchy node under Treasury if hierarchy exists
        if (treasuryHierarchyNode != null) {
            String nodeCode = "ICP-" + participant.getEntityCode() + "-" + currency;
            String materializedPath = treasuryHierarchyNode.getMaterializedPath() + "/" + nodeCode;
            int newLevel = treasuryHierarchyNode.getLevelNumber() + 1;

            HierarchyNode node = HierarchyNode.builder()
                .programId(treasuryHierarchyNode.getProgramId())
                .parentId(treasuryHierarchyNode.getId())
                .levelNumber(newLevel)
                .nodeCode(nodeCode)
                .nodeName(vaName)
                .nodeType(HierarchyNodeType.VIRTUAL_ACCOUNT)
                .currencyCode(currency)
                .dimensionValue("IC_PAYABLE")
                .materializedPath(materializedPath)
                .isLeaf(true)
                .childCount(0)
                .status("ACTIVE")
                .aggregatedBalance(BigDecimal.ZERO)
                .availableBalance(BigDecimal.ZERO)
                .virtualAccountId(icPayableVa.getId())
                .build();

            node = hierarchyNodeRepository.save(node);

            icPayableVa.setHierarchyNodeId(node.getId());
            icPayableVa.setHierarchyPath(materializedPath);
            icPayableVa.setHierarchyLevel(newLevel - 1); // treasuryHierarchyNode.getLevelNumber() is 1-indexed, va.hierarchyLevel is 0-indexed
            icPayableVa = virtualAccountRepository.save(icPayableVa);
        }

        // 5. Link IC Payable VA to the IHB Current Account
        ihbCurrentAccount.setIcPayableVaId(icPayableVa.getId());
        virtualAccountRepository.save(ihbCurrentAccount);

        log.info("Created IC Payable VA: {} at Treasury {} for subsidiary {} (linked to IHB Current Account {})",
            icPayableVa.getVaNumber(), treasury.getEntityCode(), participant.getEntityCode(), ihbCurrentAccount.getVaNumber());

        return icPayableVa;
    }

    /**
     * Ensure Treasury has a Settlement VA for IHB operations.
     *
     * This is the counterparty account for all IHB transactions:
     * - Deposits: Subsidiary → Treasury Settlement VA
     * - Loans: Treasury Settlement VA → Subsidiary
     * - Interest Settlement: Posted via Treasury Settlement VA
     *
     * Unlike getOrCreateTreasurySettlementVa, this method:
     * 1. Uses the provided parent hierarchy information (not TSETT- orphan)
     * 2. Creates Settlement VA as sibling of other Treasury VAs
     * 3. Properly links to hierarchy for display in Treasury Structure
     *
     * @param treasury The treasury center entity
     * @param currency The currency code
     * @param ihbProgramId The IHB program ID
     * @param parentVa The parent aggregation VA (Treasury's hierarchy root)
     * @param parentHierarchyNode The parent hierarchy node (optional, for linking)
     * @return Treasury's Settlement VA for the specified currency
     */
    private VirtualAccount ensureTreasurySettlementVa(
            LegalEntity treasury,
            String currency,
            UUID ihbProgramId,
            VirtualAccount parentVa,
            HierarchyNode parentHierarchyNode) {

        // 1. Check if treasury already has a Settlement VA for this currency
        if (treasury.getSettlementVaId() != null) {
            Optional<VirtualAccount> configuredVa = virtualAccountRepository.findById(treasury.getSettlementVaId());
            if (configuredVa.isPresent() && configuredVa.get().getCurrencyCode().equals(currency)) {
                log.debug("Treasury {} already has Settlement VA: {}", treasury.getEntityCode(), configuredVa.get().getVaNumber());
                return configuredVa.get();
            }
        }

        // 2. Look for existing Settlement/Transaction VA owned by Treasury for this currency
        List<VirtualAccount> treasuryVas = virtualAccountRepository.findByOwningEntityIdAndCurrencyCode(
            treasury.getId(), currency);

        // Prefer existing Settlement VA (TRANSACTION category, owned by treasury, used for settlement)
        Optional<VirtualAccount> existingSettlement = treasuryVas.stream()
            .filter(va -> va.getAccountCategory() == VirtualAccount.AccountCategory.TRANSACTION
                       && va.getVaName() != null && va.getVaName().contains("Settlement"))
            .findFirst();

        if (existingSettlement.isPresent()) {
            VirtualAccount settlement = existingSettlement.get();
            log.debug("Found existing Treasury Settlement VA: {}", settlement.getVaNumber());
            // Update treasury's settlementVaId if not set
            if (treasury.getSettlementVaId() == null) {
                treasury.setSettlementVaId(settlement.getId());
                legalEntityRepository.save(treasury);
            }
            return settlement;
        }

        // 3. If parentVa IS the Treasury's VA, use it as the settlement target
        if (parentVa != null && treasury.getId().equals(parentVa.getOwningEntityId())) {
            log.debug("Using parent VA {} as Treasury Settlement VA", parentVa.getVaNumber());
            if (treasury.getSettlementVaId() == null) {
                treasury.setSettlementVaId(parentVa.getId());
                legalEntityRepository.save(treasury);
            }
            return parentVa;
        }

        // 4. No existing Settlement VA - create one under Treasury's hierarchy
        log.info("Creating Treasury Settlement VA for {} in {} under hierarchy",
            treasury.getEntityCode(), currency);

        String vaNumber = "SETT-" + treasury.getEntityCode() + "-" + currency;
        String vaName = "Settlement - " + treasury.getEntityName() + " " + currency;

        VirtualAccount settlementVa = VirtualAccount.builder()
            .vaNumber(vaNumber)
            .vaName(vaName)
            .corporateId(treasury.getCorporateId())
            .programId(parentHierarchyNode != null ? parentHierarchyNode.getProgramId() : ihbProgramId)
            .currencyCode(currency)
            .accountCategory(VirtualAccount.AccountCategory.TRANSACTION)  // Real transactions happen
            .accountType(VirtualAccount.AccountType.VIRTUAL)
            .owningEntityId(treasury.getId())
            .owningEntityCode(treasury.getEntityCode())
            .parentAccountId(parentVa != null ? parentVa.getId() : null)  // Sibling of IHB accounts
            .currentBalance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO)
            .status(VirtualAccount.VaStatus.ACTIVE)
            .externalReference("TREASURY-SETTLEMENT-" + currency)
            .build();

        settlementVa = virtualAccountRepository.save(settlementVa);

        // 5. Create hierarchy node if parent hierarchy exists
        if (parentHierarchyNode != null) {
            String nodeCode = "SETT-" + treasury.getEntityCode() + "-" + currency;
            String materializedPath = parentHierarchyNode.getMaterializedPath() + "/" + nodeCode;
            int newLevel = parentHierarchyNode.getLevelNumber() + 1;

            HierarchyNode node = HierarchyNode.builder()
                .programId(parentHierarchyNode.getProgramId())
                .parentId(parentHierarchyNode.getId())
                .levelNumber(newLevel)
                .nodeCode(nodeCode)
                .nodeName(vaName)
                .nodeType(HierarchyNodeType.VIRTUAL_ACCOUNT)
                .currencyCode(currency)
                .dimensionValue("SETTLEMENT")
                .materializedPath(materializedPath)
                .isLeaf(true)
                .childCount(0)
                .status("ACTIVE")
                .aggregatedBalance(BigDecimal.ZERO)
                .availableBalance(BigDecimal.ZERO)
                .virtualAccountId(settlementVa.getId())
                .build();

            node = hierarchyNodeRepository.save(node);

            settlementVa.setHierarchyNodeId(node.getId());
            settlementVa.setHierarchyPath(materializedPath);
            settlementVa.setHierarchyLevel(newLevel - 1); // parentHierarchyNode.getLevelNumber() is 1-indexed, va.hierarchyLevel is 0-indexed
            settlementVa = virtualAccountRepository.save(settlementVa);

            log.info("Created Treasury Settlement VA {} with hierarchy node at {}",
                settlementVa.getVaNumber(), materializedPath);
        }

        // 6. Update treasury's settlementVaId
        treasury.setSettlementVaId(settlementVa.getId());
        legalEntityRepository.save(treasury);

        log.info("Created Treasury Settlement VA: {} for {} (category=TRANSACTION)",
            settlementVa.getVaNumber(), treasury.getEntityCode());

        return settlementVa;
    }

    /**
     * Get or create Treasury Settlement VA for IHB operations.
     *
     * Strategy:
     * 1. If treasury entity has settlementVaId set, use that VA
     * 2. Look for existing IHB participant VA for treasury in this currency
     * 3. Look for any active TRANSACTION VA for treasury in this currency
     * 4. Create new Settlement VA if none found
     *
     * The Settlement VA serves as:
     * - Parent account for IHB Current Accounts (via parentAccountId)
     * - Target for sweep rules (receives swept funds)
     * - Source/target for loan disbursements and repayments
     * - Source/target for deposit placements and withdrawals
     *
     * IMPORTANT: This is a TRANSACTION VA, not AGGREGATION because:
     * - Real fund movements happen (loans, deposits, sweeps)
     * - Balance is real from actual transactions, not computed from children
     * - Only structural-only VAs (ROOT) should be AGGREGATION
     *
     * For multi-level treasury (Regional → Global), regional treasury's Settlement VA
     * should have parentAccountId pointing to global treasury's Settlement VA.
     *
     * @param treasury The treasury center entity
     * @param currency The currency code
     * @param ihbProgramId The IHB program ID for this settlement VA
     * @return Treasury's Settlement VA for the specified currency
     */
    private VirtualAccount getOrCreateTreasurySettlementVa(LegalEntity treasury, String currency, UUID ihbProgramId) {
        // 1. Check if treasury has a configured settlement VA in this currency
        if (treasury.getSettlementVaId() != null) {
            Optional<VirtualAccount> configuredVa = virtualAccountRepository.findById(treasury.getSettlementVaId());
            if (configuredVa.isPresent() && configuredVa.get().getCurrencyCode().equals(currency)) {
                log.debug("Using treasury's configured settlement VA: {}", configuredVa.get().getVaNumber());
                return configuredVa.get();
            }
        }

        // 2. Look for existing IHB-enabled VA for treasury in this currency
        Optional<VirtualAccount> ihbVa = virtualAccountRepository
            .findByOwningEntityIdAndIhbParticipantTrueAndCurrencyCode(treasury.getId(), currency);
        if (ihbVa.isPresent()) {
            log.debug("Using treasury's existing IHB VA: {}", ihbVa.get().getVaNumber());
            return ihbVa.get();
        }

        // 3. Look for any active TRANSACTION VA for treasury in this currency
        List<VirtualAccount> existingVas = virtualAccountRepository
            .findByOwningEntityIdAndCurrencyCodeAndAccountCategory(
                treasury.getId(), currency, VirtualAccount.AccountCategory.TRANSACTION);
        if (!existingVas.isEmpty()) {
            VirtualAccount existing = existingVas.get(0);
            log.debug("Using treasury's existing TRANSACTION VA: {}", existing.getVaNumber());
            // Update entity's settlementVaId if not set
            if (treasury.getSettlementVaId() == null) {
                treasury.setSettlementVaId(existing.getId());
                legalEntityRepository.save(treasury);
            }
            return existing;
        }

        // 4. Create new Settlement VA for treasury
        log.info("Creating Treasury Settlement VA for {} in {}", treasury.getEntityCode(), currency);

        UUID physicalAccountId = resolvePhysicalAccountForTreasury(treasury, currency);

        // Resolve parent VA for multi-level treasury hierarchy
        UUID parentVaId = resolveParentTreasuryVa(treasury, currency);

        // NOTE: Treasury Settlement VA is TRANSACTION category, NOT AGGREGATION because:
        // - Real fund movements happen: loan disbursements, repayments, deposits, withdrawals
        // - It's the target for sweep rules (receives swept funds)
        // - Balance is real, not computed from children
        // - Only structural-only VAs (ROOT) should be AGGREGATION
        VirtualAccount settlementVa = VirtualAccount.builder()
            .vaNumber("TSETT-" + treasury.getEntityCode() + "-" + currency)
            .vaName("Treasury Settlement - " + treasury.getEntityName() + " " + currency)
            .corporateId(treasury.getCorporateId())
            .programId(ihbProgramId)  // Belongs to IHB program
            .physicalAccountId(physicalAccountId)
            .currencyCode(currency)
            .accountCategory(VirtualAccount.AccountCategory.TRANSACTION)  // Real transactions happen
            .accountType(VirtualAccount.AccountType.VIRTUAL)
            .owningEntityId(treasury.getId())
            .owningEntityCode(treasury.getEntityCode())
            .parentAccountId(parentVaId)  // Link to parent treasury if exists
            .currentBalance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO)
            .status(VirtualAccount.VaStatus.ACTIVE)
            .externalReference("TREASURY-SETTLEMENT-" + currency)
            .build();

        VirtualAccount saved = virtualAccountRepository.save(settlementVa);

        // Update treasury's settlementVaId
        treasury.setSettlementVaId(saved.getId());
        legalEntityRepository.save(treasury);

        log.info("Created Treasury Settlement VA: {} for {} (category=AGGREGATION)",
            saved.getVaNumber(), treasury.getEntityCode());

        return saved;
    }

    /**
     * Resolve parent treasury VA for multi-level treasury hierarchy.
     *
     * If this treasury has a parent entity that is also a treasury center,
     * return that parent treasury's settlement VA.
     *
     * Example:
     * - EMEA Treasury (regional) → parent = Global Treasury
     * - EMEA's Settlement VA.parentAccountId = Global's Settlement VA
     *
     * @param treasury The treasury center entity
     * @param currency The currency code
     * @return Parent treasury's settlement VA ID, or null if this is top-level treasury
     */
    private UUID resolveParentTreasuryVa(LegalEntity treasury, String currency) {
        // Check if treasury has a parent entity
        if (treasury.getParentEntityId() == null) {
            return null;  // Top-level treasury
        }

        // Find parent entity
        Optional<LegalEntity> parentOpt = legalEntityRepository.findById(treasury.getParentEntityId());
        if (parentOpt.isEmpty()) {
            return null;
        }

        LegalEntity parent = parentOpt.get();

        // Check if parent is also a treasury center
        if (!parent.canLend()) {
            return null;  // Parent is not a treasury
        }

        // Find parent treasury's settlement VA in this currency
        // Note: Recursive call, but limited by hierarchy depth
        if (parent.getSettlementVaId() != null) {
            Optional<VirtualAccount> parentVa = virtualAccountRepository.findById(parent.getSettlementVaId());
            if (parentVa.isPresent() && parentVa.get().getCurrencyCode().equals(currency)) {
                log.debug("Found parent treasury VA: {} for {}", parentVa.get().getVaNumber(), treasury.getEntityCode());
                return parentVa.get().getId();
            }
        }

        // Look for parent's VA in this currency
        List<VirtualAccount> parentVas = virtualAccountRepository
            .findByOwningEntityIdAndCurrencyCodeAndAccountCategory(
                parent.getId(), currency, VirtualAccount.AccountCategory.AGGREGATION);
        if (!parentVas.isEmpty()) {
            return parentVas.get(0).getId();
        }

        // Also check TRANSACTION category
        parentVas = virtualAccountRepository
            .findByOwningEntityIdAndCurrencyCodeAndAccountCategory(
                parent.getId(), currency, VirtualAccount.AccountCategory.TRANSACTION);
        if (!parentVas.isEmpty()) {
            return parentVas.get(0).getId();
        }

        return null;
    }

    /**
     * Resolve IHB Program for the corporate.
     *
     * IHB is a Program Type - all IHB Current Accounts MUST belong to an IHB program.
     *
     * Logic:
     * 1. If programId is provided, validate it is an active IHB program for this corporate
     * 2. If not provided, auto-resolve the corporate's active IHB program
     * 3. Throw error if no IHB program exists
     *
     * @param corporateId The corporate ID
     * @param requestedProgramId Optional program ID from request
     * @return Resolved IHB program ID (never null)
     */
    private UUID resolveIhbProgram(UUID corporateId, UUID requestedProgramId) {
        if (requestedProgramId != null) {
            // Validate the provided program is an active IHB program for this corporate
            Program program = programRepository.findById(requestedProgramId)
                .orElseThrow(() -> new ResourceNotFoundException(
                    "Program not found: " + requestedProgramId));

            if (!program.getCorporateId().equals(corporateId)) {
                throw new BusinessException("Program " + program.getProgramCode() +
                    " does not belong to this corporate");
            }

            if (program.getProgramType() != Program.ProgramType.IHB) {
                throw new BusinessException("Program " + program.getProgramCode() +
                    " is not an IHB program (type: " + program.getProgramType() + "). " +
                    "IHB Current Accounts must belong to an IHB program.");
            }

            if (program.getStatus() != Program.ProgramStatus.ACTIVE) {
                throw new BusinessException("Program " + program.getProgramCode() +
                    " is not active (status: " + program.getStatus() + ")");
            }

            log.debug("Using provided IHB program: {} ({})", program.getProgramCode(), program.getId());
            return program.getId();
        }

        // Auto-resolve: Find the corporate's active IHB program
        List<Program> ihbPrograms = programRepository.findActiveIhbProgramsByCorporate(corporateId);

        if (ihbPrograms.isEmpty()) {
            throw new BusinessException(
                "No active IHB program found for corporate " + corporateId + ". " +
                "Please create an IHB program first, or provide a programId.");
        }

        // Use the first (oldest) IHB program if multiple exist
        Program ihbProgram = ihbPrograms.get(0);
        if (ihbPrograms.size() > 1) {
            log.warn("Corporate {} has {} IHB programs. Using first: {} ({})",
                corporateId, ihbPrograms.size(), ihbProgram.getProgramCode(), ihbProgram.getId());
        }

        log.info("Auto-resolved IHB program for corporate {}: {} ({})",
            corporateId, ihbProgram.getProgramCode(), ihbProgram.getId());
        return ihbProgram.getId();
    }

    /**
     * Resolve physical account for treasury center.
     */
    private UUID resolvePhysicalAccountForTreasury(LegalEntity treasury, String currency) {
        // Try to find physical account for treasury in this currency
        return physicalAccountRepository
            .findWithFilters(treasury.getCorporateId(), null, currency,
                PhysicalAccount.AccountStatus.ACTIVE, null, null)
            .stream()
            .findFirst()
            .map(PhysicalAccount::getId)
            .orElseThrow(() -> new BusinessException(
                "No active physical account found for corporate " +
                treasury.getCorporateId() + " in currency " + currency));
    }

    /**
     * Get or create Interest Configuration for IHB participant.
     */
    private InterestConfiguration getOrCreateIhbInterestConfig(
            LegalEntity participant,
            LegalEntity treasury,
            VirtualAccountDto.IhbCurrentAccountRequest request) {

        // Check if participant has specific config
        Optional<InterestConfiguration> participantConfig = interestConfigRepository
            .findByTargetIdAndCurrencyCodeAndConfigType(
                participant.getId(),
                request.getCurrencyCode(),
                InterestConfiguration.ConfigType.INTERNAL);

        if (participantConfig.isPresent()) {
            return participantConfig.get();
        }

        // Fall back to Treasury's default IHB config
        if (treasury.getIhbInterestConfigId() != null) {
            Optional<InterestConfiguration> treasuryConfig = interestConfigRepository
                .findById(treasury.getIhbInterestConfigId());
            if (treasuryConfig.isPresent()) {
                return treasuryConfig.get();
            }
        }

        // Create default config
        BigDecimal creditRate = request.getCreditRate() != null
            ? request.getCreditRate() : new BigDecimal("2.50");
        BigDecimal debitRate = request.getDebitRate() != null
            ? request.getDebitRate() : new BigDecimal("5.00");
        BigDecimal penaltyRate = request.getPenaltyRate() != null
            ? request.getPenaltyRate() : new BigDecimal("7.00");

        InterestConfiguration config = InterestConfiguration.builder()
            .corporateId(participant.getCorporateId())
            .configName("IHB Rates - " + participant.getEntityCode())
            .configType(InterestConfiguration.ConfigType.INTERNAL)
            .targetId(participant.getId())
            .targetType(InterestConfiguration.TargetType.LEGAL_ENTITY)
            .currencyCode(request.getCurrencyCode())
            .effectiveCreditRate(creditRate)
            .effectiveDebitRate(debitRate)
            .penaltyRate(penaltyRate)
            .dayCountConvention("ACT/360")
            .compoundingFrequency(InterestConfiguration.CompoundingFrequency.DAILY)
            .postingFrequency(InterestConfiguration.PostingFrequency.MONTHLY)
            .status(InterestConfiguration.ConfigStatus.ACTIVE)
            .effectiveFrom(LocalDate.now())
            .build();

        return interestConfigRepository.save(config);
    }

    /**
     * Generate IHB current account number.
     */
    private String generateIhbCurrentAccountNumber(LegalEntity entity, String currency) {
        long sequence = virtualAccountRepository.countByAccountCategory(
            VirtualAccount.AccountCategory.INTERCOMPANY) + 1;
        return String.format("IHB-%s-%s-%04d", entity.getEntityCode(), currency, sequence);
    }
}
