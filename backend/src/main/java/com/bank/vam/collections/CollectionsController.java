package com.bank.vam.collections;

import com.bank.vam.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Customer mandates and collection runs (bulk direct debit / Request to Pay). */
@RestController
@RequestMapping("/api/v1/collections")
@RequiredArgsConstructor
public class CollectionsController {

    private final MandateService mandates;
    private final CollectionRoutingService routing;
    private final CollectionRunService runs;
    private final CollectionSettlementService settlement;
    private final CollectionReturnService returns;
    private final CollectionIntake intake;

    @GetMapping("/mandates")
    public ApiResponse<List<MandateService.MandateView>> list(@RequestParam UUID corporateId) {
        return ApiResponse.success(mandates.list(corporateId));
    }

    @PostMapping("/mandates")
    public ApiResponse<MandateService.MandateView> register(@RequestBody MandateService.MandateRequest request) {
        return ApiResponse.success(mandates.register(request), "Mandate registered");
    }

    /** CSV: mandateReference, customer, creditorAccount, schemeCode, validFrom [, debtorIban, debtorBic, debtorName,
     *  currency, type, validTo, signedDate, maxPerCollection, frequency (e.g. 1/MONTH)]. All rows or none. */
    @PostMapping(value = "/mandates/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<MandateService.ImportResult> upload(@RequestParam UUID corporateId, @RequestParam MultipartFile file) throws IOException {
        MandateService.ImportResult result = mandates.importFile(corporateId, new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
        return ApiResponse.success(result, result.imported() + " mandates imported");
    }

    /** An e-mandate: sent to the payer's bank (pain.009) and pending until it accepts. */
    @PostMapping("/mandates/emandate")
    public ApiResponse<MandateService.MandateView> requestEMandate(@RequestBody MandateService.MandateRequest request) {
        return ApiResponse.success(mandates.requestEMandate(request), "Mandate request sent to the payer's bank");
    }

    @PostMapping("/mandates/{id}/amend")
    public ApiResponse<MandateService.MandateView> amend(@PathVariable UUID id, @RequestBody MandateService.Amendment amendment) {
        return ApiResponse.success(mandates.amend(id, amendment));
    }

    public record BankAnswerRequest(boolean accepted, String reasonCode) {
    }

    /** The payer's bank's answer to a waiting e-mandate request or amendment, recorded by hand. */
    @PostMapping("/mandates/{id}/bank-answer")
    public ApiResponse<MandateService.BankAnswer> bankAnswer(@PathVariable UUID id, @RequestBody BankAnswerRequest request) {
        return ApiResponse.success(mandates.bankAnswer(id, request.accepted(), request.reasonCode()));
    }

    @GetMapping(value = "/mandates/{id}/message", produces = MediaType.APPLICATION_XML_VALUE)
    public String lastMessage(@PathVariable UUID id) {
        return mandates.lastMessage(id);
    }

    @PutMapping("/mandates/{id}")
    public ApiResponse<MandateService.MandateView> update(@PathVariable UUID id, @RequestBody MandateService.MandateUpdate update) {
        return ApiResponse.success(mandates.update(id, update), "Mandate updated");
    }

    /** action: suspend, resume or cancel; body may carry a {@code reason}. */
    @PostMapping("/mandates/{id}/{action:suspend|resume|cancel}")
    public ApiResponse<MandateService.MandateView> changeStatus(@PathVariable UUID id, @PathVariable String action,
                                                                @RequestBody(required = false) Map<String, String> body) {
        return ApiResponse.success(mandates.changeStatus(id, action, body == null ? null : body.get("reason")));
    }

    public record PreviewRequest(UUID corporateId, List<UUID> receivableIds) {
    }

    /** How the chosen invoices would be collected; nothing is sent. */
    @PostMapping("/runs/preview")
    public ApiResponse<CollectionRoutingService.Preview> preview(@RequestBody PreviewRequest request) {
        return ApiResponse.success(routing.preview(request.corporateId(), request.receivableIds() == null ? List.of() : request.receivableIds()));
    }

    /** Routes the invoices and sends them: direct debits into the run's pain.008, Request to Pay for the rest. */
    @PostMapping("/runs")
    public ApiResponse<CollectionRunService.RunDetail> submit(@RequestBody PreviewRequest request) {
        CollectionRunService.RunDetail run = runs.submit(request.corporateId(),
                request.receivableIds() == null ? List.of() : request.receivableIds(), CollectionRun.Source.INVOICES);
        return ApiResponse.success(run, "Collection run " + run.run().runRef() + " sent");
    }

    public record LinesRequest(UUID corporateId, List<CollectionIntake.IntakeLine> lines) {
    }

    /**
     * For the corporate's ERP: lines naming an invoice collect it; lines naming none raise it first. The lines that
     * resolve go out as one run; the rest come back in {@code rejected} with the reason.
     */
    @PostMapping("/runs/lines")
    public ApiResponse<CollectionIntake.IntakeResult> submitLines(@RequestBody LinesRequest request) {
        CollectionIntake.IntakeResult r = intake.collect(request.corporateId(), request.lines() == null ? List.of() : request.lines(),
                CollectionRun.Source.API);
        return ApiResponse.success(r, "Collection run " + r.run().run().runRef() + " sent"
                + (r.rejected().isEmpty() ? "" : "; " + r.rejected().size() + " line(s) not collected"));
    }

    @GetMapping("/runs")
    public ApiResponse<List<CollectionRunService.RunSummary>> runs(@RequestParam UUID corporateId) {
        return ApiResponse.success(runs.list(corporateId));
    }

    @GetMapping("/runs/{id}")
    public ApiResponse<CollectionRunService.RunDetail> run(@PathVariable UUID id) {
        return ApiResponse.success(runs.get(id));
    }

    @GetMapping(value = "/runs/{id}/pain008", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> pain008(@PathVariable UUID id) {
        CollectionRun run = runs.find(id);
        if (run.getPain008Xml() == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + run.getRunRef() + ".pain008.xml\"")
                .body(run.getPain008Xml());
    }

    /** Settles direct debits due by {@code asOf} (default today) and lifts holds that end by it. Runs daily on its own. */
    @PostMapping("/process")
    public ApiResponse<CollectionSettlementService.Result> process(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ApiResponse.success(settlement.process(asOf != null ? asOf : LocalDate.now()));
    }

    public record ReturnRequest(String reasonCode) {
    }

    /** "Mark returned": a collected debit comes back (or a waiting one is rejected) with an ISO reason code. */
    @PostMapping("/lines/{id}/return")
    public ApiResponse<CollectionReturnService.Outcome> returnLine(@PathVariable UUID id, @RequestBody ReturnRequest request) {
        return ApiResponse.success(returns.returnLine(id, request.reasonCode()));
    }

    @GetMapping("/return-reasons")
    public ApiResponse<Map<String, String>> returnReasons() {
        return ApiResponse.success(new java.util.TreeMap<>(CollectionReturnService.REASONS));
    }

    public record Settings(int holdBusinessDays) {
    }

    @GetMapping("/settings")
    public ApiResponse<Settings> settings(@RequestParam UUID corporateId) {
        return ApiResponse.success(new Settings(runs.holdDays(corporateId)));
    }

    @PutMapping("/settings")
    public ApiResponse<Settings> updateSettings(@RequestParam UUID corporateId, @RequestBody Settings settings) {
        return ApiResponse.success(new Settings(runs.setHoldDays(corporateId, settings.holdBusinessDays())), "Saved");
    }
}
