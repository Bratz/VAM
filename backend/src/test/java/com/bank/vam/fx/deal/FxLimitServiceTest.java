package com.bank.vam.fx.deal;

import com.bank.vam.config.FxDealProperties;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.fx.HomeCurrency;
import com.bank.vam.service.treasury.FxRateService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FxLimitServiceTest {

    private final FxDealProperties props = new FxDealProperties();
    private final FxRateService rates = mock(FxRateService.class);
    private final FxLimitService service = new FxLimitService(
            mock(FxCreditLimitRepository.class), mock(FxDealRepository.class), mock(HomeCurrency.class), rates, props);

    private static final LocalDate TRADE = LocalDate.of(2026, 11, 5);

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void theCreditFactorStepsUpWithDaysToMaturityAndStaysAtTheLastBand() {
        assertThat(FxLimitService.factor(props.getPfeBands(), 0)).isEqualByComparingTo("0.01");
        assertThat(FxLimitService.factor(props.getPfeBands(), 7)).isEqualByComparingTo("0.01");
        assertThat(FxLimitService.factor(props.getPfeBands(), 8)).isEqualByComparingTo("0.03");
        assertThat(FxLimitService.factor(props.getPfeBands(), 92)).isEqualByComparingTo("0.06");
        assertThat(FxLimitService.factor(props.getPfeBands(), 183)).isEqualByComparingTo("0.10");
        assertThat(FxLimitService.factor(props.getPfeBands(), 366)).isEqualByComparingTo("0.15");
        assertThat(FxLimitService.factor(props.getPfeBands(), 2000)).isEqualByComparingTo("0.15");
    }

    @Test
    void chargeIsNotionalInTheLimitCurrencyTimesTheFactor() {
        when(rates.getRate("EUR", "AED")).thenReturn(bd("4.00"));

        // EUR 1,000,000 = AED 4,000,000, 92 days out => 6%.
        assertThat(service.charge("EUR", bd("1000000"), TRADE, TRADE.plusDays(92), "AED")).isEqualByComparingTo("240000.00");
    }

    @Test
    void aDealInTheLimitCurrencyNeedsNoRate() {
        assertThat(service.charge("AED", bd("1000000"), TRADE, TRADE.plusDays(2), "AED")).isEqualByComparingTo("10000.00");
        verifyNoInteractions(rates);
    }

    @Test
    void aDealThatFitsPassesAndOneThatDoesNotSaysWhatIsLeft() {
        FxLimitService.Position p = new FxLimitService.Position(UUID.randomUUID(), "AED", bd("1000000"), bd("900000"), bd("100000"));

        assertThatCode(() -> service.require(p, bd("100000"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.require(p, bd("100000.01")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("100,000.01 AED of limit")
                .hasMessageContaining("100,000.00 AED is left");
    }
}
