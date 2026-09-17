package com.bank.vam.controller.receivables;

import com.bank.vam.dto.receivables.ReceivablesDto.PublicInvoiceResponse;
import com.bank.vam.service.receivables.ReceivablesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unauthenticated, read-only endpoints for the "pay this invoice" link a debtor receives
 * externally (email/SMS) -- everything under /api/v1/public/** is meant to stay reachable
 * without login even once real authentication is added to the rest of the app (see
 * SecurityConfig's own "enable authentication in production" note).
 *
 * Deliberately separate from ReceivablesController, which is internal/treasury-facing.
 */
@RestController
@RequestMapping("/api/v1/public/receivables")
@RequiredArgsConstructor
@Tag(name = "Public Receivables", description = "Unauthenticated endpoints for shared payment links")
public class PublicReceivablesController {

    private final ReceivablesService receivablesService;

    @GetMapping("/pay/{token}")
    @Operation(summary = "Get invoice details for a payment link",
            description = "Returns only payer-facing fields (amount, VIBAN, due date, description) -- no customer or internal IDs.")
    public ResponseEntity<PublicInvoiceResponse> getByPaymentLinkToken(@PathVariable String token) {
        return receivablesService.getPublicInvoiceByToken(token)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
