package com.bank.vam.service.treasury.refresh;

import com.bank.vam.config.MultiBankProperties;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.entity.VirtualAccount.BalanceDataSource;
import com.bank.vam.entity.VirtualAccount.BalanceRefreshStatus;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.treasury.ExceptionTransactionRepository;
import com.bank.vam.repository.treasury.ShadowBalanceSnapshotRepository;
import com.bank.vam.service.treasury.SettlementVaResolverService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** R14 / acceptance 6: statement-fed shadows are not polled, so they are no longer stamped "not connected". */
class StatementFedRefreshSkipTest {

    private final VirtualAccountRepository vas = mock(VirtualAccountRepository.class);
    private final StubAdapter stub = new StubAdapter();
    private final BalanceRefreshService service = new BalanceRefreshService(vas, new MultiBankProperties(), List.of(stub), stub,
            mock(SettlementVaResolverService.class), mock(ExceptionTransactionRepository.class),
            mock(ShadowBalanceSnapshotRepository.class));

    private static VirtualAccount shadow(BalanceDataSource source) {
        VirtualAccount s = VirtualAccount.builder().vaNumber("SH-" + source).accountCategory(AccountCategory.PHYSICAL_MIRROR)
                .balanceDataSource(source).build();
        s.setId(UUID.randomUUID());
        return s;
    }

    @Test
    void scheduledRefreshSkipsStatementFedShadows() {
        VirtualAccount mt940 = shadow(BalanceDataSource.SWIFT_MT940), mt942 = shadow(BalanceDataSource.SWIFT_MT942),
                h2h = shadow(BalanceDataSource.HOST_TO_HOST), openBanking = shadow(BalanceDataSource.OPEN_BANKING);
        when(vas.findByAccountCategory(AccountCategory.PHYSICAL_MIRROR)).thenReturn(List.of(mt940, mt942, h2h, openBanking));

        service.refreshAllStaleShadows();

        for (VirtualAccount s : List.of(mt940, mt942, h2h)) {
            assertNull(s.getLastBalanceRefreshAt(), s.getVaNumber());
            assertNull(s.getLastBalanceRefreshStatus(), s.getVaNumber());
            verify(vas, never()).save(s);
        }
        assertEquals(BalanceRefreshStatus.FAILED, openBanking.getLastBalanceRefreshStatus());   // still no adapter: not connected
    }

    @Test
    void forcedRefreshLeavesTheStatementStampAlone() {
        VirtualAccount mt940 = shadow(BalanceDataSource.SWIFT_MT940);
        mt940.setLastBalanceRefreshStatus(BalanceRefreshStatus.SUCCESS);
        when(vas.findById(mt940.getId())).thenReturn(Optional.of(mt940));

        assertEquals(BalanceRefreshStatus.SUCCESS, service.refresh(mt940.getId()));
        verify(vas, never()).save(mt940);
    }
}
