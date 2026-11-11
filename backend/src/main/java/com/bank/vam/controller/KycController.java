package com.bank.vam.controller;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.service.party.KycService;
import com.bank.vam.service.party.KycService.KycCaseResponse;
import com.bank.vam.service.party.KycService.KycDecisionRequest;
import com.bank.vam.service.party.KycService.KycDetailResponse;
import com.bank.vam.service.party.KycService.KycStatsResponse;
import com.bank.vam.service.party.KycService.RequestInfoRequest;
import com.bank.vam.service.party.KycService.RequestInfoResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * KYC review. Every endpoint reads and writes the party rows through
 * KycService — a case is a party whose KYC is still open, and a decision
 * updates that party's kycStatus.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/kyc")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class KycController {

    private final KycService kycService;

    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<List<KycCaseResponse>>> getPendingKyc(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId) {
        return ResponseEntity.ok(ApiResponse.success(
            kycService.getPendingReview(requireCorporate(corporateId))));
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<KycStatsResponse>> getKycStats(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId) {
        return ResponseEntity.ok(ApiResponse.success(
            kycService.getStats(requireCorporate(corporateId))));
    }

    @GetMapping("/expiring")
    public ResponseEntity<ApiResponse<List<KycCaseResponse>>> getExpiringKyc(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId,
            @RequestParam(defaultValue = "30") int daysAhead) {
        return ResponseEntity.ok(ApiResponse.success(
            kycService.getExpiring(requireCorporate(corporateId), daysAhead)));
    }

    /** {@code id} is the party id — a KYC case is a party with open KYC. */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<KycDetailResponse>> getKycDetails(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(kycService.getKycDetail(id)));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<ApiResponse<KycCaseResponse>> approveKyc(
            @PathVariable UUID id,
            @RequestBody KycDecisionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(kycService.approve(id, request)));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<ApiResponse<KycCaseResponse>> rejectKyc(
            @PathVariable UUID id,
            @RequestBody KycDecisionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(kycService.reject(id, request)));
    }

    @PostMapping("/{id}/request-info")
    public ResponseEntity<ApiResponse<RequestInfoResponse>> requestAdditionalInfo(
            @PathVariable UUID id,
            @RequestBody RequestInfoRequest request) {
        return ResponseEntity.ok(ApiResponse.success(kycService.requestAdditionalInfo(id, request)));
    }

    /** KYC cases belong to one corporate; never fall back to a default one. */
    private static UUID requireCorporate(UUID corporateId) {
        if (corporateId == null) throw new BusinessException("X-Corporate-Id header is required");
        return corporateId;
    }
}
