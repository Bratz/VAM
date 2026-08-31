package com.bank.vam.service.receivables;

import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.entity.receivables.Receivable.NettingStatus;
import com.bank.vam.entity.treasury.NettingCycle;
import com.bank.vam.entity.treasury.NettingEntry;
import com.bank.vam.repository.receivables.ReceivableRepository;
import com.bank.vam.repository.treasury.NettingCycleRepository;
import com.bank.vam.repository.treasury.NettingEntryRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ReceivableNettingService - Phase 3 Service for Receivables Netting Integration
 * 
 * Integrates receivables with the unified netting engine.
 * Supports:
 * - Adding receivables to netting cycles
 * - Removing receivables from cycles
 * - Settlement marking
 * - Netting eligibility management
 * 
 * Works in conjunction with PayableNettingService for bilateral netting:
 * - Receivables represent what entities are OWED
 * - Payables represent what entities OWE
 * - Net position = Receivables - Payables per entity pair
 * 
 * @see com.bank.vam.service.treasury.NettingService for unified netting engine
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceivableNettingService {

    private final ReceivableRepository receivableRepository;
    private final NettingCycleRepository nettingCycleRepository;
    private final NettingEntryRepository nettingEntryRepository;

    // ========================================================================
    // ADD TO NETTING CYCLE
    // ========================================================================

    /**
     * Add receivable to a netting cycle.
     * 
     * Validates:
     * - Receivable is netting-eligible
     * - Receivable not already in a cycle
     * - Cycle is in DRAFT or OPEN status
     * 
     * For receivables (money OWED TO the owning entity):
     * - Payee = owningEntity (receives the funds)
     * - Payer = intercompanyEntity (owes the funds)
     * 
     * @param receivableId Receivable to add
     * @param cycleId Target netting cycle
     * @return Result with entry details
     */
    @Transactional
    public NettingAddResult addToNettingCycle(UUID receivableId, UUID cycleId) {
        log.info("Adding receivable {} to netting cycle {}", receivableId, cycleId);
        
        Receivable receivable = receivableRepository.findById(receivableId)
            .orElseThrow(() -> new IllegalArgumentException("Receivable not found: " + receivableId));
        
        NettingCycle cycle = nettingCycleRepository.findById(cycleId)
            .orElseThrow(() -> new IllegalArgumentException("Netting cycle not found: " + cycleId));
        
        // Validate receivable can be added
        if (!receivable.canAddToNetting()) {
            throw new IllegalStateException("Receivable cannot be added to netting: " +
                "status=" + receivable.getStatus() + 
                ", nettingEligible=" + receivable.getNettingEligible() +
                ", currentNettingStatus=" + receivable.getNettingStatus());
        }
        
        // Validate cycle status
        if (cycle.getStatus() != NettingCycle.CycleStatus.DRAFT && 
            cycle.getStatus() != NettingCycle.CycleStatus.OPEN) {
            throw new IllegalStateException("Cannot add to cycle in status: " + cycle.getStatus());
        }
        
        // Create netting entry (using actual entity structure)
        // For receivables: owningEntity is the payee (receives money), intercompanyEntity is the payer
        NettingEntry entry = new NettingEntry();
        entry.setCycle(cycle);
        entry.setEntryReference(generateEntryReference());
        
        // Payer = intercompany entity (who owes the money)
        entry.setPayerEntityId(receivable.getIntercompanyEntityId());
        entry.setPayerEntityCode(receivable.getIntercompanyEntityCode());
        entry.setPayerEntityName(receivable.getIntercompanyEntityName());
        
        // Payee = owning entity (who is owed the money)
        entry.setPayeeEntityId(receivable.getOwningEntityId());
        entry.setPayeeEntityCode(receivable.getOwningEntityCode());
        entry.setPayeeEntityName(receivable.getOwningEntityName());
        
        // Amounts
        entry.setGrossAmount(receivable.getOutstandingAmount());
        entry.setCurrencyCode(receivable.getCurrencyCode());
        entry.setExchangeRate(BigDecimal.ONE);
        entry.setBaseAmount(receivable.getOutstandingAmount()); // Same currency assumed
        
        // Source tracking - use INVOICE as closest match for receivables
        entry.setSourceType(NettingEntry.SourceType.INVOICE);
        entry.setSourceReference(receivable.getReceivableNumber());
        
        // Status
        entry.setStatus(NettingEntry.EntryStatus.INCLUDED);
        entry.setCreatedAt(LocalDateTime.now());
        
        entry = nettingEntryRepository.save(entry);
        
        // Update receivable
        receivable.addToNettingCycle(cycleId, cycle.getCycleReference(), entry.getId());
        receivableRepository.save(receivable);
        
        log.info("Added receivable {} to cycle {} with entry {}", 
            receivable.getReceivableNumber(), cycle.getCycleReference(), entry.getId());
        
        return NettingAddResult.builder()
            .receivableId(receivable.getId())
            .receivableNumber(receivable.getReceivableNumber())
            .cycleId(cycleId)
            .cycleReference(cycle.getCycleReference())
            .entryId(entry.getId())
            .amount(entry.getGrossAmount())
            .status("ADDED")
            .build();
    }

    /**
     * Batch add multiple receivables to a netting cycle.
     */
    @Transactional
    public List<NettingAddResult> batchAddToNettingCycle(List<UUID> receivableIds, UUID cycleId) {
        log.info("Batch adding {} receivables to netting cycle {}", receivableIds.size(), cycleId);
        
        List<NettingAddResult> results = new ArrayList<>();
        
        for (UUID receivableId : receivableIds) {
            try {
                NettingAddResult result = addToNettingCycle(receivableId, cycleId);
                results.add(result);
            } catch (Exception e) {
                log.warn("Failed to add receivable {} to cycle: {}", receivableId, e.getMessage());
                results.add(NettingAddResult.builder()
                    .receivableId(receivableId)
                    .cycleId(cycleId)
                    .status("FAILED")
                    .errorMessage(e.getMessage())
                    .build());
            }
        }
        
        return results;
    }

    // ========================================================================
    // REMOVE FROM NETTING CYCLE
    // ========================================================================

    /**
     * Remove receivable from netting cycle.
     */
    @Transactional
    public NettingRemoveResult removeFromNettingCycle(UUID receivableId) {
        log.info("Removing receivable {} from netting cycle", receivableId);
        
        Receivable receivable = receivableRepository.findById(receivableId)
            .orElseThrow(() -> new IllegalArgumentException("Receivable not found: " + receivableId));
        
        if (receivable.getNettingCycleId() == null) {
            throw new IllegalStateException("Receivable not in any netting cycle");
        }
        
        // Check cycle status (cannot remove from settled cycle)
        NettingCycle cycle = nettingCycleRepository.findById(receivable.getNettingCycleId())
            .orElse(null);
        
        if (cycle != null && cycle.getStatus() == NettingCycle.CycleStatus.SETTLED) {
            throw new IllegalStateException("Cannot remove from settled cycle");
        }
        
        UUID cycleId = receivable.getNettingCycleId();
        UUID entryId = receivable.getNettingEntryId();
        
        // Remove entry
        if (entryId != null) {
            nettingEntryRepository.deleteById(entryId);
        }
        
        // Update receivable
        receivable.removeFromNettingCycle();
        receivableRepository.save(receivable);
        
        log.info("Removed receivable {} from cycle {}", receivable.getReceivableNumber(), cycleId);
        
        return NettingRemoveResult.builder()
            .receivableId(receivableId)
            .receivableNumber(receivable.getReceivableNumber())
            .cycleId(cycleId)
            .entryId(entryId)
            .status("REMOVED")
            .build();
    }

    // ========================================================================
    // SETTLEMENT
    // ========================================================================

    /**
     * Mark receivable as settled via netting.
     * Called by NettingService when cycle is executed.
     */
    @Transactional
    public void markNettingSettled(UUID receivableId, String settlementRef) {
        log.info("Marking receivable {} as netting settled: {}", receivableId, settlementRef);
        
        Receivable receivable = receivableRepository.findById(receivableId)
            .orElseThrow(() -> new IllegalArgumentException("Receivable not found: " + receivableId));
        
        receivable.markNettingSettled(settlementRef);
        receivableRepository.save(receivable);
        
        log.info("Receivable {} marked as netted with settlement {}", 
            receivable.getReceivableNumber(), settlementRef);
    }

    /**
     * Batch mark all receivables in a cycle as settled.
     */
    @Transactional
    public int markCycleReceivablesSettled(UUID cycleId, String settlementRef) {
        log.info("Marking all receivables in cycle {} as settled", cycleId);
        
        int updated = receivableRepository.markNettingSettledByCycle(
            cycleId, settlementRef, LocalDateTime.now());
        
        log.info("Marked {} receivables as netting settled for cycle {}", updated, cycleId);
        
        return updated;
    }

    // ========================================================================
    // ELIGIBILITY MANAGEMENT
    // ========================================================================

    /**
     * Set receivable as netting-eligible.
     */
    @Transactional
    public Receivable setNettingEligible(UUID receivableId, boolean eligible) {
        Receivable receivable = receivableRepository.findById(receivableId)
            .orElseThrow(() -> new IllegalArgumentException("Receivable not found: " + receivableId));
        
        receivable.setNettingEligible(eligible);
        
        // If disabling and in cycle, remove from cycle
        if (!eligible && receivable.getNettingCycleId() != null) {
            removeFromNettingCycle(receivableId);
        }
        
        return receivableRepository.save(receivable);
    }

    // ========================================================================
    // QUERIES
    // ========================================================================

    /**
     * Get netting-eligible receivables for a corporate.
     */
    public List<NettingEligibleReceivable> getNettingEligibleReceivables(UUID corporateId) {
        List<Receivable> receivables = receivableRepository.findNettingEligibleByCorporate(corporateId);
        
        return receivables.stream()
            .map(this::toNettingEligible)
            .collect(Collectors.toList());
    }

    /**
     * Get netting-eligible receivables for entity pair.
     */
    public List<NettingEligibleReceivable> getNettingEligibleForEntityPair(
            UUID owningEntityId, UUID counterpartyEntityId) {
        List<Receivable> receivables = receivableRepository
            .findNettingEligibleIntercompanyByEntityPair(owningEntityId, counterpartyEntityId);
        
        return receivables.stream()
            .map(this::toNettingEligible)
            .collect(Collectors.toList());
    }

    /**
     * Get receivables in a netting cycle.
     */
    public List<NettingReceivableEntry> getReceivablesInCycle(UUID cycleId) {
        List<Receivable> receivables = receivableRepository.findByNettingCycleIdOrdered(cycleId);
        
        return receivables.stream()
            .map(this::toNettingEntry)
            .collect(Collectors.toList());
    }

    /**
     * Get netting summary for corporate.
     */
    public NettingSummary getNettingSummary(UUID corporateId) {
        BigDecimal eligibleAmount = receivableRepository.sumNettingEligibleByCorporate(corporateId);
        BigDecimal settledAmount = receivableRepository.sumNettingSettledByCorporate(corporateId);
        
        List<Object[]> byStatus = receivableRepository.countNettingByStatusForCorporate(corporateId);
        
        Map<String, NettingStatusCount> statusCounts = new HashMap<>();
        if (byStatus != null) {
            statusCounts = byStatus.stream()
                .collect(Collectors.toMap(
                    row -> ((NettingStatus) row[0]).name(),
                    row -> NettingStatusCount.builder()
                        .status(((NettingStatus) row[0]).name())
                        .count(((Long) row[1]).intValue())
                        .amount((BigDecimal) row[2])
                        .build()
                ));
        }
        
        return NettingSummary.builder()
            .corporateId(corporateId)
            .eligibleAmount(eligibleAmount != null ? eligibleAmount : BigDecimal.ZERO)
            .settledAmount(settledAmount != null ? settledAmount : BigDecimal.ZERO)
            .statusCounts(statusCounts)
            .build();
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private String generateEntryReference() {
        return "NE-RCV-" + System.currentTimeMillis();
    }

    private NettingEligibleReceivable toNettingEligible(Receivable r) {
        return NettingEligibleReceivable.builder()
            .id(r.getId())
            .receivableNumber(r.getReceivableNumber())
            .customerName(r.getCustomerName())
            .owningEntityId(r.getOwningEntityId())
            .owningEntityCode(r.getOwningEntityCode())
            .intercompanyEntityId(r.getIntercompanyEntityId())
            .intercompanyEntityCode(r.getIntercompanyEntityCode())
            .outstandingAmount(r.getOutstandingAmount())
            .currencyCode(r.getCurrencyCode())
            .dueDate(r.getDueDate() != null ? r.getDueDate().toString() : null)
            .isIntercompany(Boolean.TRUE.equals(r.getIsIntercompany()))
            .status(r.getStatus().name())
            .build();
    }

    private NettingReceivableEntry toNettingEntry(Receivable r) {
        return NettingReceivableEntry.builder()
            .receivableId(r.getId())
            .receivableNumber(r.getReceivableNumber())
            .customerName(r.getCustomerName())
            .owningEntityCode(r.getOwningEntityCode())
            .intercompanyEntityCode(r.getIntercompanyEntityCode())
            .amount(r.getOutstandingAmount())
            .currencyCode(r.getCurrencyCode())
            .nettingEntryId(r.getNettingEntryId())
            .nettingStatus(r.getNettingStatus() != null ? r.getNettingStatus().name() : null)
            .build();
    }

    // ========================================================================
    // DTOs
    // ========================================================================

    @Data
    @Builder
    public static class NettingAddResult {
        private UUID receivableId;
        private String receivableNumber;
        private UUID cycleId;
        private String cycleReference;
        private UUID entryId;
        private BigDecimal amount;
        private String status;
        private String errorMessage;
    }

    @Data
    @Builder
    public static class NettingRemoveResult {
        private UUID receivableId;
        private String receivableNumber;
        private UUID cycleId;
        private UUID entryId;
        private String status;
    }

    @Data
    @Builder
    public static class NettingEligibleReceivable {
        private UUID id;
        private String receivableNumber;
        private String customerName;
        private UUID owningEntityId;
        private String owningEntityCode;
        private UUID intercompanyEntityId;
        private String intercompanyEntityCode;
        private BigDecimal outstandingAmount;
        private String currencyCode;
        private String dueDate;
        private boolean isIntercompany;
        private String status;
    }

    @Data
    @Builder
    public static class NettingReceivableEntry {
        private UUID receivableId;
        private String receivableNumber;
        private String customerName;
        private String owningEntityCode;
        private String intercompanyEntityCode;
        private BigDecimal amount;
        private String currencyCode;
        private UUID nettingEntryId;
        private String nettingStatus;
    }

    @Data
    @Builder
    public static class NettingSummary {
        private UUID corporateId;
        private BigDecimal eligibleAmount;
        private BigDecimal settledAmount;
        private Map<String, NettingStatusCount> statusCounts;
    }

    @Data
    @Builder
    public static class NettingStatusCount {
        private String status;
        private int count;
        private BigDecimal amount;
    }
}