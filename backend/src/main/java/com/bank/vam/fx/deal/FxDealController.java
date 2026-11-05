package com.bank.vam.fx.deal;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.fx.deal.FxDealModels.Confirmation;
import com.bank.vam.fx.deal.FxDealModels.DealView;
import com.bank.vam.fx.deal.FxDealModels.EventView;
import com.bank.vam.fx.deal.FxDealModels.LimitView;
import com.bank.vam.fx.deal.FxDealModels.QuoteView;
import com.bank.vam.fx.deal.FxDealModels.RfqLog;
import com.bank.vam.fx.deal.FxDealModels.RfqRow;
import com.bank.vam.fx.deal.FxDealModels.RfqSummary;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final DealViewAssembler views;

    @PostMapping("/quotes")
    public ApiResponse<QuoteView> request(@Valid @RequestBody QuoteBody b) {
        return ApiResponse.success(QuoteView.of(quotes.request(new FxQuoteService.Command(
                b.corporateId(), b.pair(), b.side(), b.instrument(), b.amount(), b.valueDate(), b.windowStart(),
                b.purpose(), b.exposureRef()))));
    }

    /**
     * The RFQ log: every request a corporate made in the last {@code days}, newest first, with what became of it.
     * The summary covers all statuses for the pair filter; {@code status} and {@code limit} only trim the rows.
     */
    @GetMapping("/quotes")
    public ApiResponse<RfqLog> rfqLog(@RequestParam UUID corporateId,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String pair,
                                      @RequestParam(defaultValue = "30") int days,
                                      @RequestParam(defaultValue = "200") int limit) {
        QuoteStatus wanted = parseStatus(status);
        LocalDateTime now = LocalDateTime.now();
        List<FxQuoteRequest> found = quotes.search(corporateId, now.minusDays(Math.min(Math.max(days, 1), 365)), now.plusMinutes(1)).stream()
                .filter(q -> pair == null || pair.isBlank() || q.getPair().equals(pair)).toList();
        List<FxQuoteRequest> shown = found.stream().filter(q -> wanted == null || q.getStatus() == wanted)
                .limit(Math.min(Math.max(limit, 1), 500)).toList();
        Map<UUID, String> refs = deals.refs(shown.stream().map(FxQuoteRequest::getDealId).filter(Objects::nonNull).toList());
        return ApiResponse.success(new RfqLog(RfqSummary.of(found), shown.stream()
                .map(q -> new RfqRow(QuoteView.of(q), refs.get(q.getDealId()))).toList()));
    }

    /** The app has no handler for a bad enum parameter, which would surface as a 500; say what is wrong instead. */
    private static QuoteStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return QuoteStatus.valueOf(status.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new com.bank.vam.exception.BusinessException("FX_REQUEST_INVALID",
                    "Unknown status '" + status + "'; use one of " + java.util.Arrays.toString(QuoteStatus.values()));
        }
    }

    /** What happened to one request, including those that never became a deal. */
    @GetMapping("/quotes/{id}/events")
    public ApiResponse<List<EventView>> quoteEvents(@PathVariable UUID id) {
        quotes.get(id);
        return ApiResponse.success(deals.events(id).stream().map(EventView::of).toList());
    }

    @GetMapping("/quotes/{id}")
    public ApiResponse<QuoteView> quote(@PathVariable UUID id) {
        return ApiResponse.success(QuoteView.of(quotes.get(id)));
    }

    /** Accepting a quote twice returns the same deal. */
    @PostMapping("/quotes/{id}/accept")
    public ApiResponse<DealView> accept(@PathVariable UUID id) {
        return ApiResponse.success(views.view(quotes.accept(id)));
    }

    @PostMapping("/quotes/{id}/cancel")
    public ApiResponse<QuoteView> cancel(@PathVariable UUID id) {
        return ApiResponse.success(QuoteView.of(quotes.cancel(id)));
    }

    @GetMapping("/deals")
    public ApiResponse<List<DealView>> deals(@RequestParam UUID corporateId) {
        return ApiResponse.success(deals.list(corporateId).stream().map(views::view).toList());
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
