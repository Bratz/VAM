package com.bank.vam.ai.copilot.tools.write;

import com.bank.vam.ai.copilot.CopilotProperties;
import com.bank.vam.ai.copilot.action.ActionProposal;
import com.bank.vam.ai.copilot.action.ActionProposalRepository;
import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.entity.treasury.SweepRule;
import com.bank.vam.repository.treasury.SweepRuleRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Write tool: <b>propose</b> pausing a sweep rule.
 *
 * <p>Critical design point — this tool DOES NOT mutate the rule. It creates
 * a pending {@link ActionProposal} row and returns a {@link ToolResult}
 * whose {@code data._action} block describes the proposed change. The
 * frontend renders that as an action card with Confirm / Cancel buttons.
 * Confirming hits {@code POST /actions/{id}/execute} which calls
 * {@code ActionExecutorService} — only then does the rule's status flip.
 *
 * <p>This split is the whole "human in the loop" guarantee for write
 * actions in the prototype.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PauseSweepRuleTool implements CopilotTool {

    private final SweepRuleRepository ruleRepository;
    private final ActionProposalRepository proposalRepository;
    private final ObjectMapper objectMapper;
    private final CopilotProperties properties;

    @Override
    public String name() {
        return "pause_sweep_rule";
    }

    @Override
    public String description() {
        return "Propose pausing a sweep rule. Creates an action card; user must confirm to execute.";
    }

    @Override
    public boolean isMutating() {
        return true;
    }

    @Override
    public Map<String, Map<String, Object>> parameterSchema() {
        return Map.of(
                "ruleId", Map.of("type", "string",
                        "description", "Rule reference (e.g. SR-101) — required")
        );
    }

    @Override
    @Transactional
    public ToolResult execute(ToolContext context, Map<String, Object> params) {
        String ruleId = paramString(params, "ruleId", null);
        if (ruleId == null) {
            return ToolResult.error(name(), "ruleId is required (e.g. \"SR-101\")");
        }
        if (context.conversationId() == null) {
            return ToolResult.error(name(),
                    "Write actions can only be proposed inside a chat conversation");
        }

        // Look up the rule. Reference is the canonical handle (SR-NNN).
        Optional<SweepRule> ruleOpt = ruleRepository.findByRuleReference(ruleId);
        if (ruleOpt.isEmpty()) {
            return ToolResult.error(name(), "No sweep rule found with reference " + ruleId);
        }
        SweepRule rule = ruleOpt.get();

        if (rule.getStatus() == SweepRule.SweepStatus.PAUSED) {
            return ToolResult.error(name(),
                    "Rule " + ruleId + " is already PAUSED. Nothing to do.");
        }
        if (rule.getStatus() == SweepRule.SweepStatus.DISABLED) {
            return ToolResult.error(name(),
                    "Rule " + ruleId + " is DISABLED. Re-enable before pausing.");
        }

        // Create the proposal.
        Map<String, Object> proposalParams = new LinkedHashMap<>();
        proposalParams.put("ruleId", ruleId);
        proposalParams.put("ruleEntityId", rule.getId().toString());

        LocalDateTime expiresAt = LocalDateTime.now()
                .plusMinutes(properties.getActionProposalTtlMinutes());

        String ruleName = rule.getRuleName() != null ? rule.getRuleName() : ruleId;
        String summary = String.format("Pause sweep rule %s (%s)", ruleId, ruleName);

        ActionProposal proposal = ActionProposal.builder()
                .conversationId(context.conversationId())
                .tool(name())
                .params(toJson(proposalParams))
                .summary(summary)
                .expiresAt(expiresAt)
                .build();
        proposal = proposalRepository.save(proposal);

        // Tool result. The frontend recognises {@code data._action} and renders
        // an action card inline with the assistant reply.
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("proposalId", proposal.getId().toString());
        action.put("type", name());
        action.put("title", "Pause sweep rule " + ruleId);
        action.put("description", "Sets the rule's status to PAUSED. Reversible by resuming. Other rules unaffected.");
        action.put("expiresAt", expiresAt.toString());
        action.put("params", proposalParams);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("proposalId", proposal.getId().toString());
        data.put("ruleId", ruleId);
        data.put("ruleName", rule.getRuleName());
        data.put("currentStatus", rule.getStatus().name());
        data.put("expiresAt", expiresAt.toString());
        data.put("_action", action);

        return ToolResult.ok(name(), summary, data);
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
