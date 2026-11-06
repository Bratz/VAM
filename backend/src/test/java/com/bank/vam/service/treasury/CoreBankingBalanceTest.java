package com.bank.vam.service.treasury;

import com.bank.vam.entity.PhysicalAccount;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.BalanceDataSource;
import com.bank.vam.repository.PhysicalAccountRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A posting to a core-banking mirror moves the physical account too, so the 15-minute refresh does not undo it. */
class CoreBankingBalanceTest {

    private final PhysicalAccountRepository banks = mock(PhysicalAccountRepository.class);

    private VirtualAccount mirror(BalanceDataSource source, PhysicalAccount pa) {
        VirtualAccount v = new VirtualAccount();
        v.setBalanceDataSource(source);
        v.setLinkedPhysicalAccountId(pa.getId());
        return v;
    }

    private PhysicalAccount account(String balance) {
        PhysicalAccount p = new PhysicalAccount();
        p.setId(UUID.randomUUID());
        p.setCurrentBalance(new BigDecimal(balance));
        p.setAvailableBalance(new BigDecimal(balance));
        p.setLedgerBalance(new BigDecimal(balance));
        when(banks.findById(p.getId())).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    void aCoreBankingMirrorsPhysicalAccountMovesByTheSameAmount() {
        PhysicalAccount p = account("1000.00");

        CoreBankingBalance.follow(banks, mirror(BalanceDataSource.CORE_BANKING, p), new BigDecimal("-250.00"));

        assertThat(p.getCurrentBalance()).isEqualByComparingTo("750.00");
        assertThat(p.getAvailableBalance()).isEqualByComparingTo("750.00");
        assertThat(p.getLedgerBalance()).isEqualByComparingTo("750.00");
        verify(banks).save(p);
    }

    @Test
    void aMirrorFedByStatementsIsLeftToItsBank() {
        PhysicalAccount p = account("1000.00");

        CoreBankingBalance.follow(banks, mirror(BalanceDataSource.SWIFT_MT940, p), new BigDecimal("-250.00"));

        assertThat(p.getCurrentBalance()).isEqualByComparingTo("1000.00");
        verify(banks, never()).save(any());
    }
}
