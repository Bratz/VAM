package com.bank.vam.entity.simulator;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A saved proposed cash-concentration structure — the Simulator's core record.
 *
 * <p>The simulated structure (shadows + rules) is carried in
 * {@link #proposedPayload} as JSONB so the backend stays decoupled from the
 * frontend's evolving structure shape. This is a deliberate decision held
 * across all five phases: scenarios are small, and promoting shadows/rules to
 * child tables would duplicate the live-table shape and create a sync surface
 * we do not want. Phase 3 activation writes to the <em>live</em> tables, not to
 * simulator child tables, so there is no FK pressure to normalise here.
 *
 * <p><b>Sandbox isolation:</b> nothing in the simulator package writes to
 * {@code virtual_accounts}, {@code sweep_rules}, or {@code physical_accounts}
 * in Phase 1. Live tables are read-only inputs.
 *
 * <p>Lifecycle: {@code DRAFT → READY → PROPOSED → ACTIVATED}; {@code ARCHIVED}
 * is the soft-delete terminal state.
 *
 * @see com.bank.vam.entity.BaseEntity for id / audit / version columns
 */
@Entity
@Table(name = "simulator_scenarios", indexes = {
        @Index(name = "idx_simscen_corporate", columnList = "corporate_id"),
        @Index(name = "idx_simscen_parent", columnList = "parent_scenario_id"),
        @Index(name = "idx_simscen_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SimulatorScenario extends BaseEntity {

    /** FK into live {@code corporates} (read-only scope; not enforced in V1). */
    @Column(name = "corporate_id", nullable = false)
    private UUID corporateId;

    @Column(name = "scenario_name", nullable = false, length = 120)
    private String scenarioName;

    /** Generated {@code SCN-yyyyMMdd-NNN}, daily sequence. */
    @Column(name = "scenario_reference", nullable = false, length = 20)
    private String scenarioReference;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "base_currency", length = 3)
    private String baseCurrency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    @Builder.Default
    private ScenarioStatus status = ScenarioStatus.DRAFT;

    /** Parent scenario for forking (Phase 4). Null for a root scenario. */
    @Column(name = "parent_scenario_id")
    private UUID parentScenarioId;

    /** Fork label within a comparison set, e.g. {@code 'A'} / {@code 'B'} (Phase 4). */
    @Column(name = "fork_label", length = 60)
    private String forkLabel;

    /** When live config was captured for the diff baseline (Phase 3). */
    @Column(name = "snapshot_taken_at")
    private LocalDateTime snapshotTakenAt;

    /** Frozen live config at snapshot time (Phase 3 diff baseline). */
    @Column(name = "snapshot_payload", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String snapshotPayload;

    /** The simulated structure: {@code { "shadows": [...], "rules": [...] }}. */
    @Column(name = "proposed_payload", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    @Builder.Default
    private String proposedPayload = "{\"shadows\":[],\"rules\":[]}";

    /** Last computed Optimisation Score (Phase 2). */
    @Column(name = "score_payload", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String scorePayload;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    // ---- Phase 3: single-user activation (additive — V7) ----

    @Column(name = "proposed_by", length = 100)
    private String proposedBy;

    @Column(name = "proposal_notes", columnDefinition = "text")
    private String proposalNotes;

    @Column(name = "scheduled_activation_at")
    private LocalDateTime scheduledActivationAt;

    @Column(name = "activated_by", length = 100)
    private String activatedBy;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "activation_error", columnDefinition = "text")
    private String activationError;

    /**
     * Mirrors the {@code chk_scenario_status} CHECK constraint in
     * {@code V5__create_simulator_scenarios.sql}. Keep the two in lockstep.
     */
    public enum ScenarioStatus {
        DRAFT, READY, PROPOSED, ACTIVATED, ARCHIVED
    }
}
