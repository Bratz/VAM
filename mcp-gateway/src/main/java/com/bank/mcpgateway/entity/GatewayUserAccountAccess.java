package com.bank.mcpgateway.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Read-only mirror of the backend's {@code UserAccountAccess}, same table.
 * See {@link GatewayUser} for why this is duplicated rather than shared.
 */
@Entity
@Table(name = "user_account_access")
@Getter
@Setter
@NoArgsConstructor
public class GatewayUserAccountAccess {

    @Id
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "corporate_id")
    private UUID corporateId;

    @Column(name = "virtual_account_id")
    private UUID virtualAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_level")
    private AccessLevel accessLevel;

    public enum AccessLevel {
        VIEW, TRANSACT, ADMIN;

        public boolean atLeast(AccessLevel required) {
            return this.ordinal() >= required.ordinal();
        }
    }
}
