package com.bank.vam.entity.treasury;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One day's captured bank balance for a PHYSICAL_MIRROR shadow account.
 *
 * Written by {@code BalanceRefreshService} on every successful refresh (one
 * row per shadow per day — a same-day re-refresh updates the existing row
 * rather than inserting a duplicate) and backfilled with synthetic demo
 * history by the {@code V18} migration. Feeds the Multi-Bank Liquidity
 * page's historical trend view.
 *
 * {@code currencyCode}/{@code corporateId} are denormalized from the shadow
 * at capture time so the trend query needs no join back to
 * {@code virtual_accounts}.
 */
@Entity
@Table(name = "shadow_balance_snapshot", indexes = {
        @Index(name = "idx_shadow_snapshot_shadow_asof", columnList = "shadow_va_id, as_of", unique = true),
        @Index(name = "idx_shadow_snapshot_corporate_asof", columnList = "corporate_id, as_of"),
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShadowBalanceSnapshot extends BaseEntity {

    @Column(name = "shadow_va_id", nullable = false)
    private UUID shadowVaId;

    @Column(name = "corporate_id")
    private UUID corporateId;

    @Column(name = "currency_code", length = 3)
    private String currencyCode;

    @Column(name = "bank_balance", precision = 19, scale = 4)
    private BigDecimal bankBalance;

    @Column(name = "bank_available_balance", precision = 19, scale = 4)
    private BigDecimal bankAvailableBalance;

    @Column(name = "bank_balance_effective", precision = 19, scale = 4)
    private BigDecimal bankBalanceEffective;

    @Column(name = "as_of", nullable = false)
    private LocalDate asOf;
}
