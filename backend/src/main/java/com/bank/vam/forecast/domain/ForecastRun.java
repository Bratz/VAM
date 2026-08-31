package com.bank.vam.forecast.domain;

import com.bank.vam.forecast.domain.enums.RunStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Header row for a single forecast generation. One row per
 * {@code ForecastOrchestrator.run(...)} call.
 *
 * <p>Lifecycle: created with {@link RunStatus#RUNNING} in a
 * {@code REQUIRES_NEW} transaction (so monitoring can see in-flight runs),
 * then transitioned to {@link RunStatus#COMPLETED} or
 * {@link RunStatus#FAILED} by the orchestrator. {@code generationMs} is
 * populated on the terminal transition.
 *
 * <p>Child rows ({@code forecast_line}, {@code forecast_adjustment},
 * {@code forecast_scenario}, {@code forecast_variance}) cascade-delete with
 * this row at the SQL level (see V13).
 *
 * <p>Standalone entity. Mirrors {@code forecast_run} in
 * {@code V13__forecasting.sql}.
 */
@Entity
@Table(name = "forecast_run", indexes = {
        @Index(name = "idx_forecast_run_corp_run_at", columnList = "corporate_id, run_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ForecastRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    /** Tenant key — the corporate this run was generated for. */
    @Column(name = "corporate_id", nullable = false)
    private UUID corporateId;

    /** When the run was kicked off. Defaulted at SQL level (now()) and at the JVM level on build. */
    @Column(name = "run_at", nullable = false)
    @Builder.Default
    private OffsetDateTime runAt = OffsetDateTime.now();

    /** Last value-date the forecast covers; computed as {@code today + horizonDays}. */
    @Column(name = "horizon_end", nullable = false)
    private LocalDate horizonEnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private RunStatus status = RunStatus.RUNNING;

    /** Free-text actor — {@code "system"} for scheduled runs, a username for treasurer-triggered ones. */
    @Column(name = "created_by", length = 100)
    private String createdBy;

    /** Wall-clock orchestrator duration in milliseconds; null while RUNNING. */
    @Column(name = "generation_ms")
    private Integer generationMs;
}
