package com.bank.vam.entity.treasury;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/**
 * A single bank-holiday date for a given clearing calendar.
 *
 * Calendars are keyed by short codes — typically "{CCY}-{CLEARING}", e.g.
 * "USD-CHIPS", "EUR-TARGET2", "GBP-CHAPS", "AED-CB". The lookup service
 * {@code CutoffCalendarService} resolves a (currency, rail) → calendar code
 * and then checks whether a candidate date is a holiday.
 *
 * v2 ships a static seed; later phases can wire an external feed
 * (e.g. SWIFT calendar file) by replacing the initialiser.
 */
@Entity
@Table(name = "bank_holidays", indexes = {
        @Index(name = "idx_holiday_cal_date", columnList = "calendar_code,holiday_date", unique = true),
        @Index(name = "idx_holiday_date", columnList = "holiday_date")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankHoliday extends BaseEntity {

    @Column(name = "calendar_code", nullable = false, length = 20)
    private String calendarCode;

    @Column(name = "holiday_date", nullable = false)
    private LocalDate holidayDate;

    @Column(name = "description", length = 200)
    private String description;
}
