package com.bank.vam.controller.wallet;

import com.bank.vam.dto.WalletDto.*;
import com.bank.vam.service.wallet.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WalletController - REST API for Wallet operations.
 * 
 * Thin controller following MVC pattern - delegates all business logic to WalletService.
 * 
 * Uses EXISTING domain model:
 * - VirtualAccount (with walletType) as Wallet
 * - Transaction for wallet operations
 * - Party as wallet holder
 * - Program for wallet program config
 */
@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
@Tag(name = "Wallets", description = "Prepaid Wallet Program and Account Management APIs")
@CrossOrigin(origins = "*")
public class WalletController {

    private final WalletService walletService;

    // ========================================================================
    // STATS
    // ========================================================================

    @GetMapping("/stats")
    @Operation(summary = "Get wallet stats", description = "Get unified stats across programs and wallets")
    public ResponseEntity<Map<String, Object>> getStats(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId) {
        WalletStatsResponse stats = walletService.getStats(corporateId);
        return ResponseEntity.ok(Map.of("success", true, "data", stats));
    }

    // ========================================================================
    // PROGRAMS
    // ========================================================================

    @GetMapping("/programs")
    @Operation(summary = "Get all wallet programs", description = "List wallet programs with stats")
    public ResponseEntity<Map<String, Object>> getPrograms(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId,
            @RequestParam(required = false) String status) {
        List<WalletProgramResponse> programs = walletService.getAllPrograms(corporateId, status);
        return ResponseEntity.ok(Map.of("success", true, "data", programs));
    }

    @GetMapping("/programs/{programId}")
    @Operation(summary = "Get program details", description = "Get detailed program info with stats")
    public ResponseEntity<Map<String, Object>> getProgramDetails(@PathVariable UUID programId) {
        WalletProgramDetailResponse program = walletService.getProgramDetails(programId);
        return ResponseEntity.ok(Map.of("success", true, "data", program));
    }

    @PostMapping("/programs")
    @Operation(summary = "Create wallet program", description = "Create a new wallet program")
    public ResponseEntity<Map<String, Object>> createProgram(@RequestBody CreateProgramRequest request) {
        WalletProgramResponse program = walletService.createProgram(request);
        return ResponseEntity.ok(Map.of("success", true, "data", program, "message", "Program created successfully"));
    }

    @PutMapping("/programs/{programId}")
    @Operation(summary = "Update wallet program", description = "Update program settings")
    public ResponseEntity<Map<String, Object>> updateProgram(
            @PathVariable UUID programId,
            @RequestBody UpdateProgramRequest request) {
        WalletProgramResponse program = walletService.updateProgram(programId, request);
        return ResponseEntity.ok(Map.of("success", true, "data", program, "message", "Program updated successfully"));
    }

    @GetMapping("/programs/{programId}/stats")
    @Operation(summary = "Get program stats", description = "Get detailed statistics for a program")
    public ResponseEntity<Map<String, Object>> getProgramStats(@PathVariable UUID programId) {
        WalletProgramStatsResponse stats = walletService.getProgramStats(programId);
        return ResponseEntity.ok(Map.of("success", true, "data", stats));
    }

    @GetMapping("/programs/{programId}/wallets")
    @Operation(summary = "Get wallets in program", description = "List wallets belonging to a program")
    public ResponseEntity<Map<String, Object>> getWalletsByProgram(
            @PathVariable UUID programId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String query) {
        WalletListResponse wallets = walletService.getWalletsByProgram(programId, page, size, status, query);
        return ResponseEntity.ok(Map.of("success", true, "data", wallets));
    }

    // ========================================================================
    // WALLETS
    // ========================================================================

    @GetMapping
    @Operation(summary = "Get all wallets", description = "List all wallets with optional filters")
    public ResponseEntity<Map<String, Object>> getAllWallets(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) UUID corporateId,
            @RequestParam(required = false) UUID programId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Boolean kycVerified) {
        WalletListResponse wallets = walletService.getAllWallets(page, size, corporateId, programId, status, query, kycVerified);
        return ResponseEntity.ok(Map.of("success", true, "data", wallets));
    }

    @GetMapping("/{walletId}")
    @Operation(summary = "Get wallet details", description = "Get detailed wallet information")
    public ResponseEntity<Map<String, Object>> getWalletDetails(@PathVariable UUID walletId) {
        WalletAccountDetailResponse wallet = walletService.getWalletDetails(walletId);
        return ResponseEntity.ok(Map.of("success", true, "data", wallet));
    }

    @GetMapping("/reference/{reference}")
    @Operation(summary = "Get wallet by reference", description = "Get wallet by wallet reference number")
    public ResponseEntity<Map<String, Object>> getWalletByReference(@PathVariable String reference) {
        WalletAccountDetailResponse wallet = walletService.getWalletByReference(reference);
        return ResponseEntity.ok(Map.of("success", true, "data", wallet));
    }

    @PostMapping
    @Operation(summary = "Issue new wallet", description = "Issue a new wallet to a beneficiary")
    public ResponseEntity<Map<String, Object>> issueWallet(@RequestBody IssueWalletRequest request) {
        WalletAccountResponse wallet = walletService.issueWallet(request);
        return ResponseEntity.ok(Map.of("success", true, "data", wallet, "message", "Wallet issued successfully"));
    }

    @PutMapping("/{walletId}")
    @Operation(summary = "Update wallet", description = "Update wallet settings")
    public ResponseEntity<Map<String, Object>> updateWallet(
            @PathVariable UUID walletId,
            @RequestBody UpdateWalletRequest request) {
        WalletAccountResponse wallet = walletService.updateWallet(walletId, request);
        return ResponseEntity.ok(Map.of("success", true, "data", wallet, "message", "Wallet updated successfully"));
    }

    // ========================================================================
    // WALLET OPERATIONS
    // ========================================================================

    @PostMapping("/{walletId}/load")
    @Operation(summary = "Load funds", description = "Load/top-up funds into a wallet")
    public ResponseEntity<Map<String, Object>> loadFunds(
            @PathVariable UUID walletId,
            @RequestBody LoadFundsRequest request) {
        LoadFundsResponse response = walletService.loadFunds(walletId, request);
        return ResponseEntity.ok(Map.of("success", true, "data", response, "message", "Funds loaded successfully"));
    }

    @PostMapping("/{walletId}/withdraw")
    @Operation(summary = "Withdraw funds", description = "Withdraw funds from a wallet")
    public ResponseEntity<Map<String, Object>> withdrawFunds(
            @PathVariable UUID walletId,
            @RequestBody WithdrawRequest request) {
        WithdrawResponse response = walletService.withdrawFunds(walletId, request);
        return ResponseEntity.ok(Map.of("success", true, "data", response, "message", "Withdrawal completed successfully"));
    }

    @PostMapping("/{walletId}/transfer")
    @Operation(summary = "Transfer funds", description = "Transfer funds to another wallet")
    public ResponseEntity<Map<String, Object>> transferFunds(
            @PathVariable UUID walletId,
            @RequestBody TransferRequest request) {
        TransferResponse response = walletService.transferFunds(walletId, request);
        return ResponseEntity.ok(Map.of("success", true, "data", response, "message", "Transfer completed successfully"));
    }

    @PostMapping("/bulk-load")
    @Operation(summary = "Bulk load funds", description = "Load funds to multiple wallets")
    public ResponseEntity<Map<String, Object>> bulkLoadFunds(@RequestBody BulkLoadRequest request) {
        BulkLoadResponse response = walletService.bulkLoadFunds(request);
        return ResponseEntity.ok(Map.of("success", true, "data", response, "message", "Bulk load completed"));
    }

    // ========================================================================
    // STATUS MANAGEMENT
    // ========================================================================

    @PostMapping("/{walletId}/suspend")
    @Operation(summary = "Suspend wallet", description = "Suspend a wallet")
    public ResponseEntity<Map<String, Object>> suspendWallet(
            @PathVariable UUID walletId,
            @RequestBody(required = false) SuspendRequest request) {
        String reason = request != null ? request.getReason() : "Not specified";
        WalletAccountResponse wallet = walletService.suspendWallet(walletId, reason);
        return ResponseEntity.ok(Map.of("success", true, "data", wallet, "message", "Wallet suspended"));
    }

    @PostMapping("/{walletId}/reactivate")
    @Operation(summary = "Reactivate wallet", description = "Reactivate a suspended wallet")
    public ResponseEntity<Map<String, Object>> reactivateWallet(
            @PathVariable UUID walletId,
            @RequestBody(required = false) ReactivateRequest request) {
        String reason = request != null ? request.getReason() : "Not specified";
        WalletAccountResponse wallet = walletService.reactivateWallet(walletId, reason);
        return ResponseEntity.ok(Map.of("success", true, "data", wallet, "message", "Wallet reactivated"));
    }

    @PostMapping("/{walletId}/block")
    @Operation(summary = "Block wallet", description = "Block a wallet (requires manual review to unblock)")
    public ResponseEntity<Map<String, Object>> blockWallet(
            @PathVariable UUID walletId,
            @RequestBody BlockRequest request) {
        WalletAccountResponse wallet = walletService.blockWallet(walletId, request.getReason());
        return ResponseEntity.ok(Map.of("success", true, "data", wallet, "message", "Wallet blocked"));
    }

    // ========================================================================
    // KYC
    // ========================================================================

    @PostMapping("/{walletId}/verify-kyc")
    @Operation(summary = "Verify KYC", description = "Mark wallet KYC as verified")
    public ResponseEntity<Map<String, Object>> verifyKyc(
            @PathVariable UUID walletId,
            @RequestBody VerifyKycRequest request) {
        KycVerificationResponse response = walletService.verifyKyc(walletId, request);
        return ResponseEntity.ok(Map.of("success", true, "data", response, "message", "KYC verified successfully"));
    }

    // ========================================================================
    // TRANSACTIONS
    // ========================================================================

    @GetMapping("/{walletId}/transactions")
    @Operation(summary = "Get wallet transactions", description = "Get transaction history for a wallet")
    public ResponseEntity<Map<String, Object>> getWalletTransactions(
            @PathVariable UUID walletId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<WalletTransactionResponse> transactions = walletService.getWalletTransactions(walletId, page, size);
        return ResponseEntity.ok(Map.of("success", true, "data", transactions));
    }

    // ========================================================================
    // WALLET TYPES
    // ========================================================================

    @GetMapping("/types")
    @Operation(summary = "Get wallet types", description = "Get available wallet types")
    public ResponseEntity<Map<String, Object>> getWalletTypes() {
        List<Map<String, Object>> types = walletService.getWalletTypes();
        return ResponseEntity.ok(Map.of("success", true, "data", types));
    }
}