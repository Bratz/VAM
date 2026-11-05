package com.bank.vam.fx.deal;

import com.bank.vam.entity.BaseEntity;
import com.bank.vam.fx.exposure.FxExposureEntry;
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
import java.util.List;
import java.util.UUID;

/**
 * A booked trade, stored as its two legs: what the corporate buys and what it sells. Rate, mid, points
 * and spread are copied from the accepted quote so the deal never depends on the quote row.
 */
@Entity
@Table(name = "fx_deal", indexes = @Index(name = "idx_fx_deal_corporate", columnList = "corporate_id"))
@Getter
@Setter
@NoArgsConstructor
public class FxDeal extends BaseEntity {

    @Column(name = "deal_ref", nullable = false, unique = true, length = 30)
    private String dealRef;

    @Column(name = "corporate_id", nullable = false)
    private UUID corporateId;

    @Column(name = "quote_request_id", nullable = false, unique = true)
    private UUID quoteRequestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "instrument", nullable = false, length = 10)
    private Instrument instrument;

    @Column(name = "pair", nullable = false, length = 7)
    private String pair;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 4)
    private Side side;

    @Column(name = "buy_currency", nullable = false, length = 3)
    private String buyCurrency;

    @Column(name = "buy_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal buyAmount;

    @Column(name = "sell_currency", nullable = false, length = 3)
    private String sellCurrency;

    @Column(name = "sell_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal sellAmount;

    @Column(name = "rate", nullable = false, precision = 19, scale = 8)
    private BigDecimal rate;

    @Column(name = "mid_rate", precision = 19, scale = 8)
    private BigDecimal midRate;

    @Column(name = "forward_points", precision = 14, scale = 2)
    private BigDecimal forwardPoints;

    @Column(name = "spread_bps", precision = 8, scale = 2)
    private BigDecimal spreadBps;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    /** Spot date, contracted date, or for a window the last date it can be drawn. */
    @Column(name = "value_date", nullable = false)
    private LocalDate valueDate;

    @Column(name = "window_start")
    private LocalDate windowStart;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DealStatus status = DealStatus.BOOKED;

    @Column(name = "limit_charge", nullable = false, precision = 18, scale = 2)
    private BigDecimal limitCharge;

    @Column(name = "limit_currency", nullable = false, length = 3)
    private String limitCurrency;

    @Column(name = "purpose", length = 200)
    private String purpose;

    @Column(name = "exposure_ref", length = 60)
    private String exposureRef;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    /**
     * The deal as the hedge positions it creates, in the shape the exposure ladder reads: the deal's
     * own position in each currency (buying EUR is +EUR, paying USD for it is -USD), dated by when it
     * settles. A window counts at its last date, the conservative end.
     */
    public List<FxExposureEntry> hedgePositions() {
        return List.of(position(buyCurrency, buyAmount), position(sellCurrency, sellAmount.negate()));
    }

    private FxExposureEntry position(String currency, BigDecimal amount) {
        FxExposureEntry e = new FxExposureEntry();
        e.setCorporateId(corporateId);
        e.setKind(FxExposureEntry.Kind.HEDGE);
        e.setCurrency(currency);
        e.setAmount(amount);
        e.setValueDate(valueDate);
        e.setReference(dealRef);
        e.setSource("DEAL");
        return e;
    }
}
