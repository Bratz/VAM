package com.bank.vam.controller;

import com.bank.vam.dto.PaymentRoutingDto.OptionsRequest;
import com.bank.vam.dto.PaymentRoutingDto.OptionsResponse;
import com.bank.vam.dto.PaymentRoutingDto.SelectRequest;
import com.bank.vam.dto.PaymentRoutingDto.SelectResponse;
import com.bank.vam.service.PaymentRoutingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The routing step, between capturing a payment and submitting it.
 *
 * <p>Both are POSTs although neither is a resource creation in the usual sense: the intent carries a
 * dozen fields including a timestamp, which is past the point where query strings help anyone.
 * {@code /quote} writes nothing of its own (it records an audit entry and no more), {@code /select}
 * records the chosen route.
 */
@RestController
@RequestMapping("/api/v1/payments/routing")
@RequiredArgsConstructor
public class PaymentRoutingController {

    private final PaymentRoutingService routing;

    /** Up to three options for this payment, or a reason there are none. */
    @PostMapping("/quote")
    public ResponseEntity<OptionsResponse> quote(@RequestBody OptionsRequest request) {
        return ResponseEntity.ok(routing.options(request));
    }

    /**
     * Commits to one route, after re-asking.
     *
     * <p>200 either way. A refusal is not a client error -- the request was valid and the world
     * moved -- and it carries the current options, so the screen has something to offer instead of
     * an error to report.
     */
    @PostMapping("/select")
    public ResponseEntity<SelectResponse> select(@RequestBody SelectRequest request) {
        return ResponseEntity.ok(routing.select(request));
    }
}
