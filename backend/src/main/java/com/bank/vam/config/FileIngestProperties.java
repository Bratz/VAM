package com.bank.vam.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * File-ingest pipeline configuration. Bound from {@code vam.fileingest.*}.
 * The workspace directory and Jira project must match what the separate
 * file-ingest-agent-service worker is configured with — they share both the
 * filesystem (uploaded source files) and the Jira project (tickets) as their
 * only two coordination points; see tasks/file-ingest-pipeline-design.md.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "vam.fileingest")
public class FileIngestProperties {

    /** Where uploaded files live, e.g. {@code {workspaceDir}/{jobId}/source.csv}. Must be the
     * same path the agent worker mounts as its own workspace volume. */
    private String workspaceDir = "/data/file-ingest-workspace";

    /** How often IngestRetrySweepService checks AWAITING_TRANSFORM jobs for a completed transform. */
    private int retryCadenceMinutes = 2;

    private final Jira jira = new Jira();

    @Getter
    @Setter
    public static class Jira {
        private String baseUrl = "https://vam-five.atlassian.net";
        private String email = "";
        private String apiToken = "";
        private String projectKey = "KAN";
    }
}
