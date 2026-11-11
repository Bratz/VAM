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
    private final NettingService service = new NettingService(null, null, null, null, recharges, null, null, null,
        null, null, mock(FxRateService.class), null);

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

        assertThat(service.addPoboRecharges(cycle, UUID.randomUUID(), false)).isEqualTo(1);
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
}
