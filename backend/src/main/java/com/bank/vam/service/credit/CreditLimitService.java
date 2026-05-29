package com.bank.vam.service.credit;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.credit.CreditFacility;
import com.bank.vam.entity.credit.CreditFacility.FacilityType;
import com.bank.vam.entity.credit.CreditLimit;
import com.bank.vam.entity.credit.CreditLimit.*;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.credit.CreditFacilityRepository;
import com.bank.vam.repository.credit.CreditLimitRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.service.hierarchy.LegalEntityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * CreditLimitService - Credit limit management for corporate users.
 * 
 * UNIFIED ARCHITECTURE v4.2 - CORPORATE USER PERSPECTIVE:
 * =========================================================
 * 
 * This service manages the TWO-LAYER credit limit model:
 * 
 * 1. EXTERNAL LIMITS (Bank-Provided) - READ-ONLY for Corporate
 *    --------------------------------------------------------
 *    - Source: Core Banking System (CBS) via BANCS sync
 *    - Types: OVERDRAFT, REVOLVING_CREDIT only
 *    - Linked to: CreditFacility -> PhysicalAccount
 *    - Corporate CANNOT modify - set by bank
 *    - Displayed for reference and ceiling validation
 * 
 * 2. INTERNAL LIMITS (CFO-Allocated) - Corporate CAN Manage
 *    ------------------------------------------------------
 *    - Source: Corporate Treasury/CFO
 *    - Types: Group Limit, Entity Limit
 *    - Applied to: Legal Entities (subsidiaries)
 *    - Corporate CAN create, update, delete
 *    - MUST NOT exceed corresponding External limit
 * 
 * KEY BUSINESS RULES:
 * ===================
 * 
 * Rule 1: Internal Limit ≤ External Limit
 *   - For any entity that is a Bank Customer
 *   - Internal limit cannot exceed external facility limit
 *   - Validation happens on create/update
 * 
 * Rule 2: Entity Limit ≤ Group Limit (Unallocated)
 *   - Entity sub-limits are allocated from Group limit
 *   - Sum of entity limits cannot exceed Group limit
 * 
 * Rule 3: Utilization Tracking
 *   - Both external and internal utilization tracked separately
 *   - Transaction blocked if EITHER limit is exceeded (for hard limits)
 * 
 * Rule 4: Effective Limit = MIN(External, Internal)
 *   - The lower of the two limits applies for transactions
 * 
 * SCOPE:
 * ======
 * - External limits are SYNCED from CBS (not created here)
 * - Internal limits are MANAGED here by corporate users
 * - Facility types limited to: OVERDRAFT, REVOLVING_CREDIT
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditLimitService {

    private final CreditLimitRepository limitRepository;
    private final CreditFacilityRepository facilityRepository;
    private final VirtualAccountRepository vaRepository;
    private final LegalEntityRepository entityRepository;
    private final LegalEntityService legalEntityService;

    // ========================================================================
    // EXTERNAL LIMITS (READ-ONLY FOR CORPORATE)
    // ========================================================================

    /**
     * Get external limits for a corporate (from bank facilities).
     * Corporate users can VIEW but not MODIFY these.
     * 
     * @param corporateId The corporate ID
     * @return List of external limits (Overdraft and Revolving Credit only)
     */
    @Transactional(readOnly = true)
    public List<CreditLimit> getExternalLimitsByCorporate(UUID corporateId) {
        return limitRepository.findByCorporateIdAndLimitType(corporateId, LimitType.EXTERNAL)
            .stream()
            .filter(CreditLimit::isActive)
            .collect(Collectors.toList());
    }

    /**
     * Get external facility details for an entity.
     * Returns OD/Revolving facilities only.
     */
    @Transactional(readOnly = true)
    public List<CreditFacility> getExternalFacilitiesForEntity(UUID entityId) {
        LegalEntity entity = entityRepository.findById(entityId)
            .orElseThrow(() -> new ResourceNotFoundException("Entity not found: " + entityId));
        
        if (!entity.isBankCustomer()) {
            return Collections.emptyList();
        }
        
        return facilityRepository.findByCorporateIdOrderByFacilityName(entity.getCorporateId())
            .stream()
            .filter(f -> f.isActive())
            .filter(f -> f.getFacilityType() == FacilityType.OVERDRAFT || 
                        f.getFacilityType() == FacilityType.REVOLVING_CREDIT)
            .collect(Collectors.toList());
    }

    /**
     * Get external limit for an entity (bank-provided).
     * This is the CEILING for internal limit.
     */
    @Transactional(readOnly = true)
    public Optional<BigDecimal> getExternalLimitCeiling(UUID entityId, String currency) {
        LegalEntity entity = entityRepository.findById(entityId).orElse(null);
        if (entity == null || !entity.isBankCustomer()) {
            return Optional.empty();
        }
        
        // Sum of active external limits for this entity
        BigDecimal total = limitRepository.findByTargetIdAndStatus(entityId, LimitStatus.ACTIVE)
            .stream()
            .filter(l -> l.getLimitType() == LimitType.EXTERNAL)
            .filter(l -> currency == null || currency.equals(l.getLimitCurrency()))
            .map(CreditLimit::getLimitAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        if (total.compareTo(BigDecimal.ZERO) == 0) {
            // Try facilities
            total = facilityRepository.findByCorporateIdOrderByFacilityName(entity.getCorporateId())
                .stream()
                .filter(f -> f.isActive())
                .filter(f -> f.getFacilityType() == FacilityType.OVERDRAFT || 
                            f.getFacilityType() == FacilityType.REVOLVING_CREDIT)
                .filter(f -> currency == null || currency.equals(f.getFacilityCurrency()))
                .map(CreditFacility::getSanctionedLimit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        
        return total.compareTo(BigDecimal.ZERO) > 0 ? Optional.of(total) : Optional.empty();
    }

    /**
     * Sync external limit from CBS facility.
     * This is called by CBS sync job, NOT by corporate users.
     * Corporate users should NOT have access to this method via API.
     */
    @Transactional
    public CreditLimit syncExternalLimitFromFacility(UUID facilityId) {
        CreditFacility facility = facilityRepository.findById(facilityId)
            .orElseThrow(() -> new ResourceNotFoundException("Facility not found: " + facilityId));
        
        // Only OD and Revolving Credit
        if (facility.getFacilityType() != FacilityType.OVERDRAFT &&
            facility.getFacilityType() != FacilityType.REVOLVING_CREDIT) {
            throw new BusinessException("Only OVERDRAFT and REVOLVING_CREDIT facilities create limits");
        }
        
        // Check if limit already exists
        Optional<CreditLimit> existing = limitRepository.findByTargetIdAndLimitTypeAndStatus(
            facility.getPhysicalAccountId(), LimitType.EXTERNAL, LimitStatus.ACTIVE);
        
        if (existing.isPresent()) {
            // Update existing limit
            CreditLimit limit = existing.get();
            limit.setLimitAmount(facility.getSanctionedLimit());
            limit.setLastSyncAt(LocalDateTime.now());
            limit.recalculateAvailable();
            return limitRepository.save(limit);
        }
        
        // Create new external limit
        CreditLimit limit = CreditLimit.createExternalLimit(
            facility.getCorporateId(),
            facility.getPhysicalAccountId(),
            TargetType.SHADOW_ACCOUNT,
            facilityId,
            facility.getSanctionedLimit(),
            facility.getFacilityCurrency()
        );
        limit.setExternalReference(facility.getExternalReference());
        limit.setSourceSystem("CBS");
        limit.setLastSyncAt(LocalDateTime.now());
        limit.setEffectiveTo(facility.getExpiryDate());
        
        return limitRepository.save(limit);
    }

    // ========================================================================
    // INTERNAL LIMITS (CORPORATE CAN MANAGE)
    // ========================================================================

    /**
     * Create a GROUP-LEVEL internal limit.
     * This is the top-level limit that can be allocated to entities.
     * 
     * VALIDATION:
     * - Only one active group limit per corporate per currency
     * - Group limit should not exceed total external limits (advisory, not blocking)
     */
    @Transactional
    public CreditLimit createGroupLimit(UUID corporateId, String limitName,
                                         BigDecimal amount, String currency,
                                         String approvedBy, boolean isHardLimit) {
        log.info("Creating group internal limit: {} {} for corporate {}", amount, currency, corporateId);

        // Check if group limit already exists for this currency
        List<CreditLimit> existingLimits = limitRepository.findByCorporateIdAndTargetType(
            corporateId, TargetType.CORPORATE);
        boolean hasActiveGroupLimit = existingLimits.stream()
            .filter(l -> l.isActive() && l.isInternal())
            .anyMatch(l -> currency.equals(l.getLimitCurrency()) && l.getParentLimitId() == null);
        
        if (hasActiveGroupLimit) {
            throw new BusinessException("Active group limit already exists for currency: " + currency);
        }

        // Advisory check: total external limits
        BigDecimal totalExternalLimit = getTotalExternalLimit(corporateId, currency);
        if (totalExternalLimit.compareTo(BigDecimal.ZERO) > 0 && 
            amount.compareTo(totalExternalLimit) > 0) {
            log.warn("Group limit {} exceeds total external limits {}. " +
                     "Effective limit will be capped at external limit.", amount, totalExternalLimit);
        }

        CreditLimit limit = CreditLimit.createGroupLimit(
            corporateId, limitName, amount, currency, approvedBy, isHardLimit);

        limit = limitRepository.save(limit);
        log.info("Created group internal limit {} for corporate {}", limit.getId(), corporateId);
        return limit;
    }

    /**
     * Get the group limit for a corporate.
     */
    @Transactional(readOnly = true)
    public Optional<CreditLimit> getGroupLimit(UUID corporateId) {
        return getGroupLimit(corporateId, null);
    }

    /**
     * Get the group limit for a corporate by currency.
     */
    @Transactional(readOnly = true)
    public Optional<CreditLimit> getGroupLimit(UUID corporateId, String currency) {
        return limitRepository.findByCorporateIdAndTargetType(corporateId, TargetType.CORPORATE)
            .stream()
            .filter(l -> l.isActive() && l.isInternal())
            .filter(l -> l.getParentLimitId() == null)
            .filter(l -> currency == null || currency.equals(l.getLimitCurrency()))
            .findFirst();
    }

    /**
     * Create an ENTITY sub-limit under the group limit.
     * 
     * VALIDATION:
     * - Entity must belong to corporate
     * - Entity cannot already have active internal limit
     * - Amount cannot exceed Group Limit unallocated
     * - Amount cannot exceed External Limit for entity (if bank customer)
     */
    @Transactional
    public CreditLimit createEntitySubLimit(UUID corporateId, UUID entityId,
                                             String limitName, BigDecimal amount, 
                                             String currency, String approvedBy,
                                             boolean isHardLimit, boolean requiresApproval,
                                             BigDecimal approvalThreshold) {
        log.info("Creating entity internal sub-limit: {} {} for entity {}", amount, currency, entityId);

        // Validate entity exists and belongs to corporate
        LegalEntity entity = entityRepository.findById(entityId)
            .orElseThrow(() -> new ResourceNotFoundException("Entity not found: " + entityId));
        
        if (!entity.getCorporateId().equals(corporateId)) {
            throw new BusinessException("Entity does not belong to this corporate");
        }

        // Check if internal limit already exists for entity
        Optional<CreditLimit> existingInternal = limitRepository.findByTargetIdAndLimitTypeAndStatus(
            entityId, LimitType.INTERNAL, LimitStatus.ACTIVE);
        if (existingInternal.isPresent()) {
            throw new BusinessException("Active internal limit already exists for this entity. " +
                                       "Update or delete existing limit first.");
        }

        // Get group limit (parent)
        CreditLimit groupLimit = getGroupLimit(corporateId, currency).orElse(null);
        UUID parentLimitId = groupLimit != null ? groupLimit.getId() : null;

        // VALIDATION 1: Check against group limit unallocated
        if (groupLimit != null) {
            BigDecimal unallocated = groupLimit.getUnallocatedAmount();
            if (amount.compareTo(unallocated) > 0) {
                throw new BusinessException(
                    String.format("Amount %s exceeds unallocated group limit: %s. " +
                                 "Increase group limit or reduce allocation to other entities.",
                                 amount, unallocated));
            }
        }

        // VALIDATION 2: Check against external limit (for bank customers)
        if (entity.isBankCustomer()) {
            Optional<BigDecimal> externalCeiling = getExternalLimitCeiling(entityId, currency);
            if (externalCeiling.isPresent() && amount.compareTo(externalCeiling.get()) > 0) {
                throw new BusinessException(
                    String.format("Internal limit %s cannot exceed external (bank) limit: %s. " +
                                 "The bank has set the maximum credit exposure for this entity.",
                                 amount, externalCeiling.get()));
            }
        }

        // Allocate from group if exists
        if (groupLimit != null) {
            groupLimit.allocateToChild(amount);
            limitRepository.save(groupLimit);
        }

        // Create entity limit
        CreditLimit limit = CreditLimit.createEntitySubLimit(
            corporateId, entityId, parentLimitId, limitName,
            amount, currency, approvedBy, isHardLimit,
            requiresApproval, approvalThreshold);

        limit = limitRepository.save(limit);

        // Sync denormalized fields to LegalEntity
        legalEntityService.syncLimitFromCreditLimit(entityId, amount, currency);

        log.info("Created entity internal sub-limit {} for entity {}", limit.getId(), entityId);
        return limit;
    }

    /**
     * Update entity sub-limit amount.
     * 
     * VALIDATION:
     * - Cannot reduce below current utilization
     * - New amount cannot exceed Group Limit unallocated + current amount
     * - New amount cannot exceed External Limit (for bank customers)
     */
    @Transactional
    public CreditLimit updateEntitySubLimitAmount(UUID limitId, BigDecimal newAmount, String updatedBy) {
        CreditLimit limit = limitRepository.findById(limitId)
            .orElseThrow(() -> new ResourceNotFoundException("Limit not found: " + limitId));

        if (limit.getTargetType() != TargetType.LEGAL_ENTITY || !limit.isInternal()) {
            throw new BusinessException("This method is only for internal entity limits");
        }

        // Cannot reduce below current utilization
        if (limit.getUtilizedAmount() != null && 
            newAmount.compareTo(limit.getUtilizedAmount()) < 0) {
            throw new BusinessException(
                String.format("Cannot reduce limit below current utilization: %s", 
                             limit.getUtilizedAmount()));
        }

        BigDecimal oldAmount = limit.getLimitAmount();
        BigDecimal difference = newAmount.subtract(oldAmount);

        // Get entity for external limit check
        LegalEntity entity = entityRepository.findById(limit.getTargetId()).orElse(null);

        // VALIDATION: Check against external limit (for bank customers)
        if (entity != null && entity.isBankCustomer()) {
            Optional<BigDecimal> externalCeiling = getExternalLimitCeiling(
                limit.getTargetId(), limit.getLimitCurrency());
            if (externalCeiling.isPresent() && newAmount.compareTo(externalCeiling.get()) > 0) {
                throw new BusinessException(
                    String.format("Internal limit %s cannot exceed external (bank) limit: %s",
                                 newAmount, externalCeiling.get()));
            }
        }

        // Update group allocation if has parent
        if (limit.getParentLimitId() != null) {
            CreditLimit parentLimit = limitRepository.findById(limit.getParentLimitId()).orElse(null);
            if (parentLimit != null) {
                if (difference.compareTo(BigDecimal.ZERO) > 0) {
                    // Increasing - check unallocated
                    if (difference.compareTo(parentLimit.getUnallocatedAmount()) > 0) {
                        throw new BusinessException(
                            String.format("Increase of %s exceeds unallocated group limit: %s",
                                         difference, parentLimit.getUnallocatedAmount()));
                    }
                    parentLimit.allocateToChild(difference);
                } else {
                    // Decreasing - release allocation
                    parentLimit.releaseChildAllocation(difference.abs());
                }
                limitRepository.save(parentLimit);
            }
        }

        // Update limit
        limit.setLimitAmount(newAmount);
        limit.recalculateAvailable();
        limit.setUpdatedBy(updatedBy);
        limit = limitRepository.save(limit);

        // Sync denormalized fields to LegalEntity
        legalEntityService.syncLimitFromCreditLimit(
            limit.getTargetId(), newAmount, limit.getLimitCurrency());

        log.info("Updated entity internal limit {} from {} to {}", limitId, oldAmount, newAmount);
        return limit;
    }

    /**
     * Update group limit amount.
     * 
     * VALIDATION:
     * - Cannot reduce below sum of entity allocations
     * - Advisory: Should not exceed total external limits
     */
    @Transactional
    public CreditLimit updateGroupLimitAmount(UUID limitId, BigDecimal newAmount, String updatedBy) {
        CreditLimit limit = limitRepository.findById(limitId)
            .orElseThrow(() -> new ResourceNotFoundException("Limit not found: " + limitId));

        if (!limit.isGroupLimit()) {
            throw new BusinessException("This method is only for group limits");
        }

        // Cannot reduce below allocated to children
        BigDecimal allocated = limit.getAllocatedToChildren() != null ? 
            limit.getAllocatedToChildren() : BigDecimal.ZERO;
        if (newAmount.compareTo(allocated) < 0) {
            throw new BusinessException(
                String.format("Cannot reduce group limit below entity allocations: %s. " +
                             "Reduce entity limits first.", allocated));
        }

        // Advisory check: total external limits
        BigDecimal totalExternalLimit = getTotalExternalLimit(
            limit.getCorporateId(), limit.getLimitCurrency());
        if (totalExternalLimit.compareTo(BigDecimal.ZERO) > 0 && 
            newAmount.compareTo(totalExternalLimit) > 0) {
            log.warn("Group limit {} exceeds total external limits {}. " +
                     "Effective limit will be capped at external limit.", newAmount, totalExternalLimit);
        }

        BigDecimal oldAmount = limit.getLimitAmount();
        limit.setLimitAmount(newAmount);
        limit.recalculateAvailable();
        limit.setUpdatedBy(updatedBy);

        log.info("Updated group limit {} from {} to {}", limitId, oldAmount, newAmount);
        return limitRepository.save(limit);
    }

    /**
     * Delete entity sub-limit (releases group allocation).
     * 
     * VALIDATION:
     * - Cannot delete if utilization > 0
     */
    @Transactional
    public void deleteEntitySubLimit(UUID limitId, String reason) {
        CreditLimit limit = limitRepository.findById(limitId)
            .orElseThrow(() -> new ResourceNotFoundException("Limit not found: " + limitId));

        if (!limit.isInternal()) {
            throw new BusinessException("Cannot delete external (bank) limits. Contact bank.");
        }

        // Check for utilization
        if (limit.getUtilizedAmount() != null && 
            limit.getUtilizedAmount().compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException(
                String.format("Cannot delete limit with utilization: %s. " +
                             "Clear outstanding credit usage first.", limit.getUtilizedAmount()));
        }

        // Release group allocation if has parent
        if (limit.getParentLimitId() != null) {
            CreditLimit parentLimit = limitRepository.findById(limit.getParentLimitId()).orElse(null);
            if (parentLimit != null) {
                parentLimit.releaseChildAllocation(limit.getLimitAmount());
                limitRepository.save(parentLimit);
            }
        }

        // Clear denormalized fields in LegalEntity
        legalEntityService.clearLimitFromCreditLimit(limit.getTargetId());

        // Mark as cancelled
        limit.setStatus(LimitStatus.CANCELLED);
        limit.setNotes("Deleted by user: " + reason);
        limitRepository.save(limit);

        log.info("Deleted entity internal limit {}: {}", limitId, reason);
    }

    /**
     * Update limit control settings (hard/soft, approval).
     */
    @Transactional
    public CreditLimit updateLimitControls(UUID limitId, boolean isHardLimit, 
                                            boolean requiresApproval, BigDecimal approvalThreshold) {
        CreditLimit limit = limitRepository.findById(limitId)
            .orElseThrow(() -> new ResourceNotFoundException("Limit not found: " + limitId));

        if (!limit.isInternal()) {
            throw new BusinessException("Cannot modify external (bank) limit controls");
        }

        limit.setIsHardLimit(isHardLimit);
        limit.setRequiresApproval(requiresApproval);
        limit.setApprovalThresholdPercent(approvalThreshold);

        return limitRepository.save(limit);
    }

    /**
     * Update limit thresholds (warning/critical levels).
     */
    @Transactional
    public CreditLimit updateThresholds(UUID limitId, BigDecimal warningPercent, 
                                         BigDecimal criticalPercent) {
        CreditLimit limit = limitRepository.findById(limitId)
            .orElseThrow(() -> new ResourceNotFoundException("Limit not found: " + limitId));

        if (warningPercent != null) {
            limit.setWarningThresholdPercent(warningPercent);
        }
        if (criticalPercent != null) {
            limit.setCriticalThresholdPercent(criticalPercent);
        }

        return limitRepository.save(limit);
    }

    // ========================================================================
    // UTILIZATION MANAGEMENT
    // ========================================================================

    /**
     * Utilize a specific limit by its ID.
     * This is the backward-compatible method used by FundsAvailabilityService.
     * 
     * @param limitId The credit limit ID to utilize
     * @param amount Amount to utilize
     */
    @Transactional
    public void utilizeLimit(UUID limitId, BigDecimal amount) {
        CreditLimit limit = limitRepository.findById(limitId)
            .orElseThrow(() -> new ResourceNotFoundException("Limit not found: " + limitId));

        if (!limit.canUtilize(amount)) {
            if (limit.isHard()) {
                throw new BusinessException(
                    String.format("Insufficient available limit. Available: %s, Required: %s",
                                 limit.getAvailableAmount(), amount));
            }
            // Soft limit - warn but proceed
            log.warn("Limit {} exceeded. Available: {}, Used: {}", 
                    limitId, limit.getAvailableAmount(), amount);
        }
        
        limit.utilize(amount);
        limitRepository.save(limit);

        // Sync to LegalEntity if applicable
        if (limit.getTargetType() == TargetType.LEGAL_ENTITY && limit.isInternal()) {
            legalEntityService.syncUtilizationFromCreditLimit(
                limit.getTargetId(), limit.getUtilizedAmount());
        }

        // Update external facility if applicable
        if (limit.isExternal() && limit.getCreditFacilityId() != null) {
            facilityRepository.findById(limit.getCreditFacilityId()).ifPresent(facility -> {
                facility.drawDown(amount);
                facilityRepository.save(facility);
            });
        }

        log.debug("Utilized {} from limit {}", amount, limitId);
    }

    /**
     * Release a specific limit by its ID.
     * This is the backward-compatible method used by FundsAvailabilityService.
     * 
     * @param limitId The credit limit ID to release
     * @param amount Amount to release
     */
    @Transactional
    public void releaseLimit(UUID limitId, BigDecimal amount) {
        CreditLimit limit = limitRepository.findById(limitId)
            .orElseThrow(() -> new ResourceNotFoundException("Limit not found: " + limitId));

        limit.release(amount);
        limitRepository.save(limit);

        // Sync to LegalEntity if applicable
        if (limit.getTargetType() == TargetType.LEGAL_ENTITY && limit.isInternal()) {
            legalEntityService.syncUtilizationFromCreditLimit(
                limit.getTargetId(), limit.getUtilizedAmount());
        }

        // Update external facility if applicable
        if (limit.isExternal() && limit.getCreditFacilityId() != null) {
            facilityRepository.findById(limit.getCreditFacilityId()).ifPresent(facility -> {
                facility.repay(amount);
                facilityRepository.save(facility);
            });
        }

        log.debug("Released {} from limit {}", amount, limitId);
    }

    /**
     * Utilize limit for an entity (after debit that goes into overdraft).
     * Updates BOTH external AND internal limits for the entity.
     * 
     * @param entityId The legal entity ID
     * @param amount Amount to utilize
     * @param currency Currency code
     */
    @Transactional
    public void utilizeLimitForEntity(UUID entityId, BigDecimal amount, String currency) {
        // Update internal limit
        Optional<CreditLimit> internalOpt = limitRepository.findByTargetIdAndLimitTypeAndStatus(
            entityId, LimitType.INTERNAL, LimitStatus.ACTIVE);
        if (internalOpt.isPresent()) {
            CreditLimit limit = internalOpt.get();
            if (!limit.canUtilize(amount)) {
                if (limit.isHard()) {
                    throw new BusinessException(
                        String.format("Insufficient internal limit. Available: %s, Required: %s",
                                     limit.getAvailableAmount(), amount));
                }
                // Soft limit - warn but proceed
                log.warn("Internal limit exceeded for entity {}. Available: {}, Used: {}", 
                        entityId, limit.getAvailableAmount(), amount);
            }
            limit.utilize(amount);
            limitRepository.save(limit);
            
            // Sync to LegalEntity
            legalEntityService.syncUtilizationFromCreditLimit(entityId, limit.getUtilizedAmount());
        }

        // Update external limit if exists
        Optional<CreditLimit> externalOpt = limitRepository.findByTargetIdAndLimitTypeAndStatus(
            entityId, LimitType.EXTERNAL, LimitStatus.ACTIVE);
        if (externalOpt.isPresent()) {
            CreditLimit limit = externalOpt.get();
            limit.utilize(amount);
            limitRepository.save(limit);
            
            // Also update facility outstanding
            if (limit.getCreditFacilityId() != null) {
                facilityRepository.findById(limit.getCreditFacilityId()).ifPresent(facility -> {
                    facility.drawDown(amount);
                    facilityRepository.save(facility);
                });
            }
        }

        log.debug("Utilized {} from limits for entity {}", amount, entityId);
    }

    /**
     * Release limit for an entity (after credit that reduces overdraft).
     * 
     * @param entityId The legal entity ID
     * @param amount Amount to release
     */
    @Transactional
    public void releaseLimitForEntity(UUID entityId, BigDecimal amount) {
        // Release internal limit
        limitRepository.findByTargetIdAndLimitTypeAndStatus(
            entityId, LimitType.INTERNAL, LimitStatus.ACTIVE
        ).ifPresent(limit -> {
            limit.release(amount);
            limitRepository.save(limit);
            legalEntityService.syncUtilizationFromCreditLimit(entityId, limit.getUtilizedAmount());
        });

        // Release external limit
        limitRepository.findByTargetIdAndLimitTypeAndStatus(
            entityId, LimitType.EXTERNAL, LimitStatus.ACTIVE
        ).ifPresent(limit -> {
            limit.release(amount);
            limitRepository.save(limit);
            
            if (limit.getCreditFacilityId() != null) {
                facilityRepository.findById(limit.getCreditFacilityId()).ifPresent(facility -> {
                    facility.repay(amount);
                    facilityRepository.save(facility);
                });
            }
        });

        log.debug("Released {} from limits for entity {}", amount, entityId);
    }

    /**
     * Check if entity can utilize a given amount.
     * Returns detailed result with both external and internal status.
     */
    @Transactional(readOnly = true)
    public LimitCheckResult checkFundsAvailability(UUID entityId, BigDecimal amount, String currency) {
        LimitCheckResult result = new LimitCheckResult();
        result.setEntityId(entityId);
        result.setRequestedAmount(amount);
        result.setCurrency(currency);

        // Check internal limit
        Optional<CreditLimit> internalOpt = limitRepository.findActiveInternalLimit(entityId);
        if (internalOpt.isPresent()) {
            CreditLimit limit = internalOpt.get();
            result.setInternalLimit(limit.getLimitAmount());
            result.setInternalUtilized(limit.getUtilizedAmount());
            result.setInternalAvailable(limit.getAvailableAmount());
            result.setInternalHardLimit(limit.isHard());
            result.setInternalCanUtilize(limit.canUtilize(amount));
        } else {
            result.setInternalCanUtilize(true); // No internal limit = unlimited
        }

        // Check external limit
        Optional<CreditLimit> externalOpt = limitRepository.findActiveExternalLimit(entityId);
        if (externalOpt.isPresent()) {
            CreditLimit limit = externalOpt.get();
            result.setExternalLimit(limit.getLimitAmount());
            result.setExternalUtilized(limit.getUtilizedAmount());
            result.setExternalAvailable(limit.getAvailableAmount());
            result.setExternalCanUtilize(limit.canUtilize(amount));
        } else {
            result.setExternalCanUtilize(true); // No external limit = unlimited
        }

        // Effective limit = MIN(external, internal)
        BigDecimal effectiveAvailable = null;
        if (result.getInternalAvailable() != null && result.getExternalAvailable() != null) {
            effectiveAvailable = result.getInternalAvailable().min(result.getExternalAvailable());
        } else if (result.getInternalAvailable() != null) {
            effectiveAvailable = result.getInternalAvailable();
        } else if (result.getExternalAvailable() != null) {
            effectiveAvailable = result.getExternalAvailable();
        }
        result.setEffectiveAvailable(effectiveAvailable);

        // Can proceed if BOTH limits allow
        result.setCanProceed(result.isInternalCanUtilize() && result.isExternalCanUtilize());

        return result;
    }

    /**
     * Check if transaction should be blocked by internal limit.
     */
    @Transactional(readOnly = true)
    public boolean shouldBlockTransaction(UUID entityId, BigDecimal amount) {
        Optional<CreditLimit> limitOpt = limitRepository.findActiveInternalLimit(entityId);
        if (limitOpt.isEmpty()) {
            return false; // No limit, no block
        }

        CreditLimit limit = limitOpt.get();
        
        // Soft limits don't block
        if (limit.isSoft()) {
            return false;
        }

        // Check if transaction would exceed available
        return !limit.canUtilize(amount);
    }

    /**
     * Check if transaction needs approval based on limit threshold.
     */
    @Transactional(readOnly = true)
    public boolean needsApproval(UUID entityId, BigDecimal amount) {
        Optional<CreditLimit> limitOpt = limitRepository.findActiveInternalLimit(entityId);
        if (limitOpt.isEmpty()) {
            return false;
        }

        CreditLimit limit = limitOpt.get();
        
        if (!Boolean.TRUE.equals(limit.getRequiresApproval())) {
            return false;
        }

        // Simulate utilization after transaction
        BigDecimal currentUtilized = limit.getUtilizedAmount() != null ? 
            limit.getUtilizedAmount() : BigDecimal.ZERO;
        BigDecimal newUtilized = currentUtilized.add(amount);
        BigDecimal newUtilizationPct = newUtilized.multiply(new BigDecimal("100"))
            .divide(limit.getLimitAmount(), 2, java.math.RoundingMode.HALF_UP);

        BigDecimal threshold = limit.getApprovalThresholdPercent() != null 
            ? limit.getApprovalThresholdPercent() 
            : new BigDecimal("80");

        return newUtilizationPct.compareTo(threshold) >= 0;
    }

    // ========================================================================
    // QUERY OPERATIONS
    // ========================================================================

    /**
     * Get limit by ID.
     */
    @Transactional(readOnly = true)
    public CreditLimit getLimit(UUID limitId) {
        return limitRepository.findById(limitId)
            .orElseThrow(() -> new ResourceNotFoundException("Limit not found: " + limitId));
    }

    /**
     * Get all limits for a corporate (both external and internal).
     */
    @Transactional(readOnly = true)
    public List<CreditLimit> getLimitsByCorporate(UUID corporateId) {
        return limitRepository.findByCorporateId(corporateId)
            .stream()
            .filter(CreditLimit::isActive)
            .collect(Collectors.toList());
    }


        // ========================================================================
    // BASIC LOOKUP METHODS (Add near existing getLimitsByCorporate method)
    // ========================================================================

    /**
     * Get all limits for a corporate (both external and internal).
     * Alias for getLimitsByCorporate for clearer naming.
     */
    @Transactional(readOnly = true)
    public List<CreditLimit> findAllByCorporate(UUID corporateId) {
        return getLimitsByCorporate(corporateId);
    }

    /**
     * Get group limits for a corporate.
     * Group limits target the CORPORATE entity itself (not legal entities).
     */
    @Transactional(readOnly = true)
    public List<CreditLimit> getGroupLimits(UUID corporateId) {
        return limitRepository.findByCorporateIdAndTargetType(corporateId, TargetType.CORPORATE)
            .stream()
            .filter(CreditLimit::isActive)
            .filter(CreditLimit::isInternal) // Only internal group limits
            .collect(Collectors.toList());
    }

    /**
     * Get limits by target ID.
     */
    @Transactional(readOnly = true)
    public List<CreditLimit> findByTarget(UUID targetId) {
        return limitRepository.findByTargetId(targetId)
            .stream()
            .filter(CreditLimit::isActive)
            .collect(Collectors.toList());
    }

    /**
     * Get limit by ID.
     * This is an alias for getLimit() for clearer Controller naming.
     */
    @Transactional(readOnly = true)
    public CreditLimit getById(UUID limitId) {
        return getLimit(limitId);
    }

    /**
     * Update limit amount (generic - for both group and entity limits).
     */
    @Transactional
    public CreditLimit updateLimitAmount(UUID limitId, BigDecimal newAmount, String updatedBy) {
        CreditLimit limit = getLimit(limitId);
        
        // Check if it's a group or entity limit
        if (limit.getTargetType() == TargetType.CORPORATE) {
            return updateGroupLimitAmount(limitId, newAmount, updatedBy);
        } else {
            return updateEntitySubLimitAmount(limitId, newAmount, updatedBy);
        }
    }

    /**
     * Get internal limits by corporate (group + entity limits).
     */
    @Transactional(readOnly = true)
    public List<CreditLimit> getInternalLimitsByCorporate(UUID corporateId) {
        return limitRepository.findByCorporateIdAndLimitType(corporateId, LimitType.INTERNAL)
            .stream()
            .filter(CreditLimit::isActive)
            .collect(Collectors.toList());
    }

    /**
     * Get entity sub-limits under a group limit.
     */
    @Transactional(readOnly = true)
    public List<CreditLimit> getEntitySubLimits(UUID groupLimitId) {
        return limitRepository.findAll().stream()
            .filter(l -> groupLimitId.equals(l.getParentLimitId()))
            .filter(CreditLimit::isActive)
            .collect(Collectors.toList());
    }

    /**
     * Get internal limit for an entity.
     */
    @Transactional(readOnly = true)
    public Optional<CreditLimit> getEntityInternalLimit(UUID entityId) {
        return limitRepository.findActiveInternalLimit(entityId);
    }

    /**
     * Get limits at warning level.
     */
    @Transactional(readOnly = true)
    public List<CreditLimit> getLimitsAtWarningLevel(UUID corporateId) {
        return limitRepository.findByCorporateIdAndStatus(corporateId, LimitStatus.ACTIVE).stream()
            .filter(l -> l.isInternal()) // Only internal limits for corporate view
            .filter(CreditLimit::isAtWarningLevel)
            .collect(Collectors.toList());
    }

    /**
     * Get limits at critical level.
     */
    @Transactional(readOnly = true)
    public List<CreditLimit> getLimitsAtCriticalLevel(UUID corporateId) {
        return limitRepository.findByCorporateIdAndStatus(corporateId, LimitStatus.ACTIVE).stream()
            .filter(l -> l.isInternal())
            .filter(CreditLimit::isAtCriticalLevel)
            .collect(Collectors.toList());
    }

    /**
     * Get breached limits.
     */
    @Transactional(readOnly = true)
    public List<CreditLimit> getBreachedLimits(UUID corporateId) {
        return limitRepository.findByCorporateIdAndStatus(corporateId, LimitStatus.BREACHED);
    }

    /**
     * Get expiring limits.
     */
    @Transactional(readOnly = true)
    public List<CreditLimit> getExpiringLimits(UUID corporateId, int daysAhead) {
        LocalDate expiryThreshold = LocalDate.now().plusDays(daysAhead);
        return limitRepository.findByCorporateIdAndStatus(corporateId, LimitStatus.ACTIVE).stream()
            .filter(l -> l.getEffectiveTo() != null && 
                        l.getEffectiveTo().isBefore(expiryThreshold))
            .collect(Collectors.toList());
    }

    /**
     * Get total external limit for a corporate.
     */
    @Transactional(readOnly = true)
    public BigDecimal getTotalExternalLimit(UUID corporateId, String currency) {
        return limitRepository.findByCorporateIdAndLimitType(corporateId, LimitType.EXTERNAL)
            .stream()
            .filter(CreditLimit::isActive)
            .filter(l -> currency == null || currency.equals(l.getLimitCurrency()))
            .map(CreditLimit::getLimitAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Get totals for corporate dashboard.
     */
    @Transactional(readOnly = true)
    public LimitTotals getLimitTotals(UUID corporateId) {
        List<CreditLimit> allLimits = limitRepository.findByCorporateId(corporateId)
            .stream()
            .filter(CreditLimit::isActive)
            .collect(Collectors.toList());

        BigDecimal externalTotal = allLimits.stream()
            .filter(CreditLimit::isExternal)
            .map(CreditLimit::getLimitAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal externalUtilized = allLimits.stream()
            .filter(CreditLimit::isExternal)
            .map(l -> l.getUtilizedAmount() != null ? l.getUtilizedAmount() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal internalTotal = allLimits.stream()
            .filter(CreditLimit::isInternal)
            .filter(l -> l.getTargetType() == TargetType.CORPORATE) // Group limits only
            .map(CreditLimit::getLimitAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal internalAllocated = allLimits.stream()
            .filter(CreditLimit::isInternal)
            .filter(l -> l.getTargetType() == TargetType.CORPORATE)
            .map(l -> l.getAllocatedToChildren() != null ? l.getAllocatedToChildren() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal internalUtilized = allLimits.stream()
            .filter(CreditLimit::isInternal)
            .filter(l -> l.getTargetType() == TargetType.LEGAL_ENTITY)
            .map(l -> l.getUtilizedAmount() != null ? l.getUtilizedAmount() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        return LimitTotals.builder()
            .corporateId(corporateId)
            .externalLimitTotal(externalTotal)
            .externalUtilizedTotal(externalUtilized)
            .externalAvailable(externalTotal.subtract(externalUtilized))
            .internalLimitTotal(internalTotal)
            .internalAllocatedTotal(internalAllocated)
            .internalUtilizedTotal(internalUtilized)
            .internalUnallocated(internalTotal.subtract(internalAllocated))
            .internalAvailable(internalTotal.subtract(internalUtilized))
            .build();
    }

    // ============================================================================
// ADD THESE NEW METHODS TO CreditLimitService.java
// Location: backend/src/main/java/com/bank/vam/service/credit/CreditLimitService.java
// 
// Add after the existing methods, before the HELPER CLASSES section
// ============================================================================

    // ========================================================================
    // NEW v5.2.0: MULTI-CURRENCY GROUP LIMITS
    // ========================================================================

    /**
     * Get ALL group limits for a corporate (multi-currency).
     * Returns one limit per currency that has been configured.
     */
    @Transactional(readOnly = true)
    public List<CreditLimit> getAllGroupLimits(UUID corporateId) {
        return limitRepository.findAllGroupLimitsByCorporate(corporateId);
    }

    /**
     * Get group limit for a specific currency.
     */
    @Transactional(readOnly = true)
    public Optional<CreditLimit> getGroupLimitByCurrency(UUID corporateId, String currency) {
        return limitRepository.findGroupLimitByCorporateAndCurrency(corporateId, currency);
    }

    /**
     * Get currencies that have group limits configured.
     */
    @Transactional(readOnly = true)
    public List<String> getGroupLimitCurrencies(UUID corporateId) {
        return limitRepository.findGroupLimitCurrencies(corporateId);
    }

    /**
     * Check if group limit exists for a specific currency.
     */
    @Transactional(readOnly = true)
    public boolean hasGroupLimitForCurrency(UUID corporateId, String currency) {
        return limitRepository.existsGroupLimitForCurrency(corporateId, currency);
    }

    // ========================================================================
    // NEW v5.2.0: MULTI-CURRENCY ENTITY LIMITS
    // ========================================================================

    /**
     * Get ALL entity limits for a target (multi-currency).
     * Returns one limit per currency that has been allocated.
     */
    @Transactional(readOnly = true)
    public List<CreditLimit> getAllEntityLimits(UUID entityId) {
        return limitRepository.findAllEntityLimitsByTarget(entityId);
    }

    /**
     * Get entity limit for a specific currency.
     */
    @Transactional(readOnly = true)
    public Optional<CreditLimit> getEntityLimitByCurrency(UUID entityId, String currency) {
        return limitRepository.findActiveInternalLimitByTargetAndCurrency(entityId, currency);
    }

    /**
     * Get currencies that have entity limits configured.
     */
    @Transactional(readOnly = true)
    public List<String> getEntityLimitCurrencies(UUID entityId) {
        return limitRepository.findEntityLimitCurrencies(entityId);
    }

    /**
     * Check if entity limit exists for a specific currency.
     */
    @Transactional(readOnly = true)
    public boolean hasEntityLimitForCurrency(UUID entityId, String currency) {
        return limitRepository.existsEntityLimitForCurrency(entityId, currency);
    }

    /**
     * Create an ENTITY sub-limit with MULTI-CURRENCY support.
     * 
     * ENHANCED VALIDATION:
     * - Entity can have multiple limits (one per currency)
     * - Each currency limit must be allocated from corresponding group limit
     * - Amount cannot exceed external ceiling for that currency
     */
    /**
     * Create entity sub-limit with multi-currency support.
     * Supports hierarchical allocation: can allocate from Group Limit or from a Regional Treasury's pool.
     *
     * @param corporateId Corporate ID
     * @param entityId Entity ID
     * @param limitName Optional limit name
     * @param amount Limit amount
     * @param currency Currency code
     * @param approvedBy Approver name
     * @param isHardLimit Whether this is a hard limit
     * @param requiresApproval Whether transactions above threshold require approval
     * @param approvalThreshold Approval threshold percentage
     * @param requestedParentLimitId Optional parent limit ID. If provided, allocates from that parent (e.g., Regional Treasury).
     *                               If null, allocates from the Group Limit for the specified currency.
     */
    @Transactional
    public CreditLimit createEntitySubLimitMultiCurrency(UUID corporateId, UUID entityId,
                                                          String limitName, BigDecimal amount,
                                                          String currency, String approvedBy,
                                                          boolean isHardLimit, boolean requiresApproval,
                                                          BigDecimal approvalThreshold,
                                                          UUID requestedParentLimitId) {
        log.info("Creating entity internal sub-limit (multi-currency): {} {} for entity {}, parentLimitId={}",
                 amount, currency, entityId, requestedParentLimitId);

        // Validate entity exists and belongs to corporate
        LegalEntity entity = entityRepository.findById(entityId)
            .orElseThrow(() -> new ResourceNotFoundException("Entity not found: " + entityId));

        if (!entity.getCorporateId().equals(corporateId)) {
            throw new BusinessException("Entity does not belong to this corporate");
        }

        // Check if internal limit already exists for THIS CURRENCY
        if (limitRepository.existsEntityLimitForCurrency(entityId, currency)) {
            throw new BusinessException(
                String.format("Active internal limit already exists for this entity in %s. " +
                             "Update or delete existing %s limit first.", currency, currency));
        }

        // Determine parent limit: use requested parent or fall back to Group Limit
        CreditLimit parentLimit;
        String parentType;

        if (requestedParentLimitId != null) {
            // Use specified parent (e.g., Regional Treasury's pool)
            parentLimit = limitRepository.findById(requestedParentLimitId)
                .orElseThrow(() -> new BusinessException("Parent limit not found: " + requestedParentLimitId));

            // Validate parent limit currency matches
            if (!parentLimit.getLimitCurrency().equals(currency)) {
                throw new BusinessException(
                    String.format("Parent limit currency %s does not match requested currency %s",
                                 parentLimit.getLimitCurrency(), currency));
            }

            // Validate parent belongs to same corporate
            if (!parentLimit.getCorporateId().equals(corporateId)) {
                throw new BusinessException("Parent limit does not belong to this corporate");
            }

            parentType = parentLimit.getTargetType().name();
            log.info("Allocating from parent limit {} ({})", requestedParentLimitId, parentType);
        } else {
            // Fall back to Group Limit for this currency
            parentLimit = limitRepository.findGroupLimitByCorporateAndCurrency(corporateId, currency)
                .orElse(null);

            if (parentLimit == null) {
                throw new BusinessException(
                    String.format("No group limit exists for currency %s. " +
                                 "Create a group limit for %s first.", currency, currency));
            }
            parentType = "GROUP";
        }

        UUID parentLimitId = parentLimit.getId();

        // VALIDATION 1: Check against parent limit unallocated amount
        BigDecimal unallocated = parentLimit.getUnallocatedAmount();
        if (amount.compareTo(unallocated) > 0) {
            throw new BusinessException(
                String.format("Amount %s exceeds unallocated %s limit: %s. " +
                             "Increase %s limit or reduce allocation to other entities.",
                             amount, currency, unallocated, parentType));
        }

        // VALIDATION 2: Check against external limit (for bank customers)
        if (entity.isBankCustomer()) {
            Optional<BigDecimal> externalCeiling = getExternalLimitCeiling(entityId, currency);
            if (externalCeiling.isPresent() && amount.compareTo(externalCeiling.get()) > 0) {
                throw new BusinessException(
                    String.format("Internal limit %s cannot exceed external (bank) limit: %s for %s. " +
                                 "The bank has set the maximum credit exposure for this entity.",
                                 amount, externalCeiling.get(), currency));
            }
        }

        // Allocate from parent
        parentLimit.allocateToChild(amount);
        limitRepository.save(parentLimit);

        // Create entity limit
        CreditLimit limit = CreditLimit.createEntitySubLimit(
            corporateId, entityId, parentLimitId, limitName,
            amount, currency, approvedBy, isHardLimit,
            requiresApproval, approvalThreshold);

        limit = limitRepository.save(limit);

        // Sync denormalized fields to LegalEntity (primary currency only)
        if (currency.equals(entity.getFunctionalCurrency())) {
            legalEntityService.syncLimitFromCreditLimit(entityId, amount, currency);
        }

        log.info("Created entity internal sub-limit {} ({}) for entity {}, allocated from {} limit {}",
                 limit.getId(), currency, entityId, parentType, parentLimitId);
        return limit;
    }

    // Overload for backward compatibility - defaults to Group Limit
    @Transactional
    public CreditLimit createEntitySubLimitMultiCurrency(UUID corporateId, UUID entityId,
                                                          String limitName, BigDecimal amount,
                                                          String currency, String approvedBy,
                                                          boolean isHardLimit, boolean requiresApproval,
                                                          BigDecimal approvalThreshold) {
        return createEntitySubLimitMultiCurrency(corporateId, entityId, limitName, amount, currency,
                                                  approvedBy, isHardLimit, requiresApproval,
                                                  approvalThreshold, null);
    }

    /**
     * Get entity limits grouped by parent group limit.
     */
    @Transactional(readOnly = true)
    public List<CreditLimit> getEntityLimitsByGroupLimit(UUID groupLimitId) {
        return limitRepository.findEntityLimitsByGroupLimit(groupLimitId);
    }

    // ========================================================================
    // NEW v5.2.0: VA-LEVEL LIMITS
    // ========================================================================

    /**
     * Create a VA-level internal limit.
     * Allocated from entity limit for the same currency.
     * 
     * Hierarchy: Group Limit → Entity Limit → VA Limit
     * 
     * @param corporateId Corporate ID
     * @param vaId Virtual Account ID
     * @param limitName Limit name
     * @param amount Limit amount
     * @param approvedBy Approver
     * @param isHardLimit Whether hard limit (blocks transactions)
     * @return Created VA limit
     */
    @Transactional
    public CreditLimit createVaLimit(UUID corporateId, UUID vaId, String limitName,
                                      BigDecimal amount, String approvedBy, boolean isHardLimit) {
        log.info("Creating VA internal limit: {} for VA {}", amount, vaId);

        // Validate VA exists and belongs to corporate
        VirtualAccount va = vaRepository.findById(vaId)
            .orElseThrow(() -> new ResourceNotFoundException("VA not found: " + vaId));
        
        if (!va.getCorporateId().equals(corporateId)) {
            throw new BusinessException("VA does not belong to this corporate");
        }

        // Check if VA already has a limit
        Optional<CreditLimit> existingVaLimit = limitRepository.findVaLimit(vaId);
        if (existingVaLimit.isPresent()) {
            throw new BusinessException("VA already has an internal limit. Update or delete first.");
        }

        String currency = va.getCurrencyCode();
        UUID entityId = va.getOwningEntityId();

        // VA must be owned by an entity
        if (entityId == null) {
            throw new BusinessException("VA must be assigned to an entity before allocating credit limit");
        }

        // Find parent entity limit for this currency
        CreditLimit entityLimit = limitRepository.findActiveInternalLimitByTargetAndCurrency(entityId, currency)
            .orElseThrow(() -> new BusinessException(
                String.format("No entity limit exists for %s in currency %s. " +
                             "Create entity limit first before allocating to VAs.", 
                             va.getOwningEntityCode(), currency)));

        // Validate amount against entity limit unallocated
        BigDecimal entityUnallocated = entityLimit.getUnallocatedAmount();
        if (amount.compareTo(entityUnallocated) > 0) {
            throw new BusinessException(
                String.format("Amount %s exceeds unallocated entity limit: %s. " +
                             "Increase entity limit or reduce allocation to other VAs.",
                             amount, entityUnallocated));
        }

        // Allocate from entity limit
        entityLimit.allocateToChild(amount);
        limitRepository.save(entityLimit);

        // Create VA limit
        CreditLimit limit = CreditLimit.builder()
            .corporateId(corporateId)
            .limitType(LimitType.INTERNAL)
            .targetType(TargetType.VIRTUAL_ACCOUNT)
            .targetId(vaId)
            .parentLimitId(entityLimit.getId())
            .limitName(limitName != null ? limitName : va.getVaName() + " Credit Limit")
            .limitAmount(amount)
            .limitCurrency(currency)
            .isHardLimit(isHardLimit)
            .approvedBy(approvedBy)
            .approvedAt(LocalDateTime.now())
            .status(LimitStatus.ACTIVE)
            .effectiveFrom(LocalDate.now())
            .utilizedAmount(BigDecimal.ZERO)
            .allocatedToChildren(BigDecimal.ZERO)
            .build();
        
        limit.recalculateAvailable();
        limit = limitRepository.save(limit);

        // Update VA's internalLimitId
        va.setInternalLimitId(limit.getId());
        va.setEffectiveCreditLimit(amount);
        va.setCreditLimitAvailable(amount);
        vaRepository.save(va);

        log.info("Created VA internal limit {} for VA {}", limit.getId(), vaId);
        return limit;
    }

    /**
     * Update VA limit amount.
     */
    @Transactional
    public CreditLimit updateVaLimitAmount(UUID limitId, BigDecimal newAmount, String updatedBy) {
        CreditLimit limit = limitRepository.findById(limitId)
            .orElseThrow(() -> new ResourceNotFoundException("Limit not found: " + limitId));

        if (limit.getTargetType() != TargetType.VIRTUAL_ACCOUNT || !limit.isInternal()) {
            throw new BusinessException("This method is only for internal VA limits");
        }

        // Cannot reduce below current utilization
        BigDecimal utilized = limit.getUtilizedAmount() != null ? limit.getUtilizedAmount() : BigDecimal.ZERO;
        if (newAmount.compareTo(utilized) < 0) {
            throw new BusinessException(
                String.format("Cannot reduce limit below current utilization: %s", utilized));
        }

        BigDecimal oldAmount = limit.getLimitAmount();
        BigDecimal difference = newAmount.subtract(oldAmount);

        // Update parent entity limit allocation
        if (limit.getParentLimitId() != null) {
            CreditLimit parentLimit = limitRepository.findById(limit.getParentLimitId())
                .orElseThrow(() -> new BusinessException("Parent entity limit not found"));
            
            if (difference.compareTo(BigDecimal.ZERO) > 0) {
                // Increasing - check unallocated
                if (difference.compareTo(parentLimit.getUnallocatedAmount()) > 0) {
                    throw new BusinessException(
                        String.format("Increase of %s exceeds unallocated entity limit: %s",
                                     difference, parentLimit.getUnallocatedAmount()));
                }
                parentLimit.allocateToChild(difference);
            } else {
                // Decreasing - release allocation
                parentLimit.releaseChildAllocation(difference.abs());
            }
            limitRepository.save(parentLimit);
        }

        // Update limit
        limit.setLimitAmount(newAmount);
        limit.recalculateAvailable();
        limit.setUpdatedBy(updatedBy);
        limit = limitRepository.save(limit);

        // Update VA
        VirtualAccount va = vaRepository.findById(limit.getTargetId()).orElse(null);
        if (va != null) {
            va.setEffectiveCreditLimit(newAmount);
            va.recalculateCreditAvailable();
            vaRepository.save(va);
        }

        log.info("Updated VA internal limit {} from {} to {}", limitId, oldAmount, newAmount);
        return limit;
    }

    /**
     * Delete VA limit (releases entity allocation).
     */
    @Transactional
    public void deleteVaLimit(UUID limitId, String reason) {
        CreditLimit limit = limitRepository.findById(limitId)
            .orElseThrow(() -> new ResourceNotFoundException("Limit not found: " + limitId));

        if (limit.getTargetType() != TargetType.VIRTUAL_ACCOUNT || !limit.isInternal()) {
            throw new BusinessException("This method is only for internal VA limits");
        }

        // Check for utilization
        BigDecimal utilized = limit.getUtilizedAmount() != null ? limit.getUtilizedAmount() : BigDecimal.ZERO;
        if (utilized.compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException(
                String.format("Cannot delete limit with utilization: %s. " +
                             "Clear outstanding credit usage first.", utilized));
        }

        // Release entity allocation
        if (limit.getParentLimitId() != null) {
            CreditLimit parentLimit = limitRepository.findById(limit.getParentLimitId()).orElse(null);
            if (parentLimit != null) {
                parentLimit.releaseChildAllocation(limit.getLimitAmount());
                limitRepository.save(parentLimit);
            }
        }

        // Clear VA's internalLimitId
        VirtualAccount va = vaRepository.findById(limit.getTargetId()).orElse(null);
        if (va != null) {
            va.setInternalLimitId(null);
            va.setEffectiveCreditLimit(null);
            va.setCreditLimitAvailable(null);
            va.setCreditLimitUtilized(BigDecimal.ZERO);
            vaRepository.save(va);
        }

        // Mark as cancelled
        limit.setStatus(LimitStatus.CANCELLED);
        limit.setNotes("Deleted by user: " + reason);
        limitRepository.save(limit);

        log.info("Deleted VA internal limit {}: {}", limitId, reason);
    }

    /**
     * Get VA limit by VA ID.
     */
    @Transactional(readOnly = true)
    public Optional<CreditLimit> getVaLimit(UUID vaId) {
        return limitRepository.findVaLimit(vaId);
    }

    /**
     * Get all VA limits under an entity.
     */
    @Transactional(readOnly = true)
    public List<CreditLimit> getVaLimitsByEntity(UUID entityId) {
        return limitRepository.findVaLimitsByEntity(entityId);
    }

    /**
     * Get VA limits allocated from a specific entity limit.
     */
    @Transactional(readOnly = true)
    public List<CreditLimit> getVaLimitsByEntityLimit(UUID entityLimitId) {
        return limitRepository.findVaLimitsByEntityLimit(entityLimitId);
    }

    /**
     * Count VAs with limits under an entity.
     */
    @Transactional(readOnly = true)
    public long countVaLimitsForEntity(UUID entityId) {
        return limitRepository.countVaLimitsByEntity(entityId);
    }

    // ========================================================================
    // NEW v5.2.0: MULTI-CURRENCY TOTALS
    // ========================================================================

    /**
     * Get totals grouped by currency for corporate dashboard.
     */
    @Transactional(readOnly = true)
    public Map<String, CurrencyLimitTotals> getLimitTotalsByCurrency(UUID corporateId) {
        List<CreditLimit> allLimits = limitRepository.findByCorporateId(corporateId)
            .stream()
            .filter(CreditLimit::isActive)
            .collect(Collectors.toList());

        // Group by currency
        Map<String, List<CreditLimit>> byCurrency = allLimits.stream()
            .collect(Collectors.groupingBy(CreditLimit::getLimitCurrency));

        Map<String, CurrencyLimitTotals> result = new HashMap<>();
        
        for (Map.Entry<String, List<CreditLimit>> entry : byCurrency.entrySet()) {
            String currency = entry.getKey();
            List<CreditLimit> currencyLimits = entry.getValue();

            // External totals
            BigDecimal externalTotal = currencyLimits.stream()
                .filter(CreditLimit::isExternal)
                .map(CreditLimit::getLimitAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal externalUtilized = currencyLimits.stream()
                .filter(CreditLimit::isExternal)
                .map(l -> l.getUtilizedAmount() != null ? l.getUtilizedAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            // Group limit for this currency
            Optional<CreditLimit> groupLimit = currencyLimits.stream()
                .filter(CreditLimit::isInternal)
                .filter(l -> l.getTargetType() == TargetType.CORPORATE)
                .filter(l -> l.getParentLimitId() == null)
                .findFirst();

            BigDecimal groupTotal = groupLimit.map(CreditLimit::getLimitAmount).orElse(BigDecimal.ZERO);
            BigDecimal groupAllocated = groupLimit
                .map(l -> l.getAllocatedToChildren() != null ? l.getAllocatedToChildren() : BigDecimal.ZERO)
                .orElse(BigDecimal.ZERO);

            // Entity totals
            BigDecimal entityUtilized = currencyLimits.stream()
                .filter(CreditLimit::isInternal)
                .filter(l -> l.getTargetType() == TargetType.LEGAL_ENTITY)
                .map(l -> l.getUtilizedAmount() != null ? l.getUtilizedAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            long entityCount = currencyLimits.stream()
                .filter(CreditLimit::isInternal)
                .filter(l -> l.getTargetType() == TargetType.LEGAL_ENTITY)
                .count();

            // VA totals
            BigDecimal vaAllocated = currencyLimits.stream()
                .filter(CreditLimit::isInternal)
                .filter(l -> l.getTargetType() == TargetType.VIRTUAL_ACCOUNT)
                .map(CreditLimit::getLimitAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            long vaCount = currencyLimits.stream()
                .filter(CreditLimit::isInternal)
                .filter(l -> l.getTargetType() == TargetType.VIRTUAL_ACCOUNT)
                .count();

            result.put(currency, CurrencyLimitTotals.builder()
                .currency(currency)
                .externalTotal(externalTotal)
                .externalUtilized(externalUtilized)
                .externalAvailable(externalTotal.subtract(externalUtilized))
                .groupTotal(groupTotal)
                .groupAllocated(groupAllocated)
                .groupUnallocated(groupTotal.subtract(groupAllocated))
                .entityUtilized(entityUtilized)
                .entityCount((int) entityCount)
                .vaAllocated(vaAllocated)
                .vaCount((int) vaCount)
                .build());
        }

        return result;
    }

    // ========================================================================
    // NEW v5.2.0: ADDITIONAL HELPER CLASSES
    // ========================================================================

    /**
     * Currency-specific limit totals.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class CurrencyLimitTotals {
        private String currency;
        
        // External (bank)
        private BigDecimal externalTotal;
        private BigDecimal externalUtilized;
        private BigDecimal externalAvailable;
        
        // Group
        private BigDecimal groupTotal;
        private BigDecimal groupAllocated;
        private BigDecimal groupUnallocated;
        
        // Entity
        private BigDecimal entityUtilized;
        private int entityCount;
        
        // VA
        private BigDecimal vaAllocated;
        private int vaCount;
    }

    // ========================================================================
    // UTILIZATION PROPAGATION - Real-time hierarchy update
    // ========================================================================

    /**
     * Record utilization on a limit and propagate up the hierarchy.
     * When entity uses credit, the utilization propagates up to Regional Treasury and Group Limit.
     *
     * @param limitId The limit being utilized
     * @param amount Amount to utilize (positive = increase, negative = decrease)
     * @param transactionRef Transaction reference for audit
     * @return The updated limit
     */
    @Transactional
    public CreditLimit recordUtilization(UUID limitId, BigDecimal amount, String transactionRef) {
        log.info("Recording utilization {} on limit {}, txnRef={}", amount, limitId, transactionRef);

        CreditLimit limit = limitRepository.findById(limitId)
            .orElseThrow(() -> new ResourceNotFoundException("Limit not found: " + limitId));

        // Record on this limit
        if (amount.compareTo(BigDecimal.ZERO) > 0) {
            limit.utilize(amount);
        } else {
            limit.release(amount.abs());
        }
        limit = limitRepository.save(limit);

        // Propagate up the hierarchy
        propagateUtilizationUp(limit.getParentLimitId(), amount);

        log.info("Utilization recorded. Limit {} now at {} / {} ({}%)",
                 limitId, limit.getUtilizedAmount(), limit.getLimitAmount(), limit.getUtilizationPercent());

        return limit;
    }

    /**
     * Record utilization by entity ID and currency.
     * Convenience method to find the limit first.
     *
     * @param entityId Legal Entity ID
     * @param currency Currency code
     * @param amount Amount to utilize
     * @param transactionRef Transaction reference
     * @return The updated limit
     */
    @Transactional
    public CreditLimit recordEntityUtilization(UUID entityId, String currency, BigDecimal amount, String transactionRef) {
        CreditLimit entityLimit = limitRepository.findActiveInternalLimitByTargetAndCurrency(entityId, currency)
            .orElseThrow(() -> new BusinessException(
                String.format("No active limit found for entity %s in currency %s", entityId, currency)));

        return recordUtilization(entityLimit.getId(), amount, transactionRef);
    }

    /**
     * Release utilization (e.g., when loan is repaid).
     * Propagates down the hierarchy.
     *
     * @param limitId The limit to release utilization from
     * @param amount Amount to release
     * @param transactionRef Transaction reference
     * @return The updated limit
     */
    @Transactional
    public CreditLimit releaseUtilization(UUID limitId, BigDecimal amount, String transactionRef) {
        return recordUtilization(limitId, amount.negate(), transactionRef);
    }

    /**
     * Propagate utilization change up the parent limit chain.
     * This ensures Regional Treasury and Group Limit see aggregated utilization.
     *
     * @param parentLimitId Starting parent limit ID
     * @param delta Change in utilization (positive = increase, negative = decrease)
     */
    private void propagateUtilizationUp(UUID parentLimitId, BigDecimal delta) {
        if (parentLimitId == null || delta.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }

        // Walk up the hierarchy
        UUID currentParentId = parentLimitId;
        int depth = 0;
        final int MAX_DEPTH = 10; // Safety limit to prevent infinite loops

        while (currentParentId != null && depth < MAX_DEPTH) {
            CreditLimit parentLimit = limitRepository.findById(currentParentId).orElse(null);
            if (parentLimit == null) {
                log.warn("Parent limit {} not found during propagation", currentParentId);
                break;
            }

            // Update parent's utilization
            if (delta.compareTo(BigDecimal.ZERO) > 0) {
                parentLimit.utilize(delta);
            } else {
                parentLimit.release(delta.abs());
            }
            limitRepository.save(parentLimit);

            log.debug("Propagated utilization {} to parent {} ({}). Now at {} / {}",
                     delta, currentParentId, parentLimit.getTargetType(),
                     parentLimit.getUtilizedAmount(), parentLimit.getLimitAmount());

            // Move up to next parent
            currentParentId = parentLimit.getParentLimitId();
            depth++;
        }

        if (depth >= MAX_DEPTH) {
            log.warn("Utilization propagation reached max depth {} - possible circular reference", MAX_DEPTH);
        }
    }

    /**
     * Check if utilization can be recorded without breaching limits.
     * Checks the entire hierarchy.
     *
     * @param limitId Limit to check
     * @param amount Amount to potentially utilize
     * @return Result with details about each level
     */
    @Transactional(readOnly = true)
    public UtilizationCheckResult checkUtilizationCapacity(UUID limitId, BigDecimal amount) {
        CreditLimit limit = limitRepository.findById(limitId)
            .orElseThrow(() -> new ResourceNotFoundException("Limit not found: " + limitId));

        UtilizationCheckResult result = new UtilizationCheckResult();
        result.setRequestedAmount(amount);
        result.setCanProceed(true);

        // Check this limit
        boolean canUtilizeHere = limit.canUtilize(amount);
        result.setEntityLimitOk(canUtilizeHere);
        result.setEntityAvailable(limit.getAvailableAmount());
        if (!canUtilizeHere && limit.isHard()) {
            result.setCanProceed(false);
            result.setBlockingLevel("ENTITY");
            result.setBlockingReason(String.format("Entity limit would be exceeded. Available: %s, Requested: %s",
                                                    limit.getAvailableAmount(), amount));
        }

        // Check up the hierarchy
        UUID currentParentId = limit.getParentLimitId();
        int level = 0;
        while (currentParentId != null && level < 10) {
            CreditLimit parentLimit = limitRepository.findById(currentParentId).orElse(null);
            if (parentLimit == null) break;

            boolean parentCanUtilize = parentLimit.canUtilize(amount);
            String levelName = parentLimit.isGroupLimit() ? "GROUP" : "REGIONAL";

            if (level == 0) {
                result.setRegionalLimitOk(parentCanUtilize);
                result.setRegionalAvailable(parentLimit.getAvailableAmount());
            }
            if (parentLimit.isGroupLimit()) {
                result.setGroupLimitOk(parentCanUtilize);
                result.setGroupAvailable(parentLimit.getAvailableAmount());
            }

            if (!parentCanUtilize && parentLimit.isHard() && result.isCanProceed()) {
                result.setCanProceed(false);
                result.setBlockingLevel(levelName);
                result.setBlockingReason(String.format("%s limit would be exceeded. Available: %s, Requested: %s",
                                                        levelName, parentLimit.getAvailableAmount(), amount));
            }

            currentParentId = parentLimit.getParentLimitId();
            level++;
        }

        return result;
    }

    /**
     * Recalculate utilization for a parent limit by summing all children.
     * Useful for reconciliation or after data corrections.
     *
     * @param parentLimitId Parent limit to recalculate
     * @return Updated parent limit
     */
    @Transactional
    public CreditLimit recalculateParentUtilization(UUID parentLimitId) {
        CreditLimit parentLimit = limitRepository.findById(parentLimitId)
            .orElseThrow(() -> new ResourceNotFoundException("Parent limit not found: " + parentLimitId));

        // Sum utilization from all child limits
        List<CreditLimit> children = limitRepository.findByParentLimitId(parentLimitId);
        BigDecimal totalChildUtilization = children.stream()
            .map(CreditLimit::getUtilizedAmount)
            .filter(u -> u != null)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        log.info("Recalculating utilization for limit {}. Previous: {}, Calculated from {} children: {}",
                 parentLimitId, parentLimit.getUtilizedAmount(), children.size(), totalChildUtilization);

        parentLimit.setUtilizedAmount(totalChildUtilization);
        parentLimit.recalculateAvailable();
        return limitRepository.save(parentLimit);
    }

    // ========================================================================
    // UTILIZATION CHECK RESULT
    // ========================================================================

    @lombok.Data
    public static class UtilizationCheckResult {
        private BigDecimal requestedAmount;
        private boolean canProceed;
        private String blockingLevel;
        private String blockingReason;

        // Entity level
        private boolean entityLimitOk;
        private BigDecimal entityAvailable;

        // Regional Treasury level (if applicable)
        private boolean regionalLimitOk;
        private BigDecimal regionalAvailable;

        // Group level
        private boolean groupLimitOk;
        private BigDecimal groupAvailable;
    }

    // ========================================================================
    // HELPER CLASSES
    // ========================================================================

    @lombok.Data
    @lombok.Builder
    public static class LimitTotals {
        private UUID corporateId;
        private BigDecimal externalLimitTotal;
        private BigDecimal externalUtilizedTotal;
        private BigDecimal externalAvailable;
        private BigDecimal internalLimitTotal;
        private BigDecimal internalAllocatedTotal;
        private BigDecimal internalUtilizedTotal;
        private BigDecimal internalUnallocated;
        private BigDecimal internalAvailable;
    }

    @lombok.Data
    public static class LimitCheckResult {
        private UUID entityId;
        private BigDecimal requestedAmount;
        private String currency;
        
        // External (bank) limit
        private BigDecimal externalLimit;
        private BigDecimal externalUtilized;
        private BigDecimal externalAvailable;
        private boolean externalCanUtilize;
        
        // Internal (CFO) limit
        private BigDecimal internalLimit;
        private BigDecimal internalUtilized;
        private BigDecimal internalAvailable;
        private boolean internalHardLimit;
        private boolean internalCanUtilize;
        
        // Effective
        private BigDecimal effectiveAvailable;
        private boolean canProceed;
    }
}