package com.bank.vam.entity.integration;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Entity
@Table(name = "integration_connections")
public class IntegrationConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "connector_id", nullable = false)
    private IntegrationConnector connector;

    @Column(name = "connection_name", nullable = false, length = 100)
    private String connectionName;

    @Enumerated(EnumType.STRING)
    @Column(name = "environment", nullable = false, length = 20)
    private Environment environment = Environment.SANDBOX;

    @Column(name = "credentials", columnDefinition = "jsonb")
    private String credentials;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_frequency", length = 20)
    private SyncFrequency syncFrequency = SyncFrequency.DAILY;

    @Column(name = "last_sync_at")
    private LocalDateTime lastSyncAt;

    @Column(name = "next_sync_at")
    private LocalDateTime nextSyncAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private ConnectionStatus status = ConnectionStatus.DISCONNECTED;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @OneToMany(mappedBy = "connection", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<IntegrationDataFlow> dataFlows = new ArrayList<>();

    public enum Environment {
        SANDBOX, PRODUCTION
    }

    public enum SyncFrequency {
        REAL_TIME, HOURLY, DAILY, MANUAL
    }

    public enum ConnectionStatus {
        CONNECTED, DISCONNECTED, ERROR, SYNCING
    }
}
