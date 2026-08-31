package com.bank.vam.forecast.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Forecast-vs-actual variance row computed post-hoc against a
 * {@link ForecastRun} once {@code value_date} has passed.
 *
 * <p>Both signed {@code variance} (actual − forecast) and {@code variancePct}
 * are stored so dashboards can sort/filter on either without recomputation.
 * {@code reasonCode} is populated by the variance attribution job when a
 * cause can be inferred (e.g. {@code CATEGORY_MISCLASSIFIED},
 * {@code LATE_PAYMENT}).
 *
 * <p>Standalone entity. Mirrors {@code forecast_variance} in
 * {@code V13__forecasting.sql}.
 */
@Entity
@Table(name = "forecast_variance", indexes = {
        @Index(name = "idx_forecast_variance_run_date", columnList = "run_id, value_date")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ForecastVariance {

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

    @Column(name = "forecast_amount", nullable = false, precision = 20, scale = 4)
    private BigDecimal forecastAmount;

    @Column(name = "actual_amount", nullable = false, precision = 20, scale = 4)
    private BigDecimal actualAmount;

    @Column(name = "variance", nullable = false, precision = 20, scale = 4)
    private BigDecimal variance;

    @Column(name = "variance_pct", precision = 8, scale = 4)
    private BigDecimal variancePct;

    @Column(name = "reason_code", length = 50)
    private String reasonCode;
}
