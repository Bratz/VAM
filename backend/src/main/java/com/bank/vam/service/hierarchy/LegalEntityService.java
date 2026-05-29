package com.bank.vam.service.hierarchy;

import com.bank.vam.controller.hierarchy.LegalEntityController.*;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.hierarchy.LegalEntity.ConsolidationMethod;
import com.bank.vam.entity.hierarchy.LegalEntity.EntityStatus;
import com.bank.vam.entity.hierarchy.LegalEntity.EntityType;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.PhysicalAccountRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * LegalEntityService - Business logic for Legal Entity/Subsidiary management.
 * 
 * Legal Entities represent the corporate organizational structure:
 * - Form a hierarchy (Holding → Subsidiaries → Branches)
 * - Own physical bank accounts
 * - Have functional and reporting currencies
 * - Can be designated as treasury centers
 * - Participate in pooling and netting arrangements
 * 
 * This service handles:
 * - Entity CRUD operations
 * - Hierarchy management (parent-child relationships)
 * - Entity search and filtering
 * - Account ownership queries
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LegalEntityService {

    private final LegalEntityRepository legalEntityRepository;
    private final PhysicalAccountRepository physicalAccountRepository;
    private final VirtualAccountRepository virtualAccountRepository;

    // ========================================================================
    // CREATE OPERATIONS
    // ========================================================================

    /**
     * Create a new legal entity.
     */
    @Transactional
    public LegalEntity createEntity(CreateEntityRequest request) {
        log.info("Creating legal entity: {} ({})", request.getEntityCode(), request.getEntityName());
        
        // Check for duplicate code - use existsByEntityCode
        if (legalEntityRepository.existsByEntityCode(request.getEntityCode())) {
            throw new BusinessException("Entity code already exists: " + request.getEntityCode());
        }
        
        // Build hierarchy path
        String hierarchyPath;
        int hierarchyLevel;
        
        if (request.getParentEntityId() != null) {
            LegalEntity parent = getEntity(request.getParentEntityId());
            if (!parent.getCorporateId().equals(request.getCorporateId())) {
                throw new BusinessException("Parent entity belongs to a different corporate");
            }
            hierarchyPath = parent.getHierarchyPath() + "/" + request.getEntityCode();
            hierarchyLevel = parent.getHierarchyLevel() + 1;
        } else {
            hierarchyPath = "/" + request.getEntityCode();
            hierarchyLevel = 1;
        }
        
        // Create entity
        LegalEntity entity = LegalEntity.builder()
            // Identity
            .entityCode(request.getEntityCode().toUpperCase())
            .entityName(request.getEntityName())
            .shortName(request.getShortName())
            
            // Hierarchy
            .corporateId(request.getCorporateId())
            .parentEntityId(request.getParentEntityId())
            .hierarchyPath(hierarchyPath)
            .hierarchyLevel(hierarchyLevel)
            
            // Currency
            .functionalCurrency(request.getFunctionalCurrency().toUpperCase())
            .reportingCurrency(request.getReportingCurrency() != null 
                ? request.getReportingCurrency().toUpperCase() 
                : request.getFunctionalCurrency().toUpperCase())
            
            // Jurisdiction
            .countryCode(request.getCountryCode())
            .jurisdiction(request.getJurisdiction())
            .taxId(request.getTaxId())
            .registrationNumber(request.getRegistrationNumber())
            
            // Classification
            .entityType(parseEntityType(request.getEntityType()))
            .legalForm(request.getLegalForm())
            
            // Ownership
            .ownershipPercent(request.getOwnershipPercent() != null 
                ? request.getOwnershipPercent() 
                : BigDecimal.valueOf(100))
            .consolidationMethod(parseConsolidationMethod(request.getConsolidationMethod()))
            
            // Treasury config
            .isTreasuryCenter(Boolean.TRUE.equals(request.getIsTreasuryCenter()))
            .canHoldPhysicalAccounts(request.getCanHoldPhysicalAccounts() != null
                ? request.getCanHoldPhysicalAccounts()
                : true)
            .canParticipatePooling(request.getCanParticipatePooling() != null
                ? request.getCanParticipatePooling()
                : true)
            .canParticipateNetting(request.getCanParticipateNetting() != null
                ? request.getCanParticipateNetting()
                : true)
            // Auto-link: Treasury Centers automatically get IHB enabled and can lend
            .ihbEnabled(Boolean.TRUE.equals(request.getIsTreasuryCenter()) ? true : request.getIhbEnabled())
            .canLend(Boolean.TRUE.equals(request.getIsTreasuryCenter()) ? true : request.getCanLend())
            .canBorrow(request.getCanBorrow())
            
            // Credit
            .internalCreditLimit(request.getInternalCreditLimit())
            .internalLimitUtilized(BigDecimal.ZERO)
            .internalLimitCurrency(request.getFunctionalCurrency().toUpperCase())
            
            // Validity
            .effectiveFrom(request.getEffectiveFrom())
            .effectiveTo(request.getEffectiveTo())
            
            // Status
            .status(EntityStatus.ACTIVE)
            .build();
        
        entity = legalEntityRepository.save(entity);
        
        log.info("Created legal entity: {} (ID: {})", entity.getEntityCode(), entity.getId());
        
        return entity;
    }

    // ========================================================================
    // QUERY OPERATIONS - By Corporate
    // ========================================================================

    /**
     * Get all legal entities for a corporate.
     */
    @Transactional(readOnly = true)
    public List<LegalEntity> getEntitiesByCorporate(UUID corporateId) {
        return legalEntityRepository.findByCorporateIdOrderByHierarchyPath(corporateId);
    }

    /**
     * Get active legal entities for a corporate.
     */
    @Transactional(readOnly = true)
    public List<LegalEntity> getActiveEntitiesByCorporate(UUID corporateId) {
        return legalEntityRepository.findByCorporateIdAndStatus(corporateId, EntityStatus.ACTIVE);
    }

    /**
     * Get root-level entities (no parent) for a corporate.
     */
    @Transactional(readOnly = true)
    public List<LegalEntity> getRootEntities(UUID corporateId) {
        return legalEntityRepository.findByCorporateIdAndParentEntityIdIsNull(corporateId);
    }

    /**
     * Get child entities of a parent entity.
     */
    @Transactional(readOnly = true)
    public List<LegalEntity> getChildEntities(UUID parentEntityId) {
        // Use existing method findByParentEntityIdOrderByEntityName
        return legalEntityRepository.findByParentEntityIdOrderByEntityName(parentEntityId);
    }

    /**
     * Get all descendants of an entity.
     */
    @Transactional(readOnly = true)
    public List<LegalEntity> getDescendants(UUID entityId) {
        LegalEntity entity = getEntity(entityId);
        // Use existing method findDescendants
        return legalEntityRepository.findDescendants(entity.getHierarchyPath());
    }

    /**
     * Get ancestors of an entity (path to root).
     */
    @Transactional(readOnly = true)
    public List<LegalEntity> getAncestors(UUID entityId) {
        // Use existing native query method
        return legalEntityRepository.findAncestors(entityId);
    }

    /**
     * Get treasury centers for a corporate.
     */
    @Transactional(readOnly = true)
    public List<LegalEntity> getTreasuryCenters(UUID corporateId) {
        // Use existing method findTreasuryCenters
        return legalEntityRepository.findTreasuryCenters(corporateId);
    }

    /**
     * Get entities that have physical accounts linked.
     */
    @Transactional(readOnly = true)
    public List<LegalEntity> getEntitiesWithPhysicalAccounts(UUID corporateId) {
        return legalEntityRepository.findEntitiesWithPhysicalAccounts(corporateId);
    }

    /**
     * Get entities that have shadow accounts.
     */
    @Transactional(readOnly = true)
    public List<LegalEntity> getEntitiesWithShadowAccounts(UUID corporateId) {
        // Filter entities that have virtual accounts with PHYSICAL_MIRROR category
        return getEntitiesByCorporate(corporateId).stream()
            .filter(e -> virtualAccountRepository.countByOwningEntityIdAndAccountCategory(
                e.getId(), AccountCategory.PHYSICAL_MIRROR) > 0)
            .collect(Collectors.toList());
    }

    // ========================================================================
    // QUERY OPERATIONS - Single Entity
    // ========================================================================

    /**
     * Get single entity by ID.
     */
    @Transactional(readOnly = true)
    public LegalEntity getEntity(UUID entityId) {
        return legalEntityRepository.findById(entityId)
            .orElseThrow(() -> new ResourceNotFoundException("Legal entity not found: " + entityId));
    }

    /**
     * Get entity by code within a corporate.
     */
    @Transactional(readOnly = true)
    public LegalEntity getEntityByCode(UUID corporateId, String entityCode) {
        // Use existing findByEntityCode and filter by corporate
        return legalEntityRepository.findByEntityCode(entityCode.toUpperCase())
            .filter(e -> e.getCorporateId().equals(corporateId))
            .orElseThrow(() -> new ResourceNotFoundException("Legal entity not found: " + entityCode));
    }

    /**
     * Check if entity exists.
     */
    @Transactional(readOnly = true)
    public boolean entityExists(UUID entityId) {
        return legalEntityRepository.existsById(entityId);
    }

    /**
     * Get physical account count for an entity.
     * PhysicalAccount uses entityCode (denormalized), not a FK to LegalEntity.
     */
    @Transactional(readOnly = true)
    public int getPhysicalAccountCount(UUID entityId) {
        // Get the entity code for this entity
        LegalEntity entity = legalEntityRepository.findById(entityId).orElse(null);
        if (entity == null || entity.getEntityCode() == null) {
            return 0;
        }
        
        // Count physical accounts by entity code
        String entityCode = entity.getEntityCode();
        return (int) physicalAccountRepository.findAll().stream()
            .filter(pa -> entityCode.equals(pa.getEntityCode()))
            .count();
    }

    /**
     * Get shadow account count for an entity.
     */
    @Transactional(readOnly = true)
    public int getShadowAccountCount(UUID entityId) {
        return virtualAccountRepository.countByOwningEntityIdAndAccountCategory(
            entityId, AccountCategory.PHYSICAL_MIRROR
        );
    }

    // ========================================================================
    // UPDATE OPERATIONS
    // ========================================================================

    /**
     * Update a legal entity.
     */
    @Transactional
    public LegalEntity updateEntity(UUID entityId, UpdateEntityRequest request) {
        log.info("Updating legal entity: {}", entityId);

        LegalEntity entity = getEntity(entityId);

        // ========================================================================
        // Basic Info
        // ========================================================================
        if (StringUtils.hasText(request.getEntityName())) {
            entity.setEntityName(request.getEntityName());
        }
        if (request.getShortName() != null) {
            entity.setShortName(request.getShortName());
        }
        if (StringUtils.hasText(request.getFunctionalCurrency())) {
            entity.setFunctionalCurrency(request.getFunctionalCurrency().toUpperCase());
        }
        if (request.getReportingCurrency() != null) {
            entity.setReportingCurrency(request.getReportingCurrency().toUpperCase());
        }
        if (request.getEntityType() != null) {
            entity.setEntityType(parseEntityType(request.getEntityType()));
        }
        if (request.getLegalForm() != null) {
            entity.setLegalForm(request.getLegalForm());
        }
        if (request.getOwnershipPercent() != null) {
            entity.setOwnershipPercent(request.getOwnershipPercent());
        }
        if (request.getConsolidationMethod() != null) {
            entity.setConsolidationMethod(parseConsolidationMethod(request.getConsolidationMethod()));
        }
        if (request.getStatus() != null) {
            entity.setStatus(parseEntityStatus(request.getStatus()));
        }

        // ========================================================================
        // Jurisdiction
        // ========================================================================
        if (request.getCountryCode() != null) {
            entity.setCountryCode(request.getCountryCode().toUpperCase());
        }
        if (request.getJurisdiction() != null) {
            entity.setJurisdiction(request.getJurisdiction());
        }
        if (request.getTaxId() != null) {
            entity.setTaxId(request.getTaxId());
        }
        if (request.getRegistrationNumber() != null) {
            entity.setRegistrationNumber(request.getRegistrationNumber());
        }

        // ========================================================================
        // Treasury Configuration
        // ========================================================================
        if (request.getIsTreasuryCenter() != null) {
            entity.setIsTreasuryCenter(request.getIsTreasuryCenter());
            // Auto-link: When enabling Treasury Center, auto-enable IHB and canLend
            if (Boolean.TRUE.equals(request.getIsTreasuryCenter())) {
                log.info("Auto-enabling IHB and canLend for Treasury Center: {}", entity.getEntityCode());
                entity.setIhbEnabled(true);
                entity.setCanLend(true);
            }
        }
        if (request.getCanHoldPhysicalAccounts() != null) {
            entity.setCanHoldPhysicalAccounts(request.getCanHoldPhysicalAccounts());
        }
        if (request.getCanParticipatePooling() != null) {
            entity.setCanParticipatePooling(request.getCanParticipatePooling());
        }
        if (request.getCanParticipateNetting() != null) {
            entity.setCanParticipateNetting(request.getCanParticipateNetting());
        }
        if (request.getInternalCreditLimit() != null) {
            entity.setInternalCreditLimit(request.getInternalCreditLimit());
        }
        if (request.getInternalLimitCurrency() != null) {
            entity.setInternalLimitCurrency(request.getInternalLimitCurrency());
        }
        if (request.getLimitWarningThreshold() != null) {
            entity.setLimitWarningThreshold(request.getLimitWarningThreshold());
        }

        // ========================================================================
        // Bank Relationship
        // ========================================================================
        if (request.getIsBankCustomer() != null) {
            entity.setIsBankCustomer(request.getIsBankCustomer());
        }
        if (request.getBancsCustomerId() != null) {
            entity.setBancsCustomerId(request.getBancsCustomerId());
        }

        // ========================================================================
        // Contact Information
        // ========================================================================
        if (request.getContactEmail() != null) {
            entity.setContactEmail(request.getContactEmail());
        }
        if (request.getContactPhone() != null) {
            entity.setContactPhone(request.getContactPhone());
        }
        if (request.getRegisteredAddress() != null) {
            entity.setRegisteredAddress(request.getRegisteredAddress());
        }

        // ========================================================================
        // IHB Configuration
        // ========================================================================
        if (request.getIhbEnabled() != null) {
            entity.setIhbEnabled(request.getIhbEnabled());
        }
        if (request.getIhbCreditLimit() != null) {
            entity.setIhbCreditLimit(request.getIhbCreditLimit());
        }
        if (request.getIhbCurrency() != null) {
            entity.setIhbCurrency(request.getIhbCurrency());
        }
        if (request.getCanLend() != null) {
            entity.setCanLend(request.getCanLend());
        }
        if (request.getCanBorrow() != null) {
            entity.setCanBorrow(request.getCanBorrow());
        }
        if (request.getLendingRateSpread() != null) {
            entity.setLendingRateSpread(request.getLendingRateSpread());
        }
        if (request.getBorrowingRateSpread() != null) {
            entity.setBorrowingRateSpread(request.getBorrowingRateSpread());
        }

        // ========================================================================
        // Validity Dates
        // ========================================================================
        if (request.getEffectiveFrom() != null) {
            entity.setEffectiveFrom(request.getEffectiveFrom());
        }
        if (request.getEffectiveTo() != null) {
            entity.setEffectiveTo(request.getEffectiveTo());
        }

        // ========================================================================
        // Parent change (re-parenting)
        // ========================================================================
        if (request.getParentEntityId() != null) {
            // Only change if different from current parent
            if (!request.getParentEntityId().equals(entity.getParentEntityId())) {
                log.info("Re-parenting entity {} from {} to {}",
                    entity.getEntityCode(), entity.getParentEntityId(), request.getParentEntityId());
                // Delegate to moveEntity for proper hierarchy handling
                return moveEntity(entityId, request.getParentEntityId());
            }
        }

        entity = legalEntityRepository.save(entity);

        log.info("Updated legal entity: {}", entity.getEntityCode());

        return entity;
    }

    /**
     * Parse entity status from string.
     */
    private EntityStatus parseEntityStatus(String status) {
        if (status == null) return null;
        try {
            return EntityStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown entity status: {}", status);
            return null;
        }
    }

    /**
     * Move entity to a new parent.
     */
    @Transactional
    public LegalEntity moveEntity(UUID entityId, UUID newParentId) {
        log.info("Moving entity {} to parent {}", entityId, newParentId);
        
        LegalEntity entity = getEntity(entityId);
        LegalEntity newParent = getEntity(newParentId);
        
        // Validate move
        if (!entity.getCorporateId().equals(newParent.getCorporateId())) {
            throw new BusinessException("Cannot move entity to a different corporate");
        }
        if (entityId.equals(newParentId)) {
            throw new BusinessException("Cannot move entity to itself");
        }
        
        // Check for circular reference
        List<LegalEntity> newParentAncestors = getAncestors(newParentId);
        if (newParentAncestors.stream().anyMatch(a -> a.getId().equals(entityId))) {
            throw new BusinessException("Cannot move entity to its own descendant");
        }
        
        String oldPath = entity.getHierarchyPath();
        String newPath = newParent.getHierarchyPath() + "/" + entity.getEntityCode();
        int newLevel = newParent.getHierarchyLevel() + 1;
        
        // Update entity
        entity.setParentEntityId(newParentId);
        entity.setHierarchyPath(newPath);
        entity.setHierarchyLevel(newLevel);
        entity = legalEntityRepository.save(entity);
        
        // Update all descendants' paths
        updateDescendantPaths(entityId, oldPath, newPath, newLevel);
        
        log.info("Moved entity {} from {} to {}", entity.getEntityCode(), oldPath, newPath);
        
        return entity;
    }

    /**
     * Set entity as treasury center.
     */
    @Transactional
    public LegalEntity setTreasuryCenter(UUID entityId, boolean isTreasuryCenter) {
        LegalEntity entity = getEntity(entityId);
        entity.setIsTreasuryCenter(isTreasuryCenter);
        return legalEntityRepository.save(entity);
    }

    /**
     * Update entity status.
     */
    @Transactional
    public LegalEntity updateStatus(UUID entityId, EntityStatus status) {
        log.info("Updating entity {} status to {}", entityId, status);
        
        LegalEntity entity = getEntity(entityId);
        
        // If deactivating, check for active children
        if (status == EntityStatus.INACTIVE) {
            List<LegalEntity> children = getChildEntities(entityId);
            List<LegalEntity> activeChildren = children.stream()
                .filter(c -> c.getStatus() == EntityStatus.ACTIVE)
                .collect(Collectors.toList());
            if (!activeChildren.isEmpty()) {
                throw new BusinessException("Cannot deactivate entity with active children");
            }
        }
        
        entity.setStatus(status);
        return legalEntityRepository.save(entity);
    }

    // ========================================================================
    // DELETE OPERATIONS
    // ========================================================================

    /**
     * Delete a legal entity.
     */
    @Transactional
    public void deleteEntity(UUID entityId) {
        log.info("Deleting legal entity: {}", entityId);
        
        LegalEntity entity = getEntity(entityId);
        
        // Check for children
        if (legalEntityRepository.existsByParentEntityId(entityId)) {
            throw new BusinessException("Cannot delete entity with children. Delete children first or move them.");
        }
        
        // Check for linked accounts
        int accountCount = getPhysicalAccountCount(entityId);
        if (accountCount > 0) {
            throw new BusinessException("Cannot delete entity with " + accountCount + " linked physical accounts");
        }
        
        int shadowCount = getShadowAccountCount(entityId);
        if (shadowCount > 0) {
            throw new BusinessException("Cannot delete entity with " + shadowCount + " shadow accounts");
        }
        
        legalEntityRepository.delete(entity);
        
        log.info("Deleted legal entity: {}", entity.getEntityCode());
    }

    // ========================================================================
    // SEARCH OPERATIONS
    // ========================================================================

    /**
     * Search legal entities.
     */
    @Transactional(readOnly = true)
    public List<LegalEntity> searchEntities(UUID corporateId, String query, 
                                             String countryCode, EntityType entityType,
                                             EntityStatus status) {
        
        // Start with all entities for corporate
        List<LegalEntity> entities = getEntitiesByCorporate(corporateId);
        
        // Apply filters
        return entities.stream()
            .filter(e -> {
                // Query filter (name or code)
                if (StringUtils.hasText(query)) {
                    String q = query.toLowerCase();
                    boolean matches = e.getEntityCode().toLowerCase().contains(q) ||
                                     e.getEntityName().toLowerCase().contains(q) ||
                                     (e.getShortName() != null && e.getShortName().toLowerCase().contains(q));
                    if (!matches) return false;
                }
                
                // Country filter
                if (StringUtils.hasText(countryCode)) {
                    if (!countryCode.equalsIgnoreCase(e.getCountryCode())) return false;
                }
                
                // Entity type filter
                if (entityType != null) {
                    if (e.getEntityType() != entityType) return false;
                }
                
                // Status filter
                if (status != null) {
                    if (e.getStatus() != status) return false;
                }
                
                return true;
            })
            .collect(Collectors.toList());
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    /**
     * Parse entity type from string.
     */
    private EntityType parseEntityType(String type) {
        if (type == null) return EntityType.SUBSIDIARY;
        
        try {
            return EntityType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return EntityType.SUBSIDIARY;
        }
    }

    /**
     * Parse consolidation method from string.
     */
    private ConsolidationMethod parseConsolidationMethod(String method) {
        if (method == null) return ConsolidationMethod.FULL;
        
        try {
            return ConsolidationMethod.valueOf(method.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ConsolidationMethod.FULL;
        }
    }

    /**
     * Update hierarchy paths for all descendants after a move.
     */
    private void updateDescendantPaths(UUID entityId, String oldPath, String newPath, int parentLevel) {
        // Use existing findDescendants method
        List<LegalEntity> descendants = legalEntityRepository.findDescendants(oldPath);
        
        for (LegalEntity descendant : descendants) {
            String updatedPath = descendant.getHierarchyPath().replace(oldPath, newPath);
            int levelDiff = parentLevel - (countSlashes(oldPath) - 1);
            
            descendant.setHierarchyPath(updatedPath);
            descendant.setHierarchyLevel(descendant.getHierarchyLevel() + levelDiff);
            legalEntityRepository.save(descendant);
        }
    }

    private int countSlashes(String path) {
        return (int) path.chars().filter(ch -> ch == '/').count();
    }

    // ========================================================================
    // CREDIT LIMIT SYNC METHODS (Called by CreditLimitService)
    // ========================================================================

    /**
     * Sync internal credit limit from CreditLimit entity to LegalEntity.
     * Called when an internal limit is created or updated for an entity.
     * 
     * @param entityId The legal entity ID
     * @param limitAmount The new limit amount
     * @param currency The limit currency
     */
    @Transactional
    public void syncLimitFromCreditLimit(UUID entityId, BigDecimal limitAmount, String currency) {
        log.debug("Syncing credit limit to entity {}: {} {}", entityId, limitAmount, currency);
        
        LegalEntity entity = legalEntityRepository.findById(entityId).orElse(null);
        if (entity == null) {
            log.warn("Entity not found for credit limit sync: {}", entityId);
            return;
        }
        
        entity.setInternalCreditLimit(limitAmount);
        entity.setInternalLimitCurrency(currency);
        
        // If utilization not set, initialize to zero
        if (entity.getInternalLimitUtilized() == null) {
            entity.setInternalLimitUtilized(BigDecimal.ZERO);
        }
        
        legalEntityRepository.save(entity);
        
        log.info("Synced credit limit {} {} to entity {}", limitAmount, currency, entity.getEntityCode());
    }

    /**
     * Sync internal limit utilization from CreditLimit entity to LegalEntity.
     * Called when credit is utilized or released for an entity.
     * 
     * @param entityId The legal entity ID
     * @param utilizedAmount The new utilized amount
     */
    @Transactional
    public void syncUtilizationFromCreditLimit(UUID entityId, BigDecimal utilizedAmount) {
        log.debug("Syncing credit utilization to entity {}: {}", entityId, utilizedAmount);
        
        LegalEntity entity = legalEntityRepository.findById(entityId).orElse(null);
        if (entity == null) {
            log.warn("Entity not found for utilization sync: {}", entityId);
            return;
        }
        
        entity.setInternalLimitUtilized(utilizedAmount != null ? utilizedAmount : BigDecimal.ZERO);
        legalEntityRepository.save(entity);
        
        log.debug("Synced utilization {} to entity {}", utilizedAmount, entity.getEntityCode());
    }

    /**
     * Clear internal credit limit from LegalEntity.
     * Called when an internal limit is deleted for an entity.
     * 
     * @param entityId The legal entity ID
     */
    @Transactional
    public void clearLimitFromCreditLimit(UUID entityId) {
        log.debug("Clearing credit limit from entity {}", entityId);
        
        LegalEntity entity = legalEntityRepository.findById(entityId).orElse(null);
        if (entity == null) {
            log.warn("Entity not found for credit limit clear: {}", entityId);
            return;
        }
        
        entity.setInternalCreditLimit(null);
        entity.setInternalLimitUtilized(null);
        
        legalEntityRepository.save(entity);
        
        log.info("Cleared credit limit from entity {}", entity.getEntityCode());
    }

    /**
     * Get available internal credit for an entity.
     * 
     * @param entityId The legal entity ID
     * @return Available credit amount, or null if no limit set
     */
    @Transactional(readOnly = true)
    public BigDecimal getAvailableInternalCredit(UUID entityId) {
        LegalEntity entity = legalEntityRepository.findById(entityId).orElse(null);
        if (entity == null || entity.getInternalCreditLimit() == null) {
            return null;
        }
        
        BigDecimal limit = entity.getInternalCreditLimit();
        BigDecimal utilized = entity.getInternalLimitUtilized() != null 
            ? entity.getInternalLimitUtilized() 
            : BigDecimal.ZERO;
        
        return limit.subtract(utilized);
    }

    /**
     * Check if entity has internal credit limit set.
     * 
     * @param entityId The legal entity ID
     * @return true if internal limit exists
     */
    @Transactional(readOnly = true)
    public boolean hasInternalCreditLimit(UUID entityId) {
        LegalEntity entity = legalEntityRepository.findById(entityId).orElse(null);
        return entity != null && entity.getInternalCreditLimit() != null 
            && entity.getInternalCreditLimit().compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * Get credit utilization percentage for an entity.
     * 
     * @param entityId The legal entity ID
     * @return Utilization percentage (0-100+), or null if no limit
     */
    @Transactional(readOnly = true)
    public BigDecimal getCreditUtilizationPercent(UUID entityId) {
        LegalEntity entity = legalEntityRepository.findById(entityId).orElse(null);
        if (entity == null || entity.getInternalCreditLimit() == null 
            || entity.getInternalCreditLimit().compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        
        BigDecimal utilized = entity.getInternalLimitUtilized() != null 
            ? entity.getInternalLimitUtilized() 
            : BigDecimal.ZERO;
        
        return utilized.multiply(new BigDecimal("100"))
            .divide(entity.getInternalCreditLimit(), 2, java.math.RoundingMode.HALF_UP);
    }
}