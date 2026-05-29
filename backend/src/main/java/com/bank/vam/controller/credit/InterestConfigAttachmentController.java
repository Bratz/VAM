package com.bank.vam.controller.credit;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.dto.VirtualAccountDto;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.credit.InterestConfiguration;
import com.bank.vam.entity.credit.InterestConfiguration.ConfigType;
import com.bank.vam.service.VirtualAccountService;
import com.bank.vam.service.credit.InterestConfigAttachmentService;
import io.swagger.v3.oas.annotations.Operation;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * InterestConfigAttachmentController - Manages interest config attachment to VAs.
 * 
 * PHASE 1: Interest Configuration Attachment
 * ==========================================
 * 
 * This controller provides endpoints for:
 * 1. Attaching/detaching interest configs to specific VAs
 * 2. Resolving effective interest config for a VA (with fallback chain)
 * 3. Bulk attachment of configs to VAs by currency
 * 4. Syncing effective rates from configs to VAs
 * 5. Checking which VAs use a specific config
 * 
 * MULTI-CURRENCY SUPPORT:
 * =======================
 * - Interest configs are currency-specific
 * - Each VA has one currency → one external config + one internal config
 * - Resolution priority: VA → Entity → Currency Default → Corporate Default
 * 
 * ENDPOINT ORGANIZATION:
 * ======================
 * POST /attach/external/{vaId}/{configId}  - Attach external config to VA
 * POST /attach/internal/{vaId}/{configId}  - Attach internal config to VA
 * DELETE /detach/external/{vaId}           - Detach external config from VA
 * DELETE /detach/internal/{vaId}           - Detach internal config from VA
 * GET /resolve/{vaId}                      - Resolve effective config for VA
 * POST /bulk-attach                        - Bulk attach to VAs by criteria
 * POST /sync-rates/{vaId}                  - Sync effective rates for VA
 * POST /sync-all/corporate/{corporateId}   - Sync all VAs in corporate
 * GET /using-config/{configId}             - Find VAs using a config
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/interest-attachment")
@RequiredArgsConstructor
@Tag(name = "Interest Config Attachment", description = "Attach interest configurations to Virtual Accounts")
public class InterestConfigAttachmentController {

    private final InterestConfigAttachmentService attachmentService;
    private final VirtualAccountService vaService;

    // ========================================================================
    // ATTACH/DETACH ENDPOINTS
    // ========================================================================

