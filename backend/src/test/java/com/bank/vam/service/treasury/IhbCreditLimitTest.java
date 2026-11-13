package com.bank.vam.service.treasury;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.VirtualAccount.VaStatus;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.VirtualAccountRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** One IHB credit limit: the entity's, which its IHB current accounts carry for the headroom check (no drift). */
class IhbCreditLimitTest {

    private final VirtualAccountRepository accounts = mock(VirtualAccountRepository.class);
    private final LegalEntity entity = new LegalEntity();
    private final VirtualAccount ihb = account(true, "-300000");
    private final VirtualAccount other = account(false, "500");

    IhbCreditLimitTest() {
        entity.setId(UUID.randomUUID());
        entity.setEntityCode("MNC-UK");
        entity.setIhbCurrency("AED");
        entity.setIhbCreditLimit(new BigDecimal("1000000"));
        ihb.setOwningEntityId(entity.getId());
        other.setOwningEntityId(entity.getId());
        when(accounts.findByOwningEntityId(entity.getId())).thenReturn(List.of(ihb, other));
    }

    @Test
    void aNewLimitReachesTheAccountsTheHeadroomCheckReads() {
        IhbUnifiedService.applyCreditLimit(accounts, entity, new BigDecimal("400000"));

        assertThat(entity.getIhbCreditLimit()).isEqualByComparingTo("400000");
        assertThat(ihb.getEffectiveCreditLimit()).isEqualByComparingTo("400000");
        assertThat(ihb.getCreditLimitUtilized()).isEqualByComparingTo("300000");
        assertThat(ihb.getCreditLimitAvailable()).isEqualByComparingTo("100000");
        assertThat(com.bank.vam.service.pobo.PoboEnrolmentService.headroom(ihb)).isEqualByComparingTo("100000");
        assertThat(other.getEffectiveCreditLimit()).isEqualByComparingTo("1000000");
        verify(accounts).save(ihb);
        verify(accounts, never()).save(other);
    }

    @Test
    void notBelowWhatWasDrawnAndNothingChanges() {
        assertThatThrownBy(() -> IhbUnifiedService.applyCreditLimit(accounts, entity, new BigDecimal("200000")))
            .isInstanceOf(BusinessException.class).hasMessageContaining("300000");
        assertThat(entity.getIhbCreditLimit()).isEqualByComparingTo("1000000");
        assertThat(ihb.getEffectiveCreditLimit()).isEqualByComparingTo("1000000");
        verify(accounts, never()).save(any());
    }

    private static VirtualAccount account(boolean ihbParticipant, String balance) {
        VirtualAccount va = VirtualAccount.builder().vaNumber(ihbParticipant ? "IHB-MNC-UK-AED-0001" : "OPS-AED")
            .currencyCode("AED").status(VaStatus.ACTIVE).currentBalance(new BigDecimal(balance))
            .effectiveCreditLimit(new BigDecimal("1000000")).build();
        va.setId(UUID.randomUUID());
        va.setIhbParticipant(ihbParticipant);
        return va;
    }
}
