package com.bank.vam.fx.deal;

import com.bank.vam.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Market Rates for the corporate: its two-way prices, spot and forward, at an amount (base currency, 100,000 when not given). */
@RestController
@RequestMapping("/api/v1/fx/board")
@RequiredArgsConstructor
public class FxBoardController {

    private final FxBoardService board;

    @GetMapping
    public ApiResponse<List<FxBoardService.BoardRow>> board(@RequestParam(required = false) BigDecimal amount) {
        return ApiResponse.success(board.board(amount));
    }

    @GetMapping("/curve")
    public ApiResponse<List<FxBoardService.CurveRow>> curve(@RequestParam String pair, @RequestParam(required = false) BigDecimal amount) {
        return ApiResponse.success(board.curve(pair, amount));
    }

    @GetMapping("/forward")
    public ApiResponse<FxBoardService.CurveRow> forward(@RequestParam String pair, @RequestParam(required = false) BigDecimal amount,
                                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate valueDate) {
        return ApiResponse.success(board.forward(pair, amount, valueDate));
    }
}
