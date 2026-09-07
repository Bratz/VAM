package com.bank.vam.controller;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.dto.ProgramTypeConfigDto;
import com.bank.vam.dto.VirtualAccountDto;
import com.bank.vam.entity.Program;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.entity.VirtualAccount.AccountType;
import com.bank.vam.entity.VirtualAccount.VaStatus;
import com.bank.vam.entity.hierarchy.HierarchyNode;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.ProgramRepository;
import com.bank.vam.service.VirtualAccountService;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.HierarchyNodeRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Virtual Account REST Controller - Enhanced with full feature support.
 * 
 * Endpoints:
 * - CRUD operations with full response mapping
 * - Limits management (update, reset)
 * - KYC management (update, verify)
 * - MCC restrictions management
 * - Hierarchy management
 * - Status management with reasons
 * - Program type configuration
 * - Statistics
 * - Bulk operations
 * 
 * Backward compatible: All existing endpoints continue to work.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/virtual-accounts")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "Virtual Accounts", description = "Virtual Account Management APIs")
public class VirtualAccountController {

    private final VirtualAccountService virtualAccountService;
    private final VirtualAccountRepository virtualAccountRepository;
    private final ProgramRepository programRepository;
    private final HierarchyNodeRepository hierarchyNodeRepository;
    private final com.bank.vam.config.MarketProfileProperties marketProfile;

    // ========================================================================
    // LIST & SEARCH ENDPOINTS (Enhanced with Response DTOs)
    // ========================================================================

