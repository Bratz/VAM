package com.bank.vam.controller.tax;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.dto.tax.TaxChargeDto.*;
import com.bank.vam.entity.tax.ChargeConfiguration;
import com.bank.vam.service.tax.ChargeService;
import com.bank.vam.service.tax.TaxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Tax and Charge Controller - REST APIs for tax/charge management.
 * 
 * Endpoints:
 * - Tax jurisdictions and configurations
 * - Tax calculation
 * - Charge configurations
 * - Payment charge calculation
 * - Net amount calculation
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tax-charges")
@RequiredArgsConstructor
public class TaxChargeController {

    private final TaxService taxService;
    private final ChargeService chargeService;

    // ========================================================================
    // TAX JURISDICTION ENDPOINTS
    // ========================================================================

    @GetMapping("/jurisdictions")
    public ResponseEntity<ApiResponse<List<JurisdictionResponse>>> getAllJurisdictions() {
        List<JurisdictionResponse> jurisdictions = taxService.getAllJurisdictions();
        return ResponseEntity.ok(ApiResponse.success(jurisdictions));
    }

    @GetMapping("/jurisdictions/{code}")
    public ResponseEntity<ApiResponse<JurisdictionResponse>> getJurisdiction(
            @PathVariable String code) {
        JurisdictionResponse jurisdiction = taxService.getJurisdiction(code);
        return ResponseEntity.ok(ApiResponse.success(jurisdiction));
    }

    // ========================================================================
    // TAX CONFIGURATION ENDPOINTS
    // ========================================================================

    @GetMapping("/tax-configs")
    public ResponseEntity<ApiResponse<List<TaxConfigResponse>>> getAllTaxConfigs() {
        List<TaxConfigResponse> configs = taxService.getAllTaxConfigs();
        return ResponseEntity.ok(ApiResponse.success(configs));
    }

    @GetMapping("/tax-configs/{taxCode}")
    public ResponseEntity<ApiResponse<TaxConfigResponse>> getTaxConfig(
            @PathVariable String taxCode) {
        TaxConfigResponse config = taxService.getTaxConfig(taxCode);
        return ResponseEntity.ok(ApiResponse.success(config));
    }

    @GetMapping("/tax-configs/jurisdiction/{jurisdictionCode}")
    public ResponseEntity<ApiResponse<List<TaxConfigResponse>>> getTaxConfigsByJurisdiction(
            @PathVariable String jurisdictionCode) {
        List<TaxConfigResponse> configs = taxService.getTaxConfigsByJurisdiction(jurisdictionCode);
        return ResponseEntity.ok(ApiResponse.success(configs));
    }

    @GetMapping("/tax-configs/withholding")
    public ResponseEntity<ApiResponse<List<TaxConfigResponse>>> getWithholdingTaxConfigs() {
        List<TaxConfigResponse> configs = taxService.getWithholdingTaxConfigs();
        return ResponseEntity.ok(ApiResponse.success(configs));
    }

    @PostMapping("/tax-configs")
    public ResponseEntity<ApiResponse<TaxConfigResponse>> createTaxConfig(
            @RequestBody CreateTaxConfigRequest request) {
        TaxConfigResponse config = taxService.createTaxConfig(request);
        return ResponseEntity.ok(ApiResponse.success(config, "Tax configuration created"));
    }

    // ========================================================================
    // TAX CALCULATION ENDPOINTS
    // ========================================================================

    @PostMapping("/calculate-tax")
    public ResponseEntity<ApiResponse<CalculateTaxResponse>> calculateTax(
            @RequestBody CalculateTaxRequest request) {
        CalculateTaxResponse result = taxService.calculateTax(request);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/calculate-payable-taxes")
    public ResponseEntity<ApiResponse<TaxBreakdownResponse>> calculatePayableTaxes(
            @RequestParam UUID payableId,
            @RequestParam BigDecimal grossAmount,
            @RequestParam(required = false) String jurisdictionCode,
            @RequestParam(defaultValue = "true") boolean isService) {
        TaxBreakdownResponse result = taxService.calculatePayableTaxes(
            payableId, grossAmount, jurisdictionCode, isService);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/calculate-net-amount")
    public ResponseEntity<ApiResponse<CalculateNetAmountResponse>> calculateNetAmount(
            @RequestBody CalculateNetAmountRequest request) {
        CalculateNetAmountResponse result = taxService.calculateNetAmount(request);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/taxes/{referenceType}/{referenceId}")
    public ResponseEntity<ApiResponse<List<CalculateTaxResponse>>> getTaxesForReference(
            @PathVariable String referenceType,
            @PathVariable UUID referenceId) {
        List<CalculateTaxResponse> taxes = taxService.getTaxesForReference(referenceType, referenceId);
        return ResponseEntity.ok(ApiResponse.success(taxes));
    }

    @PostMapping("/apply-taxes/{referenceId}")
    public ResponseEntity<ApiResponse<Void>> applyTaxes(@PathVariable UUID referenceId) {
        taxService.applyTaxes(referenceId);
        return ResponseEntity.ok(ApiResponse.success(null, "Taxes applied successfully"));
    }

    // ========================================================================
    // CHARGE CONFIGURATION ENDPOINTS
    // ========================================================================

    @GetMapping("/charge-configs")
    public ResponseEntity<ApiResponse<List<ChargeConfigResponse>>> getAllChargeConfigs() {
        List<ChargeConfigResponse> configs = chargeService.getAllChargeConfigs();
        return ResponseEntity.ok(ApiResponse.success(configs));
    }

    @GetMapping("/charge-configs/{chargeCode}")
    public ResponseEntity<ApiResponse<ChargeConfigResponse>> getChargeConfig(
            @PathVariable String chargeCode) {
        ChargeConfigResponse config = chargeService.getChargeConfig(chargeCode);
        return ResponseEntity.ok(ApiResponse.success(config));
    }

    @GetMapping("/charge-configs/type/{chargeType}")
    public ResponseEntity<ApiResponse<List<ChargeConfigResponse>>> getChargeConfigsByType(
            @PathVariable ChargeConfiguration.ChargeType chargeType) {
        List<ChargeConfigResponse> configs = chargeService.getChargeConfigsByType(chargeType);
        return ResponseEntity.ok(ApiResponse.success(configs));
    }

    @PostMapping("/charge-configs")
    public ResponseEntity<ApiResponse<ChargeConfigResponse>> createChargeConfig(
            @RequestBody CreateChargeConfigRequest request) {
        ChargeConfigResponse config = chargeService.createChargeConfig(request);
        return ResponseEntity.ok(ApiResponse.success(config, "Charge configuration created"));
    }

    // ========================================================================
    // CHARGE CALCULATION ENDPOINTS
    // ========================================================================

    @PostMapping("/calculate-charges")
    public ResponseEntity<ApiResponse<CalculateChargesResponse>> calculateCharges(
            @RequestBody CalculateChargesRequest request) {
        CalculateChargesResponse result = chargeService.calculateCharges(request);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/calculate-payment-total")
    public ResponseEntity<ApiResponse<CalculatePaymentTotalResponse>> calculatePaymentTotal(
            @RequestBody CalculatePaymentTotalRequest request) {
        CalculatePaymentTotalResponse result = chargeService.calculatePaymentTotal(request);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/apply-charges/{referenceId}")
    public ResponseEntity<ApiResponse<Void>> applyCharges(@PathVariable UUID referenceId) {
        chargeService.applyCharges(referenceId);
        return ResponseEntity.ok(ApiResponse.success(null, "Charges applied successfully"));
    }

    // ========================================================================
    // WAIVER ENDPOINTS
    // ========================================================================

    @PostMapping("/charges/{calculationId}/waive")
    public ResponseEntity<ApiResponse<Void>> waiveCharge(
            @PathVariable UUID calculationId,
            @RequestParam String reason,
            @RequestParam String approvedBy) {
        chargeService.waiveCharge(calculationId, reason, approvedBy);
        return ResponseEntity.ok(ApiResponse.success(null, "Charge waived successfully"));
    }

    @PostMapping("/charges/{calculationId}/partial-waive")
    public ResponseEntity<ApiResponse<Void>> partialWaiveCharge(
            @PathVariable UUID calculationId,
            @RequestParam BigDecimal waiverAmount,
            @RequestParam String reason,
            @RequestParam String approvedBy) {
        chargeService.waiveCharge(calculationId, waiverAmount, reason, approvedBy);
        return ResponseEntity.ok(ApiResponse.success(null, "Partial waiver applied"));
    }

    @GetMapping("/charges/waived")
    public ResponseEntity<ApiResponse<List<ChargeLineItem>>> getWaivedCharges() {
        List<ChargeLineItem> waived = chargeService.getWaivedCharges();
        return ResponseEntity.ok(ApiResponse.success(waived));
    }

    // ========================================================================
    // QUICK CALCULATION ENDPOINTS
    // ========================================================================

    @GetMapping("/calculate-vat")
    public ResponseEntity<ApiResponse<BigDecimal>> calculateUaeVat(
            @RequestParam BigDecimal amount) {
        BigDecimal vat = taxService.calculateUaeVat(amount);
        return ResponseEntity.ok(ApiResponse.success(vat));
    }

    @GetMapping("/calculate-pobo-fee")
    public ResponseEntity<ApiResponse<BigDecimal>> calculatePoboFee(
            @RequestParam BigDecimal amount) {
        BigDecimal fee = chargeService.calculatePoboFee(amount);
        return ResponseEntity.ok(ApiResponse.success(fee));
    }

    @GetMapping("/standard-rate/{jurisdictionCode}")
    public ResponseEntity<ApiResponse<BigDecimal>> getStandardRate(
            @PathVariable String jurisdictionCode) {
        BigDecimal rate = taxService.getStandardRate(jurisdictionCode);
        return ResponseEntity.ok(ApiResponse.success(rate));
    }
}