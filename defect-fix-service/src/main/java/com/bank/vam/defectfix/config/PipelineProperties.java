package com.bank.vam.defectfix.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pipeline")
public record PipelineProperties(Jira jira, GitHub github, Anthropic anthropic, Agent agent) {

    public record Jira(String baseUrl, String email, String apiToken, String projectKey) {
    }

    public record GitHub(String owner, String repo, String token, String webhookSecret) {
    }

    public record Anthropic(String apiKey, String model) {
    }

    /** Where the service keeps its local clone + per-ticket worktrees. */
    public record Agent(String workspaceDir, int maxTurns, int maxRetries) {
    }
}
