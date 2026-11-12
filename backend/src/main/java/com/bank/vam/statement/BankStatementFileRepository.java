package com.bank.vam.statement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BankStatementFileRepository extends JpaRepository<BankStatementFile, UUID> {
}
