package com.bank.vam.fx.hedge;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.fx.hedge.FxHedgeLinkService.DealLinks;
import com.bank.vam.fx.hedge.FxHedgeLinkService.LinkRequest;
import com.bank.vam.fx.hedge.FxHedgeLinkService.LinkView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Which invoices an FX deal hedges. Every call names the corporate; a deal of another corporate is not found, and only
 * that corporate's invoices can be linked.
 */
@RestController
@RequestMapping("/api/v1/fx")
@RequiredArgsConstructor
public class FxHedgeLinkController {

    public record LinkBody(List<LinkRequest> links) {
    }

    private final FxHedgeLinkService hedges;

    @GetMapping("/deals/{id}/hedge-links")
    public ApiResponse<DealLinks> list(@PathVariable UUID id, @RequestParam UUID corporateId) {
        return ApiResponse.success(hedges.forDeal(corporateId, id));
    }

    /** All or nothing: one invoice that breaks a rule refuses the request, naming it. */
    @PostMapping("/deals/{id}/hedge-links")
    public ApiResponse<DealLinks> link(@PathVariable UUID id, @RequestParam UUID corporateId, @RequestBody LinkBody body) {
        return ApiResponse.success(hedges.link(corporateId, id, body.links()));
    }

    @DeleteMapping("/deals/{id}/hedge-links/{linkId}")
    public ApiResponse<DealLinks> unlink(@PathVariable UUID id, @PathVariable UUID linkId, @RequestParam UUID corporateId) {
        return ApiResponse.success(hedges.unlink(corporateId, id, linkId));
    }

    /** {@code invoiceIds} as a comma list or repeated; invoices without links are left out of the answer. */
    @GetMapping("/hedge-links")
    public ApiResponse<Map<UUID, List<LinkView>>> forInvoices(@RequestParam UUID corporateId,
                                                             @RequestParam FxHedgeLink.Kind kind,
                                                             @RequestParam List<UUID> invoiceIds) {
        return ApiResponse.success(hedges.forInvoices(corporateId, kind, invoiceIds));
    }
}
