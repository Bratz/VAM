package com.bank.vam.fx.deal;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.fx.deal.FxDealModels.Confirmation;
import com.bank.vam.fx.deal.FxDealModels.DealView;
import com.bank.vam.fx.deal.FxDealModels.EventView;
import com.bank.vam.fx.deal.FxDealModels.LimitView;
import com.bank.vam.fx.deal.FxDealModels.QuoteView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
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

/** Quote requests, deals, and the credit limit for the corporate FX workspace. */
@RestController
@RequestMapping("/api/v1/fx")
@RequiredArgsConstructor
public class FxDealController {

    /** {@code amount} is in the pair's base currency; {@code valueDate} is the last drawdown date for a window. */
    public record QuoteBody(@NotNull UUID corporateId, @NotNull String pair, @NotNull Side side,
                            @NotNull Instrument instrument, @NotNull BigDecimal amount, LocalDate valueDate,
                            LocalDate windowStart, String purpose, String exposureRef) {
    }

    private final FxQuoteService quotes;
    private final FxDealService deals;
    private final FxLimitService limits;

    @PostMapping("/quotes")
    public ApiResponse<QuoteView> request(@Valid @RequestBody QuoteBody b) {
        return ApiResponse.success(QuoteView.of(quotes.request(new FxQuoteService.Command(
                b.corporateId(), b.pair(), b.side(), b.instrument(), b.amount(), b.valueDate(), b.windowStart(),
                b.purpose(), b.exposureRef()))));
    }

    @GetMapping("/quotes/{id}")
    public ApiResponse<QuoteView> quote(@PathVariable UUID id) {
        return ApiResponse.success(QuoteView.of(quotes.get(id)));
    }

    /** Accepting a quote twice returns the same deal. */
    @PostMapping("/quotes/{id}/accept")
    public ApiResponse<DealView> accept(@PathVariable UUID id) {
        return ApiResponse.success(DealView.of(quotes.accept(id)));
    }

    @PostMapping("/quotes/{id}/cancel")
    public ApiResponse<QuoteView> cancel(@PathVariable UUID id) {
        return ApiResponse.success(QuoteView.of(quotes.cancel(id)));
    }

    @GetMapping("/deals")
    public ApiResponse<List<DealView>> deals(@RequestParam UUID corporateId) {
        return ApiResponse.success(deals.list(corporateId).stream().map(DealView::of).toList());
    }

    @GetMapping("/deals/{id}/events")
    public ApiResponse<List<EventView>> events(@PathVariable UUID id) {
        FxDeal deal = deals.get(id);
        return ApiResponse.success(java.util.stream.Stream.concat(
                deals.events(deal.getQuoteRequestId()).stream(), deals.events(id).stream())
                .sorted(java.util.Comparator.comparing(FxDealEvent::getCreatedAt))
                .map(EventView::of).toList());
    }

    @GetMapping("/deals/{id}/confirmation")
    public ApiResponse<Confirmation> confirmation(@PathVariable UUID id) {
        FxDeal deal = deals.get(id);
        return ApiResponse.success(new Confirmation(deal.getDealRef(), deals.confirmation(deal)));
    }

    @GetMapping("/limits")
    public ApiResponse<LimitView> limit(@RequestParam UUID corporateId) {
        return ApiResponse.success(LimitView.of(limits.position(corporateId)));
    }
}