    /**
     * Attach an external interest configuration to a VA.
     * 
     * External configs represent bank-provided rates.
     * Currency must match between VA and config.
     */
    @PostMapping("/attach/external/{vaId}/{configId}")
    @Operation(summary = "Attach external interest config to VA",
               description = "Attach a bank-provided (external) interest configuration to a Virtual Account")
    public ResponseEntity<ApiResponse<AttachmentResponse>> attachExternalConfig(
            @PathVariable UUID vaId,
            @PathVariable UUID configId) {
        
        log.info("POST /attach/external/{}/{}", vaId, configId);
        
        VirtualAccount va = attachmentService.attachExternalConfig(vaId, configId);
        
        AttachmentResponse response = AttachmentResponse.builder()
            .vaId(va.getId())
            .vaNumber(va.getVaNumber())
            .currency(va.getCurrencyCode())
            .externalConfigId(va.getExternalInterestConfigId())
            .internalConfigId(va.getInternalInterestConfigId())
            .effectiveCreditRate(va.getEffectiveCreditRate())
            .effectiveDebitRate(va.getEffectiveDebitRate())
            .message("External interest config attached successfully")
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Attach an internal interest configuration to a VA.
     * 
     * Internal configs represent treasury transfer pricing rates.
     * Currency must match between VA and config.
     */
    @PostMapping("/attach/internal/{vaId}/{configId}")
    @Operation(summary = "Attach internal interest config to VA",
               description = "Attach a treasury (internal) interest configuration to a Virtual Account")
    public ResponseEntity<ApiResponse<AttachmentResponse>> attachInternalConfig(
            @PathVariable UUID vaId,
            @PathVariable UUID configId) {
        
        log.info("POST /attach/internal/{}/{}", vaId, configId);
        
        VirtualAccount va = attachmentService.attachInternalConfig(vaId, configId);
        
        AttachmentResponse response = AttachmentResponse.builder()
            .vaId(va.getId())
            .vaNumber(va.getVaNumber())
            .currency(va.getCurrencyCode())
            .externalConfigId(va.getExternalInterestConfigId())
            .internalConfigId(va.getInternalInterestConfigId())
            .effectiveCreditRate(va.getEffectiveCreditRate())
            .effectiveDebitRate(va.getEffectiveDebitRate())
            .message("Internal interest config attached successfully")
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Detach external interest configuration from a VA.
     */
    @DeleteMapping("/detach/external/{vaId}")
    @Operation(summary = "Detach external interest config from VA",
               description = "Remove the bank-provided interest configuration from a Virtual Account")
    public ResponseEntity<ApiResponse<AttachmentResponse>> detachExternalConfig(
            @PathVariable UUID vaId) {
        
        log.info("DELETE /detach/external/{}", vaId);
        
        VirtualAccount va = attachmentService.detachExternalConfig(vaId);
        
        AttachmentResponse response = AttachmentResponse.builder()
            .vaId(va.getId())
            .vaNumber(va.getVaNumber())
            .currency(va.getCurrencyCode())
            .externalConfigId(null)
            .internalConfigId(va.getInternalInterestConfigId())
            .effectiveCreditRate(va.getEffectiveCreditRate())
            .effectiveDebitRate(va.getEffectiveDebitRate())
            .message("External interest config detached")
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Detach internal interest configuration from a VA.
     */
    @DeleteMapping("/detach/internal/{vaId}")
    @Operation(summary = "Detach internal interest config from VA",
               description = "Remove the treasury interest configuration from a Virtual Account")
    public ResponseEntity<ApiResponse<AttachmentResponse>> detachInternalConfig(
            @PathVariable UUID vaId) {
        
        log.info("DELETE /detach/internal/{}", vaId);
        
        VirtualAccount va = attachmentService.detachInternalConfig(vaId);
        
        AttachmentResponse response = AttachmentResponse.builder()
            .vaId(va.getId())
            .vaNumber(va.getVaNumber())
            .currency(va.getCurrencyCode())
            .externalConfigId(va.getExternalInterestConfigId())
            .internalConfigId(null)
            .effectiveCreditRate(va.getEffectiveCreditRate())
            .effectiveDebitRate(va.getEffectiveDebitRate())
            .message("Internal interest config detached")
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ========================================================================
    // RESOLUTION ENDPOINTS
    // ========================================================================

    /**
     * Resolve effective interest configurations for a VA.
     * 
     * Returns the configs that would be used (VA → Entity → Currency → Corporate)
     * along with resolution level information.
     */
    @GetMapping("/resolve/{vaId}")
    @Operation(summary = "Resolve effective interest config for VA",
               description = "Find the effective interest configurations using priority resolution")
    public ResponseEntity<ApiResponse<ResolutionResponse>> resolveEffectiveConfig(
            @PathVariable UUID vaId) {
        
        log.info("GET /resolve/{}", vaId);
        
        VirtualAccount va = vaService.getById(vaId);
        
        // Resolve external config
        Optional<InterestConfiguration> externalOpt = attachmentService.resolveEffectiveConfig(
            va.getCorporateId(), va.getId(), va.getOwningEntityId(), 
            va.getCurrencyCode(), ConfigType.EXTERNAL);
        
        // Resolve internal config
        Optional<InterestConfiguration> internalOpt = attachmentService.resolveEffectiveConfig(
            va.getCorporateId(), va.getId(), va.getOwningEntityId(), 
            va.getCurrencyCode(), ConfigType.INTERNAL);
        
        ResolutionResponse response = ResolutionResponse.builder()
            .vaId(va.getId())
            .vaNumber(va.getVaNumber())
            .currency(va.getCurrencyCode())
            .owningEntityId(va.getOwningEntityId())
            .owningEntityCode(va.getOwningEntityCode())
            // External config details
            .externalConfigId(externalOpt.map(InterestConfiguration::getId).orElse(null))
            .externalConfigName(externalOpt.map(InterestConfiguration::getConfigName).orElse(null))
            .externalCreditRate(externalOpt.map(InterestConfiguration::getEffectiveCreditRate).orElse(null))
            .externalDebitRate(externalOpt.map(InterestConfiguration::getEffectiveDebitRate).orElse(null))
            .externalResolutionLevel(externalOpt.map(this::getResolutionLevel).orElse("NOT_FOUND"))
            // Internal config details
            .internalConfigId(internalOpt.map(InterestConfiguration::getId).orElse(null))
            .internalConfigName(internalOpt.map(InterestConfiguration::getConfigName).orElse(null))
            .internalCreditRate(internalOpt.map(InterestConfiguration::getEffectiveCreditRate).orElse(null))
            .internalDebitRate(internalOpt.map(InterestConfiguration::getEffectiveDebitRate).orElse(null))
            .internalResolutionLevel(internalOpt.map(this::getResolutionLevel).orElse("NOT_FOUND"))
            // Current attached configs
            .currentExternalConfigId(va.getExternalInterestConfigId())
            .currentInternalConfigId(va.getInternalInterestConfigId())
            .effectiveCreditRate(va.getEffectiveCreditRate())
            .effectiveDebitRate(va.getEffectiveDebitRate())
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Get current interest config status for a VA.
     */
    @GetMapping("/status/{vaId}")
    @Operation(summary = "Get interest config status for VA",
               description = "Get currently attached interest configs and effective rates for a VA")
    public ResponseEntity<ApiResponse<AttachmentResponse>> getConfigStatus(
            @PathVariable UUID vaId) {
        
        log.info("GET /status/{}", vaId);
        
        VirtualAccount va = vaService.getById(vaId);
        
        AttachmentResponse response = AttachmentResponse.builder()
            .vaId(va.getId())
            .vaNumber(va.getVaNumber())
            .currency(va.getCurrencyCode())
            .externalConfigId(va.getExternalInterestConfigId())
            .internalConfigId(va.getInternalInterestConfigId())
            .effectiveCreditRate(va.getEffectiveCreditRate())
            .effectiveDebitRate(va.getEffectiveDebitRate())
            .message("Current interest config status")
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ========================================================================
    // SYNC ENDPOINTS
    // ========================================================================

    /**
     * Sync effective rates from attached config to VA.
     * 
     * This recalculates the effectiveCreditRate and effectiveDebitRate
     * denormalized fields on the VA based on attached configs.
     */
    @PostMapping("/sync-rates/{vaId}")
    @Operation(summary = "Sync effective rates for VA",
               description = "Recalculate effective rates from attached configurations")
    public ResponseEntity<ApiResponse<AttachmentResponse>> syncRates(
            @PathVariable UUID vaId) {
        
        log.info("POST /sync-rates/{}", vaId);
        
        VirtualAccount va = attachmentService.syncEffectiveRatesById(vaId);
        
        AttachmentResponse response = AttachmentResponse.builder()
            .vaId(va.getId())
            .vaNumber(va.getVaNumber())
            .currency(va.getCurrencyCode())
            .externalConfigId(va.getExternalInterestConfigId())
            .internalConfigId(va.getInternalInterestConfigId())
            .effectiveCreditRate(va.getEffectiveCreditRate())
            .effectiveDebitRate(va.getEffectiveDebitRate())
            .message("Effective rates synced from attached configs")
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Sync effective rates for all VAs in a corporate.
     */
    @PostMapping("/sync-all/corporate/{corporateId}")
    @Operation(summary = "Sync rates for all VAs in corporate",
               description = "Recalculate effective rates for all VAs in a corporate")
    public ResponseEntity<ApiResponse<BulkSyncResponse>> syncAllForCorporate(
            @PathVariable UUID corporateId) {
        
        log.info("POST /sync-all/corporate/{}", corporateId);
        
        int count = attachmentService.syncAllForCorporate(corporateId);
        
        BulkSyncResponse response = BulkSyncResponse.builder()
            .corporateId(corporateId)
            .vasSynced(count)
            .message("Synced effective rates for " + count + " VAs")
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Propagate config update to all VAs using it.
     * 
     * Call this after updating an interest configuration
     * to sync the new rates to all VAs that reference it.
     */
    @PostMapping("/propagate/{configId}")
    @Operation(summary = "Propagate config update to VAs",
               description = "Sync updated rates to all VAs using this configuration")
    public ResponseEntity<ApiResponse<PropagateResponse>> propagateConfigUpdate(
            @PathVariable UUID configId) {
        
        log.info("POST /propagate/{}", configId);
        
        int count = attachmentService.propagateConfigUpdate(configId);
        
        PropagateResponse response = PropagateResponse.builder()
            .configId(configId)
            .vasUpdated(count)
            .message("Propagated config update to " + count + " VAs")
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ========================================================================
    // QUERY ENDPOINTS
    // ========================================================================

    /**
     * Find all VAs using a specific interest configuration.
     */
    @GetMapping("/using-config/{configId}")
    @Operation(summary = "Find VAs using a config",
               description = "List all Virtual Accounts that reference a specific interest configuration")
    public ResponseEntity<ApiResponse<List<VaConfigUsageResponse>>> findVasUsingConfig(
            @PathVariable UUID configId) {
        
        log.info("GET /using-config/{}", configId);
        
        List<VirtualAccount> vas = attachmentService.getVasUsingConfig(configId);
        
        List<VaConfigUsageResponse> response = vas.stream()
            .map(va -> VaConfigUsageResponse.builder()
                .vaId(va.getId())
                .vaNumber(va.getVaNumber())
                .vaName(va.getVaName())
                .currency(va.getCurrencyCode())
                .owningEntityCode(va.getOwningEntityCode())
                .usageType(determineUsageType(va, configId))
                .effectiveCreditRate(va.getEffectiveCreditRate())
                .effectiveDebitRate(va.getEffectiveDebitRate())
                .build())
            .collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Count VAs using a specific interest configuration.
     */
    @GetMapping("/using-config/{configId}/count")
    @Operation(summary = "Count VAs using a config",
               description = "Count Virtual Accounts that reference a specific interest configuration")
    public ResponseEntity<ApiResponse<Long>> countVasUsingConfig(
            @PathVariable UUID configId) {
        
        log.info("GET /using-config/{}/count", configId);
        
        long count = attachmentService.countVasUsingConfig(configId);
        
        return ResponseEntity.ok(ApiResponse.success(count, 
            count + " VAs using this configuration"));
    }

    /**
     * Check if a config can be safely deleted.
     */
    @GetMapping("/can-delete/{configId}")
    @Operation(summary = "Check if config can be deleted",
               description = "Check if an interest configuration can be safely deleted (not in use)")
    public ResponseEntity<ApiResponse<CanDeleteResponse>> canDeleteConfig(
            @PathVariable UUID configId) {
        
        log.info("GET /can-delete/{}", configId);
        
        boolean canDelete = attachmentService.canDeleteConfig(configId);
        long usageCount = attachmentService.countVasUsingConfig(configId);
        
        CanDeleteResponse response = CanDeleteResponse.builder()
            .configId(configId)
            .canDelete(canDelete)
            .usageCount(usageCount)
            .message(canDelete ? 
                "Configuration can be safely deleted" : 
                "Cannot delete: " + usageCount + " VAs are using this configuration")
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ========================================================================
    // BULK ATTACHMENT ENDPOINTS
    // ========================================================================

    /**
     * Auto-attach interest configs to a newly created VA.
     * 
     * This is typically called internally by VirtualAccountService.create(),
     * but can also be called manually to attach configs to existing VAs
     * that don't have configs attached.
     */
    @PostMapping("/auto-attach/{vaId}")
    @Operation(summary = "Auto-attach configs to VA",
               description = "Automatically find and attach appropriate interest configs to a VA")
    public ResponseEntity<ApiResponse<AttachmentResponse>> autoAttach(
            @PathVariable UUID vaId) {
        
        log.info("POST /auto-attach/{}", vaId);
        
        VirtualAccount va = vaService.getById(vaId);
        va = attachmentService.autoAttachInterestConfigs(va);
        
        AttachmentResponse response = AttachmentResponse.builder()
            .vaId(va.getId())
            .vaNumber(va.getVaNumber())
            .currency(va.getCurrencyCode())
            .externalConfigId(va.getExternalInterestConfigId())
            .internalConfigId(va.getInternalInterestConfigId())
            .effectiveCreditRate(va.getEffectiveCreditRate())
            .effectiveDebitRate(va.getEffectiveDebitRate())
            .message("Interest configs auto-attached")
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private String getResolutionLevel(InterestConfiguration config) {
        if (config.getTargetType() == null) {
            return "UNKNOWN";
        }
        return switch (config.getTargetType()) {
            case VIRTUAL_ACCOUNT -> "VA_SPECIFIC";
            case LEGAL_ENTITY -> "ENTITY_SPECIFIC";
            case CURRENCY -> "CURRENCY_DEFAULT";
            case CORPORATE -> "CORPORATE_DEFAULT";
            default -> "UNKNOWN";
        };
    }

    private String determineUsageType(VirtualAccount va, UUID configId) {
        if (configId.equals(va.getExternalInterestConfigId()) && 
            configId.equals(va.getInternalInterestConfigId())) {
            return "BOTH";
        } else if (configId.equals(va.getExternalInterestConfigId())) {
            return "EXTERNAL";
        } else if (configId.equals(va.getInternalInterestConfigId())) {
            return "INTERNAL";
        }
        return "NONE";
    }

    // ========================================================================
    // RESPONSE DTOs
    // ========================================================================

    @Data
    @Builder
    public static class AttachmentResponse {
        private UUID vaId;
        private String vaNumber;
        private String currency;
        private UUID externalConfigId;
        private UUID internalConfigId;
        private BigDecimal effectiveCreditRate;
        private BigDecimal effectiveDebitRate;
        private String message;
    }

    @Data
    @Builder
    public static class ResolutionResponse {
        private UUID vaId;
        private String vaNumber;
        private String currency;
        private UUID owningEntityId;
        private String owningEntityCode;
        // External config
        private UUID externalConfigId;
        private String externalConfigName;
        private BigDecimal externalCreditRate;
        private BigDecimal externalDebitRate;
        private String externalResolutionLevel;
        // Internal config
        private UUID internalConfigId;
        private String internalConfigName;
        private BigDecimal internalCreditRate;
        private BigDecimal internalDebitRate;
        private String internalResolutionLevel;
        // Currently attached
        private UUID currentExternalConfigId;
        private UUID currentInternalConfigId;
        private BigDecimal effectiveCreditRate;
        private BigDecimal effectiveDebitRate;
    }

    @Data
    @Builder
    public static class BulkSyncResponse {
        private UUID corporateId;
        private int vasSynced;
        private String message;
    }

    @Data
    @Builder
    public static class PropagateResponse {
        private UUID configId;
        private int vasUpdated;
        private String message;
    }

    @Data
    @Builder
    public static class VaConfigUsageResponse {
        private UUID vaId;
        private String vaNumber;
        private String vaName;
        private String currency;
        private String owningEntityCode;
        private String usageType; // EXTERNAL, INTERNAL, BOTH
        private BigDecimal effectiveCreditRate;
        private BigDecimal effectiveDebitRate;
    }

    @Data
    @Builder
    public static class CanDeleteResponse {
        private UUID configId;
        private boolean canDelete;
        private long usageCount;
        private String message;
    }
}