package com.bank.vam.fileingest.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per stage-transition — what the customer-facing progressive
 * timeline (design doc decision 11) polls and renders. Fired by
 * TimelineEventPublisher at the start/end of every pipeline stage.
 */
@Entity
@Table(name = "timeline_event")
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
