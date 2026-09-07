package com.bank.vam.entity.auth;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Revives {@code database/original-schema.sql}'s {@code user_account_access}
 * table almost exactly — this is the one table that directly answers what MCP
 * entitlement needs: "can this user see this corporate's data, and at what
 * level?"
 *
 * <p>A grant is either corporate-wide ({@link #corporateId} set,
 * {@link #virtualAccountId} null — "sees everything under this corporate") or
 * scoped to one account ({@link #virtualAccountId} set — "sees only this VA";
 * {@link EntitlementService} resolves which corporate that VA belongs to).
 * The original schema named the corporate-wide column
 * {@code corporate_scheme_id} against a {@code corporate_schemes} table that
 * doesn't exist in this codebase's actual data model — renamed to
 * {@code corporateId} to match the {@code Corporate}/{@code corporates}
 * convention every other entity in this session's work already uses
 * (NotionalPool, SweepRule, AuditLog).
 */
@Entity
@Table(name = "user_account_access", indexes = {
        @Index(name = "idx_uaa_user", columnList = "user_id"),
        @Index(name = "idx_uaa_corporate", columnList = "corporate_id"),
        @Index(name = "idx_uaa_va", columnList = "virtual_account_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Builder
public class UserAccountAccess extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Corporate-wide grant. Mutually exclusive with {@link #virtualAccountId} in practice. */
    @Column(name = "corporate_id")
    private UUID corporateId;

    /** Single-account grant — {@link EntitlementService} resolves its owning corporate. */
    @Column(name = "virtual_account_id")
    private UUID virtualAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_level", nullable = false, length = 20)
    @Builder.Default
    private AccessLevel accessLevel = AccessLevel.VIEW;

    public enum AccessLevel {
        VIEW, TRANSACT, ADMIN;

        /** Higher access levels imply every lower one — VIEW is always satisfied by any grant. */
        public boolean atLeast(AccessLevel required) {
            return this.ordinal() >= required.ordinal();
        }
    }
}
