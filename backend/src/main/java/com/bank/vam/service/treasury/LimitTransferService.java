package com.bank.vam.service.treasury;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.entity.credit.CreditLimit;
import com.bank.vam.entity.credit.CreditLimit.LimitStatus;
import com.bank.vam.entity.credit.CreditLimit.LimitType;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.credit.CreditLimitRepository;
import com.bank.vam.service.credit.CreditLimitService;
import com.bank.vam.service.treasury.HierarchyOperationDtos.LimitAdjustment;
import com.bank.vam.service.treasury.HierarchyOperationDtos.LimitTransferResult;
import com.bank.vam.service.treasury.HierarchyOperationDtos.MergeLimitPolicy;
import com.bank.vam.service.treasury.HierarchyOperationDtos.MergeLimitTransferResult;
import com.bank.vam.service.treasury.HierarchyOperationDtos.MergeLimitValidationResult;
import com.bank.vam.service.treasury.HierarchyOperationDtos.MoveLimitPolicy;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * LimitTransferService - Handles credit limit transfers during hierarchy operations.
 * 
 * FIXED v5.0.1: Corrected imports and references to work with existing codebase.
 * 
 * MOVE POLICIES:
 * - STRICT: Block if target has insufficient headroom
 * - TRANSFER_WITH_VA: Move limit allocation with the VA
 * - ABSORB_INTO_TARGET: Target absorbs utilization without limit transfer
 * - REQUIRE_APPROVAL: Stage for CFO approval
 * 
 * MERGE POLICIES:
 * - COMBINE_LIMITS: Add both group limits together
 * - RESET_TARGET_LIMITS: Cancel all target limits
 * - PRESERVE_TARGET_STRUCTURE: Keep target hierarchy intact
 * 
 * @version 5.0.1
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LimitTransferService {

    private final CreditLimitRepository limitRepository;
    private final VirtualAccountRepository vaRepository;
    private final CreditLimitService creditLimitService;

    // ════════════════════════════════════════════════════════════════════════════════
    // MOVE LIMIT VALIDATION
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * Validate limit transfer for a move operation.
     */
    @Transactional(readOnly = true)
    public LimitValidationResult validateMoveLimit(
            UUID vaId,
            UUID oldParentId,
            UUID newParentId,
            MoveLimitPolicy policy) {
        
        log.info("Validating move limit: va={}, oldParent={}, newParent={}, policy={}",
            vaId, oldParentId, newParentId, policy);

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        VirtualAccount va = vaRepository.findById(vaId).orElse(null);
        if (va == null) {
            errors.add("VA not found: " + vaId);
            return LimitValidationResult.builder()
                .valid(false)
                .errors(errors)
                .warnings(warnings)
                .build();
        }

        // Get VA's current balance (utilization that will move)
        BigDecimal movingUtilization = va.getCurrentBalance() != null ? 
            va.getCurrentBalance() : BigDecimal.ZERO;

        // Get subtree allocated limit for aggregations
        BigDecimal subtreeAllocatedLimit = BigDecimal.ZERO;
        if (va.getAccountCategory() == AccountCategory.AGGREGATION) {
            subtreeAllocatedLimit = calculateSubtreeAllocatedLimit(vaId);
        }

        // Get source limit info
        LimitInfo sourceLimit = getLimitInfo(oldParentId);
        
        // Get target limit info
        LimitInfo targetLimit = getLimitInfo(newParentId);

        // Validate based on policy
        boolean requiresApproval = false;
        
        switch (policy) {
            case STRICT:
                if (targetLimit.isHasLimit() && targetLimit.isHardLimit()) {
                    BigDecimal availableHeadroom = targetLimit.getAvailableAmount();
                    if (movingUtilization.compareTo(availableHeadroom) > 0) {
                        errors.add(String.format(
                            "Target has insufficient limit headroom. Required: %s, Available: %s",
                            movingUtilization, availableHeadroom));
                    }
                }
                break;
                
            case TRANSFER_WITH_VA:
                if (sourceLimit.isHasLimit()) {
                    BigDecimal unallocated = sourceLimit.getUnallocatedAmount();
                    if (subtreeAllocatedLimit.compareTo(unallocated) > 0) {
                        warnings.add("Subtree allocated limit exceeds source unallocated. Limit will be reduced.");
                    }
                }
                break;
                
            case ABSORB_INTO_TARGET:
                if (targetLimit.isHasLimit() && targetLimit.isHardLimit()) {
                    BigDecimal newUtilization = targetLimit.getUtilizedAmount().add(movingUtilization);
                    if (newUtilization.compareTo(targetLimit.getLimitAmount()) > 0) {
                        warnings.add("Target limit will be exceeded after absorbing utilization.");
                    }
                }
                break;
                
            case REQUIRE_APPROVAL:
                requiresApproval = true;
                warnings.add("Move requires CFO approval before execution.");
                break;
        }

        return LimitValidationResult.builder()
            .valid(errors.isEmpty())
            .errors(errors)
            .warnings(warnings)
            .movingUtilization(movingUtilization)
            .subtreeAllocatedLimit(subtreeAllocatedLimit)
            .sourceLimit(sourceLimit)
            .targetLimit(targetLimit)
            .requiresApproval(requiresApproval)
            .build();
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // MOVE LIMIT EXECUTION
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * Execute limit transfer after a move operation.
     */
    @Transactional
    public LimitTransferResult executeMoveLimitTransfer(
            UUID vaId,
            UUID oldParentId,
            UUID newParentId,
            MoveLimitPolicy policy,
            String approvedBy) {
        
        log.info("Executing move limit transfer: va={}, policy={}", vaId, policy);

        List<LimitAdjustment> adjustments = new ArrayList<>();
        
        VirtualAccount va = vaRepository.findById(vaId)
            .orElseThrow(() -> new BusinessException("VA not found: " + vaId));

        BigDecimal movingUtilization = va.getCurrentBalance() != null ? 
            va.getCurrentBalance() : BigDecimal.ZERO;

        switch (policy) {
            case STRICT:
                // No limit adjustments - just validate
                log.info("STRICT policy: No limit adjustments needed");
                break;
                
            case TRANSFER_WITH_VA:
                adjustments.addAll(transferLimitWithVa(va, oldParentId, newParentId));
                break;
                
            case ABSORB_INTO_TARGET:
                adjustments.addAll(absorbIntoTarget(va, newParentId));
                break;
                
            case REQUIRE_APPROVAL:
                // Create pending approval record (implementation depends on approval workflow)
                log.info("REQUIRE_APPROVAL: Creating approval request");
                break;
        }

        return LimitTransferResult.builder()
            .success(true)
            .policy(policy)
            .movingUtilization(movingUtilization)
            .adjustments(adjustments)
            .completedAt(LocalDateTime.now())
            .approvedBy(approvedBy)
            .build();
    }

    /**
     * Transfer limit allocation with the VA.
     */
    private List<LimitAdjustment> transferLimitWithVa(
            VirtualAccount va,
            UUID oldParentId,
            UUID newParentId) {
        
        List<LimitAdjustment> adjustments = new ArrayList<>();
        
        // Find limit for the VA
        Optional<CreditLimit> vaLimitOpt = limitRepository.findByTargetIdAndLimitTypeAndStatus(
            va.getId(), LimitType.INTERNAL, LimitStatus.ACTIVE);
        
        if (vaLimitOpt.isEmpty()) {
            log.debug("No active internal limit for VA {}", va.getVaNumber());
            return adjustments;
        }
        
        CreditLimit vaLimit = vaLimitOpt.get();
        BigDecimal limitAmount = vaLimit.getLimitAmount();

        // Get source parent limit
        Optional<CreditLimit> sourceLimitOpt = findParentLimit(oldParentId);
        if (sourceLimitOpt.isPresent()) {
            CreditLimit sourceLimit = sourceLimitOpt.get();
            
            // Release allocation from source
            sourceLimit.releaseChildAllocation(limitAmount);
            limitRepository.save(sourceLimit);
            
            adjustments.add(LimitAdjustment.builder()
                .targetId(sourceLimit.getTargetId())
                .adjustmentType("RELEASE_ALLOCATION")
                .oldAmount(sourceLimit.getAllocatedToChildren().add(limitAmount))
                .newAmount(sourceLimit.getAllocatedToChildren())
                .description("Released allocation for moved VA")
                .build());
        }

        // Get target parent limit
        Optional<CreditLimit> targetLimitOpt = findParentLimit(newParentId);
        if (targetLimitOpt.isPresent()) {
            CreditLimit targetLimit = targetLimitOpt.get();
            
            // Allocate to target
            targetLimit.allocateToChild(limitAmount);
            limitRepository.save(targetLimit);
            
            // Re-parent the VA's limit
            vaLimit.setParentLimitId(targetLimit.getId());
            limitRepository.save(vaLimit);
            
            adjustments.add(LimitAdjustment.builder()
                .targetId(targetLimit.getTargetId())
                .adjustmentType("ADD_ALLOCATION")
                .oldAmount(targetLimit.getAllocatedToChildren().subtract(limitAmount))
                .newAmount(targetLimit.getAllocatedToChildren())
                .description("Allocated limit for moved VA")
                .build());
        }

        return adjustments;
    }

    /**
     * Absorb utilization into target without limit transfer.
     */
    private List<LimitAdjustment> absorbIntoTarget(VirtualAccount va, UUID newParentId) {
        List<LimitAdjustment> adjustments = new ArrayList<>();
        
        BigDecimal utilization = va.getCurrentBalance() != null ? 
            va.getCurrentBalance() : BigDecimal.ZERO;
        
        if (utilization.compareTo(BigDecimal.ZERO) <= 0) {
            return adjustments;
        }

        // Get target parent limit
        Optional<CreditLimit> targetLimitOpt = findParentLimit(newParentId);
        if (targetLimitOpt.isPresent()) {
            CreditLimit targetLimit = targetLimitOpt.get();
            
            // Add utilization to target (don't adjust limit amount)
            BigDecimal oldUtilized = targetLimit.getUtilizedAmount();
            targetLimit.utilize(utilization);
            limitRepository.save(targetLimit);
            
            adjustments.add(LimitAdjustment.builder()
                .targetId(targetLimit.getTargetId())
                .adjustmentType("ABSORB_UTILIZATION")
                .oldAmount(oldUtilized)
                .newAmount(targetLimit.getUtilizedAmount())
                .description("Absorbed utilization from moved VA")
                .build());
        }

        return adjustments;
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // MERGE LIMIT VALIDATION
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * Validate limit handling for a merge/acquisition operation.
     */
    @Transactional(readOnly = true)
    public MergeLimitValidationResult validateMergeLimits(
            UUID acquirerCorporateId,
            UUID targetCorporateId,
            MergeLimitPolicy policy) {
        
        log.info("Validating merge limits: acquirer={}, target={}, policy={}",
            acquirerCorporateId, targetCorporateId, policy);

        List<String> warnings = new ArrayList<>();
        List<String> plannedActions = new ArrayList<>();

        // Get acquirer group limit
        Optional<CreditLimit> acquirerGroupOpt = creditLimitService.getGroupLimit(acquirerCorporateId);
        BigDecimal acquirerGroupLimit = acquirerGroupOpt
            .map(CreditLimit::getLimitAmount)
            .orElse(BigDecimal.ZERO);
        
        // Get target group limit
        Optional<CreditLimit> targetGroupOpt = creditLimitService.getGroupLimit(targetCorporateId);
        BigDecimal targetGroupLimit = targetGroupOpt
            .map(CreditLimit::getLimitAmount)
            .orElse(BigDecimal.ZERO);

        // Get external limits
        BigDecimal acquirerExternalCeiling = creditLimitService.getTotalExternalLimit(
            acquirerCorporateId, null);
        BigDecimal targetExternalCeiling = creditLimitService.getTotalExternalLimit(
            targetCorporateId, null);

        // Count target limits
        long targetLimitCount = limitRepository.countByCorporateIdAndStatus(
            targetCorporateId, LimitStatus.ACTIVE);

        // Calculate combined values
        BigDecimal combinedGroupLimit = acquirerGroupLimit.add(targetGroupLimit);
        BigDecimal combinedExternalCeiling = acquirerExternalCeiling.add(targetExternalCeiling);

        // Validate and plan actions based on policy
        switch (policy) {
            case COMBINE_LIMITS:
                plannedActions.add("Combine acquirer and target group limits");
                plannedActions.add("Re-parent target limits under acquirer group");
                if (combinedGroupLimit.compareTo(acquirerExternalCeiling) > 0) {
                    warnings.add("Combined group limit exceeds acquirer's external ceiling");
                }
                break;
                
            case RESET_TARGET_LIMITS:
                plannedActions.add("Cancel all target entity limits");
                plannedActions.add("CFO must reallocate from acquirer's pool");
                if (targetLimitCount > 0) {
                    warnings.add(String.format("%d target limits will be cancelled", targetLimitCount));
                }
                break;
                
            case PRESERVE_TARGET_STRUCTURE:
                plannedActions.add("Preserve target limit hierarchy as sub-structure");
                plannedActions.add("Target group becomes sub-group under acquirer");
                break;
        }

        // Determine recommended policy
        MergeLimitPolicy recommendedPolicy = policy;
        if (targetLimitCount == 0) {
            recommendedPolicy = MergeLimitPolicy.COMBINE_LIMITS;
        } else if (targetLimitCount > 10) {
            recommendedPolicy = MergeLimitPolicy.PRESERVE_TARGET_STRUCTURE;
        }

        return MergeLimitValidationResult.builder()
            .valid(true)
            .warnings(warnings)
            .plannedActions(plannedActions)
            .acquirerGroupLimit(acquirerGroupLimit)
            .targetGroupLimit(targetGroupLimit)
            .combinedGroupLimit(combinedGroupLimit)
            .acquirerExternalCeiling(acquirerExternalCeiling)
            .targetExternalCeiling(targetExternalCeiling)
            .combinedExternalCeiling(combinedExternalCeiling)
            .targetLimitCount((int) targetLimitCount)
            .recommendedPolicy(recommendedPolicy)
            .build();
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // MERGE LIMIT EXECUTION
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * Execute limit transfer for a merge/acquisition operation.
     */
    @Transactional
    public MergeLimitTransferResult executeMergeLimitTransfer(
            UUID acquirerCorporateId,
            UUID targetCorporateId,
            MergeLimitPolicy policy,
            String approvedBy) {
        
        log.info("Executing merge limit transfer: acquirer={}, target={}, policy={}",
            acquirerCorporateId, targetCorporateId, policy);

        List<LimitAdjustment> adjustments = new ArrayList<>();

        switch (policy) {
            case COMBINE_LIMITS:
                adjustments.addAll(combineLimits(acquirerCorporateId, targetCorporateId));
                break;
                
            case RESET_TARGET_LIMITS:
                adjustments.addAll(resetTargetLimits(targetCorporateId));
                break;
                
            case PRESERVE_TARGET_STRUCTURE:
                adjustments.addAll(preserveTargetStructure(acquirerCorporateId, targetCorporateId));
                break;
        }

        return MergeLimitTransferResult.builder()
            .success(true)
            .policy(policy)
            .adjustments(adjustments)
            .completedAt(LocalDateTime.now())
            .approvedBy(approvedBy)
            .build();
    }

    /**
     * Combine limits - add target group limit to acquirer.
     */
    private List<LimitAdjustment> combineLimits(UUID acquirerCorporateId, UUID targetCorporateId) {
        List<LimitAdjustment> adjustments = new ArrayList<>();

        Optional<CreditLimit> acquirerGroupOpt = creditLimitService.getGroupLimit(acquirerCorporateId);
        Optional<CreditLimit> targetGroupOpt = creditLimitService.getGroupLimit(targetCorporateId);

        if (acquirerGroupOpt.isPresent() && targetGroupOpt.isPresent()) {
            CreditLimit acquirerGroup = acquirerGroupOpt.get();
            CreditLimit targetGroup = targetGroupOpt.get();
            
            BigDecimal oldAmount = acquirerGroup.getLimitAmount();
            BigDecimal newAmount = oldAmount.add(targetGroup.getLimitAmount());
            
            // Update acquirer group limit
            acquirerGroup.setLimitAmount(newAmount);
            acquirerGroup.recalculateAvailable();
            limitRepository.save(acquirerGroup);
            
            adjustments.add(LimitAdjustment.builder()
                .targetId(acquirerCorporateId)
                .adjustmentType("INCREASE_GROUP_LIMIT")
                .oldAmount(oldAmount)
                .newAmount(newAmount)
                .description("Combined with target group limit")
                .build());
            
            // Cancel target group limit
            targetGroup.setStatus(LimitStatus.CANCELLED);
            limitRepository.save(targetGroup);
            
            adjustments.add(LimitAdjustment.builder()
                .targetId(targetCorporateId)
                .adjustmentType("CANCEL_GROUP_LIMIT")
                .oldAmount(targetGroup.getLimitAmount())
                .newAmount(BigDecimal.ZERO)
                .description("Cancelled after merge")
                .build());
            
            // Re-parent target entity limits
            List<CreditLimit> targetEntityLimits = limitRepository.findByCorporateIdAndStatus(
                targetCorporateId, LimitStatus.ACTIVE);
            
            for (CreditLimit entityLimit : targetEntityLimits) {
                if (entityLimit.getParentLimitId() != null && 
                    entityLimit.getParentLimitId().equals(targetGroup.getId())) {
                    entityLimit.setParentLimitId(acquirerGroup.getId());
                    entityLimit.setCorporateId(acquirerCorporateId);
                    limitRepository.save(entityLimit);
                    
                    adjustments.add(LimitAdjustment.builder()
                        .targetId(entityLimit.getTargetId())
                        .adjustmentType("REPARENT_LIMIT")
                        .oldParentLimitId(targetGroup.getId())
                        .newParentLimitId(acquirerGroup.getId())
                        .description("Re-parented to acquirer group")
                        .build());
                }
            }
        }

        return adjustments;
    }

    /**
     * Reset target limits - cancel all and CFO reallocates.
     */
    private List<LimitAdjustment> resetTargetLimits(UUID targetCorporateId) {
        List<LimitAdjustment> adjustments = new ArrayList<>();

        List<CreditLimit> targetLimits = limitRepository.findByCorporateIdAndStatus(
            targetCorporateId, LimitStatus.ACTIVE);
        
        for (CreditLimit limit : targetLimits) {
            limit.setStatus(LimitStatus.CANCELLED);
            limit.setNotes("Cancelled during M&A - RESET_TARGET_LIMITS policy");
            limitRepository.save(limit);
            
            adjustments.add(LimitAdjustment.builder()
                .targetId(limit.getTargetId())
                .adjustmentType("CANCEL_LIMIT")
                .oldAmount(limit.getLimitAmount())
                .newAmount(BigDecimal.ZERO)
                .description("Cancelled by RESET_TARGET_LIMITS policy")
                .build());
        }

        return adjustments;
    }

    /**
     * Preserve target structure - target becomes sub-group.
     */
    private List<LimitAdjustment> preserveTargetStructure(
            UUID acquirerCorporateId, 
            UUID targetCorporateId) {
        List<LimitAdjustment> adjustments = new ArrayList<>();

        Optional<CreditLimit> acquirerGroupOpt = creditLimitService.getGroupLimit(acquirerCorporateId);
        Optional<CreditLimit> targetGroupOpt = creditLimitService.getGroupLimit(targetCorporateId);

        if (acquirerGroupOpt.isPresent() && targetGroupOpt.isPresent()) {
            CreditLimit acquirerGroup = acquirerGroupOpt.get();
            CreditLimit targetGroup = targetGroupOpt.get();
            
            // Make target group a child of acquirer group
            targetGroup.setParentLimitId(acquirerGroup.getId());
            targetGroup.setCorporateId(acquirerCorporateId);
            limitRepository.save(targetGroup);
            
            // Allocate target's limit from acquirer
            acquirerGroup.allocateToChild(targetGroup.getLimitAmount());
            limitRepository.save(acquirerGroup);
            
            adjustments.add(LimitAdjustment.builder()
                .targetId(targetCorporateId)
                .adjustmentType("NEST_AS_SUBGROUP")
                .oldParentLimitId(null)
                .newParentLimitId(acquirerGroup.getId())
                .description("Nested as sub-group under acquirer")
                .build());
            
            // Update corporate ID for all target limits
            List<CreditLimit> targetLimits = limitRepository.findByCorporateIdAndStatus(
                targetCorporateId, LimitStatus.ACTIVE);
            
            for (CreditLimit limit : targetLimits) {
                limit.setCorporateId(acquirerCorporateId);
                limitRepository.save(limit);
            }
        }

        return adjustments;
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * Get limit info for a VA or parent.
     */
    private LimitInfo getLimitInfo(UUID targetId) {
        Optional<CreditLimit> limitOpt = limitRepository.findByTargetIdAndLimitTypeAndStatus(
            targetId, LimitType.INTERNAL, LimitStatus.ACTIVE);
        
        if (limitOpt.isEmpty()) {
            return LimitInfo.builder()
                .hasLimit(false)
                .targetId(targetId)
                .limitAmount(BigDecimal.ZERO)
                .utilizedAmount(BigDecimal.ZERO)
                .availableAmount(BigDecimal.ZERO)
                .allocatedToChildren(BigDecimal.ZERO)
                .unallocatedAmount(BigDecimal.ZERO)
                .build();
        }
        
        CreditLimit limit = limitOpt.get();
        return LimitInfo.builder()
            .hasLimit(true)
            .limitId(limit.getId())
            .targetId(targetId)
            .limitAmount(limit.getLimitAmount())
            .utilizedAmount(limit.getUtilizedAmount() != null ? limit.getUtilizedAmount() : BigDecimal.ZERO)
            .availableAmount(limit.getAvailableAmount() != null ? limit.getAvailableAmount() : BigDecimal.ZERO)
            .allocatedToChildren(limit.getAllocatedToChildren() != null ? limit.getAllocatedToChildren() : BigDecimal.ZERO)
            .unallocatedAmount(limit.getUnallocatedAmount())
            .hardLimit(Boolean.TRUE.equals(limit.getIsHardLimit()))
            .build();
    }

    /**
     * Find parent limit for a VA.
     */
    private Optional<CreditLimit> findParentLimit(UUID parentVaId) {
        VirtualAccount parentVa = vaRepository.findById(parentVaId).orElse(null);
        if (parentVa == null) {
            return Optional.empty();
        }
        
        return limitRepository.findByTargetIdAndLimitTypeAndStatus(
            parentVaId, LimitType.INTERNAL, LimitStatus.ACTIVE);
    }

    /**
     * Calculate total allocated limit in subtree.
     */
    private BigDecimal calculateSubtreeAllocatedLimit(UUID rootVaId) {
        List<VirtualAccount> children = vaRepository.findByParentAccountId(rootVaId);
        
        BigDecimal total = BigDecimal.ZERO;
        
        for (VirtualAccount child : children) {
            Optional<CreditLimit> childLimitOpt = limitRepository.findByTargetIdAndLimitTypeAndStatus(
                child.getId(), LimitType.INTERNAL, LimitStatus.ACTIVE);
            
            if (childLimitOpt.isPresent()) {
                total = total.add(childLimitOpt.get().getLimitAmount());
            }
            
            // Recurse
            total = total.add(calculateSubtreeAllocatedLimit(child.getId()));
        }
        
        return total;
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // INNER CLASSES
    // ════════════════════════════════════════════════════════════════════════════════

    @Data
    @Builder
    public static class LimitInfo {
        private boolean hasLimit;
        private UUID limitId;
        private UUID targetId;
        private BigDecimal limitAmount;
        private BigDecimal utilizedAmount;
        private BigDecimal availableAmount;
        private BigDecimal allocatedToChildren;
        private BigDecimal unallocatedAmount;
        private boolean hardLimit;
    }

    @Data
    @Builder
    public static class LimitValidationResult {
        private boolean valid;
        private List<String> errors;
        private List<String> warnings;
        private BigDecimal movingUtilization;
        private BigDecimal subtreeAllocatedLimit;
        private LimitInfo sourceLimit;
        private LimitInfo targetLimit;
        private boolean requiresApproval;
    }
}