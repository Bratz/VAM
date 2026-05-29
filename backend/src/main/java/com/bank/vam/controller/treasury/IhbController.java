package com.bank.vam.controller.treasury;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.dto.VirtualAccountDto;
import com.bank.vam.dto.treasury.IhbDto;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.service.VirtualAccountService;
import com.bank.vam.service.treasury.IhbSettlementService;
import com.bank.vam.service.treasury.IhbUnifiedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * IHB Controller - Unified with LegalEntity (Phase 2 Complete).
 *
 * All endpoints now use IhbUnifiedService which operates on LegalEntity.
 * Legacy IhbEntity-based service has been deprecated.
 *
 * All endpoints require corporateId for scoping.
 *
 * Base URL: /api/v1/ihb
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ihb")
@RequiredArgsConstructor
@Tag(name = "In-House Bank", description = "Intercompany lending and deposits")
public class IhbController {

    private final IhbUnifiedService ihbUnifiedService;
    private final VirtualAccountService virtualAccountService;
    private final IhbSettlementService ihbSettlementService;

    // ========================================================================
    // GLOBAL ENDPOINTS (All IHB entities across corporates)
    // ========================================================================

    @GetMapping("/entities")
    @Operation(summary = "Get all IHB-enabled entities",
               description = "Returns all LegalEntities with IHB enabled across all corporates")
    public ResponseEntity<ApiResponse<List<IhbDto.EntityResponse>>> getAllEntities() {
        log.debug("GET /api/v1/ihb/entities - Get all IHB entities");
        return ResponseEntity.ok(ApiResponse.success(ihbUnifiedService.getAllIhbEntities()));
    }

    @GetMapping("/entities/{entityId}")
    @Operation(summary = "Get IHB entity by ID")
    public ResponseEntity<ApiResponse<IhbDto.EntityResponse>> getEntityById(
            @Parameter(description = "Legal Entity ID") @PathVariable UUID entityId) {
        log.debug("GET /api/v1/ihb/entities/{}", entityId);
        return ResponseEntity.ok(ApiResponse.success(ihbUnifiedService.getIhbEntityById(entityId)));
    }

    @GetMapping("/entities/{entityId}/position")
    @Operation(summary = "Get entity IHB position")
    public ResponseEntity<ApiResponse<IhbDto.EntityPositionResponse>> getEntityPosition(
            @Parameter(description = "Legal Entity ID") @PathVariable UUID entityId) {
        log.debug("GET /api/v1/ihb/entities/{}/position", entityId);
        return ResponseEntity.ok(ApiResponse.success(ihbUnifiedService.getEntityPosition(entityId)));
    }

    @PostMapping("/loans/{loanId}/repay")
    @Operation(summary = "Repay loan")
    public ResponseEntity<ApiResponse<IhbDto.LoanResponse>> repayLoan(
            @Parameter(description = "Loan ID") @PathVariable UUID loanId,
            @Valid @RequestBody IhbDto.LoanRepaymentRequest request) {
        log.info("POST /api/v1/ihb/loans/{}/repay - {}", loanId, request.getAmount());
        return ResponseEntity.ok(ApiResponse.success(
            ihbUnifiedService.repayLoan(loanId, request), "Repayment processed"));
    }

    @PostMapping("/deposits/{depositId}/withdraw")
    @Operation(summary = "Withdraw from deposit")
    public ResponseEntity<ApiResponse<IhbDto.DepositResponse>> withdrawDeposit(
            @Parameter(description = "Deposit ID") @PathVariable UUID depositId,
            @Valid @RequestBody IhbDto.WithdrawRequest request) {
        log.info("POST /api/v1/ihb/deposits/{}/withdraw - {}", depositId, request.getAmount());
        return ResponseEntity.ok(ApiResponse.success(
            ihbUnifiedService.withdrawDeposit(depositId, request), "Withdrawal processed"));
    }

    // ========================================================================
    // CORPORATE-SCOPED ENDPOINTS
    // ========================================================================

    @GetMapping("/corporate/{corporateId}/entities")
    @Operation(summary = "Get IHB entities for corporate")
    public ResponseEntity<ApiResponse<List<IhbDto.EntityResponse>>> getEntitiesByCorporate(
            @Parameter(description = "Corporate ID") @PathVariable UUID corporateId) {
        log.debug("GET /api/v1/ihb/corporate/{}/entities", corporateId);
        return ResponseEntity.ok(ApiResponse.success(ihbUnifiedService.getIhbEntities(corporateId)));
    }

