package com.bank.vam.service.treasury;

import com.bank.vam.config.HomeBankProperties;
import com.bank.vam.config.MultiBankProperties;
import com.bank.vam.dto.treasury.MultiBankLiquidityDto.LiquiditySummary;
import com.bank.vam.dto.treasury.MultiBankLiquidityDto.ShadowSummary;
import com.bank.vam.dto.treasury.MultiBankLiquidityDto.TrendPoint;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.treasury.ShadowBalanceSnapshotRepository;
import com.bank.vam.repository.treasury.ShadowBalanceSnapshotRepository.TrendRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Regression coverage for the Multi-Bank Liquidity CFO-capabilities round: entity/country
 * resolution on {@code getSummary()} (including the null-owning-entity fallback) and the new
 * {@code getTrend()} aggregation.
 */
class MultiBankLiquidityViewServiceTest {

    private final VirtualAccountRepository vaRepository = mock(VirtualAccountRepository.class);
    private final HomeBankProperties homeBankProperties = new HomeBankProperties();
    private final MultiBankProperties multiBankProperties = new MultiBankProperties();
    private final LegalEntityRepository legalEntityRepository = mock(LegalEntityRepository.class);
    private final ShadowBalanceSnapshotRepository snapshotRepository = mock(ShadowBalanceSnapshotRepository.class);

    private final MultiBankLiquidityViewService service = new MultiBankLiquidityViewService(
            vaRepository, homeBankProperties, multiBankProperties, legalEntityRepository, snapshotRepository);

    private static final UUID ENTITY_ID = UUID.randomUUID();

    private VirtualAccount shadow(UUID entityId) {
        VirtualAccount s = VirtualAccount.builder()
                .vaNumber("VA-1")
                .currencyCode("AED")
                .bankSwift("TESTAEAD")
                .accountCategory(AccountCategory.PHYSICAL_MIRROR)
                .bankBalance(BigDecimal.TEN)
                .bankAvailableBalance(BigDecimal.TEN)
                .bankBalanceCommitted(BigDecimal.ZERO)
                .owningEntityId(entityId)
                .build();
        s.setId(UUID.randomUUID());
        return s;
    }

    @Test
    void resolvesOwningEntityNameAndCountryFromBulkLookup() {
        VirtualAccount s = shadow(ENTITY_ID);
        when(vaRepository.findByAccountCategory(AccountCategory.PHYSICAL_MIRROR)).thenReturn(List.of(s));

        LegalEntity entity = new LegalEntity();
        entity.setId(ENTITY_ID);
        entity.setEntityName("Acme UAE LLC");
        entity.setCountryCode("AE");
        entity.setJurisdiction("United Arab Emirates");
        when(legalEntityRepository.findAllById(eq(Set.of(ENTITY_ID)))).thenReturn(List.of(entity));

        LiquiditySummary summary = service.getSummary(null);

        ShadowSummary shadowSummary = summary.getBanks().get(0).getCurrencies().get(0).getShadows().get(0);
        assertEquals("Acme UAE LLC", shadowSummary.getOwningEntityName());
        assertEquals("AE", shadowSummary.getOwningEntityCountry());
        assertEquals("United Arab Emirates", shadowSummary.getOwningEntityJurisdiction());
        assertEquals(ENTITY_ID.toString(), shadowSummary.getOwningEntityId());
    }

    @Test
    void shadowWithNoOwningEntityFallsBackToUnassigned() {
        VirtualAccount s = shadow(null);
        when(vaRepository.findByAccountCategory(AccountCategory.PHYSICAL_MIRROR)).thenReturn(List.of(s));

        LiquiditySummary summary = service.getSummary(null);

        ShadowSummary shadowSummary = summary.getBanks().get(0).getCurrencies().get(0).getShadows().get(0);
        assertEquals("Unassigned", shadowSummary.getOwningEntityName());
        assertNull(shadowSummary.getOwningEntityCountry());
        verify(legalEntityRepository, never()).findAllById(any());
    }

    @Test
    void getTrendMapsRepositoryRowsToTrendPoints() {
        TrendRow row = mock(TrendRow.class);
        when(row.getAsOf()).thenReturn(LocalDate.of(2026, 1, 1));
        when(row.getCurrencyCode()).thenReturn("USD");
        when(row.getTotalBankBalance()).thenReturn(BigDecimal.valueOf(100));
        when(row.getTotalEffective()).thenReturn(BigDecimal.valueOf(90));
        when(snapshotRepository.findTrend(any(), any())).thenReturn(List.of(row));

        List<TrendPoint> trend = service.getTrend(null, 30);

        assertEquals(1, trend.size());
        assertEquals("USD", trend.get(0).getCurrencyCode());
        assertEquals(BigDecimal.valueOf(90), trend.get(0).getTotalEffective());
    }
}
