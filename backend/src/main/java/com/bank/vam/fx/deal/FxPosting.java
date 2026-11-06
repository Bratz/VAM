package com.bank.vam.fx.deal;

import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.repository.PhysicalAccountRepository;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.service.treasury.CoreBankingBalance;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Posts one FX settlement leg to a home-bank account: the account's mirror, the bank account behind it, and a
 * movement on the account's statement. A debit may take the account below zero; the funding item says so.
 */
@Component
@RequiredArgsConstructor
class FxPosting {

    private final VirtualAccountRepository mirrors;
    private final PhysicalAccountRepository banks;
    private final TransactionRepository movements;

    /** Why the account cannot take a posting in the currency, or null when it can. */
    String problem(UUID accountId, String currency) {
        if (accountId == null) {
            return "no " + currency + " settlement account";
        }
        VirtualAccount v = mirrors.findById(accountId).orElse(null);
        if (v == null || v.getStatus() != VirtualAccount.VaStatus.ACTIVE) {
            return "the " + currency + " settlement account is no longer active; change it on the deal";
        }
        return currency.equals(v.getCurrencyCode()) ? null : "the " + currency + " settlement account holds " + v.getCurrencyCode();
    }

    /** Moves {@code delta} (negative debits) and returns the account's bank balance after it. */
    BigDecimal post(UUID accountId, BigDecimal delta, LocalDate valueDate, String dealRef, String description) {
        VirtualAccount v = mirrors.findById(accountId).orElseThrow();
        BigDecimal before = v.getCurrentBalance();
        v.applyShadowMovement(delta);
        mirrors.save(v);
        CoreBankingBalance.follow(banks, v, delta);
        Transaction.MovementType type = delta.signum() >= 0 ? Transaction.MovementType.CREDIT : Transaction.MovementType.DEBIT;
        movements.save(Transaction.builder()
                .movementType(type)
                .transactionCategory(Transaction.TransactionCategory.EXTERNAL)
                .corporateId(v.getCorporateId())
                .vaId(v.getId())
                .physicalAccountId(v.getLinkedPhysicalAccountId())
                .programId(v.getProgramId())
                .amount(delta.abs())
                .currencyCode(v.getCurrencyCode())
                .balanceBefore(before)
                .balanceAfter(v.getCurrentBalance())
                .transactionDate(LocalDateTime.now())
                .valueDate(valueDate)
                .referenceNumber(Transaction.generateReference(type))
                .description(description)
                .correlationId(dealRef)
                .status(Transaction.TransactionStatus.COMPLETED)
                .build());
        return v.getBankBalance();
    }
}
