package com.bank.vam.controller.pobo;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.service.pobo.PoboEnrolmentService;
import com.bank.vam.service.pobo.PoboEnrolmentService.EnrolmentView;
import com.bank.vam.service.pobo.PoboEnrolmentService.PaymentAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** POBO enrolments: which subsidiaries, per currency, the treasury centre pays for and from which account
 * (In-House Bank screen; spec docs/specs/pobo-in-house-bank.md R1-R3). */
@RestController
@RequestMapping("/api/v1/pobo/enrolments")
@RequiredArgsConstructor
@Tag(name = "POBO enrolment", description = "Payments on behalf of subsidiaries through the in-house bank")
public class PoboEnrolmentController {

    private final PoboEnrolmentService service;

    public record CreateEnrolmentRequest(UUID corporateId, UUID subsidiaryEntityId, String currency,
                                         UUID payerEntityId, UUID paymentVaId, LocalDate effectiveFrom) {
    }

    @GetMapping
    @Operation(summary = "The corporate's POBO enrolments, newest first")
    public ResponseEntity<ApiResponse<List<EnrolmentView>>> list(@RequestParam UUID corporateId) {
        return ResponseEntity.ok(ApiResponse.success(service.list(corporateId)));
    }

    @PostMapping
    @Operation(summary = "Enrol a subsidiary for payment on its behalf in one currency")
    public ResponseEntity<ApiResponse<EnrolmentView>> create(@RequestBody CreateEnrolmentRequest r) {
        return ResponseEntity.ok(ApiResponse.success(service.create(r.corporateId(), r.subsidiaryEntityId(), r.currency(),
            r.payerEntityId(), r.paymentVaId(), r.effectiveFrom()), "Enrolled"));
    }

    @PostMapping("/{id}/end")
    @Operation(summary = "End an enrolment today; payables are paid directly from then on")
    public ResponseEntity<ApiResponse<EnrolmentView>> end(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(service.end(id), "Enrolment ended"));
    }

    @GetMapping("/payment-accounts")
    @Operation(summary = "The treasury centre's bank-backed accounts an enrolment in this currency can pay from")
    public ResponseEntity<ApiResponse<List<PaymentAccount>>> paymentAccounts(@RequestParam UUID payerEntityId,
                                                                           @RequestParam String currency) {
        return ResponseEntity.ok(ApiResponse.success(service.paymentAccounts(payerEntityId, currency.trim().toUpperCase())));
    }
}
