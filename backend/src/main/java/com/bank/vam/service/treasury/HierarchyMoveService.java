package com.bank.vam.service.treasury;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.entity.VirtualAccount.VaStatus;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.service.treasury.HierarchyOperationDtos.LimitTransferResult;
import com.bank.vam.service.treasury.HierarchyOperationDtos.MoveValidationResult;
import com.bank.vam.service.treasury.HierarchyOperationDtos.MoveLimitPolicy;
import com.bank.vam.service.treasury.HierarchyOperationDtos.OperationType;
import com.bank.vam.service.treasury.LimitTransferService.LimitValidationResult;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * HierarchyMoveService - Handles VA and Aggregation moves within hierarchy.
 *
 * FIXED v5.0.1:
 * - Removed references to non-existent methods
 * - Uses existing HierarchyVaService and SettlementVaService methods
 * - Self-contained settlement resolution logic
 *
 * FIXED v5.1.1:
 * - Uses AccountCategory.SETTLEMENT instead of naming conventions (SETTLE- vs SETTLEMENT-)
 * - Reuses existing Settlement VAs at destination instead of creating duplicates
 * - Closes orphaned Settlement VAs at source location when no siblings remain
 * - Transfers balances from orphaned Settlement VAs before closing
 *
 * MOVE RULES:
 * - Transaction VAs can move to any AGGREGATION within same corporate
 * - AGGREGATIONs can move to ROOT or another AGGREGATION (not to descendant)
 * - ROOT, CURRENCY_MIRROR, SETTLEMENT, EXCEPTION cannot be moved directly
 *
 * @version 5.1.1
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HierarchyMoveService {

    private final VirtualAccountRepository vaRepository;
    private final HierarchyVaService hierarchyVaService;
    private final SettlementVaService settlementVaService;
    private final LimitTransferService limitTransferService;

    // ════════════════════════════════════════════════════════════════════════════════
    // MOVE TRANSACTION VA
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * Move a Transaction VA to a new AGGREGATION parent.
     */
    @Transactional
    public MoveResult moveTransactionVa(
            UUID vaId, 
            UUID newParentId, 
            MoveLimitPolicy limitPolicy,
            String approvedBy) {
        
        log.info("╔════════════════════════════════════════════════════════════════════════════════╗");
        log.info("║ MOVE TRANSACTION VA                                                           ║");
        log.info("║ VA: {} → New Parent: {}                                   ║", vaId, newParentId);
        log.info("╚════════════════════════════════════════════════════════════════════════════════╝");

        // 1. Validate
        MoveValidationResult validation = validateMove(vaId, newParentId, limitPolicy);
        if (!validation.isValid()) {
            return MoveResult.builder()
                .success(false)
                .message("Validation failed: " + String.join(", ", validation.getErrors()))
                .build();
        }

        VirtualAccount va = vaRepository.findById(vaId)
            .orElseThrow(() -> new ResourceNotFoundException("VA not found: " + vaId));
        
        VirtualAccount newParent = vaRepository.findById(newParentId)
            .orElseThrow(() -> new ResourceNotFoundException("New parent not found: " + newParentId));

        UUID oldParentId = va.getParentAccountId();
        String currency = va.getCurrencyCode();
        Set<String> currenciesAffected = new HashSet<>();
        currenciesAffected.add(currency);

        // 2. Execute limit transfer (pre-move)
        LimitTransferResult limitResult = limitTransferService.executeMoveLimitTransfer(
            vaId, oldParentId, newParentId, limitPolicy, approvedBy);

        // 3. Update VA's parent
        va.setParentAccountId(newParentId);
        
        // 4. Recalculate hierarchy path
        String newPath = calculateHierarchyPath(newParent) + "/" + extractVaCode(va.getVaNumber());
        va.setHierarchyPathVa(newPath);
        va.setHierarchyLevel(newParent.getHierarchyLevel() + 1);
        
        vaRepository.save(va);
        log.info("✓ Updated VA parent and hierarchy path");

        // 5. Handle Currency Mirrors
        int mirrorsRecalculated = handleCurrencyMirrorsAfterMove(oldParentId, newParentId, currency);
        log.info("✓ Recalculated {} Currency Mirrors", mirrorsRecalculated);

        // 6. Re-resolve Settlement VA at new location
        boolean settlementResolved = reResolveSettlementVa(va);
        log.info("✓ Settlement VA re-resolved: {}", settlementResolved);

        // 7. Handle orphaned Settlement VA at old location (NEW v5.1.1)
        SettlementCloseoutResult closeoutResult = handleOrphanedSettlementVa(oldParentId, newParentId, currency);
        if (closeoutResult.isClosedOut()) {
            log.info("✓ Closed out orphaned Settlement VA: {} → balance transferred to {}",
                closeoutResult.getClosedVaNumber(), closeoutResult.getTargetVaNumber());
        }

        // 8. Recalculate affected hierarchy balances
        recalculateAffectedBalances(oldParentId, newParentId);

        return MoveResult.builder()
            .success(true)
            .operationType(OperationType.MOVE_TRANSACTION_VA)
            .movedVaId(vaId)
            .oldParentId(oldParentId)
            .newParentId(newParentId)
            .movedVaCount(1)
            .currenciesAffected(currenciesAffected)
            .currencyMirrorsRecalculated(mirrorsRecalculated)
            .settlementVaReResolved(settlementResolved)
            .settlementVasReResolved(settlementResolved ? 1 : 0)
            .limitTransferResult(limitResult)
            .message("Transaction VA moved successfully")
            .completedAt(LocalDateTime.now())
            .build();
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // MOVE AGGREGATION
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * Move an AGGREGATION (and all children) to a new parent.
     */
    @Transactional
    public MoveResult moveAggregation(
            UUID aggregationId, 
            UUID newParentId, 
            MoveLimitPolicy limitPolicy,
            String approvedBy) {
        
        log.info("╔════════════════════════════════════════════════════════════════════════════════╗");
        log.info("║ MOVE AGGREGATION                                                              ║");
        log.info("║ Aggregation: {} → New Parent: {}                          ║", aggregationId, newParentId);
        log.info("╚════════════════════════════════════════════════════════════════════════════════╝");

        // 1. Validate
        MoveValidationResult validation = validateMove(aggregationId, newParentId, limitPolicy);
        if (!validation.isValid()) {
            return MoveResult.builder()
                .success(false)
                .message("Validation failed: " + String.join(", ", validation.getErrors()))
                .build();
        }

        VirtualAccount aggregation = vaRepository.findById(aggregationId)
            .orElseThrow(() -> new ResourceNotFoundException("Aggregation not found: " + aggregationId));
        
        VirtualAccount newParent = vaRepository.findById(newParentId)
            .orElseThrow(() -> new ResourceNotFoundException("New parent not found: " + newParentId));

        UUID oldParentId = aggregation.getParentAccountId();

        // 2. Collect entire subtree
        List<VirtualAccount> subtree = collectSubtree(aggregationId);
        log.info("Subtree contains {} VAs", subtree.size());

        // 3. Collect all currencies in subtree
        Set<String> currenciesAffected = subtree.stream()
            .map(VirtualAccount::getCurrencyCode)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

        // 4. Execute limit transfer (pre-move)
        LimitTransferResult limitResult = limitTransferService.executeMoveLimitTransfer(
            aggregationId, oldParentId, newParentId, limitPolicy, approvedBy);

        // 5. Update aggregation's parent
        String oldPath = aggregation.getHierarchyPathVa();
        aggregation.setParentAccountId(newParentId);
        
        // 6. Recalculate hierarchy path for aggregation
        String newBasePath = calculateHierarchyPath(newParent);
        String aggregationCode = extractVaCode(aggregation.getVaNumber());
        String newPath = newBasePath + "/" + aggregationCode;
        
        aggregation.setHierarchyPathVa(newPath);
        aggregation.setHierarchyLevel(newParent.getHierarchyLevel() + 1);
        vaRepository.save(aggregation);

        // 7. Update all descendants' paths
        int levelDelta = newParent.getHierarchyLevel() + 1 - 
            (aggregation.getHierarchyLevel() != null ? aggregation.getHierarchyLevel() : 0);
        
        for (VirtualAccount descendant : subtree) {
            if (!descendant.getId().equals(aggregationId)) {
                // Update path by replacing old base with new base
                String descendantPath = descendant.getHierarchyPathVa();
                if (descendantPath != null && descendantPath.startsWith(oldPath)) {
                    String relativePath = descendantPath.substring(oldPath.length());
                    descendant.setHierarchyPathVa(newPath + relativePath);
                }
                
                // Update level
                if (descendant.getHierarchyLevel() != null) {
                    descendant.setHierarchyLevel(descendant.getHierarchyLevel() + levelDelta);
                }
                
                vaRepository.save(descendant);
            }
        }
        log.info("✓ Updated {} descendants' hierarchy paths", subtree.size() - 1);

        // 8. Handle Currency Mirrors for all affected currencies
        int mirrorsRecalculated = 0;
        for (String currency : currenciesAffected) {
            mirrorsRecalculated += handleCurrencyMirrorsAfterMove(oldParentId, newParentId, currency);
        }
        log.info("✓ Recalculated {} Currency Mirrors", mirrorsRecalculated);

        // 9. Re-resolve Settlement VAs for all transaction VAs in subtree
        int settlementsResolved = 0;
        for (VirtualAccount va : subtree) {
            if (isTransactionVa(va)) {
                if (reResolveSettlementVa(va)) {
                    settlementsResolved++;
                }
            }
        }
        log.info("✓ Re-resolved {} Settlement VAs", settlementsResolved);

        // 10. Handle orphaned Settlement VAs at old location (NEW v5.1.1)
        int settlementsClosed = 0;
        for (String currency : currenciesAffected) {
            SettlementCloseoutResult closeoutResult = handleOrphanedSettlementVa(oldParentId, newParentId, currency);
            if (closeoutResult.isClosedOut()) {
                settlementsClosed++;
                log.info("✓ Closed orphaned Settlement VA: {} → balance transferred to {}",
                    closeoutResult.getClosedVaNumber(), closeoutResult.getTargetVaNumber());
            }
        }
        if (settlementsClosed > 0) {
            log.info("✓ Closed {} orphaned Settlement VAs", settlementsClosed);
        }

        // 11. Recalculate affected hierarchy balances
        recalculateAffectedBalances(oldParentId, newParentId);

        return MoveResult.builder()
            .success(true)
            .operationType(OperationType.MOVE_AGGREGATION)
            .movedVaId(aggregationId)
            .oldParentId(oldParentId)
            .newParentId(newParentId)
            .movedVaCount(subtree.size())
            .currenciesAffected(currenciesAffected)
            .currencyMirrorsRecalculated(mirrorsRecalculated)
            .settlementVaReResolved(settlementsResolved > 0)
            .settlementVasReResolved(settlementsResolved)
            .limitTransferResult(limitResult)
            .message("Aggregation moved successfully with " + subtree.size() + " VAs")
            .completedAt(LocalDateTime.now())
            .build();
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // VALIDATION
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * Validate a move operation.
     */
    @Transactional(readOnly = true)
    public MoveValidationResult validateMove(
            UUID vaId, 
            UUID newParentId, 
            MoveLimitPolicy limitPolicy) {
        
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        VirtualAccount va = vaRepository.findById(vaId).orElse(null);
        if (va == null) {
            errors.add("VA not found: " + vaId);
            return buildValidationResult(false, errors, warnings, null, 0, false, null);
        }

        VirtualAccount newParent = vaRepository.findById(newParentId).orElse(null);
        if (newParent == null) {
            errors.add("New parent not found: " + newParentId);
            return buildValidationResult(false, errors, warnings, va.getAccountCategory().name(), 0, false, null);
        }

        // Check VA type
        AccountCategory category = va.getAccountCategory();
        String vaType = category.name();
        
        // Cannot move ROOT, CURRENCY_MIRROR, SETTLEMENT, EXCEPTION
        if (category == AccountCategory.ROOT) {
            errors.add("ROOT cannot be moved. Use acquisition/merger operations.");
        } else if (category == AccountCategory.CURRENCY_MIRROR) {
            errors.add("CURRENCY_MIRROR is system-managed and cannot be moved.");
        } else if (category == AccountCategory.SETTLEMENT) {
            errors.add("SETTLEMENT VA cannot be moved independently. Move its parent AGGREGATION.");
        } else if (category == AccountCategory.EXCEPTION) {
            errors.add("EXCEPTION VA cannot be moved.");
        }

        // Check new parent is valid target
        AccountCategory parentCategory = newParent.getAccountCategory();
        if (parentCategory != AccountCategory.ROOT && parentCategory != AccountCategory.AGGREGATION) {
            errors.add("New parent must be ROOT or AGGREGATION, not " + parentCategory);
        }

        // Check same corporate
        if (!va.getCorporateId().equals(newParent.getCorporateId())) {
            errors.add("Cross-corporate moves are not allowed. Use acquisition/divestiture operations.");
        }

        // Check not moving to descendant (for aggregations)
        if (category == AccountCategory.AGGREGATION) {
            if (isDescendant(newParentId, vaId)) {
                errors.add("Cannot move AGGREGATION to its own descendant (circular reference).");
            }
        }

        // Check not moving to same parent
        if (va.getParentAccountId() != null && va.getParentAccountId().equals(newParentId)) {
            warnings.add("VA is already under this parent. No move needed.");
        }

        // Count affected VAs
        int affectedVaCount = 1;
        if (category == AccountCategory.AGGREGATION) {
            affectedVaCount = collectSubtree(vaId).size();
        }

        // Check if Currency Mirror will be created
        boolean currencyMirrorWillBeCreated = !hierarchyVaService.checkCurrencyMirrorExists(
            newParentId, va.getCurrencyCode());

        // Validate limits
        LimitValidationResult limitValidation = null;
        if (limitPolicy != null && va.getParentAccountId() != null) {
            limitValidation = limitTransferService.validateMoveLimit(
                vaId, va.getParentAccountId(), newParentId, limitPolicy);
            
            if (!limitValidation.isValid()) {
                errors.addAll(limitValidation.getErrors());
            }
            warnings.addAll(limitValidation.getWarnings());
        }

        return buildValidationResult(
            errors.isEmpty(), 
            errors, 
            warnings, 
            vaType, 
            affectedVaCount, 
            currencyMirrorWillBeCreated,
            limitValidation);
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // HELPER METHODS - CURRENCY MIRRORS
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * Handle Currency Mirrors after a move.
     */
    private int handleCurrencyMirrorsAfterMove(UUID oldParentId, UUID newParentId, String currency) {
        int recalculated = 0;

        // Recalculate old parent's Currency Mirror
        if (oldParentId != null) {
            recalculateCurrencyMirrorForParent(oldParentId, currency);
            recalculated++;
        }

        // Ensure Currency Mirror exists at new parent
        if (newParentId != null) {
            VirtualAccount newParent = vaRepository.findById(newParentId).orElse(null);
            if (newParent != null) {
                if (!hierarchyVaService.checkCurrencyMirrorExists(newParentId, currency)) {
                    hierarchyVaService.ensureCurrencyMirrorAtLevel(
                        newParentId, currency, newParent.getCorporateId(), null);
                }
                recalculateCurrencyMirrorForParent(newParentId, currency);
                recalculated++;
            }
        }

        return recalculated;
    }

    /**
     * Recalculate Currency Mirror for a parent.
     */
    private void recalculateCurrencyMirrorForParent(UUID parentId, String currency) {
        List<VirtualAccount> mirrors = vaRepository.findByParentAccountIdAndAccountCategory(
            parentId, AccountCategory.CURRENCY_MIRROR);
        
        for (VirtualAccount mirror : mirrors) {
            if (currency.equals(mirror.getCurrencyCode())) {
                hierarchyVaService.recalculateCurrencyMirror(mirror.getId());
                break;
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // HELPER METHODS - SETTLEMENT VA
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * Re-resolve Settlement VA for a VA after move.
     *
     * FIX v5.1.0: Instead of creating new Settlement VAs (which can cause duplicates),
     * this now REUSES existing Settlement VAs at the destination if available.
     *
     * Strategy:
     * 1. Check if Settlement VA already exists under new parent (any naming pattern)
     * 2. If exists → reuse it (no new creation)
     * 3. If not exists → create new via SettlementVaService
     * 4. Optionally close orphaned Settlement VA at old location if no other siblings need it
     */
    private boolean reResolveSettlementVa(VirtualAccount va) {
        if (!isTransactionVa(va)) {
            return false;
        }

        UUID newParentId = va.getParentAccountId();
        String currency = va.getCurrencyCode();

        if (newParentId == null || currency == null) {
            log.warn("Cannot re-resolve Settlement VA - missing parent or currency for {}", va.getVaNumber());
            return false;
        }

        try {
            // Step 1: Check if ANY Settlement VA already exists under new parent for this currency
            // This catches both SETTLE-xxx and SETTLEMENT-xxx naming patterns
            Optional<VirtualAccount> existingSettlement = findAnySettlementVaUnderParent(newParentId, currency);

            if (existingSettlement.isPresent()) {
                log.info("✓ Reusing existing Settlement VA {} under new parent {} (no new creation needed)",
                    existingSettlement.get().getVaNumber(), newParentId);
                return true;
            }

            // Step 2: No existing Settlement VA - create one via SettlementVaService
            log.info("Creating new Settlement VA for {} under parent {}", va.getVaNumber(), newParentId);
            VirtualAccount settlementVa = settlementVaService.ensureSettlementVaForSibling(va);
            return settlementVa != null;

        } catch (Exception e) {
            log.warn("Failed to re-resolve Settlement VA for {}: {}", va.getVaNumber(), e.getMessage());
            return false;
        }
    }

    /**
     * Find any Settlement VA under a parent for a specific currency.
     * Uses AccountCategory.SETTLEMENT - the canonical identifier.
     *
     * @since v5.1.0
     */
    private Optional<VirtualAccount> findAnySettlementVaUnderParent(UUID parentId, String currency) {
        // Get ALL Settlement VAs under this parent by AccountCategory (not naming convention)
        List<VirtualAccount> settlements = vaRepository.findByParentAccountIdAndAccountCategory(
            parentId, AccountCategory.SETTLEMENT);

        // Find one that matches the currency and is ACTIVE
        return settlements.stream()
            .filter(va -> currency.equals(va.getCurrencyCode()))
            .filter(va -> va.getStatus() == VaStatus.ACTIVE)
            .findFirst();
    }

    /**
     * Handle orphaned Settlement VA at old location after a VA move.
     *
     * Logic:
     * 1. Find Settlement VA at old parent for this currency
     * 2. Check if any other operational VAs (siblings) still need it
     * 3. If no siblings remain → transfer balance to new Settlement VA and CLOSE
     * 4. If siblings remain → leave it active
     *
     * @since v5.1.1
     */
    private SettlementCloseoutResult handleOrphanedSettlementVa(UUID oldParentId, UUID newParentId, String currency) {
        if (oldParentId == null) {
            return SettlementCloseoutResult.notApplicable();
        }

        // Find Settlement VA at old location
        Optional<VirtualAccount> oldSettlement = findAnySettlementVaUnderParent(oldParentId, currency);
        if (oldSettlement.isEmpty()) {
            log.debug("No Settlement VA found at old parent {} for currency {}", oldParentId, currency);
            return SettlementCloseoutResult.notApplicable();
        }

        VirtualAccount oldSettlementVa = oldSettlement.get();

        // Check if any operational VAs (siblings) still need this Settlement VA
        boolean hasSiblings = hasOperationalSiblingsUnderParent(oldParentId, currency, oldSettlementVa.getId());

        if (hasSiblings) {
            log.debug("Settlement VA {} still has operational siblings - keeping active", oldSettlementVa.getVaNumber());
            return SettlementCloseoutResult.siblingsRemain(oldSettlementVa.getVaNumber());
        }

        // No siblings - this Settlement VA is orphaned
        log.info("Settlement VA {} is orphaned (no operational siblings) - initiating closeout", oldSettlementVa.getVaNumber());

        // Find target Settlement VA at new location
        Optional<VirtualAccount> newSettlement = findAnySettlementVaUnderParent(newParentId, currency);
        if (newSettlement.isEmpty()) {
            log.warn("Cannot close out orphaned Settlement VA {} - no target Settlement VA at new location",
                oldSettlementVa.getVaNumber());
            return SettlementCloseoutResult.noTarget(oldSettlementVa.getVaNumber());
        }

        VirtualAccount newSettlementVa = newSettlement.get();

        // Transfer balance if non-zero
        BigDecimal balance = oldSettlementVa.getCurrentBalance();
        if (balance != null && balance.compareTo(BigDecimal.ZERO) != 0) {
            transferSettlementBalance(oldSettlementVa, newSettlementVa, balance);
            log.info("Transferred balance {} from {} to {}", balance, oldSettlementVa.getVaNumber(), newSettlementVa.getVaNumber());
        }

        // Close the orphaned Settlement VA
        oldSettlementVa.setStatus(VaStatus.CLOSED);
        vaRepository.save(oldSettlementVa);
        log.info("✓ Closed orphaned Settlement VA: {}", oldSettlementVa.getVaNumber());

        return SettlementCloseoutResult.closedOut(
            oldSettlementVa.getVaNumber(),
            newSettlementVa.getVaNumber(),
            balance);
    }

    /**
     * Check if there are any operational VAs (TRANSACTION, COLLECTION, DISBURSEMENT)
     * under a parent for a specific currency, excluding a specific VA ID.
     */
    private boolean hasOperationalSiblingsUnderParent(UUID parentId, String currency, UUID excludeVaId) {
        List<VirtualAccount> siblings = vaRepository.findByParentAccountId(parentId);

        return siblings.stream()
            .filter(va -> !va.getId().equals(excludeVaId))
            .filter(va -> currency.equals(va.getCurrencyCode()))
            .filter(va -> va.getStatus() == VaStatus.ACTIVE)
            .anyMatch(this::isTransactionVa);
    }

    /**
     * Transfer balance from one Settlement VA to another.
     * Creates proper accounting entries for audit trail.
     */
    private void transferSettlementBalance(VirtualAccount sourceVa, VirtualAccount targetVa, BigDecimal amount) {
        // Debit source Settlement VA
        sourceVa.setCurrentBalance(sourceVa.getCurrentBalance().subtract(amount));
        sourceVa.setAvailableBalance(sourceVa.getAvailableBalance().subtract(amount));
        vaRepository.save(sourceVa);

        // Credit target Settlement VA
        targetVa.setCurrentBalance(targetVa.getCurrentBalance().add(amount));
        targetVa.setAvailableBalance(targetVa.getAvailableBalance().add(amount));
        vaRepository.save(targetVa);

        // Note: In production, you'd also create Transaction records for audit trail
        log.info("Balance transfer: {} {} from {} to {}",
            amount, sourceVa.getCurrencyCode(), sourceVa.getVaNumber(), targetVa.getVaNumber());
    }

    /**
     * Result of Settlement VA closeout operation.
     */
    @Data
    @Builder
    public static class SettlementCloseoutResult {
        private boolean closedOut;
        private boolean siblingsRemain;
        private boolean noTarget;
        private String closedVaNumber;
        private String targetVaNumber;
        private BigDecimal transferredAmount;

        public static SettlementCloseoutResult notApplicable() {
            return SettlementCloseoutResult.builder().closedOut(false).build();
        }

        public static SettlementCloseoutResult siblingsRemain(String vaNumber) {
            return SettlementCloseoutResult.builder()
                .closedOut(false)
                .siblingsRemain(true)
                .closedVaNumber(vaNumber)
                .build();
        }

        public static SettlementCloseoutResult noTarget(String vaNumber) {
            return SettlementCloseoutResult.builder()
                .closedOut(false)
                .noTarget(true)
                .closedVaNumber(vaNumber)
                .build();
        }

        public static SettlementCloseoutResult closedOut(String closedVa, String targetVa, BigDecimal amount) {
            return SettlementCloseoutResult.builder()
                .closedOut(true)
                .closedVaNumber(closedVa)
                .targetVaNumber(targetVa)
                .transferredAmount(amount)
                .build();
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // HELPER METHODS - BALANCE RECALCULATION
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * Recalculate balances for affected hierarchy nodes.
     */
    private void recalculateAffectedBalances(UUID oldParentId, UUID newParentId) {
        // Recalculate old parent chain
        if (oldParentId != null) {
            recalculateParentChain(oldParentId);
        }

        // Recalculate new parent chain
        if (newParentId != null) {
            recalculateParentChain(newParentId);
        }
    }

    /**
     * Recalculate balance up the parent chain.
     */
    private void recalculateParentChain(UUID startId) {
        UUID currentId = startId;
        Set<UUID> visited = new HashSet<>();

        while (currentId != null && !visited.contains(currentId)) {
            visited.add(currentId);
            
            VirtualAccount current = vaRepository.findById(currentId).orElse(null);
            if (current == null) break;

            // Recalculate aggregated balance
            if (current.getAccountCategory() == AccountCategory.AGGREGATION || 
                current.getAccountCategory() == AccountCategory.ROOT) {
                hierarchyVaService.recalculateAggregationBalance(currentId);
            }

            currentId = current.getParentAccountId();
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // HELPER METHODS - SUBTREE
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * Collect entire subtree under a VA (including the root).
     */
    private List<VirtualAccount> collectSubtree(UUID rootId) {
        List<VirtualAccount> result = new ArrayList<>();
        VirtualAccount root = vaRepository.findById(rootId).orElse(null);
        if (root != null) {
            result.add(root);
            collectSubtreeRecursive(rootId, result);
        }
        return result;
    }

    /**
     * Recursively collect children.
     */
    private void collectSubtreeRecursive(UUID parentId, List<VirtualAccount> result) {
        List<VirtualAccount> children = vaRepository.findByParentAccountId(parentId);
        for (VirtualAccount child : children) {
            result.add(child);
            collectSubtreeRecursive(child.getId(), result);
        }
    }

    /**
     * Check if potentialDescendant is a descendant of ancestor.
     */
    private boolean isDescendant(UUID potentialDescendantId, UUID ancestorId) {
        VirtualAccount current = vaRepository.findById(potentialDescendantId).orElse(null);
        Set<UUID> visited = new HashSet<>();
        
        while (current != null && !visited.contains(current.getId())) {
            visited.add(current.getId());
            
            if (current.getId().equals(ancestorId)) {
                return true;
            }
            
            if (current.getParentAccountId() == null) {
                return false;
            }
            
            current = vaRepository.findById(current.getParentAccountId()).orElse(null);
        }
        
        return false;
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // HELPER METHODS - MISC
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * Check if VA is a transaction type.
     */
    private boolean isTransactionVa(VirtualAccount va) {
        AccountCategory category = va.getAccountCategory();
        return category == AccountCategory.TRANSACTION ||
               category == AccountCategory.COLLECTION ||
               category == AccountCategory.DISBURSEMENT;
    }

    /**
     * Calculate hierarchy path for a VA.
     */
    private String calculateHierarchyPath(VirtualAccount va) {
        if (va.getHierarchyPathVa() != null) {
            return va.getHierarchyPathVa();
        }
        
        List<String> pathParts = new ArrayList<>();
        VirtualAccount current = va;
        
        while (current != null) {
            pathParts.add(0, extractVaCode(current.getVaNumber()));
            
            if (current.getParentAccountId() == null) {
                break;
            }
            current = vaRepository.findById(current.getParentAccountId()).orElse(null);
        }
        
        return "/" + String.join("/", pathParts);
    }

    /**
     * Extract VA code from VA number.
     */
    private String extractVaCode(String vaNumber) {
        if (vaNumber == null) return "UNKNOWN";
        
        // Handle patterns like "AGG-OPS", "VA-001", "SETTLE-USD-001"
        if (vaNumber.startsWith("AGG-")) {
            return vaNumber.substring(4);
        } else if (vaNumber.startsWith("ROOT-")) {
            return "ROOT";
        }
        return vaNumber;
    }

    /**
     * Build validation result.
     */
    private MoveValidationResult buildValidationResult(
            boolean valid,
            List<String> errors,
            List<String> warnings,
            String vaType,
            int affectedVaCount,
            boolean currencyMirrorWillBeCreated,
            LimitValidationResult limitValidation) {
        
        return MoveValidationResult.builder()
            .valid(valid)
            .errors(errors)
            .warnings(warnings)
            .vaType(vaType)
            .affectedVaCount(affectedVaCount)
            .currencyMirrorWillBeCreated(currencyMirrorWillBeCreated)
            .limitCheckPassed(limitValidation == null || limitValidation.isValid())
            .requiredHeadroom(limitValidation != null ? limitValidation.getMovingUtilization() : null)
            .availableHeadroom(limitValidation != null && limitValidation.getTargetLimit() != null ? 
                limitValidation.getTargetLimit().getAvailableAmount() : null)
            .build();
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // RESULT DTOs
    // ════════════════════════════════════════════════════════════════════════════════

    @Data
    @Builder
    public static class MoveResult {
        private boolean success;
        private OperationType operationType;
        private UUID movedVaId;
        private UUID oldParentId;
        private UUID newParentId;
        private int movedVaCount;
        private Set<String> currenciesAffected;
        private int currencyMirrorsRecalculated;
        private boolean settlementVaReResolved;
        private int settlementVasReResolved;
        private LimitTransferResult limitTransferResult;
        private String message;
        private LocalDateTime completedAt;
    }
}