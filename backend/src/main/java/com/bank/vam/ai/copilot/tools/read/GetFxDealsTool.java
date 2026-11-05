package com.bank.vam.ai.copilot.tools.read;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.fx.deal.DealStatus;
import com.bank.vam.fx.deal.DealViewAssembler;
import com.bank.vam.fx.deal.FxDeal;
import com.bank.vam.fx.deal.FxDealAttentionService;
import com.bank.vam.fx.deal.FxDealService;
import com.bank.vam.fx.deal.FxLimitService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read tool: the corporate's FX deals with the bank, its FX credit limit, and what needs attention (unconfirmed
 * or disputed confirmations, failed settlements, settlements the corporate cannot fund). For each deal: the
 * legs, rate, value date, status, whether the corporate has confirmed it, what is undrawn, and its mark against
 * today's market.
 *
 * <p>Needs a corporate scope. Read-only: nothing here confirms, draws down, closes or settles a deal.
 *
 * <p>Output shape: {@code { total, shown, limit: { currency, limit, used, headroom }, attention: [...],
 * deals: [...] }}. {@code status} is {@code open} (default), {@code all}, or one deal status.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetFxDealsTool implements CopilotTool {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final FxDealService deals;
    private final DealViewAssembler views;
    private final FxLimitService limits;
    private final FxDealAttentionService attention;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "get_fx_deals";
    }

    @Override
    public String description() {
        return "The corporate's FX deals with the bank (spot, forwards, window forwards): legs, rate, value date, "
                + "status, whether the corporate has confirmed the bank's confirmation, undrawn amount and mark-to-market; "
                + "plus its FX credit limit (used and headroom) and what needs attention. Read-only. status: open "
                + "(default), all, or one of " + Arrays.toString(DealStatus.values()) + ".";
    }

    @Override
    public Map<String, Map<String, Object>> parameterSchema() {
        Map<String, Map<String, Object>> schema = new LinkedHashMap<>();
        schema.put("status", Map.of("type", "string", "description", "open (default), all, or a deal status such as ACTIVE or SETTLED"));
        schema.put("limit", Map.of("type", "integer", "description", "Most deals to return, newest first (default 50, max 200)"));
        return schema;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> params) {
        try {
            if (!context.hasCorporateScope()) {
                return ToolResult.error(name(), "get_fx_deals requires a corporate scope");
            }
            String status = paramString(params, "status", "open").toLowerCase(Locale.ROOT);
            int limit = Math.min(Math.max(paramInt(params, "limit", DEFAULT_LIMIT), 1), MAX_LIMIT);

            List<FxDeal> matching = deals.list(context.corporateId()).stream()
                    .filter(d -> switch (status) {
                        case "open" -> DealStatus.OPEN.contains(d.getStatus());
                        case "all" -> true;
                        default -> d.getStatus() == parseStatus(status);
                    }).toList();
            List<FxDeal> shown = matching.stream().limit(limit).toList();

            FxLimitService.Position position = limits.position(context.corporateId());
            Map<String, Object> limit1 = new LinkedHashMap<>();
            limit1.put("currency", position.currency());
            limit1.put("limit", position.limit());
            limit1.put("used", position.used());
            limit1.put("headroom", position.headroom());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("total", matching.size());
            data.put("shown", shown.size());
            data.put("limit", limit1);
            data.put("attention", attention.items(context.corporateId()).stream().map(this::convert).toList());
            data.put("deals", shown.stream().map(views::view).map(this::convert).toList());

            return ToolResult.ok(name(), matching.size() + " FX deal(s) (" + status + "), limit used "
                    + position.used() + " of " + position.limit() + " " + position.currency(), data);
        } catch (BusinessException e) {
            return ToolResult.error(name(), e.getMessage());
        } catch (Exception e) {
            log.warn("get_fx_deals failed", e);
            return ToolResult.error(name(), "Failed to read FX deals: " + e.toString());
        }
    }

    private static DealStatus parseStatus(String status) {
        try {
            return DealStatus.valueOf(status.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("FX_REQUEST_INVALID",
                    "Unknown status '" + status + "'; use open, all, or one of " + Arrays.toString(DealStatus.values()));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> convert(Object value) {
        return objectMapper.convertValue(value, Map.class);
    }
}