    @PostMapping("/entities/{entityId}/enable")
    @Operation(summary = "Enable IHB for legal entity",
               description = "Enable In-House Banking for an existing LegalEntity")
    public ResponseEntity<ApiResponse<IhbDto.EntityResponse>> enableIhb(
            @Parameter(description = "Legal Entity ID") @PathVariable UUID entityId,
            @Valid @RequestBody IhbDto.EnableIhbRequest request) {
        log.info("POST /api/v1/ihb/entities/{}/enable - limit {}", entityId, request.getCreditLimit());
        return ResponseEntity.ok(ApiResponse.success(
            ihbUnifiedService.enableIhb(entityId, request), "IHB enabled for entity"));
    }

    @PutMapping("/entities/{entityId}/settings")
    @Operation(summary = "Update IHB settings")
    public ResponseEntity<ApiResponse<IhbDto.EntityResponse>> updateIhbSettings(
            @Parameter(description = "Legal Entity ID") @PathVariable UUID entityId,
            @Valid @RequestBody IhbDto.UpdateIhbSettingsRequest request) {
        log.info("PUT /api/v1/ihb/entities/{}/settings", entityId);
        return ResponseEntity.ok(ApiResponse.success(
            ihbUnifiedService.updateIhbSettings(entityId, request), "IHB settings updated"));
    }

    @PostMapping("/entities/{entityId}/disable")
    @Operation(summary = "Disable IHB for legal entity")
    public ResponseEntity<ApiResponse<Void>> disableIhb(
            @Parameter(description = "Legal Entity ID") @PathVariable UUID entityId) {
        log.info("POST /api/v1/ihb/entities/{}/disable", entityId);
        ihbUnifiedService.disableIhb(entityId);
        return ResponseEntity.ok(ApiResponse.success(null, "IHB disabled for entity"));
    }

    // ========================================================================
    // LOAN ENDPOINTS
    // ========================================================================

    @GetMapping("/corporate/{corporateId}/loans")
    @Operation(summary = "Get all loans for corporate")
    public ResponseEntity<ApiResponse<List<IhbDto.LoanResponse>>> getLoans(
            @Parameter(description = "Corporate ID") @PathVariable UUID corporateId) {
        log.debug("GET /api/v1/ihb/corporate/{}/loans", corporateId);
        return ResponseEntity.ok(ApiResponse.success(ihbUnifiedService.getLoans(corporateId)));
    }

    @GetMapping("/corporate/{corporateId}/loans/active")
    @Operation(summary = "Get active loans for corporate")
    public ResponseEntity<ApiResponse<List<IhbDto.LoanResponse>>> getActiveLoans(
            @Parameter(description = "Corporate ID") @PathVariable UUID corporateId) {
        log.debug("GET /api/v1/ihb/corporate/{}/loans/active", corporateId);
        return ResponseEntity.ok(ApiResponse.success(ihbUnifiedService.getActiveLoans(corporateId)));
    }

    @PostMapping("/loans")
    @Operation(summary = "Create IHB loan",
               description = "Create intercompany loan between two LegalEntities")
    public ResponseEntity<ApiResponse<IhbDto.LoanResponse>> createLoan(
            @Valid @RequestBody IhbDto.CreateLoanUnifiedRequest request) {
        log.info("POST /api/v1/ihb/loans - from {} to {} for {}",
            request.getLenderEntityId(), request.getBorrowerEntityId(), request.getPrincipalAmount());
        return ResponseEntity.ok(ApiResponse.success(
            ihbUnifiedService.createLoan(request), "Loan created"));
    }

    // ========================================================================
    // DEPOSIT ENDPOINTS
    // ========================================================================

    @GetMapping("/corporate/{corporateId}/deposits")
    @Operation(summary = "Get all deposits for corporate")
    public ResponseEntity<ApiResponse<List<IhbDto.DepositResponse>>> getDeposits(
            @Parameter(description = "Corporate ID") @PathVariable UUID corporateId) {
        log.debug("GET /api/v1/ihb/corporate/{}/deposits", corporateId);
        return ResponseEntity.ok(ApiResponse.success(ihbUnifiedService.getDeposits(corporateId)));
    }

    @PostMapping("/deposits")
    @Operation(summary = "Create IHB deposit",
               description = "Create intercompany deposit from a LegalEntity")
    public ResponseEntity<ApiResponse<IhbDto.DepositResponse>> createDeposit(
            @Valid @RequestBody IhbDto.CreateDepositUnifiedRequest request) {
        log.info("POST /api/v1/ihb/deposits - from {} for {}",
            request.getDepositorEntityId(), request.getPrincipalAmount());
        return ResponseEntity.ok(ApiResponse.success(
            ihbUnifiedService.createDeposit(request), "Deposit created"));
    }

    // ========================================================================
    // INTEREST & STATISTICS
    // ========================================================================

    @PostMapping("/corporate/{corporateId}/interest/calculate")
    @Operation(summary = "Calculate daily interest")
    public ResponseEntity<ApiResponse<IhbDto.CalculateInterestResponse>> calculateInterest(
            @Parameter(description = "Corporate ID") @PathVariable UUID corporateId) {
        log.info("POST /api/v1/ihb/corporate/{}/interest/calculate", corporateId);
        return ResponseEntity.ok(ApiResponse.success(
            ihbUnifiedService.calculateDailyInterest(corporateId), "Interest calculated"));
    }

    @GetMapping("/corporate/{corporateId}/stats")
    @Operation(summary = "Get IHB statistics")
    public ResponseEntity<ApiResponse<IhbDto.IhbStatsResponse>> getStats(
            @Parameter(description = "Corporate ID") @PathVariable UUID corporateId) {
        log.debug("GET /api/v1/ihb/corporate/{}/stats", corporateId);
        return ResponseEntity.ok(ApiResponse.success(ihbUnifiedService.getStats(corporateId)));
    }

    @GetMapping("/corporate/{corporateId}/settlement/status")
    @Operation(summary = "Get settlement status for corporate")
    public ResponseEntity<ApiResponse<IhbSettlementService.SettlementStatusResponse>> getSettlementStatus(
            @Parameter(description = "Corporate ID") @PathVariable UUID corporateId) {
        log.debug("GET /api/v1/ihb/corporate/{}/settlement/status", corporateId);
        return ResponseEntity.ok(ApiResponse.success(ihbSettlementService.getSettlementStatus(corporateId)));
    }

    // ========================================================================
    // TREASURY RATES
    // ========================================================================

    @GetMapping("/corporate/{corporateId}/treasury-rates")
    @Operation(summary = "Get Treasury Center's offered rates",
               description = "Returns lending and deposit rates offered by the Treasury Center")
    public ResponseEntity<ApiResponse<IhbDto.TreasuryRatesResponse>> getTreasuryRates(
            @Parameter(description = "Corporate ID") @PathVariable UUID corporateId) {
        log.info("GET /api/v1/ihb/corporate/{}/treasury-rates", corporateId);
        return ResponseEntity.ok(ApiResponse.success(
            ihbUnifiedService.getTreasuryRates(corporateId),
            "Treasury rates retrieved"));
    }

    @GetMapping("/treasury-rates/entity/{treasuryCenterId}")
    @Operation(summary = "Get Treasury Center's rates by entity ID")
    public ResponseEntity<ApiResponse<IhbDto.TreasuryRatesResponse>> getTreasuryRatesById(
            @Parameter(description = "Treasury Center Entity ID") @PathVariable UUID treasuryCenterId) {
        log.info("GET /api/v1/ihb/treasury-rates/entity/{}", treasuryCenterId);
        return ResponseEntity.ok(ApiResponse.success(
            ihbUnifiedService.getTreasuryRatesById(treasuryCenterId),
            "Treasury rates retrieved"));
    }

    @PostMapping("/indicative-rate")
    @Operation(summary = "Calculate indicative rate for loan/deposit",
               description = "Returns the effective rate an entity would receive based on amount, tenor, and type")
    public ResponseEntity<ApiResponse<IhbDto.IndicativeRateResponse>> getIndicativeRate(
            @Parameter(description = "Treasury Center ID") @RequestParam UUID treasuryCenterId,
            @Parameter(description = "Entity ID (borrower/depositor)") @RequestParam UUID entityId,
            @Valid @RequestBody IhbDto.IndicativeRateRequest request) {
        log.info("POST /api/v1/ihb/indicative-rate - treasury={}, entity={}, type={}",
            treasuryCenterId, entityId, request.getRateType());
        return ResponseEntity.ok(ApiResponse.success(
            ihbUnifiedService.getIndicativeRate(treasuryCenterId, entityId, request),
            "Indicative rate calculated"));
    }

    // ========================================================================
    // IHB CURRENT ACCOUNT ENDPOINTS
    // ========================================================================

    @PostMapping("/current-account")
    @Operation(summary = "Create IHB Current Account",
               description = "Creates an INTERCOMPANY VA that functions as a participant's " +
                           "current account at the In-House Bank (Treasury Center). " +
                           "Supports running balance with credit/debit interest and overdraft facility.")
    public ResponseEntity<ApiResponse<VirtualAccountDto.Response>> createCurrentAccount(
            @Valid @RequestBody VirtualAccountDto.IhbCurrentAccountRequest request) {
        log.info("POST /api/v1/ihb/current-account - entity={}, currency={}, parentNodeId={}, programId={}",
            request.getParticipantEntityId(), request.getCurrencyCode(),
            request.getParentNodeId(), request.getProgramId());
        VirtualAccount va = virtualAccountService.createIhbCurrentAccount(request);
        return ResponseEntity.ok(ApiResponse.success(
            virtualAccountService.toResponse(va),
            "IHB Current Account created"));
    }

