package com.bank.vam.ai.copilot.tools.read;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.fx.ForwardPricer;
import com.bank.vam.fx.ForwardQuote;
import com.bank.vam.fx.RateProvider;
import com.bank.vam.fx.SpotTick;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Read tool: indicative interbank FX rates. With no arguments, the spot mid for every listed pair; with a
 * pair, its spot and forward curve; with a pair and a tenor or value date, one forward.
 *
 * <p>Market data, not corporate data, so it needs no corporate scope. The rates are simulated and mid only: the
 * bank's own price for a deal comes from a quote request, which this connection cannot make.
 *
 * <p>Output shape: {@code { source, note, spots: [{ pair, mid, changeBps, asOf, source }], curve: [...],
 * forward? }}. {@code curve} is empty unless a pair was given without a tenor or date; {@code forward} is present
 * only when a tenor or date was given. Nulls are omitted by the app's JSON settings.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetFxRatesTool implements CopilotTool {

    private static final Pattern PAIR = Pattern.compile("[A-Z]{3}/[A-Z]{3}");
    private static final String NOTE = "Simulated interbank mid rates for information. Not dealable prices: the bank's "
            + "price for a deal is a quote request, which is not available through this connection.";

    private final RateProvider rates;
    private final ForwardPricer forwards;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "get_fx_rates";
    }

    @Override
    public String description() {
        return "Indicative (simulated, mid-only) FX rates. No arguments: spot for every listed pair. With a pair "
                + "(e.g. EUR/USD): its spot and forward curve (1W to 12M, with forward points). With a pair and a "
                + "tenor or a value date: that one forward. Not a dealable price.";
    }

    @Override
    public Map<String, Map<String, Object>> parameterSchema() {
        Map<String, Map<String, Object>> schema = new LinkedHashMap<>();
        schema.put("pair", Map.of("type", "string", "description", "Currency pair such as EUR/USD or GBP/AED"));
        schema.put("tenor", Map.of("type", "string", "description", "Forward tenor: 1W, 1M, 2M, 3M, 6M, 9M or 12M (needs pair)"));
        schema.put("valueDate", Map.of("type", "string", "description", "Exact forward value date, YYYY-MM-DD (needs pair; not with tenor)"));
        return schema;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> params) {
        try {
            String pair = paramString(params, "pair", null);
            String tenor = paramString(params, "tenor", null);
            String valueDate = paramString(params, "valueDate", null);

            if (pair == null && (tenor != null || valueDate != null)) {
                return ToolResult.error(name(), "A tenor or value date needs a pair, for example EUR/USD");
            }
            if (tenor != null && valueDate != null) {
                return ToolResult.error(name(), "Give a tenor or a value date, not both");
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("source", "SIMULATED");
            data.put("note", NOTE);
            List<Map<String, Object>> curve = List.of();

            if (pair == null) {
                data.put("spots", rates.spots().stream().map(this::spot).toList());
                data.put("curve", curve);
                return ToolResult.ok(name(), "Indicative spot rates for " + rates.spots().size() + " pair(s)", data);
            }

            pair = pair.toUpperCase(Locale.ROOT);
            if (!PAIR.matcher(pair).matches()) {
                return ToolResult.error(name(), "Pair must look like EUR/USD: " + pair);
            }
            String wanted = pair;
            SpotTick spot = rates.spot(wanted).orElse(null);
            if (spot == null) {
                return ToolResult.error(name(), "No market for " + wanted);
            }
            data.put("spots", List.of(spot(spot)));

            if (tenor != null) {
                data.put("curve", curve);
                data.put("forward", forward(forwards.forward(wanted, tenor)));
                return ToolResult.ok(name(), wanted + " " + tenor.toUpperCase(Locale.ROOT) + " forward", data);
            }
            if (valueDate != null) {
                data.put("curve", curve);
                data.put("forward", forward(forwards.forward(wanted, LocalDate.parse(valueDate))));
                return ToolResult.ok(name(), wanted + " forward for " + valueDate, data);
            }
            data.put("curve", forwards.curve(wanted).stream().map(this::forward).toList());
            return ToolResult.ok(name(), wanted + " spot and forward curve", data);
        } catch (BusinessException e) {
            return ToolResult.error(name(), e.getMessage());
        } catch (DateTimeParseException e) {
            return ToolResult.error(name(), "valueDate must be YYYY-MM-DD");
        } catch (Exception e) {
            log.warn("get_fx_rates failed", e);
            return ToolResult.error(name(), "Failed to read FX rates: " + e.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> spot(SpotTick t) {
        return objectMapper.convertValue(t, Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> forward(ForwardQuote q) {
        return objectMapper.convertValue(q, Map.class);
    }
}
