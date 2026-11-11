package com.bank.vam.controller.receivables;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.entity.receivables.InwardFxHold;
import com.bank.vam.iso20022.dto.Iso20022PaymentDto.InwardPaymentResponse;
import com.bank.vam.iso20022.service.Iso20022InwardPaymentService;
import com.bank.vam.service.receivables.CoboCutoverService;
import com.bank.vam.service.receivables.CoboEnrolmentService;
import com.bank.vam.service.receivables.CoboEnrolmentService.CollectionAccount;
import com.bank.vam.service.receivables.CoboEnrolmentService.EnrolmentView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** COBO enrolments: which subsidiaries, per currency, the treasury centre collects for (In-House Bank screen); and the
 * one-off cut-over of old-flow COBO invoices (spec D7). */
@RestController
@RequestMapping("/api/v1/cobo")
@RequiredArgsConstructor
@Tag(name = "COBO enrolment", description = "Collections on behalf of subsidiaries through the in-house bank")
public class CoboEnrolmentController {

    private final CoboEnrolmentService service;
    private final CoboCutoverService cutover;
    private final Iso20022InwardPaymentService inward;

    public record PostHeldRequest(UUID quoteId) {
    }

    public record CreateEnrolmentRequest(UUID corporateId, UUID subsidiaryEntityId, String currency,
                                         UUID collectorEntityId, UUID collectionVaId, UUID ihbAccountId, LocalDate effectiveFrom) {
    }

    @GetMapping("/enrolments")
    @Operation(summary = "The corporate's COBO enrolments, newest first")
    public ResponseEntity<ApiResponse<List<EnrolmentView>>> list(@RequestParam UUID corporateId) {
        return ResponseEntity.ok(ApiResponse.success(service.list(corporateId)));
    }

    @PostMapping("/enrolments")
    @Operation(summary = "Enrol a subsidiary for collection on its behalf in one currency")
    public ResponseEntity<ApiResponse<EnrolmentView>> create(@RequestBody CreateEnrolmentRequest r) {
        return ResponseEntity.ok(ApiResponse.success(service.create(r.corporateId(), r.subsidiaryEntityId(), r.currency(),
            r.collectorEntityId(), r.collectionVaId(), r.ihbAccountId(), r.effectiveFrom()), "Enrolled"));
    }

    @PostMapping("/enrolments/{id}/end")
    @Operation(summary = "End an enrolment today; new invoices are collected directly")
    public ResponseEntity<ApiResponse<EnrolmentView>> end(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(service.end(id), "Enrolment ended"));
    }

    @GetMapping("/enrolments/collection-accounts")
    @Operation(summary = "The treasury centre's accounts an enrolment settled in this currency (its IHB account's) can collect into")
    public ResponseEntity<ApiResponse<List<CollectionAccount>>> collectionAccounts(@RequestParam UUID collectorEntityId,
                                                                                 @RequestParam String currency) {
        return ResponseEntity.ok(ApiResponse.success(service.collectionAccounts(collectorEntityId, currency.trim().toUpperCase())));
    }

    @PostMapping("/cutover")
    @Operation(summary = "Move the corporate's old-flow COBO invoices: pending -> direct, approved -> enrolment or direct (dryRun previews)")
    public ResponseEntity<ApiResponse<CoboCutoverService.Report>> cutover(@RequestParam UUID corporateId,
                                                                         @RequestParam(defaultValue = "true") boolean dryRun) {
        return ResponseEntity.ok(ApiResponse.success(cutover.cutover(corporateId, dryRun), dryRun ? "Preview" : "Moved"));
    }

    @GetMapping("/awaiting-fx")
    @Operation(summary = "Inward credits to COBO VIBANs held for a negotiated rate (ihb-fx D8), oldest first")
    public ResponseEntity<ApiResponse<List<InwardFxHold>>> awaitingFx(@RequestParam UUID corporateId) {
        return ResponseEntity.ok(ApiResponse.success(inward.heldForFx(corporateId)));
    }

    @PostMapping("/awaiting-fx/{id}/post")
    @Operation(summary = "Post a held credit converted at the accepted firm quote: invoice applied, subsidiary credited")
    public ResponseEntity<ApiResponse<InwardPaymentResponse>> postHeld(@PathVariable UUID id, @RequestBody PostHeldRequest r) {
        return ResponseEntity.ok(ApiResponse.success(inward.postHeld(id, r.quoteId()), "Posted"));
    }

    public record ReturnHeldRequest(String reason) {
    }

    @PostMapping("/awaiting-fx/{id}/return")
    @Operation(summary = "Return a held credit to the payer instead of converting it (RETURNED; the funds are sent back by hand in v1)")
    public ResponseEntity<ApiResponse<InwardFxHold>> returnHeld(@PathVariable UUID id, @RequestBody ReturnHeldRequest r) {
        return ResponseEntity.ok(ApiResponse.success(inward.returnHeld(id, r.reason()), "Returned to the payer"));
    }
}
