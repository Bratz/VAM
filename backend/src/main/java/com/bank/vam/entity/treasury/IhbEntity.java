package com.bank.vam.entity.treasury;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "ihb_entities")
public class IhbEntity extends BaseEntity {

    @Column(name = "entity_code", nullable = false, unique = true, length = 20)
    private String entityCode;

    @Column(name = "entity_name", nullable = false, length = 100)
    private String entityName;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", length = 20)
    private EntityType entityType = EntityType.SUBSIDIARY;

    @Column(name = "credit_limit", precision = 18, scale = 2)
    private BigDecimal creditLimit = BigDecimal.ZERO;

    @Column(name = "current_exposure", precision = 18, scale = 2)
    private BigDecimal currentExposure = BigDecimal.ZERO;

    @Column(name = "available_limit", precision = 18, scale = 2)
    private BigDecimal availableLimit = BigDecimal.ZERO;

    @Column(name = "lending_rate_spread", precision = 8, scale = 4)
    private BigDecimal lendingRateSpread = BigDecimal.ZERO;

    @Column(name = "borrowing_rate_spread", precision = 8, scale = 4)
    private BigDecimal borrowingRateSpread = BigDecimal.ZERO;

    @Column(name = "contact_name", length = 100)
    private String contactName;

    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private EntityStatus status = EntityStatus.ACTIVE;

    public enum EntityType {
        HEADQUARTERS, SUBSIDIARY, BRANCH
    }

    public enum EntityStatus {
        ACTIVE, INACTIVE, SUSPENDED
    }
}
