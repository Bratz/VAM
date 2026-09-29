package com.bank.vam.service.treasury;

import com.bank.vam.entity.Corporate;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.CorporateRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A divestiture spins an AGGREGATION out into a corporate that does not exist yet, and the UI only
 * knows the name the user typed. The corporate is therefore created inside the same transaction as
 * the divestiture.
 *
 * <p>Two things make this worth a test rather than a comment. virtual_accounts.corporate_id has no
 * foreign key, so stamping an id for a corporate that was never created is accepted silently and
 * leaves the whole subtree pointing at nothing. And corporates have no delete endpoint, so creating
 * one from the browser first would strand an empty corporate on every failed divestiture.
 */
class DivestitureCreatesCorporateTest {

    private final Map<Class<?>, Object> deps = new HashMap<>();
    private final UUID aggregationId = UUID.randomUUID();
    private final UUID sourceCorporateId = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private <T> T dep(Class<T> type) {
        return (T) deps.get(type);
    }

    private HierarchyMergeService serviceWithMocks() throws Exception {
        Constructor<?> constructor = HierarchyMergeService.class.getDeclaredConstructors()[0];
        Object[] args = new Object[constructor.getParameterCount()];
        Class<?>[] types = constructor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            args[i] = mock(types[i]);
            deps.put(types[i], args[i]);
        }
        constructor.setAccessible(true);
        return (HierarchyMergeService) constructor.newInstance(args);
    }

    private VirtualAccount aggregation() {
        VirtualAccount va = VirtualAccount.builder()
            .vaNumber("AGG-1")
            .vaName("Spin-off unit")
            .accountCategory(AccountCategory.AGGREGATION)
            .corporateId(sourceCorporateId)
            .currencyCode("GBP")
            .aggregatedBalance(BigDecimal.ZERO)
            .build();
        va.setId(aggregationId);
        return va;
    }

    private HierarchyMergeService.DivestitureRequest.DivestitureRequestBuilder request() {
        return HierarchyMergeService.DivestitureRequest.builder()
            .sourceCorporateId(sourceCorporateId)
            .aggregationId(aggregationId)
            .approvedBy("tester");
    }

    private void stubRepositories(VirtualAccount aggregation) {
        VirtualAccountRepository vas = dep(VirtualAccountRepository.class);
        when(vas.findById(aggregationId)).thenReturn(Optional.of(aggregation));
        when(vas.findByParentAccountId(any())).thenReturn(List.of());
        when(vas.save(any(VirtualAccount.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void namingTheNewCorporateCreatesItAndStampsTheSubtreeWithItsRealId() throws Exception {
        HierarchyMergeService service = serviceWithMocks();
        VirtualAccount aggregation = aggregation();
        stubRepositories(aggregation);

        UUID createdId = UUID.randomUUID();
        CorporateRepository corporates = dep(CorporateRepository.class);
        when(corporates.findByCorporateId(any())).thenReturn(Optional.empty());
        when(corporates.save(any(Corporate.class))).thenAnswer(i -> {
            Corporate c = i.getArgument(0);
            c.setId(createdId);
            return c;
        });

        service.divestAggregation(request()
            .newCorporateName("Spinco Holdings")
            .newCorporateCode("SPINCO")
            .newBaseCurrency("GBP")
            .build());

        ArgumentCaptor<Corporate> saved = ArgumentCaptor.forClass(Corporate.class);
        verify(corporates).save(saved.capture());
        assertThat(saved.getValue().getLegalName()).isEqualTo("Spinco Holdings");
        assertThat(saved.getValue().getCorporateId()).isEqualTo("SPINCO");

        // The promoted ROOT must carry the id of the corporate that was actually created --
        // corporate_id has no FK, so a fabricated id would be accepted and point at nothing.
        assertThat(aggregation.getCorporateId()).isEqualTo(createdId);
        assertThat(aggregation.getAccountCategory()).isEqualTo(AccountCategory.ROOT);
    }

    @Test
    void supplyingAnExistingCorporateIdCreatesNothing() throws Exception {
        HierarchyMergeService service = serviceWithMocks();
        VirtualAccount aggregation = aggregation();
        stubRepositories(aggregation);

        UUID existing = UUID.randomUUID();
        service.divestAggregation(request().newCorporateId(existing).newBaseCurrency("GBP").build());

        verify(dep(CorporateRepository.class), never()).save(any());
        assertThat(aggregation.getCorporateId()).isEqualTo(existing);
    }

    @Test
    void neitherAnIdNorANameIsRejectedRatherThanStampingNothing() throws Exception {
        HierarchyMergeService service = serviceWithMocks();
        stubRepositories(aggregation());

        assertThatThrownBy(() -> service.divestAggregation(request().newBaseCurrency("GBP").build()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("newCorporateName");

        verify(dep(CorporateRepository.class), never()).save(any());
    }
}
