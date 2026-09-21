package com.bank.vam.service;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.VirtualAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * A payment settles through a bank account of the paying account's own program, in its
 * currency. It used to fall back to any shadow of the corporate, then to any currency, and so
 * moved real cash through a bank account another program owns.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShadowRoutingTest {

    @Mock(answer = org.mockito.Answers.RETURNS_DEFAULTS) VirtualAccountRepository vas;
    @InjectMocks TransactionService service;

    private final UUID programA = UUID.randomUUID();
    private final UUID programB = UUID.randomUUID();

    private VirtualAccount va(UUID program, AccountCategory cat, String currency, String number) {
        VirtualAccount v = VirtualAccount.builder().programId(program).accountCategory(cat).currencyCode(currency)
            .vaNumber(number).status(VirtualAccount.VaStatus.ACTIVE).build();
        v.setId(UUID.randomUUID());
        return v;
    }

    private VirtualAccount shadow(UUID program, String currency, String number) {
        VirtualAccount s = va(program, AccountCategory.PHYSICAL_MIRROR, currency, number);
        s.setLinkedPhysicalAccountId(UUID.randomUUID());
        return s;
    }

    private VirtualAccount resolve(VirtualAccount source) {
        return ReflectionTestUtils.invokeMethod(service, "resolveShadowVa", source);
    }

    @Test
    void neverSettlesThroughAnotherProgramsBankAccount() {
        VirtualAccount payer = va(programA, AccountCategory.COLLECTION, "AED", "A-1");
        when(vas.findByProgramIdAndAccountCategory(programA, AccountCategory.PHYSICAL_MIRROR)).thenReturn(List.of());
        when(vas.findByProgramIdAndAccountCategory(programA, AccountCategory.ROOT)).thenReturn(List.of());
        // program B's AED shadow exists for the same corporate; it must not be used
        Mockito.lenient().when(vas.findShadowAccountsByCurrency(Mockito.any(), Mockito.eq("AED")))
            .thenReturn(List.of(shadow(programB, "AED", "SHADOW-B")));

        assertThatThrownBy(() -> resolve(payer))
            .isInstanceOf(BusinessException.class).hasMessageContaining("no AED bank account in its program");
    }

    @Test
    void neverSettlesInAnotherCurrency() {
        VirtualAccount payer = va(programA, AccountCategory.COLLECTION, "AED", "A-1");
        when(vas.findByProgramIdAndAccountCategory(programA, AccountCategory.PHYSICAL_MIRROR))
            .thenReturn(List.of(shadow(programA, "GBP", "SHADOW-A-GBP")));
        when(vas.findByProgramIdAndAccountCategory(programA, AccountCategory.ROOT)).thenReturn(List.of());

        assertThatThrownBy(() -> resolve(payer)).isInstanceOf(BusinessException.class);
    }

    @Test
    void prefersTheProgramsMainBackingAccount() {
        VirtualAccount payer = va(programA, AccountCategory.COLLECTION, "AED", "A-1");
        VirtualAccount other = shadow(programA, "AED", "SHADOW-A-1");
        VirtualAccount main = shadow(programA, "AED", "SHADOW-A-2");
        VirtualAccount root = va(programA, AccountCategory.ROOT, "AED", "ROOT-A");
        root.setPhysicalAccountId(main.getLinkedPhysicalAccountId());
        when(vas.findByProgramIdAndAccountCategory(programA, AccountCategory.PHYSICAL_MIRROR)).thenReturn(List.of(other, main));
        when(vas.findByProgramIdAndAccountCategory(programA, AccountCategory.ROOT)).thenReturn(List.of(root));

        assertThat(resolve(payer)).isSameAs(main);
    }

    @Test
    void bookedBankAccountWinsWhenItIsTheProgramsOwn() {
        VirtualAccount booked = shadow(programA, "AED", "SHADOW-A-9");
        VirtualAccount payer = va(programA, AccountCategory.COLLECTION, "AED", "A-1");
        payer.setPhysicalAccountId(booked.getLinkedPhysicalAccountId());
        when(vas.findByLinkedPhysicalAccountIdAndAccountCategory(booked.getLinkedPhysicalAccountId(), AccountCategory.PHYSICAL_MIRROR))
            .thenReturn(Optional.of(booked));

        assertThat(resolve(payer)).isSameAs(booked);
    }
}
