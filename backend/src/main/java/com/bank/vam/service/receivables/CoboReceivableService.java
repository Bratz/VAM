package com.bank.vam.service.receivables;

import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.pobo.IntercompanyRecharge;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.Transaction.MovementType;
import com.bank.vam.entity.Transaction.TransactionStatus;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.VaStatus;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.entity.receivables.Receivable.CoboRequestStatus;
import com.bank.vam.entity.receivables.Receivable.CollectionRoute;
import com.bank.vam.entity.receivables.Receivable.NettingStatus;
import com.bank.vam.entity.receivables.Receivable.ReceivableStatus;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.pobo.IntercompanyRechargeRepository;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.receivables.ReceivableRepository;
import com.bank.vam.service.treasury.FeePostingService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * CoboReceivableService - Phase 3 COBO (Collect On Behalf Of) Workflow Service
 * 
 * COBO enables centralized collections where treasury collects on behalf of subsidiaries.
 * 
 * WORKFLOW:
 * 1. Subsidiary creates receivable with COBO request
 * 2. Treasury approves/rejects COBO request
 * 3. Treasury collects from customer
 * 4. System creates IHB deposit to forward funds to subsidiary
 * 5. Intercompany recharge record created for tracking
 * 
 * INTEGRATION POINTS:
 * - FeePostingService: COBO service fees posted to Settlement VA
 * - IHB Service: Creates deposit when forwarding funds to subsidiary
 * - IntercompanyRecharge: Records the internal fund movement
 * - Party Service: Validates customer party COBO eligibility
 * 
 * @see <a href="https://www.capgemini.com/wp-content/uploads/2020/12/Virtual-Acc-Management-in-Transaction-Banking.pdf">
 *      Capgemini VAM Reference - COBO/POBO flows</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoboReceivableService {

    private final ReceivableRepository receivableRepository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final TransactionRepository transactionRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final FeePostingService feePostingService;
    private final IntercompanyRechargeRepository rechargeRepository;
    
    // Fee configuration
    private static final BigDecimal COBO_SERVICE_FEE_PERCENT = new BigDecimal("0.0015"); // 0.15%
    private static final BigDecimal COBO_MINIMUM_FEE = new BigDecimal("5.00");
    private static final BigDecimal COBO_MAXIMUM_FEE = new BigDecimal("500.00");

    // ========================================================================
    // COBO REQUEST WORKFLOW
    // ========================================================================

    /**
     * Submit a receivable for COBO collection.
     * 
     * @param receivableId The receivable to submit
     * @param collectorEntityId The entity that will collect (usually HQ treasury)
     * @param requestedBy User requesting COBO
     * @return Updated receivable
     */
    @Transactional
    public Receivable submitForCobo(UUID receivableId, UUID collectorEntityId, String requestedBy) {
        log.info("Submitting receivable {} for COBO collection by entity {}", 
            receivableId, collectorEntityId);
        
        Receivable receivable = receivableRepository.findById(receivableId)
            .orElseThrow(() -> new IllegalArgumentException("Receivable not found: " + receivableId));
        
        // Validate can request COBO
        if (!receivable.canRequestCobo()) {
            throw new IllegalStateException("Receivable cannot be submitted for COBO: status=" + 
                receivable.getStatus() + ", route=" + receivable.getCollectionRoute());
        }
        
        // Get collector entity details
        LegalEntity collectorEntity = legalEntityRepository.findById(collectorEntityId)
            .orElseThrow(() -> new IllegalArgumentException("Collector entity not found: " + collectorEntityId));
        
        // Submit for COBO
        receivable.requestCobo(
            collectorEntityId,
            collectorEntity.getEntityCode(),
            collectorEntity.getEntityName(),
            requestedBy
        );
        
        receivable = receivableRepository.save(receivable);
        
        log.info("Receivable {} submitted for COBO. Status: {}", 
            receivableId, receivable.getCoboRequestStatus());
        
        return receivable;
    }

    /**
     * Treasury approves COBO request.
     */
    @Transactional
    public Receivable approveCobo(UUID receivableId, String approvedBy) {
        log.info("Approving COBO request for receivable {}", receivableId);
        
        Receivable receivable = receivableRepository.findById(receivableId)
            .orElseThrow(() -> new IllegalArgumentException("Receivable not found: " + receivableId));
        
        if (receivable.getCoboRequestStatus() != CoboRequestStatus.PENDING_TREASURY_APPROVAL) {
            throw new IllegalStateException("COBO request not pending approval: " + 
                receivable.getCoboRequestStatus());
        }
        
        receivable.approveCobo(approvedBy);
        receivable = receivableRepository.save(receivable);
        
        log.info("COBO approved for receivable {}. Ready for collection.", receivableId);
        
        return receivable;
    }

    /**
     * Treasury rejects COBO request.
     */
    @Transactional
    public Receivable rejectCobo(UUID receivableId, String rejectedBy, String reason) {
        log.info("Rejecting COBO request for receivable {}: {}", receivableId, reason);
        
        Receivable receivable = receivableRepository.findById(receivableId)
            .orElseThrow(() -> new IllegalArgumentException("Receivable not found: " + receivableId));
        
        if (receivable.getCoboRequestStatus() != CoboRequestStatus.PENDING_TREASURY_APPROVAL) {
            throw new IllegalStateException("COBO request not pending approval: " + 
                receivable.getCoboRequestStatus());
        }
        
        receivable.rejectCobo(rejectedBy);
        receivable.setNotes(reason);
        receivable = receivableRepository.save(receivable);
        
        log.info("COBO rejected for receivable {}. Reverted to direct collection.", receivableId);
        
        return receivable;
    }

    // ========================================================================
    // COBO COLLECTION EXECUTION
    // ========================================================================

    /**
     * Execute COBO collection - collect on behalf of subsidiary.
     * 
     * Flow:
     * 1. Validate receivable is approved for COBO
     * 2. Record collection to treasury VA
     * 3. Calculate and post COBO service fee
     * 4. Create IHB deposit to forward funds to subsidiary
     * 5. Create intercompany recharge record
     * 6. Update receivable status
     * 
     * @param request COBO collection request
     * @return COBO collection result
     */
    @Transactional
    public CoboCollectionResult executeCoboCollection(CoboCollectionRequest request) {
        log.info("Executing COBO collection for receivable {}", request.getReceivableId());
        
        Receivable receivable = receivableRepository.findById(request.getReceivableId())
            .orElseThrow(() -> new IllegalArgumentException("Receivable not found: " + request.getReceivableId()));
        
        // Validate ready for collection
        if (receivable.getCoboRequestStatus() != CoboRequestStatus.APPROVED) {
            throw new IllegalStateException("COBO not approved for collection: " + 
                receivable.getCoboRequestStatus());
        }
        
        // Get treasury VA (collector's account)
        VirtualAccount treasuryVa = virtualAccountRepository.findById(request.getTreasuryVaId())
            .orElseThrow(() -> new IllegalArgumentException("Treasury VA not found: " + request.getTreasuryVaId()));
        
        if (treasuryVa.getStatus() != VaStatus.ACTIVE) {
            throw new IllegalStateException("Treasury VA not active: " + treasuryVa.getStatus());
        }
        
        // Get subsidiary VA (owning entity's account for forwarding)
        VirtualAccount subsidiaryVa = null;
        if (request.getSubsidiaryVaId() != null) {
            subsidiaryVa = virtualAccountRepository.findById(request.getSubsidiaryVaId()).orElse(null);
        }
        
        BigDecimal collectionAmount = request.getAmount() != null ? 
            request.getAmount() : receivable.getOutstandingAmount();
        
        String correlationId = "COBO-" + System.currentTimeMillis();
        String transactionRef = "TRF-" + correlationId;
        
        // 1. Calculate COBO service fee
        BigDecimal coboFee = calculateCoboFee(collectionAmount);
        BigDecimal netAmount = collectionAmount.subtract(coboFee);
        
        // 2. Record collection to treasury VA
        BigDecimal treasuryBalanceBefore = treasuryVa.getCurrentBalance();
        treasuryVa.credit(collectionAmount);
        virtualAccountRepository.save(treasuryVa);
        
        Transaction collectionTxn = Transaction.builder()
            .referenceNumber(Transaction.generateReference(MovementType.ROBO_CREDIT))
            .movementType(MovementType.ROBO_CREDIT)
            .corporateId(treasuryVa.getCorporateId())
            .vaId(treasuryVa.getId())
            .physicalAccountId(treasuryVa.getPhysicalAccountId())
            .programId(treasuryVa.getProgramId())
            .amount(collectionAmount)
            .currencyCode(receivable.getCurrencyCode())
            .balanceBefore(treasuryBalanceBefore)
            .balanceAfter(treasuryVa.getCurrentBalance())
            .correlationId(correlationId)
            .feeAmount(coboFee)
            .remitterName(receivable.getCustomerName())
            .description("COBO Collection on behalf of " + receivable.getOwningEntityCode())
            .externalReference(request.getPaymentReference())
            .isRobo(true)
            .behalfOfEntity(receivable.getOwningEntityName())
            .behalfOfVaId(request.getSubsidiaryVaId())
            .status(TransactionStatus.COMPLETED)
            .transactionDate(LocalDateTime.now())
            .valueDate(LocalDate.now())
            .channel("COBO")
            .build();
        
        collectionTxn = transactionRepository.save(collectionTxn);
        transactionRef = collectionTxn.getReferenceNumber();
        
        // 3. Post COBO service fee to Settlement VA
        UUID settlementVaId = null;
        String settlementVaNumber = null;
        boolean feePosted = false;
        
        if (coboFee.compareTo(BigDecimal.ZERO) > 0 && feePostingService != null) {
            try {
                FeePostingService.FeePostingResult feeResult = feePostingService.postFee(
                    treasuryVa.getId(),
                    coboFee,
                    "COBO_SERVICE_FEE",
                    collectionTxn.getId(),
                    "COBO service fee - on behalf of " + receivable.getOwningEntityCode()
                );
                
                if ("POSTED".equals(feeResult.getStatus())) {
                    settlementVaId = feeResult.getSettlementVaId();
                    settlementVaNumber = feeResult.getSettlementVaNumber();
                    feePosted = true;
                    log.info("COBO fee {} posted to Settlement VA {}", coboFee, settlementVaNumber);
                }
            } catch (Exception e) {
                log.error("Failed to post COBO fee: {}", e.getMessage());
            }
        }
        
        // 4. Forward funds to subsidiary (create internal transfer or IHB deposit)
        UUID ihbDepositId = null;

        // Get entity details for IntercompanyRecharge
        UUID treasuryEntityId = treasuryVa.getOwningEntityId();
        UUID subsidiaryEntityId = receivable.getOwningEntityId();

        LegalEntity treasuryEntity = legalEntityRepository.findById(treasuryEntityId)
            .orElseThrow(() -> new IllegalArgumentException("Treasury entity not found: " + treasuryEntityId));
        LegalEntity subsidiaryEntity = legalEntityRepository.findById(subsidiaryEntityId)
            .orElseThrow(() -> new IllegalArgumentException("Subsidiary entity not found: " + subsidiaryEntityId));

        // 5. Create COBO recharge - Treasury collected, now owes Subsidiary
        IntercompanyRecharge coboRecharge = IntercompanyRecharge.builder()
            .rechargeReference(generateRechargeReference())
            // For COBO: payer = Treasury (who collected), behalf = Subsidiary (beneficiary)
            .payerEntityId(treasuryEntityId)
            .payerEntityCode(treasuryEntity.getEntityCode())
            .payerEntityName(treasuryEntity.getEntityName())
            .behalfEntityId(subsidiaryEntityId)
            .behalfEntityCode(subsidiaryEntity.getEntityCode())
            .behalfEntityName(subsidiaryEntity.getEntityName())
            .originalPayableId(receivable.getId())  // Link to receivable
            .originalAmount(collectionAmount)
            .rechargeAmount(collectionAmount)
            .serviceFee(coboFee)
            .totalRecharge(netAmount)  // Amount to forward to subsidiary
            .currencyCode(receivable.getCurrencyCode())
            .rechargeType(IntercompanyRecharge.RechargeType.COBO_COLLECTION)
            .flowDirection(IntercompanyRecharge.FlowDirection.INBOUND)
            .status(IntercompanyRecharge.RechargeStatus.APPROVED)  // Auto-approved for COBO
            .build();
        coboRecharge = rechargeRepository.save(coboRecharge);
        UUID rechargeId = coboRecharge.getId();

        log.info("Created COBO recharge {} for collection. Treasury {} owes Subsidiary {} amount {}",
            coboRecharge.getRechargeReference(), treasuryEntity.getEntityCode(),
            subsidiaryEntity.getEntityCode(), netAmount);

        if (subsidiaryVa != null && subsidiaryVa.getStatus() == VaStatus.ACTIVE) {
            // Transfer net amount to subsidiary
            BigDecimal subsidiaryBalanceBefore = subsidiaryVa.getCurrentBalance();
            
            // Debit treasury
            treasuryVa.debit(netAmount);
            virtualAccountRepository.save(treasuryVa);
            
            // Credit subsidiary
            subsidiaryVa.credit(netAmount);
            virtualAccountRepository.save(subsidiaryVa);
            
            // Record transfer
            Transaction transferOut = Transaction.builder()
                .referenceNumber(Transaction.generateReference(MovementType.TRANSFER_OUT))
                .movementType(MovementType.TRANSFER_OUT)
                .corporateId(treasuryVa.getCorporateId())
                .vaId(treasuryVa.getId())
                .physicalAccountId(treasuryVa.getPhysicalAccountId())
                .amount(netAmount)
                .currencyCode(receivable.getCurrencyCode())
                .balanceBefore(treasuryVa.getCurrentBalance().add(netAmount))
                .balanceAfter(treasuryVa.getCurrentBalance())
                .correlationId(correlationId)
                .counterpartyVaId(subsidiaryVa.getId())
                .description("COBO forward to " + receivable.getOwningEntityCode())
                .status(TransactionStatus.COMPLETED)
                .transactionDate(LocalDateTime.now())
                .valueDate(LocalDate.now())
                .channel("COBO")
                .build();
            transactionRepository.save(transferOut);
            
            Transaction transferIn = Transaction.builder()
                .referenceNumber(Transaction.generateReference(MovementType.TRANSFER_IN))
                .movementType(MovementType.TRANSFER_IN)
                .corporateId(subsidiaryVa.getCorporateId())
                .vaId(subsidiaryVa.getId())
                .physicalAccountId(subsidiaryVa.getPhysicalAccountId())
                .amount(netAmount)
                .currencyCode(receivable.getCurrencyCode())
                .balanceBefore(subsidiaryBalanceBefore)
                .balanceAfter(subsidiaryVa.getCurrentBalance())
                .correlationId(correlationId)
                .counterpartyVaId(treasuryVa.getId())
                .description("COBO receipt from Treasury")
                .externalReference(receivable.getReceivableNumber())
                .status(TransactionStatus.COMPLETED)
                .transactionDate(LocalDateTime.now())
                .valueDate(LocalDate.now())
                .channel("COBO")
                .build();
            transactionRepository.save(transferIn);
            
            log.info("COBO funds {} forwarded to subsidiary VA {}", netAmount, subsidiaryVa.getVaNumber());
        } else {
            // Would create IHB deposit instead
            log.info("COBO funds {} held in treasury. IHB deposit would be created.", netAmount);
        }
        
        // 5. Update receivable
        receivable.markCoboCollected(transactionRef, ihbDepositId, rechargeId);
        receivable = receivableRepository.save(receivable);
        
        log.info("COBO collection completed. Receivable {} marked as paid. Fee: {}, Net: {}", 
            receivable.getId(), coboFee, netAmount);
        
        return CoboCollectionResult.builder()
            .receivableId(receivable.getId())
            .receivableNumber(receivable.getReceivableNumber())
            .transactionReference(transactionRef)
            .grossAmount(collectionAmount)
            .coboFee(coboFee)
            .netAmount(netAmount)
            .treasuryVaId(treasuryVa.getId())
            .treasuryVaNumber(treasuryVa.getVaNumber())
            .subsidiaryVaId(request.getSubsidiaryVaId())
            .settlementVaId(settlementVaId)
            .settlementVaNumber(settlementVaNumber)
            .ihbDepositId(ihbDepositId)
            .rechargeId(rechargeId)
            .feePosted(feePosted)
            .collectedAt(LocalDateTime.now())
            .build();
    }

    /**
     * Preview COBO collection (calculate fees without executing).
     */
    public CoboPreviewResult previewCoboCollection(UUID receivableId) {
        Receivable receivable = receivableRepository.findById(receivableId)
            .orElseThrow(() -> new IllegalArgumentException("Receivable not found: " + receivableId));
        
        BigDecimal amount = receivable.getOutstandingAmount();
        BigDecimal coboFee = calculateCoboFee(amount);
        BigDecimal netAmount = amount.subtract(coboFee);
        
        List<String> warnings = new ArrayList<>();
        if (receivable.getCoboRequestStatus() != CoboRequestStatus.APPROVED) {
            warnings.add("COBO not yet approved. Current status: " + receivable.getCoboRequestStatus());
        }
        if (receivable.getOwningEntityId() == null) {
            warnings.add("No owning entity set. Cannot determine where to forward funds.");
        }
        
        return CoboPreviewResult.builder()
            .receivableId(receivableId)
            .receivableNumber(receivable.getReceivableNumber())
            .customerName(receivable.getCustomerName())
            .grossAmount(amount)
            .coboFee(coboFee)
            .coboFeePercent(COBO_SERVICE_FEE_PERCENT.multiply(BigDecimal.valueOf(100)))
            .netAmount(netAmount)
            .currencyCode(receivable.getCurrencyCode())
            .owningEntityId(receivable.getOwningEntityId())
            .owningEntityCode(receivable.getOwningEntityCode())
            .owningEntityName(receivable.getOwningEntityName())
            .collectorEntityId(receivable.getCoboCollectorEntityId())
            .collectorEntityCode(receivable.getCoboCollectorEntityCode())
            .collectorEntityName(receivable.getCoboCollectorEntityName())
            .coboRequestStatus(receivable.getCoboRequestStatus())
            .warnings(warnings)
            .build();
    }

    // ========================================================================
    // COBO QUERIES
    // ========================================================================

    /**
     * Find all receivables pending COBO approval.
     */
    public List<Receivable> findPendingCoboApproval() {
        return receivableRepository.findPendingCoboApproval();
    }

    /**
     * Find COBO receivables by owning entity (subsidiary).
     */
    public List<Receivable> findCoboByOwningEntity(UUID owningEntityId) {
        return receivableRepository.findCoboByOwningEntity(owningEntityId);
    }

    /**
     * Find COBO receivables by collector entity (treasury).
     */
    public List<Receivable> findCoboByCollector(UUID collectorEntityId) {
        return receivableRepository.findByCoboCollectorEntityId(collectorEntityId);
    }

    /**
     * Get COBO statistics for a corporate.
     */
    public CoboStats getCoboStats(UUID corporateId) {
        List<Receivable> coboReceivables = receivableRepository.findCoboByCorporate(
            corporateId, org.springframework.data.domain.Pageable.unpaged()).getContent();
        
        long pendingApproval = coboReceivables.stream()
            .filter(r -> r.getCoboRequestStatus() == CoboRequestStatus.PENDING_TREASURY_APPROVAL)
            .count();
        
        long approved = coboReceivables.stream()
            .filter(r -> r.getCoboRequestStatus() == CoboRequestStatus.APPROVED)
            .count();
        
        long collected = coboReceivables.stream()
            .filter(r -> r.getCoboRequestStatus() == CoboRequestStatus.COLLECTED)
            .count();
        
        BigDecimal pendingAmount = coboReceivables.stream()
            .filter(r -> r.getCoboRequestStatus() == CoboRequestStatus.APPROVED)
            .map(Receivable::getOutstandingAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal collectedAmount = coboReceivables.stream()
            .filter(r -> r.getCoboRequestStatus() == CoboRequestStatus.COLLECTED)
            .map(Receivable::getPaidAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        return CoboStats.builder()
            .totalCoboReceivables(coboReceivables.size())
            .pendingApprovalCount(pendingApproval)
            .approvedCount(approved)
            .collectedCount(collected)
            .pendingCollectionAmount(pendingAmount)
            .totalCollectedAmount(collectedAmount)
            .build();
    }

    // ========================================================================
    // FEE CALCULATION
    // ========================================================================

    /**
     * Calculate COBO service fee.
     *
     * Fee Structure:
     * - 0.15% of collection amount
     * - Minimum: AED 5
     * - Maximum: AED 500
     */
    private BigDecimal calculateCoboFee(BigDecimal amount) {
        BigDecimal fee = amount.multiply(COBO_SERVICE_FEE_PERCENT)
            .setScale(2, RoundingMode.HALF_UP);

        if (fee.compareTo(COBO_MINIMUM_FEE) < 0) {
            fee = COBO_MINIMUM_FEE;
        } else if (fee.compareTo(COBO_MAXIMUM_FEE) > 0) {
            fee = COBO_MAXIMUM_FEE;
        }

        return fee;
    }

    // ========================================================================
    // REFERENCE GENERATION
    // ========================================================================

    /**
     * Generate unique recharge reference for COBO collections.
     */
    private String generateRechargeReference() {
        return "COBO-RCH-" + System.currentTimeMillis() + "-" +
            UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    // ========================================================================
    // DTOs
    // ========================================================================

    @Data
    @Builder
    public static class CoboCollectionRequest {
        private UUID receivableId;
        private UUID treasuryVaId;
        private UUID subsidiaryVaId;
        private BigDecimal amount;
        private String paymentReference;
        private String notes;
    }

    @Data
    @Builder
    public static class CoboCollectionResult {
        private UUID receivableId;
        private String receivableNumber;
        private String transactionReference;
        private BigDecimal grossAmount;
        private BigDecimal coboFee;
        private BigDecimal netAmount;
        private UUID treasuryVaId;
        private String treasuryVaNumber;
        private UUID subsidiaryVaId;
        private UUID settlementVaId;
        private String settlementVaNumber;
        private UUID ihbDepositId;
        private UUID rechargeId;
        private boolean feePosted;
        private LocalDateTime collectedAt;
    }

    @Data
    @Builder
    public static class CoboPreviewResult {
        private UUID receivableId;
        private String receivableNumber;
        private String customerName;
        private BigDecimal grossAmount;
        private BigDecimal coboFee;
        private BigDecimal coboFeePercent;
        private BigDecimal netAmount;
        private String currencyCode;
        private UUID owningEntityId;
        private String owningEntityCode;
        private String owningEntityName;
        private UUID collectorEntityId;
        private String collectorEntityCode;
        private String collectorEntityName;
        private CoboRequestStatus coboRequestStatus;
        private List<String> warnings;
    }

    @Data
    @Builder
    public static class CoboStats {
        private int totalCoboReceivables;
        private long pendingApprovalCount;
        private long approvedCount;
        private long collectedCount;
        private BigDecimal pendingCollectionAmount;
        private BigDecimal totalCollectedAmount;
    }
}