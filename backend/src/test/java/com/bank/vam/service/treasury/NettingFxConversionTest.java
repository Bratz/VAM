package com.bank.vam.service.treasury;

import com.bank.vam.entity.treasury.NettingEntry;
import com.bank.vam.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A cross-currency netting entry is converted at the real rate, and refused without one -- never 1:1. */
class NettingFxConversionTest {

    private final FxRateService fx = mock(FxRateService.class);
    private final NettingService service = new NettingService(null, null, null, null, null, null, null, null, null, fx, null,
        null, null, null, null, null, null);

    private NettingEntry usdEntry() {
        return NettingEntry.builder().currencyCode("USD").grossAmount(new BigDecimal("100")).build();
    }

    @Test
    void convertsAtTheRateServicesRate() {
        when(fx.getRate("USD", "AED")).thenReturn(new BigDecimal("3.6725"));
        NettingEntry e = usdEntry();

        service.applyFxConversion(e, "AED");

        assertEquals(0, new BigDecimal("367.25").compareTo(e.getBaseAmount()));
        assertEquals(new BigDecimal("3.6725"), e.getExchangeRate());
    }

    @Test
    void noRateRefusesInsteadOfOneToOne() {
        when(fx.getRate("USD", "AED")).thenThrow(new BusinessException("FX_RATE_UNAVAILABLE", "No FX rate for USD/AED"));

        assertThrows(BusinessException.class, () -> service.applyFxConversion(usdEntry(), "AED"));
    }
}