    /**
     * Get all virtual accounts with pagination
     * GET /api/v1/virtual-accounts?page=0&size=10&sort=createdAt,desc
     */
    @GetMapping
    @Operation(summary = "Get all virtual accounts", 
               description = "Returns paginated list of virtual accounts with optional filtering")
    public ResponseEntity<ApiResponse<List<VirtualAccountDto.Response>>> getAll(
            @Parameter(description = "Filter by status") 
            @RequestParam(required = false) VirtualAccount.VaStatus status,
            @Parameter(description = "Filter by wallet type") 
            @RequestParam(required = false) String walletType,
            @Parameter(description = "Filter by currency") 
            @RequestParam(required = false) String currencyCode,
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        
        Page<VirtualAccount> page;
        
        if (status != null) {
            page = virtualAccountRepository.findByStatus(status, pageable);
        } else if (walletType != null) {
            page = virtualAccountRepository.findByWalletType(walletType, pageable);
        } else if (currencyCode != null) {
            page = virtualAccountRepository.findByCurrencyCode(currencyCode, pageable);
        } else {
            page = virtualAccountRepository.findAll(pageable);
        }
        
        List<VirtualAccountDto.Response> responses = page.getContent().stream()
                .map(virtualAccountService::toResponse)
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.paged(
                responses,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements()));
    }

    /**
     * Get virtual account by ID with full details
     * GET /api/v1/virtual-accounts/{id}
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get virtual account by ID", 
               description = "Returns full virtual account details including limits, KYC, and restrictions")
    public ResponseEntity<ApiResponse<VirtualAccountDto.Response>> getById(
            @Parameter(description = "Virtual account ID") @PathVariable UUID id) {
        VirtualAccountDto.Response response = virtualAccountService.getByIdWithDetails(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Get virtual account summary by ID (lightweight)
     * GET /api/v1/virtual-accounts/{id}/summary
     */
    @GetMapping("/{id}/summary")
    @Operation(summary = "Get virtual account summary", 
               description = "Returns lightweight summary for lists and dropdowns")
    public ResponseEntity<ApiResponse<VirtualAccountDto.Summary>> getSummary(
            @Parameter(description = "Virtual account ID") @PathVariable UUID id) {
        VirtualAccount va = virtualAccountService.getById(id);
        return ResponseEntity.ok(ApiResponse.success(virtualAccountService.toSummary(va)));
    }

    /**
     * Get virtual account by VA number
     * GET /api/v1/virtual-accounts/by-number/{vaNumber}
     */
    @GetMapping("/by-number/{vaNumber}")
    @Operation(summary = "Get virtual account by VA number")
    public ResponseEntity<ApiResponse<VirtualAccountDto.Response>> getByVaNumber(
            @PathVariable String vaNumber) {
        VirtualAccount va = virtualAccountService.getByVaNumber(vaNumber);
        return ResponseEntity.ok(ApiResponse.success(virtualAccountService.toResponse(va)));
    }

    /**
     * Resolve virtual accounts by scope - all VAs under a hierarchy node (including
     * descendants) or all VAs owned by a legal entity. Used for bulk enrollment
     * (notional pool / sweep rule setup) instead of one-by-one account picking.
     * GET /api/v1/virtual-accounts/by-scope?hierarchyNodeId={id}
     * GET /api/v1/virtual-accounts/by-scope?ownerEntityId={id}
     */
    @GetMapping("/by-scope")
    @Operation(summary = "Resolve virtual accounts by scope",
               description = "Returns the full matching list of VAs under a hierarchy node (descendant-inclusive) " +
                       "or owned by a legal entity. Exactly one of hierarchyNodeId/ownerEntityId is required. " +
                       "Not paginated - callers need the complete set for bulk enrollment.")
    public ResponseEntity<ApiResponse<List<VirtualAccountDto.Summary>>> getByScope(
            @Parameter(description = "Hierarchy node ID - resolves to all VAs under this node, including descendants")
            @RequestParam(required = false) UUID hierarchyNodeId,
            @Parameter(description = "Legal entity ID - resolves to all VAs owned by this entity")
            @RequestParam(required = false) UUID ownerEntityId) {

        if ((hierarchyNodeId == null) == (ownerEntityId == null)) {
            throw new BusinessException("Exactly one of hierarchyNodeId or ownerEntityId is required");
        }

        List<VirtualAccount> accounts;
        if (hierarchyNodeId != null) {
            HierarchyNode node = hierarchyNodeRepository.findById(hierarchyNodeId)
                    .orElseThrow(() -> new ResourceNotFoundException("Hierarchy node not found: " + hierarchyNodeId));
            accounts = virtualAccountRepository.findByHierarchyPathPrefix(node.getMaterializedPath());
        } else {
            accounts = virtualAccountRepository.findByOwningEntityId(ownerEntityId);
        }

        List<VirtualAccountDto.Summary> summaries = accounts.stream()
                .map(virtualAccountService::toSummary)
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(summaries));
    }

    /**
     * Resolve virtual accounts by VA number - bulk lookup for CSV-based enrollment.
     * POST /api/v1/virtual-accounts/resolve-by-numbers
     */
    @PostMapping("/resolve-by-numbers")
    @Operation(summary = "Resolve virtual accounts by VA number",
               description = "Bulk-resolves a list of VA numbers to their accounts for CSV-based enrollment. " +
                       "Returns matched summaries plus any input numbers that could not be found.")
    public ResponseEntity<ApiResponse<ResolveByNumbersResponse>> resolveByNumbers(
            @Valid @RequestBody ResolveByNumbersRequest request) {

        List<VirtualAccount> matched = virtualAccountRepository.findByVaNumberIn(request.getAccountNumbers());

        // ponytail: exact match against matched.vaNumber only - a CSV entry that differs by
        // case/whitespace from the stored vaNumber is reported unmatched, not fuzzy-resolved.
        java.util.Set<String> matchedNumbers = matched.stream()
                .map(VirtualAccount::getVaNumber)
                .collect(Collectors.toSet());

        List<String> unmatched = request.getAccountNumbers().stream()
                .filter(number -> !matchedNumbers.contains(number))
                .collect(Collectors.toList());

        List<VirtualAccountDto.Summary> summaries = matched.stream()
                .map(virtualAccountService::toSummary)
                .collect(Collectors.toList());

        ResolveByNumbersResponse response = ResolveByNumbersResponse.builder()
                .matched(summaries)
                .unmatched(unmatched)
                .build();

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Get virtual account by VIBAN
     * GET /api/v1/virtual-accounts/by-viban/{viban}
     */
    @GetMapping("/by-viban/{viban}")
    @Operation(summary = "Get virtual account by VIBAN")
    public ResponseEntity<ApiResponse<VirtualAccountDto.Response>> getByViban(
            @PathVariable String viban) {
        VirtualAccount va = virtualAccountService.getByViban(viban);
        return ResponseEntity.ok(ApiResponse.success(virtualAccountService.toResponse(va)));
    }

    /**
     * Search virtual accounts by name, VA number, or VIBAN
     * GET /api/v1/virtual-accounts/search?query=xxx&page=0&size=10
     */
    @GetMapping("/search")
    @Operation(summary = "Search virtual accounts", 
               description = "Search by VA number, name, or VIBAN")
    public ResponseEntity<ApiResponse<List<VirtualAccountDto.Response>>> search(
            @Parameter(description = "Search query") @RequestParam String query,
            @PageableDefault(size = 10) Pageable pageable) {
        Page<VirtualAccount> page = virtualAccountService.search(query, pageable);
        List<VirtualAccountDto.Response> responses = page.getContent().stream()
                .map(virtualAccountService::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.paged(
                responses,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements()));
    }

    /**
     * Get virtual accounts by corporate ID
     * GET /api/v1/virtual-accounts/corporate/{corporateId}?page=0&size=10
     */
    @GetMapping("/corporate/{corporateId}")
    @Operation(summary = "Get virtual accounts by corporate")
    public ResponseEntity<ApiResponse<List<VirtualAccountDto.Response>>> getByCorporateId(
            @PathVariable UUID corporateId,
            @PageableDefault(size = 10) Pageable pageable) {
        Page<VirtualAccount> page = virtualAccountService.getByCorporateId(corporateId, pageable);
        List<VirtualAccountDto.Response> responses = page.getContent().stream()
                .map(virtualAccountService::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.paged(
                responses,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements()));
    }

    /**
     * Get virtual accounts by program ID
     * GET /api/v1/virtual-accounts/program/{programId}?page=0&size=10
     */
    @GetMapping("/program/{programId}")
    @Operation(summary = "Get virtual accounts by program")
    public ResponseEntity<ApiResponse<List<VirtualAccountDto.Response>>> getByProgramId(
            @PathVariable UUID programId,
            @PageableDefault(size = 10) Pageable pageable) {
        Page<VirtualAccount> page = virtualAccountService.getByProgramId(programId, pageable);
        List<VirtualAccountDto.Response> responses = page.getContent().stream()
                .map(virtualAccountService::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.paged(
                responses,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements()));
    }

    /**
     * Get INTERCOMPANY virtual accounts by program
     * GET /api/v1/virtual-accounts/program/{programId}/intercompany
     */
    @GetMapping("/program/{programId}/intercompany")
    @Operation(summary = "Get INTERCOMPANY VAs by program",
               description = "Returns all virtual accounts with INTERCOMPANY category for a specific program")
    public ResponseEntity<ApiResponse<List<VirtualAccountDto.Response>>> getIntercompanyByProgram(
            @Parameter(description = "Program ID") @PathVariable UUID programId) {
        log.info("GET /api/v1/virtual-accounts/program/{}/intercompany", programId);
        List<VirtualAccount> accounts = virtualAccountRepository.findByProgramIdAndAccountCategory(
            programId, AccountCategory.INTERCOMPANY);
        List<VirtualAccountDto.Response> responses = accounts.stream()
            .map(virtualAccountService::toResponse)
            .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(responses,
            String.format("Found %d INTERCOMPANY accounts", responses.size())));
    }

    /**
     * Get virtual accounts by category for a program
     * GET /api/v1/virtual-accounts/program/{programId}/category/{category}
     */
    @GetMapping("/program/{programId}/category/{category}")
    @Operation(summary = "Get VAs by category",
               description = "Returns all virtual accounts with specified category for a program")
    public ResponseEntity<ApiResponse<List<VirtualAccountDto.Response>>> getByProgramAndCategory(
            @Parameter(description = "Program ID") @PathVariable UUID programId,
            @Parameter(description = "Account category (TRANSACTION, INTERCOMPANY, etc.)") @PathVariable String category) {
        log.info("GET /api/v1/virtual-accounts/program/{}/category/{}", programId, category);
        AccountCategory accountCategory = parseAccountCategory(category);
        List<VirtualAccount> accounts = virtualAccountRepository.findByProgramIdAndAccountCategory(
            programId, accountCategory);
        List<VirtualAccountDto.Response> responses = accounts.stream()
            .map(virtualAccountService::toResponse)
            .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(responses,
            String.format("Found %d %s accounts", responses.size(), category)));
    }

    /**
     * Get virtual accounts by category for a corporate
     * GET /api/v1/virtual-accounts/corporate/{corporateId}/category/{category}
     */
    @GetMapping("/corporate/{corporateId}/category/{category}")
    @Operation(summary = "Get VAs by category for corporate",
               description = "Returns all virtual accounts with specified category for a corporate")
    public ResponseEntity<ApiResponse<List<VirtualAccountDto.Response>>> getByCorporateAndCategory(
            @Parameter(description = "Corporate ID") @PathVariable UUID corporateId,
            @Parameter(description = "Account category (TRANSACTION, INTERCOMPANY, etc.)") @PathVariable String category) {
        log.info("GET /api/v1/virtual-accounts/corporate/{}/category/{}", corporateId, category);
        AccountCategory accountCategory = parseAccountCategory(category);
        List<VirtualAccount> accounts = virtualAccountRepository.findByCorporateIdAndAccountCategory(
            corporateId, accountCategory);
        List<VirtualAccountDto.Response> responses = accounts.stream()
            .map(virtualAccountService::toResponse)
            .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(responses,
            String.format("Found %d %s accounts for corporate", responses.size(), category)));
    }

    /**
     * Get virtual accounts by status
     * GET /api/v1/virtual-accounts/status/{status}?page=0&size=10
     */
    @GetMapping("/status/{status}")
    @Operation(summary = "Get virtual accounts by status")
    public ResponseEntity<ApiResponse<List<VirtualAccountDto.Response>>> getByStatus(
            @PathVariable VirtualAccount.VaStatus status,
            @PageableDefault(size = 10) Pageable pageable) {
        Page<VirtualAccount> page = virtualAccountRepository.findByStatus(status, pageable);
        List<VirtualAccountDto.Response> responses = page.getContent().stream()
                .map(virtualAccountService::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.paged(
                responses,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements()));
    }

    // ========================================================================
    // CREATE & UPDATE ENDPOINTS (Enhanced)
    // ========================================================================

    /**
     * Create a new virtual account with program inheritance
     * POST /api/v1/virtual-accounts
     */
    @PostMapping
    @Operation(summary = "Create virtual account", 
               description = "Create a new virtual account with optional program inheritance")
    public ResponseEntity<ApiResponse<VirtualAccountDto.Response>> create(
            @Valid @RequestBody VirtualAccountDto.CreateRequest request) {
        log.info("Creating virtual account: {}", request.getVaName());
        VirtualAccount va = virtualAccountService.create(request);
        return ResponseEntity.ok(ApiResponse.success(
                virtualAccountService.toResponse(va), 
                "Virtual account created successfully"));
    }

    /**
     * Update a virtual account
     * PUT /api/v1/virtual-accounts/{id}
     */
    @PutMapping("/{id}")
    @Operation(summary = "Update virtual account", 
               description = "Update virtual account details (name, metadata, hierarchy, etc.)")
    public ResponseEntity<ApiResponse<VirtualAccountDto.Response>> update(
            @PathVariable UUID id,
            @Valid @RequestBody VirtualAccountDto.UpdateRequest request) {
        log.info("Updating virtual account: {}", id);
        VirtualAccount va = virtualAccountService.update(id, request);
        return ResponseEntity.ok(ApiResponse.success(
                virtualAccountService.toResponse(va), 
                "Virtual account updated successfully"));
    }

    // ========================================================================
    // LIMITS MANAGEMENT ENDPOINTS (NEW)
    // ========================================================================

    /**
     * Update spending and topup limits
     * PATCH /api/v1/virtual-accounts/{id}/limits
     */
    @PatchMapping("/{id}/limits")
    @Operation(summary = "Update limits", 
               description = "Update spending limits (per-transaction, daily, weekly, monthly, annual) and topup limits")
    public ResponseEntity<ApiResponse<VirtualAccountDto.Response>> updateLimits(
            @Parameter(description = "Virtual account ID") @PathVariable UUID id,
            @Valid @RequestBody VirtualAccountDto.LimitsUpdateRequest request) {
        log.info("Updating limits for VA: {}", id);
        VirtualAccount va = virtualAccountService.updateLimits(id, request);
        return ResponseEntity.ok(ApiResponse.success(
                virtualAccountService.toResponse(va),
                "Limits updated successfully"));
    }

    /**
     * Get current limits and usage
     * GET /api/v1/virtual-accounts/{id}/limits
     */
    @GetMapping("/{id}/limits")
    @Operation(summary = "Get limits and usage", 
               description = "Get current limit configuration and usage statistics")
    public ResponseEntity<ApiResponse<VirtualAccountDto.LimitsInfo>> getLimits(
            @PathVariable UUID id) {
        VirtualAccountDto.Response va = virtualAccountService.getByIdWithDetails(id);
        return ResponseEntity.ok(ApiResponse.success(va.getLimits()));
    }

    /**
     * Get limits usage only
     * GET /api/v1/virtual-accounts/{id}/limits/usage
     */
    @GetMapping("/{id}/limits/usage")
    @Operation(summary = "Get limits usage", 
               description = "Get current usage statistics and percentages")
    public ResponseEntity<ApiResponse<VirtualAccountDto.LimitsUsage>> getLimitsUsage(
            @PathVariable UUID id) {
        VirtualAccountDto.Response va = virtualAccountService.getByIdWithDetails(id);
        return ResponseEntity.ok(ApiResponse.success(va.getLimitsUsage()));
    }

    /**
     * Reset daily usage counters
     * POST /api/v1/virtual-accounts/{id}/limits/reset/daily
     */
    @PostMapping("/{id}/limits/reset/daily")
    @Operation(summary = "Reset daily limits", 
               description = "Reset daily spending and topup usage counters to zero")
    public ResponseEntity<ApiResponse<VirtualAccountDto.Response>> resetDailyLimits(
            @PathVariable UUID id) {
        log.info("Resetting daily limits for VA: {}", id);
        VirtualAccount va = virtualAccountService.resetDailyLimits(id);
        return ResponseEntity.ok(ApiResponse.success(
                virtualAccountService.toResponse(va),
                "Daily limits reset successfully"));
    }

    /**
     * Reset weekly usage counters
     * POST /api/v1/virtual-accounts/{id}/limits/reset/weekly
     */
    @PostMapping("/{id}/limits/reset/weekly")
    @Operation(summary = "Reset weekly limits", 
               description = "Reset weekly spending usage counter to zero")
    public ResponseEntity<ApiResponse<VirtualAccountDto.Response>> resetWeeklyLimits(
            @PathVariable UUID id) {
        log.info("Resetting weekly limits for VA: {}", id);
        VirtualAccount va = virtualAccountService.resetWeeklyLimits(id);
        return ResponseEntity.ok(ApiResponse.success(
                virtualAccountService.toResponse(va),
                "Weekly limits reset successfully"));
    }

    /**
     * Reset monthly usage counters
     * POST /api/v1/virtual-accounts/{id}/limits/reset/monthly
     */
    @PostMapping("/{id}/limits/reset/monthly")
    @Operation(summary = "Reset monthly limits", 
               description = "Reset monthly spending and topup usage counters to zero")
    public ResponseEntity<ApiResponse<VirtualAccountDto.Response>> resetMonthlyLimits(
            @PathVariable UUID id) {
        log.info("Resetting monthly limits for VA: {}", id);
        VirtualAccount va = virtualAccountService.resetMonthlyLimits(id);
        return ResponseEntity.ok(ApiResponse.success(
                virtualAccountService.toResponse(va),
                "Monthly limits reset successfully"));
    }

    /**
     * Reset annual usage counters
     * POST /api/v1/virtual-accounts/{id}/limits/reset/annual
     */
    @PostMapping("/{id}/limits/reset/annual")
    @Operation(summary = "Reset annual limits", 
               description = "Reset annual spending usage counter to zero")
    public ResponseEntity<ApiResponse<VirtualAccountDto.Response>> resetAnnualLimits(
            @PathVariable UUID id) {
        log.info("Resetting annual limits for VA: {}", id);
        VirtualAccount va = virtualAccountService.resetAnnualLimits(id);
        return ResponseEntity.ok(ApiResponse.success(
                virtualAccountService.toResponse(va),
                "Annual limits reset successfully"));
    }

    // ========================================================================
    // KYC MANAGEMENT ENDPOINTS (NEW)
    // ========================================================================

    /**
     * Update KYC status and level
     * PATCH /api/v1/virtual-accounts/{id}/kyc
     */
    @PatchMapping("/{id}/kyc")
    @Operation(summary = "Update KYC", 
               description = "Update KYC level (0-3) and expiry date. Higher levels enable higher limits.")
    public ResponseEntity<ApiResponse<VirtualAccountDto.Response>> updateKyc(
            @PathVariable UUID id,
            @Valid @RequestBody VirtualAccountDto.KycUpdateRequest request) {
        log.info("Updating KYC for VA: {} to level {}", id, request.getKycLevel());
        VirtualAccount va = virtualAccountService.updateKyc(id, request);
        return ResponseEntity.ok(ApiResponse.success(
                virtualAccountService.toResponse(va),
                "KYC updated to level " + request.getKycLevel()));
    }

    /**
     * Verify KYC (shortcut to set verified=true with level)
     * POST /api/v1/virtual-accounts/{id}/kyc/verify
     */
    @PostMapping("/{id}/kyc/verify")
    @Operation(summary = "Verify KYC", 
               description = "Quick verification - sets KYC verified and optionally upgrades level")
    public ResponseEntity<ApiResponse<VirtualAccountDto.Response>> verifyKyc(
            @PathVariable UUID id,
            @Parameter(description = "KYC level (1-3)") 
            @RequestParam(defaultValue = "1") Integer level) {
        log.info("Verifying KYC for VA: {} at level {}", id, level);
        VirtualAccount va = virtualAccountService.verifyKyc(id, level);
        return ResponseEntity.ok(ApiResponse.success(
                virtualAccountService.toResponse(va),
                "KYC verified at level " + level));
    }

    // ========================================================================
    // MCC RESTRICTIONS ENDPOINTS (NEW)
    // ========================================================================

    /**
     * Update MCC and merchant restrictions
     * PUT /api/v1/virtual-accounts/{id}/mcc-restrictions
     */
    @PutMapping("/{id}/mcc-restrictions")
    @Operation(summary = "Update MCC restrictions", 
               description = "Set or update MCC whitelist/blacklist, merchant whitelist, and country restrictions")
    public ResponseEntity<ApiResponse<VirtualAccountDto.Response>> updateMccRestrictions(
            @PathVariable UUID id,
            @Valid @RequestBody VirtualAccountDto.MccRestrictionsRequest request) {
        log.info("Updating MCC restrictions for VA: {}", id);
        VirtualAccount va = virtualAccountService.updateMccRestrictions(id, request);
        return ResponseEntity.ok(ApiResponse.success(
                virtualAccountService.toResponse(va),
                "MCC restrictions updated"));
    }

    /**
     * Get MCC restrictions
     * GET /api/v1/virtual-accounts/{id}/mcc-restrictions
     */
    @GetMapping("/{id}/mcc-restrictions")
    @Operation(summary = "Get MCC restrictions", 
               description = "Get current MCC and merchant restrictions")
    public ResponseEntity<ApiResponse<VirtualAccountDto.MccRestrictions>> getMccRestrictions(
            @PathVariable UUID id) {
        VirtualAccountDto.Response va = virtualAccountService.getByIdWithDetails(id);
        return ResponseEntity.ok(ApiResponse.success(va.getMccRestrictions()));
    }

    // ========================================================================
    // STATUS MANAGEMENT ENDPOINTS (Enhanced)
    // ========================================================================

    /**
     * Update virtual account status
     * PATCH /api/v1/virtual-accounts/{id}/status
     */
    @PatchMapping("/{id}/status")
    @Operation(summary = "Update status", 
               description = "Change account status (ACTIVE, INACTIVE, SUSPENDED, BLOCKED, CLOSED)")
    public ResponseEntity<ApiResponse<VirtualAccountDto.Response>> updateStatus(
            @PathVariable UUID id,
            @Parameter(description = "New status") 
            @RequestParam VirtualAccount.VaStatus status,
            @Parameter(description = "Reason for status change (required for SUSPENDED/BLOCKED)") 
            @RequestParam(required = false) String reason) {
        log.info("Updating status for VA: {} to {}", id, status);
        VirtualAccount va = virtualAccountService.updateStatus(id, status, reason);
        return ResponseEntity.ok(ApiResponse.success(
                virtualAccountService.toResponse(va), 
                "Status updated to " + status));
    }

    /**
     * Suspend account
     * POST /api/v1/virtual-accounts/{id}/suspend
     */
    @PostMapping("/{id}/suspend")
    @Operation(summary = "Suspend account", 
               description = "Temporarily suspend the account with a reason")
    public ResponseEntity<ApiResponse<VirtualAccountDto.Response>> suspend(
            @PathVariable UUID id,
            @Parameter(description = "Suspension reason") 
            @RequestParam String reason) {
        log.info("Suspending VA: {} - Reason: {}", id, reason);
        VirtualAccount va = virtualAccountService.suspend(id, reason);
        return ResponseEntity.ok(ApiResponse.success(
                virtualAccountService.toResponse(va),
                "Account suspended"));
    }

    /**
     * Block account
     * POST /api/v1/virtual-accounts/{id}/block
     */
    @PostMapping("/{id}/block")
    @Operation(summary = "Block account", 
               description = "Block the account (more severe than suspend)")
    public ResponseEntity<ApiResponse<VirtualAccountDto.Response>> block(
            @PathVariable UUID id,
            @Parameter(description = "Block reason") 
            @RequestParam String reason) {
        log.info("Blocking VA: {} - Reason: {}", id, reason);
        VirtualAccount va = virtualAccountService.block(id, reason);
        return ResponseEntity.ok(ApiResponse.success(
                virtualAccountService.toResponse(va),
                "Account blocked"));
    }

    /**
     * Reactivate account
     * POST /api/v1/virtual-accounts/{id}/reactivate
     */
    @PostMapping("/{id}/reactivate")
    @Operation(summary = "Reactivate account", 
               description = "Reactivate a suspended or blocked account")
    public ResponseEntity<ApiResponse<VirtualAccountDto.Response>> reactivate(
            @PathVariable UUID id) {
        log.info("Reactivating VA: {}", id);
        VirtualAccount va = virtualAccountService.reactivate(id);
        return ResponseEntity.ok(ApiResponse.success(
                virtualAccountService.toResponse(va),
                "Account reactivated"));
    }

    /**
     * Close account
     * POST /api/v1/virtual-accounts/{id}/close
     */
    @PostMapping("/{id}/close")
    @Operation(summary = "Close account",
               description = "Permanently close the account (balance must be zero)")
    public ResponseEntity<ApiResponse<VirtualAccountDto.Response>> close(
            @PathVariable UUID id) {
        log.info("Closing VA: {}", id);
        VirtualAccount va = virtualAccountService.close(id);
        return ResponseEntity.ok(ApiResponse.success(
                virtualAccountService.toResponse(va),
                "Account closed"));
    }

    // ========================================================================
    // PHASE 1: PUBLISH/UNPUBLISH ENDPOINTS (Published VA = Has VIBAN)
    // ========================================================================

    /**
     * Publish a VA - Assign VIBAN for external payments.
     * POST /api/v1/virtual-accounts/{id}/publish
     */
    @PostMapping("/{id}/publish")
    @Operation(summary = "Publish VA",
               description = "Assign a VIBAN to make the VA externally visible. Published VAs can receive external payments via camt.054.")
    public ResponseEntity<ApiResponse<VirtualAccountDto.Response>> publish(
            @Parameter(description = "Virtual account ID") @PathVariable UUID id,
            @RequestBody(required = false) VirtualAccountDto.PublishRequest request) {
        log.info("Publishing VA: {}", id);

        String viban = request != null ? request.getViban() : null;
        String publishedBy = request != null && request.getPublishedBy() != null
            ? request.getPublishedBy()
            : "SYSTEM";

        VirtualAccount va = virtualAccountService.publish(id, viban, publishedBy);
        return ResponseEntity.ok(ApiResponse.success(
                virtualAccountService.toResponse(va),
                "VA published with VIBAN: " + va.getViban()));
    }

    /**
     * Unpublish a VA - Remove external visibility.
     * POST /api/v1/virtual-accounts/{id}/unpublish
     */
    @PostMapping("/{id}/unpublish")
    @Operation(summary = "Unpublish VA",
               description = "Remove external visibility. The VIBAN is retained for audit but VA won't receive external payments.")
    public ResponseEntity<ApiResponse<VirtualAccountDto.Response>> unpublish(
            @Parameter(description = "Virtual account ID") @PathVariable UUID id,
            @Valid @RequestBody VirtualAccountDto.UnpublishRequest request) {
        log.info("Unpublishing VA: {} - Reason: {}", id, request.getReason());

        VirtualAccount va = virtualAccountService.unpublish(id, request.getReason());
        return ResponseEntity.ok(ApiResponse.success(
                virtualAccountService.toResponse(va),
                "VA unpublished"));
    }

    /**
     * Suspend publish status temporarily.
     * POST /api/v1/virtual-accounts/{id}/suspend-publish
     */
    @PostMapping("/{id}/suspend-publish")
    @Operation(summary = "Suspend publish status",
               description = "Temporarily suspend a published VA. Can be re-published later.")
    public ResponseEntity<ApiResponse<VirtualAccountDto.Response>> suspendPublish(
            @Parameter(description = "Virtual account ID") @PathVariable UUID id,
            @Parameter(description = "Suspension reason") @RequestParam String reason) {
        log.info("Suspending publish for VA: {} - Reason: {}", id, reason);

        VirtualAccount va = virtualAccountService.suspendPublish(id, reason);
        return ResponseEntity.ok(ApiResponse.success(
                virtualAccountService.toResponse(va),
                "VA publish status suspended"));
    }

    /**
     * Re-publish a suspended VA.
     * POST /api/v1/virtual-accounts/{id}/republish
     */
    @PostMapping("/{id}/republish")
    @Operation(summary = "Re-publish VA",
               description = "Re-publish a previously suspended VA using its existing VIBAN.")
    public ResponseEntity<ApiResponse<VirtualAccountDto.Response>> republish(
            @Parameter(description = "Virtual account ID") @PathVariable UUID id,
            @Parameter(description = "User republishing") @RequestParam(defaultValue = "SYSTEM") String publishedBy) {
        log.info("Re-publishing VA: {}", id);

        VirtualAccount va = virtualAccountService.republish(id, publishedBy);
        return ResponseEntity.ok(ApiResponse.success(
                virtualAccountService.toResponse(va),
                "VA re-published with VIBAN: " + va.getViban()));
    }

    /**
     * Get publish status information.
     * GET /api/v1/virtual-accounts/{id}/publish-status
     */
    @GetMapping("/{id}/publish-status")
    @Operation(summary = "Get publish status",
               description = "Get detailed publish status information for a VA.")
    public ResponseEntity<ApiResponse<VirtualAccountDto.PublishStatusInfo>> getPublishStatus(
            @Parameter(description = "Virtual account ID") @PathVariable UUID id) {
        VirtualAccountDto.PublishStatusInfo status = virtualAccountService.getPublishStatus(id);
        return ResponseEntity.ok(ApiResponse.success(status));
    }

    // ========================================================================
    // HIERARCHY MANAGEMENT ENDPOINTS (NEW)
    // ========================================================================

    /**
     * Update hierarchy node assignment
     * PUT /api/v1/virtual-accounts/{id}/hierarchy
     */
    @PutMapping("/{id}/hierarchy")
    @Operation(summary = "Update hierarchy", 
               description = "Assign or update the hierarchy node for this account")
    public ResponseEntity<ApiResponse<VirtualAccountDto.Response>> updateHierarchy(
            @PathVariable UUID id,
            @Parameter(description = "Hierarchy node ID to assign") 
            @RequestParam UUID hierarchyNodeId) {
        log.info("Updating hierarchy for VA: {} to node: {}", id, hierarchyNodeId);
        VirtualAccount va = virtualAccountService.updateHierarchy(id, hierarchyNodeId);
        return ResponseEntity.ok(ApiResponse.success(
                virtualAccountService.toResponse(va),
                "Hierarchy updated"));
    }

    /**
     * Get all VAs under a hierarchy node
     * GET /api/v1/virtual-accounts/by-hierarchy/{nodeId}
     */
    @GetMapping("/by-hierarchy/{nodeId}")
    @Operation(summary = "Get VAs by hierarchy node", 
               description = "Get all virtual accounts assigned to a hierarchy node (optionally including descendants)")
    public ResponseEntity<ApiResponse<List<VirtualAccountDto.Response>>> getByHierarchy(
            @Parameter(description = "Hierarchy node ID") 
            @PathVariable UUID nodeId,
            @Parameter(description = "Include accounts from descendant nodes") 
            @RequestParam(defaultValue = "false") boolean includeDescendants,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<VirtualAccount> page = virtualAccountService.getByHierarchyNode(nodeId, includeDescendants, pageable);
        List<VirtualAccountDto.Response> responses = page.getContent().stream()
                .map(virtualAccountService::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.paged(
                responses, 
                page.getNumber(), 
                page.getSize(), 
                page.getTotalElements()));
    }

    // ========================================================================
    // BALANCE ENDPOINTS
    // ========================================================================

    /**
     * Get balance information
     * GET /api/v1/virtual-accounts/{id}/balance
     */
    @GetMapping("/{id}/balance")
    @Operation(summary = "Get balance", 
               description = "Get current balance, available balance, and held balance")
    public ResponseEntity<ApiResponse<VirtualAccountDto.BalanceResponse>> getBalance(
            @PathVariable UUID id) {
        VirtualAccount va = virtualAccountService.getById(id);
        VirtualAccountDto.BalanceResponse balance = VirtualAccountDto.BalanceResponse.builder()
                .id(va.getId())
                .vaNumber(va.getVaNumber())
                .currencyCode(va.getCurrencyCode())
                .currentBalance(va.getCurrentBalance())
                .availableBalance(va.getAvailableBalance())
                .heldBalance(va.getHeldBalance())
                .pointsBalance(va.getPointsBalance())
                .pendingPoints(va.getPendingPoints())
                .lastActivityDate(va.getLastActivityDate())
                .build();
        return ResponseEntity.ok(ApiResponse.success(balance));
    }

    /**
     * Update virtual account balance (internal use)
     * PATCH /api/v1/virtual-accounts/{id}/balance
     */
    @PatchMapping("/{id}/balance")
    @Operation(summary = "Update balance", 
               description = "Directly update balance (for internal/admin use only)")
    public ResponseEntity<ApiResponse<VirtualAccountDto.Response>> updateBalance(
            @PathVariable UUID id,
            @RequestParam BigDecimal currentBalance,
            @RequestParam BigDecimal availableBalance) {
        log.info("Updating balance for VA: {} - Current: {}, Available: {}", id, currentBalance, availableBalance);
        virtualAccountService.updateBalance(id, currentBalance, availableBalance);
        VirtualAccount va = virtualAccountService.getById(id);
        return ResponseEntity.ok(ApiResponse.success(
                virtualAccountService.toResponse(va), 
                "Balance updated successfully"));
    }

    /**
     * Credit account
     * POST /api/v1/virtual-accounts/{id}/credit
     */
    @PostMapping("/{id}/credit")
    @Operation(summary = "Credit account", 
               description = "Add funds to the account")
    public ResponseEntity<ApiResponse<VirtualAccountDto.Response>> credit(
            @PathVariable UUID id,
            @Parameter(description = "Amount to credit") 
            @RequestParam BigDecimal amount) {
        log.info("Crediting VA: {} with amount: {}", id, amount);
        VirtualAccount va = virtualAccountService.credit(id, amount);
        return ResponseEntity.ok(ApiResponse.success(
                virtualAccountService.toResponse(va),
                "Credited " + amount));
    }

    /**
     * Debit account
     * POST /api/v1/virtual-accounts/{id}/debit
     */
    @PostMapping("/{id}/debit")
    @Operation(summary = "Debit account", 
               description = "Deduct funds from the account (validates sufficient balance)")
    public ResponseEntity<ApiResponse<VirtualAccountDto.Response>> debit(
            @PathVariable UUID id,
            @Parameter(description = "Amount to debit") 
            @RequestParam BigDecimal amount) {
        log.info("Debiting VA: {} with amount: {}", id, amount);
        VirtualAccount va = virtualAccountService.debit(id, amount);
        return ResponseEntity.ok(ApiResponse.success(
                virtualAccountService.toResponse(va),
                "Debited " + amount));
    }

    // ========================================================================
    // STATISTICS ENDPOINT (Enhanced)
    // ========================================================================

    /**
     * Get virtual account statistics
     * GET /api/v1/virtual-accounts/stats
     */
    @GetMapping("/stats")
    @Operation(summary = "Get statistics", 
               description = "Get aggregated statistics for virtual accounts")
    public ResponseEntity<ApiResponse<VirtualAccountDto.VaStats>> getStats(
            @Parameter(description = "Filter by corporate ID") 
            @RequestParam(required = false) UUID corporateId,
            @Parameter(description = "Filter by program ID") 
            @RequestParam(required = false) UUID programId) {
        VirtualAccountDto.VaStats stats = virtualAccountService.getStats(corporateId, programId);
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    // ========================================================================
    // PROGRAM TYPE CONFIGURATION ENDPOINTS (NEW)
    // ========================================================================

    /**
     * Get field configuration for a program type
     * GET /api/v1/virtual-accounts/program-type-config/{programType}
     */
    @GetMapping("/program-type-config/{programType}")
    @Operation(summary = "Get program type config", 
               description = "Get field configuration, defaults, and validation rules for a program type")
    public ResponseEntity<ApiResponse<ProgramTypeConfigDto>> getProgramTypeConfig(
            @Parameter(description = "Program type (COLLECTION, WALLET, IHB, etc.)") 
            @PathVariable String programType) {
        ProgramTypeConfigDto config = virtualAccountService.getProgramTypeConfig(programType);
        if (config == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ApiResponse.success(config));
    }

    /**
     * Get all program type configurations
     * GET /api/v1/virtual-accounts/program-type-configs
     */
    @GetMapping("/program-type-configs")
    @Operation(summary = "Get all program type configs", 
               description = "Get field configurations for all program types")
    public ResponseEntity<ApiResponse<List<ProgramTypeConfigDto>>> getAllProgramTypeConfigs() {
        List<ProgramTypeConfigDto> configs = virtualAccountService.getAllProgramTypeConfigs();
        return ResponseEntity.ok(ApiResponse.success(configs));
    }

    // ========================================================================
    // BULK OPERATIONS (Enhanced)
    // ========================================================================

    /**
     * Bulk update status for multiple accounts
     * PATCH /api/v1/virtual-accounts/bulk/status
     */
    @PatchMapping("/bulk/status")
    @Operation(summary = "Bulk update status", 
               description = "Update status for multiple accounts at once")
    public ResponseEntity<ApiResponse<VirtualAccountDto.BulkOperationResponse>> bulkUpdateStatus(
            @Valid @RequestBody VirtualAccountDto.BulkStatusUpdateRequest request) {
        log.info("Bulk updating status for {} accounts to {}", request.getAccountIds().size(), request.getStatus());
        
        List<VirtualAccountDto.BulkOperationError> errors = new ArrayList<>();
        int successCount = 0;
        
        for (UUID id : request.getAccountIds()) {
            try {
                virtualAccountService.updateStatus(id, 
                        VirtualAccount.VaStatus.valueOf(request.getStatus()), 
                        request.getReason());
                successCount++;
            } catch (Exception e) {
                errors.add(VirtualAccountDto.BulkOperationError.builder()
                        .accountId(id)
                        .error(e.getMessage())
                        .errorCode("STATUS_UPDATE_FAILED")
                        .build());
            }
        }
        
        VirtualAccountDto.BulkOperationResponse response = VirtualAccountDto.BulkOperationResponse.builder()
                .totalRequested(request.getAccountIds().size())
                .successCount(successCount)
                .failureCount(errors.size())
                .errors(errors)
                .build();
        
        return ResponseEntity.ok(ApiResponse.success(response, 
                successCount + " of " + request.getAccountIds().size() + " accounts updated"));
    }

    /**
     * Bulk update limits for multiple accounts
     * PATCH /api/v1/virtual-accounts/bulk/limits
     */
    @PatchMapping("/bulk/limits")
    @Operation(summary = "Bulk update limits", 
               description = "Update limits for multiple accounts at once")
    public ResponseEntity<ApiResponse<VirtualAccountDto.BulkOperationResponse>> bulkUpdateLimits(
            @Valid @RequestBody VirtualAccountDto.BulkLimitsUpdateRequest request) {
        log.info("Bulk updating limits for {} accounts", request.getAccountIds().size());
        
        List<VirtualAccountDto.BulkOperationError> errors = new ArrayList<>();
        int successCount = 0;
        
        VirtualAccountDto.LimitsUpdateRequest limitsRequest = VirtualAccountDto.LimitsUpdateRequest.builder()
                .perTransactionLimit(request.getPerTransactionLimit())
                .dailyLimit(request.getDailyLimit())
                .weeklyLimit(request.getWeeklyLimit())
                .monthlyLimit(request.getMonthlyLimit())
                .annualLimit(request.getAnnualLimit())
                .maxBalance(request.getMaxBalance())
                .build();
        
        for (UUID id : request.getAccountIds()) {
            try {
                virtualAccountService.updateLimits(id, limitsRequest);
                successCount++;
            } catch (Exception e) {
                errors.add(VirtualAccountDto.BulkOperationError.builder()
                        .accountId(id)
                        .error(e.getMessage())
                        .errorCode("LIMITS_UPDATE_FAILED")
                        .build());
            }
        }
        
        VirtualAccountDto.BulkOperationResponse response = VirtualAccountDto.BulkOperationResponse.builder()
                .totalRequested(request.getAccountIds().size())
                .successCount(successCount)
                .failureCount(errors.size())
                .errors(errors)
                .build();
        
        return ResponseEntity.ok(ApiResponse.success(response, 
                successCount + " of " + request.getAccountIds().size() + " accounts updated"));
    }

    // ========================================================================
    // EXPORT ENDPOINT (Enhanced)
    // ========================================================================

    /**
     * Export virtual accounts (returns all matching criteria)
     * GET /api/v1/virtual-accounts/export
     */
    @GetMapping("/export")
    @Operation(summary = "Export accounts", 
               description = "Export virtual accounts matching the specified criteria")
    public ResponseEntity<ApiResponse<List<VirtualAccountDto.Response>>> exportAccounts(
            @RequestParam(required = false) VirtualAccount.VaStatus status,
            @RequestParam(required = false) UUID corporateId,
            @RequestParam(required = false) UUID programId,
            @RequestParam(required = false) String walletType) {
        
        List<VirtualAccount> accounts;
        
        if (programId != null) {
            accounts = virtualAccountRepository.findByProgramId(programId);
        } else if (corporateId != null) {
            accounts = virtualAccountService.getByCorporateId(corporateId);
        } else {
            accounts = virtualAccountRepository.findAll();
        }
        
        // Filter by status if provided
        if (status != null) {
            accounts = accounts.stream()
                    .filter(va -> va.getStatus() == status)
                    .toList();
        }
        
        // Filter by wallet type if provided
        if (walletType != null) {
            accounts = accounts.stream()
                    .filter(va -> walletType.equals(va.getWalletType()))
                    .toList();
        }
        
        List<VirtualAccountDto.Response> responses = accounts.stream()
                .map(virtualAccountService::toResponse)
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    // ========================================================================
    // LOOKUP ENDPOINTS (for dropdowns)
    // ========================================================================

    /**
     * Get summaries for dropdown/autocomplete
     * GET /api/v1/virtual-accounts/lookup
     */
    @GetMapping("/lookup")
    @Operation(summary = "Lookup accounts", 
               description = "Get lightweight account summaries for dropdowns and autocomplete")
    public ResponseEntity<ApiResponse<List<VirtualAccountDto.Summary>>> lookup(
            @Parameter(description = "Search query (VA number, name, VIBAN)") 
            @RequestParam(required = false) String query,
            @Parameter(description = "Filter by corporate ID") 
            @RequestParam(required = false) UUID corporateId,
            @Parameter(description = "Filter by program ID") 
            @RequestParam(required = false) UUID programId,
            @Parameter(description = "Maximum results") 
            @RequestParam(defaultValue = "20") int limit) {
        
        List<VirtualAccount> accounts;
        
        if (query != null && !query.isEmpty()) {
            accounts = virtualAccountRepository.searchAll(query);
        } else if (programId != null) {
            accounts = virtualAccountRepository.findByProgramId(programId);
        } else if (corporateId != null) {
            accounts = virtualAccountRepository.findByCorporateId(corporateId);
        } else {
            accounts = virtualAccountRepository.findAll();
        }
        
        List<VirtualAccountDto.Summary> summaries = accounts.stream()
                .limit(limit)
                .map(virtualAccountService::toSummary)
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.success(summaries));
    }


    /**
     * Create VA using hierarchy dimensions.
     * Auto-creates intermediate aggregation nodes if they don't exist.
     * POST /api/v1/virtual-accounts/with-dimensions
     */
    @PostMapping("/with-dimensions")
    @Operation(
        summary = "Create VA using hierarchy dimensions",
        description = "Creates a VA at the leaf level based on program hierarchy dimensions. " +
                      "Intermediate aggregation nodes are auto-created if they don't exist."
    )
    public ResponseEntity<ApiResponse<VaWithHierarchyResponse>> createVaWithDimensions(
            @Valid @RequestBody CreateVaWithDimensionsRequest request) {

        log.info("POST /api/v1/virtual-accounts/with-dimensions - Program: {}, Name: {}, Dimensions: {}",
                 request.getProgramId(), request.getVaName(), request.getHierarchyDimensions());

        // Converged flow: resolve/create the dimension node chain, then delegate
        // the leaf to VirtualAccountService.createWithParentNode — the single
        // write path (VA + hierarchy node, program VA-count, currency mirrors,
        // settlement sibling). Replaces the former controller-inline builder
        // that produced VA-table-only structures with none of those invariants.
        VirtualAccountDto.CreateRequest createRequest = VirtualAccountDto.CreateRequest.builder()
            .vaName(request.getVaName())
            .programId(request.getProgramId())
            .currencyCode(request.getCurrencyCode() != null ? request.getCurrencyCode().toUpperCase() : null)
            .accountCategory(request.getAccountCategory() != null ? request.getAccountCategory() : "TRANSACTION")
            .accountPurpose(request.getAccountPurpose())
            .owningEntityId(request.getOwningEntityId())
            .externalReference(request.getExternalReference())
            .inheritProgramDefaults(request.getInheritProgramDefaults() != null ? request.getInheritProgramDefaults() : true)
            .build();

        VirtualAccount va = virtualAccountService.createWithDimensions(createRequest, request.getHierarchyDimensions());

        String parentVaNumber = va.getParentAccountId() != null
            ? virtualAccountRepository.findById(va.getParentAccountId())
                .map(VirtualAccount::getVaNumber).orElse(null)
            : null;

        VaWithHierarchyResponse response = VaWithHierarchyResponse.builder()
            .id(va.getId())
            .vaNumber(va.getVaNumber())
            .vaName(va.getVaName())
            .currencyCode(va.getCurrencyCode())
            .accountCategory(va.getAccountCategory().name())
            .parentAccountId(va.getParentAccountId())
            .parentVaNumber(parentVaNumber)
            .hierarchyLevel(va.getHierarchyLevel())
            .hierarchyPathVa(va.getHierarchyPath())
            .owningEntityId(va.getOwningEntityId())
            .physicalAccountId(va.getPhysicalAccountId())
            .status(va.getStatus().name())
            .createdAt(va.getCreatedAt())
            .build();

        return ResponseEntity.ok(ApiResponse.success(
            response,
            "Virtual account created: " + va.getVaNumber()
        ));
    }

    @PostMapping("/with-hierarchy")
    @Operation(
        summary = "Create VA with hierarchy placement",
        description = "Creates a VA under a specific parent account (Shadow or Currency Mirror). " +
                      "Currency and physical account are inherited from the parent."
    )
    public ResponseEntity<ApiResponse<VaWithHierarchyResponse>> createVaWithHierarchy(
            @Valid @RequestBody CreateVaWithHierarchyRequest request) {

        log.info("POST /api/v1/virtual-accounts/with-hierarchy - Parent: {}, Name: {}",
                 request.getParentAccountId(), request.getVaName());
        
        // 1. Validate parent exists and get details
        VirtualAccount parent = virtualAccountRepository.findById(request.getParentAccountId())
            .orElseThrow(() -> new ResourceNotFoundException(
                "Parent VA not found: " + request.getParentAccountId()
            ));
        
        // 2. Validate parent is valid type (Shadow or Currency Mirror or ROOT)
        validateParentAccount(parent);
        
        // 3. Determine currency (inherit from parent or use requested)
        String currency = request.getCurrencyCode() != null 
            ? request.getCurrencyCode().toUpperCase()
            : parent.getCurrencyCode();
        
        if (currency == null) {
            throw new BusinessException("Currency must be specified or inherited from parent");
        }
        
        // 4. Determine physical account (inherit from parent)
        UUID physicalAccountId = request.getPhysicalAccountId() != null
            ? request.getPhysicalAccountId()
            : parent.getPhysicalAccountId();
        
        if (physicalAccountId == null) {
            physicalAccountId = parent.getLinkedPhysicalAccountId();
        }
        
        // 5. Build hierarchy path
        String vaCode = generateVaCode(request, currency);
        String hierarchyPath = parent.getHierarchyPathVa() + "/" + vaCode;
        int hierarchyLevel = parent.getHierarchyLevel() + 1;
        
        // 6. Generate VA number
        String vaNumber = generateVaNumber(request, currency, parent);
        
        // 7. Determine account category
        AccountCategory category = parseAccountCategory(request.getAccountCategory());
        
        // 8. Create the VA
        VirtualAccount va = VirtualAccount.builder()
            // Core Identity
            .vaNumber(vaNumber)
            .vaName(request.getVaName())
            .viban(request.getViban())
            .externalReference(request.getExternalReference())
            
            // Corporate & Physical
            .corporateId(request.getCorporateId())
            .physicalAccountId(physicalAccountId)
            .currencyCode(currency)
            
            // Classification
            .accountType(AccountType.VIRTUAL)
            .accountCategory(category)
            
            // Hierarchy
            .parentAccountId(request.getParentAccountId())
            .hierarchyLevel(hierarchyLevel)
            .hierarchyPathVa(hierarchyPath)
            
            // Legal Entity (ownership)
            .owningEntityId(request.getOwningEntityId())
            .owningEntityCode(getOwningEntityCode(request.getOwningEntityId(), parent))
            
            // Program (optional)
            .programId(request.getProgramId())
            
            // Initialize balances
            .currentBalance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO)
            .aggregatedBalance(BigDecimal.ZERO)
            
            // Status
            .status(VaStatus.ACTIVE)
            .build();
        
        // 9. Save
        va = virtualAccountRepository.save(va);
        
        log.info("Created VA {} under parent {} at level {} in hierarchy", 
                 va.getVaNumber(), parent.getVaNumber(), hierarchyLevel);
        
        // 10. Build response
        VaWithHierarchyResponse response = VaWithHierarchyResponse.builder()
            .id(va.getId())
            .vaNumber(va.getVaNumber())
            .vaName(va.getVaName())
            .currencyCode(va.getCurrencyCode())
            .accountCategory(va.getAccountCategory().name())
            .parentAccountId(va.getParentAccountId())
            .parentVaNumber(parent.getVaNumber())
            .hierarchyLevel(va.getHierarchyLevel())
            .hierarchyPathVa(va.getHierarchyPathVa())
            .owningEntityId(va.getOwningEntityId())
            .owningEntityCode(va.getOwningEntityCode())
            .physicalAccountId(va.getPhysicalAccountId())
            .status(va.getStatus().name())
            .createdAt(va.getCreatedAt())
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(
            response,
            "Virtual account created: " + va.getVaNumber()
        ));
    }

    /**
     * Get valid parent accounts for VA creation under a legal entity.
     */
    @GetMapping("/valid-parents/entity/{legalEntityId}")
    @Operation(
        summary = "Get valid parent accounts",
        description = "Returns Shadow Accounts and Currency Mirrors that can be parents for new VAs"
    )
    public ResponseEntity<ApiResponse<java.util.List<ParentAccountDto>>> getValidParentAccounts(
            @PathVariable UUID legalEntityId) {
        
        log.info("GET /api/v1/virtual-accounts/valid-parents/entity/{}", legalEntityId);
        
        java.util.List<VirtualAccount> validParents = virtualAccountRepository
            .findValidParentAccounts(legalEntityId);
        
        java.util.List<ParentAccountDto> dtos = validParents.stream()
            .map(this::toParentDto)
            .collect(java.util.stream.Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private void validateParentAccount(VirtualAccount parent) {
        if (parent.getAccountCategory() == null) {
            throw new BusinessException("Parent account has no category defined");
        }
        
        AccountCategory category = parent.getAccountCategory();
        
        // Valid parent types
        boolean isValidParent = category == AccountCategory.PHYSICAL_MIRROR ||
                               category == AccountCategory.CURRENCY_MIRROR ||
                               category == AccountCategory.ROOT ||
                               category == AccountCategory.AGGREGATION;
        
        if (!isValidParent) {
            throw new BusinessException(
                "Invalid parent account type: " + category + 
                ". Valid types are: PHYSICAL_MIRROR, CURRENCY_MIRROR, ROOT, AGGREGATION"
            );
        }
        
        if (parent.getStatus() != VaStatus.ACTIVE) {
            throw new BusinessException("Parent account is not active: " + parent.getVaNumber());
        }
    }

    private String generateVaCode(CreateVaWithHierarchyRequest request, String currency) {
        // Generate a short code for hierarchy path
        String baseName = request.getVaName()
            .toUpperCase()
            .replaceAll("[^A-Z0-9]", "")
            .substring(0, Math.min(8, request.getVaName().length()));
        
        return "VA-" + currency + "-" + baseName;
    }

    private String generateVaNumber(CreateVaWithHierarchyRequest request, String currency, 
                                    VirtualAccount parent) {
        // Format: VA-{CURRENCY}-{TIMESTAMP}-{RANDOM}
        String timestamp = String.valueOf(System.currentTimeMillis()).substring(7);
        String random = String.format("%04d", new java.util.Random().nextInt(10000));
        
        return "VA-" + currency + "-" + timestamp + "-" + random;
    }

    private AccountCategory parseAccountCategory(String category) {
        if (category == null || category.isBlank()) {
            return AccountCategory.TRANSACTION;
        }
        
        try {
            return AccountCategory.valueOf(category.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown account category: {}. Defaulting to TRANSACTION", category);
            return AccountCategory.TRANSACTION;
        }
    }

    private String getOwningEntityCode(UUID owningEntityId, VirtualAccount parent) {
        if (owningEntityId != null) {
            // Would normally look up from LegalEntityRepository
            // For now, inherit from parent
            return parent.getOwningEntityCode();
        }
        return parent.getOwningEntityCode();
    }

    private ParentAccountDto toParentDto(VirtualAccount va) {
        return ParentAccountDto.builder()
            .id(va.getId())
            .vaNumber(va.getVaNumber())
            .vaName(va.getVaName())
            .currencyCode(va.getCurrencyCode())
            .accountCategory(va.getAccountCategory() != null ? va.getAccountCategory().name() : null)
            .balance(va.getAccountCategory() == AccountCategory.PHYSICAL_MIRROR 
                ? va.getBankBalance() 
                : va.getCurrentBalance())
            .hierarchyLevel(va.getHierarchyLevel())
            .bankName(va.getBankName())
            .bankIban(va.getBankIban())
            .build();
    }

    // ========================================================================
    // REQUEST/RESPONSE DTOs
    // ========================================================================

    @Data
    public static class ResolveByNumbersRequest {
        @NotNull(message = "accountNumbers is required")
        private List<String> accountNumbers;
    }

    @Data
    @Builder
    public static class ResolveByNumbersResponse {
        private List<VirtualAccountDto.Summary> matched;
        private List<String> unmatched;
    }

    @Data
    public static class CreateVaWithHierarchyRequest {
        @NotBlank(message = "VA name is required")
        private String vaName;
        
        @NotNull(message = "Parent account ID is required")
        private UUID parentAccountId;
        
        @NotNull(message = "Corporate ID is required")
        private UUID corporateId;
        
        // Optional: inherited from parent if not provided
        private UUID physicalAccountId;
        
        // Optional: inherited from parent if not provided
        private String currencyCode;
        
        // Account category: TRANSACTION, COLLECTION, DISBURSEMENT, ESCROW, INTERCOMPANY
        private String accountCategory;
        
        // Legal Entity that owns this VA
        private UUID owningEntityId;
        
        // Optional program association
        private UUID programId;
        
        // Optional VIBAN
        private String viban;
        
        // Optional external reference
        private String externalReference;
    }

    @Data
    @Builder
    public static class VaWithHierarchyResponse {
        private UUID id;
        private String vaNumber;
        private String vaName;
        private String currencyCode;
        private String accountCategory;
        private UUID parentAccountId;
        private String parentVaNumber;
        private Integer hierarchyLevel;
        private String hierarchyPathVa;
        private UUID owningEntityId;
        private String owningEntityCode;
        private UUID physicalAccountId;
        private String status;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    public static class ParentAccountDto {
        private UUID id;
        private String vaNumber;
        private String vaName;
        private String currencyCode;
        private String accountCategory;
        private BigDecimal balance;
        private Integer hierarchyLevel;
        private String bankName;
        private String bankIban;
    }

    /**
     * Request DTO for creating VA using hierarchy dimensions.
     * The system will auto-create intermediate aggregation nodes as needed.
     */
    @Data
    public static class CreateVaWithDimensionsRequest {
        @NotBlank(message = "VA name is required")
        private String vaName;

        @NotNull(message = "Program ID is required")
        private UUID programId;

        /**
         * Hierarchy dimensions map: {"L1": "AED", "L2": "NORTH", "L3": "ACME", ...}
         * Each key represents a hierarchy level (L1, L2, etc.)
         * Each value is the dimension value for that level
         */
        private Map<String, String> hierarchyDimensions;

        // Optional: defaults to program currency
        private String currencyCode;

        // Account category: TRANSACTION (default), COLLECTION, DISBURSEMENT, etc.
        private String accountCategory;

        // Optional: Account purpose (OPERATING, COLLECTIONS, PAYABLES, etc.)
        private String accountPurpose;

        // Optional: Legal entity that owns this VA
        private UUID owningEntityId;

        // Optional: External reference (ERP ID, etc.)
        private String externalReference;

        // Optional: Whether to inherit program defaults
        private Boolean inheritProgramDefaults;
    }
}