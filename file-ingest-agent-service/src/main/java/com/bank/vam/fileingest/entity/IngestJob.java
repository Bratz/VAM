package com.bank.vam.fileingest.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per uploaded file — the queryable backbone of this service. The
 * linked Jira ticket is a view onto this row, not the source of truth (see
 * the design doc's "Data model" section for why that differs from
 * defect-fix-service's Jira-only approach).
 */
@Entity
@Table(name = "ingest_job")
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

    /** Where the raw file lives under the workspace volume, e.g. {@code {id}/source.csv}. Null
     * between the two saves in IngestJobService.receiveUpload (the first save is only to obtain
     * an id to build this path from) — genuinely nullable, not an oversight (see V3 migration). */
    private String workspacePath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IngestStage stage = IngestStage.RECEIVED;

    private String jiraTicketKey;

    /** Set once a format_signature match/miss is known. Null before stage 4 runs. */
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
