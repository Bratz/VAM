package com.bank.vam.controller.treasury;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.dto.treasury.NettingDto;
import com.bank.vam.service.treasury.NettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/netting")
@RequiredArgsConstructor
@Tag(name = "Netting Cycles", description = "Intercompany netting management")
public class NettingController {

    private final NettingService nettingService;

    @GetMapping("/cycles")
    @Operation(summary = "Get all netting cycles")
    public ResponseEntity<ApiResponse<List<NettingDto.CycleResponse>>> getAllCycles() {
        return ResponseEntity.ok(ApiResponse.success(nettingService.getAllCycles()));
    }

    @GetMapping("/cycles/{id}")
    @Operation(summary = "Get cycle by ID")
    public ResponseEntity<ApiResponse<NettingDto.CycleResponse>> getCycleById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(nettingService.getCycleById(id)));
    }

    @PostMapping("/cycles")
    @Operation(summary = "Create new netting cycle")
    public ResponseEntity<ApiResponse<NettingDto.CycleResponse>> createCycle(
            @RequestBody NettingDto.CreateCycleRequest request) {
        return ResponseEntity.ok(ApiResponse.success(nettingService.createCycle(request), "Cycle created"));
    }

    @PostMapping("/cycles/{id}/entries")
    @Operation(summary = "Add entry to cycle")
    public ResponseEntity<ApiResponse<NettingDto.CycleResponse>> addEntry(
            @PathVariable UUID id, @RequestBody NettingDto.AddEntryRequest request) {
        return ResponseEntity.ok(ApiResponse.success(nettingService.addEntry(id, request), "Entry added"));
    }

    @PostMapping("/cycles/{id}/populate")
    @Operation(summary = "Populate cycle with all eligible obligations (payables, receivables, POBO recharges)")
    public ResponseEntity<ApiResponse<NettingDto.PopulateCycleResponse>> populateCycle(
            @PathVariable UUID id,
            @RequestParam(required = false) UUID corporateId,
            @RequestParam(required = false, defaultValue = "false") boolean includePending) {
        return ResponseEntity.ok(ApiResponse.success(nettingService.populateCycle(id, corporateId, includePending), "Cycle populated"));
    }

    @PostMapping("/cycles/{id}/calculate")
    @Operation(summary = "Calculate netting positions")
    public ResponseEntity<ApiResponse<NettingDto.CalculateNettingResponse>> calculateNetting(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(nettingService.calculateNetting(id)));
    }

    @PostMapping("/cycles/{id}/approve")
    @Operation(summary = "Approve netting cycle")
    public ResponseEntity<ApiResponse<NettingDto.CycleResponse>> approveCycle(
            @PathVariable UUID id, @RequestParam String approver) {
        return ResponseEntity.ok(ApiResponse.success(nettingService.approveCycle(id, approver), "Cycle approved"));
    }

    @PostMapping("/cycles/{id}/settle")
    @Operation(summary = "Settle netting cycle")
    public ResponseEntity<ApiResponse<NettingDto.SettleCycleResponse>> settleCycle(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(nettingService.settleCycle(id)));
    }
}
