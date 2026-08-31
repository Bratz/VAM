package com.bank.vam.controller.treasury;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.dto.treasury.SweepRuleDto;
import com.bank.vam.service.treasury.SweepService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sweeping")
@RequiredArgsConstructor
@Tag(name = "Cash Concentration", description = "Sweep rule and execution management")
public class SweepController {

    private final SweepService sweepService;

    // ========================================================================
    // RULE ENDPOINTS
    // ========================================================================

    @GetMapping("/rules")
    @Operation(summary = "Get all sweep rules")
    public ResponseEntity<ApiResponse<List<SweepRuleDto.Response>>> getAllRules() {
        List<SweepRuleDto.Response> rules = sweepService.getAllRules();
        return ResponseEntity.ok(ApiResponse.success(rules, "Retrieved " + rules.size() + " sweep rules"));
    }

    @GetMapping("/rules/active")
    @Operation(summary = "Get active sweep rules")
    public ResponseEntity<ApiResponse<List<SweepRuleDto.Response>>> getActiveRules() {
        List<SweepRuleDto.Response> rules = sweepService.getActiveRules();
        return ResponseEntity.ok(ApiResponse.success(rules, "Retrieved " + rules.size() + " active sweep rules"));
    }

    @GetMapping("/rules/{id}")
    @Operation(summary = "Get sweep rule by ID")
    public ResponseEntity<ApiResponse<SweepRuleDto.Response>> getRuleById(@PathVariable UUID id) {
        SweepRuleDto.Response rule = sweepService.getRuleById(id);
        return ResponseEntity.ok(ApiResponse.success(rule));
    }

    @PostMapping("/rules")
    @Operation(summary = "Create new sweep rule")
    public ResponseEntity<ApiResponse<SweepRuleDto.Response>> createRule(
            @RequestBody SweepRuleDto.CreateRequest request) {
        SweepRuleDto.Response rule = sweepService.createRule(request);
        return ResponseEntity.ok(ApiResponse.success(rule, "Sweep rule created successfully"));
    }

    @PutMapping("/rules/{id}")
    @Operation(summary = "Update sweep rule")
    public ResponseEntity<ApiResponse<SweepRuleDto.Response>> updateRule(
            @PathVariable UUID id,
            @RequestBody SweepRuleDto.UpdateRequest request) {
        SweepRuleDto.Response rule = sweepService.updateRule(id, request);
        return ResponseEntity.ok(ApiResponse.success(rule, "Sweep rule updated successfully"));
    }

    @DeleteMapping("/rules/{id}")
    @Operation(summary = "Delete sweep rule")
    public ResponseEntity<ApiResponse<Void>> deleteRule(@PathVariable UUID id) {
        sweepService.deleteRule(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Sweep rule deleted successfully"));
    }

    @PostMapping("/rules/{id}/toggle")
    @Operation(summary = "Toggle sweep rule status (pause/resume)")
    public ResponseEntity<ApiResponse<SweepRuleDto.Response>> toggleRule(@PathVariable UUID id) {
        SweepRuleDto.Response rule = sweepService.toggleRuleStatus(id);
        return ResponseEntity.ok(ApiResponse.success(rule, "Sweep rule status toggled"));
    }

    // ========================================================================
    // EXECUTION ENDPOINTS
    // ========================================================================

    @PostMapping("/execute")
    @Operation(summary = "Run sweeps for selected or all active rules")
    public ResponseEntity<ApiResponse<SweepRuleDto.RunSweepsResponse>> runSweeps(
            @RequestBody(required = false) SweepRuleDto.RunSweepsRequest request) {
        if (request == null) {
            request = new SweepRuleDto.RunSweepsRequest();
        }
        SweepRuleDto.RunSweepsResponse result = sweepService.runSweeps(request);
        return ResponseEntity.ok(ApiResponse.success(result, "Sweep execution completed"));
    }

    @GetMapping("/history")
    @Operation(summary = "Get sweep execution history")
    public ResponseEntity<ApiResponse<Page<SweepRuleDto.ExecutionResponse>>> getExecutionHistory(
            @PageableDefault(size = 20, sort = "executionTime") Pageable pageable) {
        Page<SweepRuleDto.ExecutionResponse> history = sweepService.getExecutionHistory(pageable);
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    @GetMapping("/rules/{ruleId}/history")
    @Operation(summary = "Get execution history for specific rule")
    public ResponseEntity<ApiResponse<Page<SweepRuleDto.ExecutionResponse>>> getRuleExecutionHistory(
            @PathVariable UUID ruleId,
            @PageableDefault(size = 20, sort = "executionTime") Pageable pageable) {
        Page<SweepRuleDto.ExecutionResponse> history = sweepService.getExecutionsByRule(ruleId, pageable);
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    // ========================================================================
    // DEFICIT FUNDING ENDPOINTS (IHB Loan Creation)
    // ========================================================================

    @PostMapping("/deficit-funding")
    @Operation(summary = "Run deficit funding for TARGET_BALANCE rules",
               description = "Provides IHB loans to accounts below target balance. Creates IhbLoan records.")
    public ResponseEntity<ApiResponse<SweepRuleDto.DeficitFundingResponse>> runDeficitFunding(
            @RequestBody(required = false) SweepRuleDto.DeficitFundingRequest request) {
        if (request == null) {
            request = new SweepRuleDto.DeficitFundingRequest();
        }
        SweepRuleDto.DeficitFundingResponse result = sweepService.runDeficitFunding(request);
        return ResponseEntity.ok(ApiResponse.success(result, "Deficit funding completed"));
    }

    // ========================================================================
    // MAINTENANCE / CLEANUP ENDPOINTS
    // ========================================================================

    @GetMapping("/orphaned-sources")
    @Operation(summary = "Get orphaned sweep rule sources",
               description = "Returns sources that reference VAs that have been deleted")
    public ResponseEntity<ApiResponse<List<SweepRuleDto.OrphanedSourceInfo>>> getOrphanedSources() {
        List<SweepRuleDto.OrphanedSourceInfo> orphans = sweepService.getOrphanedSources();
        return ResponseEntity.ok(ApiResponse.success(orphans,
            orphans.isEmpty() ? "No orphaned sources found" : "Found " + orphans.size() + " orphaned sources"));
    }

    @PostMapping("/cleanup-orphans")
    @Operation(summary = "Clean up orphaned sweep rule sources",
               description = "Removes sources that reference deleted VAs to prevent sweep execution errors")
    public ResponseEntity<ApiResponse<SweepRuleDto.OrphanCleanupResponse>> cleanupOrphanedSources() {
        // Get orphans before cleanup
        List<SweepRuleDto.OrphanedSourceInfo> orphansBefore = sweepService.getOrphanedSources();

        // Perform cleanup
        int deletedCount = sweepService.cleanupOrphanedSources();

        // Build response
        SweepRuleDto.OrphanCleanupResponse response = new SweepRuleDto.OrphanCleanupResponse();
        response.setOrphanedCount(orphansBefore.size());
        response.setDeletedCount(deletedCount);
        response.setOrphanedSources(orphansBefore);

        return ResponseEntity.ok(ApiResponse.success(response,
            deletedCount > 0
                ? "Cleaned up " + deletedCount + " orphaned sources"
                : "No orphaned sources to clean up"));
    }
}
