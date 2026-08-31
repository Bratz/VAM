package com.bank.vam.controller.treasury;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.service.treasury.ShadowAccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ShadowAccountController - REST API for Shadow Account (PHYSICAL_MIRROR) Management.
 * 
 * Shadow Accounts are virtual representations of physical bank accounts.
 * They mirror balances from CBS/Core Banking and serve as parent nodes
 * for transactional VAs in the hierarchy.
 * 
 * Hierarchy Position:
 * ROOT → PHYSICAL_MIRROR (Shadow) → CURRENCY_MIRROR → Transactional VAs
 * 
 * @see com.bank.vam.service.treasury.ShadowAccountService
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/treasury/shadow-accounts")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "Shadow Accounts", description = "Shadow Account (PHYSICAL_MIRROR) Management APIs")
public class ShadowAccountController {

    private final ShadowAccountService shadowAccountService;

    // ========================================================================
    // QUERY APIs - By Legal Entity
    // ========================================================================

    /**
     * Get all shadow accounts for a legal entity.
     * Primary endpoint for VA Create Modal Step 3.
     */
    @GetMapping("/entity/{legalEntityId}")
    @Operation(
        summary = "Get shadow accounts by legal entity",
        description = "Retrieves all PHYSICAL_MIRROR accounts owned by a specific legal entity. " +
                      "Used in VA creation to show available parent accounts."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Shadow accounts retrieved successfully"
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Legal entity not found"
        )
    })
    public ResponseEntity<ApiResponse<List<ShadowAccountDto>>> getShadowAccountsByEntity(
            @Parameter(description = "Legal Entity UUID")
            @PathVariable UUID legalEntityId) {
        
        log.info("GET /api/v1/treasury/shadow-accounts/entity/{}", legalEntityId);
        
        List<VirtualAccount> shadows = shadowAccountService.getShadowAccountsByEntity(legalEntityId);
        
        List<ShadowAccountDto> dtos = shadows.stream()
            .map(this::toDto)
            .collect(Collectors.toList());
        
        log.debug("Found {} shadow accounts for entity {}", dtos.size(), legalEntityId);
        
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    // ========================================================================
    // QUERY APIs - By Corporate
    // ========================================================================

    /**
     * Get all shadow accounts for a corporate.
     */
    @GetMapping("/corporate/{corporateId}")
    @Operation(
        summary = "Get shadow accounts by corporate",
        description = "Retrieves all PHYSICAL_MIRROR accounts for a corporate entity"
    )
    public ResponseEntity<ApiResponse<List<ShadowAccountDto>>> getShadowAccountsByCorporate(
            @Parameter(description = "Corporate UUID")
            @PathVariable UUID corporateId) {
        
        log.info("GET /api/v1/treasury/shadow-accounts/corporate/{}", corporateId);
        
        List<VirtualAccount> shadows = shadowAccountService.getShadowAccounts(corporateId);
        
        List<ShadowAccountDto> dtos = shadows.stream()
            .map(this::toDto)
            .collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    /**
     * Get all shadow accounts for a program.
     */
    @GetMapping("/program/{programId}")
    @Operation(
        summary = "Get shadow accounts by program",
        description = "Retrieves all PHYSICAL_MIRROR accounts for a specific program"
    )
    public ResponseEntity<ApiResponse<List<ShadowAccountDto>>> getShadowAccountsByProgram(
            @Parameter(description = "Program UUID")
            @PathVariable UUID programId) {

        log.info("GET /api/v1/treasury/shadow-accounts/program/{}", programId);

        List<VirtualAccount> shadows = shadowAccountService.getShadowAccountsByProgram(programId);

        List<ShadowAccountDto> dtos = shadows.stream()
            .map(this::toDto)
            .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    /**
     * Get shadow accounts grouped by currency.
     */
    @GetMapping("/corporate/{corporateId}/by-currency")
    @Operation(
        summary = "Get shadow accounts grouped by currency",
        description = "Retrieves shadow accounts organized by currency code"
    )
    public ResponseEntity<ApiResponse<List<CurrencyGroupDto>>> getShadowAccountsByCurrency(
            @PathVariable UUID corporateId) {
        
        log.info("GET /api/v1/treasury/shadow-accounts/corporate/{}/by-currency", corporateId);
        
        List<CurrencyGroupDto> groups = shadowAccountService.getShadowAccountsGroupedByCurrency(corporateId);
        
        return ResponseEntity.ok(ApiResponse.success(groups));
    }

    // ========================================================================
    // QUERY APIs - By Physical Account
    // ========================================================================

    /**
     * Get shadow account by physical account ID.
     */
    @GetMapping("/physical/{physicalAccountId}")
    @Operation(
        summary = "Get shadow by physical account",
        description = "Retrieves the shadow account linked to a specific physical bank account"
    )
    public ResponseEntity<ApiResponse<ShadowAccountDto>> getShadowByPhysicalAccount(
            @Parameter(description = "Physical Account UUID")
            @PathVariable UUID physicalAccountId) {
        
        log.info("GET /api/v1/treasury/shadow-accounts/physical/{}", physicalAccountId);
        
        VirtualAccount shadow = shadowAccountService.getShadowByPhysicalAccount(physicalAccountId);
        
        if (shadow == null) {
            return ResponseEntity.ok(ApiResponse.success(null, "No shadow account found for this physical account"));
        }
        
        return ResponseEntity.ok(ApiResponse.success(toDto(shadow)));
    }

    // ========================================================================
    // QUERY APIs - Single Shadow Account
    // ========================================================================

    /**
     * Get shadow account by ID.
     */
    @GetMapping("/{shadowVaId}")
    @Operation(
        summary = "Get shadow account by ID",
        description = "Retrieves a specific shadow account with full details"
    )
    public ResponseEntity<ApiResponse<ShadowAccountDto>> getShadowAccount(
            @PathVariable UUID shadowVaId) {
        
        log.info("GET /api/v1/treasury/shadow-accounts/{}", shadowVaId);
        
        VirtualAccount shadow = shadowAccountService.getShadowAccount(shadowVaId);
        
        return ResponseEntity.ok(ApiResponse.success(toDto(shadow)));
    }

    /**
     * Get child VAs under a shadow account.
     */
    @GetMapping("/{shadowVaId}/children")
    @Operation(
        summary = "Get children of shadow account",
        description = "Retrieves all VAs that are direct children of this shadow account"
    )
    public ResponseEntity<ApiResponse<List<ChildVaDto>>> getShadowChildren(
            @PathVariable UUID shadowVaId) {
        
        log.info("GET /api/v1/treasury/shadow-accounts/{}/children", shadowVaId);
        
        List<VirtualAccount> children = shadowAccountService.getChildVas(shadowVaId);
        
        List<ChildVaDto> dtos = children.stream()
            .map(this::toChildDto)
            .collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    /**
     * Get shadow account with aggregated statistics.
     */
    @GetMapping("/{shadowVaId}/stats")
    @Operation(
        summary = "Get shadow account statistics",
        description = "Retrieves shadow account with balance breakdown and child count"
    )
    public ResponseEntity<ApiResponse<ShadowAccountStatsDto>> getShadowStats(
            @PathVariable UUID shadowVaId) {
        
        log.info("GET /api/v1/treasury/shadow-accounts/{}/stats", shadowVaId);
        
        ShadowAccountStatsDto stats = shadowAccountService.getShadowAccountStats(shadowVaId);
        
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    // ========================================================================
    // CREATE APIs
    // ========================================================================

    /**
     * Create shadow account for a physical account.
     */
    @PostMapping
    @Operation(
        summary = "Create shadow account",
        description = "Creates a PHYSICAL_MIRROR VA for a physical bank account. " +
                      "Automatically syncs balance from the physical account."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Shadow account created successfully"
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Shadow account already exists for this physical account"
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Physical account not found"
        )
    })
    public ResponseEntity<ApiResponse<ShadowAccountDto>> createShadowAccount(
            @Valid @RequestBody CreateShadowRequest request) {
        
        log.info("POST /api/v1/treasury/shadow-accounts - PA: {}, Entity: {}", 
                 request.getPhysicalAccountId(), request.getLegalEntityId());
        
        VirtualAccount shadow = shadowAccountService.createShadowAccount(
            request.getPhysicalAccountId(),
            request.getParentVaId(),
            request.getCorporateId()
        );
        
        // Link to legal entity if provided
        if (request.getLegalEntityId() != null) {
            shadowAccountService.linkToEntity(shadow.getId(), request.getLegalEntityId());
            // Refresh to get updated entity info
            shadow = shadowAccountService.getShadowAccount(shadow.getId());
        }
        
        log.info("Created shadow account: {}", shadow.getVaNumber());
        
        return ResponseEntity.ok(ApiResponse.success(
            toDto(shadow), 
            "Shadow account created: " + shadow.getVaNumber()
        ));
    }

    /**
     * Bulk create shadow accounts for all physical accounts of an entity.
     */
    @PostMapping("/entity/{legalEntityId}/bulk")
    @Operation(
        summary = "Bulk create shadow accounts",
        description = "Creates shadow accounts for all physical accounts linked to a legal entity"
    )
    public ResponseEntity<ApiResponse<BulkCreateResultDto>> bulkCreateShadowAccounts(
            @PathVariable UUID legalEntityId,
            @RequestParam UUID corporateId,
            @RequestParam(required = false) UUID parentVaId) {
        
        log.info("POST /api/v1/treasury/shadow-accounts/entity/{}/bulk", legalEntityId);
        
        BulkCreateResultDto result = shadowAccountService.bulkCreateShadowAccounts(
            legalEntityId, corporateId, parentVaId
        );
        
        return ResponseEntity.ok(ApiResponse.success(
            result,
            String.format("Created %d shadow accounts, %d failed", result.getCreated(), result.getFailed())
        ));
    }

    // ========================================================================
    // UPDATE APIs
    // ========================================================================

    /**
     * Link shadow account to legal entity.
     */
    @PutMapping("/{shadowVaId}/entity/{legalEntityId}")
    @Operation(
        summary = "Link shadow to legal entity",
        description = "Associates a shadow account with a legal entity for ownership tracking"
    )
    public ResponseEntity<ApiResponse<ShadowAccountDto>> linkToEntity(
            @PathVariable UUID shadowVaId,
            @PathVariable UUID legalEntityId) {
        
        log.info("PUT /api/v1/treasury/shadow-accounts/{}/entity/{}", shadowVaId, legalEntityId);
        
        shadowAccountService.linkToEntity(shadowVaId, legalEntityId);
        VirtualAccount shadow = shadowAccountService.getShadowAccount(shadowVaId);
        
        return ResponseEntity.ok(ApiResponse.success(
            toDto(shadow),
            "Shadow account linked to entity"
        ));
    }

    /**
     * Sync shadow account balance from CBS.
     */
    @PostMapping("/{shadowVaId}/sync")
    @Operation(
        summary = "Sync shadow balance",
        description = "Triggers a balance sync from CBS/Core Banking for this shadow account"
    )
    public ResponseEntity<ApiResponse<ShadowAccountDto>> syncShadowBalance(
            @PathVariable UUID shadowVaId) {
        
        log.info("POST /api/v1/treasury/shadow-accounts/{}/sync", shadowVaId);
        
        VirtualAccount shadow = shadowAccountService.syncFromCbs(shadowVaId);
        
        return ResponseEntity.ok(ApiResponse.success(
            toDto(shadow),
            "Balance synced from CBS"
        ));
    }

    /**
     * Bulk sync all shadow accounts for a corporate.
     */
    @PostMapping("/corporate/{corporateId}/sync")
    @Operation(
        summary = "Bulk sync shadow accounts",
        description = "Syncs all shadow account balances from CBS for a corporate"
    )
    public ResponseEntity<ApiResponse<SyncResultDto>> bulkSyncShadowAccounts(
            @PathVariable UUID corporateId) {
        
        log.info("POST /api/v1/treasury/shadow-accounts/corporate/{}/sync", corporateId);
        
        SyncResultDto result = shadowAccountService.syncAllFromCbs(corporateId);
        
        return ResponseEntity.ok(ApiResponse.success(
            result,
            String.format("Synced %d accounts", result.getSynced())
        ));
    }

    // ========================================================================
    // MAPPING METHODS
    // ========================================================================

    private ShadowAccountDto toDto(VirtualAccount va) {
        return ShadowAccountDto.builder()
            .id(va.getId())
            .vaNumber(va.getVaNumber())
            .vaName(va.getVaName())
            .currencyCode(va.getCurrencyCode())
            .bankBalance(va.getBankBalance())
            .bankAvailableBalance(va.getBankAvailableBalance())
            .linkedPhysicalAccountId(va.getLinkedPhysicalAccountId())
            .physicalAccountNumber(va.getBankAccountNumber())
            .bankName(va.getBankName())
            .bankIban(va.getBankIban())
            .bankSwift(va.getBankSwift())
            .owningEntityId(va.getOwningEntityId())
            .owningEntityCode(va.getOwningEntityCode())
            .corporateId(va.getCorporateId())
            .programId(va.getProgramId())  
            .parentAccountId(va.getParentAccountId())
            .hierarchyLevel(va.getHierarchyLevel())
            .hierarchyPathVa(va.getHierarchyPathVa())
            .status(va.getStatus() != null ? va.getStatus().name() : "ACTIVE")
            .bankBalanceAt(va.getBankBalanceAt())
            .dataSource(va.getBalanceDataSource() != null ? va.getBalanceDataSource().name() : null)
            .childCount((int) shadowAccountService.getChildCount(va.getId()))
            .createdAt(va.getCreatedAt())
            .updatedAt(va.getUpdatedAt())
            .build();
    }

    private ChildVaDto toChildDto(VirtualAccount va) {
        return ChildVaDto.builder()
            .id(va.getId())
            .vaNumber(va.getVaNumber())
            .vaName(va.getVaName())
            .currencyCode(va.getCurrencyCode())
            .currentBalance(va.getCurrentBalance())
            .availableBalance(va.getAvailableBalance())
            .accountCategory(va.getAccountCategory() != null ? va.getAccountCategory().name() : null)
            .status(va.getStatus() != null ? va.getStatus().name() : "ACTIVE")
            .build();
    }

    // ========================================================================
    // REQUEST/RESPONSE DTOs
    // ========================================================================

    @Data
    public static class CreateShadowRequest {
        @NotNull(message = "Physical account ID is required")
        private UUID physicalAccountId;
        
        private UUID parentVaId;  // Optional: parent in hierarchy (usually ROOT)
        
        @NotNull(message = "Corporate ID is required")
        private UUID corporateId;
        
        private UUID legalEntityId;  // Optional: owning legal entity
    }

    @Data
    @Builder
    public static class ShadowAccountDto {
        private UUID id;
        private String vaNumber;
        private String vaName;
        private String currencyCode;
        private BigDecimal bankBalance;
        private BigDecimal bankAvailableBalance;
        private UUID linkedPhysicalAccountId;
        private String physicalAccountNumber;
        private String bankName;
        private String bankIban;
        private String bankSwift;
        private UUID owningEntityId;
        private String owningEntityCode;
        private UUID corporateId;
        private UUID programId;
        private UUID parentAccountId;
        private Integer hierarchyLevel;
        private String hierarchyPathVa;
        private String status;
        private LocalDateTime bankBalanceAt;
        private String dataSource;
        private Integer childCount;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    @Builder
    public static class ChildVaDto {
        private UUID id;
        private String vaNumber;
        private String vaName;
        private String currencyCode;
        private BigDecimal currentBalance;
        private BigDecimal availableBalance;
        private String accountCategory;
        private String status;
    }

    @Data
    @Builder
    public static class CurrencyGroupDto {
        private String currencyCode;
        private int accountCount;
        private BigDecimal totalBalance;
        private List<ShadowAccountDto> accounts;
    }

    @Data
    @Builder
    public static class ShadowAccountStatsDto {
        private UUID id;
        private String vaNumber;
        private String currencyCode;
        private BigDecimal bankBalance;
        private BigDecimal totalChildBalance;
        private BigDecimal availableForAllocation;
        private int childCount;
        private int activeChildCount;
        private LocalDateTime lastSyncAt;
    }

    @Data
    @Builder
    public static class BulkCreateResultDto {
        private int total;
        private int created;
        private int skipped;  // Already had shadow
        private int failed;
        private List<String> errors;
        private List<ShadowAccountDto> createdAccounts;
    }

    @Data
    @Builder
    public static class SyncResultDto {
        private int total;
        private int synced;
        private int failed;
        private LocalDateTime syncedAt;
        private List<String> errors;
    }
}