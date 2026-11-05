package com.bank.vam.ai.copilot.tools.read;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.fx.exposure.FxExposureModels.Breach;
import com.bank.vam.fx.exposure.FxExposureModels.Bucket;
import com.bank.vam.fx.exposure.FxExposureModels.CurrencyRow;
import com.bank.vam.fx.exposure.FxExposureModels.HedgeStatus;
import com.bank.vam.fx.exposure.FxExposureModels.Ladder;
import com.bank.vam.fx.exposure.FxExposureService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read tool: the corporate's foreign-currency exposure against its hedge policy. Per currency and time bucket:
 * receivables, payables, cash held, declared exposures, the net exposure, what is hedged, the hedge ratio
 * against the policy band, the status, and the hedge that would bring it back inside the band.
 *
 * <p>Needs a corporate scope: an exposure ladder is per corporate and has no meaning across all of them. Amounts
 * stay native to each currency and are never summed across currencies.
 *
 * <p>Output shape: {@code { corporateName, baseCurrency, asOf, breachCount, breaches: [...], currencies:
 * [{ currency, rateToBase?, buckets: [...] }] }}. With {@code breachesOnly}, each currency keeps only its
 * buckets that are outside policy. Nulls are omitted by the app's JSON settings.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetFxExposureTool implements CopilotTool {

    private final FxExposureService exposure;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "get_fx_exposure";
    }

    @Override
    public String description() {
        return "The corporate's foreign-currency exposure by currency and time bucket against its hedge policy: net "
                + "exposure, amount hedged, hedge ratio vs the policy band, status (within policy / under-hedged / "
                + "over-hedged), and the hedge needed to reach the band. Native currency amounts, never summed across "
                + "currencies. Set breachesOnly to see just what is outside policy.";
    }

    @Override
    public Map<String, Map<String, Object>> parameterSchema() {
        Map<String, Map<String, Object>> schema = new LinkedHashMap<>();
        schema.put("currency", Map.of("type", "string", "description", "Only this currency, e.g. GBP"));
        schema.put("breachesOnly", Map.of("type", "boolean", "description", "Only buckets outside the hedge policy"));
        return schema;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> params) {
        try {
            if (!context.hasCorporateScope()) {
                return ToolResult.error(name(), "get_fx_exposure requires a corporate scope");
            }
            String currency = paramString(params, "currency", null);
            boolean breachesOnly = paramBoolean(params, "breachesOnly", false);

            Ladder ladder = exposure.ladder(context.corporateId());
            List<Breach> breaches = exposure.breaches(context.corporateId()).stream()
                    .filter(b -> currency == null || b.currency().equalsIgnoreCase(currency)).toList();

            List<Map<String, Object>> currencies = ladder.currencies().stream()
                    .filter(r -> currency == null || r.currency().equalsIgnoreCase(currency))
                    .map(r -> row(r, breachesOnly))
                    .filter(r -> !breachesOnly || !((List<?>) r.get("buckets")).isEmpty())
                    .toList();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("corporateName", ladder.corporateName());
            data.put("baseCurrency", ladder.baseCurrency());
            data.put("asOf", ladder.asOf().toString());
            data.put("breachCount", breaches.size());
            data.put("breaches", breaches.stream().map(b -> convert(b)).toList());
            data.put("currencies", currencies);

            String summary = ladder.corporateName() + ": " + breaches.size() + " bucket(s) outside hedge policy across "
                    + ladder.currencies().size() + " foreign currenc" + (ladder.currencies().size() == 1 ? "y" : "ies");
            return ToolResult.ok(name(), summary, data);
        } catch (BusinessException e) {
            return ToolResult.error(name(), e.getMessage());
        } catch (Exception e) {
            log.warn("get_fx_exposure failed", e);
            return ToolResult.error(name(), "Failed to read FX exposure: " + e.toString());
        }
    }

    private Map<String, Object> row(CurrencyRow row, boolean breachesOnly) {
        List<Bucket> buckets = row.buckets().stream()
                // Empty buckets say nothing; with breachesOnly keep just those outside the band.
                .filter(b -> b.exposure().signum() != 0 || b.hedged().signum() != 0)
                .filter(b -> !breachesOnly || b.status() == HedgeStatus.UNDER_HEDGED || b.status() == HedgeStatus.OVER_HEDGED)
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currency", row.currency().toUpperCase(Locale.ROOT));
        if (row.rateToBase() != null) {
            out.put("rateToBase", row.rateToBase());
        }
        out.put("buckets", buckets.stream().map(b -> convert(b)).toList());
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> convert(Object value) {
        return objectMapper.convertValue(value, Map.class);
    }
}
