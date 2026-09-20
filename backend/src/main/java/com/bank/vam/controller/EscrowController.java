package com.bank.vam.controller;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.entity.escrow.EscrowContract.EscrowStatus;
import com.bank.vam.entity.escrow.EscrowContract.EscrowType;
import com.bank.vam.service.escrow.EscrowService;
import com.bank.vam.service.escrow.EscrowService.CreateEscrowRequest;
import com.bank.vam.service.escrow.EscrowService.DisputeEscrowRequest;
import com.bank.vam.service.escrow.EscrowService.EscrowResponse;
import com.bank.vam.service.escrow.EscrowService.EscrowStatsResponse;
import com.bank.vam.service.escrow.EscrowService.ExtendEscrowRequest;
import com.bank.vam.service.escrow.EscrowService.ExtendEscrowResponse;
import com.bank.vam.service.escrow.EscrowService.FundEscrowRequest;
import com.bank.vam.service.escrow.EscrowService.FundEscrowResponse;
import com.bank.vam.service.escrow.EscrowService.ReleaseEscrowRequest;
import com.bank.vam.service.escrow.EscrowService.ReleaseEscrowResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Escrow contracts. Every endpoint reads and writes the escrow_contracts table
 * through EscrowService; funding and release move money as Transaction records
 * against the contract's linked escrow VA.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/escrow")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class EscrowController {

    private final EscrowService escrowService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<EscrowResponse>>> getAllEscrowContracts(
            @RequestParam(required = false) UUID corporateId,
            @RequestParam(required = false) String status) {

        EscrowStatus parsed = parseStatus(status);
        List<EscrowResponse> contracts = escrowService.listEscrows(corporateId, parsed);
        return ResponseEntity.ok(ApiResponse.success(contracts));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<EscrowResponse>> getEscrowDetails(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(escrowService.getEscrow(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<EscrowResponse>> createEscrow(@RequestBody CreateEscrowRequest request) {
        return ResponseEntity.ok(ApiResponse.success(escrowService.createEscrow(request)));
    }

    @PostMapping("/{id}/fund")
    public ResponseEntity<ApiResponse<FundEscrowResponse>> fundEscrow(
            @PathVariable UUID id,
            @RequestBody FundEscrowRequest request) {
        return ResponseEntity.ok(ApiResponse.success(escrowService.fundEscrow(id, request)));
    }

    @PostMapping("/{id}/release")
    public ResponseEntity<ApiResponse<ReleaseEscrowResponse>> releaseEscrow(
            @PathVariable UUID id,
            @RequestBody ReleaseEscrowRequest request) {
        return ResponseEntity.ok(ApiResponse.success(escrowService.releaseFunds(id, request)));
    }

    @PostMapping("/{id}/extend")
    public ResponseEntity<ApiResponse<ExtendEscrowResponse>> extendEscrow(
            @PathVariable UUID id,
            @RequestBody ExtendEscrowRequest request) {
        return ResponseEntity.ok(ApiResponse.success(escrowService.extendEscrow(id, request)));
    }

    @PostMapping("/{id}/dispute")
    public ResponseEntity<ApiResponse<EscrowResponse>> disputeEscrow(
            @PathVariable UUID id,
            @RequestBody DisputeEscrowRequest request) {
        return ResponseEntity.ok(ApiResponse.success(escrowService.disputeEscrow(id, request)));
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<EscrowStatsResponse>> getEscrowStats(
            @RequestParam(required = false) UUID corporateId) {
        return ResponseEntity.ok(ApiResponse.success(escrowService.getStats(corporateId)));
    }

    /** Derived from the EscrowType enum so the list cannot drift from what the service accepts. */
    @GetMapping("/types")
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> getEscrowTypes() {
        List<Map<String, String>> types = Arrays.stream(EscrowType.values())
            .map(t -> Map.of("code", t.name(), "name", TYPE_LABELS.get(t), "description", TYPE_DESCRIPTIONS.get(t)))
            .toList();
        return ResponseEntity.ok(ApiResponse.success(types));
    }

    private static EscrowStatus parseStatus(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) return null;
        try {
            return EscrowStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new com.bank.vam.exception.BusinessException("Unknown escrow status: " + status);
        }
    }

    private static final Map<EscrowType, String> TYPE_LABELS = Map.of(
        EscrowType.TRADE, "Trade Escrow",
        EscrowType.REAL_ESTATE, "Real Estate Escrow",
        EscrowType.M_AND_A, "M&A Escrow",
        EscrowType.MILESTONE, "Milestone Escrow",
        EscrowType.RENT, "Rent Escrow"
    );

    private static final Map<EscrowType, String> TYPE_DESCRIPTIONS = Map.of(
        EscrowType.TRADE, "For goods/services trade",
        EscrowType.REAL_ESTATE, "Property transactions",
        EscrowType.M_AND_A, "Mergers and acquisitions",
        EscrowType.MILESTONE, "Project milestone payments",
        EscrowType.RENT, "Rental deposits and payments"
    );
}
