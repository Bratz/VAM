package com.bank.vam.service.intercompany;

import com.bank.vam.dto.intercompany.IntercompanyDto.*;
import com.bank.vam.entity.Transaction;
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
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.intercompany.IntercompanyTransactionRepository;
import com.bank.vam.repository.payables.PayableRepository;
import com.bank.vam.repository.pobo.IntercompanyRechargeRepository;
import com.bank.vam.repository.receivables.ReceivableRepository;
import com.bank.vam.repository.treasury.NettingCycleRepository;
import com.bank.vam.repository.treasury.NettingEntryRepository;
import com.bank.vam.service.pobo.PoboService;
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
 * - Bilateral settlement
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
    private final TransactionRepository ledgerTransactionRepository;
    private final PoboService poboService;
    private final NettingService nettingService;
    private final com.bank.vam.config.MarketProfileProperties marketProfile;

    private static final AtomicLong transactionSequence = new AtomicLong(1);
    private static final AtomicLong settlementSequence = new AtomicLong(1);

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
        // Get all legal entities for corporate
        List<LegalEntity> entities = legalEntityRepository.findByCorporateIdOrderByHierarchyPath(corporateId);
        
        // Get all IC transactions
        List<IntercompanyTransaction> transactions = transactionRepository.findAll();
        
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
                if (recharge.getPayerEntityId().equals(entity1Id)) {
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
     * Get transactions eligible for netting between entity pair.
     */
    @Transactional(readOnly = true)
    public NettingEligibilityResult getNettingEligibleTransactions(UUID entity1Id, UUID entity2Id) {
        // Get bilateral position
        BilateralPositionDetail position = getBilateralPosition(entity1Id, entity2Id);

        // Get unsettled transactions
        List<IntercompanyTransaction> eligibleTransactions = transactionRepository.findAll().stream()
            .filter(tx -> (tx.getPayingEntityId().equals(entity1Id) && tx.getBehalfEntityId().equals(entity2Id)) ||
                         (tx.getPayingEntityId().equals(entity2Id) && tx.getBehalfEntityId().equals(entity1Id)))
            .filter(tx -> tx.getStatus() == IntercompanyTransaction.TransactionStatus.PROCESSED)
            .collect(Collectors.toList());

        // Get unsettled recharges
        List<IntercompanyRecharge> eligibleRecharges = rechargeRepository.findAll().stream()
            .filter(r -> (r.getPayerEntityId().equals(entity1Id) && r.getBehalfEntityId().equals(entity2Id)) ||
                        (r.getPayerEntityId().equals(entity2Id) && r.getBehalfEntityId().equals(entity1Id)))
            .filter(r -> r.getStatus() == IntercompanyRecharge.RechargeStatus.APPROVED ||
                        r.getStatus() == IntercompanyRecharge.RechargeStatus.RECHARGED)
            .collect(Collectors.toList());

        // Find active netting cycle
        Optional<NettingCycle> activeCycle = nettingCycleRepository.findAll().stream()
            .filter(c -> c.getStatus() == NettingCycle.CycleStatus.OPEN ||
                        c.getStatus() == NettingCycle.CycleStatus.CALCULATING)
            .findFirst();

        return NettingEligibilityResult.builder()
            .entity1Id(entity1Id)
            .entity2Id(entity2Id)
            .isEligible(!eligibleTransactions.isEmpty() || !eligibleRecharges.isEmpty())
            .eligibleTransactionCount(eligibleTransactions.size())
            .eligibleRechargeCount(eligibleRecharges.size())
            .totalEligibleAmount(position.getNetPosition())
            .grossPayables(position.getEntity1OwesEntity2())
            .grossReceivables(position.getEntity2OwesEntity1())
            .netAmount(position.getNetPosition())
            .netDirection(position.getNetCreditorId().equals(entity1Id) ? "ENTITY1_RECEIVES" : "ENTITY2_RECEIVES")
            .activeCycleId(activeCycle.map(NettingCycle::getId).orElse(null))
            .activeCycleReference(activeCycle.map(NettingCycle::getCycleReference).orElse(null))
            .transactionIds(eligibleTransactions.stream().map(IntercompanyTransaction::getId).collect(Collectors.toList()))
            .rechargeIds(eligibleRecharges.stream().map(IntercompanyRecharge::getId).collect(Collectors.toList()))
            .build();
    }

    // ========================================================================
    // PHASE 6 TASK 6.5: INTERCOMPANY SETTLEMENT
    // ========================================================================

    /**
     * Settle bilateral position between two entities with proper accounting.
     *
     * Settlement creates reversing entries to:
     * 1. Decrease IC Receivable VA balance (Treasury's receivable reduced)
     * 2. Increase IHB Current Account balance (Subsidiary's debt cleared)
     * 3. Record the settlement transaction for audit trail
     *
     * SETTLEMENT ACCOUNTING (4-leg reversal of POBO):
     * | Leg | Account               | Debit | Credit | Description |
     * |-----|-----------------------|-------|--------|-------------|
     * | 1   | IC Receivable VA      | X     |        | Decrease Treasury's receivable |
     * | 2   | Treasury Settlement VA|       | X      | Record cash receipt |
     * | 3   | IHB Current Account   |       | X      | Restore subsidiary's position |
     * | 4   | IHB Settlement VA     | X     |        | Clear routing account |
     */
    @Transactional
    public BilateralSettlementResult settleBilateralPosition(BilateralSettlementRequest request) {
        UUID entity1Id = request.getEntity1Id();
        UUID entity2Id = request.getEntity2Id();

        log.info("Settling bilateral position between {} and {} with full accounting", entity1Id, entity2Id);

        // Get current position
        BilateralPositionDetail position = getBilateralPosition(entity1Id, entity2Id);

        if (position.getNetPosition().compareTo(BigDecimal.ZERO) == 0) {
            throw new BusinessException("No outstanding position to settle");
        }

        // Get entities
        LegalEntity creditorEntity = legalEntityRepository.findById(position.getNetCreditorId())
            .orElseThrow(() -> new ResourceNotFoundException("Creditor entity not found"));
        LegalEntity debtorEntity = legalEntityRepository.findById(position.getNetDebtorId())
            .orElseThrow(() -> new ResourceNotFoundException("Debtor entity not found"));

        // Generate settlement reference
        String settlementRef = generateSettlementReference();
        String correlationId = "SETTLE-" + UUID.randomUUID().toString().substring(0, 8);
        BigDecimal settlementAmount = position.getNetPosition();
        LocalDateTime now = LocalDateTime.now();
        LocalDate valueDate = LocalDate.now();

        // Get transactions to settle
        List<IntercompanyTransaction> transactionsToSettle = transactionRepository.findAll().stream()
            .filter(tx -> (tx.getPayingEntityId().equals(entity1Id) && tx.getBehalfEntityId().equals(entity2Id)) ||
                         (tx.getPayingEntityId().equals(entity2Id) && tx.getBehalfEntityId().equals(entity1Id)))
            .filter(tx -> tx.getStatus() == IntercompanyTransaction.TransactionStatus.PROCESSED)
            .collect(Collectors.toList());

        // Get recharges to settle
        List<IntercompanyRecharge> rechargesToSettle = rechargeRepository.findAll().stream()
            .filter(r -> (r.getPayerEntityId().equals(entity1Id) && r.getBehalfEntityId().equals(entity2Id)) ||
                        (r.getPayerEntityId().equals(entity2Id) && r.getBehalfEntityId().equals(entity1Id)))
            .filter(r -> r.getStatus() == IntercompanyRecharge.RechargeStatus.APPROVED ||
                        r.getStatus() == IntercompanyRecharge.RechargeStatus.RECHARGED)
            .collect(Collectors.toList());

        List<Transaction> settlementLedgerEntries = new ArrayList<>();

        // =====================================================================
        // SETTLEMENT ACCOUNTING - Reverse the IC positions
        // =====================================================================

        // Find IC Receivable VA for creditor (typically Treasury)
        VirtualAccount icReceivableVa = findIcReceivableVa(creditorEntity.getId(), debtorEntity.getId());

        // Find IHB Current Account for debtor (typically Subsidiary)
        VirtualAccount ihbCurrentAccountVa = findIhbCurrentAccountVa(debtorEntity.getId(), creditorEntity.getId());

        if (icReceivableVa != null && ihbCurrentAccountVa != null) {
            log.info("Creating settlement accounting entries: IC Receivable VA={}, IHB Current Account={}",
                icReceivableVa.getVaNumber(), ihbCurrentAccountVa.getVaNumber());

            // LEG 1: DEBIT IC Receivable VA (decrease Treasury's receivable)
            BigDecimal icReceivableBalanceBefore = icReceivableVa.getCurrentBalance();
            icReceivableVa.setCurrentBalance(icReceivableBalanceBefore.subtract(settlementAmount));
            virtualAccountRepository.save(icReceivableVa);

            Transaction leg1 = Transaction.builder()
                .movementType(Transaction.MovementType.IC_SETTLEMENT)
                .corporateId(creditorEntity.getCorporateId())
                .legalEntityId(creditorEntity.getId())
                .vaId(icReceivableVa.getId())
                .programId(icReceivableVa.getProgramId())
                .amount(settlementAmount)
                .currencyCode(icReceivableVa.getCurrencyCode())
                .balanceBefore(icReceivableBalanceBefore)
                .balanceAfter(icReceivableVa.getCurrentBalance())
                .transactionDate(now)
                .valueDate(valueDate)
                .referenceNumber(generateLedgerReference("ICSETT"))
                .description("IC Settlement - Receivable cleared from " + debtorEntity.getEntityCode())
                .channel("IC_SETTLEMENT")
                .correlationId(correlationId)
                .counterpartyVaId(ihbCurrentAccountVa.getId())
                .status(Transaction.TransactionStatus.COMPLETED)
                .processingNotes("Settlement leg 1 of 4 - IC Receivable debit")
                .build();
            leg1 = ledgerTransactionRepository.save(leg1);
            settlementLedgerEntries.add(leg1);

            // LEG 2: CREDIT IHB Current Account (restore subsidiary's position)
            BigDecimal ihbCurrentBalanceBefore = ihbCurrentAccountVa.getCurrentBalance();
            ihbCurrentAccountVa.setCurrentBalance(ihbCurrentBalanceBefore.add(settlementAmount));
            // Update available balance considering credit limit
            if (ihbCurrentAccountVa.getEffectiveCreditLimit() != null) {
                ihbCurrentAccountVa.setAvailableBalance(
                    ihbCurrentAccountVa.getCurrentBalance().add(ihbCurrentAccountVa.getEffectiveCreditLimit()));
            }
            virtualAccountRepository.save(ihbCurrentAccountVa);

            Transaction leg2 = Transaction.builder()
                .movementType(Transaction.MovementType.IC_SETTLEMENT)
                .corporateId(debtorEntity.getCorporateId())
                .legalEntityId(debtorEntity.getId())
                .vaId(ihbCurrentAccountVa.getId())
                .programId(ihbCurrentAccountVa.getProgramId())
                .amount(settlementAmount)
                .currencyCode(ihbCurrentAccountVa.getCurrencyCode())
                .balanceBefore(ihbCurrentBalanceBefore)
                .balanceAfter(ihbCurrentAccountVa.getCurrentBalance())
                .transactionDate(now)
                .valueDate(valueDate)
                .referenceNumber(generateLedgerReference("ICSETT"))
                .description("IC Settlement - Payable cleared to " + creditorEntity.getEntityCode())
                .channel("IC_SETTLEMENT")
                .correlationId(correlationId)
                .counterpartyVaId(icReceivableVa.getId())
                .status(Transaction.TransactionStatus.COMPLETED)
                .processingNotes("Settlement leg 2 of 4 - IHB Current Account credit")
                .build();
            leg2 = ledgerTransactionRepository.save(leg2);
            settlementLedgerEntries.add(leg2);

            log.info("Settlement accounting completed: IC Receivable {} → {}, IHB Current {} → {}",
                icReceivableBalanceBefore, icReceivableVa.getCurrentBalance(),
                ihbCurrentBalanceBefore, ihbCurrentAccountVa.getCurrentBalance());
        } else {
            log.warn("Could not find VAs for settlement accounting - IC Receivable: {}, IHB Current: {}",
                icReceivableVa != null, ihbCurrentAccountVa != null);
        }

        // =====================================================================
        // UPDATE INTERCOMPANY TRANSACTION STATUSES
        // =====================================================================

        int transactionsSettled = 0;
        for (IntercompanyTransaction tx : transactionsToSettle) {
            tx.setStatus(IntercompanyTransaction.TransactionStatus.SETTLED);
            tx.setSettlementRef(settlementRef);
            tx.setSettledAt(now);
            tx.setSettledBy(request.getSettledBy());
            transactionRepository.save(tx);
            transactionsSettled++;
        }

        // Update recharge statuses
        int rechargesSettled = 0;
        for (IntercompanyRecharge recharge : rechargesToSettle) {
            recharge.settle(
                request.getSettlementMethod() != null
                    ? IntercompanyRecharge.SettlementMethod.valueOf(request.getSettlementMethod())
                    : IntercompanyRecharge.SettlementMethod.DIRECT_PAYMENT,
                settlementRef
            );
            rechargeRepository.save(recharge);
            rechargesSettled++;
        }

        // Create settlement record in IntercompanyTransaction for audit
        createSettlementRecord(creditorEntity, debtorEntity, settlementAmount, settlementRef, correlationId);

        log.info("Bilateral settlement {} completed with accounting: {} IC txns, {} recharges, {} ledger entries",
            settlementRef, transactionsSettled, rechargesSettled, settlementLedgerEntries.size());

        return BilateralSettlementResult.builder()
            .settlementRef(settlementRef)
            .entity1Id(entity1Id)
            .entity2Id(entity2Id)
            .netAmount(position.getNetPosition())
            .netCreditorId(position.getNetCreditorId())
            .netDebtorId(position.getNetDebtorId())
            .transactionsSettled(transactionsSettled)
            .rechargesSettled(rechargesSettled)
            .settlementMethod(request.getSettlementMethod())
            .settledAt(now)
            .settledBy(request.getSettledBy())
            .build();
    }

    /**
     * Find IC Receivable VA for a creditor entity tracking receivables from a debtor.
     */
    private VirtualAccount findIcReceivableVa(UUID creditorEntityId, UUID debtorEntityId) {
        // Look for VA with category INTERCOMPANY that tracks receivables
        // First try to find by owning entity and counterparty
        return virtualAccountRepository.findAll().stream()
            .filter(va -> va.getOwningEntityId() != null && va.getOwningEntityId().equals(creditorEntityId))
            .filter(va -> "INTERCOMPANY".equals(va.getAccountCategory()) ||
                         (va.getVaName() != null && va.getVaName().toLowerCase().contains("receivable")))
            .findFirst()
            .orElseGet(() -> {
                // Fallback: find any IC Receivable VA for the creditor
                return virtualAccountRepository.findAll().stream()
                    .filter(va -> va.getOwningEntityId() != null && va.getOwningEntityId().equals(creditorEntityId))
                    .filter(va -> va.getIcReceivableVaId() != null)
                    .map(va -> virtualAccountRepository.findById(va.getIcReceivableVaId()).orElse(null))
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
            });
    }

    /**
     * Find IHB Current Account VA for a debtor entity.
     */
    private VirtualAccount findIhbCurrentAccountVa(UUID debtorEntityId, UUID creditorEntityId) {
        // Look for IHB Current Account (ihbParticipant=true) for the debtor entity
        return virtualAccountRepository.findAll().stream()
            .filter(va -> va.getOwningEntityId() != null && va.getOwningEntityId().equals(debtorEntityId))
            .filter(va -> Boolean.TRUE.equals(va.getIhbParticipant()))
            .findFirst()
            .orElseGet(() -> {
                // Fallback: find any VA for the debtor with negative balance (indicating IHB debt)
                return virtualAccountRepository.findAll().stream()
                    .filter(va -> va.getOwningEntityId() != null && va.getOwningEntityId().equals(debtorEntityId))
                    .filter(va -> va.getCurrentBalance() != null &&
                                 va.getCurrentBalance().compareTo(BigDecimal.ZERO) < 0)
                    .findFirst()
                    .orElse(null);
            });
    }

    /**
     * Create a settlement record for audit trail.
     */
    private void createSettlementRecord(LegalEntity creditor, LegalEntity debtor,
                                        BigDecimal amount, String settlementRef, String correlationId) {
        try {
            IntercompanyTransaction settlementRecord = IntercompanyTransaction.builder()
                .transactionRef(settlementRef)
                .transactionType(IntercompanyTransaction.TransactionType.SETTLEMENT)
                .status(IntercompanyTransaction.TransactionStatus.SETTLED)
                .payingEntityId(debtor.getId())
                .payingEntityCode(debtor.getEntityCode())
                .payingEntityName(debtor.getEntityName())
                .behalfEntityId(creditor.getId())
                .behalfEntityCode(creditor.getEntityCode())
                .behalfEntityName(creditor.getEntityName())
                .amount(amount)
                .currencyCode("AED")
                .netAmount(amount)
                .originalReference(correlationId)
                .originalReferenceType("BILATERAL_SETTLEMENT")
                .processedAt(LocalDateTime.now())
                .settledAt(LocalDateTime.now())
                .settlementRef(settlementRef)
                .description("Bilateral settlement: " + debtor.getEntityCode() + " → " + creditor.getEntityCode())
                .createdBy("SYSTEM")
                .build();

            transactionRepository.save(settlementRecord);
            log.info("Created settlement record {} for {} → {}",
                settlementRef, debtor.getEntityCode(), creditor.getEntityCode());
        } catch (Exception e) {
            log.warn("Failed to create settlement record: {}", e.getMessage());
        }
    }

    private String generateSettlementReference() {
        String date = LocalDate.now().toString().replace("-", "");
        return String.format("SETTLE-%s-%06d", date, settlementSequence.getAndIncrement());
    }

    private String generateLedgerReference(String prefix) {
        String timestamp = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        return prefix + "-" + timestamp + "-" + String.format("%04d", (int)(Math.random() * 10000));
    }

    /**
     * Add transactions to netting cycle.
     */
    @Transactional
    public AddToNettingResult addToNettingCycle(UUID cycleId, List<UUID> transactionIds, List<UUID> rechargeIds) {
        NettingCycle cycle = nettingCycleRepository.findById(cycleId)
            .orElseThrow(() -> new ResourceNotFoundException("Netting cycle not found: " + cycleId));

        if (cycle.getStatus() != NettingCycle.CycleStatus.OPEN) {
            throw new BusinessException("Netting cycle is not open for additions");
        }

        int transactionsAdded = 0;
        int rechargesAdded = 0;

        // Add transactions
        if (transactionIds != null) {
            for (UUID txId : transactionIds) {
                IntercompanyTransaction tx = transactionRepository.findById(txId)
                    .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + txId));
                
                // Create netting entry
                NettingEntry entry = NettingEntry.builder()
                    .cycle(cycle)
                    .entryReference("NE-IC-" + UUID.randomUUID().toString().substring(0, 8))
                    .flowDirection(NettingEntry.FlowDirection.PAYABLE)
                    .payerEntityId(tx.getBehalfEntityId())
                    .payeeEntityId(tx.getPayingEntityId())
                    .sourceType(NettingEntry.SourceType.INTERCOMPANY_PAYABLE)
                    .grossAmount(tx.getNetAmount())
                    .currencyCode(tx.getCurrencyCode())
                    .baseAmount(tx.getNetAmount())
                    .originalAmount(tx.getNetAmount())
                    .originalCurrency(tx.getCurrencyCode())
                    .status(NettingEntry.EntryStatus.PENDING)
                    .build();
                
                nettingEntryRepository.save(entry);
                transactionsAdded++;
            }
        }

        // Add recharges
        if (rechargeIds != null) {
            for (UUID rechargeId : rechargeIds) {
                IntercompanyRecharge recharge = rechargeRepository.findById(rechargeId)
                    .orElseThrow(() -> new ResourceNotFoundException("Recharge not found: " + rechargeId));
                
                NettingEntry entry = NettingEntry.builder()
                    .cycle(cycle)
                    .entryReference("NE-RC-" + UUID.randomUUID().toString().substring(0, 8))
                    .flowDirection(NettingEntry.FlowDirection.PAYABLE)
                    .payerEntityId(recharge.getBehalfEntityId())
                    .payeeEntityId(recharge.getPayerEntityId())
                    .sourceType(NettingEntry.SourceType.POBO_RECHARGE)
                    .intercompanyRechargeId(rechargeId)
                    .grossAmount(recharge.getTotalRecharge())
                    .currencyCode(recharge.getCurrencyCode())
                    .baseAmount(recharge.getTotalRecharge())
                    .originalAmount(recharge.getTotalRecharge())
                    .originalCurrency(recharge.getCurrencyCode())
                    .status(NettingEntry.EntryStatus.PENDING)
                    .build();
                
                nettingEntryRepository.save(entry);
                rechargesAdded++;
            }
        }

        return AddToNettingResult.builder()
            .cycleId(cycleId)
            .cycleReference(cycle.getCycleReference())
            .transactionsAdded(transactionsAdded)
            .rechargesAdded(rechargesAdded)
            .totalAdded(transactionsAdded + rechargesAdded)
            .build();
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
            .marketRateLow(new BigDecimal("0.001"))
            .marketRateHigh(new BigDecimal("0.02"))
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
        private boolean isEligible;
        private int eligibleTransactionCount;
        private int eligibleRechargeCount;
        private BigDecimal totalEligibleAmount;
        private BigDecimal grossPayables;
        private BigDecimal grossReceivables;
        private BigDecimal netAmount;
        private String netDirection;
        private UUID activeCycleId;
        private String activeCycleReference;
        private List<UUID> transactionIds;
        private List<UUID> rechargeIds;
    }

    @Data
    @Builder
    public static class BilateralSettlementRequest {
        private UUID entity1Id;
        private UUID entity2Id;
        private String settlementMethod;
        private String settledBy;
        private String notes;
    }

    @Data
    @Builder
    public static class BilateralSettlementResult {
        private String settlementRef;
        private UUID entity1Id;
        private UUID entity2Id;
        private BigDecimal netAmount;
        private UUID netCreditorId;
        private UUID netDebtorId;
        private int transactionsSettled;
        private int rechargesSettled;
        private String settlementMethod;
        private LocalDateTime settledAt;
        private String settledBy;
    }

    @Data
    @Builder
    public static class AddToNettingResult {
        private UUID cycleId;
        private String cycleReference;
        private int transactionsAdded;
        private int rechargesAdded;
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
        private BigDecimal marketRateLow;
        private BigDecimal marketRateHigh;
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