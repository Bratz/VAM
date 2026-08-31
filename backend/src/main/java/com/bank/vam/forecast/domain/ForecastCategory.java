package com.bank.vam.forecast.domain;

import com.bank.vam.forecast.domain.enums.ForecastDirection;
import com.bank.vam.forecast.domain.enums.ForecastSource;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * Cash-flow category taxonomy node.
 *
 * <p>Categories form a self-referential tree (via {@code parent}). Each leaf
 * carries a {@code defaultEngine} hint so the orchestrator knows which engine
 * normally produces lines for it (a manual adjustment can still override).
 *
 * <p>{@code color} is a hex UI hint (e.g. {@code #2E7D32}) used by the
 * dashboard to render consistent legend swatches.
 *
 * <p>Standalone entity — does <strong>not</strong> extend {@code BaseEntity}.
 * V13 deliberately omits the {@code updated_at / updated_by / version} audit
 * columns for the forecast tables (runs are immutable; adjustments carry
 * their own {@code created_at}), so the BaseEntity convention does not fit.
 *
 * Mirrors {@code forecast_category} in {@code V13__forecasting.sql}.
 */
@Entity
@Table(name = "forecast_category")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ForecastCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(name = "code", nullable = false, unique = true, length = 50)
    private String code;

    @Column(name = "label", nullable = false, length = 200)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10)
    private ForecastDirection direction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private ForecastCategory parent;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_engine", nullable = false, length = 20)
    private ForecastSource defaultEngine;

    @Column(name = "color", length = 7)
    private String color;
}
