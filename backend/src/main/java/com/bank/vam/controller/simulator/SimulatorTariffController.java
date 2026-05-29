package com.bank.vam.controller.simulator;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.dto.simulator.BankFeeTariffDto;
import com.bank.vam.service.simulator.BankFeeTariffService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Effective bank-fee tariffs for the Phase-2 Bank-fees score line.
 * Read-only reference data; no live-table writes.
 *
 * <p>Separate controller (not folded into {@code SimulatorController}, whose
 * class path is {@code /scenarios}) so the verified Phase-1 scenario endpoints
 * stay untouched.
 */
@RestController
@RequestMapping("/api/v1/simulator/tariffs")
@RequiredArgsConstructor
@Tag(name = "Cash Concentration Simulator", description = "Bank-fee tariffs (Phase 2)")
/** @deprecated 2026-05-16 — `/simulator/tariffs` superseded by the bank's
 *  real charge schedule via `/tax-charges/charge-configs`. Endpoint retained
 *  but unused by the Simulator; do not build on it. */
@Deprecated
public class SimulatorTariffController {

    private final BankFeeTariffService tariffService;

    @GetMapping
    @Operation(summary = "Effective bank-fee tariffs for the given bank codes")
    public ResponseEntity<ApiResponse<List<BankFeeTariffDto>>> getTariffs(
            @RequestParam List<String> bankCodes,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ResponseEntity.ok(
                ApiResponse.success(tariffService.getEffectiveTariffs(bankCodes, asOf)));
    }
}
