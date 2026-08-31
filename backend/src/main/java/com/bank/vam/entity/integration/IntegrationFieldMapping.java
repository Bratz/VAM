package com.bank.vam.entity.integration;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "integration_field_mappings")
public class IntegrationFieldMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flow_id", nullable = false)
    private IntegrationDataFlow flow;

    @Column(name = "source_field", nullable = false, length = 100)
    private String sourceField;

    @Column(name = "target_field", nullable = false, length = 100)
    private String targetField;

    @Column(name = "transformation", length = 50)
    private String transformation = "DIRECT";

    @Column(name = "transformation_params", columnDefinition = "jsonb")
    private String transformationParams;

    @Column(name = "is_required")
    private Boolean isRequired = false;

    @Column(name = "default_value", length = 255)
    private String defaultValue;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
