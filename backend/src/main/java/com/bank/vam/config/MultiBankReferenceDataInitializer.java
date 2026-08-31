package com.bank.vam.config;

import com.bank.vam.entity.treasury.BankHoliday;
import com.bank.vam.entity.treasury.CurrencyCutoffConfig;
import com.bank.vam.entity.treasury.RejectionCodeConfig;
import com.bank.vam.entity.treasury.SweepInstruction.RejectionCategory;
import com.bank.vam.entity.treasury.SweepRule.Rail;
import com.bank.vam.repository.treasury.BankHolidayRepository;
import com.bank.vam.repository.treasury.CurrencyCutoffConfigRepository;
import com.bank.vam.repository.treasury.RejectionCodeConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Year;
import java.util.List;
import java.util.Map;

/**
 * Seeds the v2 reference tables on startup if empty:
 *  - Currency × rail cut-off times.
 *  - Bank holidays per clearing calendar (current + next year, fixed-date holidays only).
 *  - ISO 20022 rejection-code → category mapping.
 *
 * Idempotent: skips any row whose key already exists. Re-runs safely on every boot.
 */
@Slf4j
@Component
@Order(110)
@RequiredArgsConstructor
public class MultiBankReferenceDataInitializer implements ApplicationRunner {

    private final CurrencyCutoffConfigRepository cutoffRepository;
    private final BankHolidayRepository holidayRepository;
    private final RejectionCodeConfigRepository rejectionRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedCutoffs();
        seedHolidays();
        seedRejectionCodes();
    }

    // ------------------------------------------------------------------------
    // Cut-offs
    // ------------------------------------------------------------------------
    private void seedCutoffs() {
        int created = 0;
        for (CutoffSeed s : CUTOFF_SEEDS) {
            if (cutoffRepository.findByCurrencyCodeAndRail(s.ccy, s.rail).isPresent()) continue;
            cutoffRepository.save(CurrencyCutoffConfig.builder()
                    .currencyCode(s.ccy)
                    .rail(s.rail)
                    .cutoffTime(s.time)
                    .timezone(s.zone)
                    .warningOffsetMinutes(30)
                    .build());
            created++;
        }
        if (created > 0) log.info("Seeded {} currency cut-off configs", created);
    }

    private static final List<CutoffSeed> CUTOFF_SEEDS = List.of(
            new CutoffSeed("USD", Rail.SWIFT_MT103,   LocalTime.of(21, 0), "America/New_York"),
            new CutoffSeed("USD", Rail.SWIFT_PACS008, LocalTime.of(21, 0), "America/New_York"),
            new CutoffSeed("EUR", Rail.SEPA_SCT,      LocalTime.of(16, 0), "Europe/Brussels"),
            new CutoffSeed("EUR", Rail.SEPA_INST,     LocalTime.of(23, 59), "Europe/Brussels"),
            new CutoffSeed("EUR", Rail.SWIFT_MT103,   LocalTime.of(17, 0), "Europe/Brussels"),
            new CutoffSeed("GBP", Rail.SWIFT_MT103,   LocalTime.of(16, 30), "Europe/London"),
            new CutoffSeed("AED", Rail.SWIFT_MT103,   LocalTime.of(15, 0), "Asia/Dubai"),
            new CutoffSeed("SAR", Rail.SWIFT_MT103,   LocalTime.of(15, 0), "Asia/Riyadh"),
            new CutoffSeed("CHF", Rail.SWIFT_MT103,   LocalTime.of(15, 0), "Europe/Zurich"),
            new CutoffSeed("JPY", Rail.SWIFT_MT103,   LocalTime.of(14, 0), "Asia/Tokyo")
    );

    private record CutoffSeed(String ccy, Rail rail, LocalTime time, String zone) {}

    // ------------------------------------------------------------------------
    // Bank holidays — fixed-date only (movable feasts left for an external feed)
    // ------------------------------------------------------------------------
    private void seedHolidays() {
        int created = 0;
        int year = Year.now().getValue();
        for (int y : new int[]{year, year + 1}) {
            for (Map.Entry<String, List<HolidayDate>> e : HOLIDAYS_BY_CALENDAR.entrySet()) {
                String cal = e.getKey();
                for (HolidayDate h : e.getValue()) {
                    LocalDate d = LocalDate.of(y, h.month, h.day);
                    if (holidayRepository.existsByCalendarCodeAndHolidayDate(cal, d)) continue;
                    holidayRepository.save(BankHoliday.builder()
                            .calendarCode(cal)
                            .holidayDate(d)
                            .description(h.description)
                            .build());
                    created++;
                }
            }
        }
        if (created > 0) log.info("Seeded {} bank holidays (fixed-date only)", created);
    }

    private record HolidayDate(int month, int day, String description) {}

    private static final Map<String, List<HolidayDate>> HOLIDAYS_BY_CALENDAR = Map.of(
            "USD-CHIPS", List.of(
                    new HolidayDate(1, 1, "New Year's Day"),
                    new HolidayDate(7, 4, "Independence Day"),
                    new HolidayDate(12, 25, "Christmas Day")
            ),
            "EUR-TARGET2", List.of(
                    new HolidayDate(1, 1, "New Year's Day"),
                    new HolidayDate(5, 1, "Labour Day"),
                    new HolidayDate(12, 25, "Christmas Day"),
                    new HolidayDate(12, 26, "St Stephen's Day")
            ),
            "GBP-CHAPS", List.of(
                    new HolidayDate(1, 1, "New Year's Day"),
                    new HolidayDate(12, 25, "Christmas Day"),
                    new HolidayDate(12, 26, "Boxing Day")
            ),
            "AED-CB", List.of(
                    new HolidayDate(1, 1, "New Year's Day"),
                    new HolidayDate(12, 2, "UAE National Day"),
                    new HolidayDate(12, 3, "UAE National Day +1")
            ),
            "CHF-SIC", List.of(
                    new HolidayDate(1, 1, "New Year's Day"),
                    new HolidayDate(8, 1, "Swiss National Day"),
                    new HolidayDate(12, 25, "Christmas Day")
            ),
            "JPY-BOJNET", List.of(
                    new HolidayDate(1, 1, "New Year's Day"),
                    new HolidayDate(2, 11, "National Foundation Day"),
                    new HolidayDate(5, 3, "Constitution Memorial Day")
            )
    );

    // ------------------------------------------------------------------------
    // Rejection codes
    // ------------------------------------------------------------------------
    private void seedRejectionCodes() {
        int created = 0;
        for (RejectionSeed r : REJECTION_SEEDS) {
            if (rejectionRepository.findByCode(r.code).isPresent()) continue;
            rejectionRepository.save(RejectionCodeConfig.builder()
                    .code(r.code)
                    .category(r.category)
                    .description(r.description)
                    .build());
            created++;
        }
        if (created > 0) log.info("Seeded {} rejection-code classifications", created);
    }

    private record RejectionSeed(String code, RejectionCategory category, String description) {}

    private static final List<RejectionSeed> REJECTION_SEEDS = List.of(
            // UNRECOVERABLE — pause the rule
            new RejectionSeed("AC04", RejectionCategory.UNRECOVERABLE, "Account closed"),
            new RejectionSeed("AC06", RejectionCategory.UNRECOVERABLE, "Account blocked"),
            new RejectionSeed("AC01", RejectionCategory.UNRECOVERABLE, "Incorrect account number"),
            new RejectionSeed("MD07", RejectionCategory.UNRECOVERABLE, "End customer deceased"),
            new RejectionSeed("MS03", RejectionCategory.UNRECOVERABLE, "Reason not specified by agent (mandate revoked)"),
            new RejectionSeed("AG01", RejectionCategory.UNRECOVERABLE, "Transaction forbidden"),
            new RejectionSeed("AG02", RejectionCategory.UNRECOVERABLE, "Invalid bank operation code"),
            new RejectionSeed("FF01", RejectionCategory.UNRECOVERABLE, "Invalid file format"),
            new RejectionSeed("BE04", RejectionCategory.UNRECOVERABLE, "Missing creditor address"),
            new RejectionSeed("MD01", RejectionCategory.UNRECOVERABLE, "No mandate"),
            new RejectionSeed("MD02", RejectionCategory.UNRECOVERABLE, "Missing mandate information"),
            // RECOVERABLE — retry with backoff
            new RejectionSeed("AM04", RejectionCategory.RECOVERABLE, "Insufficient funds"),
            new RejectionSeed("AM05", RejectionCategory.RECOVERABLE, "Duplicate payment"),
            new RejectionSeed("AM18", RejectionCategory.RECOVERABLE, "Invalid number of transactions"),
            new RejectionSeed("CUT_OFF_MISSED", RejectionCategory.RECOVERABLE, "Cut-off missed at source"),
            new RejectionSeed("RAIL_TIMEOUT", RejectionCategory.RECOVERABLE, "Rail adapter timed out"),
            new RejectionSeed("TM01", RejectionCategory.RECOVERABLE, "Cut-off time")
    );
}
