package com.bank.vam.defectfix.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pipeline")
public record PipelineProperties(Jira jira, GitHub github) {

    public record Jira(String baseUrl, String email, String apiToken, String projectKey) {
    }

    public record GitHub(String owner, String repo, String token, String webhookSecret) {
    }
}
