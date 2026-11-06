package com.bank.vam.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/** Accounts opened "at the home bank" take this BIC as their bank code, which must be a BIC. */
class MarketProfilePropertiesTest {

    private static final String BIC = "^[A-Za-z]{6}[A-Za-z0-9]{2}([A-Za-z0-9]{3})?$";

    @Test
    void theConfiguredHomeBankWinsOverTheProfile() {
        MarketProfileProperties market = new MarketProfileProperties();
        market.init();
        HomeBankProperties home = new HomeBankProperties();
        home.setBic("EBILAEADXXX");
        ReflectionTestUtils.setField(market, "homeBank", home);

        assertThat(market.getActiveHomeBankBic()).isEqualTo("EBILAEADXXX");
    }

    @Test
    void everyProfilesFallbackIsABic() {
        MarketProfile.BUILTINS.forEach((code, p) -> assertThat(p.getHomeBankBic()).as(code).matches(BIC));
    }
}
