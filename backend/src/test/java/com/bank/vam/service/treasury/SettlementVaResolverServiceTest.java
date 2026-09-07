package com.bank.vam.service.treasury;

import com.bank.vam.entity.Program;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.treasury.ExceptionTransaction;
import com.bank.vam.repository.ProgramRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.treasury.ExceptionTransactionRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression guard for the missing-Settlement-VA exception-parking crash: when no
 * Settlement VA is configured for a program/currency, {@code resolveSettlementVaWithResult}
 * must park the operation in an Exception VA rather than throw. Previously it wrote the
 * caller-supplied {@code transactionId} — which is never a persisted {@code va_movements}
 * row at this call site (every caller evaluates this as a pre-check, before any debit/credit
 * Transaction exists) — into {@code ExceptionTransaction.originalTransactionId}, an FK
 * column to {@code va_movements}, aborting the whole surrounding transaction the first time
 * that id happened to look like a real UUID.
 */
class SettlementVaResolverServiceTest {

    @Test
    void missingSettlementVa_parksInExceptionVa_withoutWritingUnverifiedTransactionId() {
        VirtualAccountRepository vaRepository = mock(VirtualAccountRepository.class);
        ProgramRepository programRepository = mock(ProgramRepository.class);
        ExceptionTransactionRepository exceptionRepository = mock(ExceptionTransactionRepository.class);

        // Mockito's smart defaults already return Optional.empty()/List.of() for every
        // unstubbed sibling/hierarchy/program/corporate Settlement-VA lookup below — only
        // the two save() calls need stubbing, since their default (null) would NPE on the
        // subsequent .getId() calls.
        when(vaRepository.save(any(VirtualAccount.class))).thenAnswer(inv -> {
            VirtualAccount va = inv.getArgument(0);
            va.setId(UUID.randomUUID());
            return va;
        });
        when(exceptionRepository.save(any(ExceptionTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        SettlementVaResolverService resolver =
                new SettlementVaResolverService(vaRepository, programRepository, exceptionRepository);

        VirtualAccount sourceVa = VirtualAccount.builder()
                .vaNumber("ALBN0000003")
                .programId(UUID.randomUUID())
                .corporateId(null) // skip the corporate-level fallback branch
                .parentAccountId(null) // skip the sibling/hierarchy-traversal branches
                .currencyCode("GBP")
                .build();

        // A caller-supplied reference id that looks exactly like a real UUID but was never
        // inserted into va_movements — reproduces the exact failure mode found live.
        UUID unpersistedReferenceId = UUID.randomUUID();

        SettlementVaResolverService.SettlementVaResolutionResult result =
                resolver.resolveSettlementVaWithResult(sourceVa, new BigDecimal("25.00"), unpersistedReferenceId);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getExceptionRaised()).isNotNull();
        assertThat(result.getExceptionRaised().getExceptionType())
                .isEqualTo(ExceptionTransaction.ExceptionType.MISSING_SETTLEMENT_VA);
        // The load-bearing assertion: no unverified id in the FK-to-va_movements column.
        assertThat(result.getExceptionRaised().getOriginalTransactionId()).isNull();
        // The reference is preserved for triage, just not as an FK.
        assertThat(result.getExceptionRaised().getRemitterInfo()).contains(unpersistedReferenceId.toString());
    }

    /**
     * Regression guard for provisioning a Settlement VA on a "flat" program — one with no
     * hierarchy_nodes ROOT and no AccountCategory.ROOT VA (several flagship demo corporates
     * are exactly this shape: standalone PHYSICAL_MIRROR shadows, nothing else). The
     * standalone provisioning path must not require a parent to exist.
     */
    @Test
    void provisionSettlementVa_byProgramAndCurrency_createsStandaloneVaWithNoParent() {
        VirtualAccountRepository vaRepository = mock(VirtualAccountRepository.class);
        ProgramRepository programRepository = mock(ProgramRepository.class);
        ExceptionTransactionRepository exceptionRepository = mock(ExceptionTransactionRepository.class);

        when(vaRepository.save(any(VirtualAccount.class))).thenAnswer(inv -> {
            VirtualAccount va = inv.getArgument(0);
            va.setId(UUID.randomUUID());
            return va;
        });

        UUID programId = UUID.randomUUID();
        UUID corporateId = UUID.randomUUID();
        Program program = Program.builder().corporateId(corporateId).programCode("TRES-GBP-ALBN").build();
        when(programRepository.findById(programId)).thenReturn(Optional.of(program));

        SettlementVaResolverService resolver =
                new SettlementVaResolverService(vaRepository, programRepository, exceptionRepository);

        VirtualAccount settlementVa = resolver.provisionSettlementVa(programId, "GBP");

        assertThat(settlementVa.getAccountCategory()).isEqualTo(VirtualAccount.AccountCategory.SETTLEMENT);
        assertThat(settlementVa.getParentAccountId()).isNull();
        assertThat(settlementVa.getProgramId()).isEqualTo(programId);
        assertThat(settlementVa.getCorporateId()).isEqualTo(corporateId);
        assertThat(settlementVa.getCurrencyCode()).isEqualTo("GBP");
    }
}
