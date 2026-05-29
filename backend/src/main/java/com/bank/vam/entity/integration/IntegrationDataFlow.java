package com.bank.vam.entity.integration;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Entity
@Table(name = "integration_data_flows")
public class IntegrationDataFlow {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "connection_id", nullable = false)
    private IntegrationConnection connection;

    @Column(name = "flow_name", nullable = false, length = 100)
    private String flowName;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 20)
    private FlowDirection direction;

    @Column(name = "source_entity", nullable = false, length = 100)
    private String sourceEntity;

    @Column(name = "target_entity", nullable = false, length = 100)
    private String targetEntity;

    @Column(name = "schedule_enabled")
    private Boolean scheduleEnabled = true;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "records_processed")
    private Integer recordsProcessed = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private FlowStatus status = FlowStatus.ACTIVE;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    @OneToMany(mappedBy = "flow", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<IntegrationFieldMapping> fieldMappings = new ArrayList<>();

    public enum FlowDirection {
        INBOUND, OUTBOUND, BIDIRECTIONAL
    }

    public enum FlowStatus {
        ACTIVE, PAUSED, ERROR
    }
}
