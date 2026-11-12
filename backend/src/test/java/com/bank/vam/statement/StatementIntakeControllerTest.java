package com.bank.vam.statement;

import com.bank.vam.statement.StatementIntakeController.AccountStatements;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Spec acceptance 7: the account's lines come with the applied statements they belong to. */
class StatementIntakeControllerTest {

    private final BankStatementRepository statements = mock(BankStatementRepository.class);
    private final BankStatementLineRepository lines = mock(BankStatementLineRepository.class);
    private final StatementIntakeController controller = new StatementIntakeController(mock(StatementIntakeService.class),
            mock(BankStatementFileRepository.class), statements, lines);

    @Test
    void acceptance7_accountLinesWithTheirStatements() {
        UUID account = UUID.randomUUID();
        BankStatement s = new BankStatement();
        s.setId(UUID.randomUUID());
        BankStatementLine l = new BankStatementLine();
        l.setBankStatementId(s.getId());
        when(statements.findByPhysicalAccountIdAndStatusOrderByStatementDateDescCreatedAtDesc(eq(account), eq(BankStatement.Status.APPLIED), any()))
                .thenReturn(List.of(s));
        when(lines.findByBankStatementIdInOrderByValueDateDescCreatedAtAsc(eq(List.of(s.getId())), any(Pageable.class))).thenReturn(List.of(l));

        AccountStatements r = controller.lines(account).getData();

        assertEquals(List.of(s), r.statements());
        assertEquals(s.getId(), r.lines().get(0).getBankStatementId());
    }

    @Test
    void noStatements_noLineQuery() {
        assertTrue(controller.lines(UUID.randomUUID()).getData().lines().isEmpty());
        verifyNoInteractions(lines);
    }
}
