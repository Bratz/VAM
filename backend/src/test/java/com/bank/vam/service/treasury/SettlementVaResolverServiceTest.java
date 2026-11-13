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

    /** A program-less, parentless account (a home-bank shadow) settles on the corporate's only top-level settlement VA. */
    @Test
    void anAccountOutsideEveryProgramUsesTheCorporatesOnlySettlementVaInItsCurrency() {
        VirtualAccountRepository vaRepository = mock(VirtualAccountRepository.class);
        SettlementVaResolverService resolver = new SettlementVaResolverService(vaRepository,
                mock(ProgramRepository.class), mock(ExceptionTransactionRepository.class));
        UUID corporate = UUID.randomUUID();
        VirtualAccount settlement = VirtualAccount.builder().vaNumber("SETTLEMENT-TRESUSDHEL-USD")
                .programId(UUID.randomUUID()).corporateId(corporate).currencyCode("USD").build();
        VirtualAccount otherCcy = VirtualAccount.builder().vaNumber("SETTLEMENT-EUR")
                .programId(UUID.randomUUID()).corporateId(corporate).currencyCode("EUR").build();
        when(vaRepository.findByCorporateIdAndSettlementMarkTrue(corporate)).thenReturn(java.util.List.of(settlement, otherCcy));
        VirtualAccount shadow = VirtualAccount.builder().vaNumber("SHADOW-USD-54361989")
                .corporateId(corporate).currencyCode("USD").build();

        SettlementVaResolverService.SettlementVaResolutionResult result =
                resolver.resolveSettlementVaWithResult(shadow, new BigDecimal("25.00"), UUID.randomUUID());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResolvedVa().getVaNumber()).isEqualTo("SETTLEMENT-TRESUSDHEL-USD");
    }

    /**
     * The payment that is refused for a missing Settlement VA rolls back; the parking record its error cites must not
     * go with it. So the record is written in a transaction of its own (REQUIRES_NEW), committed before the caller
     * throws.
     */
    @Test
    void theParkingRecordIsWrittenAndCommittedInItsOwnTransaction() {
        VirtualAccountRepository vaRepository = mock(VirtualAccountRepository.class);
        ExceptionTransactionRepository exceptionRepository = mock(ExceptionTransactionRepository.class);
        when(vaRepository.save(any(VirtualAccount.class))).thenAnswer(inv -> {
            VirtualAccount va = inv.getArgument(0);
            va.setId(UUID.randomUUID());
            return va;
        });
        when(exceptionRepository.save(any(ExceptionTransaction.class))).thenAnswer(inv -> inv.getArgument(0));
        org.springframework.transaction.PlatformTransactionManager tm = mock(org.springframework.transaction.PlatformTransactionManager.class);
        org.springframework.transaction.TransactionStatus own = new org.springframework.transaction.support.SimpleTransactionStatus();
        when(tm.getTransaction(any())).thenReturn(own);

        SettlementVaResolverService resolver =
                new SettlementVaResolverService(vaRepository, mock(ProgramRepository.class), exceptionRepository);
        org.springframework.test.util.ReflectionTestUtils.setField(resolver, "transactionManager", tm);

        VirtualAccount sourceVa = VirtualAccount.builder().vaNumber("VA-AED-1").programId(UUID.randomUUID())
                .currencyCode("AED").build();
        SettlementVaResolverService.SettlementVaResolutionResult result =
                resolver.resolveSettlementVaWithResult(sourceVa, new BigDecimal("10.00"), null);

        assertThat(result.getExceptionRaised()).isNotNull();
        org.mockito.Mockito.verify(tm).getTransaction(org.mockito.ArgumentMatchers.argThat(d ->
                d.getPropagationBehavior() == org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        org.mockito.Mockito.verify(tm).commit(own);
    }

    /**
     * The Exception VA read in that own transaction is stale for the caller: postFee credits it and saves (merges), so two
     * fees in one transaction (an external sweep's standard + instruction fee) kept only the second credit. The caller
     * gets its own transaction's copy instead.
     */
    @Test
    void theParkedExceptionVaIsTheCallersOwnCopy() {
        VirtualAccountRepository vaRepository = mock(VirtualAccountRepository.class);
        ExceptionTransactionRepository exceptionRepository = mock(ExceptionTransactionRepository.class);
        when(vaRepository.save(any(VirtualAccount.class))).thenAnswer(inv -> {
            VirtualAccount va = inv.getArgument(0);
            va.setId(UUID.randomUUID());
            return va;
        });
        when(exceptionRepository.save(any(ExceptionTransaction.class))).thenAnswer(inv -> inv.getArgument(0));
        VirtualAccount callersCopy = VirtualAccount.builder().vaNumber("EXCEPTION-USD").currentBalance(new BigDecimal("500")).build();
        when(vaRepository.findById(any())).thenReturn(Optional.of(callersCopy));
        org.springframework.transaction.PlatformTransactionManager tm = mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        SettlementVaResolverService resolver =
                new SettlementVaResolverService(vaRepository, mock(ProgramRepository.class), exceptionRepository);
        org.springframework.test.util.ReflectionTestUtils.setField(resolver, "transactionManager", tm);

        var result = resolver.resolveSettlementVaWithResult(VirtualAccount.builder().vaNumber("VA-USD-1")
                .programId(UUID.randomUUID()).currencyCode("USD").build(), new BigDecimal("25.00"), null);

        assertThat(result.getResolvedVa()).isSameAs(callersCopy);
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

        assertThat(settlementVa.getAccountCategory()).isEqualTo(VirtualAccount.AccountCategory.TRANSACTION);
        assertThat(settlementVa.isSettlementVa()).isTrue();
        assertThat(settlementVa.getParentAccountId()).isNull();
        assertThat(settlementVa.getProgramId()).isEqualTo(programId);
        assertThat(settlementVa.getCorporateId()).isEqualTo(corporateId);
        assertThat(settlementVa.getCurrencyCode()).isEqualTo("GBP");
    }

    /**
     * A program with currency mirrors used to get its Settlement VA inside one of them, where the resolver (beside the
     * account, beside its ancestors, the top level) never looked: every payment was then parked as "Settlement VA not
     * configured". It goes to the top level, or beside the mirror a source account sits in.
     */
    @Test
    void aSettlementVaIsNeverPlacedInsideACurrencyMirror() {
        VirtualAccountRepository vaRepository = mock(VirtualAccountRepository.class);
        ProgramRepository programRepository = mock(ProgramRepository.class);
        when(vaRepository.save(any(VirtualAccount.class))).thenAnswer(inv -> {
            VirtualAccount va = inv.getArgument(0);
            va.setId(UUID.randomUUID());
            return va;
        });
        UUID programId = UUID.randomUUID();
        UUID rootId = UUID.randomUUID();
        VirtualAccount mirror = VirtualAccount.builder().programId(programId).currencyCode("AED")
                .accountCategory(VirtualAccount.AccountCategory.CURRENCY_MIRROR).parentAccountId(rootId).build();
        mirror.setId(UUID.randomUUID());
        when(vaRepository.findCurrencyMirrorsByProgramAndCurrency(programId, "AED")).thenReturn(java.util.List.of(mirror));
        when(vaRepository.findById(mirror.getId())).thenReturn(Optional.of(mirror));
        when(programRepository.findById(programId)).thenReturn(Optional.of(
                Program.builder().corporateId(UUID.randomUUID()).programCode("COLL5").build()));
        SettlementVaResolverService resolver =
                new SettlementVaResolverService(vaRepository, programRepository, mock(ExceptionTransactionRepository.class));

        assertThat(resolver.provisionSettlementVa(programId, "AED").getParentAccountId()).isNull();

        VirtualAccount inMirror = VirtualAccount.builder().programId(programId).currencyCode("AED").parentAccountId(mirror.getId()).build();
        assertThat(resolver.provisionSettlementVa(inMirror).getParentAccountId()).isEqualTo(rootId);
    }
}
