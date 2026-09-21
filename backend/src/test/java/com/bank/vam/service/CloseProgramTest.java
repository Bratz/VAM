package com.bank.vam.service;

import com.bank.vam.entity.Program;
import com.bank.vam.entity.Program.ProgramStatus;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.ProgramRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.service.treasury.ShadowAccountService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Every program has active scaffolding (root, mirrors, exception, settlement) from its setup, so
 * only customer accounts may block closing it; otherwise no program could ever be closed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CloseProgramTest {

    @Mock ProgramRepository programs;
    @Mock VirtualAccountRepository vas;
    @Mock ShadowAccountService shadows;
    @Mock com.bank.vam.service.audit.AuditLogService audit;
    @Mock com.bank.vam.repository.audit.AuditLogRepository auditLog;
    @Mock com.bank.vam.repository.CorporateRepository corporates;
    @Mock com.bank.vam.repository.PhysicalAccountRepository physicalAccounts;
    @Mock com.bank.vam.repository.hierarchy.HierarchyNodeRepository nodes;
    @Mock com.bank.vam.repository.viban.VibanPoolRepository pools;
    @Mock com.bank.vam.service.hierarchy.HierarchyService hierarchy;
    @Mock com.bank.vam.service.treasury.FxRateService fx;
    @Mock com.bank.vam.config.MarketProfileProperties market;
    @InjectMocks ProgramService service;

    private final UUID id = UUID.randomUUID();

    private Program program() {
        Program p = Program.builder().programCode("P1").programName("P").currencyCode("AED")
            .corporateId(UUID.randomUUID()).status(ProgramStatus.ACTIVE).build();
        p.setId(id);
        when(programs.findById(id)).thenReturn(Optional.of(p));
        when(programs.save(any())).thenAnswer(i -> i.getArgument(0));
        return p;
    }

    private VirtualAccount active(AccountCategory c) {
        return VirtualAccount.builder().programId(id).accountCategory(c).status(VirtualAccount.VaStatus.ACTIVE).build();
    }

    @Test
    void scaffoldingAloneDoesNotBlockClosing() {
        Program p = program();
        when(vas.findByProgramId(id)).thenReturn(List.of(active(AccountCategory.ROOT), active(AccountCategory.EXCEPTION),
            active(AccountCategory.SETTLEMENT), active(AccountCategory.CURRENCY_MIRROR), active(AccountCategory.PHYSICAL_MIRROR)));

        service.closeProgram(id);

        assertThat(p.getStatus()).isEqualTo(ProgramStatus.CLOSED);
        verify(shadows).releaseProgramShadows(p);
    }

    @Test
    void activeCustomerAccountsBlockClosing() {
        Program p = program();
        when(vas.findByProgramId(id)).thenReturn(List.of(active(AccountCategory.ROOT), active(AccountCategory.COLLECTION)));

        assertThatThrownBy(() -> service.closeProgram(id))
            .isInstanceOf(BusinessException.class).hasMessageContaining("1 active customer accounts");
        assertThat(p.getStatus()).isEqualTo(ProgramStatus.ACTIVE);
        verify(shadows, never()).releaseProgramShadows(any());
    }
}
