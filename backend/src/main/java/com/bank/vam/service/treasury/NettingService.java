package com.bank.vam.service.treasury;

import com.bank.vam.dto.treasury.NettingDto;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.payables.Payable;
import com.bank.vam.entity.pobo.IntercompanyRecharge;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.entity.treasury.*;
import com.bank.vam.entity.treasury.NettingEntry.FlowDirection;
import com.bank.vam.entity.treasury.NettingEntry.SourceType;
import com.bank.vam.entity.intercompany.IntercompanyTransaction;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.intercompany.IntercompanyTransactionRepository;
import com.bank.vam.repository.payables.PayableRepository;
import com.bank.vam.repository.pobo.IntercompanyRechargeRepository;
import com.bank.vam.repository.receivables.ReceivableRepository;
import com.bank.vam.repository.treasury.NettingCycleRepository;
import com.bank.vam.repository.treasury.NettingEntryRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import com.bank.vam.iso20022.dto.Iso20022PaymentDto.OutwardPaymentRequest;
import com.bank.vam.iso20022.dto.Iso20022PaymentDto.OutwardPaymentResponse;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * NettingService - Phase 4 Enhanced for Unified Netting Engine
 * 
 * PHASE 4 CAPABILITIES:
 * - Unified population from Payables, Receivables, POBO Recharges, IHB
 * - Bidirectional netting (payables vs receivables)
 * - Automatic source document status updates post-settlement
 * - Multi-currency support with FX conversion
 * 
 * @see <a href="https://www.capgemini.com/wp-content/uploads/2020/12/Virtual-Acc-Management-in-Transaction-Banking.pdf">
 *      Capgemini VAM Reference - Intercompany Netting</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NettingService {

    private final NettingCycleRepository cycleRepository;
    private final NettingEntryRepository entryRepository;
    private final PayableRepository payableRepository;
    private final ReceivableRepository receivableRepository;
    private final IntercompanyRechargeRepository rechargeRepository;
    private final IntercompanyTransactionRepository transactionRepository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final TransactionRepository ledgerTransactionRepository;
    private final FeePostingService feePostingService;
    private final com.bank.vam.config.MarketProfileProperties marketProfile;
    private final FxRateService fxRateService;
    private final IhbMirrorService ihbMirrors;
    private final com.bank.vam.repository.hierarchy.LegalEntityRepository legalEntityRepository;
    private final com.bank.vam.service.TransactionService transactionService;
    private final com.bank.vam.iso20022.service.Iso20022OutwardPaymentService outwardPayments;
    private final com.bank.vam.repository.PhysicalAccountRepository physicalAccountRepository;
    private final com.bank.vam.config.HomeBankProperties homeBank;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;

    private static final AtomicInteger cycleSequence = new AtomicInteger(1);
    private static final AtomicInteger entrySequence = new AtomicInteger(1);
    private static final AtomicLong settlementLedgerSequence = new AtomicLong(1);

    // Fee constants
    private static final BigDecimal NETTING_PARTICIPATION_FEE = new BigDecimal("50.00");
    private static final BigDecimal NETTING_EXECUTION_FEE = new BigDecimal("100.00");

    /**
     * Initialize sequence counters from existing database records to avoid duplicate key errors.
     */
    @PostConstruct
    public void initializeSequences() {
        try {
            // Initialize cycle sequence from max existing cycle reference
            List<NettingCycle> allCycles = cycleRepository.findAll();
            int maxCycleSeq = allCycles.stream()
                .map(NettingCycle::getCycleReference)
                .filter(ref -> ref != null && ref.startsWith("NET-"))
                .map(ref -> {
                    try {
                        // Extract sequence from format "NET-YYYYMM-NNN"
                        String[] parts = ref.split("-");
                        if (parts.length >= 3) {
                            return Integer.parseInt(parts[2]);
                        }
                    } catch (NumberFormatException e) {
                        // Ignore parsing errors
                    }
                    return 0;
                })
                .max(Integer::compareTo)
                .orElse(0);
            cycleSequence.set(maxCycleSeq + 1);

            // Initialize entry sequence from max existing entry reference
            List<NettingEntry> allEntries = entryRepository.findAll();
            int maxEntrySeq = allEntries.stream()
                .map(NettingEntry::getEntryReference)
                .filter(ref -> ref != null && ref.startsWith("ENT-"))
                .map(ref -> {
                    try {
                        return Integer.parseInt(ref.substring(4));
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                })
                .max(Integer::compareTo)
                .orElse(0);
            entrySequence.set(maxEntrySeq + 1);

            log.info("Initialized netting sequences: cycleSequence={}, entrySequence={}",
                cycleSequence.get(), entrySequence.get());
        } catch (Exception e) {
            log.warn("Failed to initialize netting sequences from database, using defaults: {}", e.getMessage());
        }
    }

    // ========================================================================
    // EXISTING METHODS (Preserved with enhancements)
    // ========================================================================

    /** The corporate's cycles (spec R7): a cycle with no corporate (V61 left it null) is no one's. */
    @Transactional(readOnly = true)
    public List<NettingDto.CycleResponse> getAllCycles(UUID corporateId) {
        return cycleRepository.findByCorporateIdOrderByCreatedAtDesc(corporateId).stream()
                .map(this::toCycleResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public NettingDto.CycleResponse getCycleById(UUID id) {
        NettingCycle cycle = cycleRepository.findByIdWithEntries(id)
                .orElseThrow(() -> new ResourceNotFoundException("Netting cycle not found: " + id));
        return toCycleResponse(cycle);
    }

    @Transactional
    public NettingDto.CycleResponse createCycle(NettingDto.CreateCycleRequest request) {
        if (request.getCorporateId() == null) {
            throw new BusinessException("corporateId is required: a netting cycle nets one corporate's entities");
        }
        NettingCycle cycle = new NettingCycle();
        cycle.setCorporateId(request.getCorporateId());
        String yearMonth = request.getPeriodStart().format(DateTimeFormatter.ofPattern("yyyyMM"));
        cycle.setCycleReference("NET-" + yearMonth + "-" + String.format("%03d", cycleSequence.getAndIncrement()));
        cycle.setCycleName(request.getCycleName());
        cycle.setPeriodStart(request.getPeriodStart());
        cycle.setPeriodEnd(request.getPeriodEnd());
        cycle.setSettlementDate(request.getSettlementDate());
        cycle.setBaseCurrency(request.getBaseCurrency() != null ? request.getBaseCurrency() : marketProfile.getDefaultCurrency());
        cycle.setStatus(NettingCycle.CycleStatus.DRAFT);

        cycle = cycleRepository.save(cycle);
        log.info("Created netting cycle: {} - {}", cycle.getCycleReference(), cycle.getCycleName());
        return toCycleResponse(cycle);
    }

    @Transactional
    public NettingDto.CycleResponse addEntry(UUID cycleId, NettingDto.AddEntryRequest request) {
        NettingCycle cycle = cycleRepository.findByIdWithEntries(cycleId)
                .orElseThrow(() -> new ResourceNotFoundException("Netting cycle not found: " + cycleId));

        if (cycle.getStatus() != NettingCycle.CycleStatus.DRAFT && 
            cycle.getStatus() != NettingCycle.CycleStatus.OPEN) {
            throw new BusinessException("Cannot add entries to cycle in status: " + cycle.getStatus());
        }

        NettingEntry entry = new NettingEntry();
        entry.setCycle(cycle);
        entry.setEntryReference("ENT-" + String.format("%06d", entrySequence.getAndIncrement()));
        entry.setFlowDirection(request.getFlowDirection() != null ? 
            request.getFlowDirection() : FlowDirection.PAYABLE);
        entry.setPayerEntityId(request.getPayerEntityId());
        entry.setPayerEntityCode(request.getPayerEntityCode());
        entry.setPayerEntityName(request.getPayerEntityName());
        entry.setPayeeEntityId(request.getPayeeEntityId());
        entry.setPayeeEntityCode(request.getPayeeEntityCode());
        entry.setPayeeEntityName(request.getPayeeEntityName());
        entry.setGrossAmount(request.getGrossAmount());
        entry.setCurrencyCode(request.getCurrencyCode());
        entry.setExchangeRate(request.getExchangeRate() != null ? request.getExchangeRate() : BigDecimal.ONE);
        entry.setBaseAmount(request.getGrossAmount().multiply(entry.getExchangeRate()));
        entry.setOriginalCurrency(request.getCurrencyCode());
        entry.setOriginalAmount(request.getGrossAmount());
        entry.setSourceType(request.getSourceType());
        entry.setSourceReference(request.getSourceReference());
        entry.setDueDate(request.getDueDate());
        entry.setStatus(NettingEntry.EntryStatus.PENDING);

        cycle.getEntries().add(entry);
        cycle.setEntryCount(cycle.getEntries().size());
        cycle.setTotalGross(cycle.getTotalGross().add(entry.getBaseAmount()));

        cycle = cycleRepository.save(cycle);
        log.info("Added entry {} to cycle {}", entry.getEntryReference(), cycle.getCycleReference());
        return toCycleResponse(cycle);
    }

    // ========================================================================
    // PHASE 4: UNIFIED POPULATION METHODS (NEW)
    // ========================================================================
    
    /**
     * Phase 4 Task 4.5: Populate cycle with all intercompany obligations.
     * 
     * This method automatically loads:
     * - Intercompany Payables (netting-eligible, approved)
     * - Intercompany Receivables (netting-eligible, open)
     * - POBO Recharges (from IntercompanyTransaction)
     *
     * @param cycleId The netting cycle to populate
     * @param corporateId Corporate ID to filter obligations
     * @param includePending If true, include PENDING recharges (for testing)
     * @return PopulationResult with counts of added entries
     */
    @Transactional
    public PopulationResult populateCycleWithAllObligations(UUID cycleId, UUID corporateId, boolean includePending) {
        NettingCycle cycle = cycleRepository.findByIdWithEntries(cycleId)
                .orElseThrow(() -> new ResourceNotFoundException("Netting cycle not found: " + cycleId));

        if (cycle.getStatus() != NettingCycle.CycleStatus.DRAFT &&
            cycle.getStatus() != NettingCycle.CycleStatus.OPEN) {
            throw new BusinessException("Cannot populate cycle in status: " + cycle.getStatus());
        }
        // The cycle's own corporate decides what it takes; a caller naming another is refused, not obeyed.
        if (cycle.getCorporateId() == null) {
            throw new BusinessException("Netting cycle " + cycle.getCycleReference() + " belongs to no corporate");
        }
        if (corporateId != null && !corporateId.equals(cycle.getCorporateId())) {
            throw new BusinessException("Netting cycle " + cycle.getCycleReference() + " is another corporate's");
        }
        corporateId = cycle.getCorporateId();

        log.info("Populating netting cycle {} with all obligations for corporate {} (includePending={})",
            cycle.getCycleReference(), corporateId, includePending);

        int payablesAdded = addIntercompanyPayables(cycle, corporateId);
        int receivablesAdded = addIntercompanyReceivables(cycle, corporateId);

        // Add POBO recharges (PAYABLE direction - subsidiary owes treasury)
        int poboRechargesAdded = addPoboRecharges(cycle, corporateId, includePending);

        // Add COBO recharges (RECEIVABLE direction - treasury owes subsidiary) - NEW
        int coboRechargesAdded = addCoboRecharges(cycle, corporateId, includePending);

        // Update cycle stats
        cycle.setEntryCount(cycle.getEntries().size());
        cycleRepository.save(cycle);

        PopulationResult result = new PopulationResult(payablesAdded, receivablesAdded, poboRechargesAdded, coboRechargesAdded);
        log.info("Populated cycle {}: {} payables, {} receivables, {} POBO recharges, {} COBO recharges",
            cycle.getCycleReference(), payablesAdded, receivablesAdded, poboRechargesAdded, coboRechargesAdded);

        return result;
    }
    
    /**
     * Phase 4 Task 4.6: Add intercompany payables to netting cycle.
     * 
     * Finds all payables that are:
     * - Intercompany (isIntercompany = true)
     * - Netting eligible
     * - Approved status
     * - Not already in a netting cycle
     * 
     * @param cycle The netting cycle
     * @param corporateId Corporate ID filter
     * @return Number of payables added
     */
    @Transactional
    public int addIntercompanyPayables(NettingCycle cycle, UUID corporateId) {
        List<Payable> eligiblePayables = payableRepository.findNettingEligiblePayables(corporateId);
        Set<UUID> group = groupOf(corporateId);
        List<Receivable> icReceivables = receivableRepository.findIntercompanyReceivablesByCorporate(corporateId);

        int addedCount = 0;
        for (Payable payable : eligiblePayables) {
            // Between two of the corporate's entities (a payable with no counterparty entity is no IC invoice), and each
            // invoice once: a payable linked to one of the group's IC receivables is netted as that receivable.
            if (!payable.canAddToNetting() || !between(group, payable.getOwningEntityId(), payable.getCounterpartyEntityId())
                    || com.bank.vam.service.intercompany.IntercompanyGroupPositionService.countedAsReceivable(payable, icReceivables)) {
                continue;
            }

            // Create netting entry from payable
            NettingEntry entry = NettingEntry.fromIntercompanyPayable(
                cycle,
                payable.getId(),
                payable.getOwningEntityId(),
                payable.getOwningEntityCode(),
                payable.getOwningEntityName(),
                payable.getCounterpartyEntityId(),
                payable.getCounterpartyEntityCode(),
                payable.getCounterpartyEntityName(),
                owed(payable.getOutstandingAmount(), payable.getNetAmount()),
                payable.getCurrencyCode(),
                payable.getDueDate(),
                payable.getPayableNumber()
            );
            entry.setEntryReference("ENT-" + String.format("%06d", entrySequence.getAndIncrement()));
            
            // Apply FX if needed
            applyFxConversion(entry, cycle.getBaseCurrency());
            
            cycle.getEntries().add(entry);
            cycle.setTotalGross(cycle.getTotalGross().add(entry.getBaseAmount()));
            
            // Update payable with netting reference
            payable.addToNettingCycle(cycle.getId(), cycle.getCycleReference(), entry.getId());
            payableRepository.save(payable);
            
            addedCount++;
            log.debug("Added payable {} to netting cycle {}",
                payable.getPayableNumber(), cycle.getCycleReference());
        }

        return addedCount;
    }

    /**
     * Adds one explicitly-chosen payable to an existing netting cycle,
     * creating a real, persisted {@link NettingEntry} — the single canonical
     * path for this operation. Callers must not fabricate a placeholder
     * entry id and link it onto the payable themselves; that leaves
     * {@code Payable.nettingEntryId} pointing at a row that was never saved.
     *
     * <p>Distinct from {@link #addIntercompanyPayables(NettingCycle, UUID)}:
     * that method scans for every eligible payable for a corporate (a bulk
     * auto-populate run, persisted once by its caller at the end of the
     * scan); this method adds exactly the one payable the caller already
     * chose, so it persists the entry and cycle immediately itself — safe to
     * call standalone, one payable at a time, from outside that bulk flow.
     */
    @Transactional
    public NettingEntry addPayableToCycle(UUID nettingCycleId, Payable payable) {
        NettingCycle cycle = cycleRepository.findById(nettingCycleId)
            .orElseThrow(() -> new IllegalArgumentException("Netting cycle not found: " + nettingCycleId));

        if (!payable.canAddToNetting()) {
            throw new IllegalStateException("Payable " + payable.getPayableNumber() + " is not eligible for netting");
        }
        requireSameCorporate(cycle, payable.getCorporateId(), payable.getPayableNumber());

        NettingEntry entry = NettingEntry.fromIntercompanyPayable(
            cycle,
            payable.getId(),
            payable.getOwningEntityId(),
            payable.getOwningEntityCode(),
            payable.getOwningEntityName(),
            payable.getCounterpartyEntityId(),
            payable.getCounterpartyEntityCode(),
            payable.getCounterpartyEntityName(),
            owed(payable.getOutstandingAmount(), payable.getNetAmount()),
            payable.getCurrencyCode(),
            payable.getDueDate(),
            payable.getPayableNumber()
        );
        entry.setEntryReference("ENT-" + String.format("%06d", entrySequence.getAndIncrement()));
        applyFxConversion(entry, cycle.getBaseCurrency());

        cycle.getEntries().add(entry);
        cycle.setTotalGross(cycle.getTotalGross().add(entry.getBaseAmount()));
        entryRepository.save(entry);
        cycleRepository.save(cycle);

        payable.addToNettingCycle(cycle.getId(), cycle.getCycleReference(), entry.getId());
        payableRepository.save(payable);

        log.debug("Added payable {} to netting cycle {} (entry {})",
            payable.getPayableNumber(), cycle.getCycleReference(), entry.getEntryReference());
        return entry;
    }

    /**
     * Adds one explicitly-chosen intercompany transaction to an existing
     * netting cycle, creating a real, persisted {@link NettingEntry} on the
     * same {@code ENT-%06d} reference scheme as every other entry point.
     *
     * <p>Replaces {@code IntercompanyTransactionService}'s own entry-building,
     * which used a separate {@code NE-IC-} reference scheme and never added
     * the entry to the cycle's own collection or updated its total, so
     * {@code cycle.totalGross} silently missed anything routed through it.
     */
    @Transactional
    public NettingEntry addTransactionToCycle(UUID nettingCycleId, IntercompanyTransaction transaction) {
        // The IC records TransactionService writes for a POBO posting (originalReferenceType POBO_4LEG_* / POBO_6LEG_*)
        // describe a payment already on the subsidiary's IHB current account: never netted (POBO spec R11).
        if (transaction.getOriginalReferenceType() != null && transaction.getOriginalReferenceType().startsWith("POBO_")) {
            throw new BusinessException("Intercompany record " + transaction.getTransactionRef()
                + " is a POBO payment already posted to the In-House Bank position; it is not netted");
        }
        NettingCycle cycle = cycleRepository.findById(nettingCycleId)
            .orElseThrow(() -> new ResourceNotFoundException("Netting cycle not found: " + nettingCycleId));

        if (!cycle.acceptsEntries()) {
            throw new BusinessException("Netting cycle is not open for additions");
        }

        NettingEntry entry = NettingEntry.builder()
            .cycle(cycle)
            .entryReference("ENT-" + String.format("%06d", entrySequence.getAndIncrement()))
            .flowDirection(FlowDirection.PAYABLE)
            .payerEntityId(transaction.getBehalfEntityId())
            .payerEntityCode(transaction.getBehalfEntityCode())
            .payerEntityName(transaction.getBehalfEntityName())
            .payeeEntityId(transaction.getPayingEntityId())
            .payeeEntityCode(transaction.getPayingEntityCode())
            .payeeEntityName(transaction.getPayingEntityName())
            .sourceType(SourceType.INTERCOMPANY_PAYABLE)
            .sourceReference(transaction.getTransactionRef())
            .grossAmount(transaction.getNetAmount())
            .currencyCode(transaction.getCurrencyCode())
            .exchangeRate(BigDecimal.ONE)
            .baseAmount(transaction.getNetAmount())
            .originalCurrency(transaction.getCurrencyCode())
            .originalAmount(transaction.getNetAmount())
            .status(NettingEntry.EntryStatus.PENDING)
            .build();

        applyFxConversion(entry, cycle.getBaseCurrency());
        cycle.getEntries().add(entry);
        cycle.setTotalGross(cycle.getTotalGross().add(entry.getBaseAmount()));
        entryRepository.save(entry);
        cycleRepository.save(cycle);

        log.debug("Added IC transaction {} to netting cycle {} (entry {})",
            transaction.getTransactionRef(), cycle.getCycleReference(), entry.getEntryReference());
        return entry;
    }

    /**
     * Adds one explicitly-chosen intercompany recharge to an existing
     * netting cycle, creating a real, persisted {@link NettingEntry} the
     * same way {@link #addPoboRecharges(NettingCycle, UUID, boolean)} does
     * for its bulk scan. Replaces {@code IntercompanyTransactionService}'s
     * own duplicate entry-building for recharges (see
     * {@link #addTransactionToCycle(UUID, IntercompanyTransaction)} javadoc
     * for what was wrong with it).
     */
    @Transactional
    public NettingEntry addRechargeToCycle(UUID nettingCycleId, IntercompanyRecharge recharge) {
        if (recharge.getRechargeType() == IntercompanyRecharge.RechargeType.POBO_PAYMENT) {
            throw new BusinessException("Recharge " + recharge.getRechargeReference()
                + " is a POBO payment already posted to the In-House Bank position; it is not netted");
        }
        NettingCycle cycle = cycleRepository.findById(nettingCycleId)
            .orElseThrow(() -> new ResourceNotFoundException("Netting cycle not found: " + nettingCycleId));

        if (!cycle.acceptsEntries()) {
            throw new BusinessException("Netting cycle is not open for additions");
        }

        NettingEntry entry = NettingEntry.builder()
            .cycle(cycle)
            .entryReference("ENT-" + String.format("%06d", entrySequence.getAndIncrement()))
            .flowDirection(FlowDirection.PAYABLE)
            .payerEntityId(recharge.getBehalfEntityId())
            .payerEntityCode(recharge.getBehalfEntityCode())
            .payerEntityName(recharge.getBehalfEntityName())
            .payeeEntityId(recharge.getPayerEntityId())
            .payeeEntityCode(recharge.getPayerEntityCode())
            .payeeEntityName(recharge.getPayerEntityName())
            .sourceType(SourceType.POBO_RECHARGE)
            .sourceReference(recharge.getRechargeReference())
            .intercompanyRechargeId(recharge.getId())
            .grossAmount(recharge.getTotalRecharge())
            .currencyCode(recharge.getCurrencyCode())
            .exchangeRate(BigDecimal.ONE)
            .baseAmount(recharge.getTotalRecharge())
            .originalCurrency(recharge.getCurrencyCode())
            .originalAmount(recharge.getTotalRecharge())
            .dueDate(recharge.getSettlementDate())
            .status(NettingEntry.EntryStatus.PENDING)
            .build();

        applyFxConversion(entry, cycle.getBaseCurrency());
        cycle.getEntries().add(entry);
        cycle.setTotalGross(cycle.getTotalGross().add(entry.getBaseAmount()));
        entryRepository.save(entry);
        cycleRepository.save(cycle);

        log.debug("Added recharge {} to netting cycle {} (entry {})",
            recharge.getRechargeReference(), cycle.getCycleReference(), entry.getEntryReference());
        return entry;
    }

    /**
     * Adds one explicitly-chosen intercompany receivable to a cycle, as {@link #addPayableToCycle} does for a payable and
     * {@link #addIntercompanyReceivables} for the bulk scan: the debtor (its intercompany entity) pays the owner.
     */
    @Transactional
    public NettingEntry addReceivableToCycle(UUID nettingCycleId, Receivable receivable) {
        NettingCycle cycle = cycleRepository.findById(nettingCycleId)
            .orElseThrow(() -> new ResourceNotFoundException("Netting cycle not found: " + nettingCycleId));
        if (!cycle.acceptsEntries()) {
            throw new BusinessException("Netting cycle is not open for additions");
        }
        if (!receivable.canAddToNetting()) {
            throw new BusinessException("Receivable " + receivable.getReceivableNumber() + " is not eligible for netting");
        }
        requireSameCorporate(cycle, receivable.getCorporateId(), receivable.getReceivableNumber());

        NettingEntry entry = NettingEntry.fromIntercompanyReceivable(
            cycle,
            receivable.getId(),
            receivable.getOwningEntityId(),
            receivable.getOwningEntityCode(),
            receivable.getOwningEntityName(),
            receivable.getIntercompanyEntityId(),
            receivable.getIntercompanyEntityCode(),
            receivable.getIntercompanyEntityName(),
            owed(receivable.getOutstandingAmount(), receivable.getNetAmount()),
            receivable.getCurrencyCode(),
            receivable.getDueDate(),
            receivable.getReceivableNumber()
        );
        entry.setEntryReference("ENT-" + String.format("%06d", entrySequence.getAndIncrement()));
        applyFxConversion(entry, cycle.getBaseCurrency());

        cycle.getEntries().add(entry);
        cycle.setTotalGross(cycle.getTotalGross().add(entry.getBaseAmount()));
        entryRepository.save(entry);
        cycleRepository.save(cycle);

        receivable.addToNettingCycle(cycle.getId(), cycle.getCycleReference(), entry.getId());
        receivableRepository.save(receivable);
        return entry;
    }

    /**
     * The cycle's entries whose source was already settled outside netting, as "ENT-… (source reference)": a recharge
     * SETTLED or carrying a settlement reference (e.g. the IHB cut-over's), a payable or receivable PAID or NETTED.
     * Settling such a cycle would move the money a second time; the one test for it, for the add-to-netting hand-off
     * now and for refusing settlement or clearing those entries in Phase 2. Meant for cycles not yet settled (a
     * settled cycle's own sources are settled by it).
     */
    @Transactional(readOnly = true)
    public List<String> entriesSettledAtSource(UUID cycleId) {
        return entryRepository.findByCycleId(cycleId).stream()
            .filter(this::settledAtSource)
            .map(e -> e.getEntryReference() + " (" + e.getSourceReference() + ")")
            .collect(Collectors.toList());
    }

    boolean settledAtSource(NettingEntry e) {
        if (e.getIntercompanyRechargeId() != null) {
            // A POBO_PAYMENT recharge is settled through the subsidiary's IHB current account when the payment posts
            // (POBO R11), with or without the cut-over's reference: netting it pays a second time.
            return rechargeRepository.findById(e.getIntercompanyRechargeId())
                .map(r -> r.getStatus() == IntercompanyRecharge.RechargeStatus.SETTLED || r.getSettlementReference() != null
                    || r.getRechargeType() == IntercompanyRecharge.RechargeType.POBO_PAYMENT)
                .orElse(false);
        }
        if (e.getPayableId() != null) {
            return payableRepository.findById(e.getPayableId())
                .map(p -> p.getStatus() == Payable.PayableStatus.PAID || p.getStatus() == Payable.PayableStatus.NETTED)
                .orElse(false);
        }
        if (e.getReceivableId() != null) {
            return receivableRepository.findById(e.getReceivableId())
                .map(r -> r.getStatus() == Receivable.ReceivableStatus.PAID || r.getStatus() == Receivable.ReceivableStatus.NETTED)
                .orElse(false);
        }
        return false;
    }

    /**
     * A recharge a netting cycle takes (the rule of {@link #addPoboRecharges} / {@link #addCoboRecharges} without
     * includePending): a manual (untyped) or COBO-collection recharge, approved or recharged, not yet settled. A
     * POBO_PAYMENT recharge is already on the subsidiary's IHB current account and is never netted.
     */
    public static boolean awaitingNetting(IntercompanyRecharge r) {
        return (r.getRechargeType() == null || r.getRechargeType() == IntercompanyRecharge.RechargeType.COBO_COLLECTION)
            && (r.getStatus() == IntercompanyRecharge.RechargeStatus.APPROVED
                || r.getStatus() == IntercompanyRecharge.RechargeStatus.RECHARGED)
            && r.getSettlementReference() == null;
    }

    /** What is still owed on an invoice: its outstanding amount (a partly paid one), else its net amount. */
    static BigDecimal owed(BigDecimal outstanding, BigDecimal net) {
        return outstanding != null && outstanding.signum() > 0 ? outstanding : net;
    }

    private Set<UUID> groupOf(UUID corporateId) {
        return legalEntityRepository.findByCorporateIdOrderByHierarchyPath(corporateId).stream()
            .map(com.bank.vam.entity.hierarchy.LegalEntity::getId).collect(Collectors.toSet());
    }

    /** Both sides are entities of the group, and two different ones. */
    private static boolean between(Set<UUID> group, UUID a, UUID b) {
        return a != null && b != null && !a.equals(b) && group.contains(a) && group.contains(b);
    }

    private static void requireSameCorporate(NettingCycle cycle, UUID corporateId, String reference) {
        if (cycle.getCorporateId() == null || !cycle.getCorporateId().equals(corporateId)) {
            throw new BusinessException(reference + " is not of netting cycle " + cycle.getCycleReference() + "'s corporate");
        }
    }

    /**
     * Phase 4 Task 4.7: Add intercompany receivables to netting cycle.
     * 
     * Finds all receivables that are:
     * - Intercompany (isIntercompany = true)
     * - Netting eligible
     * - Open status
     * - Not already in a netting cycle
     * 
     * @param cycle The netting cycle
     * @param corporateId Corporate ID filter
     * @return Number of receivables added
     */
    @Transactional
    public int addIntercompanyReceivables(NettingCycle cycle, UUID corporateId) {
        List<Receivable> eligibleReceivables = receivableRepository.findNettingEligibleReceivables(corporateId);
        Set<UUID> group = groupOf(corporateId);

        int addedCount = 0;
        for (Receivable receivable : eligibleReceivables) {
            if (!receivable.canAddToNetting()
                    || !between(group, receivable.getOwningEntityId(), receivable.getIntercompanyEntityId())) {
                continue;
            }
            
            // Create netting entry from receivable
            // Note: For receivables, the owning entity is the PAYEE (creditor)
            //       and the intercompany entity is the PAYER (debtor)
            NettingEntry entry = NettingEntry.fromIntercompanyReceivable(
                cycle,
                receivable.getId(),
                receivable.getOwningEntityId(),      // Payee = entity that is owed
                receivable.getOwningEntityCode(),
                receivable.getOwningEntityName(),
                receivable.getIntercompanyEntityId(), // Payer = entity that owes
                receivable.getIntercompanyEntityCode(),
                receivable.getIntercompanyEntityName(),
                owed(receivable.getOutstandingAmount(), receivable.getNetAmount()),
                receivable.getCurrencyCode(),
                receivable.getDueDate(),
                receivable.getReceivableNumber()
            );
            entry.setEntryReference("ENT-" + String.format("%06d", entrySequence.getAndIncrement()));

            // Apply FX if needed
            applyFxConversion(entry, cycle.getBaseCurrency());
            
            cycle.getEntries().add(entry);
            cycle.setTotalGross(cycle.getTotalGross().add(entry.getBaseAmount()));
            
            // Update receivable with netting reference
            receivable.addToNettingCycle(cycle.getId(), cycle.getCycleReference(), entry.getId());
            receivableRepository.save(receivable);
            
            addedCount++;
            log.debug("Added receivable {} to netting cycle {}", 
                receivable.getReceivableNumber(), cycle.getCycleReference());
        }
        
        return addedCount;
    }

    /**
     * Phase 4 Task 4.8: Add POBO recharges to netting cycle.
     *
     * Finds all POBO recharges that are:
     * - Status APPROVED or RECHARGED (eligible for settlement)
     * - Not already settled
     * - Within the corporate group
     *
     * POBO Recharge Direction:
     * - Payer Entity (Treasury/Parent) paid on behalf of Behalf Entity (Subsidiary)
     * - Behalf Entity OWES Payer Entity
     * - So in netting terms: Behalf Entity is PAYER, Payer Entity is PAYEE
     *
     * @param cycle The netting cycle
     * @param corporateId the cycle's corporate: only recharges between two of its entities
     * @param includePending If true, also include PENDING (unapproved) recharges
     * @return Number of recharges added
     */
    @Transactional
    public int addPoboRecharges(NettingCycle cycle, UUID corporateId, boolean includePending) {
        // Eligible recharges: APPROVED or RECHARGED status (and optionally PENDING), not yet settled.
        // Untyped (manually created) recharges only: a POBO_PAYMENT recharge is a payment already posted to the
        // subsidiary's IHB current account, settled through that position -- netting it would debit the account
        // a second time (POBO spec R11 / D8).
        Set<UUID> group = groupOf(corporateId);
        List<IntercompanyRecharge> eligibleRecharges = rechargeRepository.findAll().stream()
            .filter(r -> r.getRechargeType() == null)
            .filter(r -> {
                if (includePending && r.getStatus() == IntercompanyRecharge.RechargeStatus.PENDING) {
                    return true;
                }
                return r.getStatus() == IntercompanyRecharge.RechargeStatus.APPROVED ||
                       r.getStatus() == IntercompanyRecharge.RechargeStatus.RECHARGED;
            })
            .filter(r -> r.getSettlementReference() == null) // Not settled
            .filter(r -> between(group, r.getPayerEntityId(), r.getBehalfEntityId())) // the cycle's corporate only
            .filter(r -> !entryRepository.existsByIntercompanyRechargeId(r.getId())) // in no cycle yet (re-populate)
            .collect(Collectors.toList());

        log.info("Found {} eligible POBO recharges (includePending={})", eligibleRecharges.size(), includePending);

        int addedCount = 0;
        for (IntercompanyRecharge recharge : eligibleRecharges) {
            // Create netting entry from POBO recharge
            // Direction: Behalf Entity (subsidiary) owes Payer Entity (treasury)
            // So Behalf is the PAYER in netting, Payer is the PAYEE
            NettingEntry entry = NettingEntry.builder()
                .cycle(cycle)
                .entryReference("ENT-" + String.format("%06d", entrySequence.getAndIncrement()))
                .flowDirection(FlowDirection.PAYABLE)
                // Behalf Entity is the one who owes (PAYER in netting entry)
                .payerEntityId(recharge.getBehalfEntityId())
                .payerEntityCode(recharge.getBehalfEntityCode())
                .payerEntityName(recharge.getBehalfEntityName())
                // Payer Entity (Treasury) is owed (PAYEE in netting entry)
                .payeeEntityId(recharge.getPayerEntityId())
                .payeeEntityCode(recharge.getPayerEntityCode())
                .payeeEntityName(recharge.getPayerEntityName())
                .grossAmount(recharge.getTotalRecharge())
                .currencyCode(recharge.getCurrencyCode())
                .exchangeRate(BigDecimal.ONE)
                .baseAmount(recharge.getTotalRecharge())
                .originalCurrency(recharge.getCurrencyCode())
                .originalAmount(recharge.getTotalRecharge())
                .sourceType(SourceType.POBO_RECHARGE)
                .sourceReference(recharge.getRechargeReference())
                .intercompanyRechargeId(recharge.getId())
                .dueDate(recharge.getSettlementDate())
                .status(NettingEntry.EntryStatus.PENDING)
                .build();

            // Apply FX if needed
            applyFxConversion(entry, cycle.getBaseCurrency());

            cycle.getEntries().add(entry);
            cycle.setTotalGross(cycle.getTotalGross().add(entry.getBaseAmount()));

            addedCount++;
            log.debug("Added POBO recharge {} to netting cycle {} (Subsidiary {} owes Treasury {})",
                recharge.getRechargeReference(), cycle.getCycleReference(),
                recharge.getBehalfEntityCode(), recharge.getPayerEntityCode());
        }

        log.info("Added {} POBO recharges to netting cycle {}", addedCount, cycle.getCycleReference());
        return addedCount;
    }

    /**
     * Phase 4 Task 4.8b: Add COBO collection recharges to netting cycle.
     *
     * Finds all COBO recharges that are:
     * - RechargeType = COBO_COLLECTION
     * - Status APPROVED or RECHARGED (eligible for settlement)
     * - Not already settled
     * - Within the corporate group
     *
     * COBO Recharge Direction (opposite of POBO):
     * - Treasury/Parent COLLECTED funds on behalf of Subsidiary
     * - Treasury OWES Subsidiary the collected amount
     * - So in netting terms: Treasury is PAYER, Subsidiary is PAYEE
     * - This creates a RECEIVABLE for the Subsidiary (they will receive funds)
     *
     * @param cycle The netting cycle
     * @param corporateId the cycle's corporate: only recharges between two of its entities
     * @param includePending If true, also include PENDING (unapproved) recharges
     * @return Number of COBO recharges added
     */
    @Transactional
    public int addCoboRecharges(NettingCycle cycle, UUID corporateId, boolean includePending) {
        // Find eligible COBO recharges: COBO_COLLECTION type, APPROVED or RECHARGED status, not yet settled
        Set<UUID> group = groupOf(corporateId);
        List<IntercompanyRecharge> coboRecharges = rechargeRepository.findAll().stream()
            .filter(r -> r.getRechargeType() == IntercompanyRecharge.RechargeType.COBO_COLLECTION)
            .filter(r -> {
                if (includePending && r.getStatus() == IntercompanyRecharge.RechargeStatus.PENDING) {
                    return true;
                }
                return r.getStatus() == IntercompanyRecharge.RechargeStatus.APPROVED ||
                       r.getStatus() == IntercompanyRecharge.RechargeStatus.RECHARGED;
            })
            .filter(r -> r.getSettlementReference() == null)  // Not yet settled
            .filter(r -> between(group, r.getPayerEntityId(), r.getBehalfEntityId())) // the cycle's corporate only
            .filter(r -> !entryRepository.existsByIntercompanyRechargeId(r.getId())) // in no cycle yet (re-populate)
            .collect(Collectors.toList());

        log.info("Found {} eligible COBO recharges (includePending={})", coboRecharges.size(), includePending);

        int added = 0;
        for (IntercompanyRecharge recharge : coboRecharges) {
            // For COBO: Treasury OWES Subsidiary (opposite of POBO)
            // So in netting: Subsidiary has RECEIVABLE from Treasury
            // Payer = Treasury (payerEntity), Payee = Subsidiary (behalfEntity)
            NettingEntry entry = NettingEntry.builder()
                .cycle(cycle)
                .entryReference("ENT-" + String.format("%06d", entrySequence.getAndIncrement()))
                .flowDirection(FlowDirection.RECEIVABLE)  // Subsidiary receives
                // Treasury is the one who owes (PAYER in netting entry)
                .payerEntityId(recharge.getPayerEntityId())
                .payerEntityCode(recharge.getPayerEntityCode())
                .payerEntityName(recharge.getPayerEntityName())
                // Subsidiary is owed (PAYEE in netting entry)
                .payeeEntityId(recharge.getBehalfEntityId())
                .payeeEntityCode(recharge.getBehalfEntityCode())
                .payeeEntityName(recharge.getBehalfEntityName())
                .grossAmount(recharge.getTotalRecharge())
                .currencyCode(recharge.getCurrencyCode())
                .exchangeRate(BigDecimal.ONE)
                .baseAmount(recharge.getTotalRecharge())
                .originalCurrency(recharge.getCurrencyCode())
                .originalAmount(recharge.getTotalRecharge())
                .sourceType(SourceType.COBO_COLLECTION)
                .sourceReference(recharge.getRechargeReference())
                .intercompanyRechargeId(recharge.getId())
                .dueDate(recharge.getSettlementDate())
                .status(NettingEntry.EntryStatus.PENDING)
                .build();

            // Apply FX if needed
            applyFxConversion(entry, cycle.getBaseCurrency());

            cycle.getEntries().add(entry);
            cycle.setTotalGross(cycle.getTotalGross().add(entry.getBaseAmount()));

            added++;
            log.debug("Added COBO recharge {} to netting cycle {} (Treasury {} owes Subsidiary {})",
                recharge.getRechargeReference(), cycle.getCycleReference(),
                recharge.getPayerEntityCode(), recharge.getBehalfEntityCode());
        }

        log.info("Added {} COBO recharges to netting cycle {}", added, cycle.getCycleReference());
        return added;
    }

    /**
     * Add all recharges (both POBO and COBO) to a netting cycle.
     * This is a convenience method that calls both addPoboRecharges and addCoboRecharges.
     *
     * @param cycle The netting cycle
     * @param corporateId Corporate ID filter
     * @param includePending If true, include PENDING recharges
     * @return Total number of recharges added (POBO + COBO)
     */
    @Transactional
    public int addAllRecharges(NettingCycle cycle, UUID corporateId, boolean includePending) {
        int poboCount = addPoboRecharges(cycle, corporateId, includePending);
        int coboCount = addCoboRecharges(cycle, corporateId, includePending);
        log.info("Added {} total recharges to netting cycle {} ({} POBO, {} COBO)",
            poboCount + coboCount, cycle.getCycleReference(), poboCount, coboCount);
        return poboCount + coboCount;
    }

    /**
     * Apply FX conversion to entry if currency differs from base currency.
     * No rate means the entry can't be netted: FxRateService refuses rather than use 1:1.
     */
    void applyFxConversion(NettingEntry entry, String baseCurrency) {
        if (!entry.getCurrencyCode().equals(baseCurrency)) {
            BigDecimal fxRate = fxRateService.getRate(entry.getCurrencyCode(), baseCurrency);
            entry.setExchangeRate(fxRate);
            entry.setBaseAmount(entry.getGrossAmount().multiply(fxRate));
            log.debug("Applied FX rate {} for {} -> {}", 
                fxRate, entry.getCurrencyCode(), baseCurrency);
        }
    }

    // ========================================================================
    // PHASE 4: ENHANCED NETTING CALCULATION (Task 4.8)
    // ========================================================================

    /**
     * Phase 4 Task 4.8: Calculate netting with bidirectional flows.
     * 
     * Enhanced to handle:
     * - Both PAYABLE and RECEIVABLE flow directions
     * - Gross payables and gross receivables per entity
     * - Net position = receivables - payables
     * 
     * @param cycleId Cycle to calculate
     * @return Calculation results
     */
    @Transactional
    public NettingDto.CalculateNettingResponse calculateNetting(UUID cycleId) {
        NettingCycle cycle = cycleRepository.findByIdWithEntries(cycleId)
                .orElseThrow(() -> new ResourceNotFoundException("Netting cycle not found: " + cycleId));
        // Recalculating a settled cycle would put its settled entries back in and let it settle again.
        if (cycle.getStatus() == NettingCycle.CycleStatus.SETTLED || cycle.getStatus() == NettingCycle.CycleStatus.CANCELLED) {
            throw new BusinessException("Cycle " + cycle.getCycleReference() + " is " + cycle.getStatus() + "; it cannot be recalculated");
        }

        cycle.setStatus(NettingCycle.CycleStatus.CALCULATING);

        // Build entity settlement map
        Map<UUID, NettingSettlement> settlementMap = new HashMap<>();

        for (NettingEntry entry : cycle.getEntries()) {
            if (entry.getStatus() == NettingEntry.EntryStatus.EXCLUDED) continue;
            entry.include();

            if (entry.getFlowDirection() == FlowDirection.PAYABLE) {
                // PAYABLE: Payer owes money to Payee
                // Payer's position: adds to payables
                // Payee's position: adds to receivables
                
                NettingSettlement payerSettlement = getOrCreateSettlement(
                    settlementMap, cycle, entry.getPayerEntityId(), 
                    entry.getPayerEntityCode(), entry.getPayerEntityName());
                payerSettlement.addPayable(entry.getBaseAmount());

                NettingSettlement payeeSettlement = getOrCreateSettlement(
                    settlementMap, cycle, entry.getPayeeEntityId(),
                    entry.getPayeeEntityCode(), entry.getPayeeEntityName());
                payeeSettlement.addReceivable(entry.getBaseAmount());
                
            } else if (entry.getFlowDirection() == FlowDirection.RECEIVABLE) {
                // RECEIVABLE: Payee is owed by Payer
                // Same logic as payable - payer owes, payee receives
                
                NettingSettlement payerSettlement = getOrCreateSettlement(
                    settlementMap, cycle, entry.getPayerEntityId(), 
                    entry.getPayerEntityCode(), entry.getPayerEntityName());
                payerSettlement.addPayable(entry.getBaseAmount());

                NettingSettlement payeeSettlement = getOrCreateSettlement(
                    settlementMap, cycle, entry.getPayeeEntityId(),
                    entry.getPayeeEntityCode(), entry.getPayeeEntityName());
                payeeSettlement.addReceivable(entry.getBaseAmount());
            }
        }

        // Calculate net positions for all participants
        BigDecimal totalGrossPayables = BigDecimal.ZERO;
        BigDecimal totalGrossReceivables = BigDecimal.ZERO;
        BigDecimal totalNetMovement = BigDecimal.ZERO;

        for (NettingSettlement settlement : settlementMap.values()) {
            settlement.calculateNetPosition();
            
            totalGrossPayables = totalGrossPayables.add(settlement.getGrossPayables());
            totalGrossReceivables = totalGrossReceivables.add(settlement.getGrossReceivables());
            
            // Net movement is the absolute sum of all net positions / 2 
            // (since each flow is counted in two settlements)
            totalNetMovement = totalNetMovement.add(settlement.getNetPosition().abs());
        }
        totalNetMovement = totalNetMovement.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);

        // Update cycle with results
        cycle.getSettlements().clear();
        cycle.getSettlements().addAll(settlementMap.values());
        cycle.setTotalGross(totalGrossPayables); // Total obligations before netting
        cycle.setTotalNet(totalNetMovement);
        cycle.setParticipantCount(settlementMap.size());
        cycle.setSavingsAmount(totalGrossPayables.subtract(totalNetMovement));
        
        if (totalGrossPayables.compareTo(BigDecimal.ZERO) > 0) {
            cycle.setSavingsPercent(cycle.getSavingsAmount()
                    .divide(totalGrossPayables, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)));
        }
        cycle.setStatus(NettingCycle.CycleStatus.PENDING_APPROVAL);

        cycle = cycleRepository.save(cycle);

        // Build response
        NettingDto.CalculateNettingResponse response = new NettingDto.CalculateNettingResponse();
        response.setCycleId(cycleId);
        response.setTotalGross(cycle.getTotalGross());
        response.setTotalNet(cycle.getTotalNet());
        response.setSavingsAmount(cycle.getSavingsAmount());
        response.setSavingsPercent(cycle.getSavingsPercent());
        response.setParticipantCount(cycle.getParticipantCount());
        response.setSettlements(cycle.getSettlements().stream()
                .map(this::toSettlementResponse)
                .collect(Collectors.toList()));

        log.info("Calculated netting for cycle {}: gross={}, net={}, savings={}%",
                cycle.getCycleReference(), totalGrossPayables, totalNetMovement, cycle.getSavingsPercent());
        return response;
    }
    
    private NettingSettlement getOrCreateSettlement(Map<UUID, NettingSettlement> map, 
            NettingCycle cycle, UUID entityId, String entityCode, String entityName) {
        return map.computeIfAbsent(entityId, id -> 
            NettingSettlement.forEntity(cycle, entityId, entityCode, entityName));
    }

    // ========================================================================
    // APPROVAL & SETTLEMENT (Enhanced for Phase 4)
    // ========================================================================

    @Transactional
    public NettingDto.CycleResponse approveCycle(UUID cycleId, String approver) {
        NettingCycle cycle = cycleRepository.findById(cycleId)
                .orElseThrow(() -> new ResourceNotFoundException("Netting cycle not found: " + cycleId));

        if (cycle.getStatus() != NettingCycle.CycleStatus.PENDING_APPROVAL) {
            throw new BusinessException("Cycle must be in PENDING_APPROVAL status to approve");
        }

        cycle.setStatus(NettingCycle.CycleStatus.APPROVED);
        cycle.setApprovedBy(approver);
        cycle.setApprovedAt(LocalDateTime.now());

        cycle = cycleRepository.save(cycle);
        log.info("Approved netting cycle: {} by {}", cycle.getCycleReference(), approver);
        return toCycleResponse(cycle);
    }

    /**
     * Settle an approved cycle leg by leg (spec D5 / R10). The preview and the posting are one computation,
     * {@link #settlementPlan}, so what the treasurer confirmed is what posts.
     *
     * <p>Legs: the payers' nets are matched to the receivers' nets, biggest first (ties by entity code); each leg is one
     * payer paying one receiver, and per entity the legs add up to its net. Every entity settles on its own account in
     * the cycle's base currency ({@link #findSettlementVaForEntity}). A leg is
     * <ul>
     * <li>LEDGER when both accounts are IHB current accounts, or sit on the same bank account (the shadow their payments
     *     leave through, {@code TransactionService.payoutShadow}): the payer's account is debited and the receiver's
     *     credited, the IHB mirrors follow (POBO R10);</li>
     * <li>PAYMENT otherwise: an outward payment of the leg from the payer's account to the receiver's bank account (the
     *     IBAN/BIC of the physical account behind its shadow) through the outward payment path, remittance = the cycle
     *     reference. The receiver's account is credited when that payment comes in, by the inward path, not here;</li>
     * <li>REFUSED when an account is missing, or the payer's account is at another bank: the home bank runs every payment
     *     and cannot pay from another bank's account with an ordinary payment (that is an MT101 instruction).</li>
     * </ul>
     *
     * <p>State: the outward path books synchronously (its transactions are COMPLETED in the call), so a cycle goes
     * APPROVED to SETTLED in one transaction: every leg books, or none does. Refused legs and entries settled at their
     * source are refused before anything posts; a failed payment rolls the whole attempt back. Either way the cycle stays
     * APPROVED with {@code settlementIssue} saying why (written in its own transaction), and no entry, source document or
     * participant is marked settled.
     * ponytail: no SETTLING state, since nothing is ever left half-booked; add it (and widen any CHECK on the status
     * column, V46-style) when a payment leg books asynchronously.
     */
    public NettingDto.SettleCycleResponse settleCycle(UUID cycleId) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        try {
            return tx.execute(s -> settleInOneTransaction(cycleId));
        } catch (SettlementRefused e) {
            tx.executeWithoutResult(s -> cycleRepository.findById(cycleId).ifPresent(c -> {
                c.setSettlementIssue(e.getMessage());
                cycleRepository.save(c);
            }));
            throw new BusinessException(e.getMessage());
        }
    }

    /** A settlement attempt that must not post: it rolls back and its reason is recorded on the cycle. */
    static class SettlementRefused extends RuntimeException {
        SettlementRefused(String message) {
            super(message);
        }
    }

    /** What settling would post (R11), computed by {@link #settlementPlan} as {@link #settleCycle} computes it. */
    @Transactional(readOnly = true)
    public NettingDto.SettlementPreview previewSettlement(UUID cycleId) {
        NettingCycle cycle = cycleRepository.findByIdWithSettlements(cycleId)
            .orElseThrow(() -> new ResourceNotFoundException("Netting cycle not found: " + cycleId));
        return settlementPlan(cycle);
    }

    NettingDto.SettlementPreview settlementPlan(NettingCycle cycle) {
        List<NettingDto.SettlementLeg> legs = legs(cycle);
        List<String> stale = entriesSettledAtSource(cycle.getId());
        List<String> blockers = new ArrayList<>();
        if (cycle.getSettlements().isEmpty()) {
            blockers.add("no positions calculated: calculate the cycle first");
        }
        if (!stale.isEmpty()) {
            blockers.add("entries already settled outside netting: " + String.join(", ", stale));
        }
        legs.stream().filter(l -> l.getKind() == NettingDto.LegKind.REFUSED).forEach(l -> blockers.add(
            l.getPayerEntityCode() + " to " + l.getReceiverEntityCode() + " " + l.getAmount() + " " + l.getCurrency() + ": " + l.getReason()));

        NettingDto.SettlementPreview p = new NettingDto.SettlementPreview();
        p.setCycleId(cycle.getId());
        p.setCycleReference(cycle.getCycleReference());
        p.setStatus(cycle.getStatus());
        p.setCurrency(cycle.getBaseCurrency());
        p.setSettlements(cycle.getSettlements().stream()
            .sorted(Comparator.comparing(NettingSettlement::getEntityCode))
            .map(this::toSettlementResponse).collect(Collectors.toList()));
        p.setLegs(legs);
        p.setStaleEntries(stale);
        p.setBlockers(blockers);
        p.setSettleable(cycle.getStatus() == NettingCycle.CycleStatus.APPROVED && blockers.isEmpty());
        p.setSettlementIssue(cycle.getSettlementIssue());
        return p;
    }

    private NettingDto.SettleCycleResponse settleInOneTransaction(UUID cycleId) {
        NettingCycle cycle = cycleRepository.findByIdWithSettlements(cycleId)
                .orElseThrow(() -> new ResourceNotFoundException("Netting cycle not found: " + cycleId));

        if (cycle.getStatus() != NettingCycle.CycleStatus.APPROVED) {
            throw new BusinessException("Cycle must be APPROVED to settle");
        }
        String ref = cycle.getCycleReference();
        NettingDto.SettlementPreview plan = settlementPlan(cycle);
        if (!plan.getBlockers().isEmpty()) {
            throw new SettlementRefused("Netting cycle " + ref + " was not settled, nothing was posted: "
                + String.join("; ", plan.getBlockers()));
        }

        log.info("Settling netting cycle {}: {} legs", ref, plan.getLegs().size());
        String correlationId = "NETTING-" + ref;
        Map<UUID, UUID> transactionOf = new HashMap<>();   // entity -> its last leg's transaction
        Map<UUID, VirtualAccount> accountOf = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        int legNo = 0;
        for (NettingDto.SettlementLeg leg : plan.getLegs()) {
            legNo++;
            VirtualAccount from = virtualAccountRepository.findById(leg.getFromVaId()).orElseThrow();
            VirtualAccount to = virtualAccountRepository.findById(leg.getToVaId()).orElseThrow();
            accountOf.put(leg.getPayerEntityId(), from);
            accountOf.put(leg.getReceiverEntityId(), to);
            if (leg.getKind() == NettingDto.LegKind.LEDGER) {
                Transaction debit = postLedger(ref, from, leg.getPayerEntityId(), leg.getPayerEntityCode(), leg.getAmount(), true, correlationId);
                Transaction credit = postLedger(ref, to, leg.getReceiverEntityId(), leg.getReceiverEntityCode(), leg.getAmount(), false, correlationId);
                leg.setTransactionReference(debit.getReferenceNumber());
                transactionOf.put(leg.getPayerEntityId(), debit.getId());
                transactionOf.put(leg.getReceiverEntityId(), credit.getId());
            } else {
                OutwardPaymentResponse paid = outwardPayments.processOutwardPayment(OutwardPaymentRequest.builder()
                    .sourceVaId(from.getId())
                    .amount(leg.getAmount())
                    .currency(leg.getCurrency())
                    .creditorName(leg.getReceiverEntityName() != null ? leg.getReceiverEntityName() : leg.getReceiverEntityCode())
                    .creditorAccount(leg.getBeneficiaryAccount())
                    .creditorBic(leg.getBeneficiaryBic())
                    .remittanceInfo(ref)
                    .endToEndId(ref + "-" + legNo)
                    .requestedExecutionDate(LocalDate.now())
                    .build());
                if (paid == null || !paid.isSuccess()) {
                    throw new SettlementRefused("Netting cycle " + ref + " was not settled, nothing was posted: the payment "
                        + leg.getPayerEntityCode() + " to " + leg.getReceiverEntityCode() + " " + leg.getAmount() + " "
                        + leg.getCurrency() + " failed: " + (paid == null ? "no response" : paid.getErrorMessage()));
                }
                leg.setTransactionReference(paid.getTransactionReference());
                transactionOf.put(leg.getPayerEntityId(), paid.getTransactionId());
            }
            total = total.add(leg.getAmount());
        }

        for (NettingSettlement settlement : cycle.getSettlements()) {
            VirtualAccount account = accountOf.get(settlement.getEntityId());
            if (account != null) {
                settlement.setSettlementVaId(account.getId());
                settlement.setSettlementAccount(account.getVaNumber());
            }
            settlement.markSettled(generateNettingSettlementRef(ref), transactionOf.get(settlement.getEntityId()));
        }
        for (NettingEntry entry : cycle.getEntries()) {
            if (entry.getStatus() == NettingEntry.EntryStatus.INCLUDED) {
                entry.markSettled();
            }
        }
        updateSourceDocuments(cycle);

        cycle.setStatus(NettingCycle.CycleStatus.SETTLED);
        cycle.setSettledAt(LocalDateTime.now());
        cycle.setSettlementIssue(null);
        cycleRepository.save(cycle);
        postNettingFees(cycle);

        log.info("Settled netting cycle {}: {} legs, total {} {}", ref, plan.getLegs().size(), total, cycle.getBaseCurrency());
        NettingDto.SettleCycleResponse response = new NettingDto.SettleCycleResponse();
        response.setCycleId(cycleId);
        response.setCycleReference(ref);
        response.setSettledAt(cycle.getSettledAt());
        response.setSuccessfulSettlements(plan.getLegs().size());
        response.setFailedSettlements(0);
        response.setTotalSettled(total);
        response.setLegs(plan.getLegs());
        return response;
    }

    /** One side of a ledger leg: the account moves, a COMPLETED ledger row records it, the IHB mirrors follow. */
    private Transaction postLedger(String cycleRef, VirtualAccount va, UUID entityId, String entityCode, BigDecimal amount,
                                   boolean debit, String correlationId) {
        BigDecimal balanceBefore = va.getCurrentBalance();
        va.setCurrentBalance(debit ? balanceBefore.subtract(amount) : balanceBefore.add(amount));
        if (va.getAvailableBalance() != null) {
            if (debit) {
                va.setAvailableBalance(va.getAvailableBalance().subtract(amount));
            } else {
                va.syncAvailableToCurrent();
            }
        }
        virtualAccountRepository.save(va);
        String side = debit ? "PAY" : "RECEIVE";
        Transaction row = ledgerTransactionRepository.save(Transaction.builder()
            .movementType(Transaction.MovementType.IC_SETTLEMENT)
            .corporateId(va.getCorporateId())
            .legalEntityId(entityId)
            .vaId(va.getId())
            .programId(va.getProgramId())
            .amount(amount)
            .currencyCode(va.getCurrencyCode())
            .balanceBefore(balanceBefore)
            .balanceAfter(va.getCurrentBalance())
            .transactionDate(LocalDateTime.now())
            .valueDate(LocalDate.now())
            .referenceNumber(generateLedgerReference(debit ? "NETPAY" : "NETRCV"))
            .description("Netting settlement " + side + " - " + cycleRef)
            .channel("NETTING_SETTLEMENT")
            .correlationId(correlationId)
            .status(Transaction.TransactionStatus.COMPLETED)
            .processingNotes("Multilateral netting - " + side + " direction for " + entityCode)
            .build());
        ihbMirrors.syncIhbMirrors(va, "netting settlement " + side + " " + cycleRef, correlationId);
        return row;
    }

    /** The payers' nets matched to the receivers', biggest first, ties by entity code (deterministic: preview = posting). */
    private List<NettingDto.SettlementLeg> legs(NettingCycle cycle) {
        Comparator<NettingSettlement> biggestFirst = Comparator.comparing(NettingSettlement::getSettlementAmount).reversed()
            .thenComparing(NettingSettlement::getEntityCode);
        List<NettingSettlement> payers = cycle.getSettlements().stream().filter(NettingSettlement::needsToPay)
            .sorted(biggestFirst).collect(Collectors.toList());
        List<NettingSettlement> receivers = cycle.getSettlements().stream().filter(NettingSettlement::willReceive)
            .sorted(biggestFirst).collect(Collectors.toList());
        BigDecimal[] due = receivers.stream().map(NettingSettlement::getSettlementAmount).toArray(BigDecimal[]::new);
        Map<UUID, BigDecimal> drawn = new HashMap<>(); // per payer account: its ledger legs so far, checked together
        List<NettingDto.SettlementLeg> legs = new ArrayList<>();
        int r = 0;
        for (NettingSettlement payer : payers) {
            BigDecimal owes = payer.getSettlementAmount();
            while (owes.signum() > 0 && r < receivers.size()) {
                BigDecimal amount = owes.min(due[r]);
                legs.add(leg(cycle.getBaseCurrency(), payer, receivers.get(r), amount, drawn));
                owes = owes.subtract(amount);
                due[r] = due[r].subtract(amount);
                if (due[r].signum() == 0) r++;
            }
        }
        return legs;
    }

    private NettingDto.SettlementLeg leg(String currency, NettingSettlement payer, NettingSettlement receiver, BigDecimal amount,
                                         Map<UUID, BigDecimal> drawn) {
        NettingDto.SettlementLeg leg = new NettingDto.SettlementLeg();
        leg.setPayerEntityId(payer.getEntityId());
        leg.setPayerEntityCode(payer.getEntityCode());
        leg.setPayerEntityName(payer.getEntityName());
        leg.setReceiverEntityId(receiver.getEntityId());
        leg.setReceiverEntityCode(receiver.getEntityCode());
        leg.setReceiverEntityName(receiver.getEntityName());
        leg.setAmount(amount);
        leg.setCurrency(currency);

        VirtualAccount from = findSettlementVaForEntity(payer.getEntityId(), currency);
        VirtualAccount to = findSettlementVaForEntity(receiver.getEntityId(), currency);
        if (from == null || to == null) {
            return refused(leg, (from == null ? payer : receiver).getEntityCode() + " has no active " + currency + " account to settle on");
        }
        leg.setFromVaId(from.getId());
        leg.setFromAccount(from.getVaNumber());
        leg.setToVaId(to.getId());
        leg.setToAccount(to.getVaNumber());
        if (Boolean.TRUE.equals(from.getIhbParticipant()) && Boolean.TRUE.equals(to.getIhbParticipant())) {
            return ledger(leg, from, payer, drawn);
        }
        VirtualAccount fromBank = bankAccountOf(from);
        VirtualAccount toBank = bankAccountOf(to);
        if (fromBank != null && toBank != null && (fromBank.getId().equals(toBank.getId())
                || (fromBank.getLinkedPhysicalAccountId() != null
                    && fromBank.getLinkedPhysicalAccountId().equals(toBank.getLinkedPhysicalAccountId())))) {
            return ledger(leg, from, payer, drawn);
        }
        if (fromBank == null) {
            return refused(leg, payer.getEntityCode() + "'s account " + from.getVaNumber() + " has no " + currency
                + " bank account to pay from");
        }
        if (fromBank.isOtherBankShadow(homeBank.getBic())) {
            return refused(leg, "payer's account is at another bank (" + fromBank.getBankSwift() + "); settle it outside "
                + "netting or through an external-bank instruction");
        }
        if (toBank == null) {
            return refused(leg, receiver.getEntityCode() + "'s account " + to.getVaNumber() + " has no " + currency
                + " bank account to be paid to");
        }
        com.bank.vam.entity.PhysicalAccount bank = toBank.getLinkedPhysicalAccountId() == null ? null
            : physicalAccountRepository.findById(toBank.getLinkedPhysicalAccountId()).orElse(null);
        String account = bank == null ? toBank.getBankAccountNumber() : bank.getIban() != null ? bank.getIban() : bank.getAccountNumber();
        if (account == null) {
            return refused(leg, receiver.getEntityCode() + "'s bank account has no IBAN or account number to pay to");
        }
        leg.setBeneficiaryAccount(account);
        leg.setBeneficiaryBic(bank != null && bank.getBankCode() != null ? bank.getBankCode() : toBank.getBankSwift());
        leg.setKind(NettingDto.LegKind.PAYMENT);
        return leg;
    }

    /**
     * A ledger leg, unless the payer's account cannot cover it together with its earlier ledger legs: an IHB current
     * account up to its credit headroom (balance + credit limit, PoboEnrolmentService.headroomRefusal), any other account
     * up to its available balance. Payment legs are checked by the outward path instead.
     */
    private static NettingDto.SettlementLeg ledger(NettingDto.SettlementLeg leg, VirtualAccount from, NettingSettlement payer,
                                                   Map<UUID, BigDecimal> drawn) {
        BigDecimal needed = drawn.merge(from.getId(), leg.getAmount(), BigDecimal::add);
        String earlier = needed.compareTo(leg.getAmount()) > 0 ? "this leg and its earlier ones need " : "this leg needs ";
        if (Boolean.TRUE.equals(from.getIhbParticipant())) {
            String refusal = com.bank.vam.service.pobo.PoboEnrolmentService.headroomRefusal(from, needed);
            if (refusal != null) return refused(leg, refusal);
        } else {
            BigDecimal available = from.getAvailableBalance() != null ? from.getAvailableBalance() : from.getCurrentBalance();
            if (available == null || needed.compareTo(available) > 0) {
                String ccy = from.getCurrencyCode();
                return refused(leg, payer.getEntityCode() + "'s " + from.getVaNumber() + " has " + ccy + " "
                    + String.format(Locale.ROOT, "%,.2f", available == null ? BigDecimal.ZERO : available) + " available; " + earlier
                    + ccy + " " + String.format(Locale.ROOT, "%,.2f", needed));
            }
        }
        leg.setKind(NettingDto.LegKind.LEDGER);
        return leg;
    }

    private static NettingDto.SettlementLeg refused(NettingDto.SettlementLeg leg, String reason) {
        leg.setKind(NettingDto.LegKind.REFUSED);
        leg.setReason(reason);
        return leg;
    }

    /** The bank account an account's money sits on: the shadow its payments leave through; null when it has none. */
    private VirtualAccount bankAccountOf(VirtualAccount va) {
        try {
            return transactionService.payoutShadow(va);
        } catch (BusinessException e) {
            return null;
        }
    }

    /**
     * The account a participant settles on, paying or receiving, in the cycle's currency: its IHB current account, else
     * its settlement account, else a transaction account, else any other leaf account of its own; by account number
     * among equals, so preview and posting pick the same. Only real accounts: an in-house mirror (IC receivable /
     * payable, IHB settlement) or a bank shadow is never moved by netting; the mirrors follow the IHB positions.
     */
    private VirtualAccount findSettlementVaForEntity(UUID entityId, String currency) {
        List<VirtualAccount> entityVas = virtualAccountRepository.findAll().stream()
            .filter(va -> entityId.equals(va.getOwningEntityId()))
            .filter(va -> va.getStatus() == VirtualAccount.VaStatus.ACTIVE)
            .filter(va -> currency.equals(va.getCurrencyCode()))
            .filter(va -> isRealAccount(va) && va.isLeafAccount())
            .sorted(Comparator.comparing(VirtualAccount::getVaNumber, Comparator.nullsLast(Comparator.naturalOrder())))
            .collect(Collectors.toList());
        return entityVas.stream().filter(va -> Boolean.TRUE.equals(va.getIhbParticipant())).findFirst()
            .or(() -> entityVas.stream().filter(VirtualAccount::isSettlementVa).findFirst())
            .or(() -> entityVas.stream().filter(va -> VirtualAccount.AccountCategory.TRANSACTION == va.getAccountCategory()).findFirst())
            .orElse(entityVas.isEmpty() ? null : entityVas.get(0));
    }

    static boolean isRealAccount(VirtualAccount va) {
        VirtualAccount.MirrorAccountType m = va.getMirrorAccountType();
        boolean mirror = m == VirtualAccount.MirrorAccountType.IC_RECEIVABLE || m == VirtualAccount.MirrorAccountType.IC_PAYABLE
            || m == VirtualAccount.MirrorAccountType.IHB_SETTLEMENT;
        return !mirror && va.getAccountCategory() != VirtualAccount.AccountCategory.INTERCOMPANY && !va.isPhysicalMirror();
    }

    private String generateNettingSettlementRef(String cycleRef) {
        return "STL-" + cycleRef + "-" + String.format("%04d", settlementLedgerSequence.getAndIncrement());
    }

    private String generateLedgerReference(String prefix) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        return prefix + "-" + timestamp + "-" + String.format("%04d", (int)(Math.random() * 10000));
    }
    
    /**
     * Phase 4 Task 4.10: Update source documents after netting settlement.
     * 
     * Marks payables and receivables as settled via netting.
     */
    private void updateSourceDocuments(NettingCycle cycle) {
        String settlementRef = "NET-" + cycle.getCycleReference();
        
        for (NettingEntry entry : cycle.getEntries()) {
            if (entry.getStatus() != NettingEntry.EntryStatus.SETTLED) continue;
            
            // Update source payable if present
            if (entry.getPayableId() != null) {
                payableRepository.findById(entry.getPayableId()).ifPresent(payable -> {
                    payable.markNettingSettled(settlementRef);
                    payableRepository.save(payable);
                    log.debug("Marked payable {} as settled via netting", payable.getPayableNumber());
                });
            }
            
            // Update source receivable if present
            if (entry.getReceivableId() != null) {
                receivableRepository.findById(entry.getReceivableId()).ifPresent(receivable -> {
                    receivable.markNettingSettled(settlementRef);
                    receivableRepository.save(receivable);
                    log.debug("Marked receivable {} as settled via netting", receivable.getReceivableNumber());
                });
            }

            // Update POBO recharge if present
            if (entry.getIntercompanyRechargeId() != null) {
                rechargeRepository.findById(entry.getIntercompanyRechargeId()).ifPresent(recharge -> {
                    recharge.settle(IntercompanyRecharge.SettlementMethod.NETTING, settlementRef);
                    rechargeRepository.save(recharge);
                    log.debug("Marked POBO recharge {} as settled via netting", recharge.getRechargeReference());
                });
            }

            // TODO: Update IHB transactions if present
        }
    }
    
    /**
     * Post netting fees to Settlement VA after cycle settlement.
     */
    private void postNettingFees(NettingCycle cycle) {
        if (cycle.getSettlements() == null || cycle.getSettlements().isEmpty()) {
            return;
        }
        
        UUID initiatorEntityId = null;
        
        for (NettingSettlement settlement : cycle.getSettlements()) {
            if (settlement.getSettlementStatus() != NettingSettlement.SettlementStatus.SETTLED) {
                continue;
            }
            
            if (initiatorEntityId == null) {
                initiatorEntityId = settlement.getEntityId();
            }
            
            UUID participantVaId = resolveVaForEntity(settlement.getEntityId());
            if (participantVaId != null) {
                try {
                    feePostingService.postFee(
                        participantVaId,
                        NETTING_PARTICIPATION_FEE,
                        "NETTING_PARTICIPATION_FEE",
                        cycle.getId(),
                        "Netting cycle " + cycle.getCycleReference() + " participation fee - " + settlement.getEntityCode()
                    );
                } catch (Exception e) {
                    log.error("Failed to post netting participation fee for entity {}: {}", 
                        settlement.getEntityCode(), e.getMessage());
                }
            }
        }
        
        if (initiatorEntityId != null) {
            UUID initiatorVaId = resolveVaForEntity(initiatorEntityId);
            if (initiatorVaId != null) {
                try {
                    feePostingService.postFee(
                        initiatorVaId,
                        NETTING_EXECUTION_FEE,
                        "NETTING_EXECUTION_FEE",
                        cycle.getId(),
                        "Netting cycle " + cycle.getCycleReference() + " execution fee"
                    );
                } catch (Exception e) {
                    log.error("Failed to post netting execution fee for cycle {}: {}", 
                        cycle.getCycleReference(), e.getMessage());
                }
            }
        }
    }
    
    private UUID resolveVaForEntity(UUID entityId) {
        // TODO: Implement proper entity-to-VA mapping
        log.debug("VA resolution for netting entity {} not yet implemented", entityId);
        return null;
    }

    // ========================================================================
    // DTO CONVERSION METHODS
    // ========================================================================

    private NettingDto.CycleResponse toCycleResponse(NettingCycle cycle) {
        NettingDto.CycleResponse dto = new NettingDto.CycleResponse();
        dto.setId(cycle.getId());
        dto.setCycleReference(cycle.getCycleReference());
        dto.setCycleName(cycle.getCycleName());
        dto.setPeriodStart(cycle.getPeriodStart());
        dto.setPeriodEnd(cycle.getPeriodEnd());
        dto.setSettlementDate(cycle.getSettlementDate());
        dto.setBaseCurrency(cycle.getBaseCurrency());
        dto.setTotalGross(cycle.getTotalGross());
        dto.setTotalNet(cycle.getTotalNet());
        dto.setSavingsAmount(cycle.getSavingsAmount());
        dto.setSavingsPercent(cycle.getSavingsPercent());
        dto.setEntryCount(cycle.getEntryCount());
        dto.setParticipantCount(cycle.getParticipantCount());
        dto.setStatus(cycle.getStatus());
        dto.setApprovedBy(cycle.getApprovedBy());
        dto.setApprovedAt(cycle.getApprovedAt());
        dto.setSettledAt(cycle.getSettledAt());
        dto.setCreatedAt(cycle.getCreatedAt());
        dto.setCorporateId(cycle.getCorporateId());
        dto.setSettlementIssue(cycle.getSettlementIssue());

        if (cycle.getEntries() != null) {
            dto.setEntries(cycle.getEntries().stream()
                    .map(this::toEntryResponse)
                    .collect(Collectors.toList()));
        }
        if (cycle.getSettlements() != null) {
            dto.setSettlements(cycle.getSettlements().stream()
                    .map(this::toSettlementResponse)
                    .collect(Collectors.toList()));
        }
        return dto;
    }

    private NettingDto.EntryResponse toEntryResponse(NettingEntry entry) {
        NettingDto.EntryResponse dto = new NettingDto.EntryResponse();
        dto.setId(entry.getId());
        dto.setEntryReference(entry.getEntryReference());
        dto.setFlowDirection(entry.getFlowDirection());
        dto.setPayerEntityId(entry.getPayerEntityId());
        dto.setPayerEntityCode(entry.getPayerEntityCode());
        dto.setPayerEntityName(entry.getPayerEntityName());
        dto.setPayeeEntityId(entry.getPayeeEntityId());
        dto.setPayeeEntityCode(entry.getPayeeEntityCode());
        dto.setPayeeEntityName(entry.getPayeeEntityName());
        dto.setGrossAmount(entry.getGrossAmount());
        dto.setCurrencyCode(entry.getCurrencyCode());
        dto.setExchangeRate(entry.getExchangeRate());
        dto.setBaseAmount(entry.getBaseAmount());
        dto.setSourceType(entry.getSourceType());
        dto.setSourceReference(entry.getSourceReference());
        dto.setPayableId(entry.getPayableId());
        dto.setReceivableId(entry.getReceivableId());
        dto.setDueDate(entry.getDueDate());
        dto.setStatus(entry.getStatus());
        return dto;
    }

    private NettingDto.SettlementResponse toSettlementResponse(NettingSettlement settlement) {
        NettingDto.SettlementResponse dto = new NettingDto.SettlementResponse();
        dto.setId(settlement.getId());
        dto.setEntityId(settlement.getEntityId());
        dto.setEntityCode(settlement.getEntityCode());
        dto.setEntityName(settlement.getEntityName());
        dto.setGrossPayables(settlement.getGrossPayables());
        dto.setGrossReceivables(settlement.getGrossReceivables());
        dto.setTotalPayable(settlement.getTotalPayable());
        dto.setTotalReceivable(settlement.getTotalReceivable());
        dto.setNetPosition(settlement.getNetPosition());
        dto.setPayableEntryCount(settlement.getPayableEntryCount());
        dto.setReceivableEntryCount(settlement.getReceivableEntryCount());
        dto.setSettlementDirection(settlement.getSettlementDirection());
        dto.setSettlementAccount(settlement.getSettlementAccount());
        dto.setSettlementStatus(settlement.getSettlementStatus());
        dto.setSettledAt(settlement.getSettledAt());
        dto.setSettlementReference(settlement.getSettlementReference());
        return dto;
    }

    // ========================================================================
    // PUBLIC API: POPULATE CYCLE (Controller entry point)
    // ========================================================================

    /**
     * Populate a netting cycle with all eligible obligations.
     * This is the public API called by NettingController.
     *
     * @param cycleId The cycle to populate
     * @param corporateId Optional corporate filter
     * @param includePending If true, include PENDING recharges (for testing)
     * @return PopulateCycleResponse DTO
     */
    @Transactional
    public NettingDto.PopulateCycleResponse populateCycle(UUID cycleId, UUID corporateId, boolean includePending) {
        NettingCycle cycle = cycleRepository.findByIdWithEntries(cycleId)
                .orElseThrow(() -> new ResourceNotFoundException("Netting cycle not found: " + cycleId));

        PopulationResult result = populateCycleWithAllObligations(cycleId, corporateId, includePending);

        // Reload cycle to get updated totals
        cycle = cycleRepository.findByIdWithEntries(cycleId).orElseThrow();

        NettingDto.PopulateCycleResponse response = new NettingDto.PopulateCycleResponse();
        response.setCycleId(cycle.getId());
        response.setCycleReference(cycle.getCycleReference());
        response.setPayablesAdded(result.payablesAdded());
        response.setReceivablesAdded(result.receivablesAdded());
        response.setRechargesAdded(result.rechargesAdded());           // Total (POBO + COBO)
        response.setPoboRechargesAdded(result.poboRechargesAdded());   // POBO only
        response.setCoboRechargesAdded(result.coboRechargesAdded());   // COBO only
        response.setTotalAdded(result.totalAdded());
        response.setTotalEntries(cycle.getEntries().size());
        response.setTotalGross(cycle.getTotalGross());

        log.info("Cycle {} populated: {} entries added, total {} entries, gross {}",
            cycle.getCycleReference(), result.totalAdded(), cycle.getEntries().size(), cycle.getTotalGross());

        return response;
    }

    // ========================================================================
    // RESULT CLASSES
    // ========================================================================

    /**
     * Result of cycle population.
     */
    public record PopulationResult(
        int payablesAdded,
        int receivablesAdded,
        int poboRechargesAdded,
        int coboRechargesAdded
    ) {
        /**
         * Total recharges added (POBO + COBO).
         */
        public int rechargesAdded() {
            return poboRechargesAdded + coboRechargesAdded;
        }

        /**
         * Total entries added across all sources.
         */
        public int totalAdded() {
            return payablesAdded + receivablesAdded + poboRechargesAdded + coboRechargesAdded;
        }
    }
}