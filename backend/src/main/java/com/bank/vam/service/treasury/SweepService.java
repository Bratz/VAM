package com.bank.vam.service.treasury;

import com.bank.vam.dto.treasury.IhbDto;
import com.bank.vam.dto.treasury.SweepRuleDto;
import com.bank.vam.dto.treasury.SweepRunDto;
import com.bank.vam.entity.PhysicalAccount;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.treasury.ExternalMandate;
import com.bank.vam.entity.treasury.SweepExecution;
import com.bank.vam.entity.treasury.SweepRule;
import com.bank.vam.entity.treasury.SweepRuleSource;
import com.bank.vam.entity.treasury.SweepRun;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.treasury.SweepExecutionRepository;
import com.bank.vam.repository.treasury.SweepRuleRepository;
import com.bank.vam.repository.treasury.SweepRuleSourceRepository;
import com.bank.vam.repository.treasury.SweepRunRepository;
import com.bank.vam.service.TransactionService;
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
 * When funds are swept between IHB-enabled entities, the sweep posts to the source
 * entity's IHB current account and its IC Receivable/Payable mirror, which accrue interest.
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
    private final FeePostingService feePostingService;
    private final IhbFxService fxService;
    private final com.bank.vam.config.MarketProfileProperties marketProfile;
    private final PlatformTransactionManager transactionManager;
    private final SweepRunBootstrap sweepRunBootstrap;
    private final SweepRunRepository sweepRunRepository;
    private final com.bank.vam.config.HomeBankProperties homeBankProperties;
    private final com.bank.vam.repository.treasury.ExternalMandateRepository externalMandateRepository;
    private final com.bank.vam.repository.PhysicalAccountRepository physicalAccountRepository;
    private final TransactionService transactionService;
    private final ExternalSweepService externalSweeps;

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
                VirtualAccount srcVa = vaById.get(sourceReq.getAccountId());
                source.setRule(rule);
                source.setAccountId(sourceReq.getAccountId());
                // From the account, not the request: a request naming the account by id alone failed on the NOT NULL
                // account number (500), as pool members did.
                source.setAccountNumber(srcVa != null ? srcVa.getVaNumber() : sourceReq.getAccountNumber());
                source.setEntityCode(srcVa != null && srcVa.getOwningEntityCode() != null ? srcVa.getOwningEntityCode() : sourceReq.getEntityCode());
                source.setEntityName(sourceReq.getEntityName());
                source.setCurrencyCode(srcVa != null ? srcVa.getCurrencyCode() : sourceReq.getCurrencyCode());
                source.setBankName(sourceReq.getBankName());
                rule.getSourceAccounts().add(source);

                VirtualAccount va = vaById.get(sourceReq.getAccountId());
                if (va != null) {
                    hasShadowLeg |= assertShadowLegEligible(va, va.getVaNumber());
                    assertPooledOrMirrored(va, va.getVaNumber());
                    sourceVasForMirroring.add(va);
                }
            }
        }
        // Was .orElse(null): an unresolvable target silently passed rule
        // creation and only surfaced later as a masked NPE inside
        // executeTransfer ("Transfer execution failed", real cause hidden).
        // Fail loudly here instead, same as the source-account check above.
        VirtualAccount targetVa = virtualAccountRepository.findById(request.getTargetAccountId())
                .orElseThrow(() -> new BusinessException("Target account not found: " + request.getTargetAccountId()));
        rule.setTargetAccountNumber(targetVa.getVaNumber()); // from the account, not the request
        assertExternalLegsFit(sourceVasForMirroring, targetVa);
        hasShadowLeg |= assertShadowLegEligible(targetVa, targetVa.getVaNumber());
        assertCanHoldSweptCash(targetVa, targetVa.getVaNumber());
        // No assertPooledOrMirrored() on the target: a dedicated Treasury pool/root
        // account is routinely the ONLY VA on its own physical account by design —
        // that's the destination the concentration exists to fill, not a stand-in for
        // a subsidiary's account. The risk this guard targets (a leg silently backed
        // by no real shared cash) is specific to the many distinct subsidiary
        // accounts feeding IN, i.e. the sources — see assertPooledOrMirrored's javadoc.
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
     * <p><b>N+1 fix:</b> {@code executeSweep}
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
                VirtualAccount target = targetVa;
                try {
                    // Per-source REQUIRES_NEW transaction. Any failure here
                    // rolls back ONLY this source's work — its transfer and
                    // intercompany leg together — and the loop continues with
                    // a fresh transaction on the next source.
                    execution = perSourceTx.execute(status ->
                            executeSweep(rule, source, sourceVaById, target, legalEntityById));
                } catch (Exception ex) {
                    log.error("Sweep iteration failed for source {} of rule '{}': {}",
                            source.getAccountNumber(), rule.getRuleName(), ex.getMessage(), ex);
                    execution = recordFailedExecution(rule, source, "Sweep iteration failed: " + ex.getMessage());
                    // The rolled-back transfer may already have credited the in-memory target shared by every
                    // source of this rule; the next source's save would persist that. Re-read it.
                    targetVa = virtualAccountRepository.findById(rule.getTargetAccountId()).orElse(null);
                }
                executions.add(toExecutionResponse(execution));
                processedCount++;

                // SUCCESS or SETTLED: executed sweeps are saved SETTLED, so == SUCCESS counted them as skipped.
                // COMMITTED: an external source instructed, its leg pending at the other bank (R12) - executed too.
                if (execution.isSuccess() || execution.isCommitted()) {
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

    private SweepExecution newExecution(SweepRule rule, SweepRuleSource source) {
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
        return execution;
    }

    /**
     * A source whose transaction rolled back is recorded FAILED with the reason, in a fresh transaction (the
     * source's own is gone). If even that save fails, the in-memory row still goes into the response.
     */
    private SweepExecution recordFailedExecution(SweepRule rule, SweepRuleSource source, String reason) {
        SweepExecution execution = newExecution(rule, source);
        execution.setStatus(SweepExecution.ExecutionStatus.FAILED);
        execution.setSweepAmount(BigDecimal.ZERO);
        execution.setErrorMessage(reason);
        try {
            return perSourceTx.execute(status -> executionRepository.save(execution));
        } catch (Exception e) {
            log.error("Could not record failed sweep execution for {}: {}", source.getAccountNumber(), e.getMessage());
            return execution;
        }
    }

    /**
     * Why the intercompany leg of an IHB cross-entity sweep could not be posted on {@code ihbCurrent}, or null when
     * it can. Checked before any cash moves: the subsidiary's active IHB current account in the rule currency, linked
     * to treasury's IC Payable (surplus) or IC Receivable (deficit funding) mirror, which must exist and be active —
     * the same things TransactionService.postSweep*IntercompanyLeg requires.
     */
    private String intercompanyLegRefusal(VirtualAccount ihbCurrent, LegalEntity subsidiary, String currency, boolean surplus) {
        String mirror = surplus ? "IC Payable" : "IC Receivable";
        if (ihbCurrent == null) {
            return subsidiary.getEntityCode() + " has no active " + currency + " IHB current account, so the "
                + "intercompany position could not be recorded; nothing was moved";
        }
        UUID mirrorId = surplus ? ihbCurrent.getIcPayableVaId() : ihbCurrent.getIcReceivableVaId();
        VirtualAccount mirrorVa = mirrorId != null ? virtualAccountRepository.findById(mirrorId).orElse(null) : null;
        if (mirrorVa == null || mirrorVa.getStatus() != VirtualAccount.VaStatus.ACTIVE) {
            return "IHB current account " + ihbCurrent.getVaNumber() + " has no active " + mirror + " at treasury"
                + (mirrorId == null ? " linked" : " (" + mirrorId + ")") + ", so the intercompany position could not "
                + "be recorded; nothing was moved";
        }
        return null;
    }

    /**
     * Execute a single sweep from source to target account.
     *
     * Moves real cash immediately (executeTransfer), then — for cross-entity,
     * IHB-configured pairs — posts an intercompany-position mirror leg on top
     * (TransactionService.postSweepSurplusIntercompanyLeg): the subsidiary's
     * IHB Current Account balance increases and Treasury's IC Payable VA for
     * that subsidiary increases by the same amount, same shape as a COBO
     * collection.
     */
    private SweepExecution executeSweep(SweepRule rule, SweepRuleSource source,
                                         Map<UUID, VirtualAccount> sourceVaById,
                                         VirtualAccount targetVa,
                                         Map<UUID, LegalEntity> legalEntityById) {
        SweepExecution execution = newExecution(rule, source);
        // Set just before the transfer: from there on a failure must undo the whole source (see the catch below).
        boolean cashMoving = false;

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

            // An existing rule may already point at a container: creation only started rejecting
            // that in this change, and "IHB Sweep: MNC Dubai Branch -> Treasury (AED)" is live
            // with an AGGREGATION target. Fail the execution rather than move money somewhere no
            // position calculation will ever count it.
            if (targetVa != null && targetVa.getAccountCategory() != null
                    && CANNOT_RECEIVE_SWEPT_CASH.contains(targetVa.getAccountCategory())) {
                execution.setSweepAmount(BigDecimal.ZERO);
                execution.setBalanceAfter(sourceVa.getCurrentBalance());
                execution.setStatus(SweepExecution.ExecutionStatus.FAILED);
                execution.setErrorMessage("Target " + targetVa.getVaNumber() + " is a "
                    + targetVa.getAccountCategory() + " container and cannot hold swept cash");
                log.error("Sweep blocked for rule '{}': target {} is a {} container",
                    rule.getRuleName(), targetVa.getVaNumber(), targetVa.getAccountCategory());
                return executionRepository.save(execution);
            }

            // An in-house account is never swept (spec R16): its balance is the subsidiary's position with treasury,
            // not cash. Swept, the intercompany leg below credited the same amount straight back to that same IHB
            // current account, so each scheduler run swept it again, added that much to treasury's account and
            // charged the sweep fee (seen as AED 5 every 5 minutes on IHB-MNC-UAE-DUBAI-AED-0001).
            if (isInHouse(sourceVa)) {
                execution.setSweepAmount(BigDecimal.ZERO);
                execution.setBalanceAfter(sourceVa.getCurrentBalance());
                execution.setStatus(SweepExecution.ExecutionStatus.SKIPPED);
                execution.setErrorMessage(sourceVa.getVaNumber() + " is an in-house account (its balance is a position "
                    + "with treasury, not cash); it is not swept");
                return executionRepository.save(execution);
            }

            // An account at another bank (spec external-bank-sweeps R3, R8-R12): sized on its last end-of-day statement,
            // skipped with the reason when stale, pending or not covered by an authority; else instructed.
            if (sourceVa.isOtherBankShadow(homeBankProperties.getBic())) {
                ExternalSweepService.Plan plan = externalSweeps.plan(sourceVa, LocalDate.now(), b -> calculateSweepAmount(rule, b));
                BigDecimal statementBalance = plan.balance() != null ? plan.balance() : sourceVa.getCurrentBalance();
                execution.setBalanceBefore(statementBalance);
                if (plan.skip() != null) {
                    execution.setSweepAmount(BigDecimal.ZERO);
                    execution.setBalanceAfter(statementBalance);
                    execution.setStatus(SweepExecution.ExecutionStatus.SKIPPED);
                    execution.setErrorMessage(plan.skip());
                    return executionRepository.save(execution);
                }
                cashMoving = true;
                return instructExternal(rule, sourceVa, targetVa, plan, legalEntityById, execution);
            }

            // Sweep the account's own CASH, never its borrowing line.
            //
            // availableBalance is not cash on an IHB participant: IhbUnifiedService persists
            // getIhbAvailableBalance() into it, which is currentBalance + effectiveCreditLimit.
            // Sizing a sweep off that swept the facility itself — live, a ZERO_BALANCE rule moved
            // 1,000,000.96 out of an account holding 0.96 and left it at -1,000,000, because
            // available read 1,000,000.96 and canCommitOutflow then checked the amount against
            // that same inflated figure and passed. Nothing was wrong with calculateSweepAmount
            // or the guard; both were handed a number that was not money.
            //
            // The min() keeps availableBalance's real job — holds and committed outflows still
            // reduce what can be swept — while the facility can no longer inflate it. max(ZERO)
            // means an already-overdrawn participant sweeps nothing rather than borrowing more.
            //
            // Cash concentration moving borrowed money is wrong on its own terms, not just
            // arithmetically: it draws on the facility to fund the header and then charges debit
            // interest on the drawdown.
            BigDecimal availableBalance = sourceVa.getEffectiveAvailableBalance();
            BigDecimal sweepableCash = sweepableCash(sourceVa);
            execution.setBalanceBefore(sourceVa.getCurrentBalance());

            log.debug("Sweep check for {} ({}): current={}, available={}, sweepable cash={}",
                source.getAccountNumber(), source.getEntityCode(),
                sourceVa.getCurrentBalance(), availableBalance, sweepableCash);

            // Calculate sweep amount from cash, not from cash + credit limit
            BigDecimal sweepAmount = calculateSweepAmount(rule, sweepableCash);

            if (sweepAmount.compareTo(BigDecimal.ZERO) <= 0) {
                execution.setSweepAmount(BigDecimal.ZERO);
                execution.setBalanceAfter(sourceVa.getCurrentBalance());
                execution.setStatus(SweepExecution.ExecutionStatus.SKIPPED);
                execution.setErrorMessage("No funds to sweep or below threshold");
                log.debug("Sweep skipped for {} - sweepable cash: {}, calculated: {}",
                    source.getAccountNumber(), sweepableCash, sweepAmount);
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
                    // Cross-entity IHB-configured pair (same gate the old IhbDeposit creation used): the
                    // subsidiary's claim on treasury is recorded by the intercompany leg below, so its IHB
                    // current account and IC Payable link are checked BEFORE any cash moves. Treasury must
                    // never hold the subsidiary's cash with no claim recorded (R10).
                    // ponytail: cross-entity sweeps between entities that are NOT IHB-configured still move
                    // cash with no intercompany record, as before; out of scope here.
                    LegalEntity sourceEntity = sourceVa.getOwningEntityId() != null ?
                        legalEntityById.get(sourceVa.getOwningEntityId()) : null;
                    LegalEntity targetEntity = targetVa != null && targetVa.getOwningEntityId() != null ?
                        legalEntityById.get(targetVa.getOwningEntityId()) : null;
                    VirtualAccount ihbCurrentVa = null;
                    if (sourceEntity != null && targetEntity != null
                            && sourceEntity.isIhbEnabled() && targetEntity.canLend()) {
                        ihbCurrentVa = virtualAccountRepository
                            .findByOwningEntityIdAndIhbParticipantTrueAndCurrencyCode(sourceEntity.getId(), rule.getCurrencyCode())
                            .orElse(null);
                        String refusal = intercompanyLegRefusal(ihbCurrentVa, sourceEntity, rule.getCurrencyCode(), true);
                        if (refusal != null) {
                            execution.setSweepAmount(BigDecimal.ZERO);
                            execution.setBalanceAfter(sourceVa.getCurrentBalance());
                            execution.setStatus(SweepExecution.ExecutionStatus.FAILED);
                            execution.setErrorMessage(refusal);
                            log.warn("Sweep refused for {} before any cash moved: {}", source.getAccountNumber(), refusal);
                            return executionRepository.save(execution);
                        }
                    }

                    // Move the real cash immediately (previously deferred to
                    // an EOD IhbSettlementService.runSettlement() that had no
                    // caller anywhere in the codebase — sweeps only ever
                    // committed availableBalance, never actually moved
                    // currentBalance). Mirrors how deficit funding already
                    // moves cash immediately via the same executeTransfer().
                    cashMoving = true;
                    boolean transferSuccess = executeTransfer(
                        sourceVa, targetVa, sweepAmount, rule.getCurrencyCode(),
                        execution.getExecutionReference(), "Cash concentration sweep - " + rule.getRuleName()
                    ) != null;

                    if (!transferSuccess) {
                        execution.setSweepAmount(BigDecimal.ZERO);
                        execution.setBalanceAfter(sourceVa.getCurrentBalance());
                        execution.setStatus(SweepExecution.ExecutionStatus.FAILED);
                        execution.setErrorMessage("Transfer execution failed");
                    } else {
                        execution.setSweepAmount(sweepAmount);
                        execution.setBalanceAfter(sourceVa.getCurrentBalance());
                        execution.setStatus(SweepExecution.ExecutionStatus.SETTLED);

                        rule.setTotalSwept(rule.getTotalSwept().add(sweepAmount));

                        // The intercompany-position mirror on top of the real transfer above — see
                        // TransactionService.postSweepSurplusIntercompanyLeg. Not best-effort: if it throws,
                        // the catch below rethrows and the per-source transaction rolls the transfer back too.
                        if (ihbCurrentVa != null) {
                            transactionService.postSweepSurplusIntercompanyLeg(
                                ihbCurrentVa, sweepAmount, execution.getExecutionReference(), LocalDate.now());
                        }

                        // Post sweep fee; the source pays it, so its balance after shows it (R21)
                        if (postSweepFee(source.getAccountId(), execution, sweepAmount) != null) {
                            execution.setBalanceAfter(execution.getBalanceAfter().subtract(calculateSweepFee(sweepAmount)));
                        }

                        log.info("Sweep executed: {} {} from {} ({}) to {} ({})",
                            sweepAmount, rule.getCurrencyCode(),
                            source.getAccountNumber(), source.getEntityCode(),
                            rule.getTargetAccountNumber(), rule.getTargetEntityCode());
                    }
                }
            }
        } catch (Exception e) {
            // Once cash has started moving, a failure (transfer or intercompany leg) must not leave half of it
            // committed: leave through executeRules' per-source transaction, which rolls the whole source back and
            // records it FAILED.
            if (cashMoving) throw e;
            log.error("Sweep execution failed for source {}: {}", source.getAccountNumber(), e.getMessage(), e);
            execution.setSweepAmount(BigDecimal.ZERO);
            execution.setStatus(SweepExecution.ExecutionStatus.FAILED);
            execution.setErrorMessage(e.getMessage());
        }

        return executionRepository.save(execution);
    }

    /**
     * Book and instruct one external source (D4, R11-R13), inside its per-source transaction: any failure rolls the
     * whole source back. The home-bank target is credited at once (ledger, bank balance and physical account, as for
     * any home-bank shadow). The source's LEDGER is debited too, its bank balance is not (the mirror is a no-op for
     * another bank's account): the ledger shows the debit the home bank instructed, the bank balance stays the bank's
     * own figure until its next statement, which then resets both. Execution COMMITTED while the instruction is
     * pending. Both fees go to the target: the home bank cannot debit the other bank's account.
     */
    private SweepExecution instructExternal(SweepRule rule, VirtualAccount sourceVa, VirtualAccount targetVa,
                                            ExternalSweepService.Plan plan, Map<UUID, LegalEntity> legalEntityById,
                                            SweepExecution execution) {
        BigDecimal amount = plan.amount();
        LocalDate today = LocalDate.now();
        // Committed float (multi-bank v1): the bank balance still shows the amount until the other bank's statement, so
        // its effective balance leaves it out while pending; released on confirmation or failure. Saved by the transfer.
        sourceVa.setBankBalanceCommitted(Objects.requireNonNullElse(sourceVa.getBankBalanceCommitted(), BigDecimal.ZERO).add(amount));
        String targetCreditRef = executeTransfer(sourceVa, targetVa, amount, rule.getCurrencyCode(),
                execution.getExecutionReference(), "External sweep (instructed) - " + rule.getRuleName());
        execution.setSweepAmount(amount);
        execution.setBalanceAfter(plan.balance().subtract(amount));
        execution.setStatus(SweepExecution.ExecutionStatus.COMMITTED);
        rule.setTotalSwept(rule.getTotalSwept().add(amount));
        SweepExecution saved = executionRepository.save(execution);   // the fees and the instruction carry its id

        LegalEntity owner = sourceVa.getOwningEntityId() != null ? legalEntityById.get(sourceVa.getOwningEntityId()) : null;
        String ownerName = owner != null && owner.getEntityName() != null ? owner.getEntityName() : sourceVa.getVaName();
        String standardFeeRef = postSweepFee(targetVa.getId(), saved, amount);
        externalSweeps.instruct(saved, sourceVa, targetVa, ownerName, plan, today, standardFeeRef, targetCreditRef);
        log.info("External sweep instructed: {} {} from {} (other bank) to {} under authority {}", amount,
                rule.getCurrencyCode(), sourceVa.getVaNumber(), targetVa.getVaNumber(), plan.authority().getMandateReference());
        return saved;
    }

    // ========================================================================
    // DEFICIT FUNDING
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
     *
     * <p>Not {@code @Transactional}: like {@link #executeRules}, each source runs in its own
     * {@code REQUIRES_NEW} transaction, so a failed intercompany leg rolls back that source's transfer
     * only. Under one run-wide transaction it marked the whole run rollback-only.
     */
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
                SweepRuleDto.DeficitFundingResult result;
                VirtualAccount treasuryVa = targetVa;
                try {
                    result = perSourceTx.execute(status ->
                            executeDeficitFunding(rule, source, sourceVaById, treasuryVa, legalEntityById));
                } catch (Exception ex) {
                    log.error("Deficit funding failed for {}: {}", source.getAccountNumber(), ex.getMessage(), ex);
                    result = new SweepRuleDto.DeficitFundingResult();
                    result.setSourceAccountNumber(source.getAccountNumber());
                    result.setSourceEntityCode(source.getEntityCode());
                    result.setFundedAmount(BigDecimal.ZERO);
                    result.setMessage("Error (nothing moved): " + ex.getMessage());
                    // Same as executeRules: the rolled-back transfer may have debited the shared in-memory treasury VA.
                    targetVa = virtualAccountRepository.findById(rule.getTargetAccountId()).orElse(null);
                }
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
        boolean cashMoving = false;

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
            // Same as executeSweep: funding an in-house account and then debiting it the same amount by the
            // intercompany leg leaves it in deficit, so every run would fund it again out of treasury's cash.
            if (isInHouse(sourceVa)) {
                result.setMessage(sourceVa.getVaNumber() + " is an in-house account; it is not funded by cash concentration");
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

            if (!sourceEntity.canBorrow()) {
                result.setMessage(sourceEntity.getEntityCode() + " is not permitted to borrow from the IHB");
                return result;
            }

            // The subsidiary's IHB current account carries the claim (the intercompany leg below debits it), so it
            // must exist and be linked before any cash moves.
            VirtualAccount ihbCurrentVa = virtualAccountRepository
                    .findByOwningEntityIdAndIhbParticipantTrueAndCurrencyCode(sourceEntity.getId(), rule.getCurrencyCode())
                    .orElse(null);
            String refusal = intercompanyLegRefusal(ihbCurrentVa, sourceEntity, rule.getCurrencyCode(), false);
            if (refusal != null) {
                result.setMessage(refusal);
                return result;
            }

            // Borrower's credit headroom, the same rule POBO uses: IHB current balance + credit limit. The entity's
            // ihbCurrentExposure (LegalEntity.canBorrowAmount) is never updated anywhere, so it checked each top-up
            // alone; the IHB current balance falls with every top-up's intercompany leg, so this one is cumulative.
            String overLimit = com.bank.vam.service.pobo.PoboEnrolmentService.headroomRefusal(ihbCurrentVa, deficit);
            if (overLimit != null) {
                result.setMessage(overLimit);
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
            cashMoving = true;
            boolean transferSuccess = null != executeTransfer(
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

            // The intercompany-position mirror on top of the real transfer above — see
            // TransactionService.postSweepDeficitIntercompanyLeg. If it throws, the catch below rethrows and
            // runDeficitFunding's per-source transaction rolls the transfer back too.
            transactionService.postSweepDeficitIntercompanyLeg(ihbCurrentVa, deficit, executionRef, LocalDate.now());

            result.setFunded(true);
            result.setFundedAmount(deficit);
            result.setBalanceAfter(currentBalance.add(deficit));
            result.setMessage("Deficit funded via cash concentration");

            log.info("Deficit funding: {} {} from Treasury {} to {} ({})",
                    deficit, rule.getCurrencyCode(),
                    treasuryEntity.getEntityCode(),
                    sourceEntity.getEntityCode(), source.getAccountNumber());

        } catch (Exception e) {
            if (cashMoving) throw e; // undo the whole source, transfer included (see runDeficitFunding)
            log.error("Deficit funding failed for {}: {}", source.getAccountNumber(), e.getMessage(), e);
            result.setMessage("Error: " + e.getMessage());
        }

        return result;
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
        CoreBankingBalance.mirrorIfHomeBankShadow(physicalAccountRepository, homeBankProperties.getBic(), va, delta);
    }

    /**
     * Classify one leg (source or target) of a rule as shadow-eligible, throwing if
     * it's a mirror account with no valid path to real settlement.
     *
     * - PHYSICAL_MIRROR, home-bank-held: eligible via the in-process CBS mirror
     *   (see {@link #mirrorIfHomeBankShadow}) — no mandate needed, it's the bank's
     *   own account.
     * - EXTERNAL_MIRROR, or a PHYSICAL_MIRROR at another bank: eligible only with an
     *   authority ({@link ExternalMandate}) that passes {@link ExternalMandate#refusal}
     *   today — real money at another bank can't move without one. No amount at rule
     *   creation, so status and validity only; the rail scope is ignored (spec §4).
     * - Anything else (operational/aggregation/etc.): not a shadow leg — returns
     *   false, the sweep continues on today's plain ledger path, unchanged.
     *
     * @return true iff this leg is a shadow account (whether or not it required a mandate)
     */
    private boolean assertShadowLegEligible(VirtualAccount va, String accountNumberForError) {
        if (va.isPhysicalMirror() && va.isHomeBankHeld(homeBankProperties.getBic())) {
            return true;
        }
        if (va.isPhysicalMirror() || va.isExternalMirror()) {
            List<String> refusals = externalMandateRepository.findByShadowVaIdOrderByCreatedAtDesc(va.getId()).stream()
                    .map(m -> m.refusal(null, LocalDate.now(), BigDecimal.ZERO))
                    .collect(Collectors.toList());
            if (refusals.stream().noneMatch(Objects::isNull)) {
                throw new BusinessException(
                        "Account " + accountNumberForError + " is at another bank (BIC " + va.getBankSwift()
                        + "): the home bank sweeps it only under an ACTIVE authority, and "
                        + (refusals.isEmpty() ? "none is registered" : String.join("; ", refusals))
                        + ". Bank operations register one on the account, or remove this account from the rule.");
            }
            return true;
        }
        return false;
    }

    /**
     * External-bank sweeps (spec R4–R6, D1, D10): an account at another bank is only ever a source, swept into its
     * own legal entity's account at the home bank in the same currency. The home bank instructs the other bank and
     * credits its own account; it cannot instruct a credit at another bank, and cross-entity concentration goes
     * through the in-house bank. The source's authority is checked separately ({@link #assertShadowLegEligible}).
     */
    private void assertExternalLegsFit(List<VirtualAccount> sources, VirtualAccount target) {
        String homeBic = homeBankProperties.getBic();
        if (target.isOtherBankShadow(homeBic)) {
            throw new BusinessException("Target " + target.getVaNumber() + " is at another bank (BIC " + target.getBankSwift()
                    + "): external sweeps concentrate into the home bank. Pick an account held at the home bank as the target.");
        }
        for (VirtualAccount source : sources) {
            if (!source.isOtherBankShadow(homeBic)) continue;
            // ponytail: "home-bank account" = a home-bank shadow, the only target whose real balance follows the
            // credit (mirrorIfHomeBankShadow); a plain VA would take the cash on the ledger only.
            if (!target.isHomeBankHeld(homeBic)) {
                throw new BusinessException("Source " + source.getVaNumber() + " is at another bank, so the target must be"
                        + " its legal entity's own account held at the home bank; " + target.getVaNumber() + " is not an"
                        + " account held at the home bank.");
            }
            if (source.getOwningEntityId() == null || !source.getOwningEntityId().equals(target.getOwningEntityId())) {
                throw new BusinessException("Source " + source.getVaNumber() + " (" + entityLabel(source) + ") is at another"
                        + " bank and target " + target.getVaNumber() + " belongs to " + entityLabel(target) + ": a sweep from"
                        + " another bank stays within one legal entity. Cross-entity concentration goes through the in-house bank.");
            }
            if (source.getCurrencyCode() == null || !source.getCurrencyCode().equalsIgnoreCase(target.getCurrencyCode())) {
                throw new BusinessException("Source " + source.getVaNumber() + " is in " + source.getCurrencyCode() + " and target "
                        + target.getVaNumber() + " in " + target.getCurrencyCode() + ": a sweep from another bank does not"
                        + " convert currency. Pick the entity's home-bank account in " + source.getCurrencyCode() + ".");
            }
        }
    }

    private static String entityLabel(VirtualAccount va) {
        return va.getOwningEntityCode() != null ? va.getOwningEntityCode() : "no legal entity";
    }

    /**
     * Reject a SOURCE leg that models a standalone bank account as if it were pooled.
     * Not applied to the target — see the call site in {@link #createRule}.
     * PHYSICAL_MIRROR/EXTERNAL_MIRROR VAs are exempt — they ARE the 1:1 real-account
     * proxy by design, and {@link #mirrorIfHomeBankShadow} carries their movement
     * through to the real balance. Anything else (TRANSACTION/COLLECTION/etc.) only
     * moves {@code VirtualAccount.currentBalance} when swept (see {@code executeTransfer})
     * — nothing ever touches the underlying {@code PhysicalAccount}'s own balance. That's
     * correct when the VA is one of several sub-ledgers sharing a pooled physical account
     * (the pool's real cash already moved once, into that shared account), but if this VA
     * is the ONLY VA on its physical account, there's no pool — it's a distinct,
     * separately-titled account (e.g. a subsidiary's own operating account) pretending to
     * be a ledger-only leg, and a sweep on it would silently reassign ownership on paper
     * without moving any real cash.
     */
    private void assertPooledOrMirrored(VirtualAccount va, String accountNumberForError) {
        if (va.isPhysicalMirror() || va.isExternalMirror()) {
            return;
        }
        if (virtualAccountRepository.countByPhysicalAccountId(va.getPhysicalAccountId()) <= 1) {
            throw new BusinessException(
                    "Account " + accountNumberForError + " is the only VA on its physical account, so it isn't "
                    + "actually pooled — a sweep on it would move the ledger only, not real cash. Onboard it as a "
                    + "PHYSICAL_MIRROR shadow VA instead of a plain operational VA, or add it to an existing pool.");
        }
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

    /** @return the reference of the SWEEP_IN credit on the target; null when the source cannot cover the amount */
    private String executeTransfer(VirtualAccount sourceVa, VirtualAccount targetVa,
                                    BigDecimal amount, String currency,
                                    String executionReference, String description) {
        // No catch: a failure part-way (balances changed, ledger rows not written) must reach the caller, which
        // rolls the whole source back. Swallowed into "false", the half-done transfer was committed.
        BigDecimal sourceAvailable = sourceVa.getAvailableBalance() != null
            ? sourceVa.getAvailableBalance()
            : sourceVa.getCurrentBalance();
            
        // An account at another bank was sized on its statement (D6); its ledger only follows that and is not checked.
        if (!sourceVa.isOtherBankShadow(homeBankProperties.getBic())
                && (sourceAvailable == null || sourceAvailable.compareTo(amount) < 0)) {
            log.warn("Insufficient balance for sweep: available={}, required={}",
                sourceAvailable, amount);
            return null;
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
        return createSweepTransactions(
            sourceVa, targetVa,
            amount, currency,
            sourceBalanceBefore, targetBalanceBefore,
            executionReference, description
        );
    }

    /**
     * Create transaction records for sweep operation.
     */
    private String createSweepTransactions(VirtualAccount sourceVa, VirtualAccount targetVa,
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
        return sweepIn.getReferenceNumber();
    }

    // ========================================================================
    // FEE & CALCULATION METHODS
    // ========================================================================
    
    /** An IHB current account or an in-house mirror (IC receivable / payable, IHB settlement): a position, not cash. */
    public static boolean isInHouse(VirtualAccount va) {
        return va.isIhbParticipant()
            || (va.getMirrorAccountType() != null && va.getMirrorAccountType() != VirtualAccount.MirrorAccountType.NONE);
    }

    /**
     * The fee of one execution that moved money, charged to {@code chargedVaId} (the source; the target for a source at
     * another bank); nothing for a sweep of zero (the minimum fee applies only to a sweep).
     * @return the fee's debit transaction reference, null when none was posted
     */
    private String postSweepFee(UUID chargedVaId, SweepExecution execution, BigDecimal sweepAmount) {
        if (chargedVaId == null || sweepAmount == null || sweepAmount.signum() <= 0) return null;

        BigDecimal sweepFee = calculateSweepFee(sweepAmount);
        if (sweepFee.compareTo(BigDecimal.ZERO) <= 0) return null;

        try {
            FeePostingService.FeePostingResult r = feePostingService.postFee(
                chargedVaId,
                sweepFee,
                "SWEEP_EXECUTION_FEE",
                execution.getId(),
                "Cash concentration sweep fee - " + execution.getExecutionReference()
            );
            return r != null && r.getDebitTransaction() != null ? r.getDebitTransaction().getReferenceNumber() : null;
        } catch (Exception e) {
            log.error("Failed to post sweep fee for {}: {}", execution.getExecutionReference(), e.getMessage());
            return null;
        }
    }
    
    private BigDecimal calculateSweepFee(BigDecimal sweepAmount) {
        BigDecimal fee = sweepAmount.multiply(SWEEP_FEE_RATE).setScale(2, RoundingMode.HALF_UP);
        return fee.max(SWEEP_FEE_MIN).min(SWEEP_FEE_MAX);
    }

    /**
     * How much of this account is actually sweepable: its own cash, never its borrowing line.
     *
     * <p>Extracted so the rule is testable on its own — it is the whole fix for a live incident
     * where a sweep moved 1,000,000.96 out of an account holding 0.96.
     *
     * <p>{@code availableBalance} is not cash on an IHB participant: IhbUnifiedService persists
     * {@code getIhbAvailableBalance()} (currentBalance + effectiveCreditLimit) into that column.
     * Taking the min with currentBalance strips the facility back out while preserving the column's
     * real job, since holds and committed outflows push availableBalance BELOW cash and must still
     * win. max(ZERO) stops an already-overdrawn participant sweeping, which would mean borrowing
     * more to fund the header.
     */
    /**
     * Containers that cannot hold a sweep's cash. ROOT, AGGREGATION and CURRENCY_MIRROR exist to
     * restate what sits beneath them; the position logic excludes them everywhere precisely because
     * their balance is "not real standalone money".
     */
    private static final java.util.Set<VirtualAccount.AccountCategory> CANNOT_RECEIVE_SWEPT_CASH =
        java.util.EnumSet.of(VirtualAccount.AccountCategory.ROOT,
                             VirtualAccount.AccountCategory.AGGREGATION,
                             VirtualAccount.AccountCategory.CURRENCY_MIRROR);

    /**
     * A sweep's target must be an account that can actually hold the money.
     *
     * <p>Found live: "IHB Sweep: MNC Dubai Branch -> Treasury (AED)" targeted
     * FINALI-L6-AGG-U-8117, an AGGREGATION. The sweep debited a real account and credited a
     * container, and because every position calculation excludes containers, the debit counted and
     * the credit did not -- 1,000,000.96 left the firm's reported position entirely. Rule creation
     * validated that the target existed and was shadow-eligible, but never that it was somewhere
     * money could live.
     */
    private void assertCanHoldSweptCash(VirtualAccount targetVa, String accountNumber) {
        if (targetVa.getAccountCategory() != null
                && CANNOT_RECEIVE_SWEPT_CASH.contains(targetVa.getAccountCategory())) {
            throw new BusinessException(
                "Sweep target " + (accountNumber != null ? accountNumber : targetVa.getVaNumber())
                + " is a " + targetVa.getAccountCategory() + " container, which aggregates the accounts"
                + " beneath it rather than holding a balance of its own. Target a transaction or"
                + " settlement account instead.");
        }
    }

    static BigDecimal sweepableCash(VirtualAccount sourceVa) {
        BigDecimal cash = sourceVa.getCurrentBalance() != null ? sourceVa.getCurrentBalance() : BigDecimal.ZERO;
        BigDecimal available = sourceVa.getEffectiveAvailableBalance() != null
            ? sourceVa.getEffectiveAvailableBalance() : BigDecimal.ZERO;
        return cash.min(available).max(BigDecimal.ZERO);
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
        return dto;
    }
}