package com.bank.vam.entity.treasury;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Per-(currency, rail) cut-off time in the source clearing zone.
 *
 * Used by {@code CutoffCalendarService} to decide whether an instruction can
 * still be sent today, or must roll to the next business day.
 *
 * Example rows:
 * <ul>
 *   <li>USD / SWIFT_MT103 / 21:00 / America/New_York</li>
 *   <li>EUR / SEPA_SCT / 16:00 / Europe/Brussels</li>
 *   <li>EUR / SEPA_INST / 23:59 / Europe/Brussels (24/7 in practice; surfaces in UI)</li>
 *   <li>GBP / SWIFT_MT103 / 16:30 / Europe/London</li>
 * </ul>
 */
@Entity
@Table(name = "currency_cutoff_configs", indexes = {
        @Index(name = "idx_cutoff_ccy_rail", columnList = "currency_code,rail", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CurrencyCutoffConfig extends BaseEntity {

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "rail", nullable = false, length = 30)
    private SweepRule.Rail rail;

    @Column(name = "cutoff_time", nullable = false)
    private LocalTime cutoffTime;

    /** IANA zone of the clearing system (e.g. "America/New_York"). */
    @Column(name = "timezone", nullable = false, length = 60)
    private String timezone;

    /** Minutes before cut-off when UI should surface a warning. */
    @Column(name = "warning_offset_minutes")
    @Builder.Default
    private Integer warningOffsetMinutes = 30;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;
}