    @GetMapping("/current-account/{accountId}/position")
    @Operation(summary = "Get IHB Current Account position",
               description = "Returns the current position including balance, accrued interest, and limits")
    public ResponseEntity<ApiResponse<VirtualAccountDto.IhbPositionResponse>> getCurrentAccountPosition(
            @Parameter(description = "IHB Current Account ID") @PathVariable UUID accountId) {
        log.debug("GET /api/v1/ihb/current-account/{}/position", accountId);
        return ResponseEntity.ok(ApiResponse.success(
            ihbUnifiedService.getIhbCurrentAccountPosition(accountId)));
    }

    @GetMapping("/corporate/{corporateId}/current-accounts")
    @Operation(summary = "Get all IHB Current Accounts for corporate",
               description = "Returns all INTERCOMPANY VAs for the corporate")
    public ResponseEntity<ApiResponse<List<VirtualAccountDto.IhbPositionResponse>>> getCurrentAccountsByCorporate(
            @Parameter(description = "Corporate ID") @PathVariable UUID corporateId) {
        log.debug("GET /api/v1/ihb/corporate/{}/current-accounts", corporateId);
        return ResponseEntity.ok(ApiResponse.success(
            ihbUnifiedService.getIhbCurrentAccountsByCorporate(corporateId)));
    }

    @PostMapping("/current-account/{accountId}/deposit")
    @Operation(summary = "Deposit to IHB Current Account",
               description = "Credits the account, increasing balance")
    public ResponseEntity<ApiResponse<VirtualAccountDto.IhbPositionResponse>> depositToCurrentAccount(
            @Parameter(description = "IHB Current Account ID") @PathVariable UUID accountId,
            @Valid @RequestBody IhbDto.CurrentAccountTransactionRequest request) {
        log.info("POST /api/v1/ihb/current-account/{}/deposit - amount={}", accountId, request.getAmount());
        return ResponseEntity.ok(ApiResponse.success(
            ihbUnifiedService.depositToCurrentAccount(accountId, request),
            "Deposit processed"));
    }

    @PostMapping("/current-account/{accountId}/withdraw")
    @Operation(summary = "Withdraw from IHB Current Account",
               description = "Debits the account. Can go into overdraft up to credit limit.")
    public ResponseEntity<ApiResponse<VirtualAccountDto.IhbPositionResponse>> withdrawFromCurrentAccount(
            @Parameter(description = "IHB Current Account ID") @PathVariable UUID accountId,
            @Valid @RequestBody IhbDto.CurrentAccountTransactionRequest request) {
        log.info("POST /api/v1/ihb/current-account/{}/withdraw - amount={}", accountId, request.getAmount());
        return ResponseEntity.ok(ApiResponse.success(
            ihbUnifiedService.withdrawFromCurrentAccount(accountId, request),
            "Withdrawal processed"));
    }

    @PostMapping("/current-account/transfer")
    @Operation(summary = "Transfer between IHB Current Accounts",
               description = "Intercompany transfer between two IHB current accounts")
    public ResponseEntity<ApiResponse<IhbDto.CurrentAccountTransferResponse>> transferBetweenCurrentAccounts(
            @Valid @RequestBody IhbDto.CurrentAccountTransferRequest request) {
        log.info("POST /api/v1/ihb/current-account/transfer - from={} to={} amount={}",
            request.getFromAccountId(), request.getToAccountId(), request.getAmount());
        return ResponseEntity.ok(ApiResponse.success(
            ihbUnifiedService.transferBetweenCurrentAccounts(request),
            "Transfer processed"));
    }

    @PostMapping("/current-accounts/calculate-interest")
    @Operation(summary = "Calculate daily interest for all IHB Current Accounts",
               description = "Calculates and accrues interest on all IHB current accounts")
    public ResponseEntity<ApiResponse<List<VirtualAccountDto.IhbInterestCalcResult>>> calculateCurrentAccountInterest() {
        log.info("POST /api/v1/ihb/current-accounts/calculate-interest");
        return ResponseEntity.ok(ApiResponse.success(
            ihbUnifiedService.calculateDailyInterestForCurrentAccounts(),
            "Interest calculated for all IHB current accounts"));
    }

    @PostMapping("/current-accounts/post-interest")
    @Operation(summary = "Post accrued interest to IHB Current Accounts",
               description = "Posts accumulated interest to account balances (typically monthly)")
    public ResponseEntity<ApiResponse<List<VirtualAccountDto.IhbInterestPostingResult>>> postCurrentAccountInterest() {
        log.info("POST /api/v1/ihb/current-accounts/post-interest");
        return ResponseEntity.ok(ApiResponse.success(
            ihbUnifiedService.postInterestForCurrentAccounts(),
            "Interest posted for all IHB current accounts"));
    }
}