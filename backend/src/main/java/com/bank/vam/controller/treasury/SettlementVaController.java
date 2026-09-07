package com.bank.vam.controller.treasury;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.dto.treasury.SettlementVaDto.*;
import com.bank.vam.entity.Program;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.VaSpecialType;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.hierarchy.HierarchyNode;
import com.bank.vam.entity.hierarchy.HierarchyLevelConfig;
import com.bank.vam.repository.ProgramRepository;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.HierarchyNodeRepository;
import com.bank.vam.repository.hierarchy.HierarchyLevelConfigRepository;
import com.bank.vam.service.hierarchy.HierarchyService;
import com.bank.vam.service.treasury.SettlementVaResolverService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/treasury/settlement-vas")
@RequiredArgsConstructor
@Slf4j
public class SettlementVaController {

    private final HierarchyService hierarchyService;
    private final SettlementVaResolverService settlementVaResolver;
    private final VirtualAccountRepository vaRepository;
    private final HierarchyNodeRepository nodeRepository;
    private final TransactionRepository transactionRepository;
    private final ProgramRepository programRepository;
    private final HierarchyLevelConfigRepository levelConfigRepository;

    /**
     * Get all Settlement and Exception VAs for a program.
     * Now includes hierarchyInitialized flag to help frontend determine UI state.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<SettlementVaListResponse>> getSettlementVas(
            @RequestParam(required = false) UUID programId) {
        
        // VALIDATION: Return empty response if programId is null
        if (programId == null) {
            log.warn("getSettlementVas called with null programId");
            return ResponseEntity.ok(ApiResponse.success(buildEmptyResponse()));
        }
        
        log.info("Getting Settlement VAs for program: {}", programId);
        
        // Get program to check hierarchy status - use Optional to avoid null issues
        Optional<Program> programOpt = programRepository.findById(programId);
        if (programOpt.isEmpty()) {
            log.warn("Program not found: {}", programId);
            return ResponseEntity.ok(ApiResponse.success(buildEmptyResponse()));
        }
        
        Program program = programOpt.get();
        
        // Check hierarchy initialization status
        boolean hierarchyInitialized = false;
        String programName = program.getProgramName();
        String programCode = program.getProgramCode();
        String currencyCode = program.getCurrencyCode();
        
        // Check multiple conditions for initialization
        boolean hasHierarchyEnabled = Boolean.TRUE.equals(program.getHierarchyEnabled());
        boolean hasRootNode = program.getRootHierarchyNodeId() != null;
        
        // Check for level configs
        List<HierarchyLevelConfig> levelConfigs = levelConfigRepository.findByProgramIdOrderByLevelNumberAsc(programId);
        boolean hasLevelConfigs = levelConfigs != null && !levelConfigs.isEmpty();
        
        // Check for exception VAs
        List<VirtualAccount> programVas = vaRepository.findByProgramId(programId);
        boolean hasExceptionVa = programVas.stream()
            .anyMatch(va -> va.getSpecialType() == VaSpecialType.EXCEPTION);
        
        hierarchyInitialized = hasHierarchyEnabled && (hasRootNode || hasLevelConfigs || hasExceptionVa);
        
        log.info("Program {} hierarchy status: enabled={}, rootNode={}, levelConfigs={}, exceptionVa={}, initialized={}",
            programId, hasHierarchyEnabled, hasRootNode, hasLevelConfigs, hasExceptionVa, hierarchyInitialized);
        
        List<SettlementVaResponse> settlementVas = programVas.stream()
            .filter(va -> va.getSpecialType() == VaSpecialType.SETTLEMENT)
            .map(this::toSettlementVaResponse)
            .collect(Collectors.toList());
        
        List<SettlementVaResponse> exceptionVas = programVas.stream()
            .filter(va -> va.getSpecialType() == VaSpecialType.EXCEPTION)
            .map(this::toSettlementVaResponse)
            .collect(Collectors.toList());
        
        BigDecimal totalSettlementBalance = settlementVas.stream()
            .map(va -> va.getCurrentBalance() != null ? va.getCurrentBalance() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal totalExceptionBalance = exceptionVas.stream()
            .map(va -> va.getCurrentBalance() != null ? va.getCurrentBalance() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        SettlementVaSummary summary = SettlementVaSummary.builder()
            .totalSettlementVas(settlementVas.size())
            .totalExceptionVas(exceptionVas.size())
            .totalSettlementBalance(totalSettlementBalance)
            .totalExceptionBalance(totalExceptionBalance)
            .build();
        
        SettlementVaListResponse response = SettlementVaListResponse.builder()
            .settlementVas(settlementVas)
            .exceptionVas(exceptionVas)
            .summary(summary)
            .hierarchyInitialized(hierarchyInitialized)
            .programName(programName)
            .programCode(programCode)
            .currencyCode(currencyCode)
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/program/{programId}")
    public ResponseEntity<ApiResponse<SettlementVaListResponse>> getSettlementVasByProgram(@PathVariable UUID programId) {
        return getSettlementVas(programId);
    }

    /**
     * Get Settlement VA details.
     */
    @GetMapping("/{vaId}")
    public ResponseEntity<ApiResponse<SettlementVaDetailResponse>> getSettlementVaDetail(
            @PathVariable UUID vaId) {
        
        if (vaId == null) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("VA ID is required"));
        }
        
        VirtualAccount va = vaRepository.findById(vaId)
            .orElseThrow(() -> new RuntimeException("VA not found: " + vaId));
        
        // Get recent transactions - SAFE: vaId is validated above
        List<Transaction> recentTxns = transactionRepository.findByVaId(vaId,
            PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "transactionDate"))).getContent();
        
        List<RecentTransactionResponse> transactions = recentTxns.stream()
            .map(this::toRecentTransactionResponse)
            .collect(Collectors.toList());
        
        // Get covered VAs (VAs that post fees to this Settlement VA)
        List<CoveredVaResponse> coveredVas = null;
        if (va.getSpecialType() == VaSpecialType.SETTLEMENT && va.getHierarchyNodeId() != null) {
            coveredVas = getCoveredVas(va);
        }
        
        SettlementVaDetailResponse response = SettlementVaDetailResponse.builder()
            .va(toSettlementVaResponse(va))
            .recentTransactions(transactions)
            .coveredVas(coveredVas)
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Create Settlement VA at specified hierarchy level (CFO action).
     */
    @PostMapping
    public ResponseEntity<ApiResponse<SettlementVaResponse>> createSettlementVa(
            @RequestBody CreateSettlementVaRequest request) {
        
        if (request.getProgramId() == null) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("Program ID is required"));
        }
        
        log.info("Creating Settlement VA for program {} at node {}",
            request.getProgramId(), request.getParentNodeId());

        // No parent node given: the program has no hierarchy_nodes tree to attach to (several
        // demo corporates are flat — standalone shadow VAs with no ROOT/hierarchy at all).
        // provisionSettlementVa is the resolver's own "explicit provisioning" entrypoint
        // (SettlementVaResolverService.java) — it already builds a parentless, purely-virtual
        // SETTLEMENT VA exactly like the EXCEPTION-VA auto-creation fallback does, and is
        // reused (not duplicated) by the sibling/program/corporate lookup chain either way.
        if (request.getParentNodeId() == null) {
            VirtualAccount standaloneSettlementVa = settlementVaResolver.provisionSettlementVa(
                request.getProgramId(), request.getCurrency());
            return ResponseEntity.ok(ApiResponse.success(toSettlementVaResponse(standaloneSettlementVa)));
        }

        VirtualAccount settlementVa = hierarchyService.createSettlementVa(
            request.getProgramId(),
            request.getParentNodeId(),
            request.getCurrency(),
            request.getVaName()
        );
        
        return ResponseEntity.ok(ApiResponse.success(toSettlementVaResponse(settlementVa)));
    }

    /**
     * Initialize program hierarchy with auto Exception VAs.
     */
    @PostMapping("/initialize")
    public ResponseEntity<ApiResponse<SettlementVaListResponse>> initializeHierarchy(
            @RequestBody InitializeHierarchyRequest request) {
        
        if (request.getProgramId() == null) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("Program ID is required"));
        }
        
        log.info("Initializing hierarchy for program {} with template {}", 
            request.getProgramId(), request.getTemplateType());
        
        try {
            if (request.getCurrencies() != null && !request.getCurrencies().isEmpty()) {
                hierarchyService.initializeProgramHierarchyMultiCurrency(
                    request.getProgramId(),
                    request.getTemplateType(),
                    request.getCurrencies()
                );
            } else {
                hierarchyService.initializeProgramHierarchy(
                    request.getProgramId(),
                    request.getTemplateType()
                );
            }
            
            log.info("Hierarchy initialized successfully for program {}", request.getProgramId());
            
            // Return updated list
            return getSettlementVas(request.getProgramId());
        } catch (Exception e) {
            log.error("Failed to initialize hierarchy for program {}: {}", request.getProgramId(), e.getMessage(), e);
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("Failed to initialize hierarchy: " + e.getMessage()));
        }
    }

    /**
     * Delete a Settlement VA (only if zero balance).
     */
    @DeleteMapping("/{vaId}")
    public ResponseEntity<ApiResponse<Void>> deleteSettlementVa(@PathVariable UUID vaId) {
        if (vaId == null) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("VA ID is required"));
        }
        
        log.info("Deleting Settlement VA: {}", vaId);
        
        VirtualAccount va = vaRepository.findById(vaId)
            .orElseThrow(() -> new RuntimeException("VA not found: " + vaId));
        
        // Check balance is zero
        if (va.getCurrentBalance() != null && va.getCurrentBalance().compareTo(BigDecimal.ZERO) != 0) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("Cannot delete VA with non-zero balance"));
        }
        
        // Check it's a Settlement VA (not Exception)
        if (va.getSpecialType() == VaSpecialType.EXCEPTION) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("Cannot delete Exception VA"));
        }
        
        vaRepository.delete(va);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * Check if program has Exception VA configured.
     */
    @GetMapping("/check/{programId}/{currency}")
    public ResponseEntity<ApiResponse<Boolean>> checkExceptionVaExists(
            @PathVariable UUID programId,
            @PathVariable String currency) {
        
        if (programId == null) {
            return ResponseEntity.ok(ApiResponse.success(false));
        }
        
        boolean exists = settlementVaResolver.hasExceptionVa(programId, currency);
        return ResponseEntity.ok(ApiResponse.success(exists));
    }

    /**
     * Resolve which Settlement VA should receive fees from a source VA.
     */
    @PostMapping("/resolve")
    public ResponseEntity<ApiResponse<ResolveSettlementVaResponse>> resolveSettlementVa(
            @RequestBody ResolveSettlementVaRequest request) {
        
        if (request.getSourceVaId() == null) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("Source VA ID is required"));
        }
        
        log.info("Resolving Settlement VA for source VA: {}", request.getSourceVaId());
        
        VirtualAccount sourceVa = vaRepository.findById(request.getSourceVaId())
            .orElseThrow(() -> new RuntimeException("Source VA not found: " + request.getSourceVaId()));
        
        VirtualAccount settlementVa = settlementVaResolver.resolveSettlementVa(sourceVa);
        
        boolean isExceptionFallback = settlementVa != null && 
            settlementVa.getSpecialType() == VaSpecialType.EXCEPTION;
        
        String resolutionPath = buildResolutionPath(sourceVa, settlementVa);
        
        ResolveSettlementVaResponse response = ResolveSettlementVaResponse.builder()
            .settlementVa(settlementVa != null ? toSettlementVaResponse(settlementVa) : null)
            .resolutionPath(resolutionPath)
            .isExceptionFallback(isExceptionFallback)
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================
    
    /**
     * Build an empty response when programId is null or program not found.
     */
    private SettlementVaListResponse buildEmptyResponse() {
        return SettlementVaListResponse.builder()
            .settlementVas(Collections.emptyList())
            .exceptionVas(Collections.emptyList())
            .summary(SettlementVaSummary.builder()
                .totalSettlementVas(0)
                .totalExceptionVas(0)
                .totalSettlementBalance(BigDecimal.ZERO)
                .totalExceptionBalance(BigDecimal.ZERO)
                .build())
            .hierarchyInitialized(false)
            .build();
    }

    // ========================================================================
    // MAPPING HELPERS
    // ========================================================================

    private SettlementVaResponse toSettlementVaResponse(VirtualAccount va) {
        HierarchyNode node = null;
        // SAFE: Only lookup if hierarchyNodeId is not null
        if (va.getHierarchyNodeId() != null) {
            node = nodeRepository.findById(va.getHierarchyNodeId()).orElse(null);
        }
        
        // Get program name if available - SAFE: Check for null programId
        String programName = null;
        if (va.getProgramId() != null) {
            programName = programRepository.findById(va.getProgramId())
                .map(Program::getProgramName).orElse(null);
        }
        
        return SettlementVaResponse.builder()
            .id(va.getId())
            .vaNumber(va.getVaNumber())
            .vaName(va.getVaName())
            .specialType(va.getSpecialType() != null ? va.getSpecialType().name() : "REGULAR")
            .currency(va.getCurrencyCode())
            .currentBalance(va.getCurrentBalance())
            .availableBalance(va.getAvailableBalance())
            .status(va.getStatus() != null ? va.getStatus().name() : "ACTIVE")
            .hierarchyNodeId(va.getHierarchyNodeId())
            .hierarchyPath(node != null ? node.getMaterializedPath() : null)
            .hierarchyLevel(node != null ? node.getLevelNumber() : null)
            .parentNodeName(node != null && node.getParentId() != null ? 
                nodeRepository.findById(node.getParentId()).map(HierarchyNode::getNodeName).orElse(null) : null)
            .programId(va.getProgramId())
            .programName(programName)
            .createdAt(va.getCreatedAt())
            .updatedAt(va.getUpdatedAt())
            .build();
    }

    private RecentTransactionResponse toRecentTransactionResponse(Transaction txn) {
        String sourceVaNumber = null;
        // SAFE: Only lookup if counterpartyVaId is not null
        if (txn.getCounterpartyVaId() != null) {
            sourceVaNumber = vaRepository.findById(txn.getCounterpartyVaId())
                .map(VirtualAccount::getVaNumber).orElse(null);
        }
        
        return RecentTransactionResponse.builder()
            .id(txn.getId())
            .referenceNumber(txn.getReferenceNumber())
            .movementType(txn.getMovementType() != null ? txn.getMovementType().name() : null)
            .amount(txn.getAmount())
            .currency(txn.getCurrencyCode())
            .balanceAfter(txn.getBalanceAfter())
            .sourceVaNumber(sourceVaNumber)
            .description(txn.getDescription())
            .correlationId(txn.getCorrelationId())
            .transactionDate(txn.getTransactionDate())
            .build();
    }
    
    private List<CoveredVaResponse> getCoveredVas(VirtualAccount settlementVa) {
        if (settlementVa.getHierarchyNodeId() == null) {
            return Collections.emptyList();
        }
        
        // Find VAs under this hierarchy node that would post fees to this Settlement VA
        HierarchyNode node = nodeRepository.findById(settlementVa.getHierarchyNodeId()).orElse(null);
        if (node == null) {
            return Collections.emptyList();
        }
        
        // Get all VAs in the program that are under this node's path
        List<VirtualAccount> allProgramVas = vaRepository.findByProgramId(settlementVa.getProgramId());
        
        return allProgramVas.stream()
            .filter(va -> va.getSpecialType() == VaSpecialType.REGULAR)
            .filter(va -> {
                if (va.getHierarchyNodeId() == null) return false;
                HierarchyNode vaNode = nodeRepository.findById(va.getHierarchyNodeId()).orElse(null);
                if (vaNode == null) return false;
                // Check if VA's path starts with Settlement VA's path
                return vaNode.getMaterializedPath() != null && 
                    vaNode.getMaterializedPath().startsWith(node.getMaterializedPath());
            })
            .map(va -> CoveredVaResponse.builder()
                .vaId(va.getId())
                .vaNumber(va.getVaNumber())
                .vaName(va.getVaName())
                .hierarchyPath(nodeRepository.findById(va.getHierarchyNodeId())
                    .map(HierarchyNode::getMaterializedPath).orElse(null))
                .build())
            .collect(Collectors.toList());
    }
    
    private String buildResolutionPath(VirtualAccount sourceVa, VirtualAccount settlementVa) {
        if (settlementVa == null) {
            return "No Settlement VA found";
        }
        
        StringBuilder path = new StringBuilder();
        path.append("Source: ").append(sourceVa.getVaNumber());
        
        if (sourceVa.getHierarchyNodeId() != null) {
            HierarchyNode sourceNode = nodeRepository.findById(sourceVa.getHierarchyNodeId()).orElse(null);
            if (sourceNode != null) {
                path.append(" (").append(sourceNode.getMaterializedPath()).append(")");
            }
        }
        
        path.append(" → Target: ").append(settlementVa.getVaNumber());
        
        if (settlementVa.getSpecialType() == VaSpecialType.EXCEPTION) {
            path.append(" [Exception Fallback]");
        }
        
        return path.toString();
    }
}