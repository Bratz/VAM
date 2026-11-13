package com.bank.vam.controller.pobo;

import com.bank.vam.dto.pobo.PoboDto.*;
import com.bank.vam.dto.ApiResponse;
import com.bank.vam.service.pobo.PoboCutoverService;
import com.bank.vam.service.pobo.PoboService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * POBO recharges and statistics. POBO itself is set up by enrolment (PoboEnrolmentController) and runs on the
 * payment path; the old preview / execute / validate endpoints and the POBO authorisation limits are retired
 * (spec docs/specs/pobo-in-house-bank.md R7, D7).
 * 
 * @see <a href="https://www.capgemini.com/wp-content/uploads/2020/12/Virtual-Acc-Management-in-Transaction-Banking.pdf">
 *      Capgemini VAM Reference</a>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/pobo")
@RequiredArgsConstructor
@Tag(name = "POBO", description = "Pay On Behalf Of operations")
public class PoboController {

    private final PoboService poboService;
    private final PoboCutoverService cutover;

    @PostMapping("/cutover")
    @Operation(summary = "Cut-over and repair (spec D8/D9): stuck POBO payables back to Approved, mirrors, POBO recharges, "
        + "IHB credit use, in-house sweep rules; past self-sweeps reported only (dryRun previews)")
    public ResponseEntity<ApiResponse<PoboCutoverService.Report>> cutover(@RequestParam UUID corporateId,
                                                                         @RequestParam(defaultValue = "true") boolean dryRun) {
        return ResponseEntity.ok(ApiResponse.success(cutover.cutover(corporateId, dryRun), dryRun ? "Preview" : "Done"));
    }

    // ========================================================================
    // RECHARGE MANAGEMENT (Existing - preserved)
    // ========================================================================
    
    @GetMapping("/recharges")
    @Operation(summary = "Get all recharges")
    public ResponseEntity<List<RechargeResponse>> getAllRecharges() {
        return ResponseEntity.ok(poboService.getAllRecharges());
    }
    
    @GetMapping("/recharges/pending")
    @Operation(summary = "Get pending recharges")
    public ResponseEntity<List<RechargeResponse>> getPendingRecharges(@RequestParam(required = false) UUID corporateId) {
        if (corporateId == null) throw new com.bank.vam.exception.BusinessException("corporateId is required");
        return ResponseEntity.ok(poboService.getPendingRecharges(corporateId));
    }
    
    @GetMapping("/recharges/entity/{entityId}")
    @Operation(summary = "Get recharges for entity")
    public ResponseEntity<List<RechargeResponse>> getRechargesForEntity(
            @PathVariable UUID entityId) {
        return ResponseEntity.ok(poboService.getRechargesForEntity(entityId));
    }
    
    @PostMapping("/recharges")
    @Operation(summary = "Create recharge manually", 
        description = "Manual recharge creation (normally auto-created via POBO execution)")
    public ResponseEntity<RechargeResponse> createRecharge(
            @RequestBody CreateRechargeRequest request) {
        return ResponseEntity.ok(poboService.createRecharge(request));
    }
    
    @PostMapping("/recharges/{id}/approve")
    @Operation(summary = "Approve a pending recharge of the corporate's group (approver: the signed-in user)")
    public ResponseEntity<RechargeResponse> approveRecharge(
            @PathVariable UUID id,
            @RequestParam(required = false) UUID corporateId) {
        return ResponseEntity.ok(poboService.approveRecharge(corporateId, id));
    }

    @PostMapping("/recharges/{id}/reject")
    @Operation(summary = "Reject a pending recharge of the corporate's group, with a reason (rejector: the signed-in user)")
    public ResponseEntity<RechargeResponse> rejectRecharge(
            @PathVariable UUID id,
            @RequestParam(required = false) UUID corporateId,
            @RequestParam(required = false) String reason) {
        return ResponseEntity.ok(poboService.rejectRecharge(corporateId, id, reason));
    }

    @PostMapping("/recharges/{id}/settle/netting")
    @Operation(summary = "Settle recharge via netting")
    public ResponseEntity<RechargeResponse> settleViaNetting(
            @PathVariable UUID id,
            @RequestParam String nettingReference) {
        return ResponseEntity.ok(poboService.settleViaNetting(id, nettingReference));
    }
    
    @PostMapping("/recharges/{id}/validate-arm-length")
    @Operation(summary = "Validate arm's length pricing", 
        description = "Mark transfer pricing as compliant")
    public ResponseEntity<RechargeResponse> validateArmLength(
            @PathVariable UUID id,
            @RequestParam(required = false) String notes) {
        return ResponseEntity.ok(poboService.validateArmLength(id, notes));
    }

    // ========================================================================
    // STATISTICS (Existing - preserved)
    // ========================================================================
    
    @GetMapping("/stats")
    @Operation(summary = "Get POBO statistics")
    public ResponseEntity<PoboStatsResponse> getStats() {
        return ResponseEntity.ok(poboService.getStats());
    }

    // ========================================================================
    // PHASE 5 TASK 5.7: HISTORY ENDPOINTS
    // ========================================================================
    
    @GetMapping("/history")
    @Operation(summary = "Get POBO execution history", 
        description = "List all POBO executions with filtering options")
    public ResponseEntity<PoboHistoryResponse> getHistory(
            @RequestParam(required = false) UUID treasuryEntityId,
            @RequestParam(required = false) UUID subsidiaryEntityId,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        // An empty page would claim there were no executions; POBO recharges exist but no
        // execution history store does, so refuse. GET /recharges lists the recharges.
        throw new com.bank.vam.exception.BusinessException(
            "Not available: POBO execution history — executions are not stored as history; see the recharges instead");
    }
    
    @GetMapping("/history/{executionId}")
    @Operation(summary = "Get POBO execution details")
    public ResponseEntity<PoboExecutionDetailResponse> getExecutionDetails(
            @PathVariable UUID executionId) {
        
        // TODO: Implement execution detail lookup
        // Would include: payable details, recharge, fee breakdown, timestamps
        return ResponseEntity.notFound().build();
    }
}
