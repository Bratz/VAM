package com.bank.vam.ai.copilot.tools.write;

import com.bank.vam.ai.copilot.CopilotProperties;
import com.bank.vam.ai.copilot.action.ActionProposal;
import com.bank.vam.ai.copilot.action.ActionProposalRepository;
import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.repository.VirtualAccountRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Write tool: <b>propose</b> creating a balance alert.
 *
 * <p>Like {@code PauseSweepRuleTool}, this never mutates state. It validates
 * the inputs (account exists, threshold parses, direction sane), creates a
 * pending {@link ActionProposal}, and returns the action-card payload. The
 * actual {@code BalanceAlert} row only gets created when the user clicks
 * Confirm and {@code ActionExecutorService} dispatches.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SetBalanceAlertTool implements CopilotTool {

    private final VirtualAccountRepository accountRepository;
    private final ActionProposalRepository proposalRepository;
    private final ObjectMapper objectMapper;
    private final CopilotProperties properties;

    @Override
    public String name() {
        return "set_balance_alert";
    }

    @Override
    public String description() {
        return "Propose creating a balance alert on a virtual account.";
    }

    @Override
    public boolean isMutating() {
        return true;
    }

    @Override
    public Map<String, Map<String, Object>> parameterSchema() {
        return Map.of(
                "accountRef", Map.of("type", "string",
                        "description", "VA number, viban, or partial name (required)"),
                "direction", Map.of("type", "string",
                        "description", "above or below (required)"),
                "threshold", Map.of("type", "number",
                        "description", "Alert threshold amount (required)")
        );
    }

    @Override
    @Transactional
    public ToolResult execute(ToolContext context, Map<String, Object> params) {
        if (context.conversationId() == null) {
            return ToolResult.error(name(),
                    "Write actions can only be proposed inside a chat conversation");
        }

        String accountRef = paramString(params, "accountRef", null);
        String direction = paramString(params, "direction", "below");
        Object thresholdRaw = params == null ? null : params.get("threshold");

        if (accountRef == null) {
            return ToolResult.error(name(),
                    "Account reference required (VA number, viban, or partial name)");
        }
        BigDecimal threshold = parseAmount(thresholdRaw);
        if (threshold == null) {
            return ToolResult.error(name(), "Threshold amount required");
        }
        if (!direction.equalsIgnoreCase("above") && !direction.equalsIgnoreCase("below")) {
            return ToolResult.error(name(), "Direction must be 'above' or 'below'");
        }

        // Resolve the account.
        Optional<VirtualAccount> vaOpt = resolveAccount(accountRef);
        if (vaOpt.isEmpty()) {
            return ToolResult.error(name(),
                    "No virtual account matched '" + accountRef + "'. Try the exact VA number.");
        }
        VirtualAccount va = vaOpt.get();

        // Create proposal.
        Map<String, Object> proposalParams = new LinkedHashMap<>();
        proposalParams.put("vaId", va.getId().toString());
        proposalParams.put("vaNumber", va.getVaNumber());
        proposalParams.put("direction", direction.toUpperCase());
        proposalParams.put("threshold", threshold.toPlainString());
        proposalParams.put("currencyCode", va.getCurrencyCode());

        LocalDateTime expiresAt = LocalDateTime.now()
                .plusMinutes(properties.getActionProposalTtlMinutes());

        String summary = String.format("Alert when %s goes %s %s %s",
                va.getVaName() != null ? va.getVaName() : va.getVaNumber(),
                direction.toLowerCase(),
                threshold.toPlainString(),
                va.getCurrencyCode());

        ActionProposal proposal = ActionProposal.builder()
                .conversationId(context.conversationId())
                .tool(name())
                .params(toJson(proposalParams))
                .summary(summary)
                .expiresAt(expiresAt)
                .build();
        proposal = proposalRepository.save(proposal);

        Map<String, Object> action = new LinkedHashMap<>();
        action.put("proposalId", proposal.getId().toString());
        action.put("type", name());
        action.put("title", "Create balance alert");
        action.put("description", String.format(
                "Notify when **%s** is %s **%s %s**.",
                va.getVaName() != null ? va.getVaName() : va.getVaNumber(),
                direction.toLowerCase(),
                va.getCurrencyCode(),
                threshold.toPlainString()));
        action.put("expiresAt", expiresAt.toString());
        action.put("params", proposalParams);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("proposalId", proposal.getId().toString());
        data.put("vaId", va.getId().toString());
        data.put("vaNumber", va.getVaNumber());
        data.put("vaName", va.getVaName());
        data.put("currency", va.getCurrencyCode());
        data.put("currentBalance", va.getCurrentBalance());
        data.put("direction", direction.toUpperCase());
        data.put("threshold", threshold);
        data.put("expiresAt", expiresAt.toString());
        data.put("_action", action);

        return ToolResult.ok(name(), summary, data);
    }

    /**
     * Best-effort account resolution: exact match on vaNumber, then viban,
     * then case-insensitive substring on vaName. Returns the first match —
     * the slot extractor is constrained enough that ambiguity is rare for
     * the demo.
     */
    private Optional<VirtualAccount> resolveAccount(String ref) {
        Optional<VirtualAccount> exact = accountRepository.findByVaNumber(ref);
        if (exact.isPresent()) return exact;
        exact = accountRepository.findByViban(ref);
        if (exact.isPresent()) return exact;
        // Substring on name as last resort.
        String needle = ref.toLowerCase();
        List<VirtualAccount> all = accountRepository.findAll();
        return all.stream()
                .filter(va -> va.getVaName() != null
                        && va.getVaName().toLowerCase().contains(needle))
                .findFirst();
    }

    private BigDecimal parseAmount(Object raw) {
        if (raw == null) return null;
        if (raw instanceof BigDecimal b) return b;
        if (raw instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(raw.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String toJson(Map<String, Object> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialise proposal params: {}", e.getMessage());
            return "{}";
        }
    }
}
