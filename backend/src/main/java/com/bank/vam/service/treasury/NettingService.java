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
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
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
    private final VirtualAccountRepository virtualAccountRepository;
    private final TransactionRepository ledgerTransactionRepository;
    private final FeePostingService feePostingService;
    private final com.bank.vam.config.MarketProfileProperties marketProfile;

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

    @Transactional(readOnly = true)
    public List<NettingDto.CycleResponse> getAllCycles() {
        return cycleRepository.findAll().stream()
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
        NettingCycle cycle = new NettingCycle();
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
     * - IHB Loans/Deposits (future enhancement)
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

        log.info("Populating netting cycle {} with all obligations for corporate {} (includePending={})",
            cycle.getCycleReference(), corporateId, includePending);

        int payablesAdded = addIntercompanyPayables(cycle, corporateId);
        int receivablesAdded = addIntercompanyReceivables(cycle, corporateId);

        // Add POBO recharges (PAYABLE direction - subsidiary owes treasury)
        int poboRechargesAdded = addPoboRecharges(cycle, corporateId, includePending);

        // Add COBO recharges (RECEIVABLE direction - treasury owes subsidiary) - NEW
        int coboRechargesAdded = addCoboRecharges(cycle, corporateId, includePending);

        // Future: Add IHB loans

        // Update cycle stats
        cycle.setEntryCount(cycle.getEntries().size());
        cycleRepository.save(cycle);

        PopulationResult result = new PopulationResult(payablesAdded, receivablesAdded, poboRechargesAdded, coboRechargesAdded, 0);
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
        
        int addedCount = 0;
        for (Payable payable : eligiblePayables) {
            if (!payable.canAddToNetting()) {
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
                payable.getNetAmount(),
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
        
        int addedCount = 0;
        for (Receivable receivable : eligibleReceivables) {
            if (!receivable.canAddToNetting()) {
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
                receivable.getNetAmount(),
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
     * @param corporateId Corporate ID filter (not currently used - recharges are cross-entity)
     * @param includePending If true, also include PENDING (unapproved) recharges
     * @return Number of recharges added
     */
    @Transactional
    public int addPoboRecharges(NettingCycle cycle, UUID corporateId, boolean includePending) {
        // Find eligible POBO recharges: APPROVED or RECHARGED status (and optionally PENDING), not yet settled
        // Filter specifically for POBO_PAYMENT type or null type (backward compatibility)
        List<IntercompanyRecharge> eligibleRecharges = rechargeRepository.findAll().stream()
            .filter(r -> r.getRechargeType() == null || r.getRechargeType() == IntercompanyRecharge.RechargeType.POBO_PAYMENT)
            .filter(r -> {
                if (includePending && r.getStatus() == IntercompanyRecharge.RechargeStatus.PENDING) {
                    return true;
                }
                return r.getStatus() == IntercompanyRecharge.RechargeStatus.APPROVED ||
                       r.getStatus() == IntercompanyRecharge.RechargeStatus.RECHARGED;
            })
            .filter(r -> r.getSettlementReference() == null) // Not already in a netting cycle
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
     * @param corporateId Corporate ID filter (not currently used - recharges are cross-entity)
     * @param includePending If true, also include PENDING (unapproved) recharges
     * @return Number of COBO recharges added
     */
    @Transactional
    public int addCoboRecharges(NettingCycle cycle, UUID corporateId, boolean includePending) {
        // Find eligible COBO recharges: COBO_COLLECTION type, APPROVED or RECHARGED status, not yet settled
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
     */
    private void applyFxConversion(NettingEntry entry, String baseCurrency) {
        if (!entry.getCurrencyCode().equals(baseCurrency)) {
            // TODO: Get actual FX rate from FxRateService
            // For now, assume 1:1 rate
            BigDecimal fxRate = BigDecimal.ONE;
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
     * Phase 4 Task 4.10: Settle cycle with full multilateral accounting.
     *
     * MULTILATERAL NETTING SETTLEMENT ACCOUNTING:
     *
     * For each participant based on their net position:
     *
     * PAY Direction (net position < 0 - entity owes more than owed):
     * | Leg | Account            | Debit | Credit | Description                    |
     * |-----|-------------------|-------|--------|--------------------------------|
     * | 1   | IHB Current Acct  | X     |        | Subsidiary pays net obligation |
     * | 2   | Netting Suspense  |       | X      | Central clearing account       |
     *
     * RECEIVE Direction (net position > 0 - entity is owed more than owes):
     * | Leg | Account            | Debit | Credit | Description                     |
     * |-----|-------------------|-------|--------|---------------------------------|
     * | 1   | Netting Suspense  | X     |        | Draw from central clearing      |
     * | 2   | IC Receivable VA  |       | X      | Reduce Treasury receivable pos  |
     *
     * The central Netting Suspense account should net to zero after all settlements.
     */
    @Transactional
    public NettingDto.SettleCycleResponse settleCycle(UUID cycleId) {
        NettingCycle cycle = cycleRepository.findByIdWithSettlements(cycleId)
                .orElseThrow(() -> new ResourceNotFoundException("Netting cycle not found: " + cycleId));

        if (cycle.getStatus() != NettingCycle.CycleStatus.APPROVED) {
            throw new BusinessException("Cycle must be APPROVED to settle");
        }

        log.info("Starting multilateral settlement for netting cycle {} with {} participants",
            cycle.getCycleReference(), cycle.getSettlements().size());

        int successCount = 0;
        int failedCount = 0;
        BigDecimal totalSettled = BigDecimal.ZERO;
        List<Transaction> settlementLedgerEntries = new ArrayList<>();
        String correlationId = "NETTING-" + cycle.getCycleReference();
        LocalDateTime now = LocalDateTime.now();
        LocalDate valueDate = LocalDate.now();

        for (NettingSettlement settlement : cycle.getSettlements()) {
            if (settlement.isNeutral()) {
                // Neutral position - no settlement needed
                String settlementRef = generateNettingSettlementRef(cycle.getCycleReference());
                settlement.markSettled(settlementRef, null);
                successCount++;
                log.debug("Entity {} has neutral position - no settlement needed", settlement.getEntityCode());
                continue;
            }

            try {
                BigDecimal settlementAmount = settlement.getSettlementAmount();
                String settlementRef = generateNettingSettlementRef(cycle.getCycleReference());

                // ===========================================================
                // SETTLEMENT ACCOUNTING BASED ON DIRECTION
                // ===========================================================

                if (settlement.needsToPay()) {
                    // PAY DIRECTION: Entity needs to pay net amount
                    // Find their IHB Current Account (or IC Payable VA)
                    VirtualAccount payerVa = findSettlementVaForEntity(settlement.getEntityId(), "PAYER");

                    if (payerVa != null) {
                        log.info("Processing PAY settlement for {}: amount={}, VA={}",
                            settlement.getEntityCode(), settlementAmount, payerVa.getVaNumber());

                        // LEG 1: DEBIT Payer's IHB Current Account (reduce their balance)
                        BigDecimal balanceBefore = payerVa.getCurrentBalance();
                        payerVa.setCurrentBalance(balanceBefore.subtract(settlementAmount));

                        // Update available balance
                        if (payerVa.getAvailableBalance() != null) {
                            payerVa.setAvailableBalance(payerVa.getAvailableBalance().subtract(settlementAmount));
                        }
                        virtualAccountRepository.save(payerVa);

                        Transaction debitTx = Transaction.builder()
                            .movementType(Transaction.MovementType.IC_SETTLEMENT)
                            .corporateId(payerVa.getCorporateId())
                            .legalEntityId(settlement.getEntityId())
                            .vaId(payerVa.getId())
                            .programId(payerVa.getProgramId())
                            .amount(settlementAmount)
                            .currencyCode(payerVa.getCurrencyCode())
                            .balanceBefore(balanceBefore)
                            .balanceAfter(payerVa.getCurrentBalance())
                            .transactionDate(now)
                            .valueDate(valueDate)
                            .referenceNumber(generateLedgerReference("NETPAY"))
                            .description("Netting settlement PAY - " + cycle.getCycleReference())
                            .channel("NETTING_SETTLEMENT")
                            .correlationId(correlationId)
                            .status(Transaction.TransactionStatus.COMPLETED)
                            .processingNotes("Multilateral netting - PAY direction for " + settlement.getEntityCode())
                            .build();
                        debitTx = ledgerTransactionRepository.save(debitTx);
                        settlementLedgerEntries.add(debitTx);

                        settlement.setSettlementVaId(payerVa.getId());
                        settlement.setSettlementTransactionId(debitTx.getId());
                        settlement.setSettlementAccount(payerVa.getVaNumber());

                        log.info("PAY settlement: {} balance {} → {}",
                            payerVa.getVaNumber(), balanceBefore, payerVa.getCurrentBalance());
                    } else {
                        log.warn("No settlement VA found for PAY entity {}", settlement.getEntityCode());
                    }

                } else if (settlement.willReceive()) {
                    // RECEIVE DIRECTION: Entity receives net amount
                    // Find their IC Receivable VA or Settlement VA
                    VirtualAccount receiverVa = findSettlementVaForEntity(settlement.getEntityId(), "RECEIVER");

                    if (receiverVa != null) {
                        log.info("Processing RECEIVE settlement for {}: amount={}, VA={}",
                            settlement.getEntityCode(), settlementAmount, receiverVa.getVaNumber());

                        // For IC Receivable VA, we DEBIT (reduce the receivable position)
                        // Because the receivable is being settled/collected
                        BigDecimal balanceBefore = receiverVa.getCurrentBalance();

                        // Check if this is an IC Receivable VA (normally has credit balance)
                        // Settlement reduces the receivable position
                        if (VirtualAccount.AccountCategory.INTERCOMPANY == receiverVa.getAccountCategory() ||
                            (receiverVa.getVaName() != null && receiverVa.getVaName().toLowerCase().contains("receivable"))) {
                            // IC Receivable VA - debit to reduce receivable position
                            receiverVa.setCurrentBalance(balanceBefore.subtract(settlementAmount));
                        } else {
                            // Regular settlement VA - credit to receive funds
                            receiverVa.setCurrentBalance(balanceBefore.add(settlementAmount));
                        }

                        // Update available balance
                        if (receiverVa.getAvailableBalance() != null) {
                            receiverVa.setAvailableBalance(receiverVa.getCurrentBalance());
                        }
                        virtualAccountRepository.save(receiverVa);

                        Transaction creditTx = Transaction.builder()
                            .movementType(Transaction.MovementType.IC_SETTLEMENT)
                            .corporateId(receiverVa.getCorporateId())
                            .legalEntityId(settlement.getEntityId())
                            .vaId(receiverVa.getId())
                            .programId(receiverVa.getProgramId())
                            .amount(settlementAmount)
                            .currencyCode(receiverVa.getCurrencyCode())
                            .balanceBefore(balanceBefore)
                            .balanceAfter(receiverVa.getCurrentBalance())
                            .transactionDate(now)
                            .valueDate(valueDate)
                            .referenceNumber(generateLedgerReference("NETRCV"))
                            .description("Netting settlement RECEIVE - " + cycle.getCycleReference())
                            .channel("NETTING_SETTLEMENT")
                            .correlationId(correlationId)
                            .status(Transaction.TransactionStatus.COMPLETED)
                            .processingNotes("Multilateral netting - RECEIVE direction for " + settlement.getEntityCode())
                            .build();
                        creditTx = ledgerTransactionRepository.save(creditTx);
                        settlementLedgerEntries.add(creditTx);

                        settlement.setSettlementVaId(receiverVa.getId());
                        settlement.setSettlementTransactionId(creditTx.getId());
                        settlement.setSettlementAccount(receiverVa.getVaNumber());

                        log.info("RECEIVE settlement: {} balance {} → {}",
                            receiverVa.getVaNumber(), balanceBefore, receiverVa.getCurrentBalance());
                    } else {
                        log.warn("No settlement VA found for RECEIVE entity {}", settlement.getEntityCode());
                    }
                }

                settlement.markSettled(settlementRef,
                    settlementLedgerEntries.isEmpty() ? null :
                    settlementLedgerEntries.get(settlementLedgerEntries.size() - 1).getId());

                successCount++;
                totalSettled = totalSettled.add(settlementAmount);

            } catch (Exception e) {
                log.error("Settlement failed for entity {}: {}", settlement.getEntityCode(), e.getMessage(), e);
                settlement.markFailed();
                failedCount++;
            }
        }

        // Update entry statuses
        for (NettingEntry entry : cycle.getEntries()) {
            if (entry.getStatus() == NettingEntry.EntryStatus.INCLUDED) {
                entry.markSettled();
            }
        }

        // Update source documents with settlement reference
        updateSourceDocuments(cycle);

        cycle.setStatus(NettingCycle.CycleStatus.SETTLED);
        cycle.setSettledAt(LocalDateTime.now());
        cycleRepository.save(cycle);

        // Post netting fees
        postNettingFees(cycle);

        log.info("Completed multilateral netting settlement {}: {} success, {} failed, {} ledger entries, total={}",
            cycle.getCycleReference(), successCount, failedCount, settlementLedgerEntries.size(), totalSettled);

        NettingDto.SettleCycleResponse response = new NettingDto.SettleCycleResponse();
        response.setCycleId(cycleId);
        response.setCycleReference(cycle.getCycleReference());
        response.setSettledAt(cycle.getSettledAt());
        response.setSuccessfulSettlements(successCount);
        response.setFailedSettlements(failedCount);
        response.setTotalSettled(totalSettled);

        return response;
    }

    /**
     * Find the appropriate VA for settlement based on entity and direction.
     *
     * For PAYER: Look for IHB Current Account (ihbParticipant=true) or main operating VA
     * For RECEIVER: Look for IC Receivable VA or Settlement VA
     */
    private VirtualAccount findSettlementVaForEntity(UUID entityId, String direction) {
        List<VirtualAccount> entityVas = virtualAccountRepository.findAll().stream()
            .filter(va -> entityId.equals(va.getOwningEntityId()))
            .filter(va -> va.getStatus() == VirtualAccount.VaStatus.ACTIVE)
            .collect(Collectors.toList());

        if (entityVas.isEmpty()) {
            log.debug("No VAs found for entity {} in {} direction", entityId, direction);
            return null;
        }

        if ("PAYER".equals(direction)) {
            // Priority 1: IHB Current Account
            Optional<VirtualAccount> ihbCurrentAccount = entityVas.stream()
                .filter(va -> Boolean.TRUE.equals(va.getIhbParticipant()))
                .findFirst();
            if (ihbCurrentAccount.isPresent()) {
                return ihbCurrentAccount.get();
            }

            // Priority 2: Main operating/settlement VA
            Optional<VirtualAccount> operatingVa = entityVas.stream()
                .filter(va -> VirtualAccount.AccountCategory.SETTLEMENT == va.getAccountCategory() ||
                             VirtualAccount.AccountCategory.TRANSACTION == va.getAccountCategory())
                .findFirst();
            if (operatingVa.isPresent()) {
                return operatingVa.get();
            }

            // Fallback: Any VA with sufficient balance
            return entityVas.stream()
                .filter(va -> va.getCurrentBalance() != null)
                .findFirst()
                .orElse(null);

        } else if ("RECEIVER".equals(direction)) {
            // Priority 1: IC Receivable VA
            Optional<VirtualAccount> icReceivableVa = entityVas.stream()
                .filter(va -> VirtualAccount.AccountCategory.INTERCOMPANY == va.getAccountCategory() ||
                             (va.getVaName() != null && va.getVaName().toLowerCase().contains("receivable")))
                .findFirst();
            if (icReceivableVa.isPresent()) {
                return icReceivableVa.get();
            }

            // Priority 2: Settlement VA
            Optional<VirtualAccount> settlementVa = entityVas.stream()
                .filter(va -> VirtualAccount.AccountCategory.SETTLEMENT == va.getAccountCategory())
                .findFirst();
            if (settlementVa.isPresent()) {
                return settlementVa.get();
            }

            // Fallback: Any active VA
            return entityVas.stream().findFirst().orElse(null);
        }

        return null;
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
        response.setIhbTransactionsAdded(result.ihbTransactionsAdded());
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
        int coboRechargesAdded,
        int ihbTransactionsAdded
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
            return payablesAdded + receivablesAdded + poboRechargesAdded + coboRechargesAdded + ihbTransactionsAdded;
        }
    }
}