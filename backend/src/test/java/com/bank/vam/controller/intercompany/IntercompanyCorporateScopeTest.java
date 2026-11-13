package com.bank.vam.controller.intercompany;

import com.bank.vam.controller.pobo.PoboController;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.MirrorAccountType;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.pobo.IntercompanyRecharge;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.pobo.IntercompanyRechargeRepository;
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
 * The Intercompany page showed every corporate TestMNC's entities and entity pairs (functional review C3): the
 * endpoints took a corporateId and ignored it. What is left of the API returns the asked corporate's group only, and
 * refuses a request with no corporate. Positions come from the ledger (C1/C2/C6).
 */
class IntercompanyCorporateScopeTest {

    private final UUID mnc = UUID.randomUUID();
    private final UUID helios = UUID.randomUUID();

    private final LegalEntityRepository entities = mock(LegalEntityRepository.class);
    private final VirtualAccountRepository vas = mock(VirtualAccountRepository.class);
    private final IntercompanyRechargeRepository recharges = mock(IntercompanyRechargeRepository.class);

    private final IntercompanyTransactionService icTransactions = new IntercompanyTransactionService(recharges,
        entities, null, null, null, null, vas, null);
    private final IntercompanyController controller = new IntercompanyController(icTransactions, null);
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
        when(entities.findById(treasury.getId())).thenReturn(Optional.of(treasury));
        when(entities.findById(uk.getId())).thenReturn(Optional.of(uk));
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
        assertThatThrownBy(() -> controller.getIntercompanyPositions(null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.getGroupPosition(null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> pobo.getPendingRecharges(null)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(recharges);
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
}
