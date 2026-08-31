package com.bank.vam.entity.ai;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Persisted balance alert created via Copilot's {@code SET_ALERT} action.
 *
 * <p>Demo scope: just stores the rule. A real implementation would have a
 * worker periodically evaluate active alerts against the linked account's
 * balance and fire notifications when the threshold is crossed. For the
 * prototype this entity exists so users can see their alert was recorded —
 * the audit log row plus a row in this table is the demo's "proof of life".
 */
@Entity
@Table(name = "balance_alert", indexes = {
        @Index(name = "idx_alert_va", columnList = "va_id"),
        @Index(name = "idx_alert_active", columnList = "active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BalanceAlert extends BaseEntity {

    @Column(name = "va_id", nullable = false)
    private UUID vaId;

    /** Denormalised for display — VAs are immutable enough in this scope. */
    @Column(name = "va_number", length = 50)
    private String vaNumber;

    @Column(name = "va_name", length = 200)
    private String vaName;

    @Column(name = "currency_code", length = 3)
    private String currencyCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", length = 10, nullable = false)
    private Direction direction;

    @Column(name = "threshold", precision = 19, scale = 4, nullable = false)
    private BigDecimal threshold;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

    /** Traceability — which conversation created this alert. */
    @Column(name = "created_by_conversation_id")
    private UUID createdByConversationId;

    public enum Direction { ABOVE, BELOW }
}
