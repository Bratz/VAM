package com.bank.vam.defectfix.github;

import com.bank.vam.defectfix.config.PipelineProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Talks to the GitHub REST API to find/download workflow-run artifacts and resolve merge-bases. */
@Component
public class GitHubArtifactClient {

    private final RestClient restClient;
    private final PipelineProperties.GitHub config;

    public GitHubArtifactClient(PipelineProperties properties) {
        this.config = properties.github();
        this.restClient = RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.token())
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    /** Resolves the merge-base commit SHA for a PR's base...head range. */
    public String findMergeBaseSha(String baseSha, String headSha) {
        JsonNode response = restClient.get()
                .uri("/repos/{owner}/{repo}/compare/{base}...{head}",
                        config.owner(), config.repo(), baseSha, headSha)
                .retrieve()
                .body(JsonNode.class);
        return response.path("merge_base_commit").path("sha").asText();
    }

    /** Finds the most recent artifact with this exact name (artifacts are named "<prefix>-<sha>" per commit). */
    public Optional<Long> findArtifactId(String name) {
        JsonNode response = restClient.get()
                .uri("/repos/{owner}/{repo}/actions/artifacts?name={name}", config.owner(), config.repo(), name)
                .retrieve()
                .body(JsonNode.class);
        JsonNode artifacts = response.path("artifacts");
        if (!artifacts.isArray() || artifacts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(artifacts.get(0).path("id").asLong());
    }

    /** Downloads an artifact zip and unpacks it into filename -> UTF-8 text content. */
    public Map<String, String> downloadAndUnzip(long artifactId) {
        byte[] zipBytes = restClient.get()
                .uri("/repos/{owner}/{repo}/actions/artifacts/{id}/zip", config.owner(), config.repo(), artifactId)
                .retrieve()
                .body(byte[].class);

        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                files.put(entry.getName(), readAll(zip));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to unzip artifact " + artifactId, e);
        }
        return files;
    }

    private String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        in.transferTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }
}
