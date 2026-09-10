package com.bank.vam.defectfix.orchestrate;

import com.bank.vam.defectfix.agent.CodingAgentClient.ToolCallRecord;

import java.util.List;

/**
 * Full record of one ticket's trip through the pipeline — every coding-agent
 * tool call across every attempt, each attempt's gate result, and the final
 * outcome. Same idea as SWE-agent's ".traj" files or OpenHands' event log:
 * a structured, replayable artifact per run, not just scattered log lines.
 * Written to disk by TriageOrchestrator once a ticket finishes (success,
 * exhausted, or an unexpected error) — see PipelineProperties.agent()'s
 * workspaceDir, under "trajectories/".
 */
public record TicketTrajectory(
        String ticketKey,
        String stack,
        String targetDefect,
        String sourceBranch,
        String startedAt,
        List<AttemptRecord> attempts,
        String outcome,
        String prUrl,
        String errorMessage,
        String finishedAt) {

    public record AttemptRecord(
            int attemptNumber,
            boolean agentStoppedNaturally,
            String agentFinalMessage,
            List<ToolCallRecord> toolCalls,
            boolean gatePassed,
            String gateOutput) {
    }
}
