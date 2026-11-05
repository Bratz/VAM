package com.bank.vam.fx.exposure;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The share of a net foreign-currency exposure a corporate's policy wants hedged for one time bucket.
 * {@code currency} is "ANY" for the default band; a specific currency's row overrides it. Ratios are
 * fractions (0.75 = 75%) and may exceed 1 to tolerate a deliberate over-hedge.
 */
@Entity
@Table(name = "fx_policy_band", uniqueConstraints = @UniqueConstraint(
        name = "fx_policy_band_unique", columnNames = {"corporate_id", "currency", "bucket"}))
@Getter
@Setter
@NoArgsConstructor
public class FxPolicyBand extends BaseEntity {

    public static final String ANY = "ANY";

    @Column(name = "corporate_id", nullable = false)
    private UUID corporateId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = ANY;

    @Enumerated(EnumType.STRING)
    @Column(name = "bucket", nullable = false, length = 20)
    private ExposureBucket bucket;

    @Column(name = "min_hedge_ratio", nullable = false, precision = 5, scale = 4)
    private BigDecimal minHedgeRatio;

    @Column(name = "max_hedge_ratio", nullable = false, precision = 5, scale = 4)
    private BigDecimal maxHedgeRatio;
}
