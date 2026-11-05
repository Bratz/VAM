package com.bank.vam.fx.deal;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A deal's mark-to-market on one day, kept so its value can be followed over time. One row per deal per day. */
@Entity
@Table(name = "fx_mtm", uniqueConstraints = @UniqueConstraint(name = "fx_mtm_deal_day", columnNames = {"deal_id", "as_of"}))
@Getter
@Setter
@NoArgsConstructor
public class FxMtm extends BaseEntity {

    @Column(name = "deal_id", nullable = false)
    private UUID dealId;

    @Column(name = "corporate_id", nullable = false)
    private UUID corporateId;

    @Column(name = "as_of", nullable = false)
    private LocalDate asOf;

    /** The market forward mid for the deal's remaining term on that day. */
    @Column(name = "rate_used", nullable = false, precision = 19, scale = 8)
    private BigDecimal rateUsed;

    @Column(name = "contracted_rate", nullable = false, precision = 19, scale = 8)
    private BigDecimal contractedRate;

    /** In the pair's quote currency: positive is in the corporate's favour. */
    @Column(name = "mtm_quote", nullable = false, precision = 18, scale = 2)
    private BigDecimal mtmQuote;

    @Column(name = "quote_currency", nullable = false, length = 3)
    private String quoteCurrency;

    /** In the corporate's home currency at the stored platform rate; absent when there is no such rate. */
    @Column(name = "mtm_home", precision = 18, scale = 2)
    private BigDecimal mtmHome;

    @Column(name = "home_currency", length = 3)
    private String homeCurrency;
}
