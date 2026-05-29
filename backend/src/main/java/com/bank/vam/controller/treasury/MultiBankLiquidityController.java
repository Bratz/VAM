package com.bank.vam.controller.treasury;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.dto.treasury.MultiBankLiquidityDto.LiquiditySummary;
import com.bank.vam.entity.VirtualAccount.BalanceRefreshStatus;
import com.bank.vam.service.treasury.MultiBankLiquidityViewService;
import com.bank.vam.service.treasury.refresh.BalanceRefreshService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Read-side API for the Multi-Bank Liquidity dashboard, plus on-demand shadow
 * balance refresh.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/treasury/multi-bank")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "Multi-Bank Liquidity", description = "Multi-Bank liquidity view + refresh APIs")
public class MultiBankLiquidityController {

    private final MultiBankLiquidityViewService viewService;
    private final BalanceRefreshService refreshService;

    @GetMapping("/summary")
    @Operation(summary = "Multi-bank liquidity summary",
               description = "Aggregates PHYSICAL_MIRROR shadows by bank × currency, with home-bank-held vs external split.")
    public ResponseEntity<ApiResponse<LiquiditySummary>> getSummary(
            @RequestParam(required = false) UUID corporateId) {
        LiquiditySummary summary = viewService.getSummary(corporateId);
        return ResponseEntity.ok(ApiResponse.success(summary));
    }

    @PostMapping("/shadows/{shadowVaId}/refresh")
    @Operation(summary = "Refresh a single shadow balance",
               description = "Force-refresh regardless of freshness state.")
    public ResponseEntity<ApiResponse<BalanceRefreshStatus>> refresh(@PathVariable UUID shadowVaId) {
        BalanceRefreshStatus status = refreshService.refresh(shadowVaId);
        return ResponseEntity.ok(ApiResponse.success(status));
    }

    @PostMapping("/shadows/{shadowVaId}/refresh-if-stale")
    @Operation(summary = "Refresh shadow balance only if stale",
               description = "Refreshes only if last refresh exceeds the shadow's freshness threshold.")
    public ResponseEntity<ApiResponse<BalanceRefreshStatus>> refreshIfStale(@PathVariable UUID shadowVaId) {
        BalanceRefreshStatus status = refreshService.refreshIfStale(shadowVaId);
        return ResponseEntity.ok(ApiResponse.success(status));
    }
}
