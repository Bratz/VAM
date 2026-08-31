package com.bank.vam.iso20022.service;

import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.iso20022.dto.Iso20022PaymentDto.*;
import com.bank.vam.iso20022.mapper.Iso20022MessageMapper;
import com.bank.vam.iso20022.util.Iso20022XmlBuilder;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * ISO 20022 Status and Statement Service.
 *
 * Provides:
 * - Payment status queries (pain.002)
 * - Bank statements (camt.053)
 *
 * Integrates with existing Transaction repository.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class Iso20022StatusService {

    private final TransactionRepository transactionRepository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final Iso20022MessageMapper messageMapper;
    private final Iso20022XmlBuilder xmlBuilder;

    // ========================================================================
    // PAYMENT STATUS QUERY
    // ========================================================================

    /**
     * Get payment status by various identifiers.
     */
    @Transactional(readOnly = true)
    public PaymentStatusResponse getPaymentStatus(PaymentStatusRequest request) {
        log.info("Getting payment status: originalMsgId={}, txnRef={}",
                request.getOriginalMessageId(), request.getTransactionReference());

        Transaction txn = findTransaction(request);

        PaymentStatusResponse response = messageMapper.mapTransactionToStatus(txn);

        // Generate pain.002 XML using custom builder
        response.setPain002Xml(xmlBuilder.buildPain002(txn, response));

        return response;
    }

    private Transaction findTransaction(PaymentStatusRequest request) {
        if (request.getTransactionReference() != null) {
            return transactionRepository.findByReferenceNumber(request.getTransactionReference())
                    .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + request.getTransactionReference()));
        }
        if (request.getOriginalMessageId() != null) {
            return transactionRepository.findByReferenceNumber(request.getOriginalMessageId())
                    .orElseThrow(() -> new ResourceNotFoundException("Transaction not found for message: " + request.getOriginalMessageId()));
        }
        if (request.getOriginalEndToEndId() != null) {
            return transactionRepository.findByCorrelationId(request.getOriginalEndToEndId())
                    .orElseThrow(() -> new ResourceNotFoundException("Transaction not found for endToEndId: " + request.getOriginalEndToEndId()));
        }
        throw new BusinessException("At least one identifier is required");
    }

    // ========================================================================
    // BANK STATEMENT (camt.053)
    // ========================================================================

    /**
     * Generate bank statement for a VA.
     */
    @Transactional(readOnly = true)
    public StatementResponse generateStatement(StatementRequest request) {
        log.info("Generating ISO 20022 statement: va={}, from={}, to={}",
                request.getVirtualAccountId() != null ? request.getVirtualAccountId() : request.getVaNumber(),
                request.getFromDate(), request.getToDate());

        // Get VA
        VirtualAccount va;
        if (request.getVirtualAccountId() != null) {
            va = virtualAccountRepository.findById(request.getVirtualAccountId())
                    .orElseThrow(() -> new ResourceNotFoundException("Virtual account not found"));
        } else {
            va = virtualAccountRepository.findByVaNumber(request.getVaNumber())
                    .orElseThrow(() -> new ResourceNotFoundException("Virtual account not found: " + request.getVaNumber()));
        }

        // Get transactions for period
        LocalDate fromDate = request.getFromDate() != null ? request.getFromDate() : LocalDate.now().minusDays(30);
        LocalDate toDate = request.getToDate() != null ? request.getToDate() : LocalDate.now();

        LocalDateTime fromDateTime = fromDate.atStartOfDay();
        LocalDateTime toDateTime = toDate.plusDays(1).atStartOfDay();

        Page<Transaction> transactionPage = transactionRepository.findByVaIdAndTransactionDateBetween(
                va.getId(), fromDateTime, toDateTime,
                PageRequest.of(0, 1000, Sort.by(Sort.Direction.ASC, "transactionDate")));

        List<Transaction> transactions = transactionPage.getContent();

        // Calculate summaries
        BigDecimal creditSum = BigDecimal.ZERO;
        BigDecimal debitSum = BigDecimal.ZERO;
        int creditCount = 0;
        int debitCount = 0;

        for (Transaction txn : transactions) {
            if (txn.isCredit()) {
                creditSum = creditSum.add(txn.getAmount());
                creditCount++;
            } else if (txn.isDebit()) {
                debitSum = debitSum.add(txn.getAmount());
                debitCount++;
            }
        }

        // Determine opening balance (balance before first transaction or current if no transactions)
        BigDecimal openingBalance = transactions.isEmpty() ?
                va.getCurrentBalance() :
                transactions.get(0).getBalanceBefore() != null ?
                        transactions.get(0).getBalanceBefore() : BigDecimal.ZERO;

        String messageId = "STMT-" + System.currentTimeMillis();
        String statementId = "STM" + fromDate.toString().replace("-", "") + "-" + toDate.toString().replace("-", "");

        // Build response first (needed for XML generation)
        StatementResponse response = StatementResponse.builder()
                .messageId(messageId)
                .statementId(statementId)
                .accountIban(va.getViban())
                .accountCurrency(va.getCurrencyCode())
                .fromDate(fromDate)
                .toDate(toDate)
                .openingBalance(openingBalance)
                .closingBalance(va.getCurrentBalance())
                .creditCount(creditCount)
                .creditSum(creditSum)
                .debitCount(debitCount)
                .debitSum(debitSum)
                .entryCount(transactions.size())
                .build();

        // Generate camt.053 XML using custom builder
        String camt053Xml = xmlBuilder.buildCamt053(va, transactions, fromDate, toDate, openingBalance, response);
        response.setCamt053Xml(camt053Xml);

        return response;
    }
}
