package com.bank.vam.dto.treasury;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Read-model DTOs for the multi-bank liquidity dashboard.
 *
 * Produced by {@code MultiBankLiquidityViewService}. The view aggregates
 * PHYSICAL_MIRROR shadows by (legal-entity, bank, currency) and distinguishes
 * home-bank-held (pool-eligible) from external (sweep-source-only) shadows.
 */
public class MultiBankLiquidityDto {

    @Data
    @Builder
    public static class LiquiditySummary {
        private String homeBankBic;
        private String homeBankName;
        private int totalShadows;
        private int homeBankShadows;
        private int externalShadows;
        private int staleCount;
        private int neverRefreshedCount;
        private List<BankBucket> banks;
    }

    /** Aggregation by bank (BIC) — one bucket per external + one for home bank. */
    @Data
    @Builder
    public static class BankBucket {
        private String bankBic;
        private String bankName;
        private boolean homeBank;
        private int shadowCount;
        private List<CurrencyBucket> currencies;
    }

    /** Aggregation by currency within a bank. */
    @Data
    @Builder
    public static class CurrencyBucket {
        private String currencyCode;
        private int shadowCount;
        private BigDecimal totalBankBalance;
        private BigDecimal totalCommitted;
        private BigDecimal totalEffective;
        private List<ShadowSummary> shadows;
    }

    /** Single shadow row in the dashboard. */
    @Data
    @Builder
    public static class ShadowSummary {
        private String vaId;
        private String vaNumber;
        private String vaName;
        private String currencyCode;
        private String bankBic;
        private String bankName;
        private String bankAccountNumber;
        private String bankIban;
        private boolean homeBankHeld;
        private String owningEntityCode;
        private BigDecimal bankBalance;
        private BigDecimal bankAvailableBalance;
        private BigDecimal bankBalanceCommitted;
        private BigDecimal bankBalanceEffective;
        private String balanceDataSource;
        private LocalDateTime lastBalanceRefreshAt;
        private String lastBalanceRefreshStatus;
        private boolean stale;
    }
}
