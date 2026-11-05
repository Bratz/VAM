package com.bank.vam.fx.deal;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/** One step in the life of a quote request or a deal: who moved it, from where to where, and why. */
@Entity
@Table(name = "fx_deal_event", indexes = @Index(name = "idx_fx_deal_event_entity", columnList = "entity_id"))
@Getter
@Setter
@NoArgsConstructor
public class FxDealEvent extends BaseEntity {

    public static final String QUOTE = "QUOTE";
    public static final String DEAL = "DEAL";

    @Column(name = "entity_type", nullable = false, length = 10)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "corporate_id", nullable = false)
    private UUID corporateId;

    @Column(name = "from_status", length = 20)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 20)
    private String toStatus;

    @Column(name = "actor", length = 100)
    private String actor;

    @Column(name = "detail", length = 400)
    private String detail;
}
