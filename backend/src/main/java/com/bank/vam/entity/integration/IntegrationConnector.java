package com.bank.vam.entity.integration;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "integration_connectors")
public class IntegrationConnector {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "connector_code", nullable = false, unique = true, length = 30)
    private String connectorCode;

    @Column(name = "connector_name", nullable = false, length = 100)
    private String connectorName;

    @Column(name = "short_name", length = 50)
    private String shortName;

    @Column(name = "description", length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private ConnectorCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false, length = 20)
    private AuthType authType;

    @Column(name = "base_url_template", length = 255)
    private String baseUrlTemplate;

    @Column(name = "supported_features", columnDefinition = "jsonb")
    private String supportedFeatures;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private ConnectorStatus status = ConnectorStatus.AVAILABLE;

    @Column(name = "documentation_url", length = 255)
    private String documentationUrl;

    @Column(name = "logo_url", length = 255)
    private String logoUrl;

    @Column(name = "icon_type", length = 50)
    private String iconType;

    @Column(name = "region", length = 50)
    private String region;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    // ========================================================================
    // ENUMS - Extended to match repository queries
    // ========================================================================

    public enum ConnectorCategory {
        ERP,            // SAP, Oracle, Dynamics
        TREASURY,       // Kyriba, FIS
        BANKING,        // Core banking
        OPEN_BANKING,   // PSD2, UK OB, aggregators
        PAYMENTS,       // SWIFT, SEPA
        GENERIC         // SFTP, REST, Webhooks
    }

    public enum AuthType {
        OAUTH,
        API_KEY,
        BASIC,
        CERTIFICATE,
        OAUTH2,         // For Open Banking
        MTLS            // Mutual TLS
    }

    public enum ConnectorStatus {
        AVAILABLE,
        BETA,
        COMING_SOON,
        DEPRECATED
    }
}