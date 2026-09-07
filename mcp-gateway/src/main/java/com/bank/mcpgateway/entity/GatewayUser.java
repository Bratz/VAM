package com.bank.mcpgateway.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Read-only mirror of the backend's {@code com.bank.vam.entity.auth.DemoUser},
 * mapped to the same {@code users} table. This module is a separate Maven
 * project (matching ../backend's own standalone setup — see pom.xml), so it
 * can't import the backend's classes; duplicating this one small entity is
 * cheaper and safer than wiring a shared submodule for two tables. The
 * backend owns the schema ({@code ddl-auto: update} there); this module's
 * {@code ddl-auto: none} never creates or alters it.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class GatewayUser {

    @Id
    private UUID id;

    @Column(name = "username")
    private String username;

    @Column(name = "email")
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "status")
    private String status;

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
