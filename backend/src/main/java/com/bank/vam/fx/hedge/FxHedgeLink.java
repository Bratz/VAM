package com.bank.vam.fx.hedge;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An FX deal hedges (part of) one invoice: {@code amount} in the invoice's currency. Only what was agreed is stored;
 * what the link is still worth (the invoice may since have been paid, reduced or cancelled) is worked out on read
 * ({@link FxHedgeLinkService}), so no invoice writer has to remember it. Unlinking deletes the row (audited).
 */
@Entity
@Table(name = "fx_hedge_link", indexes = {
        @Index(name = "idx_fx_hedge_link_deal", columnList = "deal_id"),
        @Index(name = "idx_fx_hedge_link_invoice", columnList = "invoice_id")})
@Getter
@Setter
@NoArgsConstructor
public class FxHedgeLink extends BaseEntity {

    public enum Kind {
        PAYABLE,
        RECEIVABLE
    }

    @Column(name = "corporate_id", nullable = false)
    private UUID corporateId;

    @Column(name = "deal_id", nullable = false)
    private UUID dealId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 10)
    private Kind kind;

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "linked_by", length = 100)
    private String linkedBy;

    @Column(name = "linked_at", nullable = false)
    private LocalDateTime linkedAt;
}
