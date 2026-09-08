package com.bank.vam.ai.copilot.tools.read;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.forecast.api.ForecastSummaryAssembler;
import com.bank.vam.forecast.api.dto.ForecastSummaryDto;
import com.bank.vam.forecast.domain.ForecastRun;
import com.bank.vam.forecast.domain.enums.RunStatus;
import com.bank.vam.forecast.repository.ForecastLineRepository;
import com.bank.vam.forecast.repository.ForecastRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read tool: latest completed forecast run for the corporate, aggregated into
 * weekly buckets. Reuses {@link ForecastSummaryAssembler} — the exact same
 * week-bucketing logic {@code GET /api/v1/forecasts/latest} uses — instead of
 * duplicating it.
 *
 * <p>Unlike most tools, an unscoped caller gets {@code ToolResult.error}: a
 * "latest run across all corporates" has no meaningful interpretation and no
 * repository method exists for it (nor should one — forecasts are inherently
 * per-corporate).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetCashForecastTool implements CopilotTool {

    private static final int DEFAULT_HORIZON_DAYS = 91;

    private final ForecastRunRepository forecastRunRepository;
    private final ForecastLineRepository forecastLineRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "get_cash_forecast";
    }

    @Override
    public String description() {
        return "Latest completed forecast run for the corporate, aggregated into weekly buckets.";
    }

    @Override
    public Map<String, Map<String, Object>> parameterSchema() {
        return Map.of(
                "currency", Map.of("type", "string", "description", "ISO-4217 filter; omit to sum all currencies"),
                "horizonDays", Map.of("type", "integer", "description", "Forecast horizon in days from today (default 91)")
        );
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(ToolContext context, Map<String, Object> params) {
        try {
            if (!context.hasCorporateScope()) {
                return ToolResult.error(name(), "get_cash_forecast requires a corporate scope");
            }

            String currency = paramString(params, "currency", null);
            int horizonDays = paramInt(params, "horizonDays", DEFAULT_HORIZON_DAYS);

            Optional<ForecastRun> run = forecastRunRepository
                    .findFirstByCorporateIdAndStatusOrderByRunAtDesc(context.corporateId(), RunStatus.COMPLETED);
            if (run.isEmpty()) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("hasForecast", false);
                return ToolResult.ok(name(),
                        "No completed forecast run for this corporate — run one via POST /forecasts/run first", data);
            }

            LocalDate from = LocalDate.now();
            LocalDate to = from.plusDays(horizonDays);
            List<Object[]> rows = forecastLineRepository.aggregateWeeklyByCurrencyDirection(run.get().getId(), from, to);
            ForecastSummaryDto summary = ForecastSummaryAssembler.assemble(run.get(), rows, currency);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.convertValue(summary, Map.class);
            data.put("hasForecast", true);

            String result = String.format("Forecast run %s: closing balance %s %s over %d day(s)",
                    summary.runId(), summary.closingBalance(), summary.currency() != null ? summary.currency() : "", horizonDays);
            return ToolResult.ok(name(), result, data);
        } catch (Exception e) {
            log.warn("get_cash_forecast failed", e);
            return ToolResult.error(name(), "Failed to fetch cash forecast: " + e.getMessage());
        }
    }
}
