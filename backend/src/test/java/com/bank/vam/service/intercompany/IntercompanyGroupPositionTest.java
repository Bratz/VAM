package com.bank.vam.service.intercompany;

import com.bank.vam.config.MarketProfileProperties;
import com.bank.vam.controller.intercompany.IntercompanyController;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.MirrorAccountType;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.payables.Payable;
import com.bank.vam.entity.pobo.IntercompanyRecharge;
import com.bank.vam.entity.pobo.IntercompanyRecharge.RechargeStatus;
import com.bank.vam.entity.pobo.IntercompanyRecharge.RechargeType;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.entity.treasury.NettingCycle;
import com.bank.vam.entity.treasury.NettingEntry;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.intercompany.IntercompanyTransactionRepository;
import com.bank.vam.repository.payables.PayableRepository;
import com.bank.vam.repository.pobo.IntercompanyRechargeRepository;
import com.bank.vam.repository.receivables.ReceivableRepository;
import com.bank.vam.repository.treasury.NettingCycleRepository;
import com.bank.vam.repository.treasury.NettingEntryRepository;
import com.bank.vam.service.intercompany.IntercompanyGroupPositionService.*;
import com.bank.vam.service.treasury.FxRateService;
import com.bank.vam.service.treasury.NettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The Intercompany page's who-owes-whom (spec R3-R7): three line kinds per pair and currency, one invoice counted once,
 * a zero-sum control with its break, a labelled reporting-currency total, and everything scoped to one corporate,
 * netting hand-off included.
 */
class IntercompanyGroupPositionTest {

    private final UUID mnc = UUID.randomUUID();
    private final UUID helios = UUID.randomUUID();

    private final LegalEntityRepository entities = mock(LegalEntityRepository.class);
    private final VirtualAccountRepository vas = mock(VirtualAccountRepository.class);
    private final PayableRepository payables = mock(PayableRepository.class);
    private final ReceivableRepository receivables = mock(ReceivableRepository.class);
    private final IntercompanyRechargeRepository recharges = mock(IntercompanyRechargeRepository.class);
    private final NettingCycleRepository cycles = mock(NettingCycleRepository.class);
    private final NettingEntryRepository entries = mock(NettingEntryRepository.class);
    private final IntercompanyTransactionRepository icRows = mock(IntercompanyTransactionRepository.class);
    private final NettingService netting = mock(NettingService.class);
    private final FxRateService fx = mock(FxRateService.class);
    private final MarketProfileProperties market = mock(MarketProfileProperties.class);

    private final IntercompanyTransactionService icTransactions = new IntercompanyTransactionService(icRows, recharges,
        entities, payables, receivables, cycles, entries, vas, netting, market);
    private final IntercompanyGroupPositionService service = new IntercompanyGroupPositionService(icTransactions,
        entities, vas, payables, receivables, recharges, fx, market);
    private final IntercompanyController controller = new IntercompanyController(null, icTransactions, service);

    private LegalEntity holding, treasury, uk, dubai, heliosHq;

    @BeforeEach
    void setUp() {
        holding = entity("MNC-HOLDING", mnc);
        holding.setReportingCurrency("AED");
        treasury = entity("MNC-TREASURY", mnc);
        treasury.setIsTreasuryCenter(true);
        treasury.setParentEntityId(holding.getId());
        uk = entity("MNC-UK", mnc);
        uk.setParentEntityId(holding.getId());
        dubai = entity("MNC-UAE-DUBAI", mnc);
        dubai.setParentEntityId(holding.getId());
        heliosHq = entity("HEL-HQ", helios);
        when(entities.findByCorporateIdOrderByHierarchyPath(mnc)).thenReturn(List.of(holding, treasury, uk, dubai));
        when(entities.findByCorporateIdOrderByHierarchyPath(helios)).thenReturn(List.of(heliosHq));
        for (LegalEntity e : List.of(holding, treasury, uk, dubai, heliosHq)) {
            when(entities.findById(e.getId())).thenReturn(Optional.of(e));
        }
        when(market.getDefaultCurrency()).thenReturn("AED");
    }

    /** vam-five on 13 Nov: treasury owes MNC-UK AED 1,100.01 and MNC Dubai AED 14.48; the mirrors follow the accounts. */
    private void ledgerAsOnVamFive() {
        VirtualAccount ukIhb = ihb("IHB-MNC-UK-AED-0002", "1100.01", uk);
        VirtualAccount dubaiIhb = ihb("IHB-MNC-UAE-DUBAI-AED-0001", "14.48", dubai);
        when(vas.findByCorporateId(mnc)).thenReturn(List.of(ukIhb, dubaiIhb,
            mirror(MirrorAccountType.IC_PAYABLE, "1100.01", ukIhb), mirror(MirrorAccountType.IC_RECEIVABLE, "0", ukIhb),
            mirror(MirrorAccountType.IC_PAYABLE, "14.48", dubaiIhb), mirror(MirrorAccountType.IC_RECEIVABLE, "0", dubaiIhb)));
    }

    @Test
    void threeLineKindsPerPairEachWithItsOwnNet() {
        ledgerAsOnVamFive();
        Receivable overdue = receivable("IC-DUBAI-001", dubai, uk, "35000", Receivable.ReceivableStatus.OVERDUE); // UK owes Dubai
        Receivable paid = receivable("IC-OLD", dubai, uk, "999", Receivable.ReceivableStatus.PAID);
        when(receivables.findIntercompanyReceivablesByCorporate(mnc)).thenReturn(List.of(overdue, paid));
        IntercompanyRecharge manual = recharge("RCH-1", null, RechargeStatus.APPROVED, "500");       // UK owes treasury
        IntercompanyRecharge cobo = recharge("RCH-2", RechargeType.COBO_COLLECTION, RechargeStatus.RECHARGED, "200"); // treasury owes UK
        IntercompanyRecharge onIhb = recharge("RCH-3", RechargeType.POBO_PAYMENT, RechargeStatus.APPROVED, "151"); // on the IHB already
        IntercompanyRecharge pending = recharge("RCH-4", null, RechargeStatus.PENDING, "77");
        when(recharges.findAll()).thenReturn(List.of(manual, cobo, onIhb, pending));

        GroupPosition g = service.getGroupPosition(mnc);

        assertThat(g.allSettled()).isFalse();
        assertThat(g.currencies()).hasSize(1);
        CurrencyPosition aed = g.currencies().get(0);
        assertThat(aed.currency()).isEqualTo("AED");
        assertThat(aed.outstanding()).isEqualByComparingTo("36814.49"); // 1100.01 + 14.48 + 35000 + 500 + 200

        PairPosition treasuryUk = pair(aed, "MNC-TREASURY", "MNC-UK");
        assertThat(treasuryUk.ihbPositions().lines()).singleElement().satisfies(l -> {
            assertThat(l.debtor().code()).isEqualTo("MNC-TREASURY");
            assertThat(l.creditor().code()).isEqualTo("MNC-UK");
            assertThat(l.amount()).isEqualByComparingTo("1100.01");
        });
        assertThat(treasuryUk.openInvoices().lines()).isEmpty();
        assertThat(treasuryUk.recharges().lines()).extracting(Line::reference).containsExactlyInAnyOrder("RCH-1", "RCH-2");
        assertThat(treasuryUk.recharges().net().debtor().code()).isEqualTo("MNC-UK");
        assertThat(treasuryUk.recharges().net().amount()).isEqualByComparingTo("300");
        // Awaiting approval is its own kind, not owed yet (outstanding above leaves it out); the IHB one never shows.
        assertThat(treasuryUk.rechargesAwaitingApproval().lines()).singleElement().satisfies(l -> {
            assertThat(l.reference()).isEqualTo("RCH-4");
            assertThat(l.kind()).isEqualTo(IntercompanyGroupPositionService.RECHARGE_PENDING);
            assertThat(l.debtor().code()).isEqualTo("MNC-UK");
        });

        PairPosition dubaiUk = pair(aed, "MNC-UAE-DUBAI", "MNC-UK");
        assertThat(dubaiUk.openInvoices().lines()).singleElement().satisfies(l -> {
            assertThat(l.reference()).isEqualTo("IC-DUBAI-001");
            assertThat(l.debtor().code()).isEqualTo("MNC-UK");
            assertThat(l.creditor().code()).isEqualTo("MNC-UAE-DUBAI");
            assertThat(l.status()).isEqualTo("OVERDUE");
        });
        assertThat(dubaiUk.ihbPositions().lines()).isEmpty();
        assertThat(dubaiUk.ihbPositions().net().amount()).isEqualByComparingTo("0");
        assertThat(pair(aed, "MNC-TREASURY", "MNC-UAE-DUBAI").ihbPositions().net().amount()).isEqualByComparingTo("14.48");
    }

    @Test
    void oneInvoiceOnBothSidesIsCountedOnceAsTheReceivable() {
        Receivable r1 = receivable("RCV-1", uk, dubai, "1000", Receivable.ReceivableStatus.OPEN);
        Receivable r2 = receivable("RCV-2", uk, dubai, "2000", Receivable.ReceivableStatus.OPEN);
        Payable p1 = payable("PAY-1", dubai, uk, "1000"); // the same invoice as RCV-1, linked from the payable
        p1.setCounterpartyReceivableId(r1.getId());
        Payable p2 = payable("PAY-2", dubai, uk, "2000"); // the same invoice as RCV-2, linked from the receivable
        r2.setCounterpartyPayableId(p2.getId());
        Payable alone = payable("PAY-3", dubai, uk, "300"); // no receivable on the other side
        when(receivables.findIntercompanyReceivablesByCorporate(mnc)).thenReturn(List.of(r1, r2));
        when(payables.findByCorporateIdAndIsIntercompanyTrue(mnc)).thenReturn(List.of(p1, p2, alone));

        CurrencyPosition aed = service.getGroupPosition(mnc).currencies().get(0);

        LineGroup invoices = pair(aed, "MNC-UAE-DUBAI", "MNC-UK").openInvoices();
        assertThat(invoices.lines()).extracting(Line::reference).containsExactlyInAnyOrder("RCV-1", "RCV-2", "PAY-3");
        assertThat(invoices.net().debtor().code()).isEqualTo("MNC-UAE-DUBAI");
        assertThat(invoices.net().amount()).isEqualByComparingTo("3300");
        assertThat(aed.outstanding()).isEqualByComparingTo("3300");

        // Netting offers the same single side.
        when(receivables.findNettingEligibleReceivables(mnc)).thenReturn(List.of(r1, r2));
        when(payables.findNettingEligiblePayables(mnc)).thenReturn(List.of(p1, p2, alone));
        assertThat(icTransactions.getNettingEligibleTransactions(mnc, uk.getId(), dubai.getId()).getItems())
            .extracting(IntercompanyTransactionService.NettingItem::reference).containsExactlyInAnyOrder("RCV-1", "RCV-2", "PAY-3");
    }

    @Test
    void theIhbControlNetsToZeroAndADriftedMirrorIsABreak() {
        ledgerAsOnVamFive();
        Control balanced = service.getGroupPosition(mnc).currencies().get(0).control();
        assertThat(balanced.receivables()).isEqualByComparingTo("1114.49");
        assertThat(balanced.payables()).isEqualByComparingTo("1114.49");
        assertThat(balanced.balanced()).isTrue();

        // Locally Dubai's IC receivable drifted to 3,369 against a +14.48 account, and MNC-UAE's account has no mirrors.
        VirtualAccount dubaiIhb = ihb("IHB-MNC-UAE-DUBAI-AED-0001", "14.48", dubai);
        VirtualAccount uae = ihb("IHB-MNC-UAE-AED-0001", "-7212", uk);
        when(vas.findByCorporateId(mnc)).thenReturn(List.of(dubaiIhb, uae,
            mirror(MirrorAccountType.IC_RECEIVABLE, "3369", dubaiIhb), mirror(MirrorAccountType.IC_PAYABLE, "0", dubaiIhb)));

        GroupPosition g = service.getGroupPosition(mnc);
        Control broken = g.currencies().get(0).control();
        assertThat(broken.receivables()).isEqualByComparingTo("3383.48"); // 3369 + 14.48
        assertThat(broken.payables()).isEqualByComparingTo("7212");
        assertThat(broken.difference()).isEqualByComparingTo("-3828.52");
        assertThat(broken.balanced()).isFalse();
    }

    @Test
    void aBreakAloneIsShownButNothingIsOwed() {
        VirtualAccount uae = ihb("IHB-MNC-UAE-AED-0001", "-50", uk); // no mirrors: no line, still a break
        when(vas.findByCorporateId(mnc)).thenReturn(List.of(uae));

        GroupPosition g = service.getGroupPosition(mnc);

        assertThat(g.allSettled()).isTrue();
        assertThat(g.currencies()).singleElement().satisfies(c -> {
            assertThat(c.pairs()).isEmpty();
            assertThat(c.control().balanced()).isFalse();
        });
    }

    @Test
    void theReportingTotalIsLabelledWithEachRateAndItsDateAndNoDealerFigures() {
        ledgerAsOnVamFive();
        Receivable gbp = receivable("RCV-GBP", uk, dubai, "100", Receivable.ReceivableStatus.OPEN);
        gbp.setCurrencyCode("GBP");
        Receivable sar = receivable("RCV-SAR", uk, dubai, "10", Receivable.ReceivableStatus.OPEN);
        sar.setCurrencyCode("SAR");
        when(receivables.findIntercompanyReceivablesByCorporate(mnc)).thenReturn(List.of(gbp, sar));
        when(fx.getRate("GBP", "AED")).thenReturn(new BigDecimal("4.65"));
        when(fx.getRateDate("GBP", "AED")).thenReturn(Optional.of(LocalDate.of(2026, 11, 12)));
        when(fx.getRate("SAR", "AED")).thenThrow(new BusinessException("FX_RATE_UNAVAILABLE", "No FX rate for SAR/AED"));

        ReportingTotal total = service.getGroupPosition(mnc).reportingTotal();

        assertThat(total.currency()).isEqualTo("AED"); // the group parent's reporting currency
        assertThat(total.amount()).isEqualByComparingTo("1579.49"); // 1114.49 AED + 100 GBP x 4.65
        assertThat(total.conversions()).singleElement().satisfies(c -> {
            assertThat(c.currency()).isEqualTo("GBP");
            assertThat(c.rate()).isEqualByComparingTo("4.65");
            assertThat(c.rateDate()).isEqualTo(LocalDate.of(2026, 11, 12));
            assertThat(c.converted()).isEqualByComparingTo("465.00");
        });
        assertThat(total.unconvertedCurrencies()).containsExactly("SAR");
        // A corporate screen never carries the bank's build-up of a rate.
        for (Class<?> shape : List.of(GroupPosition.class, CurrencyPosition.class, ReportingTotal.class, Conversion.class)) {
            assertThat(Arrays.stream(shape.getRecordComponents()).map(rc -> rc.getName().toLowerCase()))
                .noneMatch(n -> n.contains("mid") || n.contains("margin") || n.contains("floor") || n.contains("spread"));
        }
    }

    @Test
    void eachCorporateSeesOnlyItsOwnGroupAndNoCorporateIsRefused() {
        ledgerAsOnVamFive();
        when(recharges.findAll()).thenReturn(List.of(recharge("RCH-1", null, RechargeStatus.APPROVED, "500")));
        when(receivables.findIntercompanyReceivablesByCorporate(mnc))
            .thenReturn(List.of(receivable("IC-1", dubai, uk, "10", Receivable.ReceivableStatus.OPEN)));

        GroupPosition other = controller.getGroupPosition(helios).getBody().getData();
        assertThat(other.currencies()).isEmpty();
        assertThat(other.allSettled()).isTrue();
        assertThat(controller.getGroupPosition(mnc).getBody().getData().currencies()).isNotEmpty();

        assertThatThrownBy(() -> controller.getGroupPosition(null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.checkNettingEligibility(null, uk.getId(), dubai.getId()))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.addToNettingCycle(UUID.randomUUID(), null, new IntercompanyController.AddToNettingRequest()))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void nettingEligibilityAndAddAreScopedToTheCorporate() {
        IntercompanyRecharge manual = recharge("RCH-1", null, RechargeStatus.APPROVED, "500");
        IntercompanyRecharge alreadyInACycle = recharge("RCH-5", null, RechargeStatus.APPROVED, "50");
        when(entries.existsByIntercompanyRechargeId(alreadyInACycle.getId())).thenReturn(true);
        when(recharges.findAll()).thenReturn(List.of(manual, alreadyInACycle,
            recharge("RCH-3", RechargeType.POBO_PAYMENT, RechargeStatus.APPROVED, "151")));
        NettingCycle mine = cycle("NET-202611-001", NettingCycle.CycleStatus.DRAFT); // nothing ever sets OPEN
        NettingCycle theirs = cycle("NET-202611-002", NettingCycle.CycleStatus.DRAFT);
        theirs.setCorporateId(helios); // a cycle is its corporate's (V62), whatever it holds
        NettingCycle settled = cycle("NET-202610-001", NettingCycle.CycleStatus.SETTLED);
        when(cycles.findByCorporateIdOrderByCreatedAtDesc(mnc)).thenReturn(List.of(mine, settled));
        when(recharges.findById(manual.getId())).thenReturn(Optional.of(manual));
        when(recharges.findById(alreadyInACycle.getId())).thenReturn(Optional.of(alreadyInACycle));

        var eligible = controller.checkNettingEligibility(mnc, treasury.getId(), uk.getId()).getBody().getData();

        assertThat(eligible.getItems()).singleElement().satisfies(i -> {
            assertThat(i.reference()).isEqualTo("RCH-1");
            assertThat(i.debtorCode()).isEqualTo("MNC-UK");
            assertThat(i.creditorCode()).isEqualTo("MNC-TREASURY");
            assertThat(i.currency()).isEqualTo("AED");
        });
        assertThat(eligible.getOpenCycles()).extracting(IntercompanyTransactionService.OpenCycle::cycleReference)
            .containsExactly("NET-202611-001");
        // Another corporate can't ask about MNC's entities.
        assertThatThrownBy(() -> icTransactions.getNettingEligibleTransactions(helios, treasury.getId(), uk.getId()))
            .isInstanceOf(BusinessException.class);

        IntercompanyController.AddToNettingRequest add = new IntercompanyController.AddToNettingRequest();
        add.setRechargeIds(List.of(manual.getId()));
        var added = controller.addToNettingCycle(mine.getId(), mnc, add).getBody().getData();
        assertThat(added.getRechargesAdded()).isEqualTo(1);
        verify(netting).addRechargeToCycle(mine.getId(), manual);

        assertThatThrownBy(() -> controller.addToNettingCycle(theirs.getId(), mnc, add))
            .isInstanceOf(BusinessException.class).hasMessageContaining("not this corporate's");
        assertThatThrownBy(() -> controller.addToNettingCycle(mine.getId(), helios, add))
            .isInstanceOf(BusinessException.class).hasMessageContaining("is not this corporate's");
        IntercompanyController.AddToNettingRequest again = new IntercompanyController.AddToNettingRequest();
        again.setRechargeIds(List.of(alreadyInACycle.getId()));
        assertThatThrownBy(() -> controller.addToNettingCycle(mine.getId(), mnc, again))
            .isInstanceOf(BusinessException.class).hasMessageContaining("not awaiting netting");
        assertThatThrownBy(() -> controller.addToNettingCycle(settled.getId(), mnc, add))
            .isInstanceOf(BusinessException.class).hasMessageContaining("not open");
        verify(netting, times(1)).addRechargeToCycle(any(), any());
        verify(netting, never()).addRechargeToCycle(eq(theirs.getId()), any());
    }

    @Test
    void aCycleHoldingEntriesAlreadySettledAtSourceIsNotOfferedAndRefused() {
        IntercompanyRecharge manual = recharge("RCH-1", null, RechargeStatus.APPROVED, "500");
        when(recharges.findAll()).thenReturn(List.of(manual));
        when(recharges.findById(manual.getId())).thenReturn(Optional.of(manual));
        NettingCycle clean = cycle("NET-202602-002", NettingCycle.CycleStatus.DRAFT);
        NettingCycle stale = cycle("NET-202602-003", NettingCycle.CycleStatus.DRAFT); // vam-five: cut-over-settled POBO
        when(cycles.findByCorporateIdOrderByCreatedAtDesc(mnc)).thenReturn(List.of(clean, stale));
        when(netting.entriesSettledAtSource(stale.getId()))
            .thenReturn(List.of("ENT-000101 (RCH-IHBPOBO-1770051223508-5266)", "ENT-000102 (RCH-IHBPOBO-1770391620201-5585)"));

        assertThat(controller.checkNettingEligibility(mnc, treasury.getId(), uk.getId()).getBody().getData().getOpenCycles())
            .extracting(IntercompanyTransactionService.OpenCycle::cycleReference).containsExactly("NET-202602-002");

        IntercompanyController.AddToNettingRequest add = new IntercompanyController.AddToNettingRequest();
        add.setRechargeIds(List.of(manual.getId()));
        assertThatThrownBy(() -> controller.addToNettingCycle(stale.getId(), mnc, add))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already settled outside netting")
            .hasMessageContaining("ENT-000101 (RCH-IHBPOBO-1770051223508-5266)")
            .hasMessageContaining("ENT-000102");
        verify(netting, never()).addRechargeToCycle(any(), any());
    }

    @Test
    void rechargesAreApprovedAndRejectedWithinTheCorporateByTheSignedInUser() {
        com.bank.vam.service.pobo.PoboService pobo = new com.bank.vam.service.pobo.PoboService(recharges, entities, null, null);
        com.bank.vam.controller.pobo.PoboController endpoints = new com.bank.vam.controller.pobo.PoboController(
            pobo, mock(com.bank.vam.service.pobo.PoboCutoverService.class));
        when(recharges.save(any())).thenAnswer(i -> i.getArgument(0));
        IntercompanyRecharge toApprove = recharge("RCH-4", null, RechargeStatus.PENDING, "77");
        IntercompanyRecharge toReject = recharge("RCH-6", null, RechargeStatus.PENDING, "88");
        IntercompanyRecharge approved = recharge("RCH-1", null, RechargeStatus.APPROVED, "500");
        for (IntercompanyRecharge r : List.of(toApprove, toReject, approved)) {
            when(recharges.findById(r.getId())).thenReturn(Optional.of(r));
        }
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("treasurer@mnc", null, List.of()));
        try {
            // Another corporate, or none, can't act on MNC's recharge.
            assertThatThrownBy(() -> endpoints.approveRecharge(toApprove.getId(), helios))
                .isInstanceOf(BusinessException.class).hasMessageContaining("not this corporate's");
            assertThatThrownBy(() -> endpoints.approveRecharge(toApprove.getId(), null)).isInstanceOf(BusinessException.class);
            assertThat(toApprove.getStatus()).isEqualTo(RechargeStatus.PENDING);

            endpoints.approveRecharge(toApprove.getId(), mnc);
            assertThat(toApprove.getStatus()).isEqualTo(RechargeStatus.APPROVED);
            assertThat(toApprove.getApprovedBy()).isEqualTo("treasurer@mnc"); // not an entity code from the page

            assertThatThrownBy(() -> endpoints.rejectRecharge(toReject.getId(), mnc, "  "))
                .isInstanceOf(BusinessException.class).hasMessageContaining("reason");
            endpoints.rejectRecharge(toReject.getId(), mnc, "Duplicate of RCH-4");
            assertThat(toReject.getStatus()).isEqualTo(RechargeStatus.CANCELLED);
            assertThat(toReject.getRejectionReason()).isEqualTo("Duplicate of RCH-4");
            assertThat(toReject.getApprovedBy()).isEqualTo("treasurer@mnc");

            // Only a pending recharge can be approved or rejected.
            assertThatThrownBy(() -> endpoints.approveRecharge(approved.getId(), mnc))
                .isInstanceOf(BusinessException.class).hasMessageContaining("not pending");
            assertThatThrownBy(() -> endpoints.rejectRecharge(approved.getId(), mnc, "late"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("not pending");
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    private static PairPosition pair(CurrencyPosition c, String a, String b) {
        return c.pairs().stream().filter(p -> p.entityA().code().equals(a) && p.entityB().code().equals(b)).findFirst()
            .orElseThrow(() -> new AssertionError("no pair " + a + " / " + b + " in " + c.pairs()));
    }

    private LegalEntity entity(String code, UUID corporate) {
        LegalEntity e = new LegalEntity();
        e.setId(UUID.randomUUID());
        e.setEntityCode(code);
        e.setEntityName(code + " Ltd");
        e.setCorporateId(corporate);
        e.setFunctionalCurrency("AED");
        return e;
    }

    private VirtualAccount ihb(String number, String balance, LegalEntity owner) {
        VirtualAccount va = VirtualAccount.builder().vaNumber(number).currencyCode("AED").corporateId(mnc)
            .owningEntityId(owner.getId()).currentBalance(new BigDecimal(balance)).build();
        va.setId(UUID.randomUUID());
        va.setIhbParticipant(true);
        va.setMirrorAccountType(MirrorAccountType.IHB_CURRENT);
        when(vas.findById(va.getId())).thenReturn(Optional.of(va));
        return va;
    }

    private VirtualAccount mirror(MirrorAccountType type, String balance, VirtualAccount ihbAccount) {
        VirtualAccount va = VirtualAccount.builder().vaNumber(type + "-" + ihbAccount.getVaNumber()).currencyCode("AED")
            .corporateId(mnc).owningEntityId(treasury.getId()).currentBalance(new BigDecimal(balance)).build();
        va.setId(UUID.randomUUID());
        va.setMirrorAccountType(type);
        va.setMirrorsVaId(ihbAccount.getId());
        return va;
    }

    private Receivable receivable(String number, LegalEntity owner, LegalEntity debtor, String amount,
                                  Receivable.ReceivableStatus status) {
        Receivable r = Receivable.createIntercompanyReceivable(mnc, owner.getId(), owner.getEntityCode(),
            owner.getEntityName(), debtor.getId(), debtor.getEntityCode(), debtor.getEntityName(), null, "goods",
            new BigDecimal(amount), "AED");
        r.setId(UUID.randomUUID());
        r.setReceivableNumber(number);
        r.setStatus(status);
        return r;
    }

    private Payable payable(String number, LegalEntity owner, LegalEntity creditor, String amount) {
        Payable p = Payable.createIntercompanyPayable(mnc, owner.getId(), owner.getEntityCode(), owner.getEntityName(),
            creditor.getId(), creditor.getEntityCode(), creditor.getEntityName(), null, "goods", new BigDecimal(amount), "AED");
        p.setId(UUID.randomUUID());
        p.setPayableNumber(number);
        p.setStatus(Payable.PayableStatus.APPROVED);
        return p;
    }

    /** Treasury paid (or, for a COBO collection, collected) for MNC-UK. */
    private IntercompanyRecharge recharge(String reference, RechargeType type, RechargeStatus status, String amount) {
        IntercompanyRecharge r = IntercompanyRecharge.builder().rechargeReference(reference)
            .payerEntityId(treasury.getId()).payerEntityCode(treasury.getEntityCode())
            .behalfEntityId(uk.getId()).behalfEntityCode(uk.getEntityCode())
            .rechargeType(type).status(status).totalRecharge(new BigDecimal(amount)).currencyCode("AED").build();
        r.setId(UUID.randomUUID());
        return r;
    }

    private NettingCycle cycle(String reference, NettingCycle.CycleStatus status) {
        NettingCycle c = new NettingCycle();
        c.setId(UUID.randomUUID());
        c.setCycleReference(reference);
        c.setCycleName(reference);
        c.setStatus(status);
        c.setCorporateId(mnc);
        when(cycles.findById(c.getId())).thenReturn(Optional.of(c));
        return c;
    }
}
