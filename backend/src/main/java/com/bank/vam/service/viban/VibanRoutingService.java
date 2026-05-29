package com.bank.vam.service.viban;

import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.Transaction.MovementType;
import com.bank.vam.entity.Transaction.ReconciliationMatchType;
import com.bank.vam.entity.Transaction.TransactionStatus;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.viban.Viban;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.viban.VibanRepository;
import com.bank.vam.service.treasury.BalanceAggregationServiceEnhanced;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Service for VIBAN-based payment routing (ROBO - Receive On Behalf Of).
 * 
 * This is the core routing engine that:
 * 1. Receives incoming payments with VIBAN
 * 2. Looks up the VIBAN to find target VA
 * 3. Credits the VA
 * 4. Propagates balance through hierarchy
 * 5. Auto-reconciles if reference linked
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class VibanRoutingService {

    private final VibanRepository vibanRepository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final TransactionRepository transactionRepository;
    private final VibanService vibanService;
    private final BalanceAggregationServiceEnhanced balanceAggregationService;

    /**
     * Route incoming payment via VIBAN.
     * This is the main entry point for ROBO processing.
     */
    public RoutingResult routePayment(RoutingRequest request) {
        long startTime = System.currentTimeMillis();
        
        log.info("Routing payment of {} {} via VIBAN {}", 
            request.getAmount(), request.getCurrencyCode(), request.getViban());

        // 1. Lookup VIBAN
        Viban viban = vibanRepository.findByViban(request.getViban()).orElse(null);
        
        if (viban == null) {
            log.warn("VIBAN not found: {}", request.getViban());
            return RoutingResult.builder()
                .success(false)
                .errorCode("VIBAN_NOT_FOUND")
                .errorMessage("VIBAN not found: " + request.getViban())
                .routingTimeMs(System.currentTimeMillis() - startTime)
                .build();
        }

        // 2. Validate VIBAN
        if (!viban.canAcceptPayment()) {
            log.warn("VIBAN cannot accept payment: {} status={}", viban.getViban(), viban.getStatus());
            return RoutingResult.builder()
                .success(false)
                .vibanId(viban.getId())
                .errorCode("VIBAN_INVALID")
                .errorMessage("VIBAN cannot accept payment: " + viban.getStatus())
                .routingTimeMs(System.currentTimeMillis() - startTime)
                .build();
        }

        // 3. Get Virtual Account
        VirtualAccount va = virtualAccountRepository.findById(viban.getVirtualAccountId()).orElse(null);
        
        if (va == null || !va.isActive()) {
            log.warn("Virtual account not found or inactive for VIBAN: {}", viban.getViban());
            return RoutingResult.builder()
                .success(false)
                .vibanId(viban.getId())
                .errorCode("VA_INVALID")
                .errorMessage("Virtual account not found or inactive")
                .routingTimeMs(System.currentTimeMillis() - startTime)
                .build();
        }

        // 4. Validate amount if expected amount is set
        boolean amountMatches = viban.matchesAmount(request.getAmount());
        boolean amountInBounds = viban.isAmountInBounds(request.getAmount());
        
        if (!amountInBounds) {
            log.warn("Payment amount {} out of bounds for VIBAN {}", request.getAmount(), viban.getViban());
            return RoutingResult.builder()
                .success(false)
                .vibanId(viban.getId())
                .virtualAccountId(va.getId())
                .errorCode("AMOUNT_OUT_OF_BOUNDS")
                .errorMessage("Payment amount out of allowed bounds")
                .routingTimeMs(System.currentTimeMillis() - startTime)
                .build();
        }

        // 5. Credit Virtual Account
        BigDecimal balanceBefore = va.getCurrentBalance();
        va.setCurrentBalance(balanceBefore.add(request.getAmount()));
        va.setAvailableBalance(va.getAvailableBalance().add(request.getAmount()));
        virtualAccountRepository.save(va);

        // 6. Create Transaction
        Transaction transaction = createTransaction(request, viban, va, balanceBefore);
        transaction = transactionRepository.save(transaction);

        // 7. Update VIBAN usage stats
        viban.recordPayment(request.getAmount());
        vibanRepository.save(viban);

        // 8. Propagate balance through VA hierarchy (parent-child relationships)
        if (va.getParentAccountId() != null) {
            balanceAggregationService.propagateBalanceChange(va.getId(), request.getAmount());
        }

        // 9. Determine reconciliation result
        ReconciliationMatchType matchType = determineMatchType(viban, request.getAmount(), amountMatches);

        long routingTime = System.currentTimeMillis() - startTime;
        
        log.info("Successfully routed payment {} {} to VA {} via VIBAN {} in {}ms",
            request.getAmount(), request.getCurrencyCode(), va.getVaNumber(), 
            viban.getViban(), routingTime);

        return RoutingResult.builder()
            .success(true)
            .vibanId(viban.getId())
            .virtualAccountId(va.getId())
            .vaNumber(va.getVaNumber())
            .transactionId(transaction.getId())
            .transactionReference(transaction.getReferenceNumber())
            .balanceBefore(balanceBefore)
            .balanceAfter(va.getCurrentBalance())
            .autoReconciled(viban.hasReference() && amountMatches)
            .reconciliationMatchType(matchType)
            .reconciledReferenceType(viban.getReferenceType())
            .reconciledReferenceId(viban.getReferenceId())
            .routingTimeMs(routingTime)
            .build();
    }

    /**
     * Create transaction for ROBO credit.
     */
    private Transaction createTransaction(RoutingRequest request, Viban viban, 
                                          VirtualAccount va, BigDecimal balanceBefore) {
        String referenceNumber = generateReferenceNumber();
        boolean autoReconciled = viban.hasReference();
        ReconciliationMatchType matchType = autoReconciled ? 
            ReconciliationMatchType.AUTO : ReconciliationMatchType.UNMATCHED;

        return Transaction.builder()
            .movementType(MovementType.ROBO_CREDIT)
            .corporateId(va.getCorporateId())
            .vaId(va.getId())
            .physicalAccountId(va.getPhysicalAccountId())
            .amount(request.getAmount())
            .currencyCode(request.getCurrencyCode())
            .balanceBefore(balanceBefore)
            .balanceAfter(balanceBefore.add(request.getAmount()))
            .transactionDate(LocalDateTime.now())
            .valueDate(LocalDate.now())
            .referenceNumber(referenceNumber)
            .description("ROBO Credit via VIBAN " + viban.getViban())
            .channel("VIBAN")
            .remitterName(request.getSenderName())
            .remitterAccount(request.getSenderAccount())
            .vibanId(viban.getId())
            .viban(viban.getViban())
            .routedViaViban(true)
            .isRobo(true)
            .autoReconciled(autoReconciled)
            .reconciledReferenceType(viban.getReferenceType())
            .reconciledReferenceId(viban.getReferenceId())
            .reconciliationMatchType(matchType)
            .targetHierarchyNodeId(va.getHierarchyNodeId())
            .hierarchyPath(va.getHierarchyPath())
            .status(TransactionStatus.COMPLETED)
            .externalReference(request.getPaymentReference())
            .build();
    }

    /**
     * Determine reconciliation match type.
     */
    private ReconciliationMatchType determineMatchType(Viban viban, BigDecimal amount, boolean amountMatches) {
        if (!viban.hasReference()) {
            return ReconciliationMatchType.UNMATCHED;
        }
        if (amountMatches) {
            return ReconciliationMatchType.AUTO;
        }
        // Has reference but amount doesn't match
        return ReconciliationMatchType.PARTIAL;
    }

    /**
     * Generate unique reference number.
     */
    private String generateReferenceNumber() {
        return "ROBO" + System.currentTimeMillis() + String.format("%04d", (int)(Math.random() * 10000));
    }

    // ========================================================================
    // Request/Response DTOs
    // ========================================================================

    @Data
    @Builder
    public static class RoutingRequest {
        private String viban;
        private BigDecimal amount;
        private String currencyCode;
        private String senderName;
        private String senderAccount;
        private String senderBankCode;
        private String paymentReference;
        private String remittanceInfo;
        private UUID physicalAccountId; // Source physical account
    }

    @Data
    @Builder
    public static class RoutingResult {
        private boolean success;
        private UUID vibanId;
        private UUID virtualAccountId;
        private String vaNumber;
        private UUID transactionId;
        private String transactionReference;
        private BigDecimal balanceBefore;
        private BigDecimal balanceAfter;
        private boolean autoReconciled;
        private ReconciliationMatchType reconciliationMatchType;
        private String reconciledReferenceType;
        private String reconciledReferenceId;
        private String errorCode;
        private String errorMessage;
        private long routingTimeMs;
    }
}