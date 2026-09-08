package com.bank.vam.ai.copilot.tools.read;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.entity.treasury.IhbLoan;
import com.bank.vam.entity.treasury.IhbLoan.LoanStatus;
import com.bank.vam.repository.treasury.IhbLoanRepository;
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
 * Read tool: in-house-bank loans for the corporate, optionally filtered by
 * status or maturity window.
 *
 * <p>Output shape: {@code { totalMatched, totalOutstanding, loans: [{ id,
 * loanReference, lenderEntityCode, borrowerEntityCode, principalAmount,
 * outstandingAmount, currencyCode, interestRate, disbursementDate,
 * maturityDate, status }] } } — {@code totalOutstanding} only present when
 * scoped (summing across corporates has no meaningful interpretation).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetIhbLoansTool implements CopilotTool {

    static final int DEFAULT_LIMIT = 25;
    static final int MAX_LIMIT = 200;

    private final IhbLoanRepository ihbLoanRepository;

    @Override
    public String name() {
        return "get_ihb_loans";
    }

    @Override
    public String description() {
        return "In-house-bank loans for the corporate, optionally filtered by status or maturity window.";
    }

    @Override
    public Map<String, Map<String, Object>> parameterSchema() {
        return Map.of(
                "status", Map.of("type", "string", "description", "ACTIVE, MATURED, DEFAULTED, WRITTEN_OFF"),
                "maturingWithinDays", Map.of("type", "integer", "description", "Only loans maturing within N days"),
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

            List<IhbLoan> loans;
            if (maturingWithinDays != null && context.hasCorporateScope()) {
                loans = ihbLoanRepository.findLoansMaturing(context.corporateId(),
                        LocalDate.now().plusDays(maturingWithinDays));
            } else if (statusParam != null && context.hasCorporateScope()) {
                loans = ihbLoanRepository.findByCorporateIdAndStatus(context.corporateId(), parseStatus(statusParam));
            } else if (context.hasCorporateScope()) {
                loans = ihbLoanRepository.findByCorporateId(context.corporateId());
            } else if (statusParam != null) {
                loans = ihbLoanRepository.findByStatus(parseStatus(statusParam));
            } else {
                loans = ihbLoanRepository.findAll();
            }

            int totalMatched = loans.size();
            List<Map<String, Object>> rows = loans.stream().limit(limit).map(this::toRow).collect(Collectors.toList());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalMatched", totalMatched);
            if (context.hasCorporateScope()) {
                data.put("totalOutstanding", ihbLoanRepository.sumOutstandingLoansByCorporate(context.corporateId()));
            }
            data.put("loans", rows);

            String summary = String.format("%d IHB loan(s) found", totalMatched);
            return ToolResult.ok(name(), summary, data);
        } catch (Exception e) {
            log.warn("get_ihb_loans failed", e);
            return ToolResult.error(name(), "Failed to list IHB loans: " + e.getMessage());
        }
    }

    private Map<String, Object> toRow(IhbLoan loan) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", loan.getId() != null ? loan.getId().toString() : null);
        row.put("loanReference", loan.getLoanReference());
        row.put("lenderEntityCode", loan.getLenderEntityCode());
        row.put("borrowerEntityCode", loan.getBorrowerEntityCode());
        row.put("principalAmount", loan.getPrincipalAmount());
        row.put("outstandingAmount", loan.getOutstandingAmount());
        row.put("currencyCode", loan.getCurrencyCode());
        row.put("interestRate", loan.getInterestRate());
        row.put("disbursementDate", loan.getDisbursementDate());
        row.put("maturityDate", loan.getMaturityDate());
        row.put("status", loan.getStatus() != null ? loan.getStatus().name() : null);
        return row;
    }

    private LoanStatus parseStatus(String s) {
        try {
            return LoanStatus.valueOf(s.trim().toUpperCase());
        } catch (Exception e) {
            return LoanStatus.ACTIVE;
        }
    }
}
