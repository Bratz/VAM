package com.bank.vam.ai.copilot.tools.read;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.entity.Transaction;
import com.bank.vam.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read tool: single VA transaction lookup by id — distinct from {@code
 * get_statement_lines}, which only aggregates (counts + totals) over a
 * window. {@code Transaction.corporateId} is direct — no join needed, the
 * simplest scoping of the new tools.
 *
 * <p>Output shape: {@code { totalMatched, transaction: { id, movementType,
 * amount, currencyCode, balanceBefore, balanceAfter, transactionDate,
 * valueDate, referenceNumber, description, transactionCategory, status,
 * remitterName, remitterAccount, beneficiaryName, beneficiaryAccount, viban } } }
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetTransactionDetailTool implements CopilotTool {

    private final TransactionRepository transactionRepository;

    @Override
    public String name() {
        return "get_transaction_detail";
    }

    @Override
    public String description() {
        return "Single VA transaction lookup by id.";
    }

    @Override
    public Map<String, Map<String, Object>> parameterSchema() {
        return Map.of(
                "id", Map.of("type", "string", "description", "Transaction UUID", "required", true)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(ToolContext context, Map<String, Object> params) {
        try {
            String idParam = paramString(params, "id", null);
            if (idParam == null || idParam.isBlank()) {
                return ToolResult.error(name(), "Missing required parameter: id");
            }

            UUID id;
            try {
                id = UUID.fromString(idParam);
            } catch (IllegalArgumentException e) {
                return ToolResult.error(name(), "Invalid id: not a UUID");
            }

            Optional<Transaction> found = transactionRepository.findById(id);

            // Not-found on a corporate-scope mismatch, not 403 — same convention as
            // GetSweepInstructionsTool.lookupById, so a scoped caller can't probe ids.
            if (found.isPresent() && context.hasCorporateScope()
                    && !context.corporateId().equals(found.get().getCorporateId())) {
                found = Optional.empty();
            }

            if (found.isEmpty()) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("totalMatched", 0);
                return ToolResult.ok(name(), "No transaction matched id '" + idParam + "'", data);
            }

            Transaction txn = found.get();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalMatched", 1);
            data.put("transaction", toRow(txn));
            return ToolResult.ok(name(), "Transaction " + (txn.getReferenceNumber() != null ? txn.getReferenceNumber() : txn.getId()), data);
        } catch (Exception e) {
            log.warn("get_transaction_detail failed", e);
            return ToolResult.error(name(), "Failed to fetch transaction detail: " + e.getMessage());
        }
    }

    private Map<String, Object> toRow(Transaction txn) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", txn.getId() != null ? txn.getId().toString() : null);
        row.put("movementType", txn.getMovementType() != null ? txn.getMovementType().name() : null);
        row.put("amount", txn.getAmount());
        row.put("currencyCode", txn.getCurrencyCode());
        row.put("balanceBefore", txn.getBalanceBefore());
        row.put("balanceAfter", txn.getBalanceAfter());
        row.put("transactionDate", txn.getTransactionDate());
        row.put("valueDate", txn.getValueDate());
        row.put("referenceNumber", txn.getReferenceNumber());
        row.put("description", txn.getDescription());
        row.put("transactionCategory", txn.getTransactionCategory() != null ? txn.getTransactionCategory().name() : null);
        row.put("status", txn.getStatus() != null ? txn.getStatus().name() : null);
        row.put("remitterName", txn.getRemitterName());
        row.put("remitterAccount", txn.getRemitterAccount());
        row.put("beneficiaryName", txn.getBeneficiaryName());
        row.put("beneficiaryAccount", txn.getBeneficiaryAccount());
        row.put("viban", txn.getViban());
        return row;
    }
}
