package com.bank.vam.fx;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

/** Indicative interbank market data for the corporate FX workspace. Mid rates only, simulated. */
@RestController
@RequestMapping("/api/v1/fx/market")
@RequiredArgsConstructor
public class FxMarketController {

    private static final Pattern PAIR = Pattern.compile("[A-Z]{3}/[A-Z]{3}");

    private final RateProvider rates;
    private final ForwardPricer forwards;

    @GetMapping("/spots")
    public ApiResponse<List<SpotTick>> spots() {
        return ApiResponse.success(rates.spots());
    }

    /** Give either {@code tenor} (1W..12M) or an exact {@code valueDate}. */
    @GetMapping("/forward")
    public ApiResponse<ForwardQuote> forward(@RequestParam String pair,
                                             @RequestParam(required = false) String tenor,
                                             @RequestParam(required = false)
                                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate valueDate) {
        requirePair(pair);
        if ((tenor == null) == (valueDate == null)) {
            throw new BusinessException("FX_VALUE_DATE_INVALID", "Give exactly one of tenor or valueDate");
        }
        return ApiResponse.success(tenor != null ? forwards.forward(pair, tenor) : forwards.forward(pair, valueDate));
    }

    @GetMapping("/curve")
    public ApiResponse<List<ForwardQuote>> curve(@RequestParam String pair) {
        requirePair(pair);
        return ApiResponse.success(forwards.curve(pair));
    }

    private static void requirePair(String pair) {
        if (!PAIR.matcher(pair).matches()) {
            throw new BusinessException("FX_PAIR_UNSUPPORTED", "Pair must look like EUR/USD: " + pair);
        }
    }
}
