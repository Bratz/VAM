package com.bank.vam.service.fileingest;

// ponytail: duplicated from file-ingest-agent-service's own JiraClient (itself duplicated from
// defect-fix-service's) — generic Jira Cloud REST plumbing, no defect-specific logic. This app
// only ever creates/comments/labels/transitions tickets (never polls for open ones — that's the
// separate agent worker's job, via its own copy of this same plumbing), so this copy is trimmed
// to just those four operations.

import com.bank.vam.config.FileIngestProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Thin client over the Jira Cloud REST API (v3) for the one project this pipeline files into. */
@Component
public class FileIngestJiraClient {

    private static final String PIPELINE_LABEL = "file-ingest-pipeline";

    private final RestClient restClient;
    private final FileIngestProperties.Jira config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Lazily resolved (not at construction) so a Jira hiccup at service boot doesn't stop the app
    // from starting at all. Team-managed projects don't reliably ship a "Bug" issue type, so this
    // looks up whatever the project actually has instead of hardcoding a name.
    private volatile String issueTypeId;

    public FileIngestJiraClient(FileIngestProperties properties) {
        this.config = properties.getJira();
        String basicAuth = Base64.getEncoder().encodeToString(
                (config.getEmail() + ":" + config.getApiToken()).getBytes(StandardCharsets.UTF_8));
        this.restClient = RestClient.builder()
                .baseUrl(config.getBaseUrl() + "/rest/api/3")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basicAuth)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .build();
    }

    /** Creates a ticket labeled with the pipeline label + any extra labels (e.g. domain). Returns the new issue key. */
    public String createIssue(String summary, String description, String... extraLabels) {
        ObjectNode fields = objectMapper.createObjectNode();
        ObjectNode project = fields.putObject("project");
        project.put("key", config.getProjectKey());
        fields.put("summary", summary);
        fields.set("description", toAdf(description));
        fields.putObject("issuetype").put("id", resolveIssueTypeId());
        var labels = fields.putArray("labels").add(PIPELINE_LABEL);
        for (String label : extraLabels) {
            labels.add(label);
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.set("fields", fields);

        JsonNode response = restClient.post()
                .uri("/issue")
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        return response.path("key").asText();
    }

    /**
     * Picks a usable, non-subtask issue type from the project's own configuration — "Task"/"Bug" if
     * the project has one, otherwise whatever non-subtask type comes first. Resolved once and
     * cached; a project's issue types don't change at runtime.
     */
    private String resolveIssueTypeId() {
        String cached = issueTypeId;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (issueTypeId != null) {
                return issueTypeId;
            }
            JsonNode project = restClient.get()
                    .uri("/project/{key}", config.getProjectKey())
                    .retrieve()
                    .body(JsonNode.class);
            String fallbackId = null;
            for (JsonNode type : project.path("issueTypes")) {
                if (type.path("subtask").asBoolean(false)) {
                    continue;
                }
                if ("Task".equalsIgnoreCase(type.path("name").asText())
                        || "Bug".equalsIgnoreCase(type.path("name").asText())) {
                    issueTypeId = type.path("id").asText();
                    return issueTypeId;
                }
                if (fallbackId == null) {
                    fallbackId = type.path("id").asText();
                }
            }
            if (fallbackId == null) {
                throw new IllegalStateException("Project " + config.getProjectKey() + " has no usable (non-subtask) issue type");
            }
            issueTypeId = fallbackId;
            return issueTypeId;
        }
    }

    /** No-op if the issue is already in targetStatusName — makes every caller idempotent. */
    public void transitionTo(String issueKey, String targetStatusName) {
        String currentStatus = restClient.get()
                .uri("/issue/{key}?fields=status", issueKey)
                .retrieve()
                .body(JsonNode.class)
                .path("fields").path("status").path("name").asText();
        if (targetStatusName.equalsIgnoreCase(currentStatus)) {
            return;
        }
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

    private static final int MAX_COMMENT_CHARS = 20_000;

    public void addComment(String issueKey, String text) {
        String truncated = text.length() > MAX_COMMENT_CHARS
                ? text.substring(0, MAX_COMMENT_CHARS) + "\n...[truncated, " + text.length() + " chars total]"
                : text;
        ObjectNode body = objectMapper.createObjectNode();
        body.set("body", toAdf(truncated));
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
