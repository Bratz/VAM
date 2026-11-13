package com.bank.vam.service.pobo;

import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.MirrorAccountType;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.intercompany.IntercompanyTransaction;
import com.bank.vam.entity.payables.Payable;
import com.bank.vam.entity.payables.Payable.PayableStatus;
import com.bank.vam.entity.pobo.IntercompanyRecharge;
import com.bank.vam.entity.treasury.SweepRule;
import com.bank.vam.entity.treasury.SweepRuleSource;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.intercompany.IntercompanyTransactionRepository;
import com.bank.vam.repository.payables.PayableRepository;
import com.bank.vam.repository.pobo.IntercompanyRechargeRepository;
import com.bank.vam.repository.treasury.SweepRuleRepository;
import com.bank.vam.service.intercompany.IntercompanyTransactionService;
import com.bank.vam.service.treasury.IhbMirrorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * POBO cut-over and repair (spec D8, D9): a dry run changes nothing; a real run puts stuck POBO payables back to
 * Approved, sets the mirrors and the IHB credit use from the position, closes POBO recharges / IC records (then out of
 * IC positions), stops sweep rules on in-house accounts; a second run finds nothing; past self-sweeps are only reported.
 */
class PoboCutoverServiceTest {

    private final PayableRepository payables = mock(PayableRepository.class);
    private final PoboEnrolmentService enrolments = mock(PoboEnrolmentService.class);
    private final VirtualAccountRepository vas = mock(VirtualAccountRepository.class);
    private final TransactionRepository ledger = mock(TransactionRepository.class);
    private final IntercompanyRechargeRepository recharges = mock(IntercompanyRechargeRepository.class);
    private final IntercompanyTransactionRepository icRecords = mock(IntercompanyTransactionRepository.class);
    private final LegalEntityRepository entities = mock(LegalEntityRepository.class);
    private final SweepRuleRepository rules = mock(SweepRuleRepository.class);

    private final PoboCutoverService service = new PoboCutoverService(payables, enrolments, vas,
        new IhbMirrorService(vas, ledger), recharges, icRecords, entities, rules, ledger);

    private final UUID corp = UUID.randomUUID();
    private LegalEntity treasury, dubai;
    private VirtualAccount ihbCurrent, icReceivable, icPayable, treasurySettlement, ownAccount;
    private Payable pending, approved;
    private IntercompanyRecharge recharge;
    private IntercompanyTransaction record;
    private SweepRule selfSweep;

    @BeforeEach
    void setUp() {
        treasury = entity("MNC-TC");
        dubai = entity("MNC-UAE-DUBAI");
        when(entities.findByCorporateIdOrderByHierarchyPath(corp)).thenReturn(List.of(treasury, dubai));

        // Dubai's position is +14.48 but treasury's IC receivable says 3,369 (drifted on POBO legs only).
        ihbCurrent = va("IHB-DUBAI-AED", "14.48");
        ihbCurrent.setMirrorAccountType(MirrorAccountType.IHB_CURRENT);
        ihbCurrent.setIhbParticipant(true);
        ihbCurrent.setOwningEntityCode("MNC-UAE-DUBAI");
        ihbCurrent.setEffectiveCreditLimit(new BigDecimal("50000"));
        ihbCurrent.setCreditLimitUtilized(new BigDecimal("9000")); // only ever went up
        ihbCurrent.setCreditLimitAvailable(new BigDecimal("41000"));
        icReceivable = va("ICR-DUBAI", "3369");
        icReceivable.setMirrorAccountType(MirrorAccountType.IC_RECEIVABLE);
        icPayable = va("ICP-DUBAI", "0");
        icPayable.setMirrorAccountType(MirrorAccountType.IC_PAYABLE);
        ihbCurrent.setIcReceivableVaId(icReceivable.getId());
        ihbCurrent.setIcPayableVaId(icPayable.getId());
        treasurySettlement = va("TC-SETTLE-AED", "100000");
        treasurySettlement.setMirrorAccountType(MirrorAccountType.TREASURY_SETTLEMENT);
        ownAccount = va("DUBAI-OPS-AED", "5000");
        when(vas.findByCorporateId(corp)).thenReturn(List.of(ihbCurrent, icReceivable, icPayable, treasurySettlement, ownAccount));
        when(vas.save(any())).thenAnswer(i -> i.getArgument(0));
        when(ledger.save(any())).thenAnswer(i -> i.getArgument(0));

        pending = payable("PAY-1", PayableStatus.PENDING_POBO, Payable.PoboRequestStatus.PENDING_TREASURY_APPROVAL);
        approved = payable("PAY-2", PayableStatus.POBO_APPROVED, Payable.PoboRequestStatus.APPROVED);
        when(payables.findByCorporateIdAndStatus(corp, PayableStatus.PENDING_POBO)).thenAnswer(i -> only(pending, PayableStatus.PENDING_POBO));
        when(payables.findByCorporateIdAndStatus(corp, PayableStatus.POBO_APPROVED)).thenAnswer(i -> only(approved, PayableStatus.POBO_APPROVED));
        when(enrolments.route(any(), any(), any())).thenReturn(Optional.empty());

        recharge = IntercompanyRecharge.builder().rechargeReference("RCH-1")
            .rechargeType(IntercompanyRecharge.RechargeType.POBO_PAYMENT)
            .payerEntityId(treasury.getId()).payerEntityCode("MNC-TC").behalfEntityId(dubai.getId()).behalfEntityCode("MNC-UAE-DUBAI")
            .totalRecharge(new BigDecimal("700")).currencyCode("AED").status(IntercompanyRecharge.RechargeStatus.APPROVED).build();
        recharge.setId(UUID.randomUUID());
        when(recharges.findAll()).thenReturn(List.of(recharge));
        record = IntercompanyTransaction.builder().transactionRef("ICR-IHBPOBO-1").originalReferenceType("POBO_6LEG_RECEIVABLE")
            .payingEntityId(treasury.getId()).payingEntityCode("MNC-TC").behalfEntityId(dubai.getId()).behalfEntityCode("MNC-UAE-DUBAI")
            .netAmount(new BigDecimal("700")).currencyCode("AED").status(IntercompanyTransaction.TransactionStatus.PROCESSED).build();
        record.setId(UUID.randomUUID());
        when(icRecords.findAll()).thenReturn(List.of(record));

        selfSweep = new SweepRule();
        selfSweep.setId(UUID.randomUUID());
        selfSweep.setRuleReference("IHB-MNC-UAE-DUBAI-AED");
        selfSweep.setRuleName("IHB Sweep: Dubai -> Treasury (AED)");
        selfSweep.setStatus(SweepRule.SweepStatus.ACTIVE);
        SweepRuleSource source = new SweepRuleSource();
        source.setRule(selfSweep);
        source.setAccountId(ihbCurrent.getId());
        source.setAccountNumber(ihbCurrent.getVaNumber());
        selfSweep.setSourceAccounts(new ArrayList<>(List.of(source)));
        when(rules.findByCorporateIdOrUnset(corp)).thenReturn(List.of(selfSweep));

        // One past self-sweep: 25 out to treasury's settlement account and 25 credited straight back, same execution.
        Transaction out = Transaction.builder().movementType(Transaction.MovementType.SWEEP_OUT).vaId(ihbCurrent.getId())
            .amount(new BigDecimal("25")).externalReference("SWE-1").counterpartyVaId(treasurySettlement.getId())
            .referenceNumber("SWP-1").transactionDate(LocalDateTime.now()).build();
        Transaction back = Transaction.builder().movementType(Transaction.MovementType.SWEEP_IN).vaId(ihbCurrent.getId())
            .amount(new BigDecimal("25")).externalReference("SWE-1").referenceNumber("SWEEPIC17000000001234").build();
        when(ledger.findByVaId(ihbCurrent.getId())).thenReturn(List.of(out, back));
    }

    private List<Payable> only(Payable p, PayableStatus status) {
        return p.getStatus() == status ? List.of(p) : List.of();
    }

    private LegalEntity entity(String code) {
        LegalEntity e = new LegalEntity();
        e.setId(UUID.randomUUID());
        e.setEntityCode(code);
        e.setEntityName(code + " LLC");
        e.setCorporateId(corp);
        return e;
    }

    private VirtualAccount va(String number, String balance) {
        VirtualAccount va = VirtualAccount.builder().vaNumber(number).currencyCode("AED").corporateId(corp)
            .currentBalance(new BigDecimal(balance)).build();
        va.setId(UUID.randomUUID());
        when(vas.findById(va.getId())).thenReturn(Optional.of(va));
        return va;
    }

    private Payable payable(String number, PayableStatus status, Payable.PoboRequestStatus request) {
        Payable p = Payable.builder().payableNumber(number).status(status).poboRequestStatus(request)
            .paymentRoute(Payable.PaymentRoute.POBO).isPobo(true).poboRequestId(UUID.randomUUID())
            .owningEntityId(dubai.getId()).owningEntityName("MNC-UAE-DUBAI LLC").vendorName("Supplier")
            .virtualAccountId(ownAccount.getId()).netAmount(new BigDecimal("700")).paidAmount(BigDecimal.ZERO)
            .currencyCode("AED").build();
        p.setId(UUID.randomUUID());
        return p;
    }

    @Test
    void aDryRunReportsEverySectionAndChangesNothing() {
        PoboCutoverService.Report r = service.cutover(corp, true);

        assertThat(r.payables()).hasSize(2);
        assertThat(r.payables().get(0).detail()).startsWith("Pending POBO -> Approved; will pay directly from DUBAI-OPS-AED");
        assertThat(r.mirrors()).extracting(PoboCutoverService.Item::detail).satisfiesExactly(
            d -> assertThat(d).startsWith("IC receivable 3369 -> 0 to follow IHB-DUBAI-AED's position 14.48"),
            d -> assertThat(d).startsWith("IC payable 0 -> 14.48"));
        assertThat(r.recharges()).hasSize(2);
        assertThat(r.creditLimits()).singleElement().satisfies(i ->
            assertThat(i.detail()).startsWith("Credit used 9000 -> 0, available 41000 -> 50000"));
        assertThat(r.sweepRules()).singleElement().satisfies(i -> assertThat(i.detail()).startsWith("Rule disabled"));
        assertThat(r.pastSelfSweeps()).singleElement().satisfies(i -> {
            assertThat(i.amount()).isEqualByComparingTo("25");
            assertThat(i.detail()).contains("1 self-sweep(s)", "added 25 AED to TC-SETTLE-AED", "SWE-1", "Not changed");
        });

        assertThat(pending.getStatus()).isEqualTo(PayableStatus.PENDING_POBO);
        assertThat(icReceivable.getCurrentBalance()).isEqualByComparingTo("3369");
        assertThat(ihbCurrent.getCreditLimitUtilized()).isEqualByComparingTo("9000");
        assertThat(recharge.getStatus()).isEqualTo(IntercompanyRecharge.RechargeStatus.APPROVED);
        assertThat(selfSweep.getStatus()).isEqualTo(SweepRule.SweepStatus.ACTIVE);
        verify(payables, never()).save(any());
        verify(vas, never()).save(any());
        verify(ledger, never()).save(any());
        verify(recharges, never()).save(any());
        verify(icRecords, never()).save(any());
        verify(rules, never()).save(any());
    }

    @Test
    void aRealRunRepairsEverythingAndASecondRunFindsNothing() {
        PoboCutoverService.Report r = service.cutover(corp, false);
        assertThat(r.changes()).isEqualTo(2 + 2 + 2 + 1 + 1);

        assertThat(List.of(pending, approved)).allSatisfy(p -> {
            assertThat(p.getStatus()).isEqualTo(PayableStatus.APPROVED);
            assertThat(p.getPaymentRoute()).isEqualTo(Payable.PaymentRoute.DIRECT);
            assertThat(p.getPoboRequestStatus()).isEqualTo(Payable.PoboRequestStatus.NOT_REQUESTED);
            assertThat(p.getIsPobo()).isFalse();
            assertThat(p.canBePaid()).isTrue();
        });
        assertThat(icReceivable.getCurrentBalance()).as("R10: ICR = max(0, -P)").isEqualByComparingTo("0");
        assertThat(icPayable.getCurrentBalance()).as("ICP = max(0, P)").isEqualByComparingTo("14.48");
        assertThat(ihbCurrent.getCreditLimitUtilized()).isEqualByComparingTo("0");
        assertThat(ihbCurrent.getCreditLimitAvailable()).isEqualByComparingTo("50000");
        assertThat(recharge.getStatus()).isEqualTo(IntercompanyRecharge.RechargeStatus.SETTLED);
        assertThat(recharge.getSettlementReference()).isEqualTo(PoboCutoverService.SETTLED_BY_IHB);
        assertThat(record.getStatus()).isEqualTo(IntercompanyTransaction.TransactionStatus.SETTLED);
        assertThat(selfSweep.getStatus()).isEqualTo(SweepRule.SweepStatus.DISABLED);
        assertThat(ihbCurrent.getCurrentBalance()).as("the position itself never moves").isEqualByComparingTo("14.48");
        assertThat(treasurySettlement.getCurrentBalance()).as("past self-sweeps only reported").isEqualByComparingTo("100000");

        PoboCutoverService.Report again = service.cutover(corp, false);
        assertThat(again.changes()).isZero();
        assertThat(again.pastSelfSweeps()).hasSize(1);
    }

    @Test
    void anEnrolledOwnerIsReportedAsPaidOnBehalf() {
        VirtualAccount pay = va("TC-PAY-AED", "0");
        when(enrolments.route(dubai.getId(), "AED", LocalDate.now())).thenReturn(Optional.of(new PoboEnrolmentService.Route(
            null, dubai, treasury, pay, ihbCurrent)));

        assertThat(service.cutover(corp, true).payables()).allSatisfy(i ->
            assertThat(i.detail()).endsWith("will pay on behalf via MNC-TC from TC-PAY-AED, booked to IHB-DUBAI-AED"));
    }

    @Test
    void aClosedPoboRechargeNoLongerCountsInTheBilateralPosition() {
        service.cutover(corp, false);
        when(entities.findById(treasury.getId())).thenReturn(Optional.of(treasury));
        when(entities.findById(dubai.getId())).thenReturn(Optional.of(dubai));
        IntercompanyTransactionService ic = new IntercompanyTransactionService(icRecords, recharges, entities, payables,
            mock(com.bank.vam.repository.receivables.ReceivableRepository.class),
            mock(com.bank.vam.repository.treasury.NettingCycleRepository.class),
            mock(com.bank.vam.repository.treasury.NettingEntryRepository.class), vas, ledger, null, null, null);

        var position = ic.getBilateralPosition(treasury.getId(), dubai.getId());

        assertThat(position.getEntity1OwesEntity2()).isEqualByComparingTo("0");
        assertThat(position.getEntity2OwesEntity1()).isEqualByComparingTo("0");
        assertThat(ic.getNettingEligibleTransactions(corp, treasury.getId(), dubai.getId()).getItems()).isEmpty();
    }
}
