package com.bank.vam.ai.copilot.tools.read;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.repository.VirtualAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Read tool: deep-dive on a single virtual account by id or VA number —
 * distinct from {@code get_accounts}, whose list-row projection only exposes
 * 9 lightweight fields.
 *
 * <p>Output shape: {@code { totalMatched, account: { id, vaNumber, viban,
 * vaName, currencyCode, status, accountCategory, currentBalance,
 * availableBalance, parentAccountId, hierarchyLevel, owningEntityId,
 * owningEntityCode, corporateId, programId, bankSwift, bankName,
 * bankAccountNumber, bankIban, freshnessThresholdMinutes,
 * lastBalanceRefreshAt, lastBalanceRefreshStatus, ihbParticipant,
 * ihbSweepEnabled, ihbSweepFrequency, ihbSettlementVaId } } }
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetAccountDetailTool implements CopilotTool {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final VirtualAccountRepository accountRepository;

    @Override
    public String name() {
        return "get_account_detail";
    }

    @Override
    public String description() {
        return "Deep-dive on a single virtual account by id or VA number.";
    }

    @Override
    public Map<String, Map<String, Object>> parameterSchema() {
        return Map.of(
                "id", Map.of("type", "string", "description", "Account UUID or vaNumber", "required", true)
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

            Optional<VirtualAccount> found = UUID_PATTERN.matcher(idParam).matches()
                    ? accountRepository.findById(UUID.fromString(idParam))
                    : accountRepository.findByVaNumber(idParam);

            // Not-found on a corporate-scope mismatch, not 403 — same convention as
            // GetSweepInstructionsTool.lookupById, so a scoped caller can't probe ids.
            if (found.isPresent() && context.hasCorporateScope()
                    && !context.corporateId().equals(found.get().getCorporateId())) {
                found = Optional.empty();
            }

            if (found.isEmpty()) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("totalMatched", 0);
                return ToolResult.ok(name(), "No account matched id '" + idParam + "'", data);
            }

            VirtualAccount va = found.get();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalMatched", 1);
            data.put("account", toRow(va));
            return ToolResult.ok(name(), "Account " + va.getVaNumber(), data);
        } catch (Exception e) {
            log.warn("get_account_detail failed", e);
            return ToolResult.error(name(), "Failed to fetch account detail: " + e.getMessage());
        }
    }

    private Map<String, Object> toRow(VirtualAccount va) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", va.getId() != null ? va.getId().toString() : null);
        row.put("vaNumber", va.getVaNumber());
        row.put("viban", va.getViban());
        row.put("vaName", va.getVaName());
        row.put("currencyCode", va.getCurrencyCode());
        row.put("status", va.getStatus() != null ? va.getStatus().name() : null);
        row.put("accountCategory", va.getAccountCategory() != null ? va.getAccountCategory().name() : null);
        row.put("currentBalance", va.getCurrentBalance());
        row.put("availableBalance", va.getAvailableBalance());
        row.put("parentAccountId", va.getParentAccountId() != null ? va.getParentAccountId().toString() : null);
        row.put("hierarchyLevel", va.getHierarchyLevel());
        row.put("owningEntityId", va.getOwningEntityId() != null ? va.getOwningEntityId().toString() : null);
        row.put("owningEntityCode", va.getOwningEntityCode());
        row.put("corporateId", va.getCorporateId() != null ? va.getCorporateId().toString() : null);
        row.put("programId", va.getProgramId() != null ? va.getProgramId().toString() : null);
        row.put("bankSwift", va.getBankSwift());
        row.put("bankName", va.getBankName());
        row.put("bankAccountNumber", va.getBankAccountNumber());
        row.put("bankIban", va.getBankIban());
        row.put("freshnessThresholdMinutes", va.getFreshnessThresholdMinutes());
        row.put("lastBalanceRefreshAt", va.getLastBalanceRefreshAt());
        row.put("lastBalanceRefreshStatus", va.getLastBalanceRefreshStatus() != null
                ? va.getLastBalanceRefreshStatus().name() : null);
        row.put("ihbParticipant", va.getIhbParticipant());
        row.put("ihbSweepEnabled", va.getIhbSweepEnabled());
        row.put("ihbSweepFrequency", va.getIhbSweepFrequency());
        row.put("ihbSettlementVaId", va.getIhbSettlementVaId() != null ? va.getIhbSettlementVaId().toString() : null);
        return row;
    }
}
