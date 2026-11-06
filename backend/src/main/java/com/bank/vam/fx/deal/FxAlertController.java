package com.bank.vam.fx.deal;

import com.bank.vam.dto.ApiResponse;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Rate alerts on the indicative market rate; they arrive in the notification bell. */
@RestController
@RequestMapping("/api/v1/fx/alerts")
@RequiredArgsConstructor
public class FxAlertController {

    public record AlertBody(@NotNull UUID corporateId, @NotNull String pair, @NotNull BigDecimal targetRate, String note) {
    }

    public record AlertView(UUID id, String pair, BigDecimal targetRate, String direction, BigDecimal rateAtCreation,
                            String status, String note, LocalDateTime createdAt, LocalDateTime triggeredAt, BigDecimal triggeredRate) {
        static AlertView of(FxRateAlert a) {
            return new AlertView(a.getId(), a.getPair(), a.getTargetRate(), a.getDirection().name(), a.getRateAtCreation(),
                    a.getStatus().name(), a.getNote(), a.getCreatedAt(), a.getTriggeredAt(), a.getTriggeredRate());
        }
    }

    private final FxNotifier notifier;

    @GetMapping
    public ApiResponse<List<AlertView>> list(@RequestParam UUID corporateId) {
        return ApiResponse.success(notifier.list(corporateId).stream().map(AlertView::of).toList());
    }

    @PostMapping
    public ApiResponse<AlertView> create(@Valid @RequestBody AlertBody b) {
        return ApiResponse.success(AlertView.of(notifier.create(b.corporateId(), b.pair(), b.targetRate(), b.note())));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<AlertView> cancel(@PathVariable UUID id) {
        return ApiResponse.success(AlertView.of(notifier.cancel(id)));
    }
}
