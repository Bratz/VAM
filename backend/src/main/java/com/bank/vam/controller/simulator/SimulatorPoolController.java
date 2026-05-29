package com.bank.vam.controller.simulator;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.dto.simulator.PoolMembershipDto;
import com.bank.vam.service.simulator.SimulatorPoolService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Notional-pool membership for the Simulator interest-yield Pool basket (R2).
 * Read-only; no live-table writes. Separate controller — Phase 1–4 endpoints
 * untouched (L9).
 */
@RestController
@RequestMapping("/api/v1/simulator/pool-membership")
@RequiredArgsConstructor
@Tag(name = "Cash Concentration Simulator", description = "Pool membership (R2)")
public class SimulatorPoolController {

    private final SimulatorPoolService poolService;

    @GetMapping
    @Operation(summary = "Notional-pool membership for the given physical accounts")
    public ResponseEntity<ApiResponse<List<PoolMembershipDto>>> getPoolMembership(
            @RequestParam List<UUID> physicalAccountIds) {
        return ResponseEntity.ok(ApiResponse.success(
                poolService.getPoolMembership(physicalAccountIds)));
    }
}
