package com.bank.vam.entity.treasury;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Header row for a single async {@code POST /api/v1/sweeping/execute-async}
 * run. Mirrors {@code com.bank.vam.forecast.domain.ForecastRun}'s shape and
 * lifecycle:
 *
 * <p>Created with {@link SweepRunStatus#RUNNING} in a {@code REQUIRES_NEW}
 * transaction (see {@code SweepRunBootstrap}) so a poller sees the row
 * immediately, even before the {@code sweepExecutor} thread has picked up
 * the work. Progress counters are updated as sources complete, then the run
 * transitions to {@link SweepRunStatus#COMPLETED} or
 * {@link SweepRunStatus#FAILED}.
 *
 * <p>Standalone entity (not {@code BaseEntity}) — same reasoning as
 * {@code ForecastRun}: this is a job-header row, not a domain entity that
 * needs the full audit-column set.
 */
@Entity
@Table(name = "sweep_run")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SweepRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    /** Free-text actor — "system" for scheduled runs, a caller identity for API-triggered ones. */
    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "started_at", nullable = false)
    @Builder.Default
    private LocalDateTime startedAt = LocalDateTime.now();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private SweepRunStatus status = SweepRunStatus.RUNNING;

    /**
     * Comma-joined rule ids this run targets; {@code null} means "all active
     * rules" (mirrors {@code SweepRuleDto.RunSweepsRequest.ruleIds} being
     * empty/omitted).
     */
    @Column(name = "rule_ids", length = 2000)
    private String ruleIds;

    /** Total sources across all targeted rules — known up front, before execution starts. */
    @Column(name = "sources_total")
    @Builder.Default
    private int sourcesTotal = 0;

    @Column(name = "sources_processed")
    @Builder.Default
    private int sourcesProcessed = 0;

    @Column(name = "success_count")
    @Builder.Default
    private int successCount = 0;

    @Column(name = "failed_count")
    @Builder.Default
    private int failedCount = 0;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;
}
