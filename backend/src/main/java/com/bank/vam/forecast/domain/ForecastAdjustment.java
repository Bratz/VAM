package com.bank.vam.forecast.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Manual treasurer overlay on top of engine-produced {@link ForecastLine}s.
 *
 * <p>Stored separately from {@code forecast_line} so the engine output is
 * preserved and overlays are auditable and individually reversible.
 *
 * <p>Carry-forward semantic: when a new run starts, the orchestrator loads
 * the most-recent prior COMPLETED run's adjustments and hands them to the
 * {@code ManualOverlayEngine}, which re-emits them as lines on the new run.
 * That preserves the treasurer's intent across re-generations without
 * forcing them to re-enter overlays after each run.
 *
 * <p>Standalone entity. Mirrors {@code forecast_adjustment} in
 * {@code V13__forecasting.sql} — including the table's own
 * {@code created_at} / {@code created_by} columns (which is why this entity
 * does <strong>not</strong> use the {@code BaseEntity} audit listener — V13
 * already supplies the defaults at the SQL level).
 */
@Entity
@Table(name = "forecast_adjustment", indexes = {
        @Index(name = "idx_forecast_adjustment_run_date", columnList = "run_id, value_date")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ForecastAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    /** FK → {@link ForecastRun#getId()}. {@code ON DELETE CASCADE} in V13. */
    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "value_date", nullable = false)
    private LocalDate valueDate;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "CHAR(3)")
    private String currency;

    /** FK → {@link ForecastCategory#getId()}. */
    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    /**
     * Signed delta added to the (entity, currency, category, value_date)
     * bucket at roll-up time. Sign convention: positive = adds to direction;
     * negative = subtracts.
     */
    @Column(name = "delta_amount", nullable = false, precision = 20, scale = 4)
    private BigDecimal deltaAmount;

    /** Discrete code for taxonomy/reporting (e.g. {@code FX_HEDGE}, {@code DEAL_SLIP}). */
    @Column(name = "reason_code", nullable = false, length = 50)
    private String reasonCode;

    /** Free-text treasurer note. */
    @Column(name = "note", columnDefinition = "text")
    private String note;

    /** Treasurer / system actor who entered the adjustment. */
    @Column(name = "created_by", length = 100)
    private String createdBy;

    /** Set at construction; matches V13's {@code DEFAULT now()}. */
    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
