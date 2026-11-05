package com.bank.vam.ai.copilot.tools.read;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.fx.deal.FxDealModels.RfqLog;
import com.bank.vam.fx.deal.FxQuoteService;
import com.bank.vam.fx.deal.QuoteStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Read tool: the corporate's log of requests for an FX price, including those that expired, were withdrawn or
 * were declined by the desk (and why) and so never became a deal. A summary (requests, priced, accepted,
 * expired, declined, withdrawn, hit rate, average seconds to accept) plus the requests newest first.
 *
 * <p>Needs a corporate scope. Read-only: this connection cannot request or accept a price. Shares {@link
 * FxQuoteService#rfqLog} with the REST endpoint, so the two always agree; listing turns a lapsed price into
 * EXPIRED, as reading it by id does.
 *
 * <p>Output shape: {@code { summary: {...}, quotes: [{ quote: {...}, dealRef? }] }}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetFxRfqLogTool implements CopilotTool {

    private static final int DEFAULT_DAYS = 30;
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final FxQuoteService quotes;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "get_fx_rfq_log";
    }

    @Override
    public String description() {
        return "The corporate's log of FX price requests (RFQs): each request's pair, side, amount, price and bank "
                + "margin, and its outcome (accepted / expired / declined by the desk with the reason / withdrawn / "
                + "still firm), plus a summary with the hit rate. Covers requests that never became a deal. "
                + "Read-only. status: one of " + Arrays.toString(QuoteStatus.values()) + ".";
    }

    @Override
    public Map<String, Map<String, Object>> parameterSchema() {
        Map<String, Map<String, Object>> schema = new LinkedHashMap<>();
        schema.put("status", Map.of("type", "string", "description", "Only this outcome, e.g. REJECTED or EXPIRED"));
        schema.put("pair", Map.of("type", "string", "description", "Only this pair, e.g. EUR/USD"));
        schema.put("days", Map.of("type", "integer", "description", "Look back this many days (default 30, max 365)"));
        schema.put("limit", Map.of("type", "integer", "description", "Most requests to return, newest first (default 50, max 200)"));
        return schema;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> params) {
        try {
            if (!context.hasCorporateScope()) {
                return ToolResult.error(name(), "get_fx_rfq_log requires a corporate scope");
            }
            QuoteStatus status = FxQuoteService.parseStatus(paramString(params, "status", null));
            String pair = paramString(params, "pair", null);
            int days = paramInt(params, "days", DEFAULT_DAYS);
            int limit = Math.min(Math.max(paramInt(params, "limit", DEFAULT_LIMIT), 1), MAX_LIMIT);

            RfqLog log = quotes.rfqLog(context.corporateId(), status, pair == null ? null : pair.toUpperCase(Locale.ROOT), days, limit);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.convertValue(log, Map.class);
            return ToolResult.ok(name(), log.summary().requests() + " FX price request(s) in the last "
                    + Math.min(Math.max(days, 1), 365) + " day(s), " + log.summary().accepted() + " accepted", data);
        } catch (BusinessException e) {
            return ToolResult.error(name(), e.getMessage());
        } catch (Exception e) {
            this.log.warn("get_fx_rfq_log failed", e);
            return ToolResult.error(name(), "Failed to read the RFQ log: " + e.toString());
        }
    }
}
