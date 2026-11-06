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
 * A corporate's rate alert, on its own price: "tell me when I can buy EUR at or below X" (side BUY, direction
 * BELOW) or "sell EUR at or above X" (side SELL, direction ABOVE), at an amount of the base currency (the spread
 * depends on it). Never on the interbank mid, which corporate screens do not show. Fires once.
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

    /** The side of the client's price watched: BUY the base currency, or SELL it. */
    @Enumerated(EnumType.STRING)
    @Column(name = "side", length = 4)
    private Side side;

    /** The amount of the base currency the price is for. */
    @Column(name = "amount", precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "target_rate", nullable = false, precision = 19, scale = 8)
    private BigDecimal targetRate;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10)
    private Direction direction;

    /** The client's price on that side when the alert was set. */
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
