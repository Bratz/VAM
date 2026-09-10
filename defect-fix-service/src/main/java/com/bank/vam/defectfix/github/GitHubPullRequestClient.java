package com.bank.vam.defectfix.github;

import com.bank.vam.defectfix.config.PipelineProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Optional;

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

    /**
     * @return the PR's html_url — either a freshly created one, or an existing open PR for this
     * exact branch if one already exists (an interrupted earlier attempt on the same ticket, now
     * superseded by this force-pushed branch, can leave one behind; GitHub 422s a second create
     * for the same head/base pair rather than just returning the existing PR).
     */
    public String createPullRequest(String headBranch, String baseBranch, String title, String body) {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("title", title);
        requestBody.put("head", headBranch);
        requestBody.put("base", baseBranch);
        requestBody.put("body", body);

        try {
            JsonNode response = restClient.post()
                    .uri("/repos/{owner}/{repo}/pulls", config.owner(), config.repo())
                    .body(requestBody)
                    .retrieve()
                    .body(JsonNode.class);
            return response.path("html_url").asText();
        } catch (HttpClientErrorException.UnprocessableEntity e) {
            return findExistingPullRequestUrl(headBranch)
                    .orElseThrow(() -> e);
        }
    }

    /** Open PR for this exact head branch, if one exists — the pipeline now pushes fixes straight
     * back onto the branch that failed CI, so this is the normal way onSuccess finds the PR to
     * link (it already existed before the fix; the pipeline doesn't create a fresh one anymore
     * except as a fallback if that original PR is somehow gone). */
    public Optional<String> findExistingPullRequestUrl(String headBranch) {
        JsonNode response = restClient.get()
                .uri("/repos/{owner}/{repo}/pulls?head={owner}:{branch}&state=open",
                        config.owner(), config.repo(), config.owner(), headBranch)
                .retrieve()
                .body(JsonNode.class);
        return response.isArray() && !response.isEmpty()
                ? Optional.of(response.get(0).path("html_url").asText())
                : Optional.empty();
    }

    /**
     * Appends a "Resolves KEY." footer to the PR's own description if it isn't already there —
     * the durable, no-extra-lookup link a merge webhook reads back out to know which Jira ticket
     * to close (see GitHubWebhookController's pull_request handler). Idempotent: safe to call on
     * every success, including a PR this pipeline reused rather than created (that path never set
     * the body at all otherwise).
     */
    public void ensureIssueLinked(String headBranch, String issueKey) {
        JsonNode response = restClient.get()
                .uri("/repos/{owner}/{repo}/pulls?head={owner}:{branch}&state=open",
                        config.owner(), config.repo(), config.owner(), headBranch)
                .retrieve()
                .body(JsonNode.class);
        if (!response.isArray() || response.isEmpty()) {
            return;
        }
        JsonNode pr = response.get(0);
        String body = pr.path("body").asText("");
        if (body.contains(issueKey)) {
            return;
        }
        int number = pr.path("number").asInt();
        String newBody = body.isBlank() ? "Resolves " + issueKey + "." : body + "\n\n---\nResolves " + issueKey + ".";
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("body", newBody);
        restClient.patch()
                .uri("/repos/{owner}/{repo}/pulls/{number}", config.owner(), config.repo(), number)
                .body(requestBody)
                .retrieve()
                .toBodilessEntity();
    }
}
