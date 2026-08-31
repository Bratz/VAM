package com.bank.vam.service.statement;

import com.bank.vam.dto.statement.Camt053Dto.*;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.statement.VaStatementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Implementation of VaStatementService for hierarchical VA statement generation.
 *
 * <h2>Key Implementation Details:</h2>
 * <ul>
 *   <li>Uses VaStatementRepository for recursive CTE queries</li>
 *   <li>Caches VA hierarchy trees for performance</li>
 *   <li>Batch loads transactions to minimize database round-trips</li>
 *   <li>Supports pagination for large statement periods</li>
 * </ul>
 *
 * <h2>Hierarchy Traversal Algorithm:</h2>
 * <pre>
 * 1. Use recursive CTE to get all descendant VA IDs in single query
 * 2. Cache the hierarchy for subsequent requests
 * 3. Batch load transactions for all VAs in the hierarchy
 * 4. Sort and merge transactions by booking datetime
 * 5. Calculate aggregated balances bottom-up
 * </pre>
 *
 * @see VaStatementService
 * @see VaStatementRepository
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VaStatementServiceImpl implements VaStatementService {

    private final VirtualAccountRepository virtualAccountRepository;
    private final TransactionRepository transactionRepository;
    private final VaStatementRepository statementRepository;

    private static final DateTimeFormatter ISO_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // ========================================================================
    // SINGLE VA STATEMENT GENERATION
    // ========================================================================

    @Override
    @Transactional(readOnly = true)
    public Camt053Statement generateStatement(UUID vaId, LocalDate fromDate, LocalDate toDate) {
        return generateStatement(StatementRequest.builder()
            .vaId(vaId)
            .fromDate(fromDate)
            .toDate(toDate)
            .includeEntries(true)
            .build());
    }

    @Override
    @Transactional(readOnly = true)
    public Camt053Statement generateStatement(StatementRequest request) {
        long startTime = System.currentTimeMillis();
        log.info("Generating statement for VA {} from {} to {}",
            request.getVaId(), request.getFromDate(), request.getToDate());

        // 1. Get the VA
        VirtualAccount va = getVirtualAccount(request.getVaId());

        // 2. Get transactions in date range
        List<Transaction> transactions = transactionRepository.findByVaIdAndDateRange(
            request.getVaId(),
            request.getFromDate().atStartOfDay(),
            request.getToDate().plusDays(1).atStartOfDay()
        );

        // 3. Calculate balances
        Balance openingBalance = calculateOpeningBalance(request.getVaId(), request.getFromDate());
        Balance closingBalance = calculateClosingBalance(request.getVaId(), request.getToDate());
        Balance availableBalance = createAvailableBalance(va, request.getToDate());

        // 4. Build transaction summary
        TransactionSummary summary = buildTransactionSummary(transactions);

        // 5. Convert transactions to entries
        List<StatementEntry> entries = request.isIncludeEntries()
            ? convertToEntries(transactions, va)
            : Collections.emptyList();

        // 6. Build statement
        Camt053Statement statement = Camt053Statement.builder()
            .messageId(generateMessageId("STMT"))
            .statementId(generateStatementId(va.getVaNumber(), request.getFromDate(), request.getToDate()))
            .sequenceNumber(1)
            .creationDateTime(LocalDateTime.now())
            .fromDate(request.getFromDate())
            .toDate(request.getToDate())
            .account(buildAccountInfo(va))
            .aggregated(false)
            .includedVaIds(Collections.singletonList(va.getId()))
            .balances(Arrays.asList(openingBalance, closingBalance, availableBalance))
            .transactionSummary(summary)
            .entries(entries)
            .processingInfo(ProcessingInfo.builder()
                .processingTimeMs(System.currentTimeMillis() - startTime)
                .vasProcessed(1)
                .totalEntriesBeforePagination(transactions.size())
                .hierarchyDepth(0)
                .usedCache(false)
                .generatedAt(LocalDateTime.now())
                .build())
            .build();

        // 7. Generate XML if needed
        statement.setXml(generateCamt053Xml(statement));

        log.info("Generated statement for VA {} with {} entries in {}ms",
            va.getVaNumber(), entries.size(), System.currentTimeMillis() - startTime);

        return statement;
    }

    // ========================================================================
    // AGGREGATED STATEMENT GENERATION (HIERARCHICAL)
    // ========================================================================

    @Override
    @Transactional(readOnly = true)
    public Camt053Statement generateAggregatedStatement(UUID aggregationVaId, LocalDate fromDate, LocalDate toDate) {
        return generateAggregatedStatement(AggregatedStatementRequest.builder()
            .aggregationVaId(aggregationVaId)
            .fromDate(fromDate)
            .toDate(toDate)
            .includeChildEntries(true)
            .includeVaIdentifiers(true)
            .build());
    }

    @Override
    @Transactional(readOnly = true)
    public Camt053Statement generateAggregatedStatement(AggregatedStatementRequest request) {
        long startTime = System.currentTimeMillis();
        log.info("Generating aggregated statement for VA {} from {} to {}",
            request.getAggregationVaId(), request.getFromDate(), request.getToDate());

        // 1. Get the aggregation VA
        VirtualAccount aggregationVa = getVirtualAccount(request.getAggregationVaId());

        // 2. Get all child VA IDs recursively (using cached hierarchy if available)
        List<UUID> allVaIds = getCachedVaHierarchy(request.getAggregationVaId());
        if (allVaIds.isEmpty()) {
            allVaIds = new ArrayList<>();
            allVaIds.add(request.getAggregationVaId());
        }

        log.debug("Found {} VAs in hierarchy for aggregation", allVaIds.size());

        // 3. Batch load transactions for all VAs
        List<Transaction> allTransactions = statementRepository.findTransactionsByVaIdsAndDateRange(
            allVaIds,
            request.getFromDate().atStartOfDay(),
            request.getToDate().plusDays(1).atStartOfDay()
        );

        // 4. Sort transactions by booking datetime
        allTransactions.sort(Comparator.comparing(Transaction::getTransactionDate));

        // 5. Calculate aggregated opening balance
        BigDecimal aggregatedOpeningBalance = calculateAggregatedOpeningBalance(allVaIds, request.getFromDate());

        // 6. Calculate aggregated closing balance
        BigDecimal aggregatedClosingBalance = calculateAggregatedClosingBalance(allVaIds, request.getToDate());

        // 7. Calculate aggregated available balance
        BigDecimal aggregatedAvailableBalance = statementRepository.sumAvailableBalanceForVaIds(allVaIds);

        // 8. Build balances
        List<Balance> balances = Arrays.asList(
            Balance.builder()
                .type(BalanceType.OPBD)
                .amount(aggregatedOpeningBalance.abs())
                .currencyCode(aggregationVa.getCurrencyCode())
                .creditDebitIndicator(aggregatedOpeningBalance.compareTo(BigDecimal.ZERO) >= 0
                    ? CreditDebitIndicator.CRDT : CreditDebitIndicator.DBIT)
                .date(request.getFromDate())
                .build(),
            Balance.builder()
                .type(BalanceType.CLBD)
                .amount(aggregatedClosingBalance.abs())
                .currencyCode(aggregationVa.getCurrencyCode())
                .creditDebitIndicator(aggregatedClosingBalance.compareTo(BigDecimal.ZERO) >= 0
                    ? CreditDebitIndicator.CRDT : CreditDebitIndicator.DBIT)
                .date(request.getToDate())
                .build(),
            Balance.builder()
                .type(BalanceType.CLAV)
                .amount(aggregatedAvailableBalance != null ? aggregatedAvailableBalance.abs() : BigDecimal.ZERO)
                .currencyCode(aggregationVa.getCurrencyCode())
                .creditDebitIndicator(aggregatedAvailableBalance != null && aggregatedAvailableBalance.compareTo(BigDecimal.ZERO) >= 0
                    ? CreditDebitIndicator.CRDT : CreditDebitIndicator.DBIT)
                .date(request.getToDate())
                .build()
        );

        // 9. Build transaction summary
        TransactionSummary summary = buildTransactionSummary(allTransactions);

        // 10. Convert transactions to entries with VA identifiers
        List<StatementEntry> entries = new ArrayList<>();
        if (request.isIncludeChildEntries()) {
            // Pre-load VA info for efficient lookup
            Map<UUID, VirtualAccount> vaMap = loadVaMap(allVaIds);

            for (Transaction txn : allTransactions) {
                VirtualAccount sourceVa = vaMap.get(txn.getVaId());
                StatementEntry entry = convertToEntry(txn, sourceVa);

                if (request.isIncludeVaIdentifiers() && sourceVa != null) {
                    entry.setSourceVaId(sourceVa.getId());
                    entry.setSourceVaNumber(sourceVa.getVaNumber());
                    entry.setSourceVaName(sourceVa.getVaName());
                }

                entries.add(entry);
            }
        }

        // 11. Build VA summaries if requested
        List<VaSummary> vaSummaries = new ArrayList<>();
        if (request.isIncludeSummaryByVa()) {
            vaSummaries = buildVaSummaries(allVaIds, allTransactions, request.getFromDate(), request.getToDate());
        }

        // 12. Calculate hierarchy depth
        int maxDepth = statementRepository.getMaxHierarchyDepth(request.getAggregationVaId());

        // 13. Build statement
        Camt053Statement statement = Camt053Statement.builder()
            .messageId(generateMessageId("AGGR"))
            .statementId(generateStatementId(aggregationVa.getVaNumber() + "-AGGR", request.getFromDate(), request.getToDate()))
            .sequenceNumber(1)
            .creationDateTime(LocalDateTime.now())
            .fromDate(request.getFromDate())
            .toDate(request.getToDate())
            .account(buildAccountInfo(aggregationVa))
            .aggregated(true)
            .includedVaIds(allVaIds)
            .balances(balances)
            .transactionSummary(summary)
            .entries(entries)
            .vaSummaries(vaSummaries)
            .processingInfo(ProcessingInfo.builder()
                .processingTimeMs(System.currentTimeMillis() - startTime)
                .vasProcessed(allVaIds.size())
                .totalEntriesBeforePagination(allTransactions.size())
                .hierarchyDepth(maxDepth)
                .usedCache(true)
                .generatedAt(LocalDateTime.now())
                .build())
            .build();

        // 14. Generate XML
        statement.setXml(generateCamt053Xml(statement));

        log.info("Generated aggregated statement for VA {} with {} VAs, {} entries in {}ms",
            aggregationVa.getVaNumber(), allVaIds.size(), entries.size(), System.currentTimeMillis() - startTime);

        return statement;
    }

    // ========================================================================
    // HIERARCHY TRAVERSAL
    // ========================================================================

    @Override
    @Transactional(readOnly = true)
    public List<UUID> getAllChildVaIds(UUID parentVaId) {
        return getAllChildVaIds(parentVaId, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> getAllChildVaIds(UUID parentVaId, Integer maxDepth) {
        log.debug("Getting all child VA IDs for parent {} with maxDepth {}", parentVaId, maxDepth);

        // Use recursive CTE query
        List<UUID> childIds = statementRepository.findAllChildVaIdsRecursive(parentVaId, maxDepth);

        log.debug("Found {} child VAs for parent {}", childIds.size(), parentVaId);
        return childIds;
    }

    @Override
    @Transactional(readOnly = true)
    public VaHierarchyNode getVaHierarchyTree(UUID rootVaId) {
        return getVaHierarchyTree(rootVaId, null);
    }

    @Override
    @Transactional(readOnly = true)
    public VaHierarchyNode getVaHierarchyTree(UUID rootVaId, Integer maxDepth) {
        // Get root VA
        VirtualAccount rootVa = getVirtualAccount(rootVaId);

        // Build tree recursively
        return buildHierarchyNode(rootVa, 0, maxDepth);
    }

    private VaHierarchyNode buildHierarchyNode(VirtualAccount va, int currentDepth, Integer maxDepth) {
        VaHierarchyNode node = VaHierarchyNode.builder()
            .vaId(va.getId())
            .vaNumber(va.getVaNumber())
            .vaName(va.getVaName())
            .parentVaId(va.getParentAccountId())
            .hierarchyLevel(va.getHierarchyLevel() != null ? va.getHierarchyLevel() : currentDepth)
            .hierarchyPath(va.getHierarchyPathVa())
            .accountCategory(va.getAccountCategory() != null ? va.getAccountCategory().name() : null)
            .currencyCode(va.getCurrencyCode())
            .currentBalance(va.getCurrentBalance())
            .availableBalance(va.getAvailableBalance())
            .children(new ArrayList<>())
            .build();

        // Check depth limit
        if (maxDepth != null && currentDepth >= maxDepth) {
            node.setLeaf(true);
            return node;
        }

        // Get children
        List<VirtualAccount> children = virtualAccountRepository.findByParentAccountId(va.getId());

        if (children.isEmpty()) {
            node.setLeaf(true);
        } else {
            node.setLeaf(false);
            for (VirtualAccount child : children) {
                node.getChildren().add(buildHierarchyNode(child, currentDepth + 1, maxDepth));
            }
        }

        return node;
    }

    // ========================================================================
    // BALANCE CALCULATION
    // ========================================================================

    @Override
    @Transactional(readOnly = true)
    public AggregatedBalance calculateAggregatedBalance(UUID vaId, LocalDate asOfDate) {
        return calculateAggregatedBalance(BalanceCalculationRequest.builder()
            .vaId(vaId)
            .asOfDate(asOfDate)
            .includeChildren(true)
            .build());
    }

    @Override
    @Transactional(readOnly = true)
    public AggregatedBalance calculateAggregatedBalance(BalanceCalculationRequest request) {
        VirtualAccount va = getVirtualAccount(request.getVaId());

        List<UUID> vaIds;
        if (request.isIncludeChildren()) {
            vaIds = getCachedVaHierarchy(request.getVaId());
        } else {
            vaIds = Collections.singletonList(request.getVaId());
        }

        // Calculate aggregated values
        BigDecimal bookedBalance = statementRepository.sumCurrentBalanceForVaIds(vaIds);
        BigDecimal availableBalance = statementRepository.sumAvailableBalanceForVaIds(vaIds);
        BigDecimal heldBalance = statementRepository.sumHeldBalanceForVaIds(vaIds);
        BigDecimal creditLimit = statementRepository.sumCreditLimitForVaIds(vaIds);

        // Currency breakdown
        List<Object[]> currencyData = statementRepository.sumBalancesByVaIdsGroupedByCurrency(vaIds);
        List<CurrencyBalance> currencyBreakdown = currencyData.stream()
            .map(row -> CurrencyBalance.builder()
                .currencyCode((String) row[0])
                .bookedBalance((BigDecimal) row[1])
                .availableBalance((BigDecimal) row[2])
                .vaCount(((Number) row[3]).intValue())
                .build())
            .collect(Collectors.toList());

        return AggregatedBalance.builder()
            .vaId(request.getVaId())
            .vaNumber(va.getVaNumber())
            .asOfDate(request.getAsOfDate())
            .bookedBalance(bookedBalance)
            .availableBalance(availableBalance)
            .heldBalance(heldBalance)
            .creditLimit(creditLimit)
            .effectiveAvailable(availableBalance.add(creditLimit != null ? creditLimit : BigDecimal.ZERO))
            .currencyCode(va.getCurrencyCode())
            .childVaCount(vaIds.size())
            .includedVaIds(vaIds)
            .currencyBreakdown(currencyBreakdown)
            .build();
    }

    @Override
    @Transactional(readOnly = true)
    public Balance calculateOpeningBalance(UUID vaId, LocalDate date) {
        VirtualAccount va = getVirtualAccount(vaId);

        // Get all VA IDs in hierarchy
        List<UUID> vaIds = getCachedVaHierarchy(vaId);

        // Calculate opening balance using historical calculation
        BigDecimal openingBalance = calculateHistoricalBalance(vaIds, date.atStartOfDay());

        return Balance.builder()
            .type(BalanceType.OPBD)
            .amount(openingBalance.abs())
            .currencyCode(va.getCurrencyCode())
            .creditDebitIndicator(openingBalance.compareTo(BigDecimal.ZERO) >= 0
                ? CreditDebitIndicator.CRDT : CreditDebitIndicator.DBIT)
            .date(date)
            .build();
    }

    @Override
    @Transactional(readOnly = true)
    public Balance calculateClosingBalance(UUID vaId, LocalDate date) {
        VirtualAccount va = getVirtualAccount(vaId);

        // Get all VA IDs in hierarchy
        List<UUID> vaIds = getCachedVaHierarchy(vaId);

        // Calculate closing balance using historical calculation
        BigDecimal closingBalance = calculateHistoricalBalance(vaIds, date.plusDays(1).atStartOfDay());

        return Balance.builder()
            .type(BalanceType.CLBD)
            .amount(closingBalance.abs())
            .currencyCode(va.getCurrencyCode())
            .creditDebitIndicator(closingBalance.compareTo(BigDecimal.ZERO) >= 0
                ? CreditDebitIndicator.CRDT : CreditDebitIndicator.DBIT)
            .date(date)
            .build();
    }

    /**
     * Calculate historical balance for a set of VAs at a specific point in time.
     *
     * Formula: balance_at_time = current_balance - sum(credits_after_time) + sum(debits_after_time)
     */
    private BigDecimal calculateHistoricalBalance(List<UUID> vaIds, LocalDateTime asOfTime) {
        // Get current balance
        BigDecimal currentBalance = statementRepository.sumCurrentBalanceForVaIds(vaIds);

        // Get transactions after the as-of time
        BigDecimal creditsAfter = statementRepository.sumCreditsAfterTime(vaIds, asOfTime);
        BigDecimal debitsAfter = statementRepository.sumDebitsAfterTime(vaIds, asOfTime);

        // Calculate historical balance
        return currentBalance
            .subtract(creditsAfter != null ? creditsAfter : BigDecimal.ZERO)
            .add(debitsAfter != null ? debitsAfter : BigDecimal.ZERO);
    }

    private BigDecimal calculateAggregatedOpeningBalance(List<UUID> vaIds, LocalDate date) {
        return calculateHistoricalBalance(vaIds, date.atStartOfDay());
    }

    private BigDecimal calculateAggregatedClosingBalance(List<UUID> vaIds, LocalDate date) {
        return calculateHistoricalBalance(vaIds, date.plusDays(1).atStartOfDay());
    }

    // ========================================================================
    // INTRADAY / NOTIFICATION STATEMENTS
    // ========================================================================

    @Override
    @Transactional(readOnly = true)
    public Camt053Statement generateIntradayStatement(UUID vaId, LocalDateTime asOfTime, boolean aggregated) {
        // Similar to regular statement but uses interim balance types
        VirtualAccount va = getVirtualAccount(vaId);

        List<UUID> vaIds = aggregated ? getCachedVaHierarchy(vaId) : Collections.singletonList(vaId);

        // Get transactions for today up to asOfTime
        LocalDate today = asOfTime.toLocalDate();
        List<Transaction> transactions = statementRepository.findTransactionsByVaIdsAndDateRange(
            vaIds,
            today.atStartOfDay(),
            asOfTime
        );

        BigDecimal currentBalance = calculateHistoricalBalance(vaIds, asOfTime);

        List<Balance> balances = Arrays.asList(
            Balance.builder()
                .type(BalanceType.ITBD)
                .amount(currentBalance.abs())
                .currencyCode(va.getCurrencyCode())
                .creditDebitIndicator(currentBalance.compareTo(BigDecimal.ZERO) >= 0
                    ? CreditDebitIndicator.CRDT : CreditDebitIndicator.DBIT)
                .dateTime(asOfTime)
                .build()
        );

        return Camt053Statement.builder()
            .messageId(generateMessageId("INTR"))
            .statementId(generateStatementId(va.getVaNumber() + "-INTR", today, today))
            .sequenceNumber(1)
            .creationDateTime(LocalDateTime.now())
            .fromDate(today)
            .toDate(today)
            .account(buildAccountInfo(va))
            .aggregated(aggregated)
            .includedVaIds(vaIds)
            .balances(balances)
            .transactionSummary(buildTransactionSummary(transactions))
            .entries(convertToEntries(transactions, va))
            .build();
    }

    @Override
    @Transactional(readOnly = true)
    public Camt053Statement generateTransactionNotification(UUID transactionId) {
        Transaction txn = transactionRepository.findById(transactionId)
            .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + transactionId));

        VirtualAccount va = getVirtualAccount(txn.getVaId());

        StatementEntry entry = convertToEntry(txn, va);

        return Camt053Statement.builder()
            .messageId(generateMessageId("NTFN"))
            .statementId(txn.getReferenceNumber())
            .sequenceNumber(1)
            .creationDateTime(LocalDateTime.now())
            .fromDate(txn.getTransactionDate().toLocalDate())
            .toDate(txn.getTransactionDate().toLocalDate())
            .account(buildAccountInfo(va))
            .aggregated(false)
            .includedVaIds(Collections.singletonList(va.getId()))
            .entries(Collections.singletonList(entry))
            .transactionSummary(TransactionSummary.builder()
                .totalEntries(1)
                .creditEntryCount(entry.getCreditDebitIndicator() == CreditDebitIndicator.CRDT ? 1 : 0)
                .creditEntrySum(entry.getCreditDebitIndicator() == CreditDebitIndicator.CRDT ? entry.getAmount() : BigDecimal.ZERO)
                .debitEntryCount(entry.getCreditDebitIndicator() == CreditDebitIndicator.DBIT ? 1 : 0)
                .debitEntrySum(entry.getCreditDebitIndicator() == CreditDebitIndicator.DBIT ? entry.getAmount() : BigDecimal.ZERO)
                .netMovement(entry.getCreditDebitIndicator() == CreditDebitIndicator.CRDT ? entry.getAmount() : entry.getAmount().negate())
                .build())
            .build();
    }

    // ========================================================================
    // XML GENERATION
    // ========================================================================

    @Override
    public String generateCamt053Xml(Camt053Statement statement) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.053.001.08\">\n");
        xml.append("  <BkToCstmrStmt>\n");

        // Group Header
        xml.append("    <GrpHdr>\n");
        xml.append("      <MsgId>").append(escape(statement.getMessageId())).append("</MsgId>\n");
        xml.append("      <CreDtTm>").append(statement.getCreationDateTime().format(ISO_DATETIME)).append("</CreDtTm>\n");
        xml.append("    </GrpHdr>\n");

        // Statement
        xml.append("    <Stmt>\n");
        xml.append("      <Id>").append(escape(statement.getStatementId())).append("</Id>\n");
        xml.append("      <ElctrncSeqNb>").append(statement.getSequenceNumber()).append("</ElctrncSeqNb>\n");
        xml.append("      <CreDtTm>").append(statement.getCreationDateTime().format(ISO_DATETIME)).append("</CreDtTm>\n");

        // Account
        AccountInfo account = statement.getAccount();
        xml.append("      <Acct>\n");
        xml.append("        <Id>\n");
        if (account.getIban() != null) {
            xml.append("          <IBAN>").append(escape(account.getIban())).append("</IBAN>\n");
        } else {
            xml.append("          <Othr><Id>").append(escape(account.getVaNumber())).append("</Id></Othr>\n");
        }
        xml.append("        </Id>\n");
        xml.append("        <Ccy>").append(account.getCurrencyCode()).append("</Ccy>\n");
        xml.append("        <Nm>").append(escape(account.getVaName())).append("</Nm>\n");
        xml.append("      </Acct>\n");

        // Balances
        for (Balance bal : statement.getBalances()) {
            xml.append("      <Bal>\n");
            xml.append("        <Tp><CdOrPrtry><Cd>").append(bal.getType().name()).append("</Cd></CdOrPrtry></Tp>\n");
            xml.append("        <Amt Ccy=\"").append(bal.getCurrencyCode()).append("\">").append(bal.getAmount()).append("</Amt>\n");
            xml.append("        <CdtDbtInd>").append(bal.getCreditDebitIndicator().name()).append("</CdtDbtInd>\n");
            xml.append("        <Dt><Dt>").append(bal.getDate().format(ISO_DATE)).append("</Dt></Dt>\n");
            xml.append("      </Bal>\n");
        }

        // Transaction Summary
        TransactionSummary summary = statement.getTransactionSummary();
        if (summary != null) {
            xml.append("      <TxsSummry>\n");
            xml.append("        <TtlNtries><NbOfNtries>").append(summary.getTotalEntries()).append("</NbOfNtries></TtlNtries>\n");
            xml.append("        <TtlCdtNtries>\n");
            xml.append("          <NbOfNtries>").append(summary.getCreditEntryCount()).append("</NbOfNtries>\n");
            xml.append("          <Sum>").append(summary.getCreditEntrySum()).append("</Sum>\n");
            xml.append("        </TtlCdtNtries>\n");
            xml.append("        <TtlDbtNtries>\n");
            xml.append("          <NbOfNtries>").append(summary.getDebitEntryCount()).append("</NbOfNtries>\n");
            xml.append("          <Sum>").append(summary.getDebitEntrySum()).append("</Sum>\n");
            xml.append("        </TtlDbtNtries>\n");
            xml.append("      </TxsSummry>\n");
        }

        // Entries
        for (StatementEntry entry : statement.getEntries()) {
            appendEntryXml(xml, entry, statement.isAggregated());
        }

        xml.append("    </Stmt>\n");
        xml.append("  </BkToCstmrStmt>\n");
        xml.append("</Document>");

        return xml.toString();
    }

    private void appendEntryXml(StringBuilder xml, StatementEntry entry, boolean includeVaInfo) {
        xml.append("      <Ntry>\n");
        xml.append("        <Amt Ccy=\"").append(entry.getCurrencyCode()).append("\">").append(entry.getAmount()).append("</Amt>\n");
        xml.append("        <CdtDbtInd>").append(entry.getCreditDebitIndicator().name()).append("</CdtDbtInd>\n");
        xml.append("        <Sts><Cd>").append(entry.getStatus().name()).append("</Cd></Sts>\n");
        xml.append("        <BookgDt><Dt>").append(entry.getBookingDate().format(ISO_DATE)).append("</Dt></BookgDt>\n");

        if (entry.getValueDate() != null) {
            xml.append("        <ValDt><Dt>").append(entry.getValueDate().format(ISO_DATE)).append("</Dt></ValDt>\n");
        }

        xml.append("        <AcctSvcrRef>").append(escape(entry.getEntryReference())).append("</AcctSvcrRef>\n");

        // Entry Details
        if (entry.getEntryDetails() != null) {
            EntryDetails details = entry.getEntryDetails();
            xml.append("        <NtryDtls>\n");
            xml.append("          <TxDtls>\n");
            xml.append("            <Refs>\n");
            xml.append("              <MsgId>").append(escape(details.getMessageId())).append("</MsgId>\n");
            if (details.getEndToEndId() != null) {
                xml.append("              <EndToEndId>").append(escape(details.getEndToEndId())).append("</EndToEndId>\n");
            }
            xml.append("            </Refs>\n");

            // Related Parties
            if (details.getRelatedParties() != null) {
                RelatedParties parties = details.getRelatedParties();
                xml.append("            <RltdPties>\n");
                if (parties.getDebtorName() != null) {
                    xml.append("              <Dbtr><Pty><Nm>").append(escape(parties.getDebtorName())).append("</Nm></Pty></Dbtr>\n");
                }
                if (parties.getCreditorName() != null) {
                    xml.append("              <Cdtr><Pty><Nm>").append(escape(parties.getCreditorName())).append("</Nm></Pty></Cdtr>\n");
                }
                xml.append("            </RltdPties>\n");
            }

            // Remittance Info
            if (details.getRemittanceInfo() != null) {
                xml.append("            <RmtInf>\n");
                xml.append("              <Ustrd>").append(escape(details.getRemittanceInfo())).append("</Ustrd>\n");
                xml.append("            </RmtInf>\n");
            }

            // Source VA info for aggregated statements
            if (includeVaInfo && entry.getSourceVaNumber() != null) {
                xml.append("            <AddtlTxInf>Source VA: ").append(escape(entry.getSourceVaNumber()));
                if (entry.getSourceVaName() != null) {
                    xml.append(" (").append(escape(entry.getSourceVaName())).append(")");
                }
                xml.append("</AddtlTxInf>\n");
            }

            xml.append("          </TxDtls>\n");
            xml.append("        </NtryDtls>\n");
        }

        xml.append("      </Ntry>\n");
    }

    @Override
    public String generateCamt052Xml(Camt053Statement statement) {
        // Similar to camt.053 but with different namespace and interim balances
        return generateCamt053Xml(statement)
            .replace("camt.053.001.08", "camt.052.001.08")
            .replace("BkToCstmrStmt", "BkToCstmrAcctRpt");
    }

    @Override
    public String generateCamt054Xml(Camt053Statement statement) {
        // Similar to camt.053 but with notification-specific structure
        return generateCamt053Xml(statement)
            .replace("camt.053.001.08", "camt.054.001.08")
            .replace("BkToCstmrStmt", "BkToCstmrDbtCdtNtfctn");
    }

    // ========================================================================
    // CACHING
    // ========================================================================

    @Override
    @Cacheable(value = "vaHierarchy", key = "#rootVaId")
    @Transactional(readOnly = true)
    public List<UUID> getCachedVaHierarchy(UUID rootVaId) {
        log.debug("Building VA hierarchy cache for root {}", rootVaId);
        List<UUID> allIds = getAllChildVaIds(rootVaId, null);
        allIds.add(0, rootVaId);  // Include root
        return allIds;
    }

    @Override
    @CacheEvict(value = "vaHierarchy", key = "#rootVaId")
    public void invalidateHierarchyCache(UUID rootVaId) {
        log.info("Invalidating VA hierarchy cache for root {}", rootVaId);
    }

    @Override
    @Transactional(readOnly = true)
    public void preloadHierarchyCache(UUID corporateId) {
        log.info("Preloading VA hierarchy cache for corporate {}", corporateId);

        // Find all root VAs for this corporate
        List<VirtualAccount> rootVas = virtualAccountRepository.findRootsByCorporateId(corporateId);

        for (VirtualAccount root : rootVas) {
            getCachedVaHierarchy(root.getId());
        }

        log.info("Preloaded hierarchy cache for {} root VAs", rootVas.size());
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private VirtualAccount getVirtualAccount(UUID vaId) {
        return virtualAccountRepository.findById(vaId)
            .orElseThrow(() -> new ResourceNotFoundException("Virtual account not found: " + vaId));
    }

    private AccountInfo buildAccountInfo(VirtualAccount va) {
        return AccountInfo.builder()
            .vaId(va.getId())
            .vaNumber(va.getVaNumber())
            .vaName(va.getVaName())
            .iban(va.getViban())
            .currencyCode(va.getCurrencyCode())
            .accountCategory(va.getAccountCategory() != null ? va.getAccountCategory().name() : null)
            .corporateId(va.getCorporateId())
            .programId(va.getProgramId())
            .build();
    }

    private Balance createAvailableBalance(VirtualAccount va, LocalDate date) {
        BigDecimal availableBalance = va.getAvailableBalance() != null ? va.getAvailableBalance() : BigDecimal.ZERO;
        return Balance.builder()
            .type(BalanceType.CLAV)
            .amount(availableBalance.abs())
            .currencyCode(va.getCurrencyCode())
            .creditDebitIndicator(availableBalance.compareTo(BigDecimal.ZERO) >= 0
                ? CreditDebitIndicator.CRDT : CreditDebitIndicator.DBIT)
            .date(date)
            .build();
    }

    private TransactionSummary buildTransactionSummary(List<Transaction> transactions) {
        int creditCount = 0;
        BigDecimal creditSum = BigDecimal.ZERO;
        int debitCount = 0;
        BigDecimal debitSum = BigDecimal.ZERO;

        for (Transaction txn : transactions) {
            if (txn.isCredit()) {
                creditCount++;
                creditSum = creditSum.add(txn.getAmount());
            } else if (txn.isDebit()) {
                debitCount++;
                debitSum = debitSum.add(txn.getAmount());
            }
        }

        return TransactionSummary.builder()
            .totalEntries(transactions.size())
            .creditEntryCount(creditCount)
            .creditEntrySum(creditSum)
            .debitEntryCount(debitCount)
            .debitEntrySum(debitSum)
            .netMovement(creditSum.subtract(debitSum))
            .build();
    }

    private List<StatementEntry> convertToEntries(List<Transaction> transactions, VirtualAccount va) {
        return transactions.stream()
            .map(txn -> convertToEntry(txn, va))
            .collect(Collectors.toList());
    }

    private StatementEntry convertToEntry(Transaction txn, VirtualAccount va) {
        return StatementEntry.builder()
            .entryReference(txn.getReferenceNumber())
            .amount(txn.getAmount())
            .currencyCode(txn.getCurrencyCode())
            .creditDebitIndicator(txn.isCredit() ? CreditDebitIndicator.CRDT : CreditDebitIndicator.DBIT)
            .status(txn.getStatus() == Transaction.TransactionStatus.COMPLETED ? EntryStatus.BOOK : EntryStatus.PDNG)
            .bookingDate(txn.getTransactionDate().toLocalDate())
            .valueDate(txn.getValueDate())
            .bookingDateTime(txn.getTransactionDate())
            .accountServicerReference(txn.getBancsReference())
            .bankTransactionCode(BankTransactionCode.builder()
                .domain("PMNT")
                .family(txn.isCredit() ? "RCDT" : "ICDT")
                .subFamily("ESCT")
                .proprietaryCode(txn.getMovementType() != null ? txn.getMovementType().name() : null)
                .build())
            .entryDetails(EntryDetails.builder()
                .transactionId(txn.getId())
                .messageId(txn.getReferenceNumber())
                .endToEndId(txn.getCorrelationId())
                .instructionId(txn.getExternalReference())
                .relatedParties(RelatedParties.builder()
                    .debtorName(txn.getRemitterName())
                    .debtorAccount(txn.getRemitterAccount())
                    .creditorName(txn.getBeneficiaryName())
                    .creditorAccount(txn.getBeneficiaryAccount())
                    .build())
                .remittanceInfo(txn.getDescription())
                .movementType(txn.getMovementType() != null ? txn.getMovementType().name() : null)
                .channel(txn.getChannel())
                .build())
            .sourceVaId(va != null ? va.getId() : txn.getVaId())
            .sourceVaNumber(va != null ? va.getVaNumber() : null)
            .sourceVaName(va != null ? va.getVaName() : null)
            .build();
    }

    private Map<UUID, VirtualAccount> loadVaMap(List<UUID> vaIds) {
        return virtualAccountRepository.findAllById(vaIds).stream()
            .collect(Collectors.toMap(VirtualAccount::getId, va -> va));
    }

    private List<VaSummary> buildVaSummaries(List<UUID> vaIds, List<Transaction> allTransactions,
                                              LocalDate fromDate, LocalDate toDate) {
        Map<UUID, VirtualAccount> vaMap = loadVaMap(vaIds);
        Map<UUID, List<Transaction>> txnByVa = allTransactions.stream()
            .collect(Collectors.groupingBy(Transaction::getVaId));

        List<VaSummary> summaries = new ArrayList<>();
        for (UUID vaId : vaIds) {
            VirtualAccount va = vaMap.get(vaId);
            if (va == null) continue;

            List<Transaction> vaTxns = txnByVa.getOrDefault(vaId, Collections.emptyList());
            TransactionSummary summary = buildTransactionSummary(vaTxns);

            summaries.add(VaSummary.builder()
                .vaId(va.getId())
                .vaNumber(va.getVaNumber())
                .vaName(va.getVaName())
                .currencyCode(va.getCurrencyCode())
                .openingBalance(calculateHistoricalBalance(Collections.singletonList(vaId), fromDate.atStartOfDay()))
                .closingBalance(calculateHistoricalBalance(Collections.singletonList(vaId), toDate.plusDays(1).atStartOfDay()))
                .creditCount(summary.getCreditEntryCount())
                .creditSum(summary.getCreditEntrySum())
                .debitCount(summary.getDebitEntryCount())
                .debitSum(summary.getDebitEntrySum())
                .netMovement(summary.getNetMovement())
                .entryCount(vaTxns.size())
                .hierarchyLevel(va.getHierarchyLevel() != null ? va.getHierarchyLevel() : 0)
                .build());
        }

        return summaries;
    }

    private String generateMessageId(String prefix) {
        return prefix + "-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private String generateStatementId(String vaNumber, LocalDate fromDate, LocalDate toDate) {
        return String.format("STMT-%s-%s-%s",
            vaNumber.length() > 8 ? vaNumber.substring(0, 8) : vaNumber,
            fromDate.format(DateTimeFormatter.BASIC_ISO_DATE),
            toDate.format(DateTimeFormatter.BASIC_ISO_DATE));
    }

    private String escape(String value) {
        if (value == null) return "";
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }
}
