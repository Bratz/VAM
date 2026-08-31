package com.bank.vam.service.treasury;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.entity.VirtualAccount.VaStatus;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.service.treasury.HierarchyOperationDtos.AcquisitionValidationResult;
import com.bank.vam.service.treasury.HierarchyOperationDtos.MergeLimitPolicy;
import com.bank.vam.service.treasury.HierarchyOperationDtos.MergeLimitTransferResult;
import com.bank.vam.service.treasury.HierarchyOperationDtos.MergeLimitValidationResult;
import com.bank.vam.service.treasury.HierarchyOperationDtos.OperationType;
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
 * HierarchyMergeService - Handles M&A operations (Acquisition, Merger, Divestiture).
 * 
 * FIXED v5.0.1:
 * - Removed references to non-existent methods
 * - Uses existing HierarchyVaService methods
 * - Self-contained helper methods
 * 
 * M&A OPERATIONS:
 * - ACQUISITION: Target ROOT becomes AGGREGATION under acquirer
 * - MERGER: Both ROOTs become AGGREGATIONs under new ROOT
 * - DIVESTITURE: AGGREGATION promoted to ROOT for new corporate
 * 
 * @version 5.0.1
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HierarchyMergeService {

    private final VirtualAccountRepository vaRepository;
    private final HierarchyVaService hierarchyVaService;
    private final LimitTransferService limitTransferService;

    // ════════════════════════════════════════════════════════════════════════════════
    // ACQUISITION
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * Acquire a corporate - target's ROOT becomes AGGREGATION under acquirer's ROOT.
     */
    @Transactional
    public MergeResult acquireCorporate(AcquisitionRequest request) {
        log.info("╔════════════════════════════════════════════════════════════════════════════════╗");
        log.info("║ CORPORATE ACQUISITION                                                         ║");
        log.info("║ Acquirer: {} | Target: {}                                  ║", 
            request.getAcquirerCorporateId(), request.getTargetCorporateId());
        log.info("╚════════════════════════════════════════════════════════════════════════════════╝");

        UUID acquirerCorporateId = request.getAcquirerCorporateId();
        UUID targetCorporateId = request.getTargetCorporateId();
        MergeLimitPolicy limitPolicy = request.getLimitPolicy();
        String approvedBy = request.getApprovedBy();

        // 1. Find both ROOTs
        VirtualAccount acquirerRoot = vaRepository.findRootAccount(acquirerCorporateId)
            .orElseThrow(() -> new BusinessException("Acquirer ROOT not found"));
        
        VirtualAccount targetRoot = vaRepository.findRootAccount(targetCorporateId)
            .orElseThrow(() -> new BusinessException("Target ROOT not found"));

        // 2. Validate acquisition
        AcquisitionValidationResult validation = validateAcquisition(
            acquirerCorporateId, targetCorporateId, limitPolicy);
        
        if (!validation.isValid()) {
            return MergeResult.builder()
                .success(false)
                .message("Validation failed: " + String.join(", ", validation.getErrors()))
                .build();
        }

        // 3. Execute limit transfer
        MergeLimitTransferResult limitResult = limitTransferService.executeMergeLimitTransfer(
            acquirerCorporateId, targetCorporateId, limitPolicy, approvedBy);

        // 4. Convert target ROOT to AGGREGATION
        String newAggregationName = request.getNewAggregationName() != null ? 
            request.getNewAggregationName() : "Acquired - " + targetRoot.getVaName();
        String newAggregationCode = request.getNewAggregationCode() != null ?
            request.getNewAggregationCode() : "ACQ-" + targetCorporateId.toString().substring(0, 4).toUpperCase();
        
        targetRoot.setAccountCategory(AccountCategory.AGGREGATION);
        targetRoot.setVaName(newAggregationName);
        targetRoot.setVaNumber("AGG-" + newAggregationCode);
        targetRoot.setParentAccountId(acquirerRoot.getId());
        targetRoot.setHierarchyLevel(acquirerRoot.getHierarchyLevel() + 1);
        targetRoot.setHierarchyPathVa(acquirerRoot.getHierarchyPathVa() + "/" + newAggregationCode);
        
        vaRepository.save(targetRoot);
        log.info("✓ Converted target ROOT to AGGREGATION: {}", targetRoot.getVaNumber());

        // 5. Migrate all target VAs to acquirer corporate
        List<VirtualAccount> targetVas = vaRepository.findByCorporateId(targetCorporateId);
        int migratedCount = 0;
        Set<String> currenciesAffected = new HashSet<>();
        
        for (VirtualAccount va : targetVas) {
            va.setCorporateId(acquirerCorporateId);
            if (va.getCurrencyCode() != null) {
                currenciesAffected.add(va.getCurrencyCode());
            }
            vaRepository.save(va);
            migratedCount++;
        }
        log.info("✓ Migrated {} VAs to acquirer corporate", migratedCount);

        // 6. Create Currency Mirrors at acquirer ROOT for target currencies
        int mirrorsCreated = 0;
        for (String currency : currenciesAffected) {
            if (!hierarchyVaService.checkCurrencyMirrorExists(acquirerRoot.getId(), currency)) {
                hierarchyVaService.ensureCurrencyMirrorAtLevel(
                    acquirerRoot.getId(), currency, acquirerCorporateId, null);
                mirrorsCreated++;
            }
        }
        log.info("✓ Created {} Currency Mirrors at acquirer ROOT", mirrorsCreated);

        // 7. Recalculate full hierarchy
        hierarchyVaService.recalculateFullHierarchy(acquirerCorporateId);
        log.info("✓ Recalculated full hierarchy");

        return MergeResult.builder()
            .success(true)
            .operationType(OperationType.ACQUISITION)
            .acquirerCorporateId(acquirerCorporateId)
            .targetCorporateId(targetCorporateId)
            .newAggregationId(targetRoot.getId())
            .migratedVaCount(migratedCount)
            .currenciesAffected(currenciesAffected)
            .currencyMirrorsCreated(mirrorsCreated)
            .limitValidation(validation.getLimitValidation())
            .limitTransferResult(limitResult)
            .message("Acquisition completed successfully")
            .completedAt(LocalDateTime.now())
            .build();
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // MERGER
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * Merge two corporates - both ROOTs become AGGREGATIONs under new ROOT.
     */
    @Transactional
    public MergeResult mergeCorporates(MergerRequest request) {
        log.info("╔════════════════════════════════════════════════════════════════════════════════╗");
        log.info("║ CORPORATE MERGER                                                              ║");
        log.info("║ Corp A: {} | Corp B: {}                                    ║", 
            request.getCorporateAId(), request.getCorporateBId());
        log.info("╚════════════════════════════════════════════════════════════════════════════════╝");

        UUID corporateAId = request.getCorporateAId();
        UUID corporateBId = request.getCorporateBId();
        UUID newCorporateId = request.getNewCorporateId();
        String newBaseCurrency = request.getNewBaseCurrency();
        MergeLimitPolicy limitPolicy = request.getLimitPolicy();
        String approvedBy = request.getApprovedBy();

        // 1. Find both ROOTs
        VirtualAccount rootA = vaRepository.findRootAccount(corporateAId)
            .orElseThrow(() -> new BusinessException("Corporate A ROOT not found"));
        
        VirtualAccount rootB = vaRepository.findRootAccount(corporateBId)
            .orElseThrow(() -> new BusinessException("Corporate B ROOT not found"));

        // 2. Execute limit transfer for both
        MergeLimitTransferResult limitResultA = limitTransferService.executeMergeLimitTransfer(
            newCorporateId, corporateAId, limitPolicy, approvedBy);
        MergeLimitTransferResult limitResultB = limitTransferService.executeMergeLimitTransfer(
            newCorporateId, corporateBId, limitPolicy, approvedBy);

        // 3. Create new ROOT for merged entity
        VirtualAccount newRoot = VirtualAccount.builder()
            .vaNumber("ROOT-" + newCorporateId.toString().substring(0, 8).toUpperCase())
            .vaName("Merged Corporate Root")
            .corporateId(newCorporateId)
            .currencyCode(newBaseCurrency)
            .baseCurrency(newBaseCurrency)
            .accountType(VirtualAccount.AccountType.VIRTUAL)
            .accountCategory(AccountCategory.ROOT)
            .hierarchyLevel(0)
            .hierarchyPathVa("/ROOT")
            .aggregatedBalance(BigDecimal.ZERO)
            .currentBalance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO)
            .status(VaStatus.ACTIVE)
            .build();
        
        newRoot = vaRepository.save(newRoot);
        log.info("✓ Created new ROOT: {}", newRoot.getVaNumber());

        // 4. Convert both old ROOTs to AGGREGATIONs
        String aggCodeA = request.getCorporateACode() != null ? 
            request.getCorporateACode() : "CORP-A";
        String aggCodeB = request.getCorporateBCode() != null ? 
            request.getCorporateBCode() : "CORP-B";
        
        convertRootToAggregation(rootA, newRoot, aggCodeA, 
            request.getCorporateAName() != null ? request.getCorporateAName() : "Former " + rootA.getVaName());
        convertRootToAggregation(rootB, newRoot, aggCodeB, 
            request.getCorporateBName() != null ? request.getCorporateBName() : "Former " + rootB.getVaName());
        
        log.info("✓ Converted both ROOTs to AGGREGATIONs");

        // 5. Migrate all VAs from both corporates
        Set<String> currenciesAffected = new HashSet<>();
        int migratedCount = 0;
        
        List<VirtualAccount> vasA = vaRepository.findByCorporateId(corporateAId);
        for (VirtualAccount va : vasA) {
            va.setCorporateId(newCorporateId);
            updateChildVasForMerge(va, rootA, newRoot.getHierarchyPathVa() + "/" + aggCodeA);
            if (va.getCurrencyCode() != null) currenciesAffected.add(va.getCurrencyCode());
            vaRepository.save(va);
            migratedCount++;
        }
        
        List<VirtualAccount> vasB = vaRepository.findByCorporateId(corporateBId);
        for (VirtualAccount va : vasB) {
            va.setCorporateId(newCorporateId);
            updateChildVasForMerge(va, rootB, newRoot.getHierarchyPathVa() + "/" + aggCodeB);
            if (va.getCurrencyCode() != null) currenciesAffected.add(va.getCurrencyCode());
            vaRepository.save(va);
            migratedCount++;
        }
        
        log.info("✓ Migrated {} VAs to new corporate", migratedCount);

        // 6. Create Currency Mirrors at new ROOT
        int mirrorsCreated = 0;
        for (String currency : currenciesAffected) {
            if (!hierarchyVaService.checkCurrencyMirrorExists(newRoot.getId(), currency)) {
                hierarchyVaService.ensureCurrencyMirrorAtLevel(
                    newRoot.getId(), currency, newCorporateId, null);
                mirrorsCreated++;
            }
        }
        log.info("✓ Created {} Currency Mirrors at new ROOT", mirrorsCreated);

        // 7. Recalculate full hierarchy
        hierarchyVaService.recalculateFullHierarchy(newCorporateId);

        return MergeResult.builder()
            .success(true)
            .operationType(OperationType.MERGER)
            .newCorporateId(newCorporateId)
            .newRootId(newRoot.getId())
            .migratedVaCount(migratedCount)
            .currenciesAffected(currenciesAffected)
            .currencyMirrorsCreated(mirrorsCreated)
            .limitTransferResult(limitResultA) // Primary result
            .message("Merger completed successfully")
            .completedAt(LocalDateTime.now())
            .build();
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // DIVESTITURE
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * Divest an AGGREGATION - promote to ROOT for new corporate.
     */
    @Transactional
    public MergeResult divestAggregation(DivestitureRequest request) {
        log.info("╔════════════════════════════════════════════════════════════════════════════════╗");
        log.info("║ DIVESTITURE                                                                   ║");
        log.info("║ Aggregation: {} → New Corporate: {}                        ║", 
            request.getAggregationId(), request.getNewCorporateId());
        log.info("╚════════════════════════════════════════════════════════════════════════════════╝");

        UUID aggregationId = request.getAggregationId();
        UUID newCorporateId = request.getNewCorporateId();
        UUID sourceCorporateId = request.getSourceCorporateId();
        String newBaseCurrency = request.getNewBaseCurrency();
        String approvedBy = request.getApprovedBy();

        // 1. Find the aggregation
        VirtualAccount aggregation = vaRepository.findById(aggregationId)
            .orElseThrow(() -> new ResourceNotFoundException("Aggregation not found: " + aggregationId));
        
        if (aggregation.getAccountCategory() != AccountCategory.AGGREGATION) {
            throw new BusinessException("VA is not an AGGREGATION: " + aggregation.getAccountCategory());
        }

        // 2. Collect entire subtree
        List<VirtualAccount> subtree = collectSubtree(aggregationId);
        log.info("Subtree contains {} VAs", subtree.size());

        // 3. Collect all currencies
        Set<String> currenciesAffected = subtree.stream()
            .map(VirtualAccount::getCurrencyCode)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

        // 4. Promote AGGREGATION to ROOT
        String baseCurrency = newBaseCurrency != null ? newBaseCurrency : aggregation.getCurrencyCode();
        
        aggregation.setAccountCategory(AccountCategory.ROOT);
        aggregation.setVaNumber("ROOT-" + newCorporateId.toString().substring(0, 8).toUpperCase());
        aggregation.setVaName("Corporate Root Account");
        aggregation.setCorporateId(newCorporateId);
        aggregation.setBaseCurrency(baseCurrency);
        aggregation.setParentAccountId(null);
        aggregation.setHierarchyLevel(0);
        aggregation.setHierarchyPathVa("/ROOT");
        
        vaRepository.save(aggregation);
        log.info("✓ Promoted AGGREGATION to ROOT: {}", aggregation.getVaNumber());

        // 5. Migrate all descendants to new corporate
        int migratedCount = 0;
        for (VirtualAccount va : subtree) {
            if (!va.getId().equals(aggregationId)) {
                va.setCorporateId(newCorporateId);
                recalculateHierarchyPath(va, aggregation);
                vaRepository.save(va);
                migratedCount++;
            }
        }
        log.info("✓ Migrated {} VAs to new corporate", migratedCount);

        // 6. Create base Currency Mirror at new ROOT
        int mirrorsCreated = 0;
        if (!hierarchyVaService.checkCurrencyMirrorExists(aggregation.getId(), baseCurrency)) {
            hierarchyVaService.ensureCurrencyMirrorAtLevel(
                aggregation.getId(), baseCurrency, newCorporateId, null);
            mirrorsCreated++;
        }

        // 7. Recalculate both hierarchies
        hierarchyVaService.recalculateFullHierarchy(newCorporateId);
        if (sourceCorporateId != null) {
            hierarchyVaService.recalculateFullHierarchy(sourceCorporateId);
        }

        return MergeResult.builder()
            .success(true)
            .operationType(OperationType.DIVESTITURE)
            .sourceCorporateId(sourceCorporateId)
            .newCorporateId(newCorporateId)
            .newRootId(aggregation.getId())
            .migratedVaCount(migratedCount + 1) // Include the aggregation itself
            .currenciesAffected(currenciesAffected)
            .currencyMirrorsCreated(mirrorsCreated)
            .message("Divestiture completed successfully")
            .completedAt(LocalDateTime.now())
            .build();
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // VALIDATION
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * Validate acquisition operation.
     */
    @Transactional(readOnly = true)
    public AcquisitionValidationResult validateAcquisition(
            UUID acquirerCorporateId,
            UUID targetCorporateId,
            MergeLimitPolicy limitPolicy) {
        
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Check both ROOTs exist
        Optional<VirtualAccount> acquirerRootOpt = vaRepository.findRootAccount(acquirerCorporateId);
        Optional<VirtualAccount> targetRootOpt = vaRepository.findRootAccount(targetCorporateId);
        
        if (acquirerRootOpt.isEmpty()) {
            errors.add("Acquirer corporate does not have a ROOT VA");
        }
        if (targetRootOpt.isEmpty()) {
            errors.add("Target corporate does not have a ROOT VA");
        }

        // Count VAs
        int acquirerVaCount = (int) vaRepository.countByCorporateId(acquirerCorporateId);
        int targetVaCount = (int) vaRepository.countByCorporateId(targetCorporateId);

        // Check currency compatibility
        if (acquirerRootOpt.isPresent() && targetRootOpt.isPresent()) {
            String acquirerCurrency = acquirerRootOpt.get().getBaseCurrency();
            String targetCurrency = targetRootOpt.get().getBaseCurrency();
            
            if (acquirerCurrency != null && targetCurrency != null && 
                !acquirerCurrency.equals(targetCurrency)) {
                warnings.add(String.format("Different base currencies: acquirer=%s, target=%s. " +
                    "Currency mirrors will be created.", acquirerCurrency, targetCurrency));
            }
        }

        // Validate limits
        MergeLimitValidationResult limitValidation = limitTransferService.validateMergeLimits(
            acquirerCorporateId, targetCorporateId, limitPolicy);
        
        warnings.addAll(limitValidation.getWarnings());

        return AcquisitionValidationResult.builder()
            .valid(errors.isEmpty())
            .errors(errors)
            .warnings(warnings)
            .acquirerVaCount(acquirerVaCount)
            .targetVaCount(targetVaCount)
            .totalVaCount(acquirerVaCount + targetVaCount)
            .limitValidation(limitValidation)
            .build();
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * Convert a ROOT to an AGGREGATION under new parent.
     */
    private void convertRootToAggregation(
            VirtualAccount root, 
            VirtualAccount newParent, 
            String aggregationCode,
            String aggregationName) {
        
        root.setAccountCategory(AccountCategory.AGGREGATION);
        root.setVaNumber("AGG-" + aggregationCode);
        root.setVaName(aggregationName);
        root.setParentAccountId(newParent.getId());
        root.setHierarchyLevel(newParent.getHierarchyLevel() + 1);
        root.setHierarchyPathVa(newParent.getHierarchyPathVa() + "/" + aggregationCode);
        
        vaRepository.save(root);
    }

    /**
     * Update child VA paths after merge.
     */
    private void updateChildVasForMerge(
            VirtualAccount va, 
            VirtualAccount oldRoot, 
            String newBasePath) {
        
        if (va.getHierarchyPathVa() != null && oldRoot.getHierarchyPathVa() != null) {
            String oldPath = va.getHierarchyPathVa();
            String oldRootPath = oldRoot.getHierarchyPathVa();
            
            if (oldPath.startsWith(oldRootPath)) {
                String relativePath = oldPath.substring(oldRootPath.length());
                va.setHierarchyPathVa(newBasePath + relativePath);
            }
        }
        
        // Update level
        if (va.getHierarchyLevel() != null) {
            va.setHierarchyLevel(va.getHierarchyLevel() + 1);
        }
    }

    /**
     * Collect entire subtree under a VA.
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
     * Recalculate hierarchy path for a VA after divestiture.
     */
    private void recalculateHierarchyPath(VirtualAccount va, VirtualAccount newRoot) {
        // Build path from VA to new root
        List<String> pathParts = new ArrayList<>();
        pathParts.add(extractVaCode(va.getVaNumber()));
        
        UUID currentId = va.getParentAccountId();
        while (currentId != null && !currentId.equals(newRoot.getId())) {
            VirtualAccount current = vaRepository.findById(currentId).orElse(null);
            if (current == null) break;
            pathParts.add(0, extractVaCode(current.getVaNumber()));
            currentId = current.getParentAccountId();
        }
        
        String newPath = "/ROOT/" + String.join("/", pathParts);
        va.setHierarchyPathVa(newPath);
        
        // Update level
        va.setHierarchyLevel(pathParts.size());
    }

    /**
     * Extract VA code from VA number.
     */
    private String extractVaCode(String vaNumber) {
        if (vaNumber == null) return "UNKNOWN";
        
        if (vaNumber.startsWith("AGG-")) {
            return vaNumber.substring(4);
        } else if (vaNumber.startsWith("ROOT-")) {
            return "ROOT";
        } else if (vaNumber.startsWith("VA-")) {
            return vaNumber;
        }
        return vaNumber;
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // REQUEST/RESULT DTOs
    // ════════════════════════════════════════════════════════════════════════════════

    @Data
    @Builder
    public static class AcquisitionRequest {
        private UUID acquirerCorporateId;
        private UUID targetCorporateId;
        private String newAggregationName;
        private String newAggregationCode;
        private MergeLimitPolicy limitPolicy;
        private String approvedBy;
    }

    @Data
    @Builder
    public static class MergerRequest {
        private UUID corporateAId;
        private UUID corporateBId;
        private UUID newCorporateId;
        private String newBaseCurrency;
        private String corporateAName;
        private String corporateACode;
        private String corporateBName;
        private String corporateBCode;
        private MergeLimitPolicy limitPolicy;
        private String approvedBy;
    }

    @Data
    @Builder
    public static class DivestitureRequest {
        private UUID sourceCorporateId;
        private UUID aggregationId;
        private UUID newCorporateId;
        private String newBaseCurrency;
        private String approvedBy;
    }

    @Data
    @Builder
    public static class MergeResult {
        private boolean success;
        private OperationType operationType;
        private UUID acquirerCorporateId;
        private UUID targetCorporateId;
        private UUID sourceCorporateId;
        private UUID newCorporateId;
        private UUID newAggregationId;
        private UUID newRootId;
        private int migratedVaCount;
        private Set<String> currenciesAffected;
        private int currencyMirrorsCreated;
        private MergeLimitValidationResult limitValidation;
        private MergeLimitTransferResult limitTransferResult;
        private String message;
        private LocalDateTime completedAt;
    }
}