package com.bank.vam.controller.intercompany;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.service.intercompany.IntercompanyGroupPositionService;
import com.bank.vam.service.intercompany.IntercompanyGroupPositionService.GroupPosition;
import com.bank.vam.service.intercompany.IntercompanyTransactionService;
import com.bank.vam.service.intercompany.IntercompanyTransactionService.NettingEligibilityResult;
import com.bank.vam.service.intercompany.IntercompanyTransactionService.AddToNettingResult;
import com.bank.vam.service.intercompany.IntercompanyTransactionService.SubsidiaryIntercompanyPosition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * The Intercompany page's API: the group's who-owes-whom, per-subsidiary IHB positions, and the netting hand-off.
 * Every call is scoped to one corporate. The legacy transaction, settlement and report endpoints went with the
 * intercompany_transactions table (spec D3, R12); settlement is Netting Cycles only (D4).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/intercompany")
@RequiredArgsConstructor
@Tag(name = "Intercompany", description = "Intercompany positions and netting hand-off")
public class IntercompanyController {

    private final IntercompanyTransactionService transactionService;
    private final IntercompanyGroupPositionService groupPositionService;


    @GetMapping("/positions")
    @Operation(summary = "Get per-subsidiary intercompany positions",
        description = "IC Receivable/Payable balances per subsidiary, sourced from the real VA ledger")
    public ResponseEntity<ApiResponse<List<SubsidiaryIntercompanyPosition>>> getIntercompanyPositions(
            @RequestParam(required = false) UUID corporateId) {
        log.debug("Getting intercompany positions for corporate: {}", corporateId);
        return ResponseEntity.ok(ApiResponse.success(transactionService.getIntercompanyPositions(requireCorporate(corporateId))));
    }

    @GetMapping("/group-position")
    @Operation(summary = "Get the group's who-owes-whom",
        description = "Per currency and pair of entities: in-house bank positions (ledger), open intercompany invoices "
            + "and recharges awaiting netting; a zero-sum control per currency; a reporting-currency total with its rates")
    public ResponseEntity<ApiResponse<GroupPosition>> getGroupPosition(
            @RequestParam(required = false) UUID corporateId) {
        return ResponseEntity.ok(ApiResponse.success(groupPositionService.getGroupPosition(requireCorporate(corporateId))));
    }

    @GetMapping("/netting-eligibility")
    @Operation(summary = "Check netting eligibility", 
        description = "What a netting cycle would take now for a pair of the corporate's entities, and its open cycles")
    public ResponseEntity<ApiResponse<NettingEligibilityResult>> checkNettingEligibility(
            @RequestParam(required = false) UUID corporateId,
            @RequestParam UUID entity1Id,
            @RequestParam UUID entity2Id) {
        log.debug("Checking netting eligibility: {} <-> {}", entity1Id, entity2Id);
        NettingEligibilityResult result = transactionService.getNettingEligibleTransactions(
            requireCorporate(corporateId), entity1Id, entity2Id);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/add-to-netting/{cycleId}")
    @Operation(summary = "Add to netting cycle",
        description = "Add the corporate's IC invoices and recharges to one of its netting cycles")
    public ResponseEntity<ApiResponse<AddToNettingResult>> addToNettingCycle(
            @PathVariable UUID cycleId,
            @RequestParam(required = false) UUID corporateId,
            @RequestBody AddToNettingRequest request) {
        log.info("Adding to netting cycle: {}", cycleId);
        AddToNettingResult result = transactionService.addToNettingCycle(requireCorporate(corporateId), cycleId,
            request.getRechargeIds(), request.getPayableIds(), request.getReceivableIds());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * Intercompany data belongs to one corporate's group; never all corporates' at once (neither page has a bank-wide
     * view). Refused as a 400, as KycController / PayablesController do.
     */
    static UUID requireCorporate(UUID corporateId) {
        if (corporateId == null) throw new BusinessException("corporateId is required");
        return corporateId;
    }

    @lombok.Data
    public static class AddToNettingRequest {
        private List<UUID> rechargeIds;
        private List<UUID> payableIds;
        private List<UUID> receivableIds;
    }
}
