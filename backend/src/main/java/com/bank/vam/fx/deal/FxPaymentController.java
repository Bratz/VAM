package com.bank.vam.fx.deal;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.VirtualAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** FX on the payment screens: the deals that fit a payment, in the order they are used, and what is left. */
@RestController
@RequestMapping("/api/v1/fx/payments")
@RequiredArgsConstructor
public class FxPaymentController {

    private final FxPaymentService payments;
    private final VirtualAccountRepository accounts;

    /** {@code use}: only these deals (the ones the payer kept ticked); all that fit when absent. */
    @GetMapping("/plan")
    public ApiResponse<FxPaymentService.Plan> plan(@RequestParam UUID accountId, @RequestParam String currency,
                                                   @RequestParam BigDecimal amount, @RequestParam(required = false) List<UUID> use) {
        VirtualAccount account = accounts.findById(accountId).orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountId));
        return ApiResponse.success(payments.plan(account, currency, amount, use));
    }
}
