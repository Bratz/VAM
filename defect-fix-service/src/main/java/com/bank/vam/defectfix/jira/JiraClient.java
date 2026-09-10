package com.bank.vam.defectfix.jira;

import com.bank.vam.defectfix.config.PipelineProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/** Thin client over the Jira Cloud REST API (v3) for the one project this pipeline files into. */
@Component
public class JiraClient {

    private static final String PIPELINE_LABEL = "auto-fix-pipeline";

    private final RestClient restClient;
    private final PipelineProperties.Jira config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JiraClient(PipelineProperties properties) {
        this.config = properties.jira();
        String basicAuth = Base64.getEncoder().encodeToString(
                (config.email() + ":" + config.apiToken()).getBytes(StandardCharsets.UTF_8));
        this.restClient = RestClient.builder()
                .baseUrl(config.baseUrl() + "/rest/api/3")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basicAuth)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .build();
    }

    /** True if an open ticket already carries this dedup label (regardless of who created it). */
    public boolean existsWithLabel(String label) {
        String jql = "project = " + config.projectKey() + " AND labels = \"" + PIPELINE_LABEL
                + "\" AND labels = \"" + label + "\"";
        JsonNode response = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/search")
                        .queryParam("jql", jql)
                        .queryParam("maxResults", 1)
                        .build())
                .retrieve()
                .body(JsonNode.class);
        return response.path("total").asInt(0) > 0;
    }

    /** Creates a ticket labeled with the pipeline label + this defect's dedup and stack labels. Returns the new issue key. */
    public String createIssue(String summary, String description, String dedupLabel, String stackLabel) {
        ObjectNode fields = objectMapper.createObjectNode();
        ObjectNode project = fields.putObject("project");
        project.put("key", config.projectKey());
        fields.put("summary", summary);
        fields.set("description", toAdf(description));
        ObjectNode issueType = fields.putObject("issuetype");
        issueType.put("name", "Bug");
        fields.putArray("labels").add(PIPELINE_LABEL).add(dedupLabel).add(stackLabel);

        ObjectNode body = objectMapper.createObjectNode();
        body.set("fields", fields);

        JsonNode response = restClient.post()
                .uri("/issue")
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        return response.path("key").asText();
    }

    public record Issue(String key, String summary, String description, String stackLabel) {
    }

    /** Oldest pipeline-labeled ticket still in To Do, if any — this is the single-concurrency gate's queue. */
    public Optional<Issue> findOldestOpenTicket() {
        String jql = "project = " + config.projectKey() + " AND labels = \"" + PIPELINE_LABEL
                + "\" AND status = \"To Do\" ORDER BY created ASC";
        JsonNode response = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/search")
                        .queryParam("jql", jql)
                        .queryParam("maxResults", 1)
                        .queryParam("fields", "summary,description,labels")
                        .build())
                .retrieve()
                .body(JsonNode.class);
        JsonNode issues = response.path("issues");
        if (!issues.isArray() || issues.isEmpty()) {
            return Optional.empty();
        }
        JsonNode issue = issues.get(0);
        String key = issue.path("key").asText();
        String summary = issue.path("fields").path("summary").asText();
        String description = extractPlainText(issue.path("fields").path("description"));
        String stackLabel = "stack-frontend";
        for (JsonNode label : issue.path("fields").path("labels")) {
            if (label.asText().startsWith("stack-")) {
                stackLabel = label.asText();
                break;
            }
        }
        return Optional.of(new Issue(key, summary, description, stackLabel));
    }

    /** True if any pipeline-labeled ticket is currently being worked (the single-concurrency gate). */
    public boolean anyTicketInProgress() {
        String jql = "project = " + config.projectKey() + " AND labels = \"" + PIPELINE_LABEL
                + "\" AND status = \"In Progress\"";
        JsonNode response = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/search")
                        .queryParam("jql", jql)
                        .queryParam("maxResults", 1)
                        .build())
                .retrieve()
                .body(JsonNode.class);
        return response.path("total").asInt(0) > 0;
    }

    public void transitionTo(String issueKey, String targetStatusName) {
        JsonNode transitions = restClient.get()
                .uri("/issue/{key}/transitions", issueKey)
                .retrieve()
                .body(JsonNode.class)
                .path("transitions");
        String transitionId = null;
        for (JsonNode t : transitions) {
            if (t.path("to").path("name").asText().equalsIgnoreCase(targetStatusName)) {
                transitionId = t.path("id").asText();
                break;
            }
        }
        if (transitionId == null) {
            throw new IllegalStateException("No transition to \"" + targetStatusName + "\" available for " + issueKey);
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.putObject("transition").put("id", transitionId);
        restClient.post().uri("/issue/{key}/transitions", issueKey).body(body).retrieve().toBodilessEntity();
    }

    public void addComment(String issueKey, String text) {
        ObjectNode body = objectMapper.createObjectNode();
        body.set("body", toAdf(text));
        restClient.post().uri("/issue/{key}/comment", issueKey).body(body).retrieve().toBodilessEntity();
    }

    public void addLabel(String issueKey, String label) {
        ObjectNode addOp = objectMapper.createObjectNode();
        addOp.put("add", label);
        ObjectNode update = objectMapper.createObjectNode();
        update.putArray("labels").add(addOp);
        ObjectNode body = objectMapper.createObjectNode();
        body.set("update", update);
        restClient.put().uri("/issue/{key}", issueKey).body(body).retrieve().toBodilessEntity();
    }

    /** Walks Atlassian Document Format, concatenating every text node (paragraphs separated by blank lines). */
    private String extractPlainText(JsonNode adfNode) {
        StringBuilder sb = new StringBuilder();
        collectText(adfNode, sb);
        return sb.toString().strip();
    }

    private void collectText(JsonNode node, StringBuilder sb) {
        if (node.isMissingNode() || node.isNull()) {
            return;
        }
        if ("text".equals(node.path("type").asText())) {
            sb.append(node.path("text").asText());
        }
        for (JsonNode child : node.path("content")) {
            collectText(child, sb);
        }
        if ("paragraph".equals(node.path("type").asText())) {
            sb.append("\n\n");
        }
    }

    /** Minimal Atlassian Document Format wrapper — the v3 API rejects a plain string description. */
    private JsonNode toAdf(String plainText) {
        ObjectNode doc = objectMapper.createObjectNode();
        doc.put("type", "doc");
        doc.put("version", 1);
        ObjectNode paragraph = objectMapper.createObjectNode();
        paragraph.put("type", "paragraph");
        ObjectNode text = objectMapper.createObjectNode();
        text.put("type", "text");
        text.put("text", plainText);
        paragraph.set("content", objectMapper.createArrayNode().add(text));
        doc.set("content", objectMapper.createArrayNode().add(paragraph));
        return doc;
    }
}
