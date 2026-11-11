package com.bank.vam.controller;

import com.bank.vam.controller.payables.PayablesController;
import com.bank.vam.dto.payables.PayablesDto.CreatePayableRequest;
import com.bank.vam.entity.PhysicalAccount;
import com.bank.vam.entity.PhysicalAccount.SyncStatus;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.CorporateRepository;
import com.bank.vam.repository.PhysicalAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.service.payables.PayablesService;
import com.bank.vam.service.treasury.ShadowAccountService;
import com.bank.vam.service.treasury.refresh.StubAdapter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Endpoints that used to report success while doing nothing now refuse, and no demo corporate stands in. */
class NoFakeSuccessTest {

    @Test
    void physicalAccountSyncRefusesAndLeavesTheAccountUnsynced() {
        PhysicalAccountRepository repo = mock(PhysicalAccountRepository.class);
        PhysicalAccount account = PhysicalAccount.builder()
                .accountNumber("123").bankName("Some Bank").syncStatus(SyncStatus.PENDING).build();
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.of(account));
        PhysicalAccountController controller = new PhysicalAccountController(repo, mock(CorporateRepository.class),
                mock(LegalEntityRepository.class), null, null, mock(ShadowAccountService.class));

        assertThatThrownBy(() -> controller.syncBalance(id))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Not available");
        assertThatThrownBy(() -> controller.syncAllAccounts(null))
                .isInstanceOf(BusinessException.class);
        assertThat(account.getSyncStatus()).isEqualTo(SyncStatus.PENDING);
        assertThat(account.getLastSyncAt()).isNull();
        verify(repo, never()).save(any());
    }

    @Test
    void stubAdapterReportsFailureInsteadOfTheOldBalance() {
        VirtualAccount shadow = new VirtualAccount();
        shadow.setBankBalance(new BigDecimal("100"));
        assertThat(new StubAdapter().fetch(shadow).success()).isFalse();
    }

    @Test
    void payableWithoutCorporateIsRefusedNotFiledUnderADemoCorporate() {
        PayablesService service = mock(PayablesService.class);
        PayablesController controller = new PayablesController(service);

        assertThatThrownBy(() -> controller.createPayable(null, new CreatePayableRequest()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.getStats(null)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(service);

        UUID corp = UUID.randomUUID();
        CreatePayableRequest request = new CreatePayableRequest();
        controller.createPayable(corp, request);
        assertThat(request.getCorporateId()).isEqualTo(corp);
    }
}
