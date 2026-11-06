package com.bank.vam.fx.deal;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.fx.deal.FxDealModels.DealView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Streaming click-to-deal prices: a two-sided price for a small, short trade, and dealing on it. */
@RestController
@RequestMapping("/api/v1/fx/stream")
@RequiredArgsConstructor
public class FxStreamController {

    public record DealBody(@NotNull UUID priceId, @NotNull Side side) {
    }

    private final FxStreamService stream;
    private final DealViewAssembler views;

    /** Both sides for this trade, or why it goes to a request for quote. Ask again every second or two. */
    @GetMapping
    public ApiResponse<FxStreamService.StreamPrice> price(@RequestParam UUID corporateId, @RequestParam UUID entityId,
                                                          @RequestParam String pair, @RequestParam BigDecimal amount,
                                                          @RequestParam(defaultValue = "SPOT") Instrument instrument,
                                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate valueDate,
                                                          @RequestParam(required = false) String purpose,
                                                          @RequestParam(required = false) String exposureRef,
                                                          @RequestParam(defaultValue = "false") boolean forPayment) {
        return ApiResponse.success(stream.price(new FxQuoteService.Command(corporateId, entityId, pair, Side.BUY, instrument,
                amount, valueDate, null, purpose, exposureRef, forPayment)));
    }

    /** Books the deal at the side of the price the client clicked. */
    @PostMapping("/deal")
    public ApiResponse<DealView> deal(@Valid @RequestBody DealBody b) {
        return ApiResponse.success(views.view(stream.deal(b.priceId(), b.side())));
    }
}
