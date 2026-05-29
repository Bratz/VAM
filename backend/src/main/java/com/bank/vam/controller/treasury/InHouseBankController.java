package com.bank.vam.controller.treasury;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.dto.treasury.InHouseBankDto.*;
import com.bank.vam.service.treasury.InHouseBankService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * @deprecated Use IhbController at /api/v1/ihb instead.
 * This controller uses the legacy InHouseBankService with IhbEntity.
 * All new development should use IhbController with IhbUnifiedService (LegalEntity-based).
 */
@Deprecated(since = "2.0", forRemoval = true)
@RestController
@RequestMapping("/api/v1/treasury/ihb")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "In-House Bank (Legacy)", description = "DEPRECATED - Use /api/v1/ihb instead")
public class InHouseBankController {

    private final InHouseBankService ihbService;

    // ========================================================================
    // SUMMARY / DASHBOARD
    // ========================================================================

    @GetMapping("/summary")
    @Operation(summary = "Get IHB summary/dashboard stats")
    public ResponseEntity<ApiResponse<IhbSummaryResponse>> getSummary() {
        return ResponseEntity.ok(ApiResponse.success(ihbService.getSummary()));
    }

    // ========================================================================
    // PARTICIPANTS
    // ========================================================================

    @GetMapping("/participants")
    @Operation(summary = "Get all IHB participants")
    public ResponseEntity<ApiResponse<List<ParticipantResponse>>> getAllParticipants() {
        return ResponseEntity.ok(ApiResponse.success(ihbService.getAllParticipants()));
    }

    @GetMapping("/participants/{id}")
    @Operation(summary = "Get participant by ID")
    public ResponseEntity<ApiResponse<ParticipantResponse>> getParticipantById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(ihbService.getParticipantById(id)));
    }

    @PostMapping("/participants")
    @Operation(summary = "Create new IHB participant")
    public ResponseEntity<ApiResponse<ParticipantResponse>> createParticipant(
            @RequestBody CreateParticipantRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                ihbService.createParticipant(request), "Participant created successfully"));
    }

    @PutMapping("/participants/{id}")
    @Operation(summary = "Update participant")
    public ResponseEntity<ApiResponse<ParticipantResponse>> updateParticipant(
            @PathVariable UUID id, @RequestBody UpdateParticipantRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                ihbService.updateParticipant(id, request), "Participant updated"));
    }

    @DeleteMapping("/participants/{id}")
    @Operation(summary = "Delete/deactivate participant")
    public ResponseEntity<ApiResponse<Void>> deleteParticipant(@PathVariable UUID id) {
        ihbService.deleteParticipant(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Participant deactivated"));
    }

    // ========================================================================
    // TRANSACTIONS
    // ========================================================================

    @GetMapping("/transactions")
    @Operation(summary = "Get all funding transactions")
    public ResponseEntity<ApiResponse<List<FundingTransactionResponse>>> getAllTransactions() {
        return ResponseEntity.ok(ApiResponse.success(ihbService.getAllTransactions()));
    }

    @GetMapping("/transactions/entity/{entityId}")
    @Operation(summary = "Get transactions for an entity")
    public ResponseEntity<ApiResponse<List<FundingTransactionResponse>>> getTransactionsByEntity(
            @PathVariable UUID entityId) {
        return ResponseEntity.ok(ApiResponse.success(ihbService.getTransactionsByEntity(entityId)));
    }

    @PostMapping("/transactions")
    @Operation(summary = "Create funding transaction")
    public ResponseEntity<ApiResponse<FundingTransactionResponse>> createTransaction(
            @RequestBody CreateFundingRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                ihbService.createFundingTransaction(request), "Transaction created"));
    }

    // ========================================================================
    // INTEREST ACCRUALS
    // ========================================================================

    @GetMapping("/interest/accruals")
    @Operation(summary = "Get all interest accruals")
    public ResponseEntity<ApiResponse<List<InterestAccrualResponse>>> getAllAccruals() {
        return ResponseEntity.ok(ApiResponse.success(ihbService.getAllAccruals()));
    }

    @GetMapping("/interest/accruals/period")
    @Operation(summary = "Get accruals for a period")
    public ResponseEntity<ApiResponse<List<InterestAccrualResponse>>> getAccrualsByPeriod(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        return ResponseEntity.ok(ApiResponse.success(ihbService.getAccrualsByPeriod(start, end)));
    }

    @PostMapping("/interest/calculate")
    @Operation(summary = "Calculate interest for period")
    public ResponseEntity<ApiResponse<CalculateInterestResponse>> calculateInterest(
            @RequestBody(required = false) CalculateInterestRequest request) {
        if (request == null) request = new CalculateInterestRequest();
        return ResponseEntity.ok(ApiResponse.success(
                ihbService.calculateInterest(request), "Interest calculated"));
    }

    @PostMapping("/interest/post")
    @Operation(summary = "Post accrued interest")
    public ResponseEntity<ApiResponse<List<InterestAccrualResponse>>> postInterest(
            @RequestBody PostInterestRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                ihbService.postInterest(request), "Interest posted"));
    }
}
