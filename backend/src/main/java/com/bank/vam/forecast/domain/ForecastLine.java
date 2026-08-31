package com.bank.vam.forecast.domain;

import com.bank.vam.forecast.domain.enums.ForecastSource;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One forecast point: an expected cash movement on a value date, attributed
 * to an entity / account / currency / category, with mid + optional P10/P90.
 *
 * <p>Lines are immutable outputs of an engine — corrections flow in via
 * {@link ForecastAdjustment} rather than mutating the line. {@code source}
 * identifies the engine that produced this line; {@code sourceRef} is a
 * free-form back-reference to the upstream record (e.g. {@code receivable:<uuid>},
 * {@code pattern:<id>}).
 *
 * <p>Standalone entity. Mirrors {@code forecast_line} in
 * {@code V13__forecasting.sql}.
 */
@Entity
@Table(name = "forecast_line", indexes = {
        @Index(name = "idx_forecast_line_run_date", columnList = "run_id, value_date"),
        @Index(name = "idx_forecast_line_run_entity_ccy", columnList = "run_id, entity_id, currency")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ForecastLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private ForecastRun run;

    @Column(name = "value_date", nullable = false)
    private LocalDate valueDate;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "physical_account_id")
    private UUID physicalAccountId;

    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "CHAR(3)")
    private String currency;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private ForecastCategory category;

    @Column(name = "counterparty_id")
    private UUID counterpartyId;

    @Column(name = "amount_mid", precision = 20, scale = 4, nullable = false)
    private BigDecimal amountMid;

    @Column(name = "amount_p10", precision = 20, scale = 4)
    private BigDecimal amountP10;

    @Column(name = "amount_p90", precision = 20, scale = 4)
    private BigDecimal amountP90;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private ForecastSource source;

    @Column(name = "source_ref", length = 64)
    private String sourceRef;

    @Column(name = "confidence", precision = 3, scale = 2)
    private BigDecimal confidence;
}
