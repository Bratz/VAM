package com.bank.vam.service.treasury;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** The limit figures every IHB screen shows follow what was drawn (the IHB current accounts' debit balance). */
class IhbExposureTest {

    @Test
    void availableUtilisationAndFlagsFollowWhatWasDrawn() {
        IhbExposure e = new IhbExposure(new BigDecimal("1000000"), new BigDecimal("850000"));
        assertThat(e.available()).isEqualByComparingTo("150000");
        assertThat(e.utilisationPercent()).isEqualByComparingTo("85.00");
        assertThat(e.warning()).isTrue();
        assertThat(e.breached()).isFalse();

        IhbExposure over = new IhbExposure(new BigDecimal("1000"), new BigDecimal("1200"));
        assertThat(over.available()).isEqualByComparingTo("0");
        assertThat(over.breached()).isTrue();

        IhbExposure noLimit = new IhbExposure(BigDecimal.ZERO, new BigDecimal("50"));
        assertThat(noLimit.utilisationPercent()).isEqualByComparingTo("0");
        assertThat(noLimit.warning()).isFalse();
    }
}
