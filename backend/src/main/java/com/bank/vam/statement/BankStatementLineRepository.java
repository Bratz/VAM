package com.bank.vam.statement;

import com.bank.vam.statement.BankStatementLine.Status;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface BankStatementLineRepository extends JpaRepository<BankStatementLine, UUID> {

    /** Account view: lines of the given statements, newest value date first. */
    List<BankStatementLine> findByBankStatementIdInOrderByValueDateDescCreatedAtAsc(Collection<UUID> bankStatementIds, Pageable page);

    List<BankStatementLine> findByBankStatementIdInAndStatus(Collection<UUID> bankStatementIds, Status status);
}
