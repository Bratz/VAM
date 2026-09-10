package com.bank.vam.defectfix.github;

import com.bank.vam.defectfix.config.PipelineProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class GitHubPullRequestClient {

    private final RestClient restClient;
    private final PipelineProperties.GitHub config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GitHubPullRequestClient(PipelineProperties properties) {
        this.config = properties.github();
        this.restClient = RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.token())
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    /** @return the new PR's html_url */
    public String createPullRequest(String headBranch, String baseBranch, String title, String body) {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("title", title);
        requestBody.put("head", headBranch);
        requestBody.put("base", baseBranch);
        requestBody.put("body", body);

        JsonNode response = restClient.post()
                .uri("/repos/{owner}/{repo}/pulls", config.owner(), config.repo())
                .body(requestBody)
                .retrieve()
                .body(JsonNode.class);
        return response.path("html_url").asText();
    }
}
