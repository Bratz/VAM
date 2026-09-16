package com.bank.vam.fileingest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ingest")
public record IngestProperties(Jira jira, Anthropic anthropic, Agent agent, String workspaceDir) {

    public record Jira(String baseUrl, String email, String apiToken, String projectKey) {
    }

    public record Anthropic(String apiKey, String model) {
    }

    public record Agent(int maxTurns, int maxRetries) {
    }
}
