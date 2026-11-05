package com.bank.vam.fx.deal;

import com.bank.vam.service.audit.AuditLogService;
import org.junit.jupiter.api.Test;

import static com.bank.vam.fx.deal.DealFixtures.deal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class FxEventsTest {

    private final FxDealEventRepository repo = mock(FxDealEventRepository.class);
    private final FxEvents events = new FxEvents(repo, mock(AuditLogService.class));
    private final FxDeal deal = deal(Side.BUY, Instrument.FORWARD, DealStatus.ACTIVE, java.time.LocalDate.of(2027, 2, 9));

    @Test
    void everyActionCodeTheServicesUseFitsTheTwentyCharacterColumn() {
        // Each code the services pass to note() today. A longer one failed in production only: the column
        // already existed at 20 and ddl-auto does not widen it.
        for (String code : new String[] {"CONFIRMATION_SENT", "CONFIRMED_BY_CLIENT", "DISPUTED_BY_CLIENT", "REISSUED_BY_BANK",
                "DRAWDOWN_SCHEDULED", "SETTLED_MATURITY", "SETTLED_DRAWDOWN", "SETTLED_CLOSE_OUT", "ROLLED_INTO"}) {
            assertThat(code.length()).as(code).isLessThanOrEqualTo(FxEvents.MAX_ACTION_LENGTH);
            assertThatCode(() -> events.note(deal, code, "x")).doesNotThrowAnyException();
        }
        verify(repo, org.mockito.Mockito.times(9)).save(any(FxDealEvent.class));
    }

    @Test
    void anOverLongCodeIsRefusedUpFrontInsteadOfFailingInTheDatabase() {
        assertThatThrownBy(() -> events.note(deal, "CONFIRMATION_DISPUTED", "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("longer than 20");
    }
}
