package com.bank.vam.forecast.domain;

import com.bank.vam.forecast.domain.enums.ScenarioType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;
import java.util.UUID;

/**
 * Named what-if scenario attached to a {@link ForecastRun}.
 *
 * <p>{@code rules} carries the scenario's transform: a JSON map of
 * category/entity/currency selectors and multipliers/deltas. The orchestrator
 * applies the rules at render time to project the base run into UPSIDE,
 * DOWNSIDE or WHATIF views. The shape is intentionally opaque at the entity
 * level so the rule engine can evolve without a schema migration.
 *
 * <p>Standalone entity. Mirrors {@code forecast_scenario} in
 * {@code V13__forecasting.sql}.
 */
@Entity
@Table(name = "forecast_scenario")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ForecastScenario {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    /** FK → {@link ForecastRun#getId()}. {@code ON DELETE CASCADE} in V13. */
    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private ScenarioType type;

    @Column(name = "rules", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    @Builder.Default
    private Map<String, Object> rules = Map.of();
}
