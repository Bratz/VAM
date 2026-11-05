package com.bank.vam.fx.deal;

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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The settlement of a deal, or of part of one, recorded against the deal. This is a record only: no
 * account balance is moved. The funds check reads the cash the corporate holds, but a settled record
 * does not reduce it, so the figures here say what would settle, not what has been paid.
 */
@Entity
@Table(name = "fx_settlement", indexes = @Index(name = "idx_fx_settlement_deal", columnList = "deal_id"))
@Getter
@Setter
@NoArgsConstructor
public class FxSettlement extends BaseEntity {

    @Column(name = "deal_id", nullable = false)
    private UUID dealId;

    @Column(name = "corporate_id", nullable = false)
    private UUID corporateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 10)
    private SettlementKind kind;

    @Column(name = "value_date", nullable = false)
    private LocalDate valueDate;

    @Column(name = "buy_currency", nullable = false, length = 3)
    private String buyCurrency;

    @Column(name = "buy_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal buyAmount;

    @Column(name = "sell_currency", nullable = false, length = 3)
    private String sellCurrency;

    @Column(name = "sell_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal sellAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private SettlementStatus status = SettlementStatus.PENDING;

    @Column(name = "reason", length = 300)
    private String reason;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;
}
