package com.bank.vam.service.treasury;

import com.bank.vam.entity.treasury.FxRate;
import com.bank.vam.repository.treasury.FxRateRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A new rate ends the open rates before it (it used to leave them current, as a new rate has no id yet). */
class FxRateSupersedeTest {

    private final FxRateRepository repo = mock(FxRateRepository.class);
    private final FxRateService service = new FxRateService(repo);

    @Test
    void savingANewGlobalRateEndsTheEarlierGlobalOnesForThatPairAndType() {
        UUID id = UUID.randomUUID();
        when(repo.save(any(FxRate.class))).thenAnswer(inv -> {
            FxRate r = inv.getArgument(0);
            r.setId(id);
            return r;
        });

        service.createSpotRate("EUR", "AED", new BigDecimal("4.0125"), FxRate.RateSource.REUTERS);

        verify(repo).supersede(eq("EUR"), eq("AED"), eq(FxRate.RateType.MID), eq(FxRateRepository.GLOBAL),
                eq(FxRateRepository.GLOBAL), eq(LocalDate.now()), any(), eq(id));
    }

    @Test
    void aCorporatesInternalRateOnlyEndsThatCorporatesRates() {
        UUID corp = UUID.randomUUID();
        when(repo.save(any(FxRate.class))).thenAnswer(inv -> {
            FxRate r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        service.createInternalRate("USD", "AED", new BigDecimal("3.6725"), corp, BigDecimal.ONE);

        verify(repo).supersede(eq("USD"), eq("AED"), any(), eq(corp), eq(FxRateRepository.GLOBAL), any(), any(), any());
    }
}
