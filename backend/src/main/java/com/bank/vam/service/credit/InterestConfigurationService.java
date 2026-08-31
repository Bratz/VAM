package com.bank.vam.service.credit;

import com.bank.vam.dto.credit.InterestConfigurationDto;
import com.bank.vam.entity.credit.InterestConfiguration;
import com.bank.vam.entity.credit.InterestConfiguration.*;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.credit.InterestConfigurationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * InterestConfigurationService - Interest rate configuration for corporate users.
 * 
 * UNIFIED ARCHITECTURE v4.2 - CORPORATE USER PERSPECTIVE:
 * =========================================================
 * 
 * This service manages TWO TYPES of interest configurations:
 * 
 * 1. EXTERNAL RATES (Bank-Provided) - READ-ONLY for Corporate
 *    --------------------------------------------------------
 *    - Source: Core Banking System (CBS) via BANCS sync
 *    - Represents: What bank charges/pays on overdraft/credit facilities
 *    - Corporate CANNOT modify - set by bank
 *    - Examples: 
 *      - Credit Rate: What bank pays on positive balances
 *      - Debit Rate: What bank charges on overdraft (EIBOR + spread)
 * 
 * 2. INTERNAL RATES (Treasury Transfer Pricing) - Corporate CAN Manage
 *    -----------------------------------------------------------------
 *    - Source: Corporate Treasury/CFO
 *    - Represents: Internal transfer pricing for intercompany transactions
 *    - Corporate CAN create, update, delete
 *    - Used for: IHB loans, intercompany credit, internal settlements
 *    - SPREAD over external rates (value capture for treasury)
 * 
 * BUSINESS RULES:
 * ===============
 * 
 * Rule 1: External rates are ceiling for internal credit rates
 *   - Internal credit rate ≤ External credit rate
 *   - Treasury captures spread: External - Internal
 * 
 * Rule 2: External rates are floor for internal debit rates
 *   - Internal debit rate ≥ External debit rate
 *   - Treasury charges additional spread: Internal - External
 * 
 * Rule 3: Effective Rate Calculation
 *   - Credit: effectiveRate = baseRate + creditSpread
 *   - Debit: effectiveRate = baseRate + debitSpread
 * 
 * EXAMPLE:
 * ========
 * External (Bank): EIBOR 3M (5.0%) + Spread (1.5%) = 6.5% debit rate
 * Internal (Treasury): 6.5% + Internal Spread (0.5%) = 7.0% charged to subsidiary
 * Treasury Margin: 0.5%
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterestConfigurationService {

    private final InterestConfigurationRepository repository;

    // ========================================================================
    // EXTERNAL RATES (READ-ONLY FOR CORPORATE)
    // ========================================================================

    /**
     * Get external (bank) interest configurations for corporate.
     * These are READ-ONLY for corporate users.
     */
    @Transactional(readOnly = true)
    public List<InterestConfiguration> getExternalConfigsByCorporate(UUID corporateId) {
        return repository.findByCorporateIdAndConfigType(corporateId, ConfigType.EXTERNAL);
    }

    /**
     * Get active external configs for corporate.
     */
    @Transactional(readOnly = true)
    public List<InterestConfiguration> getActiveExternalConfigs(UUID corporateId) {
        return repository.findByCorporateIdAndConfigType(corporateId, ConfigType.EXTERNAL)
            .stream()
            .filter(c -> c.isActive() && c.isCurrentlyValid())
            .toList();
    }

    /**
     * Get external config for a specific target.
     */
    @Transactional(readOnly = true)
    public Optional<InterestConfiguration> getExternalConfigForTarget(UUID targetId, String currency) {
        return repository.findByTargetIdAndConfigType(targetId, ConfigType.EXTERNAL)
            .stream()
            .filter(c -> c.isActive() && c.isCurrentlyValid())
            .filter(c -> currency == null || currency.equals(c.getCurrencyCode()))
            .findFirst();
    }

    /**
     * Sync external interest configuration from CBS.
     * This is called by CBS sync job, NOT by corporate users.
     */
    @Transactional
    public InterestConfiguration syncExternalConfig(UUID corporateId, UUID targetId, 
                                                      TargetType targetType, String currency,
                                                      BigDecimal creditRate, BigDecimal debitRate,
                                                      String baseRateType, BigDecimal baseRateValue,
                                                      String externalReference) {
        log.info("Syncing external interest config for target {} - credit: {}, debit: {}", 
                 targetId, creditRate, debitRate);

        // Check for existing
        Optional<InterestConfiguration> existing = repository
            .findByTargetIdAndConfigType(targetId, ConfigType.EXTERNAL)
            .stream()
            .filter(c -> c.isActive())
            .filter(c -> currency.equals(c.getCurrencyCode()))
            .findFirst();

        if (existing.isPresent()) {
            // Update existing
            InterestConfiguration config = existing.get();
            config.setCreditBaseRate(baseRateValue);
            config.setCreditSpread(creditRate.subtract(baseRateValue));
            config.setDebitBaseRate(baseRateValue);
            config.setDebitSpread(debitRate.subtract(baseRateValue));
            config.setEffectiveCreditRate(creditRate);
            config.setEffectiveDebitRate(debitRate);
            config.setLastSyncAt(LocalDateTime.now());
            
            // Recalculate internal configs that depend on this
            recalculateDependentInternalConfigs(targetId, currency, creditRate, debitRate);
            
            return repository.save(config);
        }

        // Create new
        InterestConfiguration config = InterestConfiguration.builder()
            .corporateId(corporateId)
            .configName("External Rate - " + currency)
            .externalReference(externalReference)
            .configType(ConfigType.EXTERNAL)
            .targetId(targetId)
            .targetType(targetType)
            .currencyCode(currency)
            .creditBaseRateType(baseRateType)
            .creditBaseRate(baseRateValue)
            .creditSpread(creditRate.subtract(baseRateValue))
            .effectiveCreditRate(creditRate)
            .debitBaseRateType(baseRateType)
            .debitBaseRate(baseRateValue)
            .debitSpread(debitRate.subtract(baseRateValue))
            .effectiveDebitRate(debitRate)
            .dayCountConvention("ACT/360")
            .compoundingFrequency(CompoundingFrequency.DAILY)
            .calculationFrequency(CalculationFrequency.DAILY)
            .postingFrequency(PostingFrequency.MONTHLY)
            .status(ConfigStatus.ACTIVE)
            .effectiveFrom(LocalDate.now())
            .sourceSystem("CBS")
            .lastSyncAt(LocalDateTime.now())
            .build();

        return repository.save(config);
    }

    /**
     * Recalculate internal configs when external rates change.
     */
    private void recalculateDependentInternalConfigs(UUID targetId, String currency,
                                                       BigDecimal newExternalCreditRate,
                                                       BigDecimal newExternalDebitRate) {
        repository.findByTargetIdAndConfigType(targetId, ConfigType.INTERNAL)
            .stream()
            .filter(c -> c.isActive() && currency.equals(c.getCurrencyCode()))
            .forEach(config -> {
                // Recalculate effective rates using existing spreads
                BigDecimal creditSpread = config.getCreditSpread() != null ? 
                    config.getCreditSpread() : BigDecimal.ZERO;
                BigDecimal debitSpread = config.getDebitSpread() != null ? 
                    config.getDebitSpread() : BigDecimal.ZERO;
                
                config.setEffectiveCreditRate(newExternalCreditRate.add(creditSpread));
                config.setEffectiveDebitRate(newExternalDebitRate.add(debitSpread));
                config.setCreditBaseRate(newExternalCreditRate);
                config.setDebitBaseRate(newExternalDebitRate);
                
                repository.save(config);
                log.info("Recalculated internal config {} based on new external rates", config.getId());
            });
    }

    // ========================================================================
    // INTERNAL RATES (CORPORATE CAN MANAGE)
    // ========================================================================

    /**
     * Get internal (treasury) interest configurations for corporate.
     */
    @Transactional(readOnly = true)
    public List<InterestConfiguration> getInternalConfigsByCorporate(UUID corporateId) {
        return repository.findByCorporateIdAndConfigType(corporateId, ConfigType.INTERNAL);
    }

    /**
     * Get active internal configs for corporate.
     */
    @Transactional(readOnly = true)
    public List<InterestConfiguration> getActiveInternalConfigs(UUID corporateId) {
        return repository.findByCorporateIdAndConfigType(corporateId, ConfigType.INTERNAL)
            .stream()
            .filter(c -> c.isActive() && c.isCurrentlyValid())
            .toList();
    }

    /**
     * Get internal config for a specific target.
     */
    @Transactional(readOnly = true)
    public Optional<InterestConfiguration> getInternalConfigForTarget(UUID targetId, String currency) {
        return repository.findByTargetIdAndConfigType(targetId, ConfigType.INTERNAL)
            .stream()
            .filter(c -> c.isActive() && c.isCurrentlyValid())
            .filter(c -> currency == null || currency.equals(c.getCurrencyCode()))
            .findFirst();
    }

    /**
     * Create internal (treasury) interest configuration.
     * 
     * @param corporateId Corporate ID
     * @param targetId Target (entity, VA, or null for currency default)
     * @param targetType Type of target
     * @param currency Currency code
     * @param configName Configuration name
     * @param baseRateType Base rate type (EIBOR, SOFR, etc.)
     * @param creditSpread Spread to add to external credit rate (typically negative - treasury pays less)
     * @param debitSpread Spread to add to external debit rate (typically positive - treasury charges more)
     */
    @Transactional
    public InterestConfiguration createInternalConfig(UUID corporateId, UUID targetId,
                                                        TargetType targetType, String currency,
                                                        String configName, String baseRateType,
                                                        BigDecimal creditSpread, BigDecimal debitSpread) {
        log.info("Creating internal interest config: {} for target {} - credit spread: {}, debit spread: {}", 
                 configName, targetId, creditSpread, debitSpread);

        // Check for existing active config
        if (targetId != null) {
            boolean exists = repository.findByTargetIdAndConfigType(targetId, ConfigType.INTERNAL)
                .stream()
                .anyMatch(c -> c.isActive() && currency.equals(c.getCurrencyCode()));
            if (exists) {
                throw new BusinessException("Active internal interest configuration already exists for this target/currency");
            }
        }

        // Get external config for validation (if exists)
        Optional<InterestConfiguration> externalOpt = targetId != null ? 
            getExternalConfigForTarget(targetId, currency) : Optional.empty();

        // Calculate effective rates
        BigDecimal effectiveCreditRate;
        BigDecimal effectiveDebitRate;
        BigDecimal baseRateValue = BigDecimal.ZERO;

        if (externalOpt.isPresent()) {
            InterestConfiguration ext = externalOpt.get();
            baseRateValue = ext.getCreditBaseRate() != null ? ext.getCreditBaseRate() : BigDecimal.ZERO;
            
            // Internal credit rate = External credit rate + creditSpread
            // creditSpread should typically be ≤ 0 (treasury pays less to subsidiaries)
            effectiveCreditRate = ext.getEffectiveCreditRate().add(creditSpread);
            if (effectiveCreditRate.compareTo(ext.getEffectiveCreditRate()) > 0) {
                log.warn("Internal credit rate {} exceeds external rate {}. " +
                        "Treasury will pay more to subsidiaries than bank pays.", 
                        effectiveCreditRate, ext.getEffectiveCreditRate());
            }

            // Internal debit rate = External debit rate + debitSpread
            // debitSpread should typically be ≥ 0 (treasury charges more to subsidiaries)
            effectiveDebitRate = ext.getEffectiveDebitRate().add(debitSpread);
            if (effectiveDebitRate.compareTo(ext.getEffectiveDebitRate()) < 0) {
                log.warn("Internal debit rate {} is less than external rate {}. " +
                        "Treasury will charge less to subsidiaries than bank charges.", 
                        effectiveDebitRate, ext.getEffectiveDebitRate());
            }
        } else {
            // No external config - use spreads as absolute rates
            log.info("No external config found. Using spreads as absolute rates.");
            effectiveCreditRate = creditSpread.abs(); // Use absolute value for rates
            effectiveDebitRate = debitSpread.abs();
        }

        InterestConfiguration config = InterestConfiguration.builder()
            .corporateId(corporateId)
            .configName(configName != null ? configName : "Internal Rate - " + currency)
            .configType(ConfigType.INTERNAL)
            .targetId(targetId)
            .targetType(targetType)
            .currencyCode(currency)
            .creditBaseRateType(baseRateType)
            .creditBaseRate(baseRateValue)
            .creditSpread(creditSpread)
            .effectiveCreditRate(effectiveCreditRate)
            .debitBaseRateType(baseRateType)
            .debitBaseRate(baseRateValue)
            .debitSpread(debitSpread)
            .effectiveDebitRate(effectiveDebitRate)
            .dayCountConvention("ACT/360")
            .compoundingFrequency(CompoundingFrequency.DAILY)
            .calculationFrequency(CalculationFrequency.DAILY)
            .postingFrequency(PostingFrequency.MONTHLY)
            .status(ConfigStatus.ACTIVE)
            .effectiveFrom(LocalDate.now())
            .build();

        config = repository.save(config);
        log.info("Created internal interest config {} for target {}", config.getId(), targetId);
        return config;
    }

    /**
     * Create corporate-wide default internal config.
     * Used when no entity-specific config exists.
     */
    @Transactional
    public InterestConfiguration createCorporateDefaultConfig(UUID corporateId, String currency,
                                                                String configName, String baseRateType,
                                                                BigDecimal creditSpread, BigDecimal debitSpread) {
        return createInternalConfig(corporateId, corporateId, TargetType.CORPORATE, 
                                     currency, configName, baseRateType, creditSpread, debitSpread);
    }

    /**
     * Create currency-specific default internal config.
     */
    @Transactional
    public InterestConfiguration createCurrencyDefaultConfig(UUID corporateId, String currency,
                                                               String configName, String baseRateType,
                                                               BigDecimal creditSpread, BigDecimal debitSpread) {
        // For currency defaults, targetId is null
        return createInternalConfig(corporateId, null, TargetType.CURRENCY, 
                                     currency, configName, baseRateType, creditSpread, debitSpread);
    }

    /**
     * Update internal spreads.
     * This is the primary operation for corporate users.
     */
    @Transactional
    public InterestConfiguration updateInternalSpreads(UUID configId, 
                                                         BigDecimal creditSpread, 
                                                         BigDecimal debitSpread) {
        InterestConfiguration config = getById(configId);
        
        if (!config.isInternal()) {
            throw new BusinessException("Cannot modify external (bank) interest rates. Contact bank.");
        }

        // Get external config for recalculation
        Optional<InterestConfiguration> externalOpt = config.getTargetId() != null ?
            getExternalConfigForTarget(config.getTargetId(), config.getCurrencyCode()) :
            Optional.empty();

        config.setCreditSpread(creditSpread);
        config.setDebitSpread(debitSpread);

        if (externalOpt.isPresent()) {
            InterestConfiguration ext = externalOpt.get();
            config.setEffectiveCreditRate(ext.getEffectiveCreditRate().add(creditSpread));
            config.setEffectiveDebitRate(ext.getEffectiveDebitRate().add(debitSpread));
        } else {
            // Spreads as absolute rates
            config.setEffectiveCreditRate(creditSpread.abs());
            config.setEffectiveDebitRate(debitSpread.abs());
        }

        log.info("Updated internal spreads for config {} - credit: {}, debit: {}", 
                 configId, creditSpread, debitSpread);
        return repository.save(config);
    }

    /**
     * Update calculation/posting frequencies.
     */
    @Transactional
    public InterestConfiguration updateFrequencies(UUID configId,
                                                     CalculationFrequency calcFreq,
                                                     PostingFrequency postFreq,
                                                     CompoundingFrequency compFreq) {
        InterestConfiguration config = getById(configId);
        
        if (!config.isInternal()) {
            throw new BusinessException("Cannot modify external (bank) configuration");
        }

        if (calcFreq != null) {
            config.setCalculationFrequency(calcFreq);
        }
        if (postFreq != null) {
            config.setPostingFrequency(postFreq);
        }
        if (compFreq != null) {
            config.setCompoundingFrequency(compFreq);
        }

        return repository.save(config);
    }

    /**
     * Update day count convention.
     */
    @Transactional
    public InterestConfiguration updateDayCountConvention(UUID configId, String dayCountConvention) {
        InterestConfiguration config = getById(configId);
        
        if (!config.isInternal()) {
            throw new BusinessException("Cannot modify external (bank) configuration");
        }

        // Validate convention
        if (!List.of("ACT/360", "ACT/365", "30/360", "ACT/ACT").contains(dayCountConvention)) {
            throw new BusinessException("Invalid day count convention: " + dayCountConvention);
        }

        config.setDayCountConvention(dayCountConvention);
        return repository.save(config);
    }

    /**
     * Set minimum balance for credit interest.
     */
    @Transactional
    public InterestConfiguration updateCreditMinBalance(UUID configId, BigDecimal minBalance) {
        InterestConfiguration config = getById(configId);
        
        if (!config.isInternal()) {
            throw new BusinessException("Cannot modify external (bank) configuration");
        }

        config.setCreditMinBalance(minBalance);
        return repository.save(config);
    }

    /**
     * Set penalty rate for overdue.
     */
    @Transactional
    public InterestConfiguration updatePenaltyRate(UUID configId, BigDecimal penaltyRate) {
        InterestConfiguration config = getById(configId);
        
        if (!config.isInternal()) {
            throw new BusinessException("Cannot modify external (bank) configuration");
        }

        config.setPenaltyRate(penaltyRate);
        return repository.save(config);
    }

    /**
     * Delete (expire) internal configuration.
     */
    @Transactional
    public void deleteInternalConfig(UUID configId, String reason) {
        InterestConfiguration config = getById(configId);
        
        if (!config.isInternal()) {
            throw new BusinessException("Cannot delete external (bank) configuration");
        }

        config.setStatus(ConfigStatus.EXPIRED);
        config.setEffectiveTo(LocalDate.now());
        // Note: reason logged but not persisted (entity has no notes field)
        repository.save(config);
        
        log.info("Deleted internal config {}: {}", configId, reason);
    }

    // ========================================================================
    // EFFECTIVE CONFIG RESOLUTION
    // ========================================================================

    /**
     * Find effective interest config for a target.
     * Priority: Target-specific > Currency default > Corporate default
     */
    @Transactional(readOnly = true)
    public Optional<InterestConfiguration> findEffectiveConfig(UUID corporateId, UUID targetId, 
                                                                 String currencyCode, ConfigType configType) {
        LocalDate today = LocalDate.now();
        
        // 1. Try target-specific config
        Optional<InterestConfiguration> targetConfig = repository.findActiveByTargetAndType(
            targetId, configType, today);
        if (targetConfig.isPresent()) {
            return targetConfig;
        }
        
        // 2. Try currency default
        Optional<InterestConfiguration> currencyConfig = repository.findActiveByCurrencyDefault(
            corporateId, currencyCode, configType, today);
        if (currencyConfig.isPresent()) {
            return currencyConfig;
        }
        
        // 3. Try corporate default
        return repository.findActiveCorporateDefault(corporateId, configType, today);
    }

    /**
     * Get effective internal config for interest calculation.
     */
    @Transactional(readOnly = true)
    public Optional<InterestConfiguration> getEffectiveInternalConfig(UUID corporateId, 
                                                                        UUID targetId, 
                                                                        String currency) {
        return findEffectiveConfig(corporateId, targetId, currency, ConfigType.INTERNAL);
    }

    /**
     * Get effective external config for reference.
     */
    @Transactional(readOnly = true)
    public Optional<InterestConfiguration> getEffectiveExternalConfig(UUID corporateId, 
                                                                        UUID targetId, 
                                                                        String currency) {
        return findEffectiveConfig(corporateId, targetId, currency, ConfigType.EXTERNAL);
    }

    // ========================================================================
    // INTEREST CALCULATION
    // ========================================================================

    /**
     * Calculate daily interest for a balance.
     */
    public BigDecimal calculateDailyInterest(InterestConfiguration config, BigDecimal balance) {
        if (balance == null || balance.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal rate;
        if (balance.compareTo(BigDecimal.ZERO) > 0) {
            // Credit interest (positive balance)
            if (config.getCreditMinBalance() != null && 
                balance.compareTo(config.getCreditMinBalance()) < 0) {
                return BigDecimal.ZERO; // Below minimum
            }
            rate = config.getEffectiveCreditRate();
        } else {
            // Debit interest (negative balance)
            rate = config.getEffectiveDebitRate();
            // Add penalty rate if applicable
            if (config.getPenaltyRate() != null) {
                rate = rate.add(config.getPenaltyRate());
            }
        }

        if (rate == null || rate.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        // Daily interest = Balance × Rate / 100 / DayBasis
        int dayBasis = config.getDayCountBasis();
        BigDecimal annualInterest = balance.abs().multiply(rate)
            .divide(new BigDecimal(100), 10, RoundingMode.HALF_UP);
        BigDecimal dailyInterest = annualInterest
            .divide(new BigDecimal(dayBasis), 10, RoundingMode.HALF_UP);

        return dailyInterest.setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Calculate interest for a period.
     */
    public BigDecimal calculateInterestForPeriod(InterestConfiguration config, 
                                                   BigDecimal avgBalance, int days) {
        if (days <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal dailyInterest = calculateDailyInterest(config, avgBalance);
        return dailyInterest.multiply(new BigDecimal(days)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculate treasury spread/margin.
     * This is the difference between internal and external rates.
     */
    @Transactional(readOnly = true)
    public SpreadAnalysis calculateTreasurySpread(UUID corporateId, UUID targetId, String currency) {
        Optional<InterestConfiguration> externalOpt = getEffectiveExternalConfig(corporateId, targetId, currency);
        Optional<InterestConfiguration> internalOpt = getEffectiveInternalConfig(corporateId, targetId, currency);

        SpreadAnalysis analysis = new SpreadAnalysis();
        analysis.setTargetId(targetId);
        analysis.setCurrency(currency);

        if (externalOpt.isPresent()) {
            InterestConfiguration ext = externalOpt.get();
            analysis.setExternalCreditRate(ext.getEffectiveCreditRate());
            analysis.setExternalDebitRate(ext.getEffectiveDebitRate());
        }

        if (internalOpt.isPresent()) {
            InterestConfiguration intl = internalOpt.get();
            analysis.setInternalCreditRate(intl.getEffectiveCreditRate());
            analysis.setInternalDebitRate(intl.getEffectiveDebitRate());
        }

        // Calculate spreads (treasury margin)
        if (analysis.getExternalCreditRate() != null && analysis.getInternalCreditRate() != null) {
            // Credit spread: External - Internal (treasury pays less to subsidiaries)
            analysis.setCreditSpread(analysis.getExternalCreditRate()
                .subtract(analysis.getInternalCreditRate()));
        }

        if (analysis.getExternalDebitRate() != null && analysis.getInternalDebitRate() != null) {
            // Debit spread: Internal - External (treasury charges more to subsidiaries)
            analysis.setDebitSpread(analysis.getInternalDebitRate()
                .subtract(analysis.getExternalDebitRate()));
        }

        return analysis;
    }

    // ========================================================================
    // STANDARD CRUD
    // ========================================================================

    @Transactional(readOnly = true)
    public InterestConfiguration getById(UUID id) {
        return repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Interest configuration not found: " + id));
    }

    @Transactional(readOnly = true)
    public Page<InterestConfiguration> findByCorporate(UUID corporateId, Pageable pageable) {
        return repository.findByCorporateId(corporateId, pageable);
    }

    @Transactional(readOnly = true)
    public List<InterestConfiguration> findByTarget(UUID targetId) {
        return repository.findByTargetId(targetId);
    }

    @Transactional(readOnly = true)
    public List<InterestConfiguration> findActiveByCorporate(UUID corporateId) {
        return repository.findByCorporateIdAndStatus(corporateId, ConfigStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public List<InterestConfiguration> findByType(UUID corporateId, ConfigType configType) {
        return repository.findByCorporateIdAndConfigType(corporateId, configType);
    }

    @Transactional(readOnly = true)
    public List<InterestConfiguration> findByCurrency(UUID corporateId, String currencyCode) {
        return repository.findByCorporateIdAndCurrencyCode(corporateId, currencyCode);
    }

    @Transactional
    public InterestConfiguration create(InterestConfigurationDto.CreateRequest request) {
        // Delegate to appropriate method based on type
        if (request.getConfigType() == ConfigType.EXTERNAL) {
            throw new BusinessException("External configurations can only be synced from CBS");
        }

        return createInternalConfig(
            request.getCorporateId(),
            request.getTargetId(),
            request.getTargetType(),
            request.getCurrencyCode(),
            request.getConfigName(),
            request.getCreditBaseRateType(),
            request.getCreditSpread() != null ? request.getCreditSpread() : BigDecimal.ZERO,
            request.getDebitSpread() != null ? request.getDebitSpread() : BigDecimal.ZERO
        );
    }

    @Transactional
    public InterestConfiguration update(UUID id, InterestConfigurationDto.UpdateRequest request) {
        InterestConfiguration config = getById(id);
        
        if (!config.isInternal()) {
            throw new BusinessException("Cannot modify external (bank) configuration");
        }

        if (request.getConfigName() != null) {
            config.setConfigName(request.getConfigName());
        }
        if (request.getCreditSpread() != null) {
            config.setCreditSpread(request.getCreditSpread());
        }
        if (request.getDebitSpread() != null) {
            config.setDebitSpread(request.getDebitSpread());
        }
        if (request.getCreditMinBalance() != null) {
            config.setCreditMinBalance(request.getCreditMinBalance());
        }
        if (request.getPenaltyRate() != null) {
            config.setPenaltyRate(request.getPenaltyRate());
        }
        if (request.getDayCountConvention() != null) {
            config.setDayCountConvention(request.getDayCountConvention());
        }
        if (request.getCompoundingFrequency() != null) {
            config.setCompoundingFrequency(request.getCompoundingFrequency());
        }
        if (request.getPostingFrequency() != null) {
            config.setPostingFrequency(request.getPostingFrequency());
        }
        if (request.getEffectiveTo() != null) {
            config.setEffectiveTo(request.getEffectiveTo());
        }

        // Recalculate effective rates
        Optional<InterestConfiguration> externalOpt = config.getTargetId() != null ?
            getExternalConfigForTarget(config.getTargetId(), config.getCurrencyCode()) :
            Optional.empty();

        if (externalOpt.isPresent()) {
            InterestConfiguration ext = externalOpt.get();
            config.setEffectiveCreditRate(ext.getEffectiveCreditRate().add(config.getCreditSpread()));
            config.setEffectiveDebitRate(ext.getEffectiveDebitRate().add(config.getDebitSpread()));
        }

        return repository.save(config);
    }

    @Transactional
    public InterestConfiguration updateBaseRates(UUID id, BigDecimal creditBaseRate, BigDecimal debitBaseRate) {
        InterestConfiguration config = getById(id);
        
        // Only allow for external (CBS sync) or when no external config exists
        if (config.isInternal()) {
            throw new BusinessException("Base rates for internal configs are derived from external. Update spreads instead.");
        }
        
        config.setCreditBaseRate(creditBaseRate);
        config.setDebitBaseRate(debitBaseRate);
        config.calculateEffectiveRates();
        config.setLastSyncAt(LocalDateTime.now());

        return repository.save(config);
    }

    @Transactional
    public InterestConfiguration updateSpreads(UUID id, BigDecimal creditSpread, BigDecimal debitSpread) {
        return updateInternalSpreads(id, creditSpread, debitSpread);
    }

    @Transactional
    public InterestConfiguration activate(UUID id) {
        InterestConfiguration config = getById(id);
        config.setStatus(ConfigStatus.ACTIVE);
        return repository.save(config);
    }

    @Transactional
    public InterestConfiguration suspend(UUID id) {
        InterestConfiguration config = getById(id);
        config.setStatus(ConfigStatus.SUSPENDED);
        return repository.save(config);
    }

    @Transactional
    public InterestConfiguration expire(UUID id) {
        InterestConfiguration config = getById(id);
        config.setStatus(ConfigStatus.EXPIRED);
        config.setEffectiveTo(LocalDate.now());
        return repository.save(config);
    }

    @Transactional
    public void delete(UUID id) {
        InterestConfiguration config = getById(id);
        if (config.isExternal()) {
            throw new BusinessException("Cannot delete external (bank) configuration");
        }
        if (config.isActive()) {
            throw new BusinessException("Cannot delete active configuration. Expire it first.");
        }
        repository.delete(config);
        log.info("Deleted interest configuration: id={}", id);
    }

    @Transactional
    public InterestConfiguration createExternal(UUID corporateId, UUID targetId, TargetType targetType,
                                                  String currency, BigDecimal creditRate, BigDecimal debitRate) {
        throw new BusinessException("External configurations can only be synced from CBS");
    }

    @Transactional
    public InterestConfiguration createInternal(UUID corporateId, UUID targetId, TargetType targetType,
                                                  String currency, BigDecimal creditRate, BigDecimal debitRate,
                                                  String baseRateType) {
        return createInternalConfig(corporateId, targetId, targetType, currency,
                                     null, baseRateType, creditRate, debitRate);
    }

    // ========================================================================
    // STATISTICS
    // ========================================================================

    @Transactional(readOnly = true)
    public InterestConfigurationDto.Statistics getStatistics(UUID corporateId) {
        List<InterestConfiguration> all = repository.findByCorporateIdAndStatus(corporateId, ConfigStatus.ACTIVE);
        
        long externalCount = all.stream().filter(c -> c.getConfigType() == ConfigType.EXTERNAL).count();
        long internalCount = all.stream().filter(c -> c.getConfigType() == ConfigType.INTERNAL).count();
        
        // Average rates for internal configs only (what corporate manages)
        List<InterestConfiguration> internalConfigs = all.stream()
            .filter(c -> c.getConfigType() == ConfigType.INTERNAL)
            .toList();
        
        BigDecimal avgCreditRate = internalConfigs.stream()
            .map(InterestConfiguration::getEffectiveCreditRate)
            .filter(r -> r != null)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(new BigDecimal(Math.max(1, internalConfigs.size())), 4, RoundingMode.HALF_UP);
        
        BigDecimal avgDebitRate = internalConfigs.stream()
            .map(InterestConfiguration::getEffectiveDebitRate)
            .filter(r -> r != null)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(new BigDecimal(Math.max(1, internalConfigs.size())), 4, RoundingMode.HALF_UP);

        return InterestConfigurationDto.Statistics.builder()
            .totalConfigs(all.size())
            .externalConfigs((int) externalCount)
            .internalConfigs((int) internalCount)
            .avgCreditRate(avgCreditRate)
            .avgDebitRate(avgDebitRate)
            .build();
    }

    // ========================================================================
    // HELPER CLASSES
    // ========================================================================

    @lombok.Data
    public static class SpreadAnalysis {
        private UUID targetId;
        private String currency;
        private BigDecimal externalCreditRate;
        private BigDecimal externalDebitRate;
        private BigDecimal internalCreditRate;
        private BigDecimal internalDebitRate;
        private BigDecimal creditSpread; // Treasury margin on credit
        private BigDecimal debitSpread;  // Treasury margin on debit
    }
}