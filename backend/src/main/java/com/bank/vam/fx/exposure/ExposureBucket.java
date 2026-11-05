package com.bank.vam.fx.exposure;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Where on the timeline an exposure falls. ON_HAND is cash already held (shown, never policed);
 * the rest are time-to-settlement buckets the hedge policy sets ratios for.
 */
public enum ExposureBucket {
    ON_HAND("On hand", 0),
    D0_30("0-30 days", 30),
    D31_60("31-60 days", 60),
    D61_90("61-90 days", 90),
    D91_180("91-180 days", 180),
    D181_PLUS("181+ days", 365);

    private final String label;
    private final int horizonDays;

    ExposureBucket(String label, int horizonDays) {
        this.label = label;
        this.horizonDays = horizonDays;
    }

    public String label() {
        return label;
    }

    /** Upper edge of the bucket in days from today (365 stands in for "181 and beyond"). */
    public int horizonDays() {
        return horizonDays;
    }

    public boolean hasPolicy() {
        return this != ON_HAND;
    }

    /** Overdue and undated items count as due now: a late receivable or payable settles imminently. */
    public static ExposureBucket forDate(LocalDate date, LocalDate today) {
        long days = date == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(today, date));
        if (days <= 30) return D0_30;
        if (days <= 60) return D31_60;
        if (days <= 90) return D61_90;
        if (days <= 180) return D91_180;
        return D181_PLUS;
    }
}
