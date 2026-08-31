package com.bank.vam.service.receivables;

import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.pobo.IntercompanyRecharge;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.pobo.IntercompanyRechargeRepository;
import com.bank.vam.repository.receivables.ReceivableRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * IntercompanyRechargeReceivableService - Phase 3 Service for COBO Recharge Linking
 * 
 * Manages the relationship between:
 * - Receivables with COBO collection
 * - IntercompanyRecharge records created for fund forwarding
 * - IHB deposits for subsidiary crediting
 * 
 * WORKFLOW:
 * 1. Treasury collects on behalf of subsidiary (COBO)
 * 2. Create IntercompanyRecharge: Treasury → Subsidiary
 * 3. Link recharge to source receivable
 * 4. Optionally create IHB deposit for subsidiary
 * 
 * NOTE: This service adapts the POBO-oriented IntercompanyRecharge entity
 * for COBO receivables use case. The recharge represents funds that treasury
 * collected on behalf of subsidiary and needs to forward.
 * 
 * @see CoboReceivableService for COBO execution
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntercompanyRechargeReceivableService {

    private final ReceivableRepository receivableRepository;
    private final IntercompanyRechargeRepository rechargeRepository;
    private final LegalEntityRepository legalEntityRepository;

    // ========================================================================
    // RECHARGE CREATION FOR COBO
    // ========================================================================

    /**
     * Create intercompany recharge when COBO collection is executed.
     * 
     * Flow:
     * 1. Treasury collected from customer on behalf of subsidiary
     * 2. Create recharge: Treasury (payer) owes Subsidiary (behalf)
     * 3. Link recharge to source receivable via originalPayableId field
     * 4. Update receivable with recharge ID
     * 
     * @param request Recharge creation request
     * @return Created recharge with receivable link
     */
    @Transactional
    public CoboRechargeResult createCoboRecharge(CoboRechargeRequest request) {
        log.info("Creating COBO recharge for receivable {}", request.getReceivableId());
        
        // Get source receivable
        Receivable receivable = receivableRepository.findById(request.getReceivableId())
            .orElseThrow(() -> new IllegalArgumentException("Receivable not found: " + request.getReceivableId()));
        
        // Validate receivable is COBO
        if (!Boolean.TRUE.equals(receivable.getIsCobo())) {
            throw new IllegalStateException("Receivable is not a COBO receivable");
        }
        
        // Get entities
        LegalEntity treasuryEntity = legalEntityRepository.findById(request.getTreasuryEntityId())
            .orElseThrow(() -> new IllegalArgumentException("Treasury entity not found"));
        
        LegalEntity subsidiaryEntity = legalEntityRepository.findById(receivable.getOwningEntityId())
            .orElseThrow(() -> new IllegalArgumentException("Subsidiary entity not found"));
        
        // Create intercompany recharge using actual entity structure
        // For COBO: Treasury (payer) collected and needs to forward to Subsidiary (behalf)
        IntercompanyRecharge recharge = IntercompanyRecharge.builder()
            .rechargeReference(generateRechargeReference())
            // Payer = Treasury (who collected the funds)
            .payerEntityId(treasuryEntity.getId())
            .payerEntityCode(treasuryEntity.getEntityCode())
            .payerEntityName(treasuryEntity.getEntityName())
            // Behalf = Subsidiary (who the funds belong to)
            .behalfEntityId(subsidiaryEntity.getId())
            .behalfEntityCode(subsidiaryEntity.getEntityCode())
            .behalfEntityName(subsidiaryEntity.getEntityName())
            // Link to receivable via originalPayableId (reusing for receivable link)
            .originalPayableId(receivable.getId())
            .originalPaymentReference(receivable.getReceivableNumber())
            // Amounts
            .originalAmount(request.getAmount())
            .rechargeAmount(request.getAmount())
            .currencyCode(receivable.getCurrencyCode())
            .status(IntercompanyRecharge.RechargeStatus.PENDING)
            .build();
        
        recharge.calculateTotalRecharge();
        recharge = rechargeRepository.save(recharge);
        
        // Update receivable with recharge link
        receivable.setCoboRechargeId(recharge.getId());
        receivableRepository.save(receivable);
        
        log.info("Created COBO recharge {} for receivable {}", 
            recharge.getRechargeReference(), receivable.getReceivableNumber());
        
        return CoboRechargeResult.builder()
            .rechargeId(recharge.getId())
            .rechargeNumber(recharge.getRechargeReference())
            .receivableId(receivable.getId())
            .receivableNumber(receivable.getReceivableNumber())
            .treasuryEntityId(treasuryEntity.getId())
            .treasuryEntityCode(treasuryEntity.getEntityCode())
            .subsidiaryEntityId(subsidiaryEntity.getId())
            .subsidiaryEntityCode(subsidiaryEntity.getEntityCode())
            .amount(request.getAmount())
            .currencyCode(receivable.getCurrencyCode())
            .status(recharge.getStatus().name())
            .createdAt(LocalDateTime.now())
            .build();
    }

    /**
     * Settle COBO recharge (mark as completed after fund transfer).
     */
    @Transactional
    public CoboRechargeResult settleCoboRecharge(UUID rechargeId, String transactionRef) {
        log.info("Settling COBO recharge {}", rechargeId);
        
        IntercompanyRecharge recharge = rechargeRepository.findById(rechargeId)
            .orElseThrow(() -> new IllegalArgumentException("Recharge not found: " + rechargeId));
        
        // Use the entity's settle method
        recharge.settle(IntercompanyRecharge.SettlementMethod.DIRECT_PAYMENT, transactionRef);
        recharge = rechargeRepository.save(recharge);
        
        log.info("COBO recharge {} settled with ref {}", recharge.getRechargeReference(), transactionRef);
        
        return CoboRechargeResult.builder()
            .rechargeId(recharge.getId())
            .rechargeNumber(recharge.getRechargeReference())
            .receivableId(recharge.getOriginalPayableId()) // Reused for receivable ID
            .amount(recharge.getRechargeAmount())
            .currencyCode(recharge.getCurrencyCode())
            .status(recharge.getStatus().name())
            .settlementRef(transactionRef)
            .settledAt(recharge.getSettlementDate() != null ? 
                recharge.getSettlementDate().atStartOfDay() : LocalDateTime.now())
            .build();
    }

    // ========================================================================
    // INTERCOMPANY RECEIVABLE CREATION
    // ========================================================================

    /**
     * Create intercompany receivable (sale to group entity).
     * Automatically creates matching payable on counterparty side (if linked).
     */
    @Transactional
    public IntercompanyReceivableResult createIntercompanyReceivable(IntercompanyReceivableRequest request) {
        log.info("Creating intercompany receivable from {} to {}", 
            request.getOwningEntityCode(), request.getCounterpartyEntityCode());
        
        // Get entities
        LegalEntity owningEntity = legalEntityRepository.findById(request.getOwningEntityId())
            .orElseThrow(() -> new IllegalArgumentException("Owning entity not found"));
        
        LegalEntity counterpartyEntity = legalEntityRepository.findById(request.getCounterpartyEntityId())
            .orElseThrow(() -> new IllegalArgumentException("Counterparty entity not found"));
        
        // Create receivable
        Receivable receivable = Receivable.createIntercompanyReceivable(
            request.getCorporateId(),
            owningEntity.getId(),
            owningEntity.getEntityCode(),
            owningEntity.getEntityName(),
            counterpartyEntity.getId(),
            counterpartyEntity.getEntityCode(),
            counterpartyEntity.getEntityName(),
            request.getCustomerPartyId(),
            request.getDescription(),
            request.getAmount(),
            request.getCurrencyCode()
        );
        
        receivable.setReceivableNumber(generateReceivableNumber());
        receivable.setExternalReference(request.getExternalReference());
        receivable.setDueDate(request.getDueDate());
        receivable.setCreatedBy(request.getCreatedBy());
        
        receivable = receivableRepository.save(receivable);
        
        log.info("Created intercompany receivable {} from {} to {}", 
            receivable.getReceivableNumber(), owningEntity.getEntityCode(), counterpartyEntity.getEntityCode());
        
        return IntercompanyReceivableResult.builder()
            .receivableId(receivable.getId())
            .receivableNumber(receivable.getReceivableNumber())
            .owningEntityId(owningEntity.getId())
            .owningEntityCode(owningEntity.getEntityCode())
            .counterpartyEntityId(counterpartyEntity.getId())
            .counterpartyEntityCode(counterpartyEntity.getEntityCode())
            .amount(receivable.getNetAmount())
            .currencyCode(receivable.getCurrencyCode())
            .status(receivable.getStatus().name())
            .nettingEligible(receivable.getNettingEligible())
            .createdAt(receivable.getCreatedAt())
            .build();
    }

    /**
     * Link receivable to counterparty payable (bidirectional IC transaction).
     */
    @Transactional
    public void linkCounterpartyPayable(UUID receivableId, UUID payableId) {
        log.info("Linking receivable {} to payable {}", receivableId, payableId);
        
        receivableRepository.linkCounterpartyPayable(receivableId, payableId);
        
        log.info("Linked receivable {} to counterparty payable {}", receivableId, payableId);
    }

    // ========================================================================
    // QUERIES
    // ========================================================================

    /**
     * Find recharges by source receivable.
     * Note: Uses originalPayableId field which stores receivable ID for COBO recharges.
     */
    public List<IntercompanyRecharge> findRechargesByReceivable(UUID receivableId) {
        return rechargeRepository.findByOriginalPayableId(receivableId);
    }

    /**
     * Find pending COBO recharges (not yet settled).
     */
    public List<IntercompanyRecharge> findPendingCoboRecharges(UUID corporateId) {
        return rechargeRepository.findByStatus(IntercompanyRecharge.RechargeStatus.PENDING)
            .stream()
            .collect(Collectors.toList());
    }

    /**
     * Get COBO recharge summary by subsidiary.
     */
    public List<CoboRechargeSummary> getCoboRechargeSummaryBySubsidiary(UUID corporateId) {
        List<IntercompanyRecharge> recharges = rechargeRepository.findAll();
        
        Map<UUID, List<IntercompanyRecharge>> bySubsidiary = recharges.stream()
            .collect(Collectors.groupingBy(IntercompanyRecharge::getBehalfEntityId));
        
        return bySubsidiary.entrySet().stream()
            .map(entry -> {
                List<IntercompanyRecharge> entityRecharges = entry.getValue();
                IntercompanyRecharge first = entityRecharges.get(0);
                
                BigDecimal pendingAmount = entityRecharges.stream()
                    .filter(r -> r.getStatus() == IntercompanyRecharge.RechargeStatus.PENDING)
                    .map(IntercompanyRecharge::getRechargeAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                
                BigDecimal settledAmount = entityRecharges.stream()
                    .filter(r -> r.getStatus() == IntercompanyRecharge.RechargeStatus.SETTLED)
                    .map(IntercompanyRecharge::getRechargeAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                
                return CoboRechargeSummary.builder()
                    .subsidiaryEntityId(entry.getKey())
                    .subsidiaryEntityCode(first.getBehalfEntityCode())
                    .subsidiaryEntityName(first.getBehalfEntityName())
                    .totalRecharges(entityRecharges.size())
                    .pendingCount((int) entityRecharges.stream()
                        .filter(r -> r.getStatus() == IntercompanyRecharge.RechargeStatus.PENDING).count())
                    .settledCount((int) entityRecharges.stream()
                        .filter(r -> r.getStatus() == IntercompanyRecharge.RechargeStatus.SETTLED).count())
                    .pendingAmount(pendingAmount)
                    .settledAmount(settledAmount)
                    .build();
            })
            .collect(Collectors.toList());
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private String generateRechargeReference() {
        return "RCHG-COBO-" + System.currentTimeMillis();
    }

    private String generateReceivableNumber() {
        return "RCV-IC-" + LocalDate.now().getYear() + "-" + 
            String.format("%06d", System.currentTimeMillis() % 1000000);
    }

    // ========================================================================
    // DTOs
    // ========================================================================

    @Data
    @Builder
    public static class CoboRechargeRequest {
        private UUID receivableId;
        private UUID treasuryEntityId;
        private BigDecimal amount;
        private String description;
    }

    @Data
    @Builder
    public static class CoboRechargeResult {
        private UUID rechargeId;
        private String rechargeNumber;
        private UUID receivableId;
        private String receivableNumber;
        private UUID treasuryEntityId;
        private String treasuryEntityCode;
        private UUID subsidiaryEntityId;
        private String subsidiaryEntityCode;
        private BigDecimal amount;
        private String currencyCode;
        private String status;
        private String settlementRef;
        private LocalDateTime settledAt;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    public static class IntercompanyReceivableRequest {
        private UUID corporateId;
        private UUID owningEntityId;
        private String owningEntityCode;
        private UUID counterpartyEntityId;
        private String counterpartyEntityCode;
        private UUID customerPartyId;
        private String description;
        private BigDecimal amount;
        private String currencyCode;
        private LocalDate dueDate;
        private String externalReference;
        private String createdBy;
    }

    @Data
    @Builder
    public static class IntercompanyReceivableResult {
        private UUID receivableId;
        private String receivableNumber;
        private UUID owningEntityId;
        private String owningEntityCode;
        private UUID counterpartyEntityId;
        private String counterpartyEntityCode;
        private BigDecimal amount;
        private String currencyCode;
        private String status;
        private Boolean nettingEligible;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    public static class CoboRechargeSummary {
        private UUID subsidiaryEntityId;
        private String subsidiaryEntityCode;
        private String subsidiaryEntityName;
        private int totalRecharges;
        private int pendingCount;
        private int settledCount;
        private BigDecimal pendingAmount;
        private BigDecimal settledAmount;
    }
}