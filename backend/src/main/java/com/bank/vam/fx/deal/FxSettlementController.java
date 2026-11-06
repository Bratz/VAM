package com.bank.vam.fx.deal;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.fx.deal.FxDealModels.DealView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Standing settlement instructions, and the accounts one deal settles through. */
@RestController
@RequestMapping("/api/v1/fx")
@RequiredArgsConstructor
public class FxSettlementController {

    public record InstructionBody(@NotNull UUID corporateId, @NotNull UUID entityId, @NotNull String currency, @NotNull UUID accountId) {
    }

    public record DealAccountsBody(UUID buyAccountId, UUID sellAccountId) {
    }

    /** What one currency settles through for an entity, and the accounts it could use instead. */
    public record AccountChoice(FxSettlementAccounts.Resolved resolved, List<FxSettlementAccounts.Account> eligible) {
    }

    private final FxSettlementAccounts accounts;
    private final FxDealService deals;
    private final DealViewAssembler views;

    /** Every entity of the corporate, per currency it holds a home-bank account in: its instruction or default. */
    @GetMapping("/settlement/instructions")
    public ApiResponse<List<FxSettlementAccounts.Row>> instructions(@RequestParam UUID corporateId) {
        return ApiResponse.success(accounts.rows(corporateId));
    }

    @PutMapping("/settlement/instructions")
    public ApiResponse<FxSettlementAccounts.Row> setInstruction(@Valid @RequestBody InstructionBody b) {
        return ApiResponse.success(accounts.setInstruction(b.corporateId(), b.entityId(), b.currency(), b.accountId()));
    }

    /** The account an entity's deals would settle through in a currency, or why none, with the eligible accounts. */
    @GetMapping("/settlement/accounts")
    public ApiResponse<AccountChoice> account(@RequestParam UUID corporateId, @RequestParam UUID entityId, @RequestParam String currency) {
        String ccy = currency.toUpperCase(Locale.ROOT);
        return ApiResponse.success(new AccountChoice(accounts.resolve(corporateId, entityId, ccy), accounts.eligible(entityId, ccy)));
    }

    /** Changes the accounts a deal settles through, until the day before value date. */
    @PutMapping("/deals/{id}/settlement-accounts")
    public ApiResponse<DealView> changeDealAccounts(@PathVariable UUID id, @RequestBody DealAccountsBody b) {
        return ApiResponse.success(views.view(deals.changeAccounts(id, b.buyAccountId(), b.sellAccountId())));
    }
}
