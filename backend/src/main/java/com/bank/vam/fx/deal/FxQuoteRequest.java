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
 * A corporate's request to deal, and the dealer's firm price for it. {@code amount} is always in the
 * pair's base currency. The mid, forward points and spread are kept beside the all-in rate so the
 * price can be reproduced and explained: the client sees what the bank charged over the market.
 */
@Entity
@Table(name = "fx_quote_request", indexes = @Index(name = "idx_fx_quote_request_corporate", columnList = "corporate_id"))
@Getter
@Setter
@NoArgsConstructor
public class FxQuoteRequest extends BaseEntity {

    @Column(name = "corporate_id", nullable = false)
    private UUID corporateId;

    /** The legal entity the deal is for; its FX sub-limit and the group line must both have room. */
    @Column(name = "entity_id")
    private UUID entityId;

    /** The entity's name when the request was made, for the confirmation and the log. */
    @Column(name = "entity_name", length = 200)
    private String entityName;

    @Column(name = "pair", nullable = false, length = 7)
    private String pair;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 4)
    private Side side;

    @Enumerated(EnumType.STRING)
    @Column(name = "instrument", nullable = false, length = 10)
    private Instrument instrument;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    /** Spot date for SPOT, the contracted date for FORWARD, the last drawdown date for WINDOW. */
    @Column(name = "value_date", nullable = false)
    private LocalDate valueDate;

    @Column(name = "window_start")
    private LocalDate windowStart;

    @Column(name = "purpose", length = 200)
    private String purpose;

    /** The exposure this hedges, as "CCY|BUCKET" (for example GBP|D0_30), so a hedge traces to its cause. */
    @Column(name = "exposure_ref", length = 60)
    private String exposureRef;

    @Column(name = "requested_by", length = 100)
    private String requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private QuoteStatus status = QuoteStatus.REQUESTED;

    @Column(name = "status_reason", length = 300)
    private String statusReason;

    @Column(name = "mid_rate", precision = 19, scale = 8)
    private BigDecimal midRate;

    /** In pips of the pair (a pip is 0.0001, or 0.01 for yen and rupee quotes). */
    @Column(name = "forward_points", precision = 14, scale = 2)
    private BigDecimal forwardPoints;

    @Column(name = "spread_bps", precision = 8, scale = 2)
    private BigDecimal spreadBps;

    @Column(name = "client_rate", precision = 19, scale = 8)
    private BigDecimal clientRate;

    /** What the client pays or receives in the pair's quote currency at the client rate. */
    @Column(name = "counter_amount", precision = 18, scale = 2)
    private BigDecimal counterAmount;

    @Column(name = "dealer", length = 30)
    private String dealer;

    /**
     * What a counter-offer needs. The forward mid and opening spread fix the desk's floor and let it re-price;
     * the round limit is that of the moment of the request. All nullable: a quote from before negotiation
     * existed has none and cannot be negotiated.
     */
    @Column(name = "forward_mid", precision = 19, scale = 8)
    private BigDecimal forwardMid;

    @Column(name = "opening_spread_bps", precision = 8, scale = 2)
    private BigDecimal openingSpreadBps;

    /** Decimal places the desk quotes this pair to; the stored forward mid is wider than that. */
    @Column(name = "rate_scale")
    private Integer rateScale;

    @Column(name = "max_rounds")
    private Integer maxRounds;

    @Column(name = "negotiation_rounds")
    private Integer negotiationRounds;

    /** True once the desk has said this is as good as it gets. */
    @Column(name = "best_price")
    private Boolean bestPrice;

    public int roundsUsed() {
        return negotiationRounds == null ? 0 : negotiationRounds;
    }

    public int roundsAllowed() {
        return forwardMid == null || openingSpreadBps == null || maxRounds == null ? 0 : maxRounds;
    }

    @Column(name = "quoted_at")
    private LocalDateTime quotedAt;

    @Column(name = "valid_until")
    private LocalDateTime validUntil;

    /** Credit-limit use this deal would add, in the limit's currency, as shown at quote time. */
    @Column(name = "limit_charge", precision = 18, scale = 2)
    private BigDecimal limitCharge;

    @Column(name = "limit_currency", length = 3)
    private String limitCurrency;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Column(name = "deal_id")
    private UUID dealId;

    public String baseCurrency() {
        return pair.substring(0, 3);
    }

    public String quoteCurrency() {
        return pair.substring(4, 7);
    }
}
