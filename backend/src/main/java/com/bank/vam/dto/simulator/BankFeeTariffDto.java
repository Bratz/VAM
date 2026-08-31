package com.bank.vam.dto.simulator;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Wire shape for an effective bank-fee tariff. Consumed client-side by the
 * Phase-2 Bank-fees score formula; {@code source} is shown in the
 * "View assumptions" drawer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BankFeeTariffDto {
    private String bankCode;
    private String paymentRail;
    private String feeCurrency;
    private BigDecimal feeFixed;
    private BigDecimal feeBps;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private String source;
}
