package com.bank.vam.service.treasury;

import com.bank.vam.entity.intercompany.IntercompanyTransaction;
import com.bank.vam.entity.pobo.IntercompanyRecharge;
import com.bank.vam.entity.treasury.NettingCycle;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.pobo.IntercompanyRechargeRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * POBO spec R11 / D8: a POBO payment is already posted to the subsidiary's IHB current account, so netting never
 * picks it up again -- not its recharge, not the IC records the posting writes. Otherwise the PAY settlement leg
 * debits the IHB current account a second time for the same payment.
 */
class NettingPoboExclusionTest {

    private final IntercompanyRechargeRepository recharges = mock(IntercompanyRechargeRepository.class);
    private final com.bank.vam.repository.hierarchy.LegalEntityRepository entities =
        mock(com.bank.vam.repository.hierarchy.LegalEntityRepository.class);
    private final NettingService service = new NettingService(null, mock(com.bank.vam.repository.treasury.NettingEntryRepository.class),
        null, null, recharges, null, null, null, null, null, mock(FxRateService.class), null, entities, null, null, null, null, null);

    private IntercompanyRecharge recharge(IntercompanyRecharge.RechargeType type) {
        IntercompanyRecharge r = IntercompanyRecharge.builder()
            .rechargeReference("RCH-" + type).rechargeType(type)
            .payerEntityId(UUID.randomUUID()).behalfEntityId(UUID.randomUUID())
            .totalRecharge(new BigDecimal("500")).currencyCode("AED")
            .status(IntercompanyRecharge.RechargeStatus.APPROVED).build();
        r.setId(UUID.randomUUID());
        return r;
    }

    @Test
    void aCycleTakesManualRechargesButNoPoboPaymentRecharge() {
        IntercompanyRecharge pobo = recharge(IntercompanyRecharge.RechargeType.POBO_PAYMENT);
        IntercompanyRecharge manual = recharge(null);
        when(recharges.findAll()).thenReturn(List.of(pobo, manual));
        NettingCycle cycle = new NettingCycle();
        UUID corporate = UUID.randomUUID();
        when(entities.findByCorporateIdOrderByHierarchyPath(corporate)).thenReturn(java.util.stream.Stream.of(pobo, manual)
            .flatMap(r -> java.util.stream.Stream.of(r.getPayerEntityId(), r.getBehalfEntityId()))
            .map(id -> { var e = new com.bank.vam.entity.hierarchy.LegalEntity(); e.setId(id); return e; }).toList());

        assertThat(service.addPoboRecharges(cycle, corporate, false)).isEqualTo(1);
        assertThat(cycle.getEntries()).extracting(e -> e.getIntercompanyRechargeId()).containsExactly(manual.getId());
    }

    @Test
    void aPoboRechargeOrPostingRecordCannotBeAddedByHand() {
        assertThatThrownBy(() -> service.addRechargeToCycle(UUID.randomUUID(), recharge(IntercompanyRecharge.RechargeType.POBO_PAYMENT)))
            .isInstanceOf(BusinessException.class).hasMessageContaining("In-House Bank position");

        IntercompanyTransaction record = IntercompanyTransaction.builder()
            .transactionRef("ICR-IHBPOBO-1").originalReferenceType("POBO_6LEG_RECEIVABLE").build();
        assertThatThrownBy(() -> service.addTransactionToCycle(UUID.randomUUID(), record))
            .isInstanceOf(BusinessException.class).hasMessageContaining("In-House Bank position");
    }

    @Test
    void aNewDraftCycleTakesARechargeByHandAsTheBulkScanWould() {
        com.bank.vam.repository.treasury.NettingCycleRepository cycles = mock(com.bank.vam.repository.treasury.NettingCycleRepository.class);
        NettingService netting = new NettingService(cycles, mock(com.bank.vam.repository.treasury.NettingEntryRepository.class),
            null, null, recharges, null, null, null, null, null, mock(FxRateService.class), null, null, null, null, null, null, null);
        NettingCycle draft = new NettingCycle(); // createCycle makes DRAFT; nothing ever sets OPEN
        draft.setBaseCurrency("AED");
        UUID id = UUID.randomUUID();
        when(cycles.findById(id)).thenReturn(java.util.Optional.of(draft));
        IntercompanyRecharge manual = recharge(null);

        assertThat(NettingService.awaitingNetting(manual)).isTrue();
        assertThat(NettingService.awaitingNetting(recharge(IntercompanyRecharge.RechargeType.POBO_PAYMENT))).isFalse();
        netting.addRechargeToCycle(id, manual);
        assertThat(draft.getEntries()).extracting(e -> e.getIntercompanyRechargeId()).containsExactly(manual.getId());

        draft.setStatus(NettingCycle.CycleStatus.PENDING_APPROVAL);
        assertThatThrownBy(() -> netting.addRechargeToCycle(id, recharge(null)))
            .isInstanceOf(BusinessException.class).hasMessageContaining("not open");
    }

    @Test
    void entriesWhoseSourceWasSettledOutsideNettingAreNamed() {
        var entries = mock(com.bank.vam.repository.treasury.NettingEntryRepository.class);
        var payables = mock(com.bank.vam.repository.payables.PayableRepository.class);
        var receivables = mock(com.bank.vam.repository.receivables.ReceivableRepository.class);
        NettingService netting = new NettingService(null, entries, payables, receivables, recharges, null, null, null,
            null, null, mock(FxRateService.class), null, null, null, null, null, null, null);
        IntercompanyRecharge cutOver = recharge(IntercompanyRecharge.RechargeType.POBO_PAYMENT); // NET-202602-003's case
        cutOver.setStatus(IntercompanyRecharge.RechargeStatus.SETTLED);
        cutOver.setSettlementReference("IHB-POSITION-CUTOVER");
        IntercompanyRecharge referenceOnly = recharge(null);
        referenceOnly.setSettlementReference("SETTLE-20260912-000002");
        IntercompanyRecharge live = recharge(null);
        com.bank.vam.entity.payables.Payable paid = com.bank.vam.entity.payables.Payable.builder()
            .payableNumber("PAY-1").status(com.bank.vam.entity.payables.Payable.PayableStatus.PAID).build();
        paid.setId(UUID.randomUUID());
        com.bank.vam.entity.receivables.Receivable open = com.bank.vam.entity.receivables.Receivable.builder()
            .receivableNumber("RCV-1").status(com.bank.vam.entity.receivables.Receivable.ReceivableStatus.OPEN).build();
        open.setId(UUID.randomUUID());
        com.bank.vam.entity.receivables.Receivable netted = com.bank.vam.entity.receivables.Receivable.builder()
            .receivableNumber("RCV-2").status(com.bank.vam.entity.receivables.Receivable.ReceivableStatus.NETTED).build();
        netted.setId(UUID.randomUUID());
        for (IntercompanyRecharge r : List.of(cutOver, referenceOnly, live)) {
            when(recharges.findById(r.getId())).thenReturn(java.util.Optional.of(r));
        }
        when(payables.findById(paid.getId())).thenReturn(java.util.Optional.of(paid));
        when(receivables.findById(open.getId())).thenReturn(java.util.Optional.of(open));
        when(receivables.findById(netted.getId())).thenReturn(java.util.Optional.of(netted));
        UUID cycleId = UUID.randomUUID();
        when(entries.findByCycleId(cycleId)).thenReturn(List.of(
            entry("ENT-1", "RCH-CUT", e -> e.setIntercompanyRechargeId(cutOver.getId())),
            entry("ENT-2", "RCH-REF", e -> e.setIntercompanyRechargeId(referenceOnly.getId())),
            entry("ENT-3", "RCH-LIVE", e -> e.setIntercompanyRechargeId(live.getId())),
            entry("ENT-4", "PAY-1", e -> e.setPayableId(paid.getId())),
            entry("ENT-5", "RCV-1", e -> e.setReceivableId(open.getId())),
            entry("ENT-6", "RCV-2", e -> e.setReceivableId(netted.getId()))));

        assertThat(netting.entriesSettledAtSource(cycleId))
            .containsExactly("ENT-1 (RCH-CUT)", "ENT-2 (RCH-REF)", "ENT-4 (PAY-1)", "ENT-6 (RCV-2)");
    }

    private static com.bank.vam.entity.treasury.NettingEntry entry(String ref, String source,
                                                                    java.util.function.Consumer<com.bank.vam.entity.treasury.NettingEntry> link) {
        com.bank.vam.entity.treasury.NettingEntry e = new com.bank.vam.entity.treasury.NettingEntry();
        e.setEntryReference(ref);
        e.setSourceReference(source);
        link.accept(e);
        return e;
    }
}
