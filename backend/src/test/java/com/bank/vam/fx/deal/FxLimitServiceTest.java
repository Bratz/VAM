package com.bank.vam.fx.deal;

import com.bank.vam.config.FxDealProperties;
import com.bank.vam.config.MarketProfileProperties;
import com.bank.vam.entity.credit.CreditAgreement;
import com.bank.vam.entity.credit.CreditFacility;
import com.bank.vam.entity.credit.CreditFacility.FacilityStatus;
import com.bank.vam.entity.credit.CreditFacility.FacilityType;
import com.bank.vam.entity.credit.CreditLimit;
import com.bank.vam.entity.credit.CreditLimit.LimitStatus;
import com.bank.vam.entity.credit.CreditLimit.LimitType;
import com.bank.vam.entity.credit.CreditLimit.TargetType;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.fx.HomeCurrency;
import com.bank.vam.repository.credit.CreditAgreementRepository;
import com.bank.vam.repository.credit.CreditFacilityRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.service.treasury.FxRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The FX line inside the credit model: a facility's external limit at group level, internal sub-limits per legal
 * entity, a deal checked against both. The FX rows of credit_limits are kept in an in-memory store behind the
 * repository so allocation, usage and seeding can be followed end to end.
 */
class FxLimitServiceTest {

    private static final UUID CORP = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.now();
    private static final LocalDate TRADE = LocalDate.of(2026, 11, 5);

    private final List<CreditLimit> store = new ArrayList<>();
    private final FxCreditLineRepository lines = mock(FxCreditLineRepository.class);
    private final CreditFacilityRepository facilities = mock(CreditFacilityRepository.class);
    private final CreditAgreementRepository agreements = mock(CreditAgreementRepository.class);
    private final LegalEntityRepository entities = mock(LegalEntityRepository.class);
    private final FxDealRepository deals = mock(FxDealRepository.class);
    private final HomeCurrency home = mock(HomeCurrency.class);
    private final FxRateService rates = mock(FxRateService.class);
    private final FxDealProperties props = new FxDealProperties();
    private final MarketProfileProperties market = mock(MarketProfileProperties.class);
    private final FxLimitService service = new FxLimitService(lines, facilities, agreements, entities, deals, home, rates, props, market);

    private final LegalEntity treasury = entity("Test Treasury Ltd", true);
    private final LegalEntity subsidiary = entity("Test Trading Ltd", false);
    private CreditFacility facility;
    private CreditLimit line;

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static LegalEntity entity(String name, boolean treasury) {
        LegalEntity e = new LegalEntity();
        e.setId(UUID.randomUUID());
        e.setCorporateId(CORP);
        e.setEntityName(name);
        e.setStatus(LegalEntity.EntityStatus.ACTIVE);
        e.setEntityType(treasury ? LegalEntity.EntityType.TREASURY_CENTER : LegalEntity.EntityType.SUBSIDIARY);
        e.setIsTreasuryCenter(treasury);
        return e;
    }

    @BeforeEach
    void wiring() {
        when(lines.save(any(CreditLimit.class))).thenAnswer(i -> {
            CreditLimit l = i.getArgument(0);
            if (l.getId() == null) {
                l.setId(UUID.randomUUID());
            }
            if (store.stream().noneMatch(x -> x == l)) {
                store.add(l);
            }
            return l;
        });
        when(lines.findLines(CORP)).thenAnswer(i -> store.stream().filter(l -> l.getTargetType() == TargetType.FX_LINE).toList());
        when(lines.findAllocations(any())).thenAnswer(i -> store.stream()
                .filter(l -> l.getTargetType() == TargetType.FX_ENTITY && i.getArgument(0).equals(l.getParentLimitId())).toList());
        when(lines.findAllocation(any(), any())).thenAnswer(i -> store.stream()
                .filter(l -> l.getTargetType() == TargetType.FX_ENTITY && i.getArgument(0).equals(l.getParentLimitId())
                        && i.getArgument(1).equals(l.getTargetId())).findFirst());
        when(lines.lockById(any())).thenAnswer(i -> store.stream().filter(l -> l.getId().equals(i.getArgument(0))).findFirst());
        when(entities.findById(treasury.getId())).thenReturn(Optional.of(treasury));
        when(entities.findById(subsidiary.getId())).thenReturn(Optional.of(subsidiary));
        when(entities.findByCorporateIdAndStatus(CORP, LegalEntity.EntityStatus.ACTIVE)).thenReturn(List.of(subsidiary, treasury));
        when(deals.sumLimitCharge(eq(CORP), anyString(), any())).thenReturn(BigDecimal.ZERO);
        when(deals.sumLimitChargeForEntity(eq(CORP), any(), anyString(), any())).thenReturn(BigDecimal.ZERO);
        when(home.of(CORP)).thenReturn("AED");
        when(market.getActiveHomeBankBic()).thenReturn("HBUKGB4BXXX");
        when(market.getActiveHomeBankName()).thenReturn("Home Bank");
    }

    /** A 1,000,000 AED FX facility and its line, with nothing allocated yet. */
    private void givenALine() {
        facility = CreditFacility.builder().facilityName("FX treasury line").facilityType(FacilityType.FX_TREASURY_LINE)
                .sanctionedLimit(bd("1000000")).facilityCurrency("AED").status(FacilityStatus.ACTIVE)
                .effectiveDate(TODAY.minusDays(1)).expiryDate(TODAY.plusYears(1)).build();
        facility.setId(UUID.randomUUID());
        when(facilities.findById(facility.getId())).thenReturn(Optional.of(facility));
        line = CreditLimit.createExternalLimit(CORP, CORP, TargetType.FX_LINE, facility.getId(), bd("1000000"), "AED");
        line.setStatus(LimitStatus.ACTIVE);
        lines.save(line);
    }

    @Test
    void theCreditFactorStepsUpWithDaysToMaturityAndStaysAtTheLastBand() {
        assertThat(FxLimitService.factor(props.getPfeBands(), 0)).isEqualByComparingTo("0.01");
        assertThat(FxLimitService.factor(props.getPfeBands(), 8)).isEqualByComparingTo("0.03");
        assertThat(FxLimitService.factor(props.getPfeBands(), 92)).isEqualByComparingTo("0.06");
        assertThat(FxLimitService.factor(props.getPfeBands(), 366)).isEqualByComparingTo("0.15");
        assertThat(FxLimitService.factor(props.getPfeBands(), 900)).isEqualByComparingTo("0.15");
    }

    @Test
    void spotUsesItsFullNotionalAndAForwardItsRiskWeightedShare() {
        when(rates.getRate("EUR", "AED")).thenReturn(bd("4.00"));

        BigDecimal spot = service.charge(Instrument.SPOT, "EUR", bd("100000"), TRADE, TRADE.plusDays(2), "AED");
        BigDecimal threeMonths = service.charge(Instrument.FORWARD, "EUR", bd("100000"), TRADE, TRADE.plusDays(92), "AED");

        assertThat(spot).isEqualByComparingTo("400000.00");
        assertThat(threeMonths).isEqualByComparingTo("24000.00"); // 400,000 x 6%
    }

    @Test
    void withNoFxFacilityTheCorporateCannotDeal() {
        assertThat(service.line(CORP).usable()).isFalse();
        assertThat(service.refusal(CORP, treasury.getId(), bd("1"))).startsWith("No FX line");
    }

    @Test
    void anExpiredOrSuspendedFacilityOrLineBlocksDealing() {
        givenALine();
        service.allocate(CORP, treasury.getId(), bd("500000"));
        assertThat(service.refusal(CORP, treasury.getId(), bd("1"))).isNull();

        facility.setExpiryDate(TODAY.minusDays(1));
        assertThat(service.refusal(CORP, treasury.getId(), bd("1"))).startsWith("The FX facility expired on");

        facility.setExpiryDate(TODAY.plusYears(1));
        line.setStatus(LimitStatus.SUSPENDED);
        assertThat(service.refusal(CORP, treasury.getId(), bd("1"))).isEqualTo("The FX line is suspended");
    }

    @Test
    void anEntityWithNoSubLimitCannotDealUntilOneIsAllocated() {
        givenALine();

        assertThat(service.refusal(CORP, subsidiary.getId(), bd("1"))).startsWith("No FX sub-limit is allocated to Test Trading Ltd");

        service.allocate(CORP, subsidiary.getId(), bd("200000"));
        assertThat(service.refusal(CORP, subsidiary.getId(), bd("1"))).isNull();
    }

    @Test
    void aDealMustFitBothItsEntitysSubLimitAndTheGroupLine() {
        givenALine();
        service.allocate(CORP, treasury.getId(), bd("300000"));
        service.allocate(CORP, subsidiary.getId(), bd("700000"));
        when(deals.sumLimitChargeForEntity(eq(CORP), eq(treasury.getId()), eq("AED"), any())).thenReturn(bd("250000"));

        assertThat(service.refusal(CORP, treasury.getId(), bd("50000"))).isNull();
        assertThat(service.refusal(CORP, treasury.getId(), bd("60000")))
                .startsWith("The FX sub-limit of Test Treasury Ltd would be exceeded").contains("50,000.00 left");

        // The group line can run out before an entity's own sub-limit does, if the line is reduced under it.
        line.setLimitAmount(bd("900000"));
        when(deals.sumLimitCharge(eq(CORP), eq("AED"), any())).thenReturn(bd("880000"));
        assertThat(service.refusal(CORP, subsidiary.getId(), bd("30000")))
                .startsWith("The corporate's FX line would be exceeded").contains("20,000.00 left");
    }

    @Test
    void acceptingLocksTheLineThenTheSubLimitAndRefusesWhatNoLongerFits() {
        givenALine();
        service.allocate(CORP, treasury.getId(), bd("100000"));
        when(deals.sumLimitChargeForEntity(eq(CORP), eq(treasury.getId()), eq("AED"), any())).thenReturn(bd("95000"));

        assertThatThrownBy(() -> service.requireLocked(CORP, treasury.getId(), bd("10000")))
                .isInstanceOf(BusinessException.class).hasMessageStartingWith("Cannot book. The FX sub-limit");
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(lines);
        order.verify(lines).lockById(line.getId());
        order.verify(lines).lockById(store.stream().filter(l -> l.getTargetType() == TargetType.FX_ENTITY).findFirst().orElseThrow().getId());
    }

    @Test
    void subLimitsCannotTogetherExceedTheLineNorBeSetBelowWhatIsInUse() {
        givenALine();
        service.allocate(CORP, treasury.getId(), bd("600000"));

        assertThatThrownBy(() -> service.allocate(CORP, subsidiary.getId(), bd("500000")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("more than the FX line");

        when(deals.sumLimitChargeForEntity(eq(CORP), eq(treasury.getId()), eq("AED"), any())).thenReturn(bd("450000"));
        assertThatThrownBy(() -> service.allocate(CORP, treasury.getId(), bd("400000")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("cannot be set below");

        CreditLimit sub = store.stream().filter(l -> l.getTargetType() == TargetType.FX_ENTITY).findFirst().orElseThrow();
        assertThat(sub.getLimitType()).isEqualTo(LimitType.INTERNAL);
        assertThat(sub.getParentLimitId()).isEqualTo(line.getId());
        assertThat(sub.getTargetId()).isEqualTo(treasury.getId());
        assertThat(line.getAllocatedToChildren()).isEqualByComparingTo("600000");
    }

    @Test
    void usageFromOpenDealsIsWrittenToTheLineTheSubLimitsAndTheFacility() {
        givenALine();
        service.allocate(CORP, treasury.getId(), bd("400000"));
        when(deals.sumLimitCharge(eq(CORP), eq("AED"), any())).thenReturn(bd("120000"));
        when(deals.sumLimitChargeForEntity(eq(CORP), eq(treasury.getId()), eq("AED"), any())).thenReturn(bd("120000"));

        service.refreshUsage(CORP);

        CreditLimit sub = store.stream().filter(l -> l.getTargetType() == TargetType.FX_ENTITY).findFirst().orElseThrow();
        assertThat(line.getUtilizedAmount()).isEqualByComparingTo("120000");
        assertThat(sub.getUtilizedAmount()).isEqualByComparingTo("120000");
        verify(facilities).updateOutstanding(facility.getId(), bd("120000"));
    }

    @Test
    void warningsFollowEachLimitsOwnThresholds() {
        givenALine();
        service.allocate(CORP, treasury.getId(), bd("100000"));
        when(deals.sumLimitCharge(eq(CORP), eq("AED"), any())).thenReturn(bd("96000"));
        when(deals.sumLimitChargeForEntity(eq(CORP), eq(treasury.getId()), eq("AED"), any())).thenReturn(bd("96000"));

        List<FxLimitService.Alert> alerts = service.alerts(CORP);

        // The line is at 9.6%: no alert. The sub-limit is at 96%: past 80% and past 95%.
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).what()).isEqualTo("The FX sub-limit of Test Treasury Ltd");
        assertThat(alerts.get(0).critical()).isTrue();
        assertThat(alerts.get(0).percentUsed()).isEqualByComparingTo("96.0");
    }

    @Test
    void seedingGivesTheCorporateAnFxFacilityUnderAHomeBankAgreementAndAllocatesItToTheTreasuryCentre() {
        when(agreements.findByCorporateIdOrderByAgreementName(CORP)).thenReturn(List.of());
        when(agreements.save(any(CreditAgreement.class))).thenAnswer(i -> {
            CreditAgreement a = i.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });
        when(facilities.save(any(CreditFacility.class))).thenAnswer(i -> {
            CreditFacility f = i.getArgument(0);
            f.setId(UUID.randomUUID());
            when(facilities.findById(f.getId())).thenReturn(Optional.of(f));
            return f;
        });

        assertThat(service.ensureLine(CORP)).isTrue();
        assertThat(service.ensureLine(CORP)).isFalse(); // idempotent

        ArgumentCaptor<CreditAgreement> agreement = ArgumentCaptor.forClass(CreditAgreement.class);
        verify(agreements).save(agreement.capture());
        assertThat(agreement.getValue().getBankCode()).isEqualTo("HBUKGB4BXXX");
        ArgumentCaptor<CreditFacility> fac = ArgumentCaptor.forClass(CreditFacility.class);
        verify(facilities).save(fac.capture());
        assertThat(fac.getValue().getFacilityType()).isEqualTo(FacilityType.FX_TREASURY_LINE);
        assertThat(fac.getValue().getSanctionedLimit()).isEqualByComparingTo(props.getDefaultLimit());
        assertThat(service.line(CORP).usable()).isTrue();
        assertThat(service.allocation(CORP, treasury.getId()).usage().limit()).isEqualByComparingTo(props.getDefaultLimit());
        assertThat(service.allocation(CORP, subsidiary.getId()).usable()).isFalse();
    }

    @Test
    void anExistingHomeBankAgreementIsUsedRatherThanASecondOne() {
        CreditAgreement existing = CreditAgreement.builder().agreementName("Facility agreement").corporateId(CORP)
                .bankCode("HBUKGB4BXXX").totalLimit(bd("50000000")).limitCurrency("GBP").build();
        existing.setId(UUID.randomUUID());
        when(agreements.findByCorporateIdOrderByAgreementName(CORP)).thenReturn(List.of(existing));
        when(facilities.save(any(CreditFacility.class))).thenAnswer(i -> {
            CreditFacility f = i.getArgument(0);
            f.setId(UUID.randomUUID());
            when(facilities.findById(f.getId())).thenReturn(Optional.of(f));
            return f;
        });

        service.ensureLine(CORP);

        verify(agreements, never()).save(any());
        assertThat(service.line(CORP).currency()).isEqualTo("GBP"); // the facility currency, from its agreement
    }
}
