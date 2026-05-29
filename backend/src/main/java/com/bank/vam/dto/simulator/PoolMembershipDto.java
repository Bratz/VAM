package com.bank.vam.dto.simulator;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Notional-pool membership for a physical account, resolved via its live
 * PHYSICAL_MIRROR Shadow VA. Drives the Simulator's interest-yield Pool
 * basket (R2). `interestRate` is RAW from `notional_pools` — the frontend
 * normalises (>1 ⇒ %, ≤1 ⇒ fraction×100) and discloses the rule.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PoolMembershipDto {
    private UUID physicalAccountId;
    private String poolReference;
    private String poolCurrency;
    private BigDecimal interestRate;
}
