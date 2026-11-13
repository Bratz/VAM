package com.bank.vam.controller.intercompany;

import com.bank.vam.controller.pobo.PoboController;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.Transaction.MovementType;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.MirrorAccountType;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.intercompany.IntercompanyTransaction;
import com.bank.vam.entity.pobo.IntercompanyRecharge;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.intercompany.IntercompanyTransactionRepository;
import com.bank.vam.repository.pobo.IntercompanyRechargeRepository;
import com.bank.vam.service.intercompany.IntercompanyService;
import com.bank.vam.service.intercompany.IntercompanyTransactionService;
import com.bank.vam.service.pobo.PoboCutoverService;
import com.bank.vam.service.pobo.PoboService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * The Intercompany Dashboard showed every corporate TestMNC's entities and entity pairs (functional review C3): the
 * endpoints took a corporateId and ignored it. Each now returns the asked corporate's group only, and refuses a
 * request with no corporate. The headline figures come from the ledger (C1/C2/C6).
 */
class IntercompanyCorporateScopeTest {

    private final UUID mnc = UUID.randomUUID();
    private final UUID helios = UUID.randomUUID();

    private final LegalEntityRepository entities = mock(LegalEntityRepository.class);
    private final VirtualAccountRepository vas = mock(VirtualAccountRepository.class);
    private final TransactionRepository ledger = mock(TransactionRepository.class);
    private final IntercompanyTransactionRepository icRows = mock(IntercompanyTransactionRepository.class);
    private final IntercompanyRechargeRepository recharges = mock(IntercompanyRechargeRepository.class);

    private final IntercompanyService intercompany = new IntercompanyService(icRows, entities, vas, ledger, null, null);
    private final IntercompanyTransactionService icTransactions = new IntercompanyTransactionService(icRows, recharges,
        entities, null, null, null, null, vas, null, null);
    private final IntercompanyController controller = new IntercompanyController(intercompany, icTransactions, null);
    private final PoboController pobo = new PoboController(
        new PoboService(recharges, entities, null, null), mock(PoboCutoverService.class));

    private LegalEntity treasury, uk, heliosHq, heliosSub;

    @BeforeEach
    void setUp() {
        treasury = entity("MNC-TREASURY", mnc, true);
        uk = entity("MNC-UK", mnc, false);
        heliosHq = entity("HEL-HQ", helios, false);
        heliosSub = entity("HEL-SUB", helios, false);
        heliosHq.setIhbEnabled(false);
        heliosSub.setIhbEnabled(false);
        when(entities.findByCorporateIdOrderByHierarchyPath(mnc)).thenReturn(List.of(treasury, uk));
        when(entities.findByCorporateIdOrderByHierarchyPath(helios)).thenReturn(List.of(heliosHq, heliosSub));
        // What the endpoints used to read: every corporate's IHB entities.
        when(entities.findByIhbEnabledTrue()).thenReturn(new java.util.ArrayList<>(List.of(treasury, uk)));
        when(entities.findById(treasury.getId())).thenReturn(Optional.of(treasury));
        when(entities.findById(uk.getId())).thenReturn(Optional.of(uk));
    }

    @Test
    void entitiesAreTheCorporatesOwn() {
        assertThat(controller.getEntitiesWithPositions(helios, null).getBody().getData()).isEmpty();
        assertThat(controller.getEntitiesWithPositions(mnc, null).getBody().getData())
            .extracting(e -> e.getEntityCode()).containsExactly("MNC-TREASURY", "MNC-UK");
    }

    @Test
    void entityPairsAreTheCorporatesOwn() {
        IntercompanyTransaction row = IntercompanyTransaction.builder().transactionRef("ICR-1")
            .transactionType(IntercompanyTransaction.TransactionType.IC_RECEIVABLE)
            .payingEntityId(treasury.getId()).payingEntityCode("MNC-TREASURY")
            .behalfEntityId(uk.getId()).behalfEntityCode("MNC-UK")
            .amount(new BigDecimal("150")).netAmount(new BigDecimal("175")).currencyCode("AED")
            .status(IntercompanyTransaction.TransactionStatus.SETTLED).build();
        when(icRows.findAll()).thenReturn(List.of(row));

        assertThat(controller.getEntityPairs(helios).getBody().getData()).isEmpty();
        assertThat(controller.getEntityPairs(mnc).getBody().getData()).hasSize(1);
    }

    @Test
    void pendingRechargesAreTheCorporatesOwn() {
        IntercompanyRecharge r = IntercompanyRecharge.builder().rechargeReference("RCH-1")
            .payerEntityId(treasury.getId()).behalfEntityId(uk.getId())
            .totalRecharge(new BigDecimal("175")).currencyCode("AED")
            .status(IntercompanyRecharge.RechargeStatus.PENDING).build();
        r.setId(UUID.randomUUID());
        when(recharges.findPendingRecharges()).thenReturn(List.of(r));

        assertThat(pobo.getPendingRecharges(helios).getBody()).isEmpty();
        assertThat(pobo.getPendingRecharges(mnc).getBody()).hasSize(1);
    }

    @Test
    void noCorporateIsRefusedNotAnsweredForAllCorporates() {
        assertThatThrownBy(() -> controller.getEntitiesWithPositions(null, null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.getEntityPairs(null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.getIntercompanyPositions(null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.getPositionSummary(null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.getStats(null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.getTransactions(null, null, null, null, 0, 50)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> pobo.getPendingRecharges(null)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(icRows, recharges, ledger);
    }

    @Test
    void positionsNameWhoOwesWhomFromTheMirrors() {
        VirtualAccount ihb = va("IHB-MNC-UK-AED-0002", "1100.01", uk);
        ihb.setIhbParticipant(true);
        VirtualAccount icp = va("ICP-MNC-TREASURY-MNC-UK-AED", "1100.01", treasury);
        icp.setMirrorAccountType(MirrorAccountType.IC_PAYABLE);
        icp.setMirrorsVaId(ihb.getId());
        VirtualAccount icr = va("ICR-MNC-TREASURY-MNC-UK-AED", "0", treasury);
        icr.setMirrorAccountType(MirrorAccountType.IC_RECEIVABLE);
        icr.setMirrorsVaId(ihb.getId());
        when(vas.findByCorporateId(mnc)).thenReturn(List.of(ihb, icp, icr));

        var p = controller.getIntercompanyPositions(mnc).getBody().getData();

        assertThat(p).hasSize(1);
        assertThat(p.get(0).getSubsidiaryEntityCode()).isEqualTo("MNC-UK");
        assertThat(p.get(0).getTreasuryEntityCode()).isEqualTo("MNC-TREASURY");
        assertThat(p.get(0).getPayableBalance()).isEqualByComparingTo("1100.01"); // treasury owes MNC-UK
        assertThat(p.get(0).getNetPosition()).isEqualByComparingTo("-1100.01");
        assertThat(controller.getIntercompanyPositions(helios).getBody().getData()).isEmpty();
    }

    @Test
    void poboAndCoboVolumeComeFromTheLedgerPerCurrencyWithoutFees() {
        VirtualAccount dubai = va("IHB-DUBAI-AED", "14.48", uk);
        dubai.setIhbParticipant(true);
        VirtualAccount ukUsd = va("IHB-UK-USD", "0", uk);
        ukUsd.setCurrencyCode("USD");
        ukUsd.setIhbParticipant(true);
        VirtualAccount settlement = va("TC-SETTLE-AED", "0", treasury); // treasury's payout leg, not a POBO count
        when(vas.findByCorporateId(mnc)).thenReturn(List.of(dubai, ukUsd, settlement));
        when(ledger.findByVaId(dubai.getId())).thenReturn(List.of(
            move(MovementType.POBO_DEBIT, "IHB_POBO", "150", "25", "AED"),
            move(MovementType.POBO_DEBIT, "POBO", "356", "25", "AED"),
            move(MovementType.FEE, "FEE", "25", null, "AED"),
            move(MovementType.TRANSFER_IN, "IHB_COBO", "1000", null, "AED"),
            move(MovementType.TRANSFER_IN, "IHB_COBO", "995", null, "AED"),
            move(MovementType.TRANSFER_OUT, "IHB_COBO", "995", null, "AED"))); // a returned collection
        when(ledger.findByVaId(ukUsd.getId())).thenReturn(List.of(move(MovementType.POBO_DEBIT, "IHB_POBO", "40", "0", "USD")));
        when(ledger.findByVaId(settlement.getId())).thenReturn(List.of(move(MovementType.POBO_DEBIT, "IHB_POBO", "150", null, "AED")));

        var stats = controller.getStats(mnc).getBody().getData();

        assertThat(stats.getTotalPoboTransactions()).isEqualTo(3);
        assertThat(stats.getTotalCoboTransactions()).isEqualTo(1);
        assertThat(stats.getVolumes()).hasSize(2);
        var aed = stats.getVolumes().get(0);
        assertThat(aed.getCurrency()).isEqualTo("AED");
        assertThat(aed.getPoboCount()).isEqualTo(2);
        assertThat(aed.getPoboVolume()).isEqualByComparingTo("506");
        assertThat(aed.getPoboFees()).isEqualByComparingTo("50");
        assertThat(aed.getCoboCount()).isEqualTo(1);
        assertThat(aed.getCoboVolume()).isEqualByComparingTo("1000");
        assertThat(stats.getVolumes().get(1).getPoboVolume()).isEqualByComparingTo("40");
    }

    private LegalEntity entity(String code, UUID corporate, boolean lends) {
        LegalEntity e = new LegalEntity();
        e.setId(UUID.randomUUID());
        e.setEntityCode(code);
        e.setEntityName(code + " Ltd");
        e.setCorporateId(corporate);
        e.setIhbEnabled(true);
        e.setCanLend(lends);
        e.setIhbCurrency("AED");
        return e;
    }

    private VirtualAccount va(String number, String balance, LegalEntity owner) {
        VirtualAccount va = VirtualAccount.builder().vaNumber(number).currencyCode("AED").corporateId(mnc)
            .owningEntityId(owner.getId()).currentBalance(new BigDecimal(balance)).build();
        va.setId(UUID.randomUUID());
        when(vas.findById(va.getId())).thenReturn(Optional.of(va));
        return va;
    }

    private static Transaction move(MovementType type, String channel, String amount, String fee, String ccy) {
        return Transaction.builder().movementType(type).channel(channel).amount(new BigDecimal(amount))
            .feeAmount(fee != null ? new BigDecimal(fee) : null).currencyCode(ccy)
            .status(Transaction.TransactionStatus.COMPLETED).build();
    }
}
