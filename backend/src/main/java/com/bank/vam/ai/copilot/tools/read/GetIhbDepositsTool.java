package com.bank.vam.ai.copilot.tools.read;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.entity.treasury.IhbDeposit;
import com.bank.vam.entity.treasury.IhbDeposit.DepositStatus;
import com.bank.vam.repository.treasury.IhbDepositRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read tool: in-house-bank deposits for the corporate, optionally filtered by
 * status or maturity window. Exact mirror of {@link GetIhbLoansTool} against
 * the deposit side.
 *
 * <p>Output shape: {@code { totalMatched, totalActive, deposits: [{ id,
 * depositReference, depositorEntityCode, principalAmount, currentBalance,
 * currencyCode, interestRate, depositDate, maturityDate, status }] } }
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetIhbDepositsTool implements CopilotTool {

    static final int DEFAULT_LIMIT = 25;
    static final int MAX_LIMIT = 200;

    private final IhbDepositRepository ihbDepositRepository;

    @Override
    public String name() {
        return "get_ihb_deposits";
    }

    @Override
    public String description() {
        return "In-house-bank deposits for the corporate, optionally filtered by status or maturity window.";
    }

    @Override
    public Map<String, Map<String, Object>> parameterSchema() {
        return Map.of(
                "status", Map.of("type", "string", "description", "ACTIVE, MATURED, WITHDRAWN"),
                "maturingWithinDays", Map.of("type", "integer", "description", "Only deposits maturing within N days"),
                "limit", Map.of("type", "integer", "description", "Max rows to return (default 25, cap 200)")
        );
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(ToolContext context, Map<String, Object> params) {
        try {
            String statusParam = paramString(params, "status", null);
            Integer maturingWithinDays = params != null && params.get("maturingWithinDays") != null
                    ? paramInt(params, "maturingWithinDays", 0) : null;
            int limit = Math.min(Math.max(1, paramInt(params, "limit", DEFAULT_LIMIT)), MAX_LIMIT);

            List<IhbDeposit> deposits;
            if (maturingWithinDays != null && context.hasCorporateScope()) {
                deposits = ihbDepositRepository.findDepositsMaturing(context.corporateId(),
                        LocalDate.now().plusDays(maturingWithinDays));
            } else if (statusParam != null && context.hasCorporateScope()) {
                deposits = ihbDepositRepository.findByCorporateIdAndStatus(context.corporateId(), parseStatus(statusParam));
            } else if (context.hasCorporateScope()) {
                deposits = ihbDepositRepository.findByCorporateId(context.corporateId());
            } else if (statusParam != null) {
                deposits = ihbDepositRepository.findByStatus(parseStatus(statusParam));
            } else {
                deposits = ihbDepositRepository.findAll();
            }

            int totalMatched = deposits.size();
            List<Map<String, Object>> rows = deposits.stream().limit(limit).map(this::toRow).collect(Collectors.toList());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalMatched", totalMatched);
            if (context.hasCorporateScope()) {
                data.put("totalActive", ihbDepositRepository.sumActiveDepositsByCorporate(context.corporateId()));
            }
            data.put("deposits", rows);

            String summary = String.format("%d IHB deposit(s) found", totalMatched);
            return ToolResult.ok(name(), summary, data);
        } catch (Exception e) {
            log.warn("get_ihb_deposits failed", e);
            return ToolResult.error(name(), "Failed to list IHB deposits: " + e.getMessage());
        }
    }

    private Map<String, Object> toRow(IhbDeposit deposit) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", deposit.getId() != null ? deposit.getId().toString() : null);
        row.put("depositReference", deposit.getDepositReference());
        row.put("depositorEntityCode", deposit.getDepositorEntityCode());
        row.put("principalAmount", deposit.getPrincipalAmount());
        row.put("currentBalance", deposit.getCurrentBalance());
        row.put("currencyCode", deposit.getCurrencyCode());
        row.put("interestRate", deposit.getInterestRate());
        row.put("depositDate", deposit.getDepositDate());
        row.put("maturityDate", deposit.getMaturityDate());
        row.put("status", deposit.getStatus() != null ? deposit.getStatus().name() : null);
        return row;
    }

    private DepositStatus parseStatus(String s) {
        try {
            return DepositStatus.valueOf(s.trim().toUpperCase());
        } catch (Exception e) {
            return DepositStatus.ACTIVE;
        }
    }
}
