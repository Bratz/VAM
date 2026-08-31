package com.bank.vam.controller.hierarchy;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.hierarchy.LegalEntity.EntityType;
import com.bank.vam.entity.hierarchy.LegalEntity.EntityStatus;
import com.bank.vam.service.hierarchy.LegalEntityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * LegalEntityController - REST API for Legal Entity/Subsidiary Management.
 * 
 * Legal Entities represent the corporate structure:
 * - Holding companies
 * - Subsidiaries
 * - Branches
 * - Treasury centers
 * - Special Purpose Vehicles (SPVs)
 * 
 * They form a hierarchy and own physical bank accounts,
 * which in turn are shadowed by PHYSICAL_MIRROR VAs.
 * 
 * Hierarchy: Corporate → Legal Entities → Physical Accounts → Shadow VAs
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/legal-entities")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "Legal Entities", description = "Legal Entity/Subsidiary Management APIs")
public class LegalEntityController {

    private final LegalEntityService legalEntityService;

    // ========================================================================
    // QUERY APIs - By Corporate
    // ========================================================================

    /**
     * Get all legal entities for a corporate.
     * Primary endpoint for VA Create Modal Step 2.
     */
    @GetMapping("/corporate/{corporateId}")
    @Operation(
        summary = "Get legal entities by corporate",
        description = "Retrieves all legal entities (subsidiaries/branches) for a corporate. " +
                      "Returns flat list; use /tree endpoint for hierarchical view."
    )
    public ResponseEntity<ApiResponse<List<LegalEntityDto>>> getEntitiesByCorporate(
            @Parameter(description = "Corporate UUID")
            @PathVariable UUID corporateId) {
        
        log.info("GET /api/v1/legal-entities/corporate/{}", corporateId);
        
        List<LegalEntity> entities = legalEntityService.getEntitiesByCorporate(corporateId);
        
        List<LegalEntityDto> dtos = entities.stream()
            .map(this::toDto)
            .collect(Collectors.toList());
        
        log.debug("Found {} legal entities for corporate {}", dtos.size(), corporateId);
        
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    /**
     * Get legal entity tree for a corporate.
     */
    @GetMapping("/corporate/{corporateId}/tree")
    @Operation(
        summary = "Get legal entity tree",
        description = "Retrieves legal entities as a hierarchical tree structure"
    )
    public ResponseEntity<ApiResponse<List<LegalEntityDto>>> getEntityTree(
            @PathVariable UUID corporateId) {
        
        log.info("GET /api/v1/legal-entities/corporate/{}/tree", corporateId);
        
        List<LegalEntity> roots = legalEntityService.getRootEntities(corporateId);
        
        List<LegalEntityDto> dtos = roots.stream()
            .map(e -> toDtoWithChildren(e))
            .collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    /**
     * Get active legal entities for a corporate.
     */
    @GetMapping("/corporate/{corporateId}/active")
    @Operation(
        summary = "Get active legal entities",
        description = "Retrieves only active legal entities for a corporate"
    )
    public ResponseEntity<ApiResponse<List<LegalEntityDto>>> getActiveEntities(
            @PathVariable UUID corporateId) {
        
        log.info("GET /api/v1/legal-entities/corporate/{}/active", corporateId);
        
        List<LegalEntity> entities = legalEntityService.getActiveEntitiesByCorporate(corporateId);
        
        List<LegalEntityDto> dtos = entities.stream()
            .map(this::toDto)
            .collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    /**
     * Get entities with physical accounts.
     * Filters to only entities that have bank accounts linked.
     */
    @GetMapping("/corporate/{corporateId}/with-accounts")
    @Operation(
        summary = "Get entities with bank accounts",
        description = "Retrieves legal entities that have physical bank accounts linked. " +
                      "Useful for VA creation to show only relevant entities."
    )
    public ResponseEntity<ApiResponse<List<LegalEntityDto>>> getEntitiesWithAccounts(
            @PathVariable UUID corporateId) {
        
        log.info("GET /api/v1/legal-entities/corporate/{}/with-accounts", corporateId);
        
        List<LegalEntity> entities = legalEntityService.getEntitiesWithPhysicalAccounts(corporateId);
        
        List<LegalEntityDto> dtos = entities.stream()
            .map(this::toDtoWithAccountCount)
            .collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    /**
     * Get treasury centers for a corporate.
     */
    @GetMapping("/corporate/{corporateId}/treasury-centers")
    @Operation(
        summary = "Get treasury centers",
        description = "Retrieves legal entities marked as treasury centers"
    )
    public ResponseEntity<ApiResponse<List<LegalEntityDto>>> getTreasuryCenters(
            @PathVariable UUID corporateId) {
        
        log.info("GET /api/v1/legal-entities/corporate/{}/treasury-centers", corporateId);
        
        List<LegalEntity> entities = legalEntityService.getTreasuryCenters(corporateId);
        
        List<LegalEntityDto> dtos = entities.stream()
            .map(this::toDto)
            .collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    // ========================================================================
    // QUERY APIs - Single Entity
    // ========================================================================

    /**
     * Get single legal entity.
     */
    @GetMapping("/{entityId}")
    @Operation(
        summary = "Get legal entity",
        description = "Retrieves a specific legal entity by ID"
    )
    public ResponseEntity<ApiResponse<LegalEntityDto>> getEntity(
            @PathVariable UUID entityId) {
        
        log.info("GET /api/v1/legal-entities/{}", entityId);
        
        LegalEntity entity = legalEntityService.getEntity(entityId);
        
        return ResponseEntity.ok(ApiResponse.success(toDto(entity)));
    }

    /**
     * Get legal entity by code within a corporate.
     */
    @GetMapping("/corporate/{corporateId}/code/{entityCode}")
    @Operation(
        summary = "Get entity by code",
        description = "Retrieves a legal entity by its code within a corporate"
    )
    public ResponseEntity<ApiResponse<LegalEntityDto>> getEntityByCode(
            @PathVariable UUID corporateId,
            @PathVariable String entityCode) {
        
        log.info("GET /api/v1/legal-entities/corporate/{}/code/{}", corporateId, entityCode);
        
        LegalEntity entity = legalEntityService.getEntityByCode(corporateId, entityCode);
        
        return ResponseEntity.ok(ApiResponse.success(toDto(entity)));
    }

    /**
     * Get child entities of a parent entity.
     */
    @GetMapping("/{entityId}/children")
    @Operation(
        summary = "Get child entities",
        description = "Retrieves direct child entities of a parent entity"
    )
    public ResponseEntity<ApiResponse<List<LegalEntityDto>>> getChildEntities(
            @PathVariable UUID entityId) {
        
        log.info("GET /api/v1/legal-entities/{}/children", entityId);
        
        List<LegalEntity> children = legalEntityService.getChildEntities(entityId);
        
        List<LegalEntityDto> dtos = children.stream()
            .map(this::toDto)
            .collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    /**
     * Get ancestors of an entity (path to root).
     */
    @GetMapping("/{entityId}/ancestors")
    @Operation(
        summary = "Get entity ancestors",
        description = "Retrieves all ancestor entities from this entity to root"
    )
    public ResponseEntity<ApiResponse<List<LegalEntityDto>>> getAncestors(
            @PathVariable UUID entityId) {
        
        log.info("GET /api/v1/legal-entities/{}/ancestors", entityId);
        
        List<LegalEntity> ancestors = legalEntityService.getAncestors(entityId);
        
        List<LegalEntityDto> dtos = ancestors.stream()
            .map(this::toDto)
            .collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    // ========================================================================
    // CREATE APIs
    // ========================================================================

    /**
     * Create a new legal entity.
     */
    @PostMapping
    @Operation(
        summary = "Create legal entity",
        description = "Creates a new legal entity/subsidiary"
    )
    public ResponseEntity<ApiResponse<LegalEntityDto>> createEntity(
            @Valid @RequestBody CreateEntityRequest request) {
        
        log.info("POST /api/v1/legal-entities - Code: {}, Name: {}", 
                 request.getEntityCode(), request.getEntityName());
        
        LegalEntity entity = legalEntityService.createEntity(request);
        
        log.info("Created legal entity: {} ({})", entity.getEntityCode(), entity.getId());
        
        return ResponseEntity.ok(ApiResponse.success(
            toDto(entity),
            "Legal entity created: " + entity.getEntityCode()
        ));
    }

    /**
     * Create child entity under a parent.
     */
    @PostMapping("/{parentEntityId}/children")
    @Operation(
        summary = "Create child entity",
        description = "Creates a new legal entity as a child of the specified parent"
    )
    public ResponseEntity<ApiResponse<LegalEntityDto>> createChildEntity(
            @PathVariable UUID parentEntityId,
            @Valid @RequestBody CreateEntityRequest request) {
        
        log.info("POST /api/v1/legal-entities/{}/children - Code: {}", 
                 parentEntityId, request.getEntityCode());
        
        // Set parent before creation
        request.setParentEntityId(parentEntityId);
        
        LegalEntity entity = legalEntityService.createEntity(request);
        
        return ResponseEntity.ok(ApiResponse.success(
            toDto(entity),
            "Child entity created: " + entity.getEntityCode()
        ));
    }

    // ========================================================================
    // UPDATE APIs
    // ========================================================================

    /**
     * Update a legal entity.
     */
    @PutMapping("/{entityId}")
    @Operation(
        summary = "Update legal entity",
        description = "Updates an existing legal entity"
    )
    public ResponseEntity<ApiResponse<LegalEntityDto>> updateEntity(
            @PathVariable UUID entityId,
            @Valid @RequestBody UpdateEntityRequest request) {
        
        log.info("PUT /api/v1/legal-entities/{}", entityId);
        
        LegalEntity entity = legalEntityService.updateEntity(entityId, request);
        
        return ResponseEntity.ok(ApiResponse.success(
            toDto(entity),
            "Legal entity updated"
        ));
    }

    /**
     * Move entity to a new parent.
     */
    @PutMapping("/{entityId}/parent/{newParentId}")
    @Operation(
        summary = "Move entity",
        description = "Moves a legal entity to a new parent in the hierarchy"
    )
    public ResponseEntity<ApiResponse<LegalEntityDto>> moveEntity(
            @PathVariable UUID entityId,
            @PathVariable UUID newParentId) {
        
        log.info("PUT /api/v1/legal-entities/{}/parent/{}", entityId, newParentId);
        
        LegalEntity entity = legalEntityService.moveEntity(entityId, newParentId);
        
        return ResponseEntity.ok(ApiResponse.success(
            toDto(entity),
            "Entity moved to new parent"
        ));
    }

    /**
     * Set entity as treasury center.
     */
    @PutMapping("/{entityId}/treasury-center")
    @Operation(
        summary = "Set treasury center",
        description = "Marks or unmarks a legal entity as a treasury center"
    )
    public ResponseEntity<ApiResponse<LegalEntityDto>> setTreasuryCenter(
            @PathVariable UUID entityId,
            @RequestParam boolean isTreasuryCenter) {
        
        log.info("PUT /api/v1/legal-entities/{}/treasury-center?isTreasuryCenter={}", 
                 entityId, isTreasuryCenter);
        
        LegalEntity entity = legalEntityService.setTreasuryCenter(entityId, isTreasuryCenter);
        
        return ResponseEntity.ok(ApiResponse.success(
            toDto(entity),
            isTreasuryCenter ? "Entity set as treasury center" : "Treasury center flag removed"
        ));
    }

    /**
     * Activate/Deactivate entity.
     */
    @PutMapping("/{entityId}/status")
    @Operation(
        summary = "Update entity status",
        description = "Activates or deactivates a legal entity"
    )
    public ResponseEntity<ApiResponse<LegalEntityDto>> updateEntityStatus(
            @PathVariable UUID entityId,
            @RequestParam EntityStatus status) {
        
        log.info("PUT /api/v1/legal-entities/{}/status?status={}", entityId, status);
        
        LegalEntity entity = legalEntityService.updateStatus(entityId, status);
        
        return ResponseEntity.ok(ApiResponse.success(
            toDto(entity),
            "Entity status updated to " + status
        ));
    }

    // ========================================================================
    // DELETE APIs
    // ========================================================================

    /**
     * Delete a legal entity.
     */
    @DeleteMapping("/{entityId}")
    @Operation(
        summary = "Delete legal entity",
        description = "Deletes a legal entity. Entity must have no children or linked accounts."
    )
    public ResponseEntity<ApiResponse<Void>> deleteEntity(@PathVariable UUID entityId) {
        log.info("DELETE /api/v1/legal-entities/{}", entityId);
        
        legalEntityService.deleteEntity(entityId);
        
        return ResponseEntity.ok(ApiResponse.success(null, "Legal entity deleted"));
    }

    // ========================================================================
    // SEARCH APIs
    // ========================================================================

    /**
     * Search legal entities.
     */
    @GetMapping("/search")
    @Operation(
        summary = "Search legal entities",
        description = "Searches legal entities by name, code, or country"
    )
    public ResponseEntity<ApiResponse<List<LegalEntityDto>>> searchEntities(
            @RequestParam UUID corporateId,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String countryCode,
            @RequestParam(required = false) EntityType entityType,
            @RequestParam(required = false) EntityStatus status) {
        
        log.info("GET /api/v1/legal-entities/search?corporateId={}&query={}", corporateId, query);
        
        List<LegalEntity> entities = legalEntityService.searchEntities(
            corporateId, query, countryCode, entityType, status
        );
        
        List<LegalEntityDto> dtos = entities.stream()
            .map(this::toDto)
            .collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    // ========================================================================
    // MAPPING METHODS
    // ========================================================================

    private LegalEntityDto toDto(LegalEntity entity) {
        return LegalEntityDto.builder()
            .id(entity.getId())
            .entityCode(entity.getEntityCode())
            .entityName(entity.getEntityName())
            .shortName(entity.getShortName())
            .corporateId(entity.getCorporateId())
            .parentEntityId(entity.getParentEntityId())
            .hierarchyPath(entity.getHierarchyPath())
            .hierarchyLevel(entity.getHierarchyLevel())
            .functionalCurrency(entity.getFunctionalCurrency())
            .reportingCurrency(entity.getReportingCurrency())
            .countryCode(entity.getCountryCode())
            .jurisdiction(entity.getJurisdiction())
            .taxId(entity.getTaxId())
            .registrationNumber(entity.getRegistrationNumber())
            .entityType(entity.getEntityType() != null ? entity.getEntityType().name() : null)
            .legalForm(entity.getLegalForm())
            .ownershipPercent(entity.getOwnershipPercent())
            .consolidationMethod(entity.getConsolidationMethod() != null ? entity.getConsolidationMethod().name() : null)
            .status(entity.getStatus() != null ? entity.getStatus().name() : "ACTIVE")
            .isTreasuryCenter(entity.getIsTreasuryCenter())
            .canHoldPhysicalAccounts(entity.getCanHoldPhysicalAccounts())
            .canParticipatePooling(entity.getCanParticipatePooling())
            .canParticipateNetting(entity.getCanParticipateNetting())
            .internalCreditLimit(entity.getInternalCreditLimit())
            .internalLimitUtilized(entity.getInternalLimitUtilized())
            .internalLimitCurrency(entity.getInternalLimitCurrency())
            .limitWarningThreshold(entity.getLimitWarningThreshold())
            .effectiveFrom(entity.getEffectiveFrom())
            .effectiveTo(entity.getEffectiveTo())
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            // Bank Relationship
            .isBankCustomer(entity.getIsBankCustomer())
            .bancsCustomerId(entity.getBancsCustomerId())
            // Contact Information
            .contactEmail(entity.getContactEmail())
            .contactPhone(entity.getContactPhone())
            .registeredAddress(entity.getRegisteredAddress())
            // Operating Currencies
            .operatingCurrencies(entity.getOperatingCurrencies())
            // ENHANCED: IHB fields
            .ihbEnabled(entity.isIhbEnabled())
            .ihbCreditLimit(entity.getIhbCreditLimit())
            .ihbCurrentExposure(entity.getIhbCurrentExposure())
            .ihbAvailableLimit(entity.getIhbAvailableLimit())
            .canLend(entity.getCanLend())
            .canBorrow(entity.getCanBorrow())
            .ihbCurrency(entity.getIhbCurrency())
            .lendingRateSpread(entity.getLendingRateSpread())
            .borrowingRateSpread(entity.getBorrowingRateSpread())
            .totalLentOut(entity.getTotalLentOut())
            .totalDeposited(entity.getTotalDeposited())
            .netIhbPosition(entity.getNetIhbPosition())
            .utilizationPercent(entity.getIhbUtilizationPercent())
            .limitWarning(entity.isIhbLimitWarning())
            .limitBreached(entity.isIhbLimitBreached())
            .build();
    }

    private LegalEntityDto toDtoWithChildren(LegalEntity entity) {
        LegalEntityDto dto = toDto(entity);
        
        List<LegalEntity> children = legalEntityService.getChildEntities(entity.getId());
        if (!children.isEmpty()) {
            dto.setChildren(children.stream()
                .map(this::toDtoWithChildren)
                .collect(Collectors.toList()));
        }
        
        return dto;
    }

    private LegalEntityDto toDtoWithAccountCount(LegalEntity entity) {
        LegalEntityDto dto = toDto(entity);
        dto.setPhysicalAccountCount(legalEntityService.getPhysicalAccountCount(entity.getId()));
        dto.setShadowAccountCount(legalEntityService.getShadowAccountCount(entity.getId()));
        return dto;
    }

    // ========================================================================
    // REQUEST/RESPONSE DTOs
    // ========================================================================

    @Data
    public static class CreateEntityRequest {
        @NotBlank(message = "Entity code is required")
        @Size(max = 20, message = "Entity code must not exceed 20 characters")
        private String entityCode;
        
        @NotBlank(message = "Entity name is required")
        @Size(max = 200, message = "Entity name must not exceed 200 characters")
        private String entityName;
        
        @Size(max = 50)
        private String shortName;
        
        @NotNull(message = "Corporate ID is required")
        private UUID corporateId;
        
        private UUID parentEntityId;
        
        @NotBlank(message = "Functional currency is required")
        @Size(min = 3, max = 3, message = "Currency must be 3 characters")
        private String functionalCurrency;
        
        private String reportingCurrency;
        
        @Size(min = 2, max = 2, message = "Country code must be 2 characters")
        private String countryCode;
        
        private String jurisdiction;
        private String taxId;
        private String registrationNumber;
        private String entityType;
        private String legalForm;
        private BigDecimal ownershipPercent;
        private String consolidationMethod;
        private Boolean isTreasuryCenter;
        private Boolean canHoldPhysicalAccounts;
        private Boolean canParticipatePooling;
        private Boolean canParticipateNetting;
        private BigDecimal internalCreditLimit;
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;

        // IHB Configuration (auto-enabled when isTreasuryCenter=true)
        private Boolean ihbEnabled;
        private Boolean canLend;
        private Boolean canBorrow;
        private String ihbCurrency;
        private BigDecimal lendingRateSpread;
        private BigDecimal borrowingRateSpread;
    }

    @Data
    public static class UpdateEntityRequest {
        @Size(max = 200)
        private String entityName;

        @Size(max = 50)
        private String shortName;

        @Size(min = 3, max = 3)
        private String functionalCurrency;

        private String reportingCurrency;

        @Size(min = 2, max = 2)
        private String countryCode;

        private String jurisdiction;
        private String taxId;
        private String registrationNumber;
        private String entityType;
        private String legalForm;
        private BigDecimal ownershipPercent;
        private String consolidationMethod;
        private String status;  // Entity status (ACTIVE, INACTIVE, etc.)
        private Boolean isTreasuryCenter;
        private Boolean canHoldPhysicalAccounts;
        private Boolean canParticipatePooling;
        private Boolean canParticipateNetting;
        private BigDecimal internalCreditLimit;
        private String internalLimitCurrency;
        private BigDecimal limitWarningThreshold;
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;

        // Bank Relationship
        private Boolean isBankCustomer;
        private String bancsCustomerId;

        // Contact Information
        private String contactEmail;
        private String contactPhone;
        private String registeredAddress;

        // IHB Configuration
        private Boolean ihbEnabled;
        private BigDecimal ihbCreditLimit;
        private String ihbCurrency;
        private Boolean canLend;
        private Boolean canBorrow;
        private BigDecimal lendingRateSpread;
        private BigDecimal borrowingRateSpread;

        // Parent change (optional re-parenting)
        private UUID parentEntityId;
    }

    @Data
    @Builder
    public static class LegalEntityDto {
        private UUID id;
        private String entityCode;
        private String entityName;
        private String shortName;
        private UUID corporateId;
        private UUID parentEntityId;
        private String hierarchyPath;
        private Integer hierarchyLevel;
        private String functionalCurrency;
        private String reportingCurrency;
        private String countryCode;
        private String jurisdiction;
        private String taxId;
        private String registrationNumber;
        private String entityType;
        private String legalForm;
        private BigDecimal ownershipPercent;
        private String consolidationMethod;
        private String status;
        private Boolean isTreasuryCenter;
        private Boolean canHoldPhysicalAccounts;
        private Boolean canParticipatePooling;
        private Boolean canParticipateNetting;
        private BigDecimal internalCreditLimit;
        private BigDecimal internalLimitUtilized;
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        
        // For tree view
        private List<LegalEntityDto> children;
        
        // For account counts
        private Integer physicalAccountCount;
        private Integer shadowAccountCount;
        
        // Internal limit currency and warning
        private String internalLimitCurrency;
        private BigDecimal limitWarningThreshold;

        // Bank Relationship
        private Boolean isBankCustomer;
        private String bancsCustomerId;

        // Contact Information
        private String contactEmail;
        private String contactPhone;
        private String registeredAddress;

        // Operating currencies
        private List<String> operatingCurrencies;

        // ENHANCED: IHB fields
        private Boolean ihbEnabled;
        private BigDecimal ihbCreditLimit;
        private BigDecimal ihbCurrentExposure;
        private BigDecimal ihbAvailableLimit;
        private Boolean canLend;
        private Boolean canBorrow;
        private String ihbCurrency;
        private BigDecimal lendingRateSpread;
        private BigDecimal borrowingRateSpread;
        private BigDecimal totalLentOut;
        private BigDecimal totalDeposited;
        private BigDecimal netIhbPosition;
        private BigDecimal utilizationPercent;
        private Boolean limitWarning;
        private Boolean limitBreached;
    }
}