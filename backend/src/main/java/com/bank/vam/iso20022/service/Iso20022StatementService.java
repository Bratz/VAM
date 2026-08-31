package com.bank.vam.iso20022.service;

import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.iso20022.dto.Iso20022StatementDto.*;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ISO 20022 Statement Service.
 *
 * Provides generation of:
 * - camt.053.001.08 - Bank-to-Customer Statement
 * - camt.054.001.08 - Bank-to-Customer Debit/Credit Notification
 * - Aggregated statements with child account consolidation
 *
 * Features:
 * - Sync and async statement generation
 * - XML and JSON output formats
 * - Pagination for large statements
 * - Statement storage and retrieval
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class Iso20022StatementService {

    private final TransactionRepository transactionRepository;
    private final VirtualAccountRepository virtualAccountRepository;

    // In-memory storage for demo - replace with database in production
    private final Map<String, StatementDetail> statementStore = new ConcurrentHashMap<>();
    private final Map<String, AsyncJobStatus> asyncJobStore = new ConcurrentHashMap<>();

    private static final int ASYNC_THRESHOLD_DAYS = 90;
    private static final int ASYNC_THRESHOLD_ENTRIES = 10000;
    private static final DateTimeFormatter ISO_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // ========================================================================
    // CAMT.053 - BANK TO CUSTOMER STATEMENT
    // ========================================================================

    /**
     * Check if request requires async processing.
     */
    public boolean requiresAsyncProcessing(Camt053GenerateRequest request) {
        long daysBetween = ChronoUnit.DAYS.between(request.getFromDate(), request.getToDate());
        if (daysBetween > ASYNC_THRESHOLD_DAYS) {
            return true;
        }

        // Check transaction count estimate
        VirtualAccount va = getVirtualAccount(request.getVaId(), request.getVaNumber());
        long estimatedCount = transactionRepository.countByVaIdAndTransactionDateBetween(
                va.getId(),
                request.getFromDate().atStartOfDay(),
                request.getToDate().plusDays(1).atStartOfDay());

        return estimatedCount > ASYNC_THRESHOLD_ENTRIES;
    }

    /**
     * Generate camt.053 statement synchronously.
     */
    @Transactional(readOnly = true)
    public Camt053Response generateCamt053(Camt053GenerateRequest request) {
        long startTime = System.currentTimeMillis();
        log.info("Generating camt.053 statement: vaId={}, from={}, to={}",
                request.getVaId(), request.getFromDate(), request.getToDate());

        VirtualAccount va = getVirtualAccount(request.getVaId(), request.getVaNumber());

        // Get transactions
        LocalDateTime fromDateTime = request.getFromDate().atStartOfDay();
        LocalDateTime toDateTime = request.getToDate().plusDays(1).atStartOfDay();

        int pageSize = request.getMaxEntries() != null ? request.getMaxEntries() : 1000;
        int pageNum = request.getPage() != null ? request.getPage() : 0;

        Page<Transaction> transactionPage = transactionRepository.findByVaIdAndTransactionDateBetween(
                va.getId(), fromDateTime, toDateTime,
                PageRequest.of(pageNum, pageSize, Sort.by(Sort.Direction.ASC, "transactionDate")));

        List<Transaction> transactions = transactionPage.getContent();

        // Build summary
        StatementSummary summary = buildStatementSummary(va, transactions, request.getFromDate(), request.getToDate());

        // Build entries
        List<StatementEntry> entries = transactions.stream()
                .map(this::mapTransactionToEntry)
                .collect(Collectors.toList());

        // Handle child accounts if requested
        List<ChildAccountStatement> childStatements = null;
        if (request.isIncludeChildren()) {
            childStatements = buildChildAccountStatements(va.getId(), request.getFromDate(), request.getToDate());
        }

        // Generate statement ID
        String statementId = generateStatementId("STMT", va.getVaNumber(), request.getFromDate());
        String messageId = "MSG-" + statementId;

        // Build XML if requested
        String camt053Xml = null;
        if (request.getFormat() == OutputFormat.XML) {
            camt053Xml = buildCamt053Xml(va, transactions, summary, messageId, statementId,
                    request.getFromDate(), request.getToDate());
        }

        // Build pagination info
        PaginationInfo pagination = PaginationInfo.builder()
                .page(pageNum)
                .size(pageSize)
                .totalElements(transactionPage.getTotalElements())
                .totalPages(transactionPage.getTotalPages())
                .hasNext(transactionPage.hasNext())
                .hasPrevious(transactionPage.hasPrevious())
                .build();

        long processingTime = System.currentTimeMillis() - startTime;

        Camt053Response response = Camt053Response.builder()
                .statementId(statementId)
                .messageId(messageId)
                .status(GenerationStatus.COMPLETED)
                .vaId(va.getId())
                .vaNumber(va.getVaNumber())
                .accountIban(va.getViban())
                .currency(va.getCurrencyCode())
                .fromDate(request.getFromDate())
                .toDate(request.getToDate())
                .generatedAt(LocalDateTime.now())
                .summary(summary)
                .entries(entries)
                .childStatements(childStatements)
                .pagination(pagination)
                .downloadUrl("/api/v1/iso20022/statements/" + statementId + "/download")
                .camt053Xml(camt053Xml)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .processingTimeMs(processingTime)
                .build();

        // Store for later retrieval
        storeStatement(response, va);

        log.info("Generated camt.053 statement {} with {} entries in {}ms",
                statementId, entries.size(), processingTime);

        return response;
    }

    /**
     * Generate camt.053 statement asynchronously.
     */
    public AsyncJobResponse generateCamt053Async(Camt053GenerateRequest request) {
        String jobId = "JOB-" + UUID.randomUUID().toString().substring(0, 8);

        AsyncJobStatus jobStatus = AsyncJobStatus.builder()
                .jobId(jobId)
                .status(GenerationStatus.PENDING)
                .progressPercent(0)
                .currentStep("Initializing")
                .startedAt(LocalDateTime.now())
                .estimatedCompletion(LocalDateTime.now().plusMinutes(5))
                .build();

        asyncJobStore.put(jobId, jobStatus);

        // Start async processing
        processAsyncCamt053(jobId, request);

        return AsyncJobResponse.builder()
                .jobId(jobId)
                .status(GenerationStatus.PENDING)
                .message("Statement generation started")
                .statusUrl("/api/v1/iso20022/statements/jobs/" + jobId)
                .estimatedCompletion(jobStatus.getEstimatedCompletion())
                .build();
    }

    @Async("taskExecutor")
    protected void processAsyncCamt053(String jobId, Camt053GenerateRequest request) {
        AsyncJobStatus jobStatus = asyncJobStore.get(jobId);
        try {
            jobStatus.setStatus(GenerationStatus.PROCESSING);
            jobStatus.setCurrentStep("Fetching transactions");
            jobStatus.setProgressPercent(10);

            Camt053Response response = generateCamt053(request);

            jobStatus.setStatus(GenerationStatus.COMPLETED);
            jobStatus.setStatementId(response.getStatementId());
            jobStatus.setDownloadUrl(response.getDownloadUrl());
            jobStatus.setProgressPercent(100);
            jobStatus.setCompletedAt(LocalDateTime.now());
            jobStatus.setCurrentStep("Completed");

        } catch (Exception e) {
            log.error("Async camt.053 generation failed for job {}: ", jobId, e);
            jobStatus.setStatus(GenerationStatus.FAILED);
            jobStatus.setErrorMessage(e.getMessage());
            jobStatus.setCompletedAt(LocalDateTime.now());
        }
    }

    // ========================================================================
    // CAMT.054 - DEBIT/CREDIT NOTIFICATION
    // ========================================================================

    @Transactional(readOnly = true)
    public Camt054Response generateCamt054(Camt054GenerateRequest request) {
        long startTime = System.currentTimeMillis();
        log.info("Generating camt.054 notification: vaId={}, from={}, to={}",
                request.getVaId(), request.getFromDate(), request.getToDate());

        VirtualAccount va = getVirtualAccount(request.getVaId(), request.getVaNumber());

        LocalDate fromDate = request.getFromDate() != null ? request.getFromDate() : LocalDate.now();
        LocalDate toDate = request.getToDate() != null ? request.getToDate() : LocalDate.now();

        LocalDateTime fromDateTime = fromDate.atStartOfDay();
        LocalDateTime toDateTime = toDate.plusDays(1).atStartOfDay();

        // Get transactions
        Page<Transaction> transactionPage = transactionRepository.findByVaIdAndTransactionDateBetween(
                va.getId(), fromDateTime, toDateTime,
                PageRequest.of(0, 1000, Sort.by(Sort.Direction.DESC, "transactionDate")));

        List<Transaction> transactions = transactionPage.getContent();

        // Apply filters
        if (request.isCreditsOnly()) {
            transactions = transactions.stream()
                    .filter(Transaction::isCredit)
                    .collect(Collectors.toList());
        } else if (request.isDebitsOnly()) {
            transactions = transactions.stream()
                    .filter(Transaction::isDebit)
                    .collect(Collectors.toList());
        }

        if (request.getMinAmount() != null) {
            transactions = transactions.stream()
                    .filter(t -> t.getAmount().compareTo(request.getMinAmount()) >= 0)
                    .collect(Collectors.toList());
        }

        if (request.getMaxAmount() != null) {
            transactions = transactions.stream()
                    .filter(t -> t.getAmount().compareTo(request.getMaxAmount()) <= 0)
                    .collect(Collectors.toList());
        }

        if (request.getTransactionId() != null) {
            transactions = transactions.stream()
                    .filter(t -> t.getId().equals(request.getTransactionId()))
                    .collect(Collectors.toList());
        }

        // Build summary
        NotificationSummary summary = buildNotificationSummary(transactions, va.getCurrencyCode());

        // Build entries
        List<NotificationEntry> entries = transactions.stream()
                .map(this::mapTransactionToNotificationEntry)
                .collect(Collectors.toList());

        String notificationId = generateStatementId("NTF", va.getVaNumber(), fromDate);
        String messageId = "MSG-" + notificationId;

        // Build XML if requested
        String camt054Xml = null;
        if (request.getFormat() == OutputFormat.XML) {
            camt054Xml = buildCamt054Xml(va, transactions, summary, messageId, notificationId, fromDate, toDate);
        }

        long processingTime = System.currentTimeMillis() - startTime;

        return Camt054Response.builder()
                .notificationId(notificationId)
                .messageId(messageId)
                .status(GenerationStatus.COMPLETED)
                .vaId(va.getId())
                .vaNumber(va.getVaNumber())
                .accountIban(va.getViban())
                .currency(va.getCurrencyCode())
                .fromDate(fromDate)
                .toDate(toDate)
                .generatedAt(LocalDateTime.now())
                .summary(summary)
                .entries(entries)
                .camt054Xml(camt054Xml)
                .downloadUrl("/api/v1/iso20022/statements/" + notificationId + "/download")
                .processingTimeMs(processingTime)
                .build();
    }

    // ========================================================================
    // AGGREGATED STATEMENT
    // ========================================================================

    @Transactional(readOnly = true)
    public AggregatedStatementResponse generateAggregatedStatement(AggregatedStatementRequest request) {
        long startTime = System.currentTimeMillis();
        log.info("Generating aggregated statement: vaId={}, from={}, to={}, depth={}",
                request.getVaId(), request.getFromDate(), request.getToDate(), request.getDepth());

        VirtualAccount parentVa = getVirtualAccount(request.getVaId(), null);

        // Get all accounts in hierarchy
        List<VirtualAccount> accounts = new ArrayList<>();
        accounts.add(parentVa);

        if (request.isIncludeChildren()) {
            List<VirtualAccount> children = getChildAccounts(parentVa.getId(), request.getDepth());
            accounts.addAll(children);
        }

        // Generate statements for each account
        List<ChildAccountStatement> accountStatements = new ArrayList<>();
        Map<String, List<StatementSummary>> currencyGroups = new HashMap<>();

        for (VirtualAccount va : accounts) {
            Camt053GenerateRequest childRequest = Camt053GenerateRequest.builder()
                    .vaId(va.getId())
                    .fromDate(request.getFromDate())
                    .toDate(request.getToDate())
                    .format(OutputFormat.JSON)
                    .includeChildren(false)
                    .build();

            Camt053Response childResponse = generateCamt053(childRequest);

            ChildAccountStatement childStatement = ChildAccountStatement.builder()
                    .vaId(va.getId())
                    .vaNumber(va.getVaNumber())
                    .vaName(va.getVaName())
                    .accountIban(va.getViban())
                    .currency(va.getCurrencyCode())
                    .hierarchyLevel(va.getHierarchyLevel() != null ? va.getHierarchyLevel() : 0)
                    .parentVaId(va.getParentAccountId())
                    .summary(childResponse.getSummary())
                    .entries(request.isIncludeDetails() ? childResponse.getEntries() : null)
                    .build();

            accountStatements.add(childStatement);

            // Group by currency
            currencyGroups.computeIfAbsent(va.getCurrencyCode(), k -> new ArrayList<>())
                    .add(childResponse.getSummary());
        }

        // Build currency summaries
        List<CurrencySummary> currencySummaries = new ArrayList<>();
        for (Map.Entry<String, List<StatementSummary>> entry : currencyGroups.entrySet()) {
            CurrencySummary currencySummary = CurrencySummary.builder()
                    .currency(entry.getKey())
                    .accountCount(entry.getValue().size())
                    .summary(aggregateSummaries(entry.getValue(), entry.getKey()))
                    .build();
            currencySummaries.add(currencySummary);
        }

        // Build total summary (in base currency - parent's currency)
        List<StatementSummary> allSummaries = accountStatements.stream()
                .map(ChildAccountStatement::getSummary)
                .collect(Collectors.toList());
        StatementSummary totalSummary = aggregateSummaries(allSummaries, parentVa.getCurrencyCode());

        String statementId = generateStatementId("AGG", parentVa.getVaNumber(), request.getFromDate());
        long processingTime = System.currentTimeMillis() - startTime;

        return AggregatedStatementResponse.builder()
                .statementId(statementId)
                .vaId(parentVa.getId())
                .vaNumber(parentVa.getVaNumber())
                .fromDate(request.getFromDate())
                .toDate(request.getToDate())
                .generatedAt(LocalDateTime.now())
                .accountCount(accounts.size())
                .currencySummaries(request.isGroupByCurrency() ? currencySummaries : null)
                .totalSummary(totalSummary)
                .accountStatements(accountStatements)
                .downloadUrl("/api/v1/iso20022/statements/" + statementId + "/download")
                .processingTimeMs(processingTime)
                .build();
    }

    // ========================================================================
    // STATEMENT HISTORY & RETRIEVAL
    // ========================================================================

    public StatementHistoryResponse getStatementHistory(StatementHistoryRequest request) {
        log.info("Getting statement history: vaId={}, corporateId={}, type={}",
                request.getVaId(), request.getCorporateId(), request.getType());

        // Filter stored statements
        List<StatementHistoryEntry> entries = statementStore.values().stream()
                .filter(s -> request.getVaId() == null || s.getVaId().equals(request.getVaId()))
                .filter(s -> request.getCorporateId() == null || s.getCorporateId().equals(request.getCorporateId()))
                .filter(s -> request.getType() == null || s.getType().equals(request.getType()))
                .filter(s -> request.getStatus() == null || s.getStatus().equals(request.getStatus()))
                .map(this::mapToHistoryEntry)
                .sorted((a, b) -> {
                    if ("asc".equalsIgnoreCase(request.getSortDirection())) {
                        return a.getGeneratedAt().compareTo(b.getGeneratedAt());
                    }
                    return b.getGeneratedAt().compareTo(a.getGeneratedAt());
                })
                .skip((long) request.getPage() * request.getSize())
                .limit(request.getSize())
                .collect(Collectors.toList());

        long totalElements = statementStore.size();

        PaginationInfo pagination = PaginationInfo.builder()
                .page(request.getPage())
                .size(request.getSize())
                .totalElements(totalElements)
                .totalPages((int) Math.ceil((double) totalElements / request.getSize()))
                .hasNext((request.getPage() + 1) * request.getSize() < totalElements)
                .hasPrevious(request.getPage() > 0)
                .build();

        return StatementHistoryResponse.builder()
                .statements(entries)
                .pagination(pagination)
                .build();
    }

    public StatementDetail getStatementById(String statementId, boolean includeEntries, int page, int size) {
        log.info("Getting statement by ID: {}", statementId);

        StatementDetail statement = statementStore.get(statementId);
        if (statement == null) {
            throw new ResourceNotFoundException("Statement not found: " + statementId);
        }

        // Paginate entries if needed
        if (!includeEntries) {
            statement = StatementDetail.builder()
                    .statementId(statement.getStatementId())
                    .type(statement.getType())
                    .status(statement.getStatus())
                    .vaId(statement.getVaId())
                    .vaNumber(statement.getVaNumber())
                    .vaName(statement.getVaName())
                    .accountIban(statement.getAccountIban())
                    .currency(statement.getCurrency())
                    .corporateId(statement.getCorporateId())
                    .corporateName(statement.getCorporateName())
                    .fromDate(statement.getFromDate())
                    .toDate(statement.getToDate())
                    .generatedAt(statement.getGeneratedAt())
                    .expiresAt(statement.getExpiresAt())
                    .format(statement.getFormat())
                    .summary(statement.getSummary())
                    .entries(null)
                    .downloadUrl(statement.getDownloadUrl())
                    .includesChildren(statement.isIncludesChildren())
                    .childAccountCount(statement.getChildAccountCount())
                    .fileSize(statement.getFileSize())
                    .messageId(statement.getMessageId())
                    .build();
        }

        return statement;
    }

    public StatementDownloadResponse downloadStatement(String statementId, OutputFormat format) {
        log.info("Downloading statement: {} format={}", statementId, format);

        StatementDetail statement = statementStore.get(statementId);
        if (statement == null) {
            throw new ResourceNotFoundException("Statement not found: " + statementId);
        }

        // Regenerate in requested format if needed
        OutputFormat targetFormat = format != null ? format : statement.getFormat();

        String content;
        String contentType;
        String extension;

        switch (targetFormat) {
            case XML -> {
                content = generateXmlContent(statement);
                contentType = "application/xml";
                extension = "xml";
            }
            case JSON -> {
                content = generateJsonContent(statement);
                contentType = "application/json";
                extension = "json";
            }
            case CSV -> {
                content = generateCsvContent(statement);
                contentType = "text/csv";
                extension = "csv";
            }
            default -> {
                content = generateJsonContent(statement);
                contentType = "application/json";
                extension = "json";
            }
        }

        String fileName = String.format("%s_%s_%s.%s",
                statement.getType().name().toLowerCase(),
                statement.getVaNumber(),
                statement.getFromDate(),
                extension);

        return StatementDownloadResponse.builder()
                .statementId(statementId)
                .fileName(fileName)
                .contentType(contentType)
                .fileSize((long) content.length())
                .content(content)
                .urlExpiresAt(LocalDateTime.now().plusHours(1))
                .build();
    }

    // ========================================================================
    // ASYNC JOB MANAGEMENT
    // ========================================================================

    public AsyncJobStatus getAsyncJobStatus(String jobId) {
        AsyncJobStatus status = asyncJobStore.get(jobId);
        if (status == null) {
            throw new ResourceNotFoundException("Job not found: " + jobId);
        }
        return status;
    }

    public void cancelAsyncJob(String jobId) {
        AsyncJobStatus status = asyncJobStore.get(jobId);
        if (status == null) {
            throw new ResourceNotFoundException("Job not found: " + jobId);
        }
        if (status.getStatus() == GenerationStatus.COMPLETED ||
            status.getStatus() == GenerationStatus.FAILED) {
            throw new BusinessException("Cannot cancel completed or failed job");
        }
        status.setStatus(GenerationStatus.FAILED);
        status.setErrorMessage("Cancelled by user");
        status.setCompletedAt(LocalDateTime.now());
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private VirtualAccount getVirtualAccount(UUID vaId, String vaNumber) {
        if (vaId != null) {
            return virtualAccountRepository.findById(vaId)
                    .orElseThrow(() -> new ResourceNotFoundException("Virtual account not found: " + vaId));
        }
        if (vaNumber != null) {
            return virtualAccountRepository.findByVaNumber(vaNumber)
                    .orElseThrow(() -> new ResourceNotFoundException("Virtual account not found: " + vaNumber));
        }
        throw new BusinessException("Either vaId or vaNumber is required");
    }

    private List<VirtualAccount> getChildAccounts(UUID parentVaId, int maxDepth) {
        List<VirtualAccount> children = new ArrayList<>();
        collectChildren(parentVaId, children, 0, maxDepth);
        return children;
    }

    private void collectChildren(UUID parentVaId, List<VirtualAccount> collected, int currentDepth, int maxDepth) {
        if (maxDepth >= 0 && currentDepth >= maxDepth) {
            return;
        }

        List<VirtualAccount> directChildren = virtualAccountRepository.findByParentAccountId(parentVaId);
        for (VirtualAccount child : directChildren) {
            collected.add(child);
            collectChildren(child.getId(), collected, currentDepth + 1, maxDepth);
        }
    }

    private StatementSummary buildStatementSummary(VirtualAccount va, List<Transaction> transactions,
                                                    LocalDate fromDate, LocalDate toDate) {
        BigDecimal creditTotal = BigDecimal.ZERO;
        BigDecimal debitTotal = BigDecimal.ZERO;
        int creditCount = 0;
        int debitCount = 0;

        for (Transaction txn : transactions) {
            if (txn.isCredit()) {
                creditTotal = creditTotal.add(txn.getAmount());
                creditCount++;
            } else if (txn.isDebit()) {
                debitTotal = debitTotal.add(txn.getAmount());
                debitCount++;
            }
        }

        BigDecimal openingBalance = transactions.isEmpty() ? va.getCurrentBalance() :
                (transactions.get(0).getBalanceBefore() != null ?
                        transactions.get(0).getBalanceBefore() : BigDecimal.ZERO);

        return StatementSummary.builder()
                .openingBalance(openingBalance)
                .openingBalanceDate(fromDate)
                .openingBalanceIndicator(openingBalance.compareTo(BigDecimal.ZERO) >= 0 ? "CRDT" : "DBIT")
                .closingBalance(va.getCurrentBalance())
                .closingBalanceDate(toDate)
                .closingBalanceIndicator(va.getCurrentBalance().compareTo(BigDecimal.ZERO) >= 0 ? "CRDT" : "DBIT")
                .availableBalance(va.getAvailableBalance())
                .currency(va.getCurrencyCode())
                .entryCount(transactions.size())
                .creditCount(creditCount)
                .creditTotal(creditTotal)
                .debitCount(debitCount)
                .debitTotal(debitTotal)
                .netMovement(creditTotal.subtract(debitTotal))
                .build();
    }

    private NotificationSummary buildNotificationSummary(List<Transaction> transactions, String currency) {
        BigDecimal creditTotal = BigDecimal.ZERO;
        BigDecimal debitTotal = BigDecimal.ZERO;
        int creditCount = 0;
        int debitCount = 0;

        for (Transaction txn : transactions) {
            if (txn.isCredit()) {
                creditTotal = creditTotal.add(txn.getAmount());
                creditCount++;
            } else if (txn.isDebit()) {
                debitTotal = debitTotal.add(txn.getAmount());
                debitCount++;
            }
        }

        return NotificationSummary.builder()
                .totalCount(transactions.size())
                .creditCount(creditCount)
                .creditTotal(creditTotal)
                .debitCount(debitCount)
                .debitTotal(debitTotal)
                .currency(currency)
                .build();
    }

    private StatementEntry mapTransactionToEntry(Transaction txn) {
        return StatementEntry.builder()
                .transactionId(txn.getId())
                .entryReference(txn.getReferenceNumber())
                .amount(txn.getAmount())
                .currency(txn.getCurrencyCode())
                .creditDebitIndicator(txn.isCredit() ? "CRDT" : "DBIT")
                .reversal(false)
                .status("BOOK")
                .bookingDate(txn.getTransactionDate().toLocalDate())
                .valueDate(txn.getValueDate())
                .endToEndId(txn.getCorrelationId())
                .messageId(txn.getReferenceNumber())
                .debtor(txn.getRemitterName() != null ?
                        PartyDetails.builder().name(txn.getRemitterName()).build() : null)
                .debtorAccount(txn.getRemitterAccount() != null ?
                        AccountDetails.builder().accountNumber(txn.getRemitterAccount()).build() : null)
                .creditor(txn.getBeneficiaryName() != null ?
                        PartyDetails.builder().name(txn.getBeneficiaryName()).build() : null)
                .creditorAccount(txn.getBeneficiaryAccount() != null ?
                        AccountDetails.builder().accountNumber(txn.getBeneficiaryAccount()).build() : null)
                .remittanceInfo(txn.getDescription())
                .balanceAfter(txn.getBalanceAfter())
                .bankTransactionCode(BankTransactionCode.builder()
                        .domain("PMNT")
                        .family(txn.isCredit() ? "RCDT" : "ICDT")
                        .subFamily("DMCT")
                        .build())
                .build();
    }

    private NotificationEntry mapTransactionToNotificationEntry(Transaction txn) {
        return NotificationEntry.builder()
                .transactionId(txn.getId())
                .entryReference(txn.getReferenceNumber())
                .amount(txn.getAmount())
                .currency(txn.getCurrencyCode())
                .creditDebitIndicator(txn.isCredit() ? "CRDT" : "DBIT")
                .bookingDateTime(txn.getTransactionDate())
                .valueDate(txn.getValueDate())
                .endToEndId(txn.getCorrelationId())
                .counterparty(PartyDetails.builder()
                        .name(txn.isCredit() ? txn.getRemitterName() : txn.getBeneficiaryName())
                        .build())
                .counterpartyAccount(AccountDetails.builder()
                        .accountNumber(txn.isCredit() ? txn.getRemitterAccount() : txn.getBeneficiaryAccount())
                        .build())
                .remittanceInfo(txn.getDescription())
                .balanceAfter(txn.getBalanceAfter())
                .status("BOOK")
                .build();
    }

    private List<ChildAccountStatement> buildChildAccountStatements(UUID parentVaId, LocalDate fromDate, LocalDate toDate) {
        List<VirtualAccount> children = getChildAccounts(parentVaId, -1);
        return children.stream()
                .map(va -> {
                    List<Transaction> transactions = transactionRepository.findByVaIdAndTransactionDateBetween(
                            va.getId(),
                            fromDate.atStartOfDay(),
                            toDate.plusDays(1).atStartOfDay(),
                            PageRequest.of(0, 1000, Sort.by(Sort.Direction.ASC, "transactionDate"))
                    ).getContent();

                    return ChildAccountStatement.builder()
                            .vaId(va.getId())
                            .vaNumber(va.getVaNumber())
                            .vaName(va.getVaName())
                            .accountIban(va.getViban())
                            .currency(va.getCurrencyCode())
                            .hierarchyLevel(va.getHierarchyLevel() != null ? va.getHierarchyLevel() : 0)
                            .parentVaId(va.getParentAccountId())
                            .summary(buildStatementSummary(va, transactions, fromDate, toDate))
                            .build();
                })
                .collect(Collectors.toList());
    }

    private StatementSummary aggregateSummaries(List<StatementSummary> summaries, String currency) {
        BigDecimal totalOpening = BigDecimal.ZERO;
        BigDecimal totalClosing = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;
        BigDecimal totalDebits = BigDecimal.ZERO;
        int totalCreditCount = 0;
        int totalDebitCount = 0;

        for (StatementSummary summary : summaries) {
            totalOpening = totalOpening.add(summary.getOpeningBalance() != null ? summary.getOpeningBalance() : BigDecimal.ZERO);
            totalClosing = totalClosing.add(summary.getClosingBalance() != null ? summary.getClosingBalance() : BigDecimal.ZERO);
            totalCredits = totalCredits.add(summary.getCreditTotal() != null ? summary.getCreditTotal() : BigDecimal.ZERO);
            totalDebits = totalDebits.add(summary.getDebitTotal() != null ? summary.getDebitTotal() : BigDecimal.ZERO);
            totalCreditCount += summary.getCreditCount();
            totalDebitCount += summary.getDebitCount();
        }

        return StatementSummary.builder()
                .openingBalance(totalOpening)
                .closingBalance(totalClosing)
                .creditTotal(totalCredits)
                .debitTotal(totalDebits)
                .creditCount(totalCreditCount)
                .debitCount(totalDebitCount)
                .entryCount(totalCreditCount + totalDebitCount)
                .netMovement(totalCredits.subtract(totalDebits))
                .currency(currency)
                .build();
    }

    private String generateStatementId(String prefix, String vaNumber, LocalDate date) {
        return String.format("%s-%s-%s-%d",
                prefix,
                vaNumber.substring(0, Math.min(8, vaNumber.length())),
                date.format(DateTimeFormatter.BASIC_ISO_DATE),
                System.currentTimeMillis() % 10000);
    }

    private void storeStatement(Camt053Response response, VirtualAccount va) {
        StatementDetail detail = StatementDetail.builder()
                .statementId(response.getStatementId())
                .type(StatementType.CAMT053)
                .status(response.getStatus())
                .vaId(response.getVaId())
                .vaNumber(response.getVaNumber())
                .vaName(va.getVaName())
                .accountIban(response.getAccountIban())
                .currency(response.getCurrency())
                .corporateId(va.getCorporateId())
                .fromDate(response.getFromDate())
                .toDate(response.getToDate())
                .generatedAt(response.getGeneratedAt())
                .expiresAt(response.getExpiresAt())
                .format(OutputFormat.JSON)
                .summary(response.getSummary())
                .entries(response.getEntries())
                .downloadUrl(response.getDownloadUrl())
                .includesChildren(response.getChildStatements() != null && !response.getChildStatements().isEmpty())
                .childAccountCount(response.getChildStatements() != null ? response.getChildStatements().size() : 0)
                .messageId(response.getMessageId())
                .build();

        statementStore.put(response.getStatementId(), detail);
    }

    private StatementHistoryEntry mapToHistoryEntry(StatementDetail detail) {
        return StatementHistoryEntry.builder()
                .statementId(detail.getStatementId())
                .type(detail.getType())
                .vaId(detail.getVaId())
                .vaNumber(detail.getVaNumber())
                .corporateId(detail.getCorporateId())
                .fromDate(detail.getFromDate())
                .toDate(detail.getToDate())
                .status(detail.getStatus())
                .format(detail.getFormat())
                .generatedAt(detail.getGeneratedAt())
                .expiresAt(detail.getExpiresAt())
                .entryCount(detail.getSummary() != null ? detail.getSummary().getEntryCount() : 0)
                .downloadUrl(detail.getDownloadUrl())
                .includesChildren(detail.isIncludesChildren())
                .build();
    }

    // ========================================================================
    // XML/JSON/CSV GENERATION
    // ========================================================================

    private String buildCamt053Xml(VirtualAccount va, List<Transaction> transactions,
                                    StatementSummary summary, String messageId, String statementId,
                                    LocalDate fromDate, LocalDate toDate) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.053.001.08\">\n");
        xml.append("  <BkToCstmrStmt>\n");

        // Group Header
        xml.append("    <GrpHdr>\n");
        xml.append("      <MsgId>").append(escape(messageId)).append("</MsgId>\n");
        xml.append("      <CreDtTm>").append(LocalDateTime.now().format(ISO_DATETIME)).append("</CreDtTm>\n");
        xml.append("    </GrpHdr>\n");

        // Statement
        xml.append("    <Stmt>\n");
        xml.append("      <Id>").append(escape(statementId)).append("</Id>\n");
        xml.append("      <ElctrncSeqNb>1</ElctrncSeqNb>\n");
        xml.append("      <CreDtTm>").append(LocalDateTime.now().format(ISO_DATETIME)).append("</CreDtTm>\n");
        xml.append("      <FrToDt>\n");
        xml.append("        <FrDtTm>").append(fromDate.atStartOfDay().format(ISO_DATETIME)).append("</FrDtTm>\n");
        xml.append("        <ToDtTm>").append(toDate.atTime(23, 59, 59).format(ISO_DATETIME)).append("</ToDtTm>\n");
        xml.append("      </FrToDt>\n");

        // Account
        xml.append("      <Acct>\n");
        xml.append("        <Id>\n");
        xml.append("          <IBAN>").append(escape(va.getViban())).append("</IBAN>\n");
        xml.append("        </Id>\n");
        xml.append("        <Ccy>").append(va.getCurrencyCode()).append("</Ccy>\n");
        xml.append("        <Nm>").append(escape(va.getVaName())).append("</Nm>\n");
        xml.append("      </Acct>\n");

        // Balances
        appendBalance(xml, "OPBD", summary.getOpeningBalance(), va.getCurrencyCode(), fromDate);
        appendBalance(xml, "CLBD", summary.getClosingBalance(), va.getCurrencyCode(), toDate);
        if (summary.getAvailableBalance() != null) {
            appendBalance(xml, "AVLB", summary.getAvailableBalance(), va.getCurrencyCode(), toDate);
        }

        // Transaction Summary
        xml.append("      <TxsSummry>\n");
        xml.append("        <TtlNtries>\n");
        xml.append("          <NbOfNtries>").append(summary.getEntryCount()).append("</NbOfNtries>\n");
        xml.append("          <Sum>").append(summary.getCreditTotal().add(summary.getDebitTotal())).append("</Sum>\n");
        xml.append("        </TtlNtries>\n");
        xml.append("        <TtlCdtNtries>\n");
        xml.append("          <NbOfNtries>").append(summary.getCreditCount()).append("</NbOfNtries>\n");
        xml.append("          <Sum>").append(summary.getCreditTotal()).append("</Sum>\n");
        xml.append("        </TtlCdtNtries>\n");
        xml.append("        <TtlDbtNtries>\n");
        xml.append("          <NbOfNtries>").append(summary.getDebitCount()).append("</NbOfNtries>\n");
        xml.append("          <Sum>").append(summary.getDebitTotal()).append("</Sum>\n");
        xml.append("        </TtlDbtNtries>\n");
        xml.append("      </TxsSummry>\n");

        // Entries
        for (Transaction txn : transactions) {
            appendEntry(xml, txn);
        }

        xml.append("    </Stmt>\n");
        xml.append("  </BkToCstmrStmt>\n");
        xml.append("</Document>");

        return xml.toString();
    }

    private String buildCamt054Xml(VirtualAccount va, List<Transaction> transactions,
                                    NotificationSummary summary, String messageId, String notificationId,
                                    LocalDate fromDate, LocalDate toDate) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.054.001.08\">\n");
        xml.append("  <BkToCstmrDbtCdtNtfctn>\n");

        // Group Header
        xml.append("    <GrpHdr>\n");
        xml.append("      <MsgId>").append(escape(messageId)).append("</MsgId>\n");
        xml.append("      <CreDtTm>").append(LocalDateTime.now().format(ISO_DATETIME)).append("</CreDtTm>\n");
        xml.append("    </GrpHdr>\n");

        // Notification
        xml.append("    <Ntfctn>\n");
        xml.append("      <Id>").append(escape(notificationId)).append("</Id>\n");
        xml.append("      <CreDtTm>").append(LocalDateTime.now().format(ISO_DATETIME)).append("</CreDtTm>\n");

        // Account
        xml.append("      <Acct>\n");
        xml.append("        <Id>\n");
        xml.append("          <IBAN>").append(escape(va.getViban())).append("</IBAN>\n");
        xml.append("        </Id>\n");
        xml.append("        <Ccy>").append(va.getCurrencyCode()).append("</Ccy>\n");
        xml.append("      </Acct>\n");

        // Entries
        for (Transaction txn : transactions) {
            appendEntry(xml, txn);
        }

        xml.append("    </Ntfctn>\n");
        xml.append("  </BkToCstmrDbtCdtNtfctn>\n");
        xml.append("</Document>");

        return xml.toString();
    }

    private void appendBalance(StringBuilder xml, String type, BigDecimal amount, String currency, LocalDate date) {
        xml.append("      <Bal>\n");
        xml.append("        <Tp><CdOrPrtry><Cd>").append(type).append("</Cd></CdOrPrtry></Tp>\n");
        xml.append("        <Amt Ccy=\"").append(currency).append("\">").append(amount.abs()).append("</Amt>\n");
        xml.append("        <CdtDbtInd>").append(amount.compareTo(BigDecimal.ZERO) >= 0 ? "CRDT" : "DBIT").append("</CdtDbtInd>\n");
        xml.append("        <Dt><Dt>").append(date.format(ISO_DATE)).append("</Dt></Dt>\n");
        xml.append("      </Bal>\n");
    }

    private void appendEntry(StringBuilder xml, Transaction txn) {
        xml.append("      <Ntry>\n");
        xml.append("        <Amt Ccy=\"").append(txn.getCurrencyCode()).append("\">").append(txn.getAmount()).append("</Amt>\n");
        xml.append("        <CdtDbtInd>").append(txn.isCredit() ? "CRDT" : "DBIT").append("</CdtDbtInd>\n");
        xml.append("        <Sts><Cd>BOOK</Cd></Sts>\n");
        xml.append("        <BookgDt><Dt>").append(txn.getTransactionDate().toLocalDate().format(ISO_DATE)).append("</Dt></BookgDt>\n");
        if (txn.getValueDate() != null) {
            xml.append("        <ValDt><Dt>").append(txn.getValueDate().format(ISO_DATE)).append("</Dt></ValDt>\n");
        }
        xml.append("        <AcctSvcrRef>").append(escape(txn.getReferenceNumber())).append("</AcctSvcrRef>\n");
        xml.append("        <BkTxCd>\n");
        xml.append("          <Domn><Cd>PMNT</Cd><Fmly><Cd>").append(txn.isCredit() ? "RCDT" : "ICDT").append("</Cd>");
        xml.append("<SubFmlyCd>DMCT</SubFmlyCd></Fmly></Domn>\n");
        xml.append("        </BkTxCd>\n");

        // Entry Details
        xml.append("        <NtryDtls>\n");
        xml.append("          <TxDtls>\n");
        xml.append("            <Refs>\n");
        xml.append("              <AcctSvcrRef>").append(escape(txn.getReferenceNumber())).append("</AcctSvcrRef>\n");
        if (txn.getCorrelationId() != null) {
            xml.append("              <EndToEndId>").append(escape(txn.getCorrelationId())).append("</EndToEndId>\n");
        }
        xml.append("            </Refs>\n");

        if (txn.getRemitterName() != null || txn.getBeneficiaryName() != null) {
            xml.append("            <RltdPties>\n");
            if (txn.getRemitterName() != null) {
                xml.append("              <Dbtr><Pty><Nm>").append(escape(txn.getRemitterName())).append("</Nm></Pty></Dbtr>\n");
            }
            if (txn.getBeneficiaryName() != null) {
                xml.append("              <Cdtr><Pty><Nm>").append(escape(txn.getBeneficiaryName())).append("</Nm></Pty></Cdtr>\n");
            }
            xml.append("            </RltdPties>\n");
        }

        if (txn.getDescription() != null) {
            xml.append("            <RmtInf>\n");
            xml.append("              <Ustrd>").append(escape(txn.getDescription())).append("</Ustrd>\n");
            xml.append("            </RmtInf>\n");
        }

        xml.append("          </TxDtls>\n");
        xml.append("        </NtryDtls>\n");
        xml.append("      </Ntry>\n");
    }

    private String generateXmlContent(StatementDetail statement) {
        // Regenerate XML from stored data
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!-- Statement XML for " + statement.getStatementId() + " -->";
    }

    private String generateJsonContent(StatementDetail statement) {
        // Simple JSON representation
        return String.format("""
                {
                  "statementId": "%s",
                  "vaNumber": "%s",
                  "fromDate": "%s",
                  "toDate": "%s",
                  "summary": {
                    "openingBalance": %s,
                    "closingBalance": %s,
                    "creditCount": %d,
                    "creditTotal": %s,
                    "debitCount": %d,
                    "debitTotal": %s
                  }
                }
                """,
                statement.getStatementId(),
                statement.getVaNumber(),
                statement.getFromDate(),
                statement.getToDate(),
                statement.getSummary().getOpeningBalance(),
                statement.getSummary().getClosingBalance(),
                statement.getSummary().getCreditCount(),
                statement.getSummary().getCreditTotal(),
                statement.getSummary().getDebitCount(),
                statement.getSummary().getDebitTotal()
        );
    }

    private String generateCsvContent(StatementDetail statement) {
        StringBuilder csv = new StringBuilder();
        csv.append("Date,Reference,Type,Amount,Currency,Description,Balance\n");

        if (statement.getEntries() != null) {
            for (StatementEntry entry : statement.getEntries()) {
                csv.append(String.format("%s,%s,%s,%s,%s,\"%s\",%s\n",
                        entry.getBookingDate(),
                        entry.getEntryReference(),
                        entry.getCreditDebitIndicator(),
                        entry.getAmount(),
                        entry.getCurrency(),
                        entry.getRemittanceInfo() != null ? entry.getRemittanceInfo().replace("\"", "\"\"") : "",
                        entry.getBalanceAfter() != null ? entry.getBalanceAfter() : ""
                ));
            }
        }

        return csv.toString();
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
