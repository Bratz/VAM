package com.bank.vam.fx.deal;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.fx.deal.FxDealModels.DealView;
import com.bank.vam.fx.deal.FxDealModels.MtmPoint;
import com.bank.vam.fx.deal.FxDealModels.SettlementView;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What happens to a deal after it is booked: the corporate's confirmation, drawdowns, close-out and roll,
 * value-date settlement and marks. Settlement is a record; no account balance is moved.
 */
@RestController
@RequestMapping("/api/v1/fx/deals")
@RequiredArgsConstructor
public class FxDealOpsController {

    public record DisputeBody(@NotNull String reason) {
    }

    public record ResolveBody(@NotNull FxConfirmationService.Resolution outcome, String note) {
    }

    public record DrawdownBody(@NotNull BigDecimal amount, @NotNull LocalDate valueDate) {
    }

    public record RollBody(@NotNull LocalDate newValueDate) {
    }

    private final FxDealService deals;
    private final FxConfirmationService confirmations;
    private final FxSettlementService settlement;
    private final FxLifecycleService lifecycle;
    private final FxMtmService mtm;
    private final FxDealAttentionService attention;
    private final FxSettlementRepository settlements;
    private final DealViewAssembler views;

    // ---- confirmation ----

    /** The corporate affirms the bank's confirmation. Affirming twice is harmless. */
    @PostMapping("/{id}/confirm")
    public ApiResponse<DealView> confirm(@PathVariable UUID id) {
        return ApiResponse.success(views.view(confirmations.affirm(id)));
    }

    @PostMapping("/{id}/dispute")
    public ApiResponse<DealView> dispute(@PathVariable UUID id, @RequestBody DisputeBody body) {
        return ApiResponse.success(views.view(confirmations.dispute(id, body.reason())));
    }

    /** The bank's answer to a dispute: re-issue a corrected confirmation, or cancel the deal. */
    @PostMapping("/{id}/resolve-dispute")
    public ApiResponse<DealView> resolve(@PathVariable UUID id, @RequestBody ResolveBody body) {
        return ApiResponse.success(views.view(confirmations.resolve(id, body.outcome(), body.note())));
    }

    @GetMapping(value = "/{id}/confirmation.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> confirmationPdf(@PathVariable UUID id) {
        FxDeal d = deals.get(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + d.getDealRef() + "-confirmation.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(deals.confirmationPdf(d));
    }

    // ---- in-life ----

    @PostMapping("/{id}/drawdowns")
    public ApiResponse<SettlementView> drawdown(@PathVariable UUID id, @RequestBody DrawdownBody body) {
        return ApiResponse.success(SettlementView.of(settlement.drawdown(id, body.amount(), body.valueDate())));
    }

    @GetMapping("/{id}/close-out")
    public ApiResponse<FxLifecycleService.CloseOutQuote> closeOutPreview(@PathVariable UUID id) {
        return ApiResponse.success(lifecycle.closeOutPreview(id));
    }

    @PostMapping("/{id}/close-out")
    public ApiResponse<DealView> closeOut(@PathVariable UUID id) {
        return ApiResponse.success(views.view(lifecycle.closeOut(id)));
    }

    @GetMapping("/{id}/roll")
    public ApiResponse<FxLifecycleService.RollQuote> rollPreview(
            @PathVariable UUID id, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate newValueDate) {
        return ApiResponse.success(lifecycle.rollPreview(id, newValueDate));
    }

    @PostMapping("/{id}/roll")
    public ApiResponse<DealView> roll(@PathVariable UUID id, @RequestBody RollBody body) {
        return ApiResponse.success(views.view(lifecycle.roll(id, body.newValueDate()).replacement()));
    }

    // ---- reads ----

    @GetMapping("/{id}/settlements")
    public ApiResponse<List<SettlementView>> settlementsOf(@PathVariable UUID id) {
        return ApiResponse.success(settlements.findByDealIdOrderByCreatedAtAsc(id).stream().map(SettlementView::of).toList());
    }

    @GetMapping("/{id}/mtm")
    public ApiResponse<List<MtmPoint>> mtmHistory(@PathVariable UUID id) {
        return ApiResponse.success(mtm.history(id).stream().map(MtmPoint::of).toList());
    }

    // ---- batch runs ----

    /**
     * Settles whatever is due. {@code asOf} moves the clock forward for the demo, because the simulated desk
     * has no other way to reach a value date; leave it out for today.
     */
    @PostMapping("/settlement/run")
    public ApiResponse<FxSettlementService.RunResult> runSettlement(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) UUID corporateId) {
        return ApiResponse.success(settlement.run(asOf, corporateId));
    }

    @PostMapping("/mtm/run")
    public ApiResponse<Integer> runMtm(@RequestParam(required = false) UUID corporateId) {
        return ApiResponse.success(mtm.snapshot(LocalDate.now(), corporateId));
    }

    @GetMapping("/attention")
    public ApiResponse<List<FxDealAttentionService.Item>> attention(@RequestParam(required = false) UUID corporateId) {
        return ApiResponse.success(attention.items(corporateId));
    }
}
