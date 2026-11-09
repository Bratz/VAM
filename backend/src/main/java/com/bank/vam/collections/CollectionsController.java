package com.bank.vam.collections;

import com.bank.vam.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
}
