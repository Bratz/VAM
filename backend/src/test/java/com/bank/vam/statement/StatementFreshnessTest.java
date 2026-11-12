package com.bank.vam.statement;

import com.bank.vam.config.MarketProfile;
import com.bank.vam.config.MarketProfileProperties;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.BalanceDataSource;
import com.bank.vam.repository.treasury.BankHolidayRepository;
import com.bank.vam.repository.treasury.CurrencyCutoffConfigRepository;
import com.bank.vam.service.treasury.cutoff.CutoffCalendarService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/** D6 / acceptance 6: stale = no applied end-of-day statement for the last business day (weekends skipped). */
class StatementFreshnessTest {

    private static final LocalDate MON = LocalDate.of(2026, 11, 16), FRI = MON.minusDays(3), THU = MON.minusDays(4);

    private final BankStatementRepository statements = mock(BankStatementRepository.class);
    private final BankHolidayRepository holidays = mock(BankHolidayRepository.class);
    private final StatementFreshness freshness;
    private final List<Object[]> latest = new ArrayList<>();

    StatementFreshnessTest() {
        MarketProfileProperties market = mock(MarketProfileProperties.class);
        when(market.getActiveWeekend()).thenReturn(MarketProfile.Weekend.SAT_SUN);
        freshness = new StatementFreshness(statements,
                new CutoffCalendarService(mock(CurrencyCutoffConfigRepository.class), holidays, market));
        when(statements.latestDates(anyCollection(), any(), any())).thenReturn(latest);
    }

    private VirtualAccount shadow(BalanceDataSource source, LocalDate lastStatement) {
        VirtualAccount s = VirtualAccount.builder().currencyCode("GBP").balanceDataSource(source).build();
        s.setId(UUID.randomUUID());
        if (lastStatement != null) latest.add(new Object[]{s.getId(), lastStatement});
        return s;
    }

    @Test
    void fridayStatementSeenOnMondayIsFresh() {
        StatementFreshness.Freshness f = freshness.of(shadow(BalanceDataSource.SWIFT_MT940, FRI), MON);
        assertFalse(f.stale());
        assertEquals(FRI, f.lastStatementDate());
    }

    @Test
    void thursdayStatementSeenOnMondayIsStale() {
        assertTrue(freshness.of(shadow(BalanceDataSource.HOST_TO_HOST, THU), MON).stale());
    }

    @Test
    void noStatementEverIsStale() {
        StatementFreshness.Freshness f = freshness.of(shadow(BalanceDataSource.SWIFT_MT942, null), MON);
        assertTrue(f.stale());
        assertNull(f.lastStatementDate());
    }

    @Test
    void bankHolidayIsSkippedToo() {
        when(holidays.existsByCalendarCodeAndHolidayDate("GBP-CHAPS", FRI)).thenReturn(true);
        assertFalse(freshness.of(shadow(BalanceDataSource.SWIFT_MT940, THU), MON).stale());
    }

    @Test
    void otherSourcesAreNotJudgedByStatements() {
        assertNull(freshness.of(shadow(BalanceDataSource.CORE_BANKING, null), MON));
        assertNull(freshness.of(shadow(BalanceDataSource.OPEN_BANKING, null), MON));
        verifyNoInteractions(statements);
    }
}
