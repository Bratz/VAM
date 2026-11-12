package com.bank.vam.service.receivables;

import com.bank.vam.exception.BusinessException;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.VaStatus;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.entity.receivables.UnmatchedPayment;
import com.bank.vam.entity.receivables.Receivable.CoboRequestStatus;
import com.bank.vam.entity.receivables.Receivable.CollectionRoute;
import com.bank.vam.entity.receivables.Receivable.NettingStatus;
import com.bank.vam.entity.receivables.Receivable.ReceivableStatus;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.receivables.ReceivableRepository;
import com.bank.vam.repository.receivables.UnmatchedPaymentRepository;
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
 * CoboReceivableService - Phase 3 COBO (Collect On Behalf Of) Workflow Service
 * 
 * COBO enables centralized collections where treasury collects on behalf of subsidiaries.
 * 
 * WORKFLOW:
 * 1. The subsidiary is enrolled for the currency (CoboEnrolmentService); its invoices are COBO from creation
 *    (ReceivablesService.createInvoice), with no per-invoice request or approval
 * 2. The customer's payment lands on the collector's account; matching it (auto, or treasury picking the
 *    unmatched credit) goes through ReconciliationService.applyPayment, which credits the subsidiary's IHB
 *    current account and treasury's IC payable, gross (no COBO fee).
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
    private final UnmatchedPaymentRepository unmatchedPaymentRepository;
    private final ReconciliationService reconciliationService;

    // ========================================================================
    // COBO COLLECTION EXECUTION
    // ========================================================================

    /**
     * Apply a bank credit that already sits, unmatched, on the collector's account to an approved COBO invoice
     * (spec D6). Nothing is credited out of thin air: the credit is matched through
     * {@link ReconciliationService#manualMatch}, the one application point, which also credits the subsidiary
     * in-house for the amount applied. No fee (D4), no recharge record.
     */
    @Transactional
    public CoboCollectionResult executeCoboCollection(CoboCollectionRequest request) {
        Receivable receivable = receivableRepository.findById(request.getReceivableId())
            .orElseThrow(() -> new BusinessException("Receivable not found: " + request.getReceivableId()));
        if (receivable.getCoboRequestStatus() != CoboRequestStatus.APPROVED) {
            throw new BusinessException("COBO not approved for collection: " + receivable.getCoboRequestStatus());
        }
        if (request.getUnmatchedPaymentId() == null) {
            throw new BusinessException("Choose the bank credit that paid this invoice");
        }
        CollectCredit credit = collectCredits(receivable).stream()
            .filter(c -> c.id().equals(request.getUnmatchedPaymentId())).findFirst()
            .orElseThrow(() -> new BusinessException("That is not an unmatched " + receivable.getCurrencyCode()
                + " credit on " + receivable.getCoboCollectorEntityCode() + "'s accounts"));

        ReconciliationService.ReconciliationResult result =
            reconciliationService.manualMatch(credit.id(), receivable.getId(), "TREASURY");
        if (!result.isSuccess()) {
            throw new BusinessException(result.getErrorMessage());
        }
        Receivable paid = receivableRepository.findById(receivable.getId()).orElseThrow();
        log.info("COBO {}: bank credit {} of {} applied on {}", paid.getReceivableNumber(), credit.id(), credit.amount(), credit.vaNumber());
        return CoboCollectionResult.builder()
            .receivableId(paid.getId())
            .receivableNumber(paid.getReceivableNumber())
            .transactionReference(paid.getCoboTransactionRef())
            .grossAmount(credit.amount())
            .coboFee(BigDecimal.ZERO)
            .netAmount(credit.amount())
            .treasuryVaId(credit.vaId())
            .treasuryVaNumber(credit.vaNumber())
            .feePosted(false)
            .collectedAt(LocalDateTime.now())
            .build();
    }

    /** An unmatched bank credit on the collector's account that can pay an approved COBO invoice. */
    public record CollectCredit(UUID id, BigDecimal amount, String currencyCode, String payerName, String remittanceInfo,
                                LocalDateTime paymentDate, UUID vaId, String vaNumber) {
    }

    /**
     * The unmatched credits on the collecting entity's active accounts that pay in the invoice currency -- booked in it,
     * or sent in it and converted on arrival (ihb-fx: EUR to treasury's AED account) -- at that amount, newest first.
     */
    @Transactional(readOnly = true)
    public List<CollectCredit> collectCredits(UUID receivableId) {
        return collectCredits(receivableRepository.findById(receivableId)
            .orElseThrow(() -> new BusinessException("Receivable not found: " + receivableId)));
    }

    private List<CollectCredit> collectCredits(Receivable receivable) {
        if (receivable.getCoboCollectorEntityId() == null) {
            throw new BusinessException("Receivable " + receivable.getReceivableNumber() + " has no collecting entity");
        }
        return virtualAccountRepository.findByOwningEntityIdAndStatus(receivable.getCoboCollectorEntityId(), VaStatus.ACTIVE).stream()
            .flatMap(v -> unmatchedPaymentRepository.findByVirtualAccountId(v.getId()).stream()
                .filter(UnmatchedPayment::isPending)
                .map(u -> {
                    ReconciliationService.Money paid = reconciliationService.paidOn(receivable, u.getTransactionId(), u.getAmount(), u.getCurrencyCode());
                    return new CollectCredit(u.getId(), paid.amount(), paid.currency(), u.getPayerName(),
                        u.getRemittanceInfo(), u.getPaymentDate(), v.getId(), v.getVaNumber());
                })
                .filter(c -> receivable.getCurrencyCode().equals(c.currencyCode())))
            .sorted(Comparator.comparing(CollectCredit::paymentDate, Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    /**
     * Preview COBO collection (calculate fees without executing).
     */
    public CoboPreviewResult previewCoboCollection(UUID receivableId) {
        Receivable receivable = receivableRepository.findById(receivableId)
            .orElseThrow(() -> new BusinessException("Receivable not found: " + receivableId));
        
        BigDecimal amount = receivable.getOutstandingAmount();
        BigDecimal coboFee = BigDecimal.ZERO; // no COBO fee (spec D4)
        BigDecimal netAmount = amount;
        
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
            .coboFeePercent(BigDecimal.ZERO)
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
    // DTOs
    // ========================================================================

    @Data
    @Builder
    public static class CoboCollectionRequest {
        private UUID receivableId;
        /** The unmatched bank credit on the collector's account that paid the invoice. */
        private UUID unmatchedPaymentId;
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