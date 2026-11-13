package com.bank.vam.service.treasury;

import com.bank.vam.config.HomeBankProperties;
import com.bank.vam.dto.treasury.NettingDto;
import com.bank.vam.dto.treasury.NettingDto.LegKind;
import com.bank.vam.entity.PhysicalAccount;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.payables.Payable;
import com.bank.vam.entity.pobo.IntercompanyRecharge;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.entity.treasury.NettingCycle;
import com.bank.vam.entity.treasury.NettingEntry;
import com.bank.vam.entity.treasury.NettingSettlement;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.iso20022.dto.Iso20022PaymentDto.OutwardPaymentRequest;
import com.bank.vam.iso20022.dto.Iso20022PaymentDto.OutwardPaymentResponse;
import com.bank.vam.iso20022.service.Iso20022OutwardPaymentService;
import com.bank.vam.repository.PhysicalAccountRepository;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.payables.PayableRepository;
import com.bank.vam.repository.pobo.IntercompanyRechargeRepository;
import com.bank.vam.repository.receivables.ReceivableRepository;
import com.bank.vam.repository.treasury.NettingCycleRepository;
import com.bank.vam.repository.treasury.NettingEntryRepository;
import com.bank.vam.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Netting settles per entity net, leg by leg (IHB/IC spec D5, R10, R11; Phase 2): a ledger transfer when both accounts
 * are IHB current accounts or sit on the same bank account, otherwise an outward payment of the net; a payer at another
 * bank is refused; the preview is the posting; a refused or failed attempt posts nothing and flags the cycle. Also the
 * populate rules: overdue invoices, one corporate, a linked invoice once.
 */
class NettingSettlementTest {

    private final NettingCycleRepository cycles = mock(NettingCycleRepository.class);
    private final NettingEntryRepository entries = mock(NettingEntryRepository.class);
    private final PayableRepository payables = mock(PayableRepository.class);
    private final ReceivableRepository receivables = mock(ReceivableRepository.class);
    private final IntercompanyRechargeRepository recharges = mock(IntercompanyRechargeRepository.class);
    private final VirtualAccountRepository vas = mock(VirtualAccountRepository.class);
    private final TransactionRepository ledger = mock(TransactionRepository.class);
    private final LegalEntityRepository entities = mock(LegalEntityRepository.class);
    private final TransactionService transactions = mock(TransactionService.class);
    private final Iso20022OutwardPaymentService outward = mock(Iso20022OutwardPaymentService.class);
    private final PhysicalAccountRepository physicals = mock(PhysicalAccountRepository.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final HomeBankProperties homeBank = new HomeBankProperties();
    private final NettingService service = new NettingService(cycles, entries, payables, receivables, recharges, vas,
        ledger, mock(FeePostingService.class), null, mock(FxRateService.class), new IhbMirrorService(vas, ledger), entities,
        transactions, outward, physicals, homeBank, txManager);

    private final List<VirtualAccount> accounts = new ArrayList<>();
    private final Map<UUID, VirtualAccount> shadowOf = new HashMap<>();
    private final UUID corporate = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        homeBank.setBic("EBILAEADXXX");
        when(vas.findAll()).thenReturn(accounts);
        when(vas.save(any())).thenAnswer(i -> i.getArgument(0));
        when(ledger.save(any())).thenAnswer(i -> {
            com.bank.vam.entity.Transaction t = i.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });
        when(transactions.payoutShadow(any())).thenAnswer(i -> {
            VirtualAccount s = shadowOf.get(((VirtualAccount) i.getArgument(0)).getId());
            if (s == null) throw new BusinessException("no bank account");
            return s;
        });
    }

    // ------------------------------------------------------------------------------------------------------------
    // Settlement legs
    // ------------------------------------------------------------------------------------------------------------

    @Test
    void aPairOnTheSameBankAccountIsALedgerTransfer() {
        UUID holding = UUID.randomUUID(), abuDhabi = UUID.randomUUID();
        VirtualAccount bank = shadow("SHADOW-AED-002", UUID.randomUUID(), "EBILAEADXXX");
        VirtualAccount payer = account("SETTLE-AED-3953", holding, "1000", bank);
        VirtualAccount receiver = account("SETTLE-AED-7523", abuDhabi, "0", bank);
        NettingCycle cycle = approved(net(holding, "MNC-HOLDING", "-300"), net(abuDhabi, "MNC-UAE-ABUDHABI", "300"));
        NettingEntry entry = entry(cycle, holding, abuDhabi, "300");

        NettingDto.SettleCycleResponse settled = service.settleCycle(cycle.getId());

        assertThat(settled.getLegs()).singleElement().satisfies(l -> {
            assertThat(l.getKind()).isEqualTo(LegKind.LEDGER);
            assertThat(l.getFromAccount()).isEqualTo("SETTLE-AED-3953");
            assertThat(l.getToAccount()).isEqualTo("SETTLE-AED-7523");
        });
        assertThat(payer.getCurrentBalance()).isEqualByComparingTo("700");
        assertThat(receiver.getCurrentBalance()).isEqualByComparingTo("300");
        verifyNoInteractions(outward);
        assertThat(cycle.getStatus()).isEqualTo(NettingCycle.CycleStatus.SETTLED);
        assertThat(entry.getStatus()).isEqualTo(NettingEntry.EntryStatus.SETTLED);
        assertThat(cycle.getSettlements()).allMatch(s -> s.getSettlementStatus() == NettingSettlement.SettlementStatus.SETTLED);
    }

    @Test
    void twoIhbCurrentAccountsAreALedgerTransferWithoutAnyBankAccount() {
        UUID uk = UUID.randomUUID(), dubai = UUID.randomUUID();
        VirtualAccount ukIhb = account("IHB-MNC-UK-AED-0002", uk, "100", null);
        ukIhb.setIhbParticipant(true);
        VirtualAccount dubaiIhb = account("IHB-MNC-UAE-DUBAI-AED-0001", dubai, "14.48", null);
        dubaiIhb.setIhbParticipant(true);
        NettingCycle cycle = approved(net(uk, "MNC-UK", "-40"), net(dubai, "MNC-UAE-DUBAI", "40"));

        assertThat(service.previewSettlement(cycle.getId()).getLegs()).singleElement()
            .satisfies(l -> assertThat(l.getKind()).isEqualTo(LegKind.LEDGER));
        service.settleCycle(cycle.getId());

        assertThat(ukIhb.getCurrentBalance()).isEqualByComparingTo("60");
        assertThat(dubaiIhb.getCurrentBalance()).isEqualByComparingTo("54.48");
        verifyNoInteractions(outward);
    }

    @Test
    void aPairOnDifferentBankAccountsIsAnOutwardPaymentOfTheNet() {
        UUID uae = UUID.randomUUID(), uk = UUID.randomUUID();
        UUID ukBank = UUID.randomUUID();
        VirtualAccount payer = account("SETTLE-AED-1", uae, "5000", shadow("SHADOW-AED-1", UUID.randomUUID(), "EBILAEADXXX"));
        VirtualAccount receiver = account("SETTLE-AED-2", uk, "0", shadow("SHADOW-AED-2", ukBank, "HBUKGB4BXXX"));
        PhysicalAccount ukAccount = PhysicalAccount.builder().accountNumber("PA-2").iban("GB82WEST12345698765432")
            .bankCode("HBUKGB4BXXX").build();
        when(physicals.findById(ukBank)).thenReturn(Optional.of(ukAccount));
        when(outward.processOutwardPayment(any())).thenReturn(OutwardPaymentResponse.builder().success(true)
            .transactionId(UUID.randomUUID()).transactionReference("PMT-1").build());
        NettingCycle cycle = approved(net(uae, "MNC-UAE", "-1250.50"), net(uk, "MNC-UK", "1250.50"));

        NettingDto.SettleCycleResponse settled = service.settleCycle(cycle.getId());

        ArgumentCaptor<OutwardPaymentRequest> sent = ArgumentCaptor.forClass(OutwardPaymentRequest.class);
        verify(outward).processOutwardPayment(sent.capture());
        assertThat(sent.getValue().getSourceVaId()).isEqualTo(payer.getId());
        assertThat(sent.getValue().getAmount()).isEqualByComparingTo("1250.50");
        assertThat(sent.getValue().getCurrency()).isEqualTo("AED");
        assertThat(sent.getValue().getCreditorAccount()).isEqualTo("GB82WEST12345698765432");
        assertThat(sent.getValue().getCreditorBic()).isEqualTo("HBUKGB4BXXX");
        assertThat(sent.getValue().getRemittanceInfo()).isEqualTo(cycle.getCycleReference());
        assertThat(settled.getLegs()).singleElement().satisfies(l -> {
            assertThat(l.getKind()).isEqualTo(LegKind.PAYMENT);
            assertThat(l.getTransactionReference()).isEqualTo("PMT-1");
        });
        // The receiver is credited when the payment comes in, not by netting; netting books nothing itself here.
        assertThat(receiver.getCurrentBalance()).isEqualByComparingTo("0");
        verify(ledger, never()).save(any());
        assertThat(cycle.getStatus()).isEqualTo(NettingCycle.CycleStatus.SETTLED);
    }

    @Test
    void aPayerAtAnotherBankIsRefusedAndNothingPosts() {
        UUID albion = UUID.randomUUID(), uk = UUID.randomUUID();
        account("SETTLE-GBP-BARC", albion, "900", shadow("SHADOW-GBP-BARC", UUID.randomUUID(), "BARCGB22XXX"));
        account("SETTLE-GBP-HB", uk, "0", shadow("SHADOW-GBP-HB", UUID.randomUUID(), "EBILAEADXXX"));
        NettingCycle cycle = approved(net(albion, "ALB-UK", "-500"), net(uk, "MNC-UK", "500"));
        cycle.setBaseCurrency("GBP");

        NettingDto.SettlementPreview preview = service.previewSettlement(cycle.getId());
        assertThat(preview.isSettleable()).isFalse();
        assertThat(preview.getLegs()).singleElement().satisfies(l -> {
            assertThat(l.getKind()).isEqualTo(LegKind.REFUSED);
            assertThat(l.getReason()).contains("payer's account is at another bank")
                .contains("outside netting or through an external-bank instruction");
        });

        assertThatThrownBy(() -> service.settleCycle(cycle.getId()))
            .isInstanceOf(BusinessException.class).hasMessageContaining("nothing was posted")
            .hasMessageContaining("ALB-UK to MNC-UK 500 GBP: payer's account is at another bank");
        verifyNoInteractions(outward);
        verify(vas, never()).save(any());
        assertThat(cycle.getStatus()).isEqualTo(NettingCycle.CycleStatus.APPROVED);
        assertThat(cycle.getSettlementIssue()).contains("payer's account is at another bank");
    }

    @Test
    void thePreviewIsWhatPosts() {
        UUID treasury = UUID.randomUUID(), uae = UUID.randomUUID(), dubai = UUID.randomUUID(), uk = UUID.randomUUID();
        UUID homeAccount = UUID.randomUUID(), ukBank = UUID.randomUUID();
        account("SETTLE-T", treasury, "0", shadow("S-T", homeAccount, "EBILAEADXXX"));
        account("SETTLE-UAE", uae, "1000", shadow("S-UAE", homeAccount, "EBILAEADXXX"));
        account("SETTLE-DXB", dubai, "1000", shadow("S-DXB", homeAccount, "EBILAEADXXX"));
        account("SETTLE-UK", uk, "0", shadow("S-UK", ukBank, "HBUKGB4BXXX"));
        when(physicals.findById(ukBank)).thenReturn(Optional.of(PhysicalAccount.builder().accountNumber("PA-UK")
            .iban("GB00UK").bankCode("HBUKGB4BXXX").build()));
        when(outward.processOutwardPayment(any())).thenReturn(OutwardPaymentResponse.builder().success(true)
            .transactionId(UUID.randomUUID()).transactionReference("PMT-9").build());
        NettingCycle cycle = approved(net(treasury, "MNC-TREASURY", "250"), net(uae, "MNC-UAE", "-400"),
            net(dubai, "MNC-UAE-DUBAI", "-100"), net(uk, "MNC-UK", "250"));

        NettingDto.SettlementPreview preview = service.previewSettlement(cycle.getId());
        NettingDto.SettleCycleResponse settled = service.settleCycle(cycle.getId());

        assertThat(legs(settled.getLegs())).isEqualTo(legs(preview.getLegs()))
            .containsExactly("MNC-UAE>MNC-TREASURY 250 LEDGER SETTLE-UAE>SETTLE-T",
                "MNC-UAE>MNC-UK 150 PAYMENT SETTLE-UAE>SETTLE-UK",
                "MNC-UAE-DUBAI>MNC-UK 100 PAYMENT SETTLE-DXB>SETTLE-UK");
        // Per entity the legs add up to its net.
        assertThat(settled.getLegs().stream().filter(l -> l.getPayerEntityCode().equals("MNC-UAE"))
            .map(NettingDto.SettlementLeg::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("400");
    }

    @Test
    void aFailedPaymentLeavesTheCycleFlaggedAndNothingSettled() {
        UUID uae = UUID.randomUUID(), uk = UUID.randomUUID(), ukBank = UUID.randomUUID();
        account("SETTLE-UAE", uae, "10", shadow("S-UAE", UUID.randomUUID(), "EBILAEADXXX"));
        account("SETTLE-UK", uk, "0", shadow("S-UK", ukBank, "HBUKGB4BXXX"));
        when(physicals.findById(ukBank)).thenReturn(Optional.of(PhysicalAccount.builder().accountNumber("PA-UK")
            .iban("GB00UK").bankCode("HBUKGB4BXXX").build()));
        when(outward.processOutwardPayment(any())).thenReturn(OutwardPaymentResponse.builder().success(false)
            .statusCode("RJCT").errorMessage("Payment rejected: insufficient funds").build());
        NettingCycle cycle = approved(net(uae, "MNC-UAE", "-500"), net(uk, "MNC-UK", "500"));
        NettingEntry entry = entry(cycle, uae, uk, "500");

        assertThatThrownBy(() -> service.settleCycle(cycle.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("the payment MNC-UAE to MNC-UK 500 AED failed: Payment rejected: insufficient funds");

        verify(txManager).rollback(any());   // the attempt's own transaction is rolled back
        assertThat(cycle.getStatus()).isEqualTo(NettingCycle.CycleStatus.APPROVED);
        assertThat(cycle.getSettlementIssue()).contains("insufficient funds");
        assertThat(entry.getStatus()).isEqualTo(NettingEntry.EntryStatus.INCLUDED);
        assertThat(cycle.getSettlements()).allMatch(s -> s.getSettlementStatus() == NettingSettlement.SettlementStatus.PENDING);
        verify(receivables, never()).save(any());
    }

    @Test
    void aCycleHoldingEntriesSettledAtSourceIsRefusedNamingThem() {
        UUID dubai = UUID.randomUUID(), treasury = UUID.randomUUID();
        VirtualAccount bank = shadow("S", UUID.randomUUID(), "EBILAEADXXX");
        account("IHB-DXB", dubai, "0", bank);
        account("SETTLE-T", treasury, "0", bank);
        NettingCycle cycle = approved(net(dubai, "MNC-UAE-DUBAI", "-151"), net(treasury, "MNC-TREASURY", "151"));
        cycle.setCycleReference("NET-202602-003");
        IntercompanyRecharge pobo = IntercompanyRecharge.builder().rechargeReference("RCH-IHBPOBO-1770051223508-5266")
            .rechargeType(IntercompanyRecharge.RechargeType.POBO_PAYMENT)
            .status(IntercompanyRecharge.RechargeStatus.APPROVED).build(); // locally never cut over: still refused
        pobo.setId(UUID.randomUUID());
        when(recharges.findById(pobo.getId())).thenReturn(Optional.of(pobo));
        NettingEntry e = entry(cycle, dubai, treasury, "151");
        e.setEntryReference("ENT-000001");
        e.setSourceReference(pobo.getRechargeReference());
        e.setIntercompanyRechargeId(pobo.getId());

        NettingDto.SettlementPreview preview = service.previewSettlement(cycle.getId());
        assertThat(preview.getStaleEntries()).containsExactly("ENT-000001 (RCH-IHBPOBO-1770051223508-5266)");
        assertThat(preview.isSettleable()).isFalse();
        assertThatThrownBy(() -> service.settleCycle(cycle.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("entries already settled outside netting: ENT-000001 (RCH-IHBPOBO-1770051223508-5266)");
        verify(vas, never()).save(any());
        assertThat(cycle.getStatus()).isEqualTo(NettingCycle.CycleStatus.APPROVED);
    }

    @Test
    void aLedgerLegFromAnIhbCurrentAccountMustFitItsCreditHeadroom() {
        UUID uae = UUID.randomUUID(), abuDhabi = UUID.randomUUID();
        VirtualAccount bank = shadow("S", UUID.randomUUID(), "EBILAEADXXX");
        VirtualAccount ihb = account("IHB-MNC-UAE-AED-0001", uae, "-7212", bank);
        ihb.setIhbParticipant(true);
        ihb.setOwningEntityCode("MNC-UAE");
        ihb.setEffectiveCreditLimit(new BigDecimal("25000"));   // headroom 17,788
        account("SETTLE-AED-7523", abuDhabi, "0", bank);

        NettingCycle within = approved(net(uae, "MNC-UAE", "-13000"), net(abuDhabi, "MNC-UAE-ABUDHABI", "13000"));
        assertThat(service.previewSettlement(within.getId()).getLegs()).singleElement()
            .satisfies(l -> assertThat(l.getKind()).isEqualTo(LegKind.LEDGER));
        service.settleCycle(within.getId());
        assertThat(ihb.getCurrentBalance()).isEqualByComparingTo("-20212");

        NettingCycle over = approved(net(uae, "MNC-UAE", "-5000"), net(abuDhabi, "MNC-UAE-ABUDHABI", "5000")); // headroom now 4,788
        NettingDto.SettlementPreview preview = service.previewSettlement(over.getId());
        assertThat(preview.isSettleable()).isFalse();
        assertThat(preview.getLegs()).singleElement().satisfies(l -> {
            assertThat(l.getKind()).isEqualTo(LegKind.REFUSED);
            assertThat(l.getReason()).contains("over MNC-UAE's IHB credit headroom of 4788");
        });
        assertThatThrownBy(() -> service.settleCycle(over.getId())).isInstanceOf(BusinessException.class)
            .hasMessageContaining("IHB credit headroom");
        assertThat(ihb.getCurrentBalance()).isEqualByComparingTo("-20212");
    }

    @Test
    void aLedgerLegFromAPlainAccountMustFitItsAvailableBalance() {
        UUID holding = UUID.randomUUID(), abuDhabi = UUID.randomUUID();
        VirtualAccount bank = shadow("S", UUID.randomUUID(), "EBILAEADXXX");
        VirtualAccount payer = account("SETTLE-AED-3953", holding, "15", bank);
        account("SETTLE-AED-7523", abuDhabi, "0", bank);
        NettingCycle cycle = approved(net(holding, "MNC-HOLDING", "-15000"), net(abuDhabi, "MNC-UAE-ABUDHABI", "15000"));

        NettingDto.SettlementPreview preview = service.previewSettlement(cycle.getId());

        assertThat(preview.getLegs()).singleElement().satisfies(l -> {
            assertThat(l.getKind()).isEqualTo(LegKind.REFUSED);
            assertThat(l.getReason()).isEqualTo("MNC-HOLDING's SETTLE-AED-3953 has AED 15.00 available; this leg needs AED 15,000.00");
        });
        assertThat(preview.getBlockers()).singleElement().asString()
            .contains("MNC-HOLDING to MNC-UAE-ABUDHABI 15000 AED: MNC-HOLDING's SETTLE-AED-3953 has AED 15.00 available");
        assertThatThrownBy(() -> service.settleCycle(cycle.getId())).isInstanceOf(BusinessException.class)
            .hasMessageContaining("nothing was posted").hasMessageContaining("has AED 15.00 available");
        assertThat(payer.getCurrentBalance()).isEqualByComparingTo("15");
        verify(vas, never()).save(any());
    }

    @Test
    void severalLegsFromOneAccountAreCheckedTogether() {
        UUID uae = UUID.randomUUID(), treasury = UUID.randomUUID(), abuDhabi = UUID.randomUUID();
        VirtualAccount bank = shadow("S", UUID.randomUUID(), "EBILAEADXXX");
        account("SETTLE-UAE", uae, "500", bank);   // 300 + 300 > 500: each leg alone would pass
        account("SETTLE-T", treasury, "0", bank);
        account("SETTLE-AD", abuDhabi, "0", bank);
        NettingCycle cycle = approved(net(uae, "MNC-UAE", "-600"), net(treasury, "MNC-TREASURY", "300"),
            net(abuDhabi, "MNC-UAE-ABUDHABI", "300"));

        List<NettingDto.SettlementLeg> legs = service.previewSettlement(cycle.getId()).getLegs();

        assertThat(legs).extracting(NettingDto.SettlementLeg::getKind).containsExactly(LegKind.LEDGER, LegKind.REFUSED);
        assertThat(legs.get(1).getReason())
            .isEqualTo("MNC-UAE's SETTLE-UAE has AED 500.00 available; this leg and its earlier ones need AED 600.00");
    }

    @Test
    void aSettledCycleCannotBeRecalculatedIntoASecondSettlement() {
        NettingCycle cycle = approved();
        cycle.setStatus(NettingCycle.CycleStatus.SETTLED);
        when(cycles.findByIdWithEntries(cycle.getId())).thenReturn(Optional.of(cycle));
        assertThatThrownBy(() -> service.calculateNetting(cycle.getId()))
            .isInstanceOf(BusinessException.class).hasMessageContaining("cannot be recalculated");
    }

    // ------------------------------------------------------------------------------------------------------------
    // Populate: overdue invoices, one corporate, a linked invoice once
    // ------------------------------------------------------------------------------------------------------------

    @Test
    void overdueAndPartlyPaidInvoicesAreNettable() {
        assertThat(receivable(Receivable.ReceivableStatus.OVERDUE, "50000", null).canAddToNetting()).isTrue();
        assertThat(receivable(Receivable.ReceivableStatus.PARTIAL, "50000", "20000").canAddToNetting()).isTrue();
        assertThat(receivable(Receivable.ReceivableStatus.DISPUTED, "50000", null).canAddToNetting()).isFalse();
        assertThat(receivable(Receivable.ReceivableStatus.NETTED, "50000", null).canAddToNetting()).isFalse();
        Payable partlyPaid = payable(UUID.randomUUID(), UUID.randomUUID());
        partlyPaid.setStatus(Payable.PayableStatus.PARTIAL);
        assertThat(partlyPaid.canAddToNetting()).isTrue();

        LegalEntity a = entity(), b = entity();
        Receivable overdue = receivable(Receivable.ReceivableStatus.OVERDUE, "50000", null);
        overdue.setOwningEntityId(a.getId());
        overdue.setIntercompanyEntityId(b.getId());
        Receivable partial = receivable(Receivable.ReceivableStatus.PARTIAL, "22000", "7000");
        partial.setOwningEntityId(b.getId());
        partial.setIntercompanyEntityId(a.getId());
        when(entities.findByCorporateIdOrderByHierarchyPath(corporate)).thenReturn(List.of(a, b));
        when(receivables.findNettingEligibleReceivables(corporate)).thenReturn(List.of(overdue, partial));
        NettingCycle cycle = draft(corporate);

        assertThat(service.addIntercompanyReceivables(cycle, corporate)).isEqualTo(2);
        assertThat(cycle.getEntries()).extracting(NettingEntry::getGrossAmount)
            .usingElementComparator(BigDecimal::compareTo).containsExactlyInAnyOrder(new BigDecimal("50000"), new BigDecimal("7000"));
        assertThat(overdue.getStatus()).isEqualTo(Receivable.ReceivableStatus.PENDING_NETTING);
    }

    @Test
    void cyclesAndPopulateAreScopedToTheCycleCorporate() {
        UUID other = UUID.randomUUID();
        NettingCycle mine = draft(corporate);
        when(cycles.findByCorporateIdOrderByCreatedAtDesc(corporate)).thenReturn(List.of(mine));
        assertThat(service.getAllCycles(corporate)).extracting(NettingDto.CycleResponse::getId).containsExactly(mine.getId());
        assertThat(service.getAllCycles(other)).isEmpty();

        assertThatThrownBy(() -> service.populateCycleWithAllObligations(mine.getId(), other, false))
            .isInstanceOf(BusinessException.class).hasMessageContaining("another corporate's");
        NettingCycle orphan = draft(null);
        assertThatThrownBy(() -> service.populateCycleWithAllObligations(orphan.getId(), corporate, false))
            .isInstanceOf(BusinessException.class).hasMessageContaining("belongs to no corporate");

        LegalEntity treasury = entity(), uk = entity();
        when(entities.findByCorporateIdOrderByHierarchyPath(corporate)).thenReturn(List.of(treasury, uk));
        IntercompanyRecharge ours = recharge(treasury.getId(), uk.getId());
        IntercompanyRecharge theirs = recharge(UUID.randomUUID(), UUID.randomUUID()); // another corporate's entities
        IntercompanyRecharge inACycle = recharge(treasury.getId(), uk.getId());
        when(entries.existsByIntercompanyRechargeId(inACycle.getId())).thenReturn(true);
        when(recharges.findAll()).thenReturn(List.of(ours, theirs, inACycle));

        NettingService.PopulationResult added = service.populateCycleWithAllObligations(mine.getId(), null, false);

        assertThat(added.poboRechargesAdded()).isEqualTo(1);
        assertThat(mine.getEntries()).extracting(NettingEntry::getIntercompanyRechargeId).containsExactly(ours.getId());
        verify(payables).findNettingEligiblePayables(corporate);
        verify(receivables).findNettingEligibleReceivables(corporate);

        NettingDto.CreateCycleRequest create = new NettingDto.CreateCycleRequest();
        create.setCycleName("Q4");
        create.setPeriodStart(java.time.LocalDate.of(2026, 11, 1));
        create.setPeriodEnd(java.time.LocalDate.of(2026, 11, 30));
        create.setBaseCurrency("AED");
        assertThatThrownBy(() -> service.createCycle(create)).isInstanceOf(BusinessException.class)
            .hasMessageContaining("corporateId is required");
        create.setCorporateId(corporate);
        when(cycles.save(any())).thenAnswer(i -> i.getArgument(0));
        assertThat(service.createCycle(create).getCorporateId()).isEqualTo(corporate);
    }

    @Test
    void aLinkedInvoiceIsNettedOnceAndAPayableWithoutAGroupCounterpartyNotAtAll() {
        LegalEntity uae = entity(), abuDhabi = entity();
        when(entities.findByCorporateIdOrderByHierarchyPath(corporate)).thenReturn(List.of(uae, abuDhabi));
        Receivable receivable = receivable(Receivable.ReceivableStatus.OVERDUE, "22000", null);
        receivable.setOwningEntityId(uae.getId());
        receivable.setIntercompanyEntityId(abuDhabi.getId());
        Payable otherSide = payable(abuDhabi.getId(), uae.getId());
        otherSide.setCounterpartyReceivableId(receivable.getId());   // the debtor's side of the same invoice
        Payable noCounterparty = payable(abuDhabi.getId(), null);    // PAY-2026-0003: counterparty entity unknown
        Payable separate = payable(abuDhabi.getId(), uae.getId());   // unlinked: an invoice of its own
        when(receivables.findIntercompanyReceivablesByCorporate(corporate)).thenReturn(List.of(receivable));
        when(receivables.findNettingEligibleReceivables(corporate)).thenReturn(List.of(receivable));
        when(payables.findNettingEligiblePayables(corporate)).thenReturn(List.of(otherSide, noCounterparty, separate));
        NettingCycle cycle = draft(corporate);

        NettingService.PopulationResult added = service.populateCycleWithAllObligations(cycle.getId(), corporate, false);

        assertThat(added.receivablesAdded()).isEqualTo(1);
        assertThat(added.payablesAdded()).isEqualTo(1);
        assertThat(cycle.getEntries()).extracting(e -> e.getPayableId() != null ? e.getPayableId() : e.getReceivableId())
            .containsExactlyInAnyOrder(receivable.getId(), separate.getId());
    }

    // ------------------------------------------------------------------------------------------------------------

    private static List<String> legs(List<NettingDto.SettlementLeg> legs) {
        return legs.stream().map(l -> l.getPayerEntityCode() + ">" + l.getReceiverEntityCode() + " "
            + l.getAmount().stripTrailingZeros().toPlainString() + " " + l.getKind() + " " + l.getFromAccount() + ">"
            + l.getToAccount()).collect(Collectors.toList());
    }

    private VirtualAccount account(String number, UUID owner, String balance, VirtualAccount bank) {
        VirtualAccount va = VirtualAccount.builder().vaNumber(number).currencyCode(bank != null ? bank.getCurrencyCode() : "AED")
            .owningEntityId(owner)
            .status(VirtualAccount.VaStatus.ACTIVE).currentBalance(new BigDecimal(balance))
            .availableBalance(new BigDecimal(balance)).settlementMark(true).build();
        va.setId(UUID.randomUUID());
        when(vas.findById(va.getId())).thenReturn(Optional.of(va));
        accounts.add(va);
        if (bank != null) shadowOf.put(va.getId(), bank);
        return va;
    }

    private VirtualAccount shadow(String number, UUID physicalAccount, String bic) {
        VirtualAccount s = VirtualAccount.builder().vaNumber(number).currencyCode(number.contains("GBP") ? "GBP" : "AED")
            .accountCategory(VirtualAccount.AccountCategory.PHYSICAL_MIRROR).linkedPhysicalAccountId(physicalAccount)
            .bankSwift(bic).status(VirtualAccount.VaStatus.ACTIVE).currentBalance(BigDecimal.ZERO).build();
        s.setId(UUID.randomUUID());
        return s;
    }

    private NettingSettlement net(UUID entity, String code, String net) {
        BigDecimal n = new BigDecimal(net);
        NettingSettlement s = NettingSettlement.builder().entityId(entity).entityCode(code).entityName(code)
            .grossReceivables(n.max(BigDecimal.ZERO)).grossPayables(n.min(BigDecimal.ZERO).negate()).build();
        s.setId(UUID.randomUUID());
        s.calculateNetPosition();
        return s;
    }

    private NettingCycle approved(NettingSettlement... nets) {
        NettingCycle cycle = new NettingCycle();
        cycle.setId(UUID.randomUUID());
        cycle.setCycleReference("NET-202611-001");
        cycle.setCorporateId(corporate);
        cycle.setStatus(NettingCycle.CycleStatus.APPROVED);
        for (NettingSettlement s : nets) {
            s.setCycle(cycle);
            cycle.getSettlements().add(s);
        }
        when(cycles.findByIdWithSettlements(cycle.getId())).thenReturn(Optional.of(cycle));
        when(cycles.findById(cycle.getId())).thenReturn(Optional.of(cycle));
        when(entries.findByCycleId(cycle.getId())).thenAnswer(i -> new ArrayList<>(cycle.getEntries()));
        return cycle;
    }

    private NettingCycle draft(UUID corporateId) {
        NettingCycle cycle = new NettingCycle();
        cycle.setId(UUID.randomUUID());
        cycle.setCycleReference("NET-202611-00" + (corporateId == null ? "9" : "2"));
        cycle.setCycleName("Q4");
        cycle.setCorporateId(corporateId);
        when(cycles.findByIdWithEntries(cycle.getId())).thenReturn(Optional.of(cycle));
        return cycle;
    }

    private NettingEntry entry(NettingCycle cycle, UUID payer, UUID payee, String amount) {
        NettingEntry e = NettingEntry.builder().cycle(cycle).entryReference("ENT-" + cycle.getEntries().size())
            .payerEntityId(payer).payerEntityCode("P").payeeEntityId(payee).payeeEntityCode("R")
            .grossAmount(new BigDecimal(amount)).baseAmount(new BigDecimal(amount)).currencyCode("AED")
            .status(NettingEntry.EntryStatus.INCLUDED).build();
        e.setId(UUID.randomUUID());
        cycle.getEntries().add(e);
        return e;
    }

    private Receivable receivable(Receivable.ReceivableStatus status, String net, String outstanding) {
        Receivable r = Receivable.builder().receivableNumber("IC-" + status).status(status).isIntercompany(true)
            .nettingEligible(true).netAmount(new BigDecimal(net)).currencyCode("AED").corporateId(corporate)
            .outstandingAmount(outstanding == null ? new BigDecimal(net) : new BigDecimal(outstanding)).build();
        r.setId(UUID.randomUUID());
        return r;
    }

    private Payable payable(UUID owner, UUID counterparty) {
        Payable p = Payable.builder().payableNumber("PAY-" + UUID.randomUUID()).status(Payable.PayableStatus.APPROVED)
            .isIntercompany(true).nettingEligible(true).owningEntityId(owner).owningEntityCode("O")
            .counterpartyEntityId(counterparty).counterpartyEntityCode(counterparty == null ? null : "C")
            .netAmount(new BigDecimal("100")).currencyCode("AED").corporateId(corporate).build();
        p.setId(UUID.randomUUID());
        return p;
    }

    private IntercompanyRecharge recharge(UUID payer, UUID behalf) {
        IntercompanyRecharge r = IntercompanyRecharge.builder().rechargeReference("RCH-" + UUID.randomUUID())
            .payerEntityId(payer).behalfEntityId(behalf).totalRecharge(new BigDecimal("75")).currencyCode("AED")
            .status(IntercompanyRecharge.RechargeStatus.APPROVED).build();
        r.setId(UUID.randomUUID());
        return r;
    }

    private static LegalEntity entity() {
        LegalEntity e = new LegalEntity();
        e.setId(UUID.randomUUID());
        return e;
    }
}
