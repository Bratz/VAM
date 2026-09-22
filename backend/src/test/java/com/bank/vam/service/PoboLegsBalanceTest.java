package com.bank.vam.service;

import com.bank.vam.dto.TransactionDto;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.tax.ChargeConfigurationRepository;
import com.bank.vam.service.treasury.FundsAvailabilityService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A POBO payment moves real money out of the group: the bank account (its shadow) goes down by the
 * amount, and nothing else should be left over. The intercompany pair (the subsidiary's IHB current
 * account and Treasury's IC receivable) mirrors one position and nets to zero; the settlement
 * accounts are only stops on the way, so they must end where they started.
 *
 * Today they don't: Treasury Settlement is credited (leg 4) and never debited, so it keeps the
 * amount and every converted total that excludes shadows grows by a payment that actually left.
 */
class PoboLegsBalanceTest {

    private final Map<Class<?>, Object> deps = new HashMap<>();
    private final UUID programId = UUID.randomUUID();
    private final UUID corporateId = UUID.randomUUID();

    private VirtualAccount account(String number, AccountCategory category, String balance) {
        VirtualAccount va = VirtualAccount.builder()
            .vaNumber(number)
            .accountCategory(category)
            .programId(programId)
            .corporateId(corporateId)
            .currencyCode("EUR")
            .status(VirtualAccount.VaStatus.ACTIVE)
            .currentBalance(new BigDecimal(balance))
            .availableBalance(new BigDecimal(balance))
            .build();
        va.setId(UUID.randomUUID());
        return va;
    }

    @SuppressWarnings("unchecked")
    private <T> T dep(Class<T> type) {
        return (T) deps.get(type);
    }

    private TransactionService serviceWithMocks() throws Exception {
        Constructor<?> constructor = TransactionService.class.getDeclaredConstructors()[0];
        Object[] args = new Object[constructor.getParameterCount()];
        Class<?>[] types = constructor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            args[i] = mock(types[i]);
            deps.put(types[i], args[i]);
        }
        constructor.setAccessible(true);
        return (TransactionService) constructor.newInstance(args);
    }

    @Test
    void theSixLegsOfAPoboPaymentNetToTheMoneyThatLeftTheBank() throws Exception {
        TransactionService service = serviceWithMocks();
        VirtualAccountRepository vas = dep(VirtualAccountRepository.class);
        TransactionRepository txns = dep(TransactionRepository.class);

        VirtualAccount ihbCurrent = account("IHB-UK-EUR", AccountCategory.TRANSACTION, "0");
        VirtualAccount ihbSettlement = account("IHBS-UK-EUR", AccountCategory.TRANSACTION, "0");
        VirtualAccount treasurySettlement = account("TSETT-EUR", AccountCategory.SETTLEMENT, "0");
        VirtualAccount icReceivable = account("IC-REC-UK-EUR", AccountCategory.TRANSACTION, "0");
        VirtualAccount shadow = account("SHADOW-EUR", AccountCategory.PHYSICAL_MIRROR, "1000000");
        shadow.setLinkedPhysicalAccountId(UUID.randomUUID());
        shadow.setBankBalance(new BigDecimal("1000000"));

        ihbCurrent.setIhbSettlementVaId(ihbSettlement.getId());
        ihbCurrent.setTreasuryPoolVaId(treasurySettlement.getId());
        ihbCurrent.setIcReceivableVaId(icReceivable.getId());

        List<VirtualAccount> all = List.of(ihbCurrent, ihbSettlement, treasurySettlement, icReceivable, shadow);
        for (VirtualAccount va : all) {
            when(vas.findById(va.getId())).thenReturn(Optional.of(va));
        }
        when(vas.findByProgramIdAndAccountCategory(programId, AccountCategory.ROOT)).thenReturn(List.of());
        when(vas.findByProgramIdAndAccountCategory(programId, AccountCategory.PHYSICAL_MIRROR)).thenReturn(List.of(shadow));
        when(vas.save(any(VirtualAccount.class))).thenAnswer(i -> i.getArgument(0));
        when(txns.save(any(Transaction.class))).thenAnswer(i -> {
            Transaction t = i.getArgument(0);
            if (t.getId() == null) t.setId(UUID.randomUUID());
            return t;
        });
        when(dep(LegalEntityRepository.class).findById(any())).thenReturn(Optional.empty());
        when(dep(ChargeConfigurationRepository.class).findActiveByCode(any())).thenReturn(Optional.empty()); // no fee
        FundsAvailabilityService.FundsCheckResult approved = new FundsAvailabilityService.FundsCheckResult();
        approved.setApproved(true);
        when(dep(FundsAvailabilityService.class).checkFundsAvailability(any(), any(), any())).thenReturn(approved);

        BigDecimal amount = new BigDecimal("20000");
        Map<VirtualAccount, BigDecimal> before = new HashMap<>();
        all.forEach(va -> before.put(va, va.getCurrentBalance()));

        TransactionDto.PoboPaymentRequest request = new TransactionDto.PoboPaymentRequest();
        request.setOwnerVaId(ihbCurrent.getId());
        request.setPayerVaId(treasurySettlement.getId());
        request.setAmount(amount);
        request.setBeneficiaryName("Supplier Ltd");

        service.makeIhb6LegPoboPayment(request);

        List<String> moved = new ArrayList<>();
        BigDecimal everything = BigDecimal.ZERO;
        BigDecimal exceptShadow = BigDecimal.ZERO;
        for (VirtualAccount va : all) {
            BigDecimal delta = va.getCurrentBalance().subtract(before.get(va));
            everything = everything.add(delta);
            if (va.getAccountCategory() != AccountCategory.PHYSICAL_MIRROR) {
                exceptShadow = exceptShadow.add(delta);
            }
            if (delta.signum() != 0) moved.add(va.getVaNumber() + " " + delta.toPlainString());
        }

        // The shadow carries the real outflow; everything together must equal it.
        assertThat(everything).as("all accounts, moved: %s", moved).isEqualByComparingTo(amount.negate());
        // The virtual side is a redistribution among the group's own accounts: it nets to zero.
        assertThat(exceptShadow).as("virtual accounts only, moved: %s", moved).isEqualByComparingTo(BigDecimal.ZERO);
        // The intercompany pair mirrors one position.
        assertThat(ihbCurrent.getCurrentBalance().add(icReceivable.getCurrentBalance()))
            .as("IHB current + IC receivable").isEqualByComparingTo(BigDecimal.ZERO);
        // Settlement accounts are stops on the way, not destinations.
        assertThat(ihbSettlement.getCurrentBalance()).as("IHB settlement VA").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(treasurySettlement.getCurrentBalance()).as("Treasury settlement VA").isEqualByComparingTo(BigDecimal.ZERO);
    }
}
