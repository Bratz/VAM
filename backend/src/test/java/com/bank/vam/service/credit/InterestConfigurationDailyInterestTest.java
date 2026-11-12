package com.bank.vam.service.credit;

import com.bank.vam.entity.credit.InterestConfiguration;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.credit.InterestConfigurationRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The interest engine a notional pool's daily run uses (spec R7, R8): the debit rate for a debit balance, the
 * config's day count, and banded tiers on a credit balance.
 */
class InterestConfigurationDailyInterestTest {

    private final InterestConfigurationService engine = new InterestConfigurationService(mock(InterestConfigurationRepository.class));

    @Test
    void aDebitBalanceAccruesAtTheDebitRateAndACreditBalanceAtTheCreditRate() {
        InterestConfiguration c = config("3.00", "6.00", "ACT/365");

        // 365,000 × 6% / 365 = 60; × 3% / 365 = 30
        assertThat(engine.calculateDailyInterest(c, new BigDecimal("-365000"))).isEqualByComparingTo("60");
        assertThat(engine.calculateDailyInterest(c, new BigDecimal("365000"))).isEqualByComparingTo("30");
    }

    @Test
    void theDayCountIsTheConfigs() {
        BigDecimal balance = new BigDecimal("3600000");
        assertThat(engine.calculateDailyInterest(config("3.65", "0", "ACT/360"), balance)).isEqualByComparingTo("365");
        assertThat(engine.calculateDailyInterest(config("3.65", "0", "ACT/365"), balance)).isEqualByComparingTo("360");
        assertThat(engine.calculateDailyInterest(config("3.65", "0", "ACT/ACT"), balance)).isEqualByComparingTo("360");
    }

    @Test
    void tiersPriceEachSliceOfACreditBalanceAtItsBandsRate() {
        InterestConfiguration c = config("9.99", "6.00", "ACT/365");
        c.setIsTiered(true);
        c.setTierConfig("[{\"min\":0,\"max\":100000,\"rate\":1.0},{\"min\":100000,\"rate\":2.0}]");

        // 100,000 × 1% + 265,000 × 2% = 6,300 a year → / 365
        assertThat(engine.calculateDailyInterest(c, new BigDecimal("365000")))
                .isEqualByComparingTo(new BigDecimal("6300").divide(new BigDecimal("365"), 4, java.math.RoundingMode.HALF_UP));
        // Debit balances keep the debit rate.
        assertThat(engine.calculateDailyInterest(c, new BigDecimal("-365000"))).isEqualByComparingTo("60");
    }

    @Test
    void theDefaultDayCountFollowsTheCurrency() {
        assertThat(InterestConfigurationService.defaultDayCount("GBP")).isEqualTo("ACT/365");
        assertThat(InterestConfigurationService.defaultDayCount("AED")).isEqualTo("ACT/365");
        assertThat(InterestConfigurationService.defaultDayCount("USD")).isEqualTo("ACT/360");
        assertThat(InterestConfigurationService.defaultDayCount("EUR")).isEqualTo("ACT/360");
    }

    @Test
    void createKeepsTheBaseRatesDayCountAndTiersItWasGiven() {
        InterestConfigurationRepository repo = mock(InterestConfigurationRepository.class);
        org.mockito.Mockito.when(repo.save(org.mockito.ArgumentMatchers.any())).thenAnswer(i -> i.getArgument(0));
        InterestConfigurationService service = new InterestConfigurationService(repo);

        InterestConfiguration c = service.create(com.bank.vam.dto.credit.InterestConfigurationDto.CreateRequest.builder()
                .configType(InterestConfiguration.ConfigType.INTERNAL)
                .targetType(InterestConfiguration.TargetType.POOL)
                .currencyCode("GBP")
                .creditBaseRate(new BigDecimal("5.00")).creditSpread(new BigDecimal("-0.25"))
                .debitBaseRate(new BigDecimal("5.00")).debitSpread(new BigDecimal("1.50"))
                .dayCountConvention("ACT/365")
                .isTiered(true).tierConfig("[{\"min\":0,\"rate\":1.0}]")
                .build());

        assertThat(c.getEffectiveCreditRate()).isEqualByComparingTo("4.75");
        assertThat(c.getEffectiveDebitRate()).isEqualByComparingTo("6.50");
        assertThat(c.getDayCountConvention()).isEqualTo("ACT/365");
        assertThat(c.getIsTiered()).isTrue();
        assertThatThrownBy(() -> service.create(com.bank.vam.dto.credit.InterestConfigurationDto.CreateRequest.builder()
                .configType(InterestConfiguration.ConfigType.INTERNAL).currencyCode("GBP").tierConfig("[{\"min\":0}]").build()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("numeric rate");
    }

    private static InterestConfiguration config(String credit, String debit, String dayCount) {
        return InterestConfiguration.builder()
                .configType(InterestConfiguration.ConfigType.INTERNAL)
                .currencyCode("GBP")
                .effectiveCreditRate(new BigDecimal(credit))
                .effectiveDebitRate(new BigDecimal(debit))
                .dayCountConvention(dayCount)
                .build();
    }
}
