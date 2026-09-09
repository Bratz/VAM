package com.bank.vam.service.treasury;

import com.bank.vam.dto.treasury.IhbDto;
import com.bank.vam.dto.treasury.SweepRuleDto;
import com.bank.vam.dto.treasury.SweepRunDto;
import com.bank.vam.entity.PhysicalAccount;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.treasury.ExternalMandate;
import com.bank.vam.entity.treasury.IhbDeposit;
import com.bank.vam.entity.treasury.IhbLoan;
import com.bank.vam.entity.treasury.SweepExecution;
import com.bank.vam.entity.treasury.SweepRule;
import com.bank.vam.entity.treasury.SweepRuleSource;
import com.bank.vam.entity.treasury.SweepRun;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.treasury.IhbDepositRepository;
import com.bank.vam.repository.treasury.IhbLoanRepository;
import com.bank.vam.repository.treasury.SweepExecutionRepository;
import com.bank.vam.repository.treasury.SweepRuleRepository;
import com.bank.vam.repository.treasury.SweepRuleSourceRepository;
import com.bank.vam.repository.treasury.SweepRunRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * SweepService - Cash Concentration with IHB Integration.
 * 
 * When funds are swept between IHB-enabled entities:
 * - Source entity gets an IHB Deposit (they're lending to Treasury)
 * - Target entity (Treasury) gets an IHB Loan (they're borrowing from source)
 * 
 * This creates proper intercompany positions with interest accrual.
 * 
 * Sweep Types:
 * - ZERO_BALANCE: Sweep all funds to target
 * - TARGET_BALANCE: Maintain specific balance, sweep excess
 * - THRESHOLD: Sweep when balance exceeds maximum
 * - PERCENTAGE: Sweep a percentage of available balance
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SweepService {

    private final SweepRuleRepository ruleRepository;
    private final SweepExecutionRepository executionRepository;
    private final SweepRuleSourceRepository sourceRepository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final TransactionRepository transactionRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final IhbLoanRepository ihbLoanRepository;
    private final IhbDepositRepository ihbDepositRepository;
    private final FeePostingService feePostingService;
    private final IhbFxService fxService;
    private final com.bank.vam.config.MarketProfileProperties marketProfile;
    private final PlatformTransactionManager transactionManager;
    private final SweepRunBootstrap sweepRunBootstrap;
    private final SweepRunRepository sweepRunRepository;
    private final com.bank.vam.config.HomeBankProperties homeBankProperties;
    private final com.bank.vam.repository.treasury.ExternalMandateRepository externalMandateRepository;
    private final com.bank.vam.repository.PhysicalAccountRepository physicalAccountRepository;

    /**
     * Per-source transaction boundary used by {@link #runSweeps}. Each source's
     * {@code executeSweep} call runs in its own {@code REQUIRES_NEW} transaction
     * so a SQL failure on one source (e.g. an FK violation, a missing VA, or
     * stale state) can't leave PostgreSQL's transaction in the
     * {@code 25P02 — current transaction is aborted} state and poison every
     * subsequent source in the loop. Initialised in {@link #initTxTemplates}.
     */
    private TransactionTemplate perSourceTx;

    @PostConstruct
    void initTxTemplates() {
        TransactionTemplate t = new TransactionTemplate(transactionManager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.perSourceTx = t;
    }

    
    // Sweep fee constants
    private static final BigDecimal SWEEP_FEE_RATE = new BigDecimal("0.0005");  // 0.05%
    private static final BigDecimal SWEEP_FEE_MIN = new BigDecimal("5.00");
    private static final BigDecimal SWEEP_FEE_MAX = new BigDecimal("500.00");
    
    // Default IHB interest rates (when no InterestConfig attached)
    private static final BigDecimal DEFAULT_LENDING_RATE = new BigDecimal("5.50");   // What borrower pays
    private static final BigDecimal DEFAULT_DEPOSIT_RATE = new BigDecimal("4.50");   // What depositor earns

    // ========================================================================
    // ORPHAN CLEANUP OPERATIONS
    // ========================================================================

    /**
     * Clean up orphaned sweep rule sources (sources referencing deleted VAs).
     *
     * @return Number of orphaned sources deleted
     */
    @Transactional
    public int cleanupOrphanedSources() {
        log.info("Starting cleanup of orphaned sweep rule sources...");

        // Find orphaned sources first for logging
        List<SweepRuleSource> orphanedSources = sourceRepository.findOrphanedSources();
        if (orphanedSources.isEmpty()) {
            log.info("No orphaned sweep rule sources found");
            return 0;
        }

        // Log details before deletion
        for (SweepRuleSource source : orphanedSources) {
            log.warn("Found orphaned sweep source: {} (VA: {}) in rule {}",
                source.getAccountNumber(), source.getAccountId(),
                source.getRule() != null ? source.getRule().getRuleName() : "unknown");
        }

        // Delete orphaned sources
        int deletedCount = sourceRepository.deleteOrphanedSources();
        log.info("Cleaned up {} orphaned sweep rule sources", deletedCount);
        return deletedCount;
    }

    /**
     * Get list of orphaned sources without deleting them.
     *
     * @return List of orphaned sweep rule sources
     */
    @Transactional(readOnly = true)
    public List<SweepRuleDto.OrphanedSourceInfo> getOrphanedSources() {
        return sourceRepository.findOrphanedSources().stream()
            .map(source -> {
                SweepRuleDto.OrphanedSourceInfo info = new SweepRuleDto.OrphanedSourceInfo();
                info.setSourceId(source.getId());
                info.setAccountId(source.getAccountId());
                info.setAccountNumber(source.getAccountNumber());
                info.setEntityCode(source.getEntityCode());
                info.setEntityName(source.getEntityName());
                info.setRuleId(source.getRule() != null ? source.getRule().getId() : null);
                info.setRuleName(source.getRule() != null ? source.getRule().getRuleName() : "Unknown");
                return info;
            })
            .collect(Collectors.toList());
    }

    // ========================================================================
    // RULE OPERATIONS
    // ========================================================================

    @Transactional(readOnly = true)
    public List<SweepRuleDto.Response> getAllRules() {
        return ruleRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<SweepRuleDto.Response> getActiveRules() {
        return ruleRepository.findAllActiveWithSources().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public SweepRuleDto.Response getRuleById(UUID id) {
        SweepRule rule = ruleRepository.findByIdWithSources(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sweep rule not found: " + id));
        return toResponse(rule);
    }

    @Transactional
    public SweepRuleDto.Response createRule(SweepRuleDto.CreateRequest request) {
        SweepRule rule = new SweepRule();
        rule.setRuleReference(generateRuleReference(request.getSweepType()));
        rule.setRuleName(request.getRuleName());
        rule.setSweepType(request.getSweepType());
        rule.setTargetAmount(request.getTargetAmount());
        rule.setThresholdMin(request.getThresholdMin());
        rule.setThresholdMax(request.getThresholdMax());
        rule.setPercentage(request.getPercentage());
        rule.setTargetAccountId(request.getTargetAccountId());
        rule.setTargetAccountNumber(request.getTargetAccountNumber());
        rule.setTargetEntityCode(request.getTargetEntityCode());
        // V9: persist the owning corporate/program (was silently dropped —
        // no column existed — so Cash Concentration's corporate filter could
        // never match anything).
        rule.setCorporateId(request.getCorporateId());
        rule.setProgramId(request.getProgramId());
        rule.setFrequency(request.getFrequency());
        rule.setExecutionTime(request.getExecutionTime());
        rule.setPriority(request.getPriority() != null ? request.getPriority() : 1);
        rule.setCurrencyCode(request.getCurrencyCode() != null ? request.getCurrencyCode() : marketProfile.getDefaultCurrency());
        rule.setStatus(SweepRule.SweepStatus.ACTIVE);

        // Add source accounts — validated up front via one batch lookup
        // rather than trusting the request body blindly (was previously
        // never checked against the DB at all). The same batch also drives
        // shadow-account classification below (no extra query).
        boolean hasShadowLeg = false;
        List<VirtualAccount> sourceVasForMirroring = new ArrayList<>();
        if (request.getSourceAccounts() != null) {
            List<UUID> requestedIds = request.getSourceAccounts().stream()
                    .map(SweepRuleDto.SourceAccountRequest::getAccountId)
                    .collect(Collectors.toList());
            final Map<UUID, VirtualAccount> vaById = requestedIds.isEmpty() ? Map.of() :
                    virtualAccountRepository.findAllById(requestedIds).stream()
                            .collect(Collectors.toMap(VirtualAccount::getId, Function.identity()));
            if (!requestedIds.isEmpty()) {
                List<UUID> missingIds = requestedIds.stream()
                        .filter(id -> !vaById.containsKey(id))
                        .distinct()
                        .collect(Collectors.toList());
                if (!missingIds.isEmpty()) {
                    throw new BusinessException("Source account(s) not found: " + missingIds);
                }
            }
            for (SweepRuleDto.SourceAccountRequest sourceReq : request.getSourceAccounts()) {
                SweepRuleSource source = new SweepRuleSource();
                source.setRule(rule);
                source.setAccountId(sourceReq.getAccountId());
                source.setAccountNumber(sourceReq.getAccountNumber());
                source.setEntityCode(sourceReq.getEntityCode());
                source.setEntityName(sourceReq.getEntityName());
                source.setCurrencyCode(sourceReq.getCurrencyCode());
                source.setBankName(sourceReq.getBankName());
                rule.getSourceAccounts().add(source);

                VirtualAccount va = vaById.get(sourceReq.getAccountId());
                if (va != null) {
                    hasShadowLeg |= assertShadowLegEligible(va, sourceReq.getAccountNumber(), rule.getRail());
                    sourceVasForMirroring.add(va);
                }
            }
        }
        VirtualAccount targetVa = virtualAccountRepository.findById(request.getTargetAccountId()).orElse(null);
        if (targetVa != null) {
            hasShadowLeg |= assertShadowLegEligible(targetVa, request.getTargetAccountNumber(), rule.getRail());
        }
        // v2 multi-bank field, previously never populated (default NOTIONAL) — a rule
        // touching at least one shadow account now actually gets REAL execution wired
        // in executeRules/executeTransfer via mirrorIfHomeBankShadow, so reflect that here.
        if (hasShadowLeg) {
            rule.setExecutionMode(SweepRule.ExecutionMode.REAL);
        }

        rule = ruleRepository.save(rule);
        log.info("Created sweep rule: {} - {}", rule.getRuleReference(), rule.getRuleName());

        mirrorSweepParticipation(sourceVasForMirroring, targetVa, rule.getId());

        return toResponse(rule);
    }

    @Transactional
    public SweepRuleDto.Response updateRule(UUID id, SweepRuleDto.UpdateRequest request) {
        SweepRule rule = ruleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sweep rule not found: " + id));

        if (request.getRuleName() != null) rule.setRuleName(request.getRuleName());
        if (request.getSweepType() != null) rule.setSweepType(request.getSweepType());
        if (request.getTargetAmount() != null) rule.setTargetAmount(request.getTargetAmount());
        if (request.getThresholdMin() != null) rule.setThresholdMin(request.getThresholdMin());
        if (request.getThresholdMax() != null) rule.setThresholdMax(request.getThresholdMax());
        if (request.getPercentage() != null) rule.setPercentage(request.getPercentage());
        if (request.getFrequency() != null) rule.setFrequency(request.getFrequency());
        if (request.getExecutionTime() != null) rule.setExecutionTime(request.getExecutionTime());
        if (request.getPriority() != null) rule.setPriority(request.getPriority());

        rule = ruleRepository.save(rule);
        log.info("Updated sweep rule: {}", rule.getRuleReference());
        return toResponse(rule);
    }

    @Transactional
    public void deleteRule(UUID id) {
        SweepRule rule = ruleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sweep rule not found: " + id));

        List<UUID> involvedAccountIds = new ArrayList<>(
                rule.getSourceAccounts().stream().map(SweepRuleSource::getAccountId).collect(Collectors.toList()));
        if (rule.getTargetAccountId() != null) {
            involvedAccountIds.add(rule.getTargetAccountId());
        }

        ruleRepository.delete(rule);
        log.info("Deleted sweep rule: {}", rule.getRuleReference());

        clearSweepParticipationMirror(involvedAccountIds, id);
    }

    @Transactional
    public SweepRuleDto.Response toggleRuleStatus(UUID id) {
        SweepRule rule = ruleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sweep rule not found: " + id));

        if (rule.getStatus() == SweepRule.SweepStatus.ACTIVE) {
            rule.setStatus(SweepRule.SweepStatus.PAUSED);
        } else if (rule.getStatus() == SweepRule.SweepStatus.PAUSED) {
            rule.setStatus(SweepRule.SweepStatus.ACTIVE);
        } else {
            throw new BusinessException("Cannot toggle rule in status: " + rule.getStatus());
        }

        rule = ruleRepository.save(rule);
        log.info("Toggled sweep rule {} to status: {}", rule.getRuleReference(), rule.getStatus());
        return toResponse(rule);
    }

    // ========================================================================
    // EXECUTION OPERATIONS
    // ========================================================================

    @Transactional(readOnly = true)
    public Page<SweepRuleDto.ExecutionResponse> getExecutionHistory(Pageable pageable) {
        return executionRepository.findAllOrderByExecutionTimeDesc(pageable)
                .map(this::toExecutionResponse);
    }

    @Transactional(readOnly = true)
    public Page<SweepRuleDto.ExecutionResponse> getExecutionsByRule(UUID ruleId, Pageable pageable) {
        return executionRepository.findByRuleId(ruleId, pageable)
                .map(this::toExecutionResponse);
    }

    /**
     * Resolve the rule set for a run request, sources eagerly fetched (see
     * {@code SweepRuleRepository.findAllByIdWithSources} /
     * {@code findAllActiveWithSources}). Eagerly fetching matters here
     * specifically because {@link #runSweepsAsync} iterates the result on a
     * plain {@code sweepExecutor} thread with no HTTP request and therefore
     * no Open-Session-In-View — a lazy {@code sourceAccounts} access there
     * would throw {@code LazyInitializationException}.
     *
     * <p>Note: safety here comes from the repository's {@code JOIN FETCH},
     * not a transaction boundary on this method — every caller (sync, async,
     * and {@code createSweepRunHeader}) invokes this via plain self-invocation,
     * which Spring's proxy-based {@code @Transactional} can't intercept
     * anyway. {@code JOIN FETCH} loads {@code sourceAccounts} inside the
     * repository call itself, so the collection is already materialised by
     * the time this method returns regardless of transaction demarcation.
     */
    public List<SweepRule> resolveRulesForRun(SweepRuleDto.RunSweepsRequest request) {
        if (request.getRuleIds() != null && !request.getRuleIds().isEmpty()) {
            // Explicit rule IDs (manual "run this rule" from the UI) always
            // run regardless of frequency — the caller picked them deliberately.
            return ruleRepository.findAllByIdWithSources(request.getRuleIds()).stream()
                    .filter(r -> r.getStatus() == SweepRule.SweepStatus.ACTIVE)
                    .collect(Collectors.toList());
        }
        List<SweepRule> active = ruleRepository.findAllActiveWithSources();
        if (request.getFrequency() == null) {
            // No frequency given (the manual "Run Sweeps" button's request,
            // and any other caller not scoped to a schedule) — run everything,
            // same as before this filter existed.
            return active;
        }
        // Scoped call from ScheduledJobService: only rules configured for
        // this exact cadence. Without this, executeRealTimeSweeps() (every 5
        // minutes) and executeDailySweeps() (once at 6pm) both ran every
        // active rule unfiltered, so a rule configured DAILY actually
        // executed ~289x/day (288 from the 5-minute job + 1 from the daily
        // job) — confirmed live: cumulative "Total Swept" on individual
        // rules had inflated into the billions after weeks of uptime.
        return active.stream()
                .filter(r -> r.getFrequency() == request.getFrequency())
                .collect(Collectors.toList());
    }

    /**
     * Callback invoked after each source finishes, so the async execution
     * path can persist live progress counters for pollers. {@code null} for
     * the plain synchronous call.
     */
    private interface ProgressCallback {
        void onProgress(int sourcesProcessed, int successCount, int failedCount);
    }

    /**
     * Synchronous entry point (existing {@code POST /execute} behaviour) —
     * unchanged aggregation semantics, now delegating to {@link #executeRules}.
     */
    public SweepRuleDto.RunSweepsResponse runSweeps(SweepRuleDto.RunSweepsRequest request) {
        return executeRules(resolveRulesForRun(request), null);
    }

    /**
     * Create the {@code RUNNING} header row for an async run (Part A) in its
     * own committed transaction via {@link SweepRunBootstrap}, so a poller
     * hitting {@code GET /runs/{runId}} immediately after the {@code POST}
     * response sees the row even though the real work hasn't started yet.
     */
    @Transactional(readOnly = true)
    public SweepRun createSweepRunHeader(SweepRuleDto.RunSweepsRequest request, String createdBy) {
        List<SweepRule> rules = resolveRulesForRun(request);
        int sourcesTotal = rules.stream().mapToInt(r -> r.getSourceAccounts().size()).sum();
        return sweepRunBootstrap.createRunningRow(createdBy, request.getRuleIds(), sourcesTotal);
    }

    @Transactional(readOnly = true)
    public SweepRunDto getSweepRun(UUID runId) {
        SweepRun run = sweepRunRepository.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("No sweep run exists with id " + runId));
        return SweepRunDto.from(run);
    }

    /**
     * Background worker for {@code POST /execute-async}. MUST be called from
     * a different bean (the controller) than the one initiating it — Spring's
     * proxy-based {@code @Async} interception ignores self-invocation, same
     * caveat as {@link SweepRunBootstrap}'s {@code REQUIRES_NEW} methods.
     */
    @Async("sweepExecutor")
    public void runSweepsAsync(UUID runId, SweepRuleDto.RunSweepsRequest request) {
        try {
            List<SweepRule> rules = resolveRulesForRun(request);
            SweepRuleDto.RunSweepsResponse response = executeRules(rules,
                    (processed, success, failed) -> sweepRunBootstrap.updateProgress(runId, processed, success, failed));
            int total = response.getSuccessCount() + response.getFailedCount() + response.getSkippedCount();
            sweepRunBootstrap.markCompleted(runId, total, response.getSuccessCount(), response.getFailedCount());
            log.info("Async sweep run {} completed: {} success, {} failed, {} skipped",
                    runId, response.getSuccessCount(), response.getFailedCount(), response.getSkippedCount());
        } catch (Exception ex) {
            log.error("Async sweep run {} failed: {}", runId, ex.getMessage(), ex);
            sweepRunBootstrap.markFailed(runId, ex.getMessage());
        }
    }

    /**
     * Iterate sweep rules and execute each source under its own
     * {@code REQUIRES_NEW} transaction (see {@link #perSourceTx}). Deliberately
     * NOT annotated {@code @Transactional} at the method level: a single outer
     * transaction would let one source's SQL failure cascade into a
     * PostgreSQL {@code 25P02} state that breaks every subsequent source in
     * the loop. Per-source transaction boundaries contain the damage.
     *
     * <p><b>N+1 fix:</b> {@code executeSweep}/{@code createCommittedIhbDeposit}
     * used to re-run {@code findById} on the source VA, the target VA, and
     * both legal entities on EVERY source iteration — for a rule with 2000
     * sources that's up to ~10,000 individual lookups where the target VA and
     * both entities are identical every time. Batch-fetch once per rule
     * instead (source VAs via {@code findAllById}, the single target VA, then
     * every distinct owning-entity id via one more {@code findAllById}) and
     * pass the resulting maps into the per-source closure.
     */
    private SweepRuleDto.RunSweepsResponse executeRules(List<SweepRule> rules, ProgressCallback progressCallback) {
        int successCount = 0;
        int failedCount = 0;
        int skippedCount = 0;
        int processedCount = 0;
        BigDecimal totalSwept = BigDecimal.ZERO;
        List<SweepRuleDto.ExecutionResponse> executions = new ArrayList<>();

        for (SweepRule rule : rules) {
            // ---- batch-fetch once per rule (was: per source) ----
            List<UUID> sourceAccountIds = rule.getSourceAccounts().stream()
                    .map(SweepRuleSource::getAccountId)
                    .collect(Collectors.toList());
            Map<UUID, VirtualAccount> sourceVaById = sourceAccountIds.isEmpty() ? Map.of() :
                    virtualAccountRepository.findAllById(sourceAccountIds).stream()
                            .collect(Collectors.toMap(VirtualAccount::getId, Function.identity()));
            VirtualAccount targetVa = virtualAccountRepository.findById(rule.getTargetAccountId()).orElse(null);

            Set<UUID> entityIds = new HashSet<>();
            sourceVaById.values().forEach(va -> {
                if (va.getOwningEntityId() != null) entityIds.add(va.getOwningEntityId());
            });
            if (targetVa != null && targetVa.getOwningEntityId() != null) {
                entityIds.add(targetVa.getOwningEntityId());
            }
            Map<UUID, LegalEntity> legalEntityById = entityIds.isEmpty() ? Map.of() :
                    legalEntityRepository.findAllById(entityIds).stream()
                            .collect(Collectors.toMap(LegalEntity::getId, Function.identity()));

            for (SweepRuleSource source : rule.getSourceAccounts()) {
                SweepExecution execution;
                try {
                    // Per-source REQUIRES_NEW transaction. Any SQL failure
                    // here rolls back ONLY this source's work — the loop
                    // continues with a fresh transaction on the next source.
                    execution = perSourceTx.execute(status ->
                            executeSweep(rule, source, sourceVaById, targetVa, legalEntityById));
                } catch (Exception ex) {
                    log.error("Sweep iteration failed for source {} of rule '{}': {}",
                            source.getAccountNumber(), rule.getRuleName(), ex.getMessage(), ex);
                    // Synthesise an in-memory FAILED row for the response. We
                    // don't try to persist it — that would need yet another
                    // transaction and the caller only needs the aggregate.
                    execution = new SweepExecution();
                    execution.setRuleName(rule.getRuleName());
                    execution.setSourceAccountNumber(source.getAccountNumber());
                    execution.setStatus(SweepExecution.ExecutionStatus.FAILED);
                    execution.setSweepAmount(BigDecimal.ZERO);
                    execution.setErrorMessage("Sweep iteration failed: " + ex.getMessage());
                }
                executions.add(toExecutionResponse(execution));
                processedCount++;

                if (execution.getStatus() == SweepExecution.ExecutionStatus.SUCCESS) {
                    successCount++;
                    totalSwept = totalSwept.add(execution.getSweepAmount());
                } else if (execution.getStatus() == SweepExecution.ExecutionStatus.FAILED) {
                    failedCount++;
                } else {
                    skippedCount++;
                }

                if (progressCallback != null) {
                    progressCallback.onProgress(processedCount, successCount, failedCount);
                }
            }

            // Update rule execution tracking in its own transaction so a
            // tracking-save failure doesn't break the run aggregation.
            try {
                perSourceTx.executeWithoutResult(status -> {
                    rule.setLastExecution(LocalDateTime.now());
                    rule.setExecutionCount(rule.getExecutionCount() + 1);
                    ruleRepository.save(rule);
                });
            } catch (Exception ex) {
                log.warn("Failed to update execution tracking for rule '{}': {}",
                        rule.getRuleName(), ex.getMessage());
            }
        }

        SweepRuleDto.RunSweepsResponse response = new SweepRuleDto.RunSweepsResponse();
        response.setSuccessCount(successCount);
        response.setFailedCount(failedCount);
        response.setSkippedCount(skippedCount);
        response.setTotalSwept(totalSwept);
        response.setExecutions(executions);

        log.info("Completed sweep run: {} success, {} failed, {} skipped, total swept: {}",
                successCount, failedCount, skippedCount, totalSwept);
        return response;
    }

    /**
     * Execute a single sweep from source to target account.
     *
     * OPTION B ARCHITECTURE (Position-Based Settlement):
     * - Sweeps create IHB positions ONLY (no immediate fund movement)
     * - Funds are committed (committedOutflow on source VA)
     * - Actual settlement happens at EOD via IhbSettlementService
     * - Maturity = sweep frequency (daily=overnight, monthly=30 days, etc.)
     *
     * Benefits:
     * - Netting: Multiple sweeps net to single settlement
     * - Single source of truth: IHB position is the record
     * - True IHB model: Positions drive interest, not VA balances
     */
    private SweepExecution executeSweep(SweepRule rule, SweepRuleSource source,
                                         Map<UUID, VirtualAccount> sourceVaById,
                                         VirtualAccount targetVa,
                                         Map<UUID, LegalEntity> legalEntityById) {
        SweepExecution execution = new SweepExecution();
        execution.setExecutionReference(generateExecutionReference());
        execution.setRule(rule);
        execution.setRuleName(rule.getRuleName());
        execution.setSourceAccountId(source.getAccountId());
        execution.setSourceAccountNumber(source.getAccountNumber());
        execution.setSourceEntityCode(source.getEntityCode());
        execution.setTargetAccountId(rule.getTargetAccountId());
        execution.setTargetAccountNumber(rule.getTargetAccountNumber());
        execution.setTargetEntityCode(rule.getTargetEntityCode());
        execution.setCurrencyCode(rule.getCurrencyCode());

        try {
            // Get source VA - gracefully handle missing/deleted VAs.
            // Pre-fetched once per rule by the caller (N+1 fix) instead of a
            // fresh findById on every source.
            VirtualAccount sourceVa = sourceVaById.get(source.getAccountId());

            if (sourceVa == null) {
                // VA was deleted - mark source as orphaned and skip
                log.warn("Source VA not found for sweep rule '{}': {} ({}). VA may have been deleted. Removing orphaned source.",
                    rule.getRuleName(), source.getAccountNumber(), source.getAccountId());

                // Auto-deactivate the orphaned source
                try {
                    sourceRepository.deleteSourceById(source.getId());
                    log.info("Removed orphaned sweep rule source: {} from rule '{}'",
                        source.getAccountNumber(), rule.getRuleName());
                } catch (Exception e) {
                    log.error("Failed to remove orphaned source {}: {}", source.getId(), e.getMessage());
                }

                execution.setSweepAmount(BigDecimal.ZERO);
                execution.setStatus(SweepExecution.ExecutionStatus.SKIPPED);
                execution.setErrorMessage("Source VA deleted (ID: " + source.getAccountId() + ") - orphaned source removed from rule");
                return executionRepository.save(execution);
            }

            // Use AVAILABLE balance (accounts for existing commitments)
            BigDecimal availableBalance = sourceVa.getEffectiveAvailableBalance();
            execution.setBalanceBefore(sourceVa.getCurrentBalance());

            log.debug("Sweep check for {} ({}): current={}, available={}",
                source.getAccountNumber(), source.getEntityCode(),
                sourceVa.getCurrentBalance(), availableBalance);

            // Calculate sweep amount based on available balance
            BigDecimal sweepAmount = calculateSweepAmount(rule, availableBalance);

            if (sweepAmount.compareTo(BigDecimal.ZERO) <= 0) {
                execution.setSweepAmount(BigDecimal.ZERO);
                execution.setBalanceAfter(sourceVa.getCurrentBalance());
                execution.setStatus(SweepExecution.ExecutionStatus.SKIPPED);
                execution.setErrorMessage("No funds to sweep or below threshold");
                log.debug("Sweep skipped for {} - available: {}, calculated: {}",
                    source.getAccountNumber(), availableBalance, sweepAmount);
            } else {
                // HARD BLOCK: Verify sufficient available balance
                if (!sourceVa.canCommitOutflow(sweepAmount)) {
                    execution.setSweepAmount(BigDecimal.ZERO);
                    execution.setBalanceAfter(sourceVa.getCurrentBalance());
                    execution.setStatus(SweepExecution.ExecutionStatus.FAILED);
                    execution.setErrorMessage("Insufficient available balance for commitment");
                    log.warn("Sweep blocked for {} - insufficient available balance: {} < {}",
                        source.getAccountNumber(), availableBalance, sweepAmount);
                } else {
                    // ====================================================
                    // OPTION B: Create position + commit (NO fund movement)
                    // ====================================================

                    // 1. Commit the outflow on source VA (reduces available balance)
                    sourceVa.commitOutflow(sweepAmount);
                    virtualAccountRepository.save(sourceVa);

                    // 2. Create IHB Deposit position with COMMITTED status
                    IhbDeposit deposit = createCommittedIhbDeposit(source, rule, sweepAmount, execution,
                            sourceVa, targetVa, legalEntityById);

                    // 3. Update execution
                    execution.setSweepAmount(sweepAmount);
                    execution.setBalanceAfter(sourceVa.getCurrentBalance()); // Unchanged until settlement
                    execution.setStatus(SweepExecution.ExecutionStatus.COMMITTED);
                    execution.setIhbDepositId(deposit != null ? deposit.getId() : null);
                    execution.setIhbEnabled(deposit != null);

                    // Update rule total (committed, not yet settled)
                    rule.setTotalSwept(rule.getTotalSwept().add(sweepAmount));

                    // Post sweep fee
                    postSweepFee(source, execution, sweepAmount);

                    log.info("Sweep committed: {} {} from {} ({}) to {} ({}) - awaiting EOD settlement",
                        sweepAmount, rule.getCurrencyCode(),
                        source.getAccountNumber(), source.getEntityCode(),
                        rule.getTargetAccountNumber(), rule.getTargetEntityCode());
                }
            }
        } catch (Exception e) {
            log.error("Sweep execution failed for source {}: {}", source.getAccountNumber(), e.getMessage(), e);
            execution.setSweepAmount(BigDecimal.ZERO);
            execution.setStatus(SweepExecution.ExecutionStatus.FAILED);
            execution.setErrorMessage(e.getMessage());
        }

        return executionRepository.save(execution);
    }

    /**
     * Create a COMMITTED IHB Deposit (awaiting EOD settlement).
     *
     * Position details:
     * - Status: COMMITTED (not SETTLED until EOD)
     * - Maturity: Based on sweep frequency (daily=overnight, etc.)
     * - Interest accrues from value date (= position date by default)
     */
    private IhbDeposit createCommittedIhbDeposit(SweepRuleSource source, SweepRule rule,
                                                  BigDecimal amount, SweepExecution execution,
                                                  VirtualAccount sourceVa, VirtualAccount targetVa,
                                                  Map<UUID, LegalEntity> legalEntityById) {
        try {
            // Source/target VA and owning entities are pre-fetched once per
            // rule by the caller (N+1 fix) — this used to re-run findById on
            // the source VA (again), the target VA, and both entities on
            // EVERY source iteration of the same rule.
            if (sourceVa == null || sourceVa.getOwningEntityId() == null) {
                log.debug("Source VA has no owning entity - skipping IHB position creation");
                return null;
            }

            if (targetVa == null || targetVa.getOwningEntityId() == null) {
                log.debug("Target VA has no owning entity - skipping IHB position creation");
                return null;
            }

            LegalEntity sourceEntity = legalEntityById.get(sourceVa.getOwningEntityId());
            LegalEntity targetEntity = legalEntityById.get(targetVa.getOwningEntityId());

            if (sourceEntity == null || targetEntity == null) {
                log.debug("Could not resolve entities - skipping IHB position creation");
                return null;
            }

            // Check if both entities are IHB-enabled
            if (!sourceEntity.isIhbEnabled()) {
                log.debug("Source entity {} is not IHB-enabled - skipping IHB position", sourceEntity.getEntityCode());
                return null;
            }

            if (!targetEntity.canLend()) {
                log.debug("Target entity {} is not a Treasury Center - skipping IHB position", targetEntity.getEntityCode());
                return null;
            }

            // Determine interest rate
            BigDecimal interestRate = determineDepositRate(sourceEntity, targetEntity);

            // Calculate maturity based on sweep frequency
            LocalDate positionDate = LocalDate.now();
            LocalDate maturityDate = calculateMaturityDate(rule.getFrequency(), positionDate);

            // Create the deposit with COMMITTED status
            IhbDeposit deposit = new IhbDeposit();
            String timestamp = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            String random = String.format("%04d", (int)(Math.random() * 10000));
            deposit.setDepositReference("IHB-D-" + timestamp.substring(8) + random);

            // Entity references
            deposit.setDepositorLegalEntityId(sourceEntity.getId());
            deposit.setDepositorEntityCode(sourceEntity.getEntityCode());
            deposit.setTreasuryLegalEntityId(targetEntity.getId());
            deposit.setCorporateId(sourceEntity.getCorporateId());
            deposit.setDepositorVaId(sourceVa.getId());
            deposit.setTreasuryVaId(targetVa.getId());

            // ================================================================
            // CROSS-CURRENCY HANDLING
            // ================================================================
            String sourceCurrency = sourceVa.getCurrencyCode();
            String targetCurrency = targetVa.getCurrencyCode();
            String settlementCurrency = rule.getCurrencyCode() != null ? rule.getCurrencyCode() : targetCurrency;

            BigDecimal principalAmount = amount;
            BigDecimal originalAmount = null;
            String originalCurrency = null;
            BigDecimal fxRate = null;
            LocalDate fxRateDate = null;

            // Check for cross-currency scenario
            if (!sourceCurrency.equals(settlementCurrency)) {
                // Cross-currency deposit: convert source currency to settlement currency
                if (fxService.isCurrencyPairSupported(sourceCurrency, settlementCurrency)) {
                    IhbFxService.ConversionResult conversion = fxService.convert(
                        amount, sourceCurrency, settlementCurrency, positionDate);

                    principalAmount = conversion.convertedAmount();
                    originalAmount = conversion.originalAmount();
                    originalCurrency = conversion.originalCurrency();
                    fxRate = conversion.rateUsed().rate();
                    fxRateDate = conversion.conversionDate();

                    log.info("Cross-currency deposit: {} {} → {} {} @ {}",
                        originalAmount, originalCurrency, principalAmount, settlementCurrency, fxRate);
                } else {
                    log.warn("Unsupported currency pair {}/{} - using source currency",
                        sourceCurrency, settlementCurrency);
                    settlementCurrency = sourceCurrency;
                }
            }

            // Amounts (with cross-currency support)
            deposit.setPrincipalAmount(principalAmount);
            deposit.setCurrencyCode(settlementCurrency);
            deposit.setCurrentBalance(principalAmount);
            deposit.setInterestRate(interestRate);

            // Cross-currency fields
            deposit.setOriginalAmount(originalAmount);
            deposit.setOriginalCurrency(originalCurrency);
            deposit.setFxRate(fxRate);
            deposit.setFxRateDate(fxRateDate);

            // Dates
            deposit.setDepositDate(positionDate);
            deposit.setValueDate(positionDate);  // Interest starts from today
            deposit.setMaturityDate(maturityDate);

            // Type and status
            deposit.setDepositType(IhbDeposit.DepositType.FIXED);  // Sweep deposits have fixed maturity
            deposit.setStatus(IhbDeposit.DepositStatus.COMMITTED);  // Awaiting settlement

            // Interest
            deposit.setAccruedInterest(BigDecimal.ZERO);
            deposit.setTotalInterestEarned(BigDecimal.ZERO);

            // Sweep tracking
            deposit.setSweepExecutionReference(execution.getExecutionReference());
            deposit.setAutoCreated(true);
            deposit.setSweepRuleId(rule.getId());
            deposit.setSweepFrequency(rule.getFrequency() != null ? rule.getFrequency().name() : "DAILY");

            // Update depositor's total deposited (committed, not yet settled)
            sourceEntity.addDepositedAmount(amount);
            legalEntityRepository.save(sourceEntity);

            deposit = ihbDepositRepository.save(deposit);

            log.info("Created COMMITTED IHB deposit {} - {} {} @ {}%, matures {}",
                deposit.getDepositReference(), amount, rule.getCurrencyCode(),
                interestRate, maturityDate);

            return deposit;

        } catch (Exception e) {
            log.error("Failed to create IHB deposit for sweep {}: {}",
                execution.getExecutionReference(), e.getMessage());
            return null;
        }
    }

    /**
     * Calculate maturity date based on sweep frequency.
     * Sweep positions mature at the next sweep cycle.
     */
    private LocalDate calculateMaturityDate(SweepRule.SweepFrequency frequency, LocalDate positionDate) {
        if (frequency == null) {
            return positionDate.plusDays(1);  // Default overnight
        }
        return switch (frequency) {
            case DAILY -> positionDate.plusDays(1);
            case WEEKLY -> positionDate.plusWeeks(1);
            //case BIWEEKLY -> positionDate.plusWeeks(2);
            case MONTHLY -> positionDate.plusMonths(1);
            //case QUARTERLY -> positionDate.plusMonths(3);
            default -> positionDate.plusDays(1);
        };
    }

    // ========================================================================
    // IHB INTEGRATION
    // ========================================================================

    /**
     * Create IHB positions when sweeping between IHB-enabled entities.
     * 
     * When Entity A's funds are swept to Treasury T:
     * - Entity A gets an IHB Deposit (they're "depositing" with Treasury)
     * - This represents an intercompany receivable with interest
     * 
     * The Treasury Center (target) doesn't create a separate loan because
     * the deposit already represents the liability. The Treasury's total
     * deposits represent their borrowings from subsidiaries.
     */
    private void createIhbPositionsIfApplicable(SweepRuleSource source, SweepRule rule, 
                                                 BigDecimal amount, SweepExecution execution) {
        try {
            // Get source VA to find owning entity
            VirtualAccount sourceVa = virtualAccountRepository.findById(source.getAccountId())
                .orElse(null);
            if (sourceVa == null || sourceVa.getOwningEntityId() == null) {
                log.debug("Source VA has no owning entity - skipping IHB position creation");
                return;
            }
            
            // Get target VA to find treasury entity
            VirtualAccount targetVa = virtualAccountRepository.findById(rule.getTargetAccountId())
                .orElse(null);
            if (targetVa == null || targetVa.getOwningEntityId() == null) {
                log.debug("Target VA has no owning entity - skipping IHB position creation");
                return;
            }
            
            // Get both entities
            LegalEntity sourceEntity = legalEntityRepository.findById(sourceVa.getOwningEntityId())
                .orElse(null);
            LegalEntity targetEntity = legalEntityRepository.findById(targetVa.getOwningEntityId())
                .orElse(null);
            
            if (sourceEntity == null || targetEntity == null) {
                log.debug("Could not resolve entities - skipping IHB position creation");
                return;
            }
            
            // Check if both entities are IHB-enabled
            boolean sourceIhbEnabled = sourceEntity.isIhbEnabled();
            boolean targetIsTreasury = targetEntity.canLend(); // Treasury Center can lend
            
            if (!sourceIhbEnabled) {
                log.debug("Source entity {} is not IHB-enabled - sweep without IHB position", 
                    sourceEntity.getEntityCode());
                return;
            }
            
            if (!targetIsTreasury) {
                log.debug("Target entity {} is not a Treasury Center - sweep without IHB position", 
                    targetEntity.getEntityCode());
                return;
            }
            
            // Both entities are IHB-enabled: Create deposit for source entity
            log.info("Creating IHB deposit for sweep: {} deposits {} {} with Treasury {}", 
                sourceEntity.getEntityCode(), amount, rule.getCurrencyCode(), targetEntity.getEntityCode());
            
            IhbDeposit deposit = createIhbDepositFromSweep(
                sourceEntity, targetEntity, 
                sourceVa, targetVa,
                amount, rule.getCurrencyCode(),
                execution.getExecutionReference()
            );
            
            // Store IHB reference in execution for tracking
            execution.setIhbDepositId(deposit.getId());
            execution.setIhbEnabled(true);
            
            log.info("Created IHB deposit {} from sweep {} - {} {} @ {}%", 
                deposit.getDepositReference(), execution.getExecutionReference(),
                amount, rule.getCurrencyCode(), deposit.getInterestRate());
            
        } catch (Exception e) {
            log.error("Failed to create IHB positions for sweep {}: {}", 
                execution.getExecutionReference(), e.getMessage());
            // Don't fail the sweep, just log the error
        }
    }

    /**
     * Create an IHB Deposit when funds are swept to Treasury Center.
     * 
     * The deposit represents:
     * - From source entity's perspective: An intercompany deposit earning interest
     * - From Treasury's perspective: A liability (borrowed funds) paying interest
     */
    private IhbDeposit createIhbDepositFromSweep(LegalEntity depositor, LegalEntity treasury,
                                                  VirtualAccount depositorVa, VirtualAccount treasuryVa,
                                                  BigDecimal amount, String currency,
                                                  String sweepReference) {
        // Determine interest rate
        BigDecimal interestRate = determineDepositRate(depositor, treasury);
        
        IhbDeposit deposit = new IhbDeposit();
        // Generate unique deposit reference with timestamp
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String random = String.format("%04d", (int)(Math.random() * 10000));
        deposit.setDepositReference("IHB-D-" + timestamp.substring(8) + random); // HHmmss + random = 10 chars
        deposit.setDepositorLegalEntityId(depositor.getId());
        deposit.setDepositorEntityCode(depositor.getEntityCode());
        deposit.setTreasuryLegalEntityId(treasury.getId());
        deposit.setCorporateId(depositor.getCorporateId());
        deposit.setDepositorVaId(depositorVa.getId());
        deposit.setTreasuryVaId(treasuryVa.getId());
        deposit.setPrincipalAmount(amount);
        deposit.setCurrencyCode(currency);
        deposit.setCurrentBalance(amount);
        deposit.setInterestRate(interestRate);
        deposit.setDepositDate(LocalDate.now());
        // Sweep deposits are typically CALL (no fixed maturity)
        deposit.setDepositType(IhbDeposit.DepositType.CALL);
        deposit.setStatus(IhbDeposit.DepositStatus.ACTIVE);
        deposit.setAccruedInterest(BigDecimal.ZERO);
        deposit.setTotalInterestEarned(BigDecimal.ZERO);
        // Link to sweep execution
        deposit.setSweepExecutionReference(sweepReference);
        deposit.setAutoCreated(true);
        
        // Update depositor's total deposited
        depositor.addDepositedAmount(amount);
        legalEntityRepository.save(depositor);
        
        return ihbDepositRepository.save(deposit);
    }

    /**
     * Determine the deposit rate based on entity configurations.
     *
     * Priority:
     * 1. Treasury's InterestConfiguration (if attached)
     * 2. Entity spreads (base rate - depositor's lending spread)
     * 3. Default rate
     */
    private BigDecimal determineDepositRate(LegalEntity depositor, LegalEntity treasury) {
        // Try Treasury's Interest Configuration first
        if (treasury.getIhbInterestConfigId() != null) {
            // Would need to inject InterestConfigRepository to look this up
            // For now, use spread-based calculation
        }

        // Base rate (e.g., EIBOR at 5%)
        BigDecimal baseRate = new BigDecimal("5.00");

        // Depositor gets base rate minus their lending spread
        // (The spread is the "cost" of the intermediation)
        BigDecimal depositorSpread = depositor.getLendingRateSpread() != null ?
            depositor.getLendingRateSpread() : BigDecimal.ZERO;

        BigDecimal rate = baseRate.subtract(depositorSpread);

        // Ensure rate is not negative
        if (rate.compareTo(BigDecimal.ZERO) < 0) {
            rate = BigDecimal.ZERO;
        }

        return rate.setScale(2, RoundingMode.HALF_UP);
    }

    // ========================================================================
    // DEFICIT FUNDING (IHB LOAN CREATION)
    // ========================================================================

    /**
     * Check and fund deficit accounts from Treasury Center.
     *
     * When an entity's balance falls below target:
     * - Treasury provides funds (IHB Loan)
     * - Entity receives funding and owes interest to Treasury
     *
     * This is the reverse of sweep: instead of surplus going to Treasury,
     * Treasury lends to deficit accounts.
     */
    @Transactional
    public SweepRuleDto.DeficitFundingResponse runDeficitFunding(SweepRuleDto.DeficitFundingRequest request) {
        List<SweepRule> rules;
        if (request.getRuleIds() != null && !request.getRuleIds().isEmpty()) {
            rules = ruleRepository.findAllByIdWithSources(request.getRuleIds()).stream()
                    .filter(r -> r.getStatus() == SweepRule.SweepStatus.ACTIVE)
                    .filter(r -> r.getSweepType() == SweepRule.SweepType.TARGET_BALANCE)
                    .collect(Collectors.toList());
        } else {
            // Only TARGET_BALANCE rules support deficit funding
            rules = ruleRepository.findAllActiveWithSources().stream()
                    .filter(r -> r.getSweepType() == SweepRule.SweepType.TARGET_BALANCE)
                    .collect(Collectors.toList());
        }

        int fundedCount = 0;
        int skippedCount = 0;
        BigDecimal totalFunded = BigDecimal.ZERO;
        List<SweepRuleDto.DeficitFundingResult> results = new ArrayList<>();

        for (SweepRule rule : rules) {
            // ---- N+1 fix: same batch-fetch-once-per-rule treatment as
            // executeRules/executeSweep — was previously re-fetching the
            // source VA, target VA, and both legal entities individually on
            // every source (target VA/entity are identical across the rule).
            List<UUID> sourceAccountIds = rule.getSourceAccounts().stream()
                    .map(SweepRuleSource::getAccountId)
                    .collect(Collectors.toList());
            Map<UUID, VirtualAccount> sourceVaById = sourceAccountIds.isEmpty() ? Map.of() :
                    virtualAccountRepository.findAllById(sourceAccountIds).stream()
                            .collect(Collectors.toMap(VirtualAccount::getId, Function.identity()));
            VirtualAccount targetVa = virtualAccountRepository.findById(rule.getTargetAccountId()).orElse(null);

            Set<UUID> entityIds = new HashSet<>();
            sourceVaById.values().forEach(va -> {
                if (va.getOwningEntityId() != null) entityIds.add(va.getOwningEntityId());
            });
            if (targetVa != null && targetVa.getOwningEntityId() != null) {
                entityIds.add(targetVa.getOwningEntityId());
            }
            Map<UUID, LegalEntity> legalEntityById = entityIds.isEmpty() ? Map.of() :
                    legalEntityRepository.findAllById(entityIds).stream()
                            .collect(Collectors.toMap(LegalEntity::getId, Function.identity()));

            for (SweepRuleSource source : rule.getSourceAccounts()) {
                SweepRuleDto.DeficitFundingResult result =
                        executeDeficitFunding(rule, source, sourceVaById, targetVa, legalEntityById);
                results.add(result);

                if (result.isFunded()) {
                    fundedCount++;
                    totalFunded = totalFunded.add(result.getFundedAmount());
                } else {
                    skippedCount++;
                }
            }
        }

        SweepRuleDto.DeficitFundingResponse response = new SweepRuleDto.DeficitFundingResponse();
        response.setFundedCount(fundedCount);
        response.setSkippedCount(skippedCount);
        response.setTotalFunded(totalFunded);
        response.setResults(results);

        log.info("Completed deficit funding: {} funded, {} skipped, total: {}",
                fundedCount, skippedCount, totalFunded);
        return response;
    }

    /**
     * Execute deficit funding for a single source account.
     */
    private SweepRuleDto.DeficitFundingResult executeDeficitFunding(SweepRule rule, SweepRuleSource source,
                                                                      Map<UUID, VirtualAccount> sourceVaById,
                                                                      VirtualAccount targetVa,
                                                                      Map<UUID, LegalEntity> legalEntityById) {
        SweepRuleDto.DeficitFundingResult result = new SweepRuleDto.DeficitFundingResult();
        result.setSourceAccountNumber(source.getAccountNumber());
        result.setSourceEntityCode(source.getEntityCode());
        result.setFunded(false);
        result.setFundedAmount(BigDecimal.ZERO);

        try {
            // Only TARGET_BALANCE rules support deficit funding
            if (rule.getSweepType() != SweepRule.SweepType.TARGET_BALANCE) {
                result.setMessage("Rule type does not support deficit funding");
                return result;
            }

            // Source/target VA pre-fetched once per rule by the caller (N+1
            // fix) instead of a fresh findById per source.
            VirtualAccount sourceVa = sourceVaById.get(source.getAccountId());
            if (sourceVa == null || targetVa == null) {
                result.setMessage("Source or target VA not found");
                return result;
            }

            BigDecimal targetBalance = rule.getTargetAmount() != null ? rule.getTargetAmount() : BigDecimal.ZERO;
            BigDecimal currentBalance = effectiveBalance(sourceVa);
            result.setBalanceBefore(currentBalance);

            // Check if below target (deficit)
            BigDecimal deficit = targetBalance.subtract(currentBalance);
            if (deficit.compareTo(BigDecimal.ZERO) <= 0) {
                result.setMessage("No deficit - balance at or above target");
                result.setBalanceAfter(currentBalance);
                return result;
            }

            // Check if entities are IHB-enabled
            LegalEntity sourceEntity = sourceVa.getOwningEntityId() != null ?
                    legalEntityById.get(sourceVa.getOwningEntityId()) : null;
            LegalEntity treasuryEntity = targetVa.getOwningEntityId() != null ?
                    legalEntityById.get(targetVa.getOwningEntityId()) : null;

            if (sourceEntity == null || treasuryEntity == null) {
                result.setMessage("Cannot resolve entities for IHB");
                return result;
            }

            if (!sourceEntity.isIhbEnabled() || !treasuryEntity.canLend()) {
                result.setMessage("Entities not IHB-enabled for deficit funding");
                return result;
            }

            // Check borrower's credit limit
            if (!sourceEntity.canBorrowAmount(deficit)) {
                result.setMessage("Borrower credit limit exceeded. Available: " + sourceEntity.getAvailableIhbLimit());
                return result;
            }

            // Check Treasury has sufficient balance
            BigDecimal treasuryBalance = effectiveBalance(targetVa);
            if (treasuryBalance.compareTo(deficit) < 0) {
                result.setMessage("Treasury has insufficient funds for deficit funding");
                return result;
            }

            // Execute the reverse transfer: Treasury → Source (deficit account)
            String executionRef = generateExecutionReference();
            boolean transferSuccess = executeTransfer(
                    targetVa,   // FROM Treasury
                    sourceVa,   // TO deficit account
                    deficit,
                    rule.getCurrencyCode(),
                    executionRef,
                    "IHB deficit funding - " + rule.getRuleName()
            );

            if (!transferSuccess) {
                result.setMessage("Transfer execution failed");
                return result;
            }

            // Create IHB Loan for the borrower
            IhbLoan loan = createIhbLoanFromDeficitFunding(
                    treasuryEntity, sourceEntity,
                    targetVa, sourceVa,
                    deficit, rule.getCurrencyCode(),
                    executionRef
            );

            result.setFunded(true);
            result.setFundedAmount(deficit);
            result.setBalanceAfter(currentBalance.add(deficit));
            result.setIhbLoanId(loan.getId());
            result.setIhbLoanReference(loan.getLoanReference());
            result.setMessage("Deficit funded via IHB loan");

            log.info("Deficit funding: {} {} from Treasury {} to {} ({}). Loan: {}",
                    deficit, rule.getCurrencyCode(),
                    treasuryEntity.getEntityCode(),
                    sourceEntity.getEntityCode(), source.getAccountNumber(),
                    loan.getLoanReference());

        } catch (Exception e) {
            log.error("Deficit funding failed for {}: {}", source.getAccountNumber(), e.getMessage(), e);
            result.setMessage("Error: " + e.getMessage());
        }

        return result;
    }

    /**
     * Create an IHB Loan when Treasury provides deficit funding.
     *
     * The loan represents:
     * - From borrower's perspective: Intercompany payable with interest obligation
     * - From Treasury's perspective: Intercompany receivable earning interest
     */
    private IhbLoan createIhbLoanFromDeficitFunding(LegalEntity lender, LegalEntity borrower,
                                                     VirtualAccount lenderVa, VirtualAccount borrowerVa,
                                                     BigDecimal amount, String currency,
                                                     String fundingReference) {
        // Determine interest rate (what borrower pays)
        BigDecimal interestRate = determineLendingRate(lender, borrower);

        IhbLoan loan = new IhbLoan();
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String random = String.format("%04d", (int)(Math.random() * 10000));
        loan.setLoanReference("IHB-L-" + timestamp.substring(8) + random);
        loan.setLenderLegalEntityId(lender.getId());
        loan.setLenderEntityCode(lender.getEntityCode());
        loan.setBorrowerLegalEntityId(borrower.getId());
        loan.setBorrowerEntityCode(borrower.getEntityCode());
        loan.setCorporateId(borrower.getCorporateId());
        loan.setLenderVaId(lenderVa.getId());
        loan.setBorrowerVaId(borrowerVa.getId());
        loan.setPrincipalAmount(amount);
        loan.setCurrencyCode(currency);
        loan.setOutstandingAmount(amount);
        loan.setInterestRate(interestRate);
        loan.setInterestType(IhbLoan.InterestType.FIXED);
        loan.setDisbursementDate(LocalDate.now());
        // Deficit funding loans are typically short-term or on-demand
        loan.setMaturityDate(LocalDate.now().plusMonths(1)); // 1 month default
        loan.setRepaymentFrequency(IhbLoan.RepaymentFrequency.BULLET);
        loan.setStatus(IhbLoan.LoanStatus.ACTIVE);
        loan.setAccruedInterest(BigDecimal.ZERO);
        loan.setTotalInterestPaid(BigDecimal.ZERO);

        // Update borrower's exposure
        borrower.utilizeIhbLimit(amount);
        legalEntityRepository.save(borrower);

        // Update lender's lent amount
        lender.addLentAmount(amount);
        legalEntityRepository.save(lender);

        return ihbLoanRepository.save(loan);
    }

    /**
     * Determine the lending rate (what borrower pays).
     *
     * Priority:
     * 1. Treasury's InterestConfiguration
     * 2. Base rate + Treasury lending spread + Borrower borrowing spread
     * 3. Default rate
     */
    private BigDecimal determineLendingRate(LegalEntity lender, LegalEntity borrower) {
        // Base rate (e.g., EIBOR at 5%)
        BigDecimal baseRate = new BigDecimal("5.00");

        // Lender's spread (what Treasury charges)
        BigDecimal lenderSpread = lender.getLendingRateSpread() != null ?
                lender.getLendingRateSpread() : BigDecimal.ZERO;

        // Borrower's spread (additional risk premium)
        BigDecimal borrowerSpread = borrower.getBorrowingRateSpread() != null ?
                borrower.getBorrowingRateSpread() : BigDecimal.ZERO;

        BigDecimal rate = baseRate.add(lenderSpread).add(borrowerSpread);

        return rate.setScale(2, RoundingMode.HALF_UP);
    }

    // ========================================================================
    // BALANCE & TRANSFER OPERATIONS
    // ========================================================================

    /**
     * Available-else-current balance for a VA already in hand. Replaces the
     * old {@code getAccountBalance(UUID)} which re-fetched the VA by id —
     * the deficit-funding path now passes in the VA it already batch-fetched
     * once per rule (N+1 fix), so no repository call belongs here.
     */
    private BigDecimal effectiveBalance(VirtualAccount va) {
        BigDecimal balance = va.getAvailableBalance();
        return balance != null ? balance : (va.getCurrentBalance() != null ? va.getCurrentBalance() : BigDecimal.ZERO);
    }

    /**
     * Execute the actual fund transfer between two already-fetched accounts
     * (was: re-{@code findById} both by id — redundant, since deficit funding's
     * only caller had just fetched the same two VAs from its per-rule batch).
     */
    /**
     * If {@code va} is a home-bank-held shadow (PHYSICAL_MIRROR), mirror the same delta
     * just applied to its ledger balance into {@code bankBalance} — otherwise the
     * CBS-mirrored balance silently drifts from what the ledger says moved. No-op for
     * ordinary operational VAs and for external/other-bank shadows (those require a
     * mandated rail instruction, not a same-process ledger mirror — see ExternalMandate).
     */
    private void mirrorIfHomeBankShadow(VirtualAccount va, BigDecimal delta) {
        if (va.isPhysicalMirror() && va.isHomeBankHeld(homeBankProperties.getBic())) {
            va.mirrorBankBalance(delta);
        }
    }

    /**
     * Classify one leg (source or target) of a rule as shadow-eligible, throwing if
     * it's a mirror account with no valid path to real settlement.
     *
     * - PHYSICAL_MIRROR, home-bank-held: eligible via the in-process CBS mirror
     *   (see {@link #mirrorIfHomeBankShadow}) — no mandate needed, it's the bank's
     *   own account.
     * - EXTERNAL_MIRROR, or a PHYSICAL_MIRROR at another bank: eligible only with an
     *   ACTIVE {@link ExternalMandate} for this shadow that supports the rule's rail
     *   — real money at another bank can't move without one.
     * - Anything else (operational/aggregation/etc.): not a shadow leg — returns
     *   false, the sweep continues on today's plain ledger path, unchanged.
     *
     * @return true iff this leg is a shadow account (whether or not it required a mandate)
     */
    private boolean assertShadowLegEligible(VirtualAccount va, String accountNumberForError, SweepRule.Rail rail) {
        if (va.isPhysicalMirror() && va.isHomeBankHeld(homeBankProperties.getBic())) {
            return true;
        }
        if (va.isPhysicalMirror() || va.isExternalMirror()) {
            // rail==AUTO means "pick at execution time" (per SweepRule.Rail javadoc), so at
            // creation time any active mandate on this shadow is sufficient; a specific rail
            // must be explicitly supported by the mandate.
            boolean mandated = externalMandateRepository
                    .findByShadowVaIdAndStatus(va.getId(), ExternalMandate.MandateStatus.ACTIVE).stream()
                    .anyMatch(m -> m.isActive(LocalDate.now())
                            && (rail == SweepRule.Rail.AUTO || m.supportsRail(rail)));
            if (!mandated) {
                throw new BusinessException(
                        "Account " + accountNumberForError + " mirrors an external/non-home-bank account (BIC "
                        + va.getBankSwift() + "). Real movement requires an ACTIVE ExternalMandate for rail "
                        + rail + " — add one first, or remove this account from the rule.");
            }
            return true;
        }
        return false;
    }

    /**
     * Mirror real sweep participation onto each involved account's PhysicalAccount row
     * (if it has one). PhysicalAccountController exposes its own independent
     * sweepEnabled/sweepRuleId bookkeeping (own dashboard stat tile, own filter query
     * param) that this service never otherwise touches — without this, that display
     * drifts from the SweepRuleSource rows that are the actual source of truth.
     */
    private void mirrorSweepParticipation(List<VirtualAccount> sourceVas, VirtualAccount targetVa, UUID ruleId) {
        List<UUID> physicalAccountIds = new ArrayList<>();
        sourceVas.stream().map(VirtualAccount::getPhysicalAccountId).filter(Objects::nonNull)
                .forEach(physicalAccountIds::add);
        if (targetVa != null && targetVa.getPhysicalAccountId() != null) {
            physicalAccountIds.add(targetVa.getPhysicalAccountId());
        }
        if (physicalAccountIds.isEmpty()) {
            return;
        }
        UUID targetPhysicalAccountId = targetVa != null ? targetVa.getPhysicalAccountId() : null;
        List<PhysicalAccount> accounts = physicalAccountRepository.findAllById(physicalAccountIds.stream().distinct().collect(Collectors.toList()));
        for (PhysicalAccount pa : accounts) {
            boolean isTarget = pa.getId().equals(targetPhysicalAccountId);
            pa.markSweepParticipant(ruleId, isTarget ? PhysicalAccount.SweepRole.HEADER : PhysicalAccount.SweepRole.PARTICIPANT);
        }
        physicalAccountRepository.saveAll(accounts);
    }

    /**
     * Counterpart to {@link #mirrorSweepParticipation}, called on rule deletion. Only
     * clears an account whose sweepRuleId still points at this rule — an account already
     * reassigned to a different rule (or re-added to another one) must not be clobbered.
     */
    private void clearSweepParticipationMirror(List<UUID> accountIds, UUID ruleId) {
        if (accountIds.isEmpty()) {
            return;
        }
        List<UUID> physicalAccountIds = virtualAccountRepository.findAllById(accountIds).stream()
                .map(VirtualAccount::getPhysicalAccountId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (physicalAccountIds.isEmpty()) {
            return;
        }
        List<PhysicalAccount> accounts = physicalAccountRepository.findAllById(physicalAccountIds).stream()
                .filter(pa -> ruleId.equals(pa.getSweepRuleId()))
                .collect(Collectors.toList());
        accounts.forEach(PhysicalAccount::clearSweepParticipant);
        physicalAccountRepository.saveAll(accounts);
    }

    private boolean executeTransfer(VirtualAccount sourceVa, VirtualAccount targetVa,
                                    BigDecimal amount, String currency,
                                    String executionReference, String description) {
        try {
            BigDecimal sourceAvailable = sourceVa.getAvailableBalance() != null
                ? sourceVa.getAvailableBalance()
                : sourceVa.getCurrentBalance();
                
            if (sourceAvailable == null || sourceAvailable.compareTo(amount) < 0) {
                log.warn("Insufficient balance for sweep: available={}, required={}", 
                    sourceAvailable, amount);
                return false;
            }
            
            BigDecimal sourceBalanceBefore = sourceVa.getCurrentBalance();
            BigDecimal targetBalanceBefore = targetVa.getCurrentBalance();
            
            // Debit source
            sourceVa.setCurrentBalance(sourceVa.getCurrentBalance().subtract(amount));
            if (sourceVa.getAvailableBalance() != null) {
                sourceVa.setAvailableBalance(sourceVa.getAvailableBalance().subtract(amount));
            }
            mirrorIfHomeBankShadow(sourceVa, amount.negate());

            // Credit target
            targetVa.setCurrentBalance(targetVa.getCurrentBalance().add(amount));
            if (targetVa.getAvailableBalance() != null) {
                targetVa.setAvailableBalance(targetVa.getAvailableBalance().add(amount));
            }
            mirrorIfHomeBankShadow(targetVa, amount);

            virtualAccountRepository.save(sourceVa);
            virtualAccountRepository.save(targetVa);
            
            // Create transaction records
            createSweepTransactions(
                sourceVa, targetVa, 
                amount, currency,
                sourceBalanceBefore, targetBalanceBefore,
                executionReference, description
            );
            
            return true;
        } catch (Exception e) {
            log.error("Transfer failed: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Create transaction records for sweep operation.
     */
    private void createSweepTransactions(VirtualAccount sourceVa, VirtualAccount targetVa,
                                         BigDecimal amount, String currency,
                                         BigDecimal sourceBalanceBefore, BigDecimal targetBalanceBefore,
                                         String executionReference, String description) {
        LocalDateTime now = LocalDateTime.now();
        String correlationId = "SWP-" + UUID.randomUUID().toString().substring(0, 8);
        
        // SWEEP_OUT transaction (debit from source)
        Transaction sweepOut = Transaction.builder()
            .movementType(Transaction.MovementType.SWEEP_OUT)
            .corporateId(sourceVa.getCorporateId())
            .vaId(sourceVa.getId())
            .physicalAccountId(sourceVa.getPhysicalAccountId())
            .programId(sourceVa.getProgramId())
            .amount(amount)
            .currencyCode(currency)
            .balanceBefore(sourceBalanceBefore)
            .balanceAfter(sourceBalanceBefore.subtract(amount))
            .transactionDate(now)
            .valueDate(LocalDate.now())
            .referenceNumber(Transaction.generateReference(Transaction.MovementType.SWEEP_OUT))
            .externalReference(executionReference)
            .correlationId(correlationId)
            .counterpartyVaId(targetVa.getId())
            .description(description)
            .beneficiaryName(targetVa.getVaName())
            .beneficiaryAccount(targetVa.getVaNumber())
            .status(Transaction.TransactionStatus.COMPLETED)
            .channel("TREASURY")
            .initiatedBy("SYSTEM")
            .build();
        
        // SWEEP_IN transaction (credit to target)
        Transaction sweepIn = Transaction.builder()
            .movementType(Transaction.MovementType.SWEEP_IN)
            .corporateId(targetVa.getCorporateId())
            .vaId(targetVa.getId())
            .physicalAccountId(targetVa.getPhysicalAccountId())
            .programId(targetVa.getProgramId())
            .amount(amount)
            .currencyCode(currency)
            .balanceBefore(targetBalanceBefore)
            .balanceAfter(targetBalanceBefore.add(amount))
            .transactionDate(now)
            .valueDate(LocalDate.now())
            .referenceNumber(Transaction.generateReference(Transaction.MovementType.SWEEP_IN))
            .externalReference(executionReference)
            .correlationId(correlationId)
            .counterpartyVaId(sourceVa.getId())
            .description(description)
            .remitterName(sourceVa.getVaName())
            .remitterAccount(sourceVa.getVaNumber())
            .status(Transaction.TransactionStatus.COMPLETED)
            .channel("TREASURY")
            .initiatedBy("SYSTEM")
            .build();
        
        transactionRepository.save(sweepOut);
        transactionRepository.save(sweepIn);
        
        log.debug("Created sweep transactions: OUT={}, IN={}", 
            sweepOut.getReferenceNumber(), sweepIn.getReferenceNumber());
    }

    // ========================================================================
    // FEE & CALCULATION METHODS
    // ========================================================================
    
    private void postSweepFee(SweepRuleSource source, SweepExecution execution, BigDecimal sweepAmount) {
        if (source.getAccountId() == null) return;
        
        BigDecimal sweepFee = calculateSweepFee(sweepAmount);
        if (sweepFee.compareTo(BigDecimal.ZERO) <= 0) return;
        
        try {
            feePostingService.postFee(
                source.getAccountId(),
                sweepFee,
                "SWEEP_EXECUTION_FEE",
                execution.getId(),
                "Cash concentration sweep fee - " + execution.getExecutionReference()
            );
        } catch (Exception e) {
            log.error("Failed to post sweep fee for {}: {}", execution.getExecutionReference(), e.getMessage());
        }
    }
    
    private BigDecimal calculateSweepFee(BigDecimal sweepAmount) {
        BigDecimal fee = sweepAmount.multiply(SWEEP_FEE_RATE).setScale(2, RoundingMode.HALF_UP);
        return fee.max(SWEEP_FEE_MIN).min(SWEEP_FEE_MAX);
    }

    private BigDecimal calculateSweepAmount(SweepRule rule, BigDecimal balance) {
        if (balance == null || balance.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        
        switch (rule.getSweepType()) {
            case ZERO_BALANCE:
                return balance;
            case TARGET_BALANCE:
                BigDecimal target = rule.getTargetAmount() != null ? rule.getTargetAmount() : BigDecimal.ZERO;
                return balance.subtract(target).max(BigDecimal.ZERO);
            case THRESHOLD:
                BigDecimal thresholdMax = rule.getThresholdMax() != null ? rule.getThresholdMax() : BigDecimal.ZERO;
                BigDecimal thresholdMin = rule.getThresholdMin() != null ? rule.getThresholdMin() : BigDecimal.ZERO;
                if (balance.compareTo(thresholdMax) > 0) {
                    return balance.subtract(thresholdMin).max(BigDecimal.ZERO);
                }
                return BigDecimal.ZERO;
            case PERCENTAGE:
                BigDecimal pct = rule.getPercentage() != null ? rule.getPercentage() : BigDecimal.ZERO;
                return balance.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            default:
                return BigDecimal.ZERO;
        }
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private String generateRuleReference(SweepRule.SweepType type) {
        String prefix = switch (type) {
            case ZERO_BALANCE -> "SWP-ZB";
            case TARGET_BALANCE -> "SWP-TB";
            case THRESHOLD -> "SWP-TH";
            case PERCENTAGE -> "SWP-PC";
        };
        // Use timestamp for uniqueness
        String timestamp = String.valueOf(System.currentTimeMillis() % 1000000);
        return prefix + "-" + timestamp;
    }

    private String generateExecutionReference() {
        // Use timestamp + random to ensure uniqueness across restarts
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String random = String.format("%04d", (int)(Math.random() * 10000));
        return "EXE-" + timestamp + "-" + random;
    }

    // ========================================================================
    // DTO MAPPING
    // ========================================================================

    private SweepRuleDto.Response toResponse(SweepRule rule) {
        SweepRuleDto.Response dto = new SweepRuleDto.Response();
        dto.setId(rule.getId());
        dto.setRuleReference(rule.getRuleReference());
        dto.setRuleName(rule.getRuleName());
        dto.setSweepType(rule.getSweepType());
        dto.setTargetAmount(rule.getTargetAmount());
        dto.setThresholdMin(rule.getThresholdMin());
        dto.setThresholdMax(rule.getThresholdMax());
        dto.setPercentage(rule.getPercentage());
        dto.setTargetAccountId(rule.getTargetAccountId());
        dto.setTargetAccountNumber(rule.getTargetAccountNumber());
        dto.setTargetEntityCode(rule.getTargetEntityCode());
        dto.setCorporateId(rule.getCorporateId());
        dto.setProgramId(rule.getProgramId());
        dto.setFrequency(rule.getFrequency());
        dto.setExecutionTime(rule.getExecutionTime());
        dto.setPriority(rule.getPriority());
        dto.setStatus(rule.getStatus());
        dto.setTotalSwept(rule.getTotalSwept());
        dto.setExecutionCount(rule.getExecutionCount());
        dto.setLastExecution(rule.getLastExecution());
        dto.setNextExecution(rule.getNextExecution());
        dto.setCurrencyCode(rule.getCurrencyCode());
        dto.setCreatedAt(rule.getCreatedAt());
        dto.setUpdatedAt(rule.getUpdatedAt());

        if (rule.getSourceAccounts() != null) {
            dto.setSourceAccounts(rule.getSourceAccounts().stream()
                    .map(this::toSourceDto)
                    .collect(Collectors.toList()));
        }

        return dto;
    }

    private SweepRuleDto.SourceAccountDto toSourceDto(SweepRuleSource source) {
        SweepRuleDto.SourceAccountDto dto = new SweepRuleDto.SourceAccountDto();
        dto.setId(source.getId());
        dto.setAccountId(source.getAccountId());
        dto.setAccountNumber(source.getAccountNumber());
        dto.setEntityCode(source.getEntityCode());
        dto.setEntityName(source.getEntityName());
        dto.setCurrencyCode(source.getCurrencyCode());
        dto.setBankName(source.getBankName());
        return dto;
    }

    private SweepRuleDto.ExecutionResponse toExecutionResponse(SweepExecution exec) {
        SweepRuleDto.ExecutionResponse dto = new SweepRuleDto.ExecutionResponse();
        dto.setId(exec.getId());
        dto.setExecutionReference(exec.getExecutionReference());
        dto.setRuleId(exec.getRule().getId());
        dto.setRuleName(exec.getRuleName());
        dto.setSourceAccountNumber(exec.getSourceAccountNumber());
        dto.setSourceEntityCode(exec.getSourceEntityCode());
        dto.setTargetAccountNumber(exec.getTargetAccountNumber());
        dto.setTargetEntityCode(exec.getTargetEntityCode());
        dto.setSweepAmount(exec.getSweepAmount());
        dto.setCurrencyCode(exec.getCurrencyCode());
        dto.setBalanceBefore(exec.getBalanceBefore());
        dto.setBalanceAfter(exec.getBalanceAfter());
        dto.setStatus(exec.getStatus().name());
        dto.setErrorMessage(exec.getErrorMessage());
        dto.setExecutionTime(exec.getExecutionTime());
        dto.setCompletedAt(exec.getCompletedAt());
        // IHB fields
        dto.setIhbEnabled(exec.getIhbEnabled());
        dto.setIhbDepositId(exec.getIhbDepositId());
        return dto;
    }
}