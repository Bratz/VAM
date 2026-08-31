package com.bank.vam.controller.simulator;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.dto.simulator.SourceQualityDto;
import com.bank.vam.service.simulator.SourceQualityService;
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
 * Trailing-90d source-quality metrics for the Phase-4 Source-quality score
 * line. Read-only; no live-table writes.
 *
 * <p>Separate controller (not folded into the scenarios/tariffs controllers)
 * so the curl-verified Phase 1–3 endpoints stay untouched.
 */
@RestController
@RequestMapping("/api/v1/simulator/source-quality")
@RequiredArgsConstructor
@Tag(name = "Cash Concentration Simulator", description = "Source quality (Phase 4)")
public class SimulatorSourceQualityController {

    private final SourceQualityService sourceQualityService;

    @GetMapping
    @Operation(summary = "Trailing-90d miss-rate / avg-lag per physical account")
    public ResponseEntity<ApiResponse<List<SourceQualityDto>>> getSourceQuality(
            @RequestParam List<UUID> physicalAccountIds) {
        return ResponseEntity.ok(ApiResponse.success(
                sourceQualityService.getSourceQuality(physicalAccountIds)));
    }
}
