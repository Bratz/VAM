package com.bank.vam.service.intercompany;

import com.bank.vam.dto.intercompany.IntercompanyDto.*;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.intercompany.IntercompanyTransaction;
import com.bank.vam.entity.payables.Payable;
import com.bank.vam.entity.pobo.IntercompanyRecharge;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.entity.treasury.NettingCycle;
import com.bank.vam.entity.treasury.NettingEntry;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.intercompany.IntercompanyTransactionRepository;
import com.bank.vam.repository.payables.PayableRepository;
import com.bank.vam.repository.pobo.IntercompanyRechargeRepository;
import com.bank.vam.repository.receivables.ReceivableRepository;
import com.bank.vam.repository.treasury.NettingCycleRepository;
import com.bank.vam.repository.treasury.NettingEntryRepository;
import com.bank.vam.service.treasury.NettingService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Phase 6: Intercompany Transaction Management Service
 * 
 * Comprehensive service for managing intercompany transactions across:
 * - POBO (Pay On Behalf Of) transactions
 * - COBO (Collect On Behalf Of) transactions  
 * - Intercompany recharges from payables/receivables
 * - Netting cycle integration
 * - Intercompany position tracking
 * 
 * Phase 6 Tasks:
 * - 6.1: Unified intercompany transaction creation
 * - 6.2: Entity pair relationship management
 * - 6.3: Automatic netting eligibility detection
 * - 6.4: Bilateral position calculation
 * - 6.5: Intercompany settlement workflows
 * - 6.6: Transfer pricing validation
 * - 6.7: Intercompany reporting & analytics
 * 
 * @see <a href="https://www.capgemini.com/wp-content/uploads/2020/12/Virtual-Acc-Management-in-Transaction-Banking.pdf">
 *      Capgemini VAM Reference</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntercompanyTransactionService {

    private final IntercompanyTransactionRepository transactionRepository;
    private final IntercompanyRechargeRepository rechargeRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final PayableRepository payableRepository;
    private final ReceivableRepository receivableRepository;
    private final NettingCycleRepository nettingCycleRepository;
    private final NettingEntryRepository nettingEntryRepository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final NettingService nettingService;
    private final com.bank.vam.config.MarketProfileProperties marketProfile;

    private static final AtomicLong transactionSequence = new AtomicLong(1);

    // ========================================================================
    // PHASE 6 TASK 6.1: UNIFIED INTERCOMPANY TRANSACTION CREATION
    // ========================================================================

    /**
     * Create intercompany transaction from any source.
     * Unified entry point for all IC transactions.
     */
    @Transactional
    public IntercompanyTransactionResult createIntercompanyTransaction(CreateIntercompanyTransactionRequest request) {
        log.info("Creating IC transaction: {} -> {}, type={}", 
            request.getSourceEntityCode(), request.getTargetEntityCode(), request.getTransactionType());

        // 1. Validate entities
        LegalEntity sourceEntity = legalEntityRepository.findByEntityCode(request.getSourceEntityCode())
            .orElseThrow(() -> new ResourceNotFoundException("Source entity not found: " + request.getSourceEntityCode()));
        
        LegalEntity targetEntity = legalEntityRepository.findByEntityCode(request.getTargetEntityCode())
            .orElseThrow(() -> new ResourceNotFoundException("Target entity not found: " + request.getTargetEntityCode()));

        // 2. Validate same corporate
        if (!sourceEntity.getCorporateId().equals(targetEntity.getCorporateId())) {
            throw new BusinessException("Intercompany transactions must be within same corporate");
        }

        // 3. Generate reference
        String transactionRef = generateTransactionReference(request.getTransactionType());

        // 4. Calculate charges if applicable
        BigDecimal charges = calculateIntercompanyCharges(
            request.getAmount(), 
            request.getTransactionType(),
            request.getChargeRate()
        );

        // 5. Create transaction
        IntercompanyTransaction transaction = IntercompanyTransaction.builder()
            .transactionRef(transactionRef)
            .transactionType(mapTransactionType(request.getTransactionType()))
            .payingEntityId(sourceEntity.getId())
            .payingEntityCode(sourceEntity.getEntityCode())
            .payingEntityName(sourceEntity.getEntityName())
            .behalfEntityId(targetEntity.getId())
            .behalfEntityCode(targetEntity.getEntityCode())
            .behalfEntityName(targetEntity.getEntityName())
            .amount(request.getAmount())
            .currencyCode(request.getCurrencyCode() != null ? request.getCurrencyCode() : marketProfile.getDefaultCurrency())
            .charges(charges)
            .netAmount(request.getAmount().add(charges))
            .originalReference(request.getSourceReference())
            .originalReferenceType(request.getSourceType())
            .originalReferenceId(request.getSourceId())
            .status(IntercompanyTransaction.TransactionStatus.PENDING)
            .description(request.getDescription())
            .createdBy(request.getCreatedBy())
            .build();

        transaction = transactionRepository.save(transaction);

        // 6. Check netting eligibility
        boolean nettingEligible = checkNettingEligibility(sourceEntity, targetEntity);

        log.info("Created IC transaction: {} between {} and {}", 
            transactionRef, sourceEntity.getEntityCode(), targetEntity.getEntityCode());

        return IntercompanyTransactionResult.builder()
            .transactionId(transaction.getId())
            .transactionRef(transactionRef)
            .sourceEntityId(sourceEntity.getId())
            .sourceEntityCode(sourceEntity.getEntityCode())
            .targetEntityId(targetEntity.getId())
            .targetEntityCode(targetEntity.getEntityCode())
            .amount(request.getAmount())
            .charges(charges)
            .netAmount(transaction.getNetAmount())
            .currency(transaction.getCurrencyCode())
            .transactionType(request.getTransactionType())
            .status("PENDING")
            .nettingEligible(nettingEligible)
            .createdAt(transaction.getCreatedAt())
            .build();
    }

    /**
     * Create IC transaction from payable (when marked as intercompany).
     */
    @Transactional
    public IntercompanyTransactionResult createFromPayable(UUID payableId) {
        Payable payable = payableRepository.findById(payableId)
            .orElseThrow(() -> new ResourceNotFoundException("Payable not found: " + payableId));

        if (!Boolean.TRUE.equals(payable.getIsIntercompany())) {
            throw new BusinessException("Payable is not marked as intercompany");
        }

        return createIntercompanyTransaction(CreateIntercompanyTransactionRequest.builder()
            .sourceEntityCode(payable.getOwningEntityCode())
            .targetEntityCode(payable.getCounterpartyEntityCode())
            .amount(payable.getNetAmount())
            .currencyCode(payable.getCurrencyCode())
            .transactionType("IC_PAYABLE")
            .sourceType("PAYABLE")
            .sourceId(payableId)
            .sourceReference(payable.getPayableNumber())
            .description("Intercompany payable: " + payable.getPayableNumber())
            .build());
    }

    /**
     * Create IC transaction from receivable (when marked as intercompany).
     */
    @Transactional
    public IntercompanyTransactionResult createFromReceivable(UUID receivableId) {
        Receivable receivable = receivableRepository.findById(receivableId)
            .orElseThrow(() -> new ResourceNotFoundException("Receivable not found: " + receivableId));

        if (!Boolean.TRUE.equals(receivable.getIsIntercompany())) {
            throw new BusinessException("Receivable is not marked as intercompany");
        }

        return createIntercompanyTransaction(CreateIntercompanyTransactionRequest.builder()
            .sourceEntityCode(receivable.getOwningEntityCode())
            .targetEntityCode(receivable.getIntercompanyEntityCode())
            .amount(receivable.getNetAmount())
            .currencyCode(receivable.getCurrencyCode())
            .transactionType("IC_RECEIVABLE")
            .sourceType("RECEIVABLE")
            .sourceId(receivableId)
            .sourceReference(receivable.getReceivableNumber())
            .description("Intercompany receivable: " + receivable.getReceivableNumber())
            .build());
    }

    // ========================================================================
    // PHASE 6 TASK 6.2: ENTITY PAIR RELATIONSHIP MANAGEMENT
    // ========================================================================

    /**
     * Get all entity pairs with intercompany activity.
     */
    @Transactional(readOnly = true)
    public List<EntityPairSummary> getEntityPairs(UUID corporateId) {
        // The corporate's IC transactions only: either side is one of its legal entities
        Set<UUID> entityIds = legalEntityRepository.findByCorporateIdOrderByHierarchyPath(corporateId).stream()
            .map(LegalEntity::getId).collect(Collectors.toSet());
        List<IntercompanyTransaction> transactions = transactionRepository.findAll().stream()
            .filter(tx -> entityIds.contains(tx.getPayingEntityId()) || entityIds.contains(tx.getBehalfEntityId()))
            .collect(Collectors.toList());
        
        // Build pair summaries
        Map<String, EntityPairSummary> pairMap = new HashMap<>();
        
        for (IntercompanyTransaction tx : transactions) {
            String pairKey = buildPairKey(tx.getPayingEntityId(), tx.getBehalfEntityId());
            
            EntityPairSummary summary = pairMap.computeIfAbsent(pairKey, k -> {
                UUID entity1Id = tx.getPayingEntityId().compareTo(tx.getBehalfEntityId()) < 0 
                    ? tx.getPayingEntityId() : tx.getBehalfEntityId();
                UUID entity2Id = tx.getPayingEntityId().compareTo(tx.getBehalfEntityId()) < 0 
                    ? tx.getBehalfEntityId() : tx.getPayingEntityId();
                
                return EntityPairSummary.builder()
                    .entity1Id(entity1Id)
                    .entity1Code(tx.getPayingEntityId().equals(entity1Id) ? tx.getPayingEntityCode() : tx.getBehalfEntityCode())
                    .entity2Id(entity2Id)
                    .entity2Code(tx.getPayingEntityId().equals(entity2Id) ? tx.getPayingEntityCode() : tx.getBehalfEntityCode())
                    .entity1OwesEntity2(BigDecimal.ZERO)
                    .entity2OwesEntity1(BigDecimal.ZERO)
                    .totalTransactions(0)
                    .pendingTransactions(0)
                    .build();
            });
            
            // Update amounts
            summary.setTotalTransactions(summary.getTotalTransactions() + 1);
            
            if (tx.getStatus() == IntercompanyTransaction.TransactionStatus.PENDING ||
                tx.getStatus() == IntercompanyTransaction.TransactionStatus.PROCESSED) {
                summary.setPendingTransactions(summary.getPendingTransactions() + 1);
                
                // Determine direction and update amounts
                if (tx.getPayingEntityId().equals(summary.getEntity1Id())) {
                    // Entity1 paid for Entity2, so Entity2 owes Entity1
                    summary.setEntity2OwesEntity1(
                        summary.getEntity2OwesEntity1().add(tx.getNetAmount() != null ? tx.getNetAmount() : BigDecimal.ZERO));
                } else {
                    // Entity2 paid for Entity1, so Entity1 owes Entity2
                    summary.setEntity1OwesEntity2(
                        summary.getEntity1OwesEntity2().add(tx.getNetAmount() != null ? tx.getNetAmount() : BigDecimal.ZERO));
                }
            }
        }
        
        // Calculate net positions
        for (EntityPairSummary summary : pairMap.values()) {
            BigDecimal netPosition = summary.getEntity2OwesEntity1().subtract(summary.getEntity1OwesEntity2());
            summary.setNetPosition(netPosition.abs());
            summary.setNetCreditor(netPosition.compareTo(BigDecimal.ZERO) >= 0 
                ? summary.getEntity1Id() : summary.getEntity2Id());
            summary.setNetDebtor(netPosition.compareTo(BigDecimal.ZERO) >= 0 
                ? summary.getEntity2Id() : summary.getEntity1Id());
        }
        
        return new ArrayList<>(pairMap.values());
    }

    /**
     * Get detailed bilateral position between two entities.
     */
    @Transactional(readOnly = true)
    public BilateralPositionDetail getBilateralPosition(UUID entity1Id, UUID entity2Id) {
        LegalEntity entity1 = legalEntityRepository.findById(entity1Id)
            .orElseThrow(() -> new ResourceNotFoundException("Entity not found: " + entity1Id));
        LegalEntity entity2 = legalEntityRepository.findById(entity2Id)
            .orElseThrow(() -> new ResourceNotFoundException("Entity not found: " + entity2Id));

        // Get all transactions between the pair
        List<IntercompanyTransaction> transactions = transactionRepository.findAll().stream()
            .filter(tx -> (tx.getPayingEntityId().equals(entity1Id) && tx.getBehalfEntityId().equals(entity2Id)) ||
                         (tx.getPayingEntityId().equals(entity2Id) && tx.getBehalfEntityId().equals(entity1Id)))
            .collect(Collectors.toList());

        // Get recharges between the pair
        List<IntercompanyRecharge> recharges = rechargeRepository.findAll().stream()
            .filter(r -> (r.getPayerEntityId().equals(entity1Id) && r.getBehalfEntityId().equals(entity2Id)) ||
                        (r.getPayerEntityId().equals(entity2Id) && r.getBehalfEntityId().equals(entity1Id)))
            .collect(Collectors.toList());

        // Calculate positions
        BigDecimal entity1OwesEntity2 = BigDecimal.ZERO;
        BigDecimal entity2OwesEntity1 = BigDecimal.ZERO;

        // From IC transactions
        for (IntercompanyTransaction tx : transactions) {
            if (tx.getStatus() != IntercompanyTransaction.TransactionStatus.SETTLED &&
                tx.getStatus() != IntercompanyTransaction.TransactionStatus.REVERSED) {
                BigDecimal amount = tx.getNetAmount() != null ? tx.getNetAmount() : BigDecimal.ZERO;
                if (tx.getPayingEntityId().equals(entity1Id)) {
                    entity2OwesEntity1 = entity2OwesEntity1.add(amount);
                } else {
                    entity1OwesEntity2 = entity1OwesEntity2.add(amount);
                }
            }
        }

        // From recharges
        for (IntercompanyRecharge recharge : recharges) {
            if (recharge.getStatus() != IntercompanyRecharge.RechargeStatus.SETTLED &&
                recharge.getStatus() != IntercompanyRecharge.RechargeStatus.CANCELLED) {
                BigDecimal amount = recharge.getTotalRecharge();
                // payerEntityId means "who is owed" for POBO_PAYMENT (they fronted the
                // money on the other entity's behalf) but "who collected" for
                // COBO_COLLECTION - the opposite economic position (CoboReceivableService
                // sets payerEntityId to the collector, not the party that's owed).
                // NettingService.addCoboRecharges() already special-cases this same
                // inversion for the netting-cycle path; this mirrors it here.
                boolean payerIsOwed = recharge.getRechargeType() != IntercompanyRecharge.RechargeType.COBO_COLLECTION;
                boolean payerIsEntity1 = recharge.getPayerEntityId().equals(entity1Id);
                if (payerIsEntity1 == payerIsOwed) {
                    entity2OwesEntity1 = entity2OwesEntity1.add(amount);
                } else {
                    entity1OwesEntity2 = entity1OwesEntity2.add(amount);
                }
            }
        }

        BigDecimal netPosition = entity2OwesEntity1.subtract(entity1OwesEntity2);

        return BilateralPositionDetail.builder()
            .entity1Id(entity1Id)
            .entity1Code(entity1.getEntityCode())
            .entity1Name(entity1.getEntityName())
            .entity2Id(entity2Id)
            .entity2Code(entity2.getEntityCode())
            .entity2Name(entity2.getEntityName())
            .entity1OwesEntity2(entity1OwesEntity2)
            .entity2OwesEntity1(entity2OwesEntity1)
            .netPosition(netPosition.abs())
            .netCreditorId(netPosition.compareTo(BigDecimal.ZERO) >= 0 ? entity1Id : entity2Id)
            .netDebtorId(netPosition.compareTo(BigDecimal.ZERO) >= 0 ? entity2Id : entity1Id)
            .transactionCount(transactions.size())
            .rechargeCount(recharges.size())
            .currency("AED")
            .asOfDate(LocalDateTime.now())
            .build();
    }

    // ========================================================================
    // PHASE 6 TASK 6.3: NETTING ELIGIBILITY
    // ========================================================================

    /**
     * Check if entity pair is eligible for netting.
     */
    @Transactional(readOnly = true)
    public boolean checkNettingEligibility(LegalEntity entity1, LegalEntity entity2) {
        // Both must be netting enabled
        if (!Boolean.TRUE.equals(entity1.getCanParticipateNetting()) || 
            !Boolean.TRUE.equals(entity2.getCanParticipateNetting())) {
            return false;
        }

        // Must be same corporate
        if (!entity1.getCorporateId().equals(entity2.getCorporateId())) {
            return false;
        }

        // Both must be active
        if (entity1.getStatus() != LegalEntity.EntityStatus.ACTIVE ||
            entity2.getStatus() != LegalEntity.EntityStatus.ACTIVE) {
            return false;
        }

        return true;
    }

    /**
     * What a netting cycle would take now for one pair of the corporate's entities (spec R5): the pair's open
     * intercompany invoices that pass netting's own checks, each invoice once (a payable linked to one of the group's
     * receivables is netted as that receivable, {@link IntercompanyGroupPositionService#countedAsReceivable}), and the
     * recharges awaiting netting that are in no cycle yet; plus the corporate's cycles still taking entries. Amounts
     * stay per item and currency (this used to sum currencies and stamp AED). The legacy IC transaction rows are not
     * offered: the Intercompany page does not show them, and Phase 3 retires them.
     */
    @Transactional(readOnly = true)
    public NettingEligibilityResult getNettingEligibleTransactions(UUID corporateId, UUID entity1Id, UUID entity2Id) {
        Map<UUID, LegalEntity> group = groupOf(corporateId);
        LegalEntity e1 = group.get(entity1Id);
        LegalEntity e2 = group.get(entity2Id);
        if (e1 == null || e2 == null || e1 == e2) {
            throw new BusinessException("Both entities must be two different entities of corporate " + corporateId);
        }
        Set<UUID> pair = Set.of(entity1Id, entity2Id);
        List<NettingItem> items = new ArrayList<>();
        String reason = checkNettingEligibility(e1, e2) ? null
            : e1.getEntityCode() + " and " + e2.getEntityCode() + " must both be active and take part in netting";
        if (reason == null) {
            List<Receivable> icReceivables = receivableRepository.findIntercompanyReceivablesByCorporate(corporateId);
            for (Receivable r : receivableRepository.findNettingEligibleReceivables(corporateId)) {
                if (between(pair, r.getOwningEntityId(), r.getIntercompanyEntityId())) {
                    items.add(new NettingItem("RECEIVABLE", r.getId(), r.getReceivableNumber(),
                        group.get(r.getIntercompanyEntityId()).getEntityCode(), group.get(r.getOwningEntityId()).getEntityCode(),
                        r.getNetAmount(), r.getCurrencyCode()));
                }
            }
            for (Payable p : payableRepository.findNettingEligiblePayables(corporateId)) {
                if (between(pair, p.getOwningEntityId(), p.getCounterpartyEntityId())
                        && !IntercompanyGroupPositionService.countedAsReceivable(p, icReceivables)) {
                    items.add(new NettingItem("PAYABLE", p.getId(), p.getPayableNumber(),
                        group.get(p.getOwningEntityId()).getEntityCode(), group.get(p.getCounterpartyEntityId()).getEntityCode(),
                        p.getNetAmount(), p.getCurrencyCode()));
                }
            }
            for (IntercompanyRecharge r : rechargeRepository.findAll()) {
                if (NettingService.awaitingNetting(r) && between(pair, r.getPayerEntityId(), r.getBehalfEntityId())
                        && !nettingEntryRepository.existsByIntercompanyRechargeId(r.getId())) {
                    boolean cobo = r.getRechargeType() == IntercompanyRecharge.RechargeType.COBO_COLLECTION;
                    UUID debtor = cobo ? r.getPayerEntityId() : r.getBehalfEntityId();
                    UUID creditor = cobo ? r.getBehalfEntityId() : r.getPayerEntityId();
                    items.add(new NettingItem("RECHARGE", r.getId(), r.getRechargeReference(),
                        group.get(debtor).getEntityCode(), group.get(creditor).getEntityCode(),
                        r.getTotalRecharge(), r.getCurrencyCode()));
                }
            }
        }

        return NettingEligibilityResult.builder()
            .entity1Id(entity1Id)
            .entity2Id(entity2Id)
            .reason(reason)
            .items(items)
            .openCycles(openCyclesOf(corporateId))
            .build();
    }

    private Map<UUID, LegalEntity> groupOf(UUID corporateId) {
        Map<UUID, LegalEntity> group = new LinkedHashMap<>();
        legalEntityRepository.findByCorporateIdOrderByHierarchyPath(corporateId).forEach(e -> group.put(e.getId(), e));
        return group;
    }

    /** Both sides are in the set (the group's or the pair's entities) and they are two different entities. */
    private static boolean between(Set<UUID> entityIds, UUID a, UUID b) {
        return a != null && b != null && !a.equals(b) && entityIds.contains(a) && entityIds.contains(b);
    }

    /**
     * The corporate's cycles still taking entries, leaving out any holding an entry whose source was already settled
     * outside netting (NettingService.entriesSettledAtSource): adding to it would hand the treasurer a cycle that pays
     * twice.
     */
    private List<OpenCycle> openCyclesOf(UUID corporateId) {
        return nettingCycleRepository.findByCorporateIdOrderByCreatedAtDesc(corporateId).stream()
            .filter(NettingCycle::acceptsEntries)
            .filter(c -> nettingService.entriesSettledAtSource(c.getId()).isEmpty())
            .sorted(Comparator.comparing(NettingCycle::getCycleReference))
            .map(c -> new OpenCycle(c.getId(), c.getCycleReference(), c.getCycleName(), c.getStatus().name(),
                c.getBaseCurrency()))
            .collect(Collectors.toList());
    }

    /**
     * Add the corporate's IC items to one of its netting cycles. Entry creation stays with {@link NettingService}, the
     * canonical owner of {@link NettingEntry} construction. Everything is checked before anything is added: the cycle
     * takes entries and is the corporate's, and each item is between two of the corporate's entities and still
     * nettable (a payable linked to a receivable is netted as the receivable).
     */
    @Transactional
    public AddToNettingResult addToNettingCycle(UUID corporateId, UUID cycleId, List<UUID> transactionIds,
                                                List<UUID> rechargeIds, List<UUID> payableIds, List<UUID> receivableIds) {
        Set<UUID> group = groupOf(corporateId).keySet();
        NettingCycle cycle = nettingCycleRepository.findById(cycleId)
            .orElseThrow(() -> new ResourceNotFoundException("Netting cycle not found: " + cycleId));
        if (!cycle.acceptsEntries()) {
            throw new BusinessException("Netting cycle is not open for additions");
        }
        if (!corporateId.equals(cycle.getCorporateId())) {
            throw new BusinessException("Netting cycle " + cycle.getCycleReference() + " is not this corporate's");
        }
        List<String> stale = nettingService.entriesSettledAtSource(cycleId);
        if (!stale.isEmpty()) {
            throw new BusinessException("Netting cycle " + cycle.getCycleReference() + " holds entries already settled "
                + "outside netting: " + String.join(", ", stale));
        }

        List<IntercompanyTransaction> txs = new ArrayList<>();
        for (UUID id : nz(transactionIds)) {
            IntercompanyTransaction tx = transactionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + id));
            if (!between(group, tx.getPayingEntityId(), tx.getBehalfEntityId())) throw notTheCorporates(tx.getTransactionRef());
            txs.add(tx);
        }
        List<IntercompanyRecharge> recharges = new ArrayList<>();
        for (UUID id : nz(rechargeIds)) {
            IntercompanyRecharge r = rechargeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Recharge not found: " + id));
            if (!between(group, r.getPayerEntityId(), r.getBehalfEntityId())) throw notTheCorporates(r.getRechargeReference());
            if (!NettingService.awaitingNetting(r) || nettingEntryRepository.existsByIntercompanyRechargeId(r.getId())) {
                throw new BusinessException("Recharge " + r.getRechargeReference() + " is not awaiting netting");
            }
            recharges.add(r);
        }
        List<Receivable> icReceivables = receivableRepository.findIntercompanyReceivablesByCorporate(corporateId);
        List<Payable> payables = new ArrayList<>();
        for (UUID id : nz(payableIds)) {
            Payable p = payableRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payable not found: " + id));
            if (!corporateId.equals(p.getCorporateId()) || !between(group, p.getOwningEntityId(), p.getCounterpartyEntityId())) {
                throw notTheCorporates(p.getPayableNumber());
            }
            if (IntercompanyGroupPositionService.countedAsReceivable(p, icReceivables)) {
                throw new BusinessException("Payable " + p.getPayableNumber()
                    + " is the other side of an intercompany receivable; add the receivable");
            }
            if (!p.canAddToNetting()) {
                throw new BusinessException("Payable " + p.getPayableNumber() + " is not eligible for netting");
            }
            payables.add(p);
        }
        List<Receivable> receivables = new ArrayList<>();
        for (UUID id : nz(receivableIds)) {
            Receivable r = receivableRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Receivable not found: " + id));
            if (!corporateId.equals(r.getCorporateId()) || !between(group, r.getOwningEntityId(), r.getIntercompanyEntityId())) {
                throw notTheCorporates(r.getReceivableNumber());
            }
            receivables.add(r);
        }

        txs.forEach(tx -> nettingService.addTransactionToCycle(cycleId, tx));
        recharges.forEach(r -> nettingService.addRechargeToCycle(cycleId, r));
        payables.forEach(p -> nettingService.addPayableToCycle(cycleId, p));
        receivables.forEach(r -> nettingService.addReceivableToCycle(cycleId, r));

        return AddToNettingResult.builder()
            .cycleId(cycleId)
            .cycleReference(cycle.getCycleReference())
            .transactionsAdded(txs.size())
            .rechargesAdded(recharges.size())
            .payablesAdded(payables.size())
            .receivablesAdded(receivables.size())
            .totalAdded(txs.size() + recharges.size() + payables.size() + receivables.size())
            .build();
    }

    private static List<UUID> nz(List<UUID> ids) {
        return ids != null ? ids : List.of();
    }

    private static BusinessException notTheCorporates(String reference) {
        return new BusinessException(reference + " is not between two entities of this corporate");
    }

    // ========================================================================
    // PHASE 6 TASK 6.6: TRANSFER PRICING
    // ========================================================================

    /**
     * Validate arm's length pricing for IC transaction.
     */
    @Transactional
    public TransferPricingValidation validateTransferPricing(UUID transactionId, String validationNotes) {
        IntercompanyTransaction tx = transactionRepository.findById(transactionId)
            .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + transactionId));

        // Simple validation - in production would check against market rates
        boolean isCompliant = true;
        List<String> findings = new ArrayList<>();

        // Check if charges are within market range
        BigDecimal chargeRate = tx.getCharges() != null && tx.getAmount().compareTo(BigDecimal.ZERO) > 0
            ? tx.getCharges().divide(tx.getAmount(), 4, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

        if (chargeRate.compareTo(new BigDecimal("0.05")) > 0) {
            findings.add("Charge rate exceeds 5% - review required");
            isCompliant = false;
        }

        // Large transaction check
        if (tx.getAmount().compareTo(new BigDecimal("1000000")) > 0) {
            findings.add("Large transaction - additional documentation recommended");
        }

        // Update transaction with validation
        // (In real implementation, would update a transfer_pricing_validated field)
        
        return TransferPricingValidation.builder()
            .transactionId(transactionId)
            .transactionRef(tx.getTransactionRef())
            .isCompliant(isCompliant)
            .findings(findings)
            .chargeRate(chargeRate)
            .validationNotes(validationNotes)
            .validatedAt(LocalDateTime.now())
            .build();
    }

    // ========================================================================
    // PHASE 6 TASK 6.7: REPORTING & ANALYTICS
    // ========================================================================

    /**
     * Get intercompany position summary for corporate.
     */
    @Transactional(readOnly = true)
    public IntercompanyPositionSummary getCorporatePositionSummary(UUID corporateId) {
        List<LegalEntity> entities = legalEntityRepository.findByCorporateIdOrderByHierarchyPath(corporateId);
        List<IntercompanyTransaction> transactions = transactionRepository.findAll();
        List<IntercompanyRecharge> recharges = rechargeRepository.findAll();

        // Filter to corporate entities
        Set<UUID> entityIds = entities.stream().map(LegalEntity::getId).collect(Collectors.toSet());

        BigDecimal totalOutstandingPayables = BigDecimal.ZERO;
        BigDecimal totalOutstandingReceivables = BigDecimal.ZERO;
        int pendingTransactions = 0;
        int pendingRecharges = 0;

        for (IntercompanyTransaction tx : transactions) {
            if (entityIds.contains(tx.getPayingEntityId()) || entityIds.contains(tx.getBehalfEntityId())) {
                if (tx.getStatus() == IntercompanyTransaction.TransactionStatus.PENDING ||
                    tx.getStatus() == IntercompanyTransaction.TransactionStatus.PROCESSED) {
                    pendingTransactions++;
                    BigDecimal amount = tx.getNetAmount() != null ? tx.getNetAmount() : BigDecimal.ZERO;
                    
                    if (tx.isPobo()) {
                        totalOutstandingPayables = totalOutstandingPayables.add(amount);
                    } else if (tx.isCobo()) {
                        totalOutstandingReceivables = totalOutstandingReceivables.add(amount);
                    }
                }
            }
        }

        for (IntercompanyRecharge recharge : recharges) {
            if (entityIds.contains(recharge.getPayerEntityId()) || entityIds.contains(recharge.getBehalfEntityId())) {
                if (recharge.getStatus() != IntercompanyRecharge.RechargeStatus.SETTLED &&
                    recharge.getStatus() != IntercompanyRecharge.RechargeStatus.CANCELLED) {
                    pendingRecharges++;
                    totalOutstandingPayables = totalOutstandingPayables.add(recharge.getTotalRecharge());
                }
            }
        }

        return IntercompanyPositionSummary.builder()
            .corporateId(corporateId)
            .totalEntities(entities.size())
            .totalOutstandingPayables(totalOutstandingPayables)
            .totalOutstandingReceivables(totalOutstandingReceivables)
            .netPosition(totalOutstandingReceivables.subtract(totalOutstandingPayables))
            .pendingTransactions(pendingTransactions)
            .pendingRecharges(pendingRecharges)
            .currency("AED")
            .asOfDate(LocalDateTime.now())
            .build();
    }

    /**
     * Get entity-level intercompany report.
     */
    @Transactional(readOnly = true)
    public EntityIntercompanyReport getEntityReport(UUID entityId, LocalDate startDate, LocalDate endDate) {
        LegalEntity entity = legalEntityRepository.findById(entityId)
            .orElseThrow(() -> new ResourceNotFoundException("Entity not found: " + entityId));

        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.atTime(23, 59, 59);

        // Get transactions for entity
        List<IntercompanyTransaction> transactions = transactionRepository.findAll().stream()
            .filter(tx -> tx.getPayingEntityId().equals(entityId) || tx.getBehalfEntityId().equals(entityId))
            .filter(tx -> tx.getCreatedAt() != null && 
                         !tx.getCreatedAt().isBefore(start) && !tx.getCreatedAt().isAfter(end))
            .collect(Collectors.toList());

        // Calculate metrics
        int totalTransactions = transactions.size();
        BigDecimal totalPaid = transactions.stream()
            .filter(tx -> tx.getPayingEntityId().equals(entityId))
            .map(tx -> tx.getNetAmount() != null ? tx.getNetAmount() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalReceived = transactions.stream()
            .filter(tx -> tx.getBehalfEntityId().equals(entityId))
            .map(tx -> tx.getNetAmount() != null ? tx.getNetAmount() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        int poboCount = (int) transactions.stream()
            .filter(IntercompanyTransaction::isPobo)
            .count();

        int coboCount = (int) transactions.stream()
            .filter(IntercompanyTransaction::isCobo)
            .count();

        return EntityIntercompanyReport.builder()
            .entityId(entityId)
            .entityCode(entity.getEntityCode())
            .entityName(entity.getEntityName())
            .reportPeriodStart(startDate)
            .reportPeriodEnd(endDate)
            .totalTransactions(totalTransactions)
            .totalPaidOnBehalf(totalPaid)
            .totalReceivedOnBehalf(totalReceived)
            .netPosition(totalReceived.subtract(totalPaid))
            .poboTransactions(poboCount)
            .coboTransactions(coboCount)
            .currency("AED")
            .generatedAt(LocalDateTime.now())
            .build();
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private String generateTransactionReference(String type) {
        String prefix = type != null && type.startsWith("IC_") ? type.substring(3, Math.min(6, type.length())) : "IC";
        String date = LocalDate.now().toString().replace("-", "");
        return String.format("%s-%s-%06d", prefix, date, transactionSequence.getAndIncrement());
    }

    private String buildPairKey(UUID id1, UUID id2) {
        return id1.compareTo(id2) < 0 
            ? id1.toString() + "_" + id2.toString()
            : id2.toString() + "_" + id1.toString();
    }

    private IntercompanyTransaction.TransactionType mapTransactionType(String type) {
        if (type == null) return IntercompanyTransaction.TransactionType.POBO;
        return switch (type.toUpperCase()) {
            case "POBO", "POBO_PAYMENT" -> IntercompanyTransaction.TransactionType.POBO;
            case "COBO", "COBO_COLLECTION" -> IntercompanyTransaction.TransactionType.COBO;
            case "IC_PAYABLE" -> IntercompanyTransaction.TransactionType.IC_PAYABLE;
            case "IC_RECEIVABLE" -> IntercompanyTransaction.TransactionType.IC_RECEIVABLE;
            case "SETTLEMENT" -> IntercompanyTransaction.TransactionType.SETTLEMENT;
            case "INTEREST" -> IntercompanyTransaction.TransactionType.INTEREST;
            case "ADJUSTMENT" -> IntercompanyTransaction.TransactionType.ADJUSTMENT;
            default -> IntercompanyTransaction.TransactionType.POBO;
        };
    }

    private BigDecimal calculateIntercompanyCharges(BigDecimal amount, String type, BigDecimal customRate) {
        BigDecimal rate = customRate != null ? customRate : new BigDecimal("0.001"); // 0.1% default
        BigDecimal charge = amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        
        // Min/max bounds
        BigDecimal minCharge = new BigDecimal("10");
        BigDecimal maxCharge = new BigDecimal("500");
        
        if (charge.compareTo(minCharge) < 0) charge = minCharge;
        if (charge.compareTo(maxCharge) > 0) charge = maxCharge;
        
        return charge;
    }

    // ========================================================================
    // DTOs
    // ========================================================================

    @Data
    @Builder
    public static class CreateIntercompanyTransactionRequest {
        private String sourceEntityCode;
        private String targetEntityCode;
        private BigDecimal amount;
        private String currencyCode;
        private String transactionType;
        private String sourceType;
        private UUID sourceId;
        private String sourceReference;
        private String description;
        private BigDecimal chargeRate;
        private String createdBy;
    }

    @Data
    @Builder
    public static class IntercompanyTransactionResult {
        private UUID transactionId;
        private String transactionRef;
        private UUID sourceEntityId;
        private String sourceEntityCode;
        private UUID targetEntityId;
        private String targetEntityCode;
        private BigDecimal amount;
        private BigDecimal charges;
        private BigDecimal netAmount;
        private String currency;
        private String transactionType;
        private String status;
        private boolean nettingEligible;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    public static class EntityPairSummary {
        private UUID entity1Id;
        private String entity1Code;
        private UUID entity2Id;
        private String entity2Code;
        private BigDecimal entity1OwesEntity2;
        private BigDecimal entity2OwesEntity1;
        private BigDecimal netPosition;
        private UUID netCreditor;
        private UUID netDebtor;
        private int totalTransactions;
        private int pendingTransactions;
    }

    @Data
    @Builder
    public static class SubsidiaryIntercompanyPosition {
        private UUID subsidiaryEntityId;
        private String subsidiaryEntityCode;
        private String subsidiaryEntityName;
        private UUID treasuryEntityId;        // the treasury centre on the other side (owns the IC mirrors)
        private String treasuryEntityCode;
        private String currencyCode;
        private BigDecimal receivableBalance; // Treasury's claim on subsidiary (POBO)
        private BigDecimal payableBalance;    // Treasury's obligation to subsidiary (COBO)
        private BigDecimal netPosition;       // receivable - payable; positive = subsidiary owes Treasury
    }

    /**
     * Per-subsidiary intercompany position, sourced directly from the real IC
     * Receivable / IC Payable VA balances - not from summing IntercompanyTransaction/
     * IntercompanyRecharge rows, which are not the source of truth for it. One row per
     * (subsidiary, currency): a subsidiary with multiple IHB Current Accounts in
     * different currencies gets one row per currency, matching how every other
     * FX-honest breakdown on this dashboard refuses to fabricate a cross-currency sum.
     */
    @Transactional(readOnly = true)
    public List<SubsidiaryIntercompanyPosition> getIntercompanyPositions(UUID corporateId) {
        List<VirtualAccount> icVas = virtualAccountRepository.findByCorporateId(corporateId).stream()
            .filter(va -> va.getMirrorAccountType() == VirtualAccount.MirrorAccountType.IC_RECEIVABLE
                       || va.getMirrorAccountType() == VirtualAccount.MirrorAccountType.IC_PAYABLE)
            .filter(va -> va.getMirrorsVaId() != null)
            .collect(Collectors.toList());

        record Key(UUID subsidiaryId, String currency) {}
        Map<Key, BigDecimal[]> totals = new LinkedHashMap<>(); // [receivable, payable]
        Map<Key, UUID> treasuryIdByKey = new LinkedHashMap<>();

        for (VirtualAccount va : icVas) {
            VirtualAccount ihbCurrentAccount = virtualAccountRepository.findById(va.getMirrorsVaId()).orElse(null);
            if (ihbCurrentAccount == null || ihbCurrentAccount.getOwningEntityId() == null) {
                continue;
            }
            Key key = new Key(ihbCurrentAccount.getOwningEntityId(), va.getCurrencyCode());
            BigDecimal[] slot = totals.computeIfAbsent(key, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal balance = va.getCurrentBalance() != null ? va.getCurrentBalance() : BigDecimal.ZERO;
            if (va.getMirrorAccountType() == VirtualAccount.MirrorAccountType.IC_RECEIVABLE) {
                slot[0] = slot[0].add(balance);
            } else {
                slot[1] = slot[1].add(balance);
            }
            treasuryIdByKey.putIfAbsent(key, va.getOwningEntityId()); // treasury owns the IC mirrors
        }

        List<SubsidiaryIntercompanyPosition> result = new ArrayList<>();
        for (Map.Entry<Key, BigDecimal[]> entry : totals.entrySet()) {
            BigDecimal receivable = entry.getValue()[0];
            BigDecimal payable = entry.getValue()[1];
            if (receivable.signum() == 0 && payable.signum() == 0) {
                continue;
            }
            LegalEntity subsidiary = legalEntityRepository.findById(entry.getKey().subsidiaryId()).orElse(null);
            UUID treasuryId = treasuryIdByKey.get(entry.getKey());
            LegalEntity treasury = treasuryId != null ? legalEntityRepository.findById(treasuryId).orElse(null) : null;
            result.add(SubsidiaryIntercompanyPosition.builder()
                .subsidiaryEntityId(entry.getKey().subsidiaryId())
                .subsidiaryEntityCode(subsidiary != null ? subsidiary.getEntityCode() : null)
                .subsidiaryEntityName(subsidiary != null ? subsidiary.getEntityName() : null)
                .treasuryEntityId(treasuryId)
                .treasuryEntityCode(treasury != null ? treasury.getEntityCode() : null)
                .currencyCode(entry.getKey().currency())
                .receivableBalance(receivable)
                .payableBalance(payable)
                .netPosition(receivable.subtract(payable))
                .build());
        }
        result.sort(Comparator.comparing((SubsidiaryIntercompanyPosition s) -> s.getNetPosition().abs()).reversed());
        return result;
    }

    @Data
    @Builder
    public static class BilateralPositionDetail {
        private UUID entity1Id;
        private String entity1Code;
        private String entity1Name;
        private UUID entity2Id;
        private String entity2Code;
        private String entity2Name;
        private BigDecimal entity1OwesEntity2;
        private BigDecimal entity2OwesEntity1;
        private BigDecimal netPosition;
        private UUID netCreditorId;
        private UUID netDebtorId;
        private int transactionCount;
        private int rechargeCount;
        private String currency;
        private LocalDateTime asOfDate;
    }

    @Data
    @Builder
    public static class NettingEligibilityResult {
        private UUID entity1Id;
        private UUID entity2Id;
        private String reason;               // why the pair can't be netted at all; null when it can
        private List<NettingItem> items;     // what a cycle would take now, one per invoice or recharge
        private List<OpenCycle> openCycles;  // the corporate's cycles still taking entries
    }

    /** One nettable item: kind RECEIVABLE, PAYABLE or RECHARGE; the debtor owes the creditor the amount. */
    public record NettingItem(String kind, UUID id, String reference, String debtorCode, String creditorCode,
                              BigDecimal amount, String currency) {}

    public record OpenCycle(UUID id, String cycleReference, String cycleName, String status, String baseCurrency) {}

    @Data
    @Builder
    public static class AddToNettingResult {
        private UUID cycleId;
        private String cycleReference;
        private int transactionsAdded;
        private int rechargesAdded;
        private int payablesAdded;
        private int receivablesAdded;
        private int totalAdded;
    }

    @Data
    @Builder
    public static class TransferPricingValidation {
        private UUID transactionId;
        private String transactionRef;
        private boolean isCompliant;
        private List<String> findings;
        private BigDecimal chargeRate;
        private String validationNotes;
        private LocalDateTime validatedAt;
    }

    @Data
    @Builder
    public static class IntercompanyPositionSummary {
        private UUID corporateId;
        private int totalEntities;
        private BigDecimal totalOutstandingPayables;
        private BigDecimal totalOutstandingReceivables;
        private BigDecimal netPosition;
        private int pendingTransactions;
        private int pendingRecharges;
        private String currency;
        private LocalDateTime asOfDate;
    }

    @Data
    @Builder
    public static class EntityIntercompanyReport {
        private UUID entityId;
        private String entityCode;
        private String entityName;
        private LocalDate reportPeriodStart;
        private LocalDate reportPeriodEnd;
        private int totalTransactions;
        private BigDecimal totalPaidOnBehalf;
        private BigDecimal totalReceivedOnBehalf;
        private BigDecimal netPosition;
        private int poboTransactions;
        private int coboTransactions;
        private String currency;
        private LocalDateTime generatedAt;
    }
}