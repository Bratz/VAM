package com.bank.vam.ai.copilot.tools.read;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.dto.treasury.MultiBankLiquidityDto.LiquiditySummary;
import com.bank.vam.service.treasury.MultiBankLiquidityViewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Read tool: position by bank and currency across all banks (home + external),
 * with shadow-account staleness rollups. Thin wrapper — {@link
 * MultiBankLiquidityViewService} already does all the aggregation and already
 * accepts a nullable {@code corporateId}, scoping itself.
 *
 * <p>Output shape is the {@code LiquiditySummary} DTO tree flattened via
 * Jackson rather than hand-built {@code LinkedHashMap}s — it's three levels
 * deep (bank → currency → shadow), and {@code ObjectMapper.convertValue} on an
 * already-{@code @Data}-annotated DTO is simpler and less error-prone than
 * manually re-deriving ~15 field names across three nested classes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetMultiBankLiquidityTool implements CopilotTool {

    private final MultiBankLiquidityViewService liquidityViewService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "get_multibank_liquidity";
    }

    @Override
    public String description() {
        return "Position by bank and currency across all banks (home and external), with shadow-account staleness.";
    }

    @Override
    public Map<String, Map<String, Object>> parameterSchema() {
        return Map.of();
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(ToolContext context, Map<String, Object> params) {
        try {
            LiquiditySummary summary = liquidityViewService.getSummary(context.corporateId());
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.convertValue(summary, Map.class);

            String result = String.format("%d shadow account(s) across %d bank(s): %d home-bank-held, %d external, %d stale",
                    summary.getTotalShadows(),
                    summary.getBanks() != null ? summary.getBanks().size() : 0,
                    summary.getHomeBankShadows(), summary.getExternalShadows(), summary.getStaleCount());

            return ToolResult.ok(name(), result, data);
        } catch (Exception e) {
            log.warn("get_multibank_liquidity failed", e);
            return ToolResult.error(name(), "Failed to compute multi-bank liquidity: " + e.getMessage());
        }
    }
}
