package com.bank.vam.service;

import com.bank.vam.dto.TransactionDto;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final VirtualAccountRepository virtualAccountRepository;

    @Transactional(readOnly = true)
    public Transaction getById(UUID id) {
        return transactionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + id));
    }

    @Transactional(readOnly = true)
    public Transaction getByReference(String referenceNumber) {
        return transactionRepository.findByReferenceNumber(referenceNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + referenceNumber));
    }

    @Transactional(readOnly = true)
    public Page<Transaction> getByVaId(UUID vaId, Pageable pageable) {
        return transactionRepository.findByVaId(vaId, pageable);
    }

    @Transactional(readOnly = true)
    public List<Transaction> getRecentByVaId(UUID vaId, int limit) {
        return transactionRepository.findRecentByVaId(vaId, PageRequest.of(0, limit));
    }

    @Transactional
    public Transaction credit(TransactionDto.CreditRequest request) {
        log.info("Processing credit: VA={}, Amount={}", request.getVaId(), request.getAmount());

        VirtualAccount va = virtualAccountRepository.findById(request.getVaId())
                .orElseThrow(() -> new ResourceNotFoundException("Virtual account not found"));

        BigDecimal balanceBefore = va.getCurrentBalance();
        BigDecimal balanceAfter = balanceBefore.add(request.getAmount());

        // Update VA balance
        va.setCurrentBalance(balanceAfter);
        va.setAvailableBalance(balanceAfter);
        virtualAccountRepository.save(va);

        // Create transaction record
        Transaction txn = Transaction.builder()
                .movementType(Transaction.MovementType.CREDIT)
                .vaId(va.getId())
                .physicalAccountId(va.getPhysicalAccountId())
                .amount(request.getAmount())
                .currencyCode(va.getCurrencyCode())
                .balanceBefore(balanceBefore)
                .balanceAfter(balanceAfter)
                .transactionDate(LocalDateTime.now())
                .valueDate(request.getValueDate())
                .referenceNumber(generateReferenceNumber())
                .description(request.getDescription())
                .channel(request.getChannel())
                .remitterName(request.getRemitterName())
                .remitterAccount(request.getRemitterAccount())
                .status(Transaction.TransactionStatus.COMPLETED)
                .externalReference(request.getExternalReference())
                .build();

        return transactionRepository.save(txn);
    }

    @Transactional
    public Transaction debit(TransactionDto.DebitRequest request) {
        log.info("Processing debit: VA={}, Amount={}", request.getVaId(), request.getAmount());

        VirtualAccount va = virtualAccountRepository.findById(request.getVaId())
                .orElseThrow(() -> new ResourceNotFoundException("Virtual account not found"));

        BigDecimal balanceBefore = va.getCurrentBalance();

        // Check sufficient balance
        if (balanceBefore.compareTo(request.getAmount()) < 0) {
            throw new BusinessException("Insufficient balance");
        }

        BigDecimal balanceAfter = balanceBefore.subtract(request.getAmount());

        // Update VA balance
        va.setCurrentBalance(balanceAfter);
        va.setAvailableBalance(balanceAfter);
        virtualAccountRepository.save(va);

        // Create transaction record
        Transaction txn = Transaction.builder()
                .movementType(Transaction.MovementType.DEBIT)
                .vaId(va.getId())
                .physicalAccountId(va.getPhysicalAccountId())
                .amount(request.getAmount())
                .currencyCode(va.getCurrencyCode())
                .balanceBefore(balanceBefore)
                .balanceAfter(balanceAfter)
                .transactionDate(LocalDateTime.now())
                .valueDate(request.getValueDate())
                .referenceNumber(generateReferenceNumber())
                .description(request.getDescription())
                .channel(request.getChannel())
                .beneficiaryName(request.getBeneficiaryName())
                .beneficiaryAccount(request.getBeneficiaryAccount())
                .status(Transaction.TransactionStatus.COMPLETED)
                .externalReference(request.getExternalReference())
                .build();

        return transactionRepository.save(txn);
    }

    @Transactional
    public Transaction transfer(TransactionDto.TransferRequest request) {
        log.info("Processing transfer: From={}, To={}, Amount={}", 
                request.getFromVaId(), request.getToVaId(), request.getAmount());

        // Debit source
        TransactionDto.DebitRequest debitRequest = TransactionDto.DebitRequest.builder()
                .vaId(request.getFromVaId())
                .amount(request.getAmount())
                .description("Transfer to " + request.getToVaId())
                .channel("INTERNAL")
                .build();
        Transaction debitTxn = debit(debitRequest);

        // Credit destination
        TransactionDto.CreditRequest creditRequest = TransactionDto.CreditRequest.builder()
                .vaId(request.getToVaId())
                .amount(request.getAmount())
                .description("Transfer from " + request.getFromVaId())
                .channel("INTERNAL")
                .build();
        Transaction creditTxn = credit(creditRequest);

        // Link the transactions
        debitTxn.setCounterpartyVaId(request.getToVaId());
        creditTxn.setCounterpartyVaId(request.getFromVaId());
        
        transactionRepository.save(debitTxn);
        transactionRepository.save(creditTxn);

        return debitTxn;
    }

    private String generateReferenceNumber() {
        return "TXN" + System.currentTimeMillis() + String.format("%04d", (int)(Math.random() * 10000));
    }
}
