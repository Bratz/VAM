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

    /** Creates a ticket labeled with the pipeline label + this defect's dedup label. Returns the new issue key. */
    public String createIssue(String summary, String description, String dedupLabel) {
        ObjectNode fields = objectMapper.createObjectNode();
        ObjectNode project = fields.putObject("project");
        project.put("key", config.projectKey());
        fields.put("summary", summary);
        fields.set("description", toAdf(description));
        ObjectNode issueType = fields.putObject("issuetype");
        issueType.put("name", "Bug");
        fields.putArray("labels").add(PIPELINE_LABEL).add(dedupLabel);

        ObjectNode body = objectMapper.createObjectNode();
        body.set("fields", fields);

        JsonNode response = restClient.post()
                .uri("/issue")
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        return response.path("key").asText();
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
