package com.bank.vam.ai.copilot.action;

import com.bank.vam.entity.ai.BalanceAlert;
import com.bank.vam.entity.treasury.SweepRule;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.ai.BalanceAlertRepository;
import com.bank.vam.repository.treasury.SweepRuleRepository;
import com.bank.vam.service.audit.AuditLogService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Executes (or cancels) a previously-proposed Copilot action after the user
 * confirms via the action card.
 *
 * <p>State machine guards:
 * <ul>
 *   <li>Only PENDING proposals can be executed or cancelled.</li>
 *   <li>An expired PENDING proposal is transitioned to EXPIRED on first read
 *       and the call returns failure.</li>
 *   <li>Idempotent: a second Confirm click sees EXECUTED status and returns
 *       success without re-running the mutation.</li>
 * </ul>
 *
 * <p>Every executed action writes an {@link AuditLogService} entry with
 * {@code COPILOT_ACTION_*} event types so governance can trace Copilot
 * mutations back to the conversation + user that produced them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActionExecutorService {

    private static final TypeReference<Map<String, Object>> PARAMS_TYPE = new TypeReference<>() {};

    private final ActionProposalRepository proposalRepository;
    private final SweepRuleRepository sweepRuleRepository;
    private final BalanceAlertRepository balanceAlertRepository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final AuditLogService auditLog;
    private final ObjectMapper objectMapper;

    @Transactional
    public ExecutionResult execute(UUID proposalId) {
        ActionProposal proposal = proposalRepository.findById(proposalId).orElse(null);
        if (proposal == null) {
            return ExecutionResult.failure(null, "Action proposal not found");
        }

        // Already executed → idempotent success.
        if (proposal.getStatus() == ActionProposal.ProposalStatus.EXECUTED) {
            return ExecutionResult.success(proposal.getId(),
                    proposal.getExecutionResult() != null
                            ? proposal.getExecutionResult()
                            : "Already executed");
        }
        if (proposal.getStatus() == ActionProposal.ProposalStatus.CANCELLED) {
            return ExecutionResult.failure(proposal.getId(), "Action was cancelled");
        }
        if (proposal.isExpired(LocalDateTime.now())) {
            proposal.setStatus(ActionProposal.ProposalStatus.EXPIRED);
            proposalRepository.save(proposal);
            return ExecutionResult.failure(proposal.getId(),
                    "Action expired (TTL is short by design — re-ask Copilot)");
        }
        if (proposal.getStatus() != ActionProposal.ProposalStatus.PENDING) {
            return ExecutionResult.failure(proposal.getId(),
                    "Action is " + proposal.getStatus() + " — cannot execute");
        }

        Map<String, Object> params = parseParams(proposal.getParams());

        String result;
        try {
            result = switch (proposal.getTool()) {
                case "pause_sweep_rule" -> executePauseSweepRule(params);
                case "set_balance_alert" -> executeSetBalanceAlert(params, proposal.getConversationId());
                default -> throw new IllegalStateException(
                        "No executor for tool '" + proposal.getTool() + "'");
            };
        } catch (Exception e) {
            log.warn("Action execution failed for proposal {}: {}",
                    proposal.getId(), e.getMessage(), e);
            return ExecutionResult.failure(proposal.getId(),
                    "Execution failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }

        proposal.setStatus(ActionProposal.ProposalStatus.EXECUTED);
        proposal.setExecutedAt(LocalDateTime.now());
        proposal.setExecutionResult(result);
        proposalRepository.save(proposal);

        auditLog.record(
                "COPILOT_ACTION_EXECUTED",
                "ActionProposal",
                proposal.getId(),
                proposal.getSummary() + " — " + result,
                Map.of(
                        "tool", proposal.getTool(),
                        "params", params,
                        "conversationId", proposal.getConversationId().toString(),
                        "source", "COPILOT"
                )
        );

        return ExecutionResult.success(proposal.getId(), result);
    }

    @Transactional
    public ExecutionResult cancel(UUID proposalId) {
        ActionProposal proposal = proposalRepository.findById(proposalId).orElse(null);
        if (proposal == null) {
            return ExecutionResult.failure(null, "Action proposal not found");
        }
        if (proposal.getStatus() != ActionProposal.ProposalStatus.PENDING) {
            return ExecutionResult.failure(proposal.getId(),
                    "Action is " + proposal.getStatus() + " — cannot cancel");
        }
        proposal.setStatus(ActionProposal.ProposalStatus.CANCELLED);
        proposalRepository.save(proposal);
        auditLog.record(
                "COPILOT_ACTION_CANCELLED",
                "ActionProposal",
                proposal.getId(),
                "Cancelled: " + proposal.getSummary(),
                Map.of("tool", proposal.getTool(), "source", "COPILOT")
        );
        return ExecutionResult.success(proposal.getId(), "Action cancelled");
    }

    // ------------------------------------------------------------------------
    // Per-tool executors — small, focused, easy to extend
    // ------------------------------------------------------------------------

    private String executePauseSweepRule(Map<String, Object> params) {
        String ruleId = (String) params.get("ruleId");
        if (ruleId == null) {
            throw new IllegalStateException("Missing ruleId in proposal params");
        }
        SweepRule rule = sweepRuleRepository.findByRuleReference(ruleId)
                .orElseThrow(() -> new IllegalStateException("Sweep rule no longer exists: " + ruleId));
        rule.setStatus(SweepRule.SweepStatus.PAUSED);
        sweepRuleRepository.save(rule);
        return "Sweep rule " + ruleId + " is now PAUSED";
    }

    private String executeSetBalanceAlert(Map<String, Object> params, UUID conversationId) {
        UUID vaId = UUID.fromString((String) params.get("vaId"));
        String vaNumber = (String) params.get("vaNumber");
        String direction = (String) params.get("direction");
        BigDecimal threshold = new BigDecimal(params.get("threshold").toString());
        String currency = (String) params.get("currencyCode");

        // Re-confirm the VA still exists at execution time — small window
        // between propose and confirm so this should always succeed, but it
        // is cheap insurance.
        var va = virtualAccountRepository.findById(vaId)
                .orElseThrow(() -> new IllegalStateException("Virtual account no longer exists: " + vaId));

        BalanceAlert alert = BalanceAlert.builder()
                .vaId(va.getId())
                .vaNumber(vaNumber != null ? vaNumber : va.getVaNumber())
                .vaName(va.getVaName())
                .currencyCode(currency != null ? currency : va.getCurrencyCode())
                .direction(BalanceAlert.Direction.valueOf(direction.toUpperCase()))
                .threshold(threshold)
                .active(true)
                .createdByConversationId(conversationId)
                .build();
        balanceAlertRepository.save(alert);
        return String.format("Balance alert created on %s: %s %s %s",
                va.getVaNumber(), direction.toLowerCase(), threshold.toPlainString(),
                alert.getCurrencyCode());
    }

    private Map<String, Object> parseParams(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, PARAMS_TYPE);
        } catch (Exception e) {
            log.warn("Failed to parse proposal params: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * Result of an execute/cancel call. Stable shape for the controller's
     * JSON response and for frontend toast formatting.
     */
    public record ExecutionResult(
            boolean ok,
            UUID proposalId,
            String message
    ) {
        public static ExecutionResult success(UUID id, String message) {
            return new ExecutionResult(true, id, message);
        }
        public static ExecutionResult failure(UUID id, String message) {
            return new ExecutionResult(false, id, message);
        }
    }
}
