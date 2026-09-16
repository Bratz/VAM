package com.bank.vam.entity.fileingest;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per stage-transition — what the customer-facing progressive
 * timeline polls and renders. Written by both this app and the separate
 * agent worker (see IngestStage) since they share this table directly.
 */
@Entity
@Table(name = "timeline_event", indexes = {
        @Index(name = "idx_timeline_event_job", columnList = "ingest_job_id, occurred_at")
})
@Getter
@Setter
@NoArgsConstructor
public class TimelineEvent {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID ingestJobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IngestStage stage;

    @Column(nullable = false)
    private String status;

    @Column(length = 2000)
    private String detail;

    @Column(nullable = false)
    private String actor = "system";

    @Column(nullable = false, updatable = false)
    private Instant occurredAt = Instant.now();
}
