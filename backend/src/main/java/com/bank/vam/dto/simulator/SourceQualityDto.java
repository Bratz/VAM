package com.bank.vam.dto.simulator;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Trailing-90d source-quality metrics for one physical account. Consumed
 * client-side by the Phase-4 Source-quality score formula.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourceQualityDto {
    private UUID physicalAccountId;
    /** failed-or-missed / total, 0..1. */
    private double missRate;
    /** mean lag over successful syncs, hours. */
    private double avgLagHours;
    /** sample size (sync events in the window) — disclosed for transparency. */
    private long samples;
}
