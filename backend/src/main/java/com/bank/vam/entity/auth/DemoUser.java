package com.bank.vam.entity.auth;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Revives {@code database/original-schema.sql}'s {@code users} table — designed
 * years ago, never applied to the live schema (it's absent from every Flyway
 * migration and from the current {@code vam_schema.sql} dump; Flyway itself is
 * disabled here, so schema arrives via {@code ddl-auto: update} against JPA
 * entities like this one).
 *
 * <p>Scoped down from the original design for what MCP entitlement actually
 * needs: identity + a password hash for the Phase 3 gateway's login form. The
 * original schema's {@code roles}/{@code permissions}/{@code role_permissions}/
 * {@code user_roles} tables model a general RBAC surface for UI actions
 * (TRANSACT/ADMIN workflows) that nothing in the MCP enquiry pipeline reads —
 * every MCP tool only ever needs {@code VIEW}, which {@link UserAccountAccess}
 * already expresses directly. Reviving that unused RBAC layer now would just
 * be trading one dormant schema for another; add it if/when a real
 * permission-gated UI action needs it, not speculatively here.
 */
@Entity
@Table(name = "users", indexes = {
        @Index(name = "idx_users_username", columnList = "username", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class DemoUser extends BaseEntity {

    @Column(name = "username", nullable = false, unique = true, length = 100)
    private String username;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    /**
     * Phase 3's gateway login form checks against this. Demo-grade (plain
     * BCrypt of a known demo password, no MFA) — matches the rest of this
     * platform's simulated-not-real security posture (see
     * docs/mcp-architecture.md's "demo-grade but architecture-complete"
     * fidelity decision).
     */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "display_name", length = 200)
    private String displayName;

    @Column(name = "status", length = 20)
    private String status = "ACTIVE";

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
