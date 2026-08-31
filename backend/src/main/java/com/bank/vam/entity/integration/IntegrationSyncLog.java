package com.bank.vam.entity.integration;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "integration_sync_logs")
public class IntegrationSyncLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "connection_id", nullable = false)
    private IntegrationConnection connection;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flow_id")
    private IntegrationDataFlow flow;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "records_processed")
    private Integer recordsProcessed = 0;

    @Column(name = "records_failed")
    private Integer recordsFailed = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SyncStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "error_details", columnDefinition = "jsonb")
    private String errorDetails;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum SyncStatus {
        RUNNING, SUCCESS, PARTIAL, FAILED
    }
}
