package com.bank.vam.service.treasury;

import com.bank.vam.dto.treasury.BalanceStructureDto.HierarchyNode;
import com.bank.vam.entity.Corporate;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.entity.VirtualAccount.MirrorAccountType;
import com.bank.vam.repository.CorporateRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
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
 * Treasury's IC receivable/payable mirror a subsidiary's own IHB position: the same money from the
 * other side. They are reported as intercompany figures but must not add to (or subtract from) the
 * consolidated total -- while they did, a POBO payment left the total unchanged although the cash
 * had left the bank, and a COBO collection likewise.
 */
class IntercompanyMirrorsNotMoneyTest {

    private final Map<Class<?>, Object> deps = new HashMap<>();
    private final UUID corporateId = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private <T> T dep(Class<T> type) {
        return (T) deps.get(type);
    }

    private BalanceStructureService serviceWithMocks() throws Exception {
        Constructor<?> constructor = BalanceStructureService.class.getDeclaredConstructors()[0];
        Object[] args = new Object[constructor.getParameterCount()];
        Class<?>[] types = constructor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            args[i] = mock(types[i]);
            deps.put(types[i], args[i]);
        }
        constructor.setAccessible(true);
        return (BalanceStructureService) constructor.newInstance(args);
    }

    private VirtualAccount account(String number, AccountCategory category, MirrorAccountType mirror,
                                   String balance, UUID parentId) {
        VirtualAccount va = VirtualAccount.builder()
            .vaNumber(number).vaName(number)
            .accountCategory(category)
            .mirrorAccountType(mirror)
            .parentAccountId(parentId)
            .corporateId(corporateId)
            .currencyCode("AED")
            .status(VirtualAccount.VaStatus.ACTIVE)
            .currentBalance(new BigDecimal(balance))
            .build();
        va.setId(UUID.randomUUID());
        return va;
    }

    @Test
    void icMirrorsAreReportedButLeftOutOfTheTotal() throws Exception {
        BalanceStructureService service = serviceWithMocks();

        VirtualAccount root = account("ROOT", AccountCategory.ROOT, null, "0", null);
        VirtualAccount ops = account("OPS", AccountCategory.TRANSACTION, null, "1000", root.getId());
        VirtualAccount icReceivable = account("IC-REC", AccountCategory.TRANSACTION, MirrorAccountType.IC_RECEIVABLE, "200", root.getId());
        VirtualAccount icPayable = account("IC-PAY", AccountCategory.TRANSACTION, MirrorAccountType.IC_PAYABLE, "50", root.getId());

        Corporate corporate = new Corporate();
        corporate.setId(corporateId);
        corporate.setLegalName("Acme Group");
        when(dep(CorporateRepository.class).findById(corporateId)).thenReturn(Optional.of(corporate));
        when(dep(VirtualAccountRepository.class).findByCorporateId(corporateId))
            .thenReturn(List.of(root, ops, icReceivable, icPayable));
        when(dep(FxRateService.class).hasRate(any(), any())).thenReturn(true);

        HierarchyNode tree = service.getHierarchy(corporateId, null, "AED");

        assertThat(tree.getConsolidatedBalance()).as("only the operating account is money").isEqualByComparingTo("1000");
        assertThat(tree.getIntercompanyReceivable()).as("still reported").isEqualByComparingTo("200");
        assertThat(tree.getIntercompanyPayable()).as("still reported").isEqualByComparingTo("50");
    }
}
