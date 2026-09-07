package com.bank.vam.entity.audit;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * Lightweight audit-log row for governance events.
 *
 * v1 captures pool configuration changes (create/update/add-member/remove-member
 * /allocation-method-change) for the demo. Maker-checker is intentionally
 * skipped (G2); this log gives the audit trail without the workflow.
 *
 * Production: extend coverage to sweep-rule edits and instruction lifecycle.
 */
@Entity
@Table(name = "audit_log", indexes = {
        @Index(name = "idx_audit_event_type", columnList = "event_type"),
        @Index(name = "idx_audit_entity", columnList = "entity_type,entity_id"),
        @Index(name = "idx_audit_actor", columnList = "actor")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog extends BaseEntity {

    /** Event identifier, e.g. POOL_CREATED, POOL_ALLOCATION_METHOD_CHANGED. */
    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    /** Entity type the event is about, e.g. NotionalPool, PoolMember. */
    @Column(name = "entity_type", length = 60)
    private String entityType;

    /** ID of the affected entity. */
    @Column(name = "entity_id")
    private UUID entityId;

    /**
     * Owning corporate, when the event has one (nullable — several existing
     * call sites, e.g. copilot action execution, don't resolve one yet).
     * Added for MCP tool scoping: {@code get_audit_trail} must not leak one
     * corporate's governance events to a caller entitled to another.
     */
    @Column(name = "corporate_id")
    private UUID corporateId;

    /** User or system actor that triggered the event. */
    @Column(name = "actor", length = 100)
    private String actor;

    /** Free-form human-readable summary for quick scanning. */
    @Column(name = "summary", length = 500)
    private String summary;

    /** Structured event payload (before/after snapshots, request details). */
    @Column(name = "payload", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;
}
