package com.bank.vam.fx.deal;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A corporate's rate alert: tell me when the indicative market rate of a pair reaches a level. The direction is set
 * from where the rate was when the alert was made (a target above it fires when the rate rises to it). Fires once.
 */
@Entity
@Table(name = "fx_rate_alert")
@Getter
@Setter
@NoArgsConstructor
public class FxRateAlert extends BaseEntity {

    public enum Direction { ABOVE, BELOW }

    public enum Status { ACTIVE, TRIGGERED, CANCELLED }

    @Column(name = "corporate_id", nullable = false)
    private UUID corporateId;

    @Column(name = "pair", nullable = false, length = 7)
    private String pair;

    @Column(name = "target_rate", nullable = false, precision = 19, scale = 8)
    private BigDecimal targetRate;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10)
    private Direction direction;

    /** The rate when the alert was set, for the message. */
    @Column(name = "rate_at_creation", precision = 19, scale = 8)
    private BigDecimal rateAtCreation;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private Status status = Status.ACTIVE;

    @Column(name = "note", length = 200)
    private String note;

    @Column(name = "triggered_at")
    private LocalDateTime triggeredAt;

    @Column(name = "triggered_rate", precision = 19, scale = 8)
    private BigDecimal triggeredRate;
}
