package com.bank.vam.service.credit;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.credit.InterestConfiguration;
import com.bank.vam.entity.credit.InterestConfiguration.ConfigType;
import com.bank.vam.entity.credit.InterestConfiguration.TargetType;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.credit.InterestConfigurationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * InterestConfigAttachmentService - Manages attachment of interest configurations to VAs.
 * 
 * PHASE 1: Interest Config Attachment Implementation
 * ===================================================
 * 
 * This service handles:
 * 1. Auto-attachment of interest config when VA is created
 * 2. Manual attachment/detachment of interest configs
 * 3. Syncing effective rates from config to VA (denormalized fields)
 * 4. Multi-currency resolution (config per currency per target)
 * 5. Nightly batch sync job
 * 
 * MULTI-CURRENCY CONSIDERATIONS:
 * ==============================
 * - Each VA has a single currencyCode
 * - Interest configs can be:
 *   a) VA-specific (targetId = VA.id, targetType = VIRTUAL_ACCOUNT)
 *   b) Entity-specific (targetId = entityId, targetType = LEGAL_ENTITY)
 *   c) Currency default (targetType = CURRENCY, currencyCode = X)
 *   d) Corporate default (targetType = CORPORATE)
 * 
 * - Resolution priority:
 *   1. VA-specific for matching currency
 *   2. Entity-specific for matching currency (via owningEntityId)
 *   3. Currency default for VA's currency
 *   4. Corporate default (any currency)
 * 
 * ATTACHMENT MODEL:
 * =================
 * VirtualAccount has two interest config references:
 * - external_interest_config_id → Bank rates (ConfigType = EXTERNAL)
 * - internal_interest_config_id → Treasury rates (ConfigType = INTERNAL)
 * 
 * Plus denormalized effective rates:
 * - effective_credit_rate → For positive balances
 * - effective_debit_rate → For overdrafts
 * 
 * These denormalized fields are synced from the attached config for performance.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterestConfigAttachmentService {

    private final VirtualAccountRepository vaRepository;
    private final InterestConfigurationRepository configRepository;
    private final InterestConfigurationService configService;

    // ========================================================================
    // AUTO-ATTACHMENT (Called when VA is created)
    // ========================================================================

    /**
     * Auto-attach interest configurations to a newly created VA.
     * 
     * This method should be called from VirtualAccountService.create() after
     * the VA is saved, to automatically link appropriate interest configs.
     * 
     * @param va The newly created Virtual Account
     * @return Updated VA with interest config links
     */
    @Transactional
    public VirtualAccount autoAttachInterestConfigs(VirtualAccount va) {
        log.info("Auto-attaching interest configs to VA: {} ({})", va.getVaNumber(), va.getCurrencyCode());
        
        UUID corporateId = va.getCorporateId();
        String currency = va.getCurrencyCode();
        
        // Skip system VAs (ROOT, AGGREGATION, CURRENCY_MIRROR)
        if (isSystemVa(va)) {
            log.debug("Skipping interest config attachment for system VA: {}", va.getVaNumber());
            return va;
        }
        
        // 1. Try to find and attach EXTERNAL config
        Optional<InterestConfiguration> externalConfig = resolveEffectiveConfig(
            corporateId, va.getId(), va.getOwningEntityId(), currency, ConfigType.EXTERNAL);
        
        if (externalConfig.isPresent()) {
            va.setExternalInterestConfigId(externalConfig.get().getId());
            log.debug("Attached EXTERNAL interest config {} to VA {}", 
                externalConfig.get().getId(), va.getVaNumber());
        } else {
            log.debug("No EXTERNAL interest config found for VA {} (currency: {})", 
                va.getVaNumber(), currency);
        }
        
        // 2. Try to find and attach INTERNAL config
        Optional<InterestConfiguration> internalConfig = resolveEffectiveConfig(
            corporateId, va.getId(), va.getOwningEntityId(), currency, ConfigType.INTERNAL);
        
        if (internalConfig.isPresent()) {
            va.setInternalInterestConfigId(internalConfig.get().getId());
            log.debug("Attached INTERNAL interest config {} to VA {}", 
                internalConfig.get().getId(), va.getVaNumber());
        } else {
            log.debug("No INTERNAL interest config found for VA {} (currency: {})", 
                va.getVaNumber(), currency);
        }
        
        // 3. Sync effective rates to VA
        syncEffectiveRates(va);
        
        va = vaRepository.save(va);
        log.info("Interest configs attached to VA {}: external={}, internal={}", 
            va.getVaNumber(), va.getExternalInterestConfigId(), va.getInternalInterestConfigId());
        
        return va;
    }

    /**
     * Check if VA is a system/structural VA that doesn't need interest config.
     */
    private boolean isSystemVa(VirtualAccount va) {
        if (va.getAccountCategory() == null) {
            return false;
        }
        return switch (va.getAccountCategory()) {
            case ROOT, AGGREGATION, CURRENCY_MIRROR, PHYSICAL_MIRROR, EXTERNAL_MIRROR -> true;
            default -> false;
        };
    }

    // ========================================================================
    // MANUAL ATTACHMENT/DETACHMENT
    // ========================================================================

    /**
     * Manually attach an external interest configuration to a VA.
     * 
     * @param vaId The Virtual Account ID
     * @param configId The Interest Configuration ID (must be ConfigType.EXTERNAL)
     * @return Updated VA
     */
    @Transactional
    public VirtualAccount attachExternalConfig(UUID vaId, UUID configId) {
        VirtualAccount va = getVa(vaId);
        InterestConfiguration config = getConfig(configId);
        
        // Validate config type
        if (config.getConfigType() != ConfigType.EXTERNAL) {
            throw new BusinessException("Configuration must be of type EXTERNAL. Got: " + config.getConfigType());
        }
        
        // Validate currency match
        if (!config.getCurrencyCode().equals(va.getCurrencyCode())) {
            throw new BusinessException(String.format(
                "Currency mismatch: VA is %s but config is %s", 
                va.getCurrencyCode(), config.getCurrencyCode()));
        }
        
        // Attach
        va.setExternalInterestConfigId(configId);
        syncEffectiveRates(va);
        
        va = vaRepository.save(va);
        log.info("Attached EXTERNAL config {} to VA {}", configId, va.getVaNumber());
        
        return va;
    }

    /**
     * Manually attach an internal interest configuration to a VA.
     * 
     * @param vaId The Virtual Account ID
     * @param configId The Interest Configuration ID (must be ConfigType.INTERNAL)
     * @return Updated VA
     */
    @Transactional
    public VirtualAccount attachInternalConfig(UUID vaId, UUID configId) {
        VirtualAccount va = getVa(vaId);
        InterestConfiguration config = getConfig(configId);
        
        // Validate config type
        if (config.getConfigType() != ConfigType.INTERNAL) {
            throw new BusinessException("Configuration must be of type INTERNAL. Got: " + config.getConfigType());
        }
        
        // Validate currency match
        if (!config.getCurrencyCode().equals(va.getCurrencyCode())) {
            throw new BusinessException(String.format(
                "Currency mismatch: VA is %s but config is %s", 
                va.getCurrencyCode(), config.getCurrencyCode()));
        }
        
        // Attach
        va.setInternalInterestConfigId(configId);
        syncEffectiveRates(va);
        
        va = vaRepository.save(va);
        log.info("Attached INTERNAL config {} to VA {}", configId, va.getVaNumber());
        
        return va;
    }

    /**
     * Detach external interest configuration from a VA.
     */
    @Transactional
    public VirtualAccount detachExternalConfig(UUID vaId) {
        VirtualAccount va = getVa(vaId);
        
        UUID previousConfigId = va.getExternalInterestConfigId();
        va.setExternalInterestConfigId(null);
        
        // Re-sync rates (may fall back to internal or defaults)
        syncEffectiveRates(va);
        
        va = vaRepository.save(va);
        log.info("Detached EXTERNAL config {} from VA {}", previousConfigId, va.getVaNumber());
        
        return va;
    }

    /**
     * Detach internal interest configuration from a VA.
     */
    @Transactional
    public VirtualAccount detachInternalConfig(UUID vaId) {
        VirtualAccount va = getVa(vaId);
        
        UUID previousConfigId = va.getInternalInterestConfigId();
        va.setInternalInterestConfigId(null);
        
        // Re-sync rates (may fall back to external or defaults)
        syncEffectiveRates(va);
        
        va = vaRepository.save(va);
        log.info("Detached INTERNAL config {} from VA {}", previousConfigId, va.getVaNumber());
        
        return va;
    }

    // ========================================================================
    // EFFECTIVE RATE SYNC
    // ========================================================================

    /**
     * Sync effective rates from attached config to VA's denormalized fields.
     * 
     * Priority for effective rates:
     * 1. Internal config (treasury rates) - if attached
     * 2. External config (bank rates) - if attached
     * 3. null (no rate)
     * 
     * This ensures the VA always has the "internal" rate if available,
     * which is what's used for actual interest calculations in IHB.
     */
    public void syncEffectiveRates(VirtualAccount va) {
        BigDecimal effectiveCreditRate = null;
        BigDecimal effectiveDebitRate = null;
        
        // Priority 1: Use internal config rates (treasury transfer pricing)
        if (va.getInternalInterestConfigId() != null) {
            Optional<InterestConfiguration> internalOpt = 
                configRepository.findById(va.getInternalInterestConfigId());
            
            if (internalOpt.isPresent() && internalOpt.get().isActive()) {
                InterestConfiguration internal = internalOpt.get();
                effectiveCreditRate = internal.getEffectiveCreditRate();
                effectiveDebitRate = internal.getEffectiveDebitRate();
                log.debug("Using INTERNAL rates for VA {}: credit={}, debit={}", 
                    va.getVaNumber(), effectiveCreditRate, effectiveDebitRate);
            }
        }
        
        // Priority 2: Fall back to external config rates (bank rates)
        if (effectiveCreditRate == null && va.getExternalInterestConfigId() != null) {
            Optional<InterestConfiguration> externalOpt = 
                configRepository.findById(va.getExternalInterestConfigId());
            
            if (externalOpt.isPresent() && externalOpt.get().isActive()) {
                InterestConfiguration external = externalOpt.get();
                effectiveCreditRate = external.getEffectiveCreditRate();
                effectiveDebitRate = external.getEffectiveDebitRate();
                log.debug("Using EXTERNAL rates for VA {}: credit={}, debit={}", 
                    va.getVaNumber(), effectiveCreditRate, effectiveDebitRate);
            }
        }
        
        // Update VA
        va.setEffectiveCreditRate(effectiveCreditRate);
        va.setEffectiveDebitRate(effectiveDebitRate);
    }

    /**
     * Sync effective rates for a specific VA by ID.
     */
    @Transactional
    public VirtualAccount syncEffectiveRatesById(UUID vaId) {
        VirtualAccount va = getVa(vaId);
        syncEffectiveRates(va);
        return vaRepository.save(va);
    }

    /**
     * Sync effective rates for all VAs in a corporate.
     */
    @Transactional
    public int syncAllForCorporate(UUID corporateId) {
        List<VirtualAccount> vas = vaRepository.findByCorporateId(corporateId);
        int synced = 0;
        
        for (VirtualAccount va : vas) {
            if (!isSystemVa(va)) {
                syncEffectiveRates(va);
                vaRepository.save(va);
                synced++;
            }
        }
        
        log.info("Synced interest rates for {} VAs in corporate {}", synced, corporateId);
        return synced;
    }

    // ========================================================================
    // EFFECTIVE CONFIG RESOLUTION
    // ========================================================================

    /**
     * Resolve the effective interest configuration for a VA.
     * 
     * Resolution priority:
     * 1. VA-specific config (targetId = vaId, targetType = VIRTUAL_ACCOUNT)
     * 2. Entity-specific config (targetId = entityId, targetType = LEGAL_ENTITY)
     * 3. Currency default (targetType = CURRENCY, currencyCode = currency)
     * 4. Corporate default (targetType = CORPORATE)
     * 
     * @param corporateId Corporate ID
     * @param vaId Virtual Account ID
     * @param entityId Owning entity ID (can be null)
     * @param currency Currency code
     * @param configType EXTERNAL or INTERNAL
     * @return Resolved config or empty
     */
    public Optional<InterestConfiguration> resolveEffectiveConfig(
            UUID corporateId, UUID vaId, UUID entityId, String currency, ConfigType configType) {
        
        LocalDate today = LocalDate.now();
        
        // 1. Try VA-specific config
        Optional<InterestConfiguration> vaConfig = configRepository
            .findActiveByTargetAndType(vaId, configType, today);
        if (vaConfig.isPresent() && currency.equals(vaConfig.get().getCurrencyCode())) {
            log.debug("Resolved config at VA level: {}", vaConfig.get().getId());
            return vaConfig;
        }
        
        // 2. Try Entity-specific config
        if (entityId != null) {
            Optional<InterestConfiguration> entityConfig = configRepository
                .findActiveByTargetAndType(entityId, configType, today);
            if (entityConfig.isPresent() && currency.equals(entityConfig.get().getCurrencyCode())) {
                log.debug("Resolved config at Entity level: {}", entityConfig.get().getId());
                return entityConfig;
            }
        }
        
        // 3. Try Currency default
        Optional<InterestConfiguration> currencyConfig = configRepository
            .findActiveByCurrencyDefault(corporateId, currency, configType, today);
        if (currencyConfig.isPresent()) {
            log.debug("Resolved config at Currency default level: {}", currencyConfig.get().getId());
            return currencyConfig;
        }
        
        // 4. Try Corporate default
        Optional<InterestConfiguration> corpConfig = configRepository
            .findActiveCorporateDefault(corporateId, configType, today);
        if (corpConfig.isPresent()) {
            log.debug("Resolved config at Corporate default level: {}", corpConfig.get().getId());
            return corpConfig;
        }
        
        log.debug("No config found for corporate={}, va={}, currency={}, type={}", 
            corporateId, vaId, currency, configType);
        return Optional.empty();
    }

    // ========================================================================
    // BATCH SYNC JOB (Nightly)
    // ========================================================================

    /**
     * Nightly batch job to sync all interest configs.
     * Runs at 2:00 AM every day.
     * 
     * This ensures:
     * 1. New default configs are applied to existing VAs
     * 2. Rate changes are propagated
     * 3. Expired configs are handled
     */
    @Scheduled(cron = "0 0 2 * * ?") // 2:00 AM daily
    @Transactional
    public void nightlySyncJob() {
        log.info("Starting nightly interest config sync job");
        
        long startTime = System.currentTimeMillis();
        int totalSynced = 0;
        int totalReattached = 0;
        
        // Get all active VAs
        List<VirtualAccount> allVas = vaRepository.findByStatus(VirtualAccount.VaStatus.ACTIVE);
        
        for (VirtualAccount va : allVas) {
            if (isSystemVa(va)) {
                continue;
            }
            
            try {
                boolean reattached = false;
                
                // Check if current configs are still valid
                if (va.getExternalInterestConfigId() != null) {
                    Optional<InterestConfiguration> extOpt = 
                        configRepository.findById(va.getExternalInterestConfigId());
                    if (extOpt.isEmpty() || !extOpt.get().isActive() || !extOpt.get().isCurrentlyValid()) {
                        // Config expired/invalid - try to find a new one
                        Optional<InterestConfiguration> newExt = resolveEffectiveConfig(
                            va.getCorporateId(), va.getId(), va.getOwningEntityId(), 
                            va.getCurrencyCode(), ConfigType.EXTERNAL);
                        va.setExternalInterestConfigId(newExt.map(InterestConfiguration::getId).orElse(null));
                        reattached = true;
                    }
                }
                
                if (va.getInternalInterestConfigId() != null) {
                    Optional<InterestConfiguration> intOpt = 
                        configRepository.findById(va.getInternalInterestConfigId());
                    if (intOpt.isEmpty() || !intOpt.get().isActive() || !intOpt.get().isCurrentlyValid()) {
                        // Config expired/invalid - try to find a new one
                        Optional<InterestConfiguration> newInt = resolveEffectiveConfig(
                            va.getCorporateId(), va.getId(), va.getOwningEntityId(), 
                            va.getCurrencyCode(), ConfigType.INTERNAL);
                        va.setInternalInterestConfigId(newInt.map(InterestConfiguration::getId).orElse(null));
                        reattached = true;
                    }
                }
                
                // Sync effective rates
                syncEffectiveRates(va);
                vaRepository.save(va);
                
                totalSynced++;
                if (reattached) {
                    totalReattached++;
                }
                
            } catch (Exception e) {
                log.error("Error syncing interest config for VA {}: {}", va.getVaNumber(), e.getMessage());
            }
        }
        
        long duration = System.currentTimeMillis() - startTime;
        log.info("Nightly interest config sync completed: {} VAs synced, {} reattached, took {}ms", 
            totalSynced, totalReattached, duration);
    }

    // ========================================================================
    // PROPAGATION (When config changes)
    // ========================================================================

    /**
     * Called when an interest configuration is updated.
     * Propagates rate changes to all VAs that reference this config.
     */
    @Transactional
    public int propagateConfigUpdate(UUID configId) {
        log.info("Propagating interest config update for config: {}", configId);
        
        InterestConfiguration config = getConfig(configId);
        int updated = 0;
        
        // Find all VAs that reference this config (either external or internal)
        List<VirtualAccount> vasWithExternal = vaRepository.findByExternalInterestConfigId(configId);
        List<VirtualAccount> vasWithInternal = vaRepository.findByInternalInterestConfigId(configId);
        
        for (VirtualAccount va : vasWithExternal) {
            syncEffectiveRates(va);
            vaRepository.save(va);
            updated++;
        }
        
        for (VirtualAccount va : vasWithInternal) {
            // Skip if already processed (both external and internal point to same config)
            if (vasWithExternal.contains(va)) {
                continue;
            }
            syncEffectiveRates(va);
            vaRepository.save(va);
            updated++;
        }
        
        log.info("Propagated config update to {} VAs", updated);
        return updated;
    }

    // ========================================================================
    // QUERY METHODS
    // ========================================================================

    /**
     * Get all VAs using a specific interest configuration.
     */
    @Transactional(readOnly = true)
    public List<VirtualAccount> getVasUsingConfig(UUID configId) {
        List<VirtualAccount> result = vaRepository.findByExternalInterestConfigId(configId);
        result.addAll(vaRepository.findByInternalInterestConfigId(configId));
        return result.stream().distinct().toList();
    }

    /**
     * Count VAs using a specific interest configuration.
     */
    @Transactional(readOnly = true)
    public long countVasUsingConfig(UUID configId) {
        long extCount = vaRepository.countByExternalInterestConfigId(configId);
        long intCount = vaRepository.countByInternalInterestConfigId(configId);
        // Note: This might double-count if same config is used for both
        return extCount + intCount;
    }

    /**
     * Check if a config can be deleted (not in use).
     */
    @Transactional(readOnly = true)
    public boolean canDeleteConfig(UUID configId) {
        return countVasUsingConfig(configId) == 0;
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private VirtualAccount getVa(UUID vaId) {
        return vaRepository.findById(vaId)
            .orElseThrow(() -> new ResourceNotFoundException("Virtual Account not found: " + vaId));
    }

    private InterestConfiguration getConfig(UUID configId) {
        return configRepository.findById(configId)
            .orElseThrow(() -> new ResourceNotFoundException("Interest Configuration not found: " + configId));
    }
}