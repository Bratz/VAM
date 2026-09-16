package com.bank.vam.entity.fileingest;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per uploaded file — the queryable backbone of the file-ingest
 * feature. The linked Jira ticket (filed only on an unrecognized format) is
 * a view onto this row, not the source of truth.
 */
@Entity
@Table(name = "ingest_job", indexes = {
        @Index(name = "idx_ingest_job_customer_domain", columnList = "customer_id, domain"),
        @Index(name = "idx_ingest_job_stage", columnList = "stage")
})
@Getter
@Setter
@NoArgsConstructor
public class IngestJob {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IngestDomain domain;

    @Column(nullable = false)
    private String originalFilename;

    /** Where the raw file lives under the workspace volume, e.g. {@code {id}/source.csv}.
     * Null between the two saves in IngestJobService.receiveUpload (the first save is
     * only to obtain an id to build this path from). */
    private String workspacePath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IngestStage stage = IngestStage.RECEIVED;

    private String jiraTicketKey;

    /** Set by the agent worker once it knows which signature this job's shape resolves
     * to (hit or miss) — IngestRetrySweepService watches this field to know when it's
     * safe to resume an AWAITING_TRANSFORM job, without ever computing a hash itself. */
    private UUID formatSignatureId;

    /** Human-readable reason, populated only when stage = BLOCKED. */
    @Column(length = 2000)
    private String blockedReason;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
