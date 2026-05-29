package com.bank.vam.service.intercompany;

import com.bank.vam.dto.intercompany.IntercompanyDto.*;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.intercompany.IntercompanyTransaction;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.intercompany.IntercompanyTransactionRepository;
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
 * Service for Intercompany Settlement Operations.
 * 
 * UPDATED: Now uses LegalEntityRepository instead of IhbEntityRepository.
 * LegalEntity contains all IHB configuration fields.
 * 
 * Provides POBO/COBO operations, entity management, and settlement workflows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class IntercompanyService {

    private final IntercompanyTransactionRepository transactionRepository;
    private final LegalEntityRepository legalEntityRepository;
    private final com.bank.vam.config.MarketProfileProperties marketProfile;

    // ========================================================================
    // ENTITY MANAGEMENT
    // ========================================================================

    @Transactional(readOnly = true)
    public List<EntityWithPositionResponse> getEntitiesWithPositions(String status, String entityType) {
        // Get all IHB-enabled entities
        List<LegalEntity> entities = legalEntityRepository.findByIhbEnabledTrue();
        
        // Also add treasury centers (they may not have ihbEnabled but can still participate)
        List<LegalEntity> treasuryCenters = legalEntityRepository.findByCanLendTrue();
        for (LegalEntity tc : treasuryCenters) {
            if (!entities.contains(tc)) {
                entities.add(tc);
            }
        }
        
        // Filter by status if provided
        if (status != null && !status.isEmpty()) {
            try {
                LegalEntity.EntityStatus entityStatus = LegalEntity.EntityStatus.valueOf(status.toUpperCase());
                entities = entities.stream()
                        .filter(e -> e.getStatus() == entityStatus)
                        .collect(Collectors.toList());
            } catch (IllegalArgumentException e) {
                // Invalid status, ignore filter
            }
        }
        
        return entities.stream()
                .map(this::mapToEntityWithPosition)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public EntityWithPositionResponse getEntityWithPosition(UUID entityId) {
        LegalEntity entity = legalEntityRepository.findById(entityId)
                .orElseThrow(() -> new BusinessException("Entity not found: " + entityId));
        return mapToEntityWithPosition(entity);
    }

    @Transactional(readOnly = true)
    public EntityPositionDetailResponse getEntityPositionDetail(UUID entityId) {
        LegalEntity entity = legalEntityRepository.findById(entityId)
                .orElseThrow(() -> new BusinessException("Entity not found: " + entityId));

        List<IntercompanyTransaction> asPayer = transactionRepository.findByPayingEntityIdOrderByCreatedAtDesc(entityId);
        List<IntercompanyTransaction> asBehalf = transactionRepository.findByBehalfEntityIdOrderByCreatedAtDesc(entityId);

        BigDecimal totalLent = asPayer.stream()
                .filter(tx -> tx.getStatus() != IntercompanyTransaction.TransactionStatus.SETTLED)
                .map(tx -> tx.getNetAmount() != null ? tx.getNetAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal totalBorrowed = asBehalf.stream()
                .filter(tx -> tx.getStatus() != IntercompanyTransaction.TransactionStatus.SETTLED)
                .map(tx -> tx.getNetAmount() != null ? tx.getNetAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return EntityPositionDetailResponse.builder()
                .entityId(entityId)
                .entityCode(entity.getEntityCode())
                .entityName(entity.getEntityName())
                .loansAsLender(Collections.emptyList())
                .loansAsBorrower(Collections.emptyList())
                .deposits(Collections.emptyList())
                .totalLent(totalLent)
                .totalBorrowed(totalBorrowed)
                .totalDeposited(entity.getTotalDeposited() != null ? entity.getTotalDeposited() : BigDecimal.ZERO)
                .netPosition(totalLent.subtract(totalBorrowed))
                .build();
    }

    @Transactional(readOnly = true)
    public EntityValidationResponse validateEntity(UUID entityId, EntityValidationRequest request) {
        LegalEntity entity = legalEntityRepository.findById(entityId)
                .orElseThrow(() -> new BusinessException("Entity not found: " + entityId));
        
        BigDecimal amount = request.getAmount();
        List<String> warnings = new ArrayList<>();
        boolean valid = true;
        String reason = null;
        
        BigDecimal creditLimit = entity.getIhbCreditLimit() != null ? entity.getIhbCreditLimit() : BigDecimal.ZERO;
        BigDecimal currentExposure = entity.getIhbCurrentExposure() != null ? entity.getIhbCurrentExposure() : BigDecimal.ZERO;
        BigDecimal availableLimit = entity.getIhbAvailableLimit() != null ? entity.getIhbAvailableLimit() : creditLimit.subtract(currentExposure);
        
        if (entity.getStatus() != LegalEntity.EntityStatus.ACTIVE) {
            valid = false;
            reason = "Entity is not active";
        }
        
        if (valid && !Boolean.TRUE.equals(entity.getIsTreasuryCenter()) && !Boolean.TRUE.equals(entity.getIhbEnabled())) {
            valid = false;
            reason = "Entity is not enabled for IHB transactions";
        }
        
        if (valid && Boolean.TRUE.equals(entity.getCanBorrow()) && creditLimit.compareTo(BigDecimal.ZERO) > 0) {
            if (amount.compareTo(availableLimit) > 0) {
                valid = false;
                reason = "Insufficient credit limit. Available: " + availableLimit + ", Required: " + amount;
            }
        }
        
        BigDecimal proposedExposure = currentExposure.add(amount);
        BigDecimal utilizationPercent = creditLimit.compareTo(BigDecimal.ZERO) > 0
                ? proposedExposure.divide(creditLimit, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;
        
        if (utilizationPercent.compareTo(new BigDecimal("80")) > 0) {
            warnings.add("Credit utilization will exceed 80% after this transaction");
        }
        if (amount.compareTo(new BigDecimal("1000000")) > 0) {
            warnings.add("Large transaction amount - additional approval may be required");
        }
        
        return EntityValidationResponse.builder()
                .entityId(entityId)
                .entityCode(entity.getEntityCode())
                .entityName(entity.getEntityName())
                .valid(valid)
                .reason(reason)
                .creditLimit(creditLimit)
                .currentExposure(currentExposure)
                .availableLimit(availableLimit)
                .proposedExposure(proposedExposure)
                .utilizationPercent(utilizationPercent)
                .warnings(warnings)
                .build();
    }

    // ========================================================================
    // POBO OPERATIONS
    // ========================================================================

    @Transactional(readOnly = true)
    public PoboPreviewResponse previewPobo(PoboRequest request) {
        UUID payingEntityId = request.getPayingEntityId();
        UUID behalfEntityId = request.getBehalfEntityId();
        BigDecimal amount = request.getAmount();
        String currencyCode = request.getCurrencyCode() != null ? request.getCurrencyCode() : marketProfile.getDefaultCurrency();
        
        LegalEntity payingEntity = legalEntityRepository.findById(payingEntityId)
                .orElseThrow(() -> new BusinessException("Paying entity not found: " + payingEntityId));
        LegalEntity behalfEntity = legalEntityRepository.findById(behalfEntityId)
                .orElseThrow(() -> new BusinessException("Behalf entity not found: " + behalfEntityId));
        
        if (!Boolean.TRUE.equals(payingEntity.getIsTreasuryCenter()) && !Boolean.TRUE.equals(payingEntity.getCanLend())) {
            throw new BusinessException("Paying entity must be a Treasury Center or enabled for lending");
        }
        
        BigDecimal charges = calculateCharges(amount, "POBO");
        BigDecimal totalAmount = amount.add(charges);
        
        BigDecimal availableLimit = behalfEntity.getIhbAvailableLimit() != null 
                ? behalfEntity.getIhbAvailableLimit() 
                : behalfEntity.getIhbCreditLimit() != null ? behalfEntity.getIhbCreditLimit() : BigDecimal.ZERO;
        boolean withinLimit = availableLimit.compareTo(BigDecimal.ZERO) == 0 || totalAmount.compareTo(availableLimit) <= 0;
        
        BigDecimal interestRate = payingEntity.getLendingRateSpread() != null 
                ? payingEntity.getLendingRateSpread() : new BigDecimal("3.50");
        
        List<String> warnings = new ArrayList<>();
        if (!withinLimit) {
            warnings.add("Transaction exceeds available credit limit");
        }
        if (amount.compareTo(new BigDecimal("1000000")) > 0) {
            warnings.add("Large transaction - additional approval may be required");
        }
        if (!Boolean.TRUE.equals(behalfEntity.getIhbEnabled())) {
            warnings.add("Behalf entity is not IHB enabled - enable IHB first");
        }
        
        return PoboPreviewResponse.builder()
                .payingEntityId(payingEntityId)
                .payingEntityCode(payingEntity.getEntityCode())
                .payingEntityName(payingEntity.getEntityName())
                .behalfEntityId(behalfEntityId)
                .behalfEntityCode(behalfEntity.getEntityCode())
                .behalfEntityName(behalfEntity.getEntityName())
                .amount(amount)
                .currencyCode(currencyCode)
                .charges(charges)
                .totalAmount(totalAmount)
                .ihbLoanPreview(IhbLoanPreview.builder()
                        .lenderId(payingEntityId)
                        .borrowerId(behalfEntityId)
                        .principalAmount(totalAmount)
                        .interestRate(interestRate)
                        .estimatedInterest(totalAmount.multiply(interestRate).divide(new BigDecimal("36500"), 4, RoundingMode.HALF_UP))
                        .build())
                .withinCreditLimit(withinLimit)
                .availableLimit(availableLimit)
                .warnings(warnings)
                .chargeBreakdown(List.of(
                        ChargeBreakdown.builder()
                                .chargeType("PROCESSING_FEE")
                                .description("POBO Processing Fee")
                                .amount(charges)
                                .build()
                ))
                .build();
    }

    public PoboResultResponse executePobo(PoboRequest request) {
        UUID payingEntityId = request.getPayingEntityId();
        UUID behalfEntityId = request.getBehalfEntityId();
        BigDecimal amount = request.getAmount();
        String currencyCode = request.getCurrencyCode() != null ? request.getCurrencyCode() : marketProfile.getDefaultCurrency();
        
        LegalEntity payingEntity = legalEntityRepository.findById(payingEntityId)
                .orElseThrow(() -> new BusinessException("Paying entity not found: " + payingEntityId));
        LegalEntity behalfEntity = legalEntityRepository.findById(behalfEntityId)
                .orElseThrow(() -> new BusinessException("Behalf entity not found: " + behalfEntityId));
        
        BigDecimal charges = calculateCharges(amount, "POBO");
        BigDecimal totalAmount = amount.add(charges);
        
        String transactionRef = "POBO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String loanRef = "LOAN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        UUID ihbLoanId = UUID.randomUUID();
        
        if (Boolean.TRUE.equals(behalfEntity.getIhbEnabled())) {
            behalfEntity.utilizeIhbLimit(totalAmount);
            legalEntityRepository.save(behalfEntity);
        }
        
        if (Boolean.TRUE.equals(payingEntity.getCanLend())) {
            payingEntity.addLentAmount(totalAmount);
            legalEntityRepository.save(payingEntity);
        }
        
        IntercompanyTransaction transaction = IntercompanyTransaction.builder()
                .transactionRef(transactionRef)
                .transactionType(IntercompanyTransaction.TransactionType.POBO)
                .payingEntityId(payingEntityId)
                .payingEntityCode(payingEntity.getEntityCode())
                .payingEntityName(payingEntity.getEntityName())
                .behalfEntityId(behalfEntityId)
                .behalfEntityCode(behalfEntity.getEntityCode())
                .behalfEntityName(behalfEntity.getEntityName())
                .amount(amount)
                .currencyCode(currencyCode)
                .charges(charges)
                .netAmount(totalAmount)
                .ihbLoanId(ihbLoanId)
                .status(IntercompanyTransaction.TransactionStatus.PROCESSED)
                .processedAt(LocalDateTime.now())
                .description(request.getDescription())
                .createdBy("SYSTEM")
                .build();
        
        transaction = transactionRepository.save(transaction);
        log.info("Created POBO transaction: {}", transactionRef);
        
        return PoboResultResponse.builder()
                .transactionRef(transactionRef)
                .status("SUCCESS")
                .message("POBO payment executed successfully")
                .payingEntityId(payingEntityId)
                .payingEntityCode(payingEntity.getEntityCode())
                .behalfEntityId(behalfEntityId)
                .behalfEntityCode(behalfEntity.getEntityCode())
                .amount(amount)
                .currencyCode(currencyCode)
                .charges(charges)
                .totalAmount(totalAmount)
                .ihbLoanId(ihbLoanId)
                .ihbLoanRef(loanRef)
                .processedAt(LocalDateTime.now())
                .build();
    }

    @Transactional(readOnly = true)
    public List<IntercompanyTransactionResponse> getPoboTransactions(
            UUID entityId, String role, String status, LocalDate startDate, LocalDate endDate) {
        List<IntercompanyTransaction> transactions;
        
        if (entityId != null) {
            if ("payer".equalsIgnoreCase(role)) {
                transactions = transactionRepository.findByPayingEntityIdOrderByCreatedAtDesc(entityId);
            } else if ("behalf".equalsIgnoreCase(role)) {
                transactions = transactionRepository.findByBehalfEntityIdOrderByCreatedAtDesc(entityId);
            } else {
                transactions = new ArrayList<>();
                transactions.addAll(transactionRepository.findByPayingEntityIdOrderByCreatedAtDesc(entityId));
                transactions.addAll(transactionRepository.findByBehalfEntityIdOrderByCreatedAtDesc(entityId));
            }
        } else {
            transactions = transactionRepository.findAll();
        }
        
        // Apply all filters
        return transactions.stream()
                // Filter by transaction type (POBO-related types)
                .filter(t -> t.getTransactionType() == IntercompanyTransaction.TransactionType.POBO
                          || t.getTransactionType() == IntercompanyTransaction.TransactionType.POBO_PAYMENT
                          || t.getTransactionType() == IntercompanyTransaction.TransactionType.IC_RECEIVABLE
                          || t.getTransactionType() == IntercompanyTransaction.TransactionType.IC_PAYABLE)
                // Filter by status if provided
                .filter(t -> {
                    if (status == null || status.isEmpty()) return true;
                    try {
                        IntercompanyTransaction.TransactionStatus txStatus =
                            IntercompanyTransaction.TransactionStatus.valueOf(status.toUpperCase());
                        return t.getStatus() == txStatus;
                    } catch (IllegalArgumentException e) {
                        log.warn("Invalid POBO status filter: {}", status);
                        return true;
                    }
                })
                // Filter by date range if provided
                .filter(t -> {
                    if (startDate == null && endDate == null) return true;
                    LocalDateTime createdAt = t.getCreatedAt();
                    if (createdAt == null) return true;
                    LocalDate txDate = createdAt.toLocalDate();
                    if (startDate != null && txDate.isBefore(startDate)) return false;
                    if (endDate != null && txDate.isAfter(endDate)) return false;
                    return true;
                })
                .map(this::mapToTransactionResponse)
                .collect(Collectors.toList());
    }

    // ========================================================================
    // COBO OPERATIONS
    // ========================================================================

    public CoboResultResponse setupCobo(CoboRequest request) {
        UUID collectingEntityId = request.getCollectingEntityId();
        UUID behalfEntityId = request.getBehalfEntityId();
        BigDecimal amount = request.getAmount();
        String currencyCode = request.getCurrencyCode() != null ? request.getCurrencyCode() : marketProfile.getDefaultCurrency();
        
        LegalEntity collectingEntity = legalEntityRepository.findById(collectingEntityId)
                .orElseThrow(() -> new BusinessException("Collecting entity not found: " + collectingEntityId));
        LegalEntity behalfEntity = legalEntityRepository.findById(behalfEntityId)
                .orElseThrow(() -> new BusinessException("Behalf entity not found: " + behalfEntityId));
        
        BigDecimal charges = calculateCharges(amount, "COBO");
        BigDecimal totalAmount = amount.add(charges);
        
        String transactionRef = "COBO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        
        String viban = null;
        UUID vibanId = null;
        if (request.isGenerateViban()) {
            viban = "AE" + String.format("%021d", System.nanoTime() % 1000000000000000000L);
            vibanId = UUID.randomUUID();
        }
        
        IntercompanyTransaction transaction = IntercompanyTransaction.builder()
                .transactionRef(transactionRef)
                .transactionType(IntercompanyTransaction.TransactionType.COBO)
                .payingEntityId(collectingEntityId)
                .payingEntityCode(collectingEntity.getEntityCode())
                .payingEntityName(collectingEntity.getEntityName())
                .behalfEntityId(behalfEntityId)
                .behalfEntityCode(behalfEntity.getEntityCode())
                .behalfEntityName(behalfEntity.getEntityName())
                .amount(amount)
                .currencyCode(currencyCode)
                .charges(charges)
                .netAmount(totalAmount)
                .viban(viban)
                .vibanId(vibanId)
                .status(IntercompanyTransaction.TransactionStatus.ACTIVE)
                .description(request.getDescription())
                .createdBy("SYSTEM")
                .build();
        
        transaction = transactionRepository.save(transaction);
        log.info("Created COBO transaction: {}", transactionRef);
        
        return CoboResultResponse.builder()
                .transactionRef(transactionRef)
                .status("SUCCESS")
                .message("COBO collection setup successfully")
                .collectingEntityId(collectingEntityId)
                .collectingEntityCode(collectingEntity.getEntityCode())
                .behalfEntityId(behalfEntityId)
                .behalfEntityCode(behalfEntity.getEntityCode())
                .amount(amount)
                .currencyCode(currencyCode)
                .charges(charges)
                .totalAmount(totalAmount)
                .viban(viban)
                .vibanId(vibanId)
                .createdAt(LocalDateTime.now())
                .build();
    }

    public CoboCollectionResponse processCoboCollection(String transactionRef, CoboCollectionRequest request) {
        IntercompanyTransaction transaction = transactionRepository.findByTransactionRef(transactionRef)
                .orElseThrow(() -> new BusinessException("Transaction not found: " + transactionRef));
        
        if (transaction.getStatus() != IntercompanyTransaction.TransactionStatus.ACTIVE) {
            throw new BusinessException("Transaction is not in ACTIVE status");
        }
        
        String depositRef = "DEP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        UUID ihbDepositId = UUID.randomUUID();
        
        transaction.setStatus(IntercompanyTransaction.TransactionStatus.PROCESSED);
        transaction.setProcessedAt(LocalDateTime.now());
        transaction.setIhbDepositId(ihbDepositId);
        transactionRepository.save(transaction);
        
        log.info("Processed COBO collection: {}", transactionRef);
        
        return CoboCollectionResponse.builder()
                .transactionRef(transactionRef)
                .status("SUCCESS")
                .message("COBO collection processed")
                .collectedAmount(request.getAmount())
                .ihbDepositId(ihbDepositId)
                .ihbDepositRef(depositRef)
                .processedAt(LocalDateTime.now())
                .build();
    }

    @Transactional(readOnly = true)
    public List<IntercompanyTransactionResponse> getCoboTransactions(
            UUID entityId, String role, String status, LocalDate startDate, LocalDate endDate) {
        List<IntercompanyTransaction> transactions;
        
        if (entityId != null) {
            if ("collector".equalsIgnoreCase(role)) {
                transactions = transactionRepository.findByPayingEntityIdOrderByCreatedAtDesc(entityId);
            } else if ("behalf".equalsIgnoreCase(role)) {
                transactions = transactionRepository.findByBehalfEntityIdOrderByCreatedAtDesc(entityId);
            } else {
                transactions = new ArrayList<>();
                transactions.addAll(transactionRepository.findByPayingEntityIdOrderByCreatedAtDesc(entityId));
                transactions.addAll(transactionRepository.findByBehalfEntityIdOrderByCreatedAtDesc(entityId));
            }
        } else {
            transactions = transactionRepository.findAll();
        }
        
        // Apply all filters
        return transactions.stream()
                // Filter by transaction type (COBO or COBO_COLLECTION)
                .filter(t -> t.getTransactionType() == IntercompanyTransaction.TransactionType.COBO
                          || t.getTransactionType() == IntercompanyTransaction.TransactionType.COBO_COLLECTION)
                // Filter by status if provided
                .filter(t -> {
                    if (status == null || status.isEmpty()) return true;
                    try {
                        IntercompanyTransaction.TransactionStatus txStatus =
                            IntercompanyTransaction.TransactionStatus.valueOf(status.toUpperCase());
                        return t.getStatus() == txStatus;
                    } catch (IllegalArgumentException e) {
                        log.warn("Invalid COBO status filter: {}", status);
                        return true;
                    }
                })
                // Filter by date range if provided
                .filter(t -> {
                    if (startDate == null && endDate == null) return true;
                    LocalDateTime createdAt = t.getCreatedAt();
                    if (createdAt == null) return true;
                    LocalDate txDate = createdAt.toLocalDate();
                    if (startDate != null && txDate.isBefore(startDate)) return false;
                    if (endDate != null && txDate.isAfter(endDate)) return false;
                    return true;
                })
                .map(this::mapToTransactionResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public IntercompanyTransactionDetailResponse getIntercompanyTransaction(String transactionRef) {
        IntercompanyTransaction transaction = transactionRepository.findByTransactionRef(transactionRef)
                .orElseThrow(() -> new BusinessException("Transaction not found: " + transactionRef));
        return mapToTransactionDetailResponse(transaction);
    }

    // ========================================================================
    // SETTLEMENT & STATISTICS (abbreviated for brevity - same logic as before)
    // ========================================================================

    @Transactional(readOnly = true)
    public List<IntercompanyTransactionResponse> getUnsettledTransactions(UUID entityId) {
        List<IntercompanyTransaction> transactions;
        if (entityId != null) {
            transactions = new ArrayList<>();
            transactions.addAll(transactionRepository.findByPayingEntityIdOrderByCreatedAtDesc(entityId));
            transactions.addAll(transactionRepository.findByBehalfEntityIdOrderByCreatedAtDesc(entityId));
        } else {
            transactions = transactionRepository.findAll();
        }
        return transactions.stream()
                .filter(t -> t.getStatus() == IntercompanyTransaction.TransactionStatus.PROCESSED)
                .map(this::mapToTransactionResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public NetSettlementResponse calculateNetSettlement(NetSettlementRequest request) {
        UUID entity1Id = request.getEntity1Id();
        UUID entity2Id = request.getEntity2Id();
        
        List<IntercompanyTransaction> allTransactions = transactionRepository.findAll().stream()
                .filter(t -> t.getStatus() == IntercompanyTransaction.TransactionStatus.PROCESSED)
                .filter(t -> (t.getPayingEntityId().equals(entity1Id) && t.getBehalfEntityId().equals(entity2Id))
                          || (t.getPayingEntityId().equals(entity2Id) && t.getBehalfEntityId().equals(entity1Id)))
                .collect(Collectors.toList());
        
        BigDecimal entity1Owes = allTransactions.stream()
                .filter(t -> t.getBehalfEntityId().equals(entity1Id))
                .map(t -> t.getNetAmount() != null ? t.getNetAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal entity2Owes = allTransactions.stream()
                .filter(t -> t.getBehalfEntityId().equals(entity2Id))
                .map(t -> t.getNetAmount() != null ? t.getNetAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal netAmount = entity1Owes.subtract(entity2Owes).abs();
        
        return NetSettlementResponse.builder()
                .entity1Id(entity1Id)
                .entity2Id(entity2Id)
                .entity1OwesEntity2(entity1Owes)
                .entity2OwesEntity1(entity2Owes)
                .netAmount(netAmount)
                .netPayerId(entity1Owes.compareTo(entity2Owes) > 0 ? entity1Id : entity2Id)
                .netReceiverId(entity1Owes.compareTo(entity2Owes) > 0 ? entity2Id : entity1Id)
                .transactionCount(allTransactions.size())
                .transactionIds(allTransactions.stream().map(IntercompanyTransaction::getId).collect(Collectors.toList()))
                .build();
    }

    public SettlementResultResponse executeSettlement(SettlementRequest request) {
        List<UUID> transactionIds = request.getTransactionIds();
        String settlementRef = "SETTLE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        
        int settledCount = 0;
        BigDecimal totalSettled = BigDecimal.ZERO;
        
        for (UUID txId : transactionIds) {
            Optional<IntercompanyTransaction> optTx = transactionRepository.findById(txId);
            if (optTx.isPresent()) {
                IntercompanyTransaction tx = optTx.get();
                tx.setStatus(IntercompanyTransaction.TransactionStatus.SETTLED);
                tx.setSettlementRef(settlementRef);
                tx.setSettledAt(LocalDateTime.now());
                transactionRepository.save(tx);
                settledCount++;
                totalSettled = totalSettled.add(tx.getNetAmount() != null ? tx.getNetAmount() : BigDecimal.ZERO);
            }
        }
        
        log.info("Settlement {} completed: {} transactions, total {}", settlementRef, settledCount, totalSettled);
        
        return SettlementResultResponse.builder()
                .settlementRef(settlementRef)
                .status("SUCCESS")
                .message("Settlement completed successfully")
                .transactionsSettled(settledCount)
                .totalAmount(totalSettled)
                .settledAt(LocalDateTime.now())
                .build();
    }

    @Transactional(readOnly = true)
    public List<SettlementHistoryResponse> getSettlementHistory(UUID entityId, LocalDate startDate, LocalDate endDate) {
        List<IntercompanyTransaction> settled = transactionRepository.findAll().stream()
                .filter(t -> t.getStatus() == IntercompanyTransaction.TransactionStatus.SETTLED)
                .filter(t -> entityId == null || t.getPayingEntityId().equals(entityId) || t.getBehalfEntityId().equals(entityId))
                .collect(Collectors.toList());
        
        Map<String, List<IntercompanyTransaction>> byRef = settled.stream()
                .filter(t -> t.getSettlementRef() != null)
                .collect(Collectors.groupingBy(IntercompanyTransaction::getSettlementRef));
        
        return byRef.entrySet().stream()
                .map(e -> SettlementHistoryResponse.builder()
                        .settlementRef(e.getKey())
                        .transactionCount(e.getValue().size())
                        .totalAmount(e.getValue().stream()
                                .map(t -> t.getNetAmount() != null ? t.getNetAmount() : BigDecimal.ZERO)
                                .reduce(BigDecimal.ZERO, BigDecimal::add))
                        .settledAt(e.getValue().stream().map(IntercompanyTransaction::getSettledAt).filter(Objects::nonNull).findFirst().orElse(null))
                        .build())
                .sorted(Comparator.comparing(SettlementHistoryResponse::getSettledAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public SettlementDetailResponse getSettlement(String settlementRef) {
        List<IntercompanyTransaction> txs = transactionRepository.findAll().stream()
                .filter(t -> settlementRef.equals(t.getSettlementRef()))
                .collect(Collectors.toList());
        
        if (txs.isEmpty()) throw new BusinessException("Settlement not found: " + settlementRef);
        
        return SettlementDetailResponse.builder()
                .settlementRef(settlementRef)
                .transactionCount(txs.size())
                .totalAmount(txs.stream().map(t -> t.getNetAmount() != null ? t.getNetAmount() : BigDecimal.ZERO).reduce(BigDecimal.ZERO, BigDecimal::add))
                .transactions(txs.stream().map(this::mapToTransactionResponse).collect(Collectors.toList()))
                .settledAt(txs.get(0).getSettledAt())
                .build();
    }

    @Transactional(readOnly = true)
    public IntercompanyStatsResponse getStats() {
        List<IntercompanyTransaction> all = transactionRepository.findAll();
        
        long totalPobo = all.stream().filter(t -> t.getTransactionType() == IntercompanyTransaction.TransactionType.POBO || t.getTransactionType() == IntercompanyTransaction.TransactionType.POBO_PAYMENT).count();
        long totalCobo = all.stream().filter(t -> t.getTransactionType() == IntercompanyTransaction.TransactionType.COBO || t.getTransactionType() == IntercompanyTransaction.TransactionType.COBO_COLLECTION).count();
        long pending = all.stream().filter(t -> t.getStatus() == IntercompanyTransaction.TransactionStatus.PROCESSED).count();
        
        BigDecimal poboVol = all.stream().filter(t -> t.getTransactionType() == IntercompanyTransaction.TransactionType.POBO || t.getTransactionType() == IntercompanyTransaction.TransactionType.POBO_PAYMENT)
                .map(t -> t.getNetAmount() != null ? t.getNetAmount() : BigDecimal.ZERO).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal coboVol = all.stream().filter(t -> t.getTransactionType() == IntercompanyTransaction.TransactionType.COBO || t.getTransactionType() == IntercompanyTransaction.TransactionType.COBO_COLLECTION)
                .map(t -> t.getNetAmount() != null ? t.getNetAmount() : BigDecimal.ZERO).reduce(BigDecimal.ZERO, BigDecimal::add);
        
        // Count IHB-enabled entities + treasury centers
        long activeEntities = legalEntityRepository.findByIhbEnabledTrue().stream()
                .filter(e -> e.getStatus() == LegalEntity.EntityStatus.ACTIVE).count();
        activeEntities += legalEntityRepository.findByCanLendTrue().stream()
                .filter(e -> e.getStatus() == LegalEntity.EntityStatus.ACTIVE && !Boolean.TRUE.equals(e.getIhbEnabled())).count();
        
        return IntercompanyStatsResponse.builder()
                .totalPoboTransactions((int) totalPobo)
                .totalCoboTransactions((int) totalCobo)
                .pendingSettlement((int) pending)
                .totalPoboVolume(poboVol)
                .totalCoboVolume(coboVol)
                .activeEntities((int) activeEntities)
                .build();
    }

    @Transactional(readOnly = true)
    public IntercompanyActivityReport getActivityReport(LocalDate startDate, LocalDate endDate, UUID entityId) {
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.atTime(23, 59, 59);
        
        List<IntercompanyTransaction> txs = transactionRepository.findAll().stream()
                .filter(t -> t.getCreatedAt() != null && !t.getCreatedAt().isBefore(start) && !t.getCreatedAt().isAfter(end))
                .filter(t -> entityId == null || t.getPayingEntityId().equals(entityId) || t.getBehalfEntityId().equals(entityId))
                .collect(Collectors.toList());
        
        return IntercompanyActivityReport.builder()
                .startDate(startDate).endDate(endDate).entityId(entityId)
                .totalTransactions(txs.size())
                .totalVolume(txs.stream().map(t -> t.getNetAmount() != null ? t.getNetAmount() : BigDecimal.ZERO).reduce(BigDecimal.ZERO, BigDecimal::add))
                .poboCount((int) txs.stream().filter(t -> t.getTransactionType() == IntercompanyTransaction.TransactionType.POBO || t.getTransactionType() == IntercompanyTransaction.TransactionType.POBO_PAYMENT).count())
                .coboCount((int) txs.stream().filter(t -> t.getTransactionType() == IntercompanyTransaction.TransactionType.COBO || t.getTransactionType() == IntercompanyTransaction.TransactionType.COBO_COLLECTION).count())
                .settledCount((int) txs.stream().filter(t -> t.getStatus() == IntercompanyTransaction.TransactionStatus.SETTLED).count())
                .build();
    }

    @Transactional(readOnly = true)
    public List<IntercompanyTransactionResponse> getTransactions(UUID corporateId, UUID entityId, String type, String status, int page, int size) {
        List<IntercompanyTransaction> transactions = transactionRepository.findAll();

        // Filter by corporate: get all entity IDs belonging to this corporate
        if (corporateId != null) {
            Set<UUID> corporateEntityIds = legalEntityRepository.findByCorporateIdOrderByHierarchyPath(corporateId).stream()
                    .map(LegalEntity::getId)
                    .collect(Collectors.toSet());
            transactions = transactions.stream()
                    .filter(tx -> corporateEntityIds.contains(tx.getPayingEntityId())
                               || corporateEntityIds.contains(tx.getBehalfEntityId()))
                    .collect(Collectors.toList());
        }

        // Further filter by specific entity if provided
        if (entityId != null) {
            transactions = transactions.stream()
                    .filter(tx -> tx.getPayingEntityId().equals(entityId) || tx.getBehalfEntityId().equals(entityId))
                    .collect(Collectors.toList());
        }
        
        if (type != null && !type.isEmpty()) {
            try {
                IntercompanyTransaction.TransactionType txType = IntercompanyTransaction.TransactionType.valueOf(type.toUpperCase());
                transactions = transactions.stream().filter(tx -> tx.getTransactionType() == txType).collect(Collectors.toList());
            } catch (IllegalArgumentException e) { log.warn("Invalid type filter: {}", type); }
        }
        
        if (status != null && !status.isEmpty()) {
            try {
                IntercompanyTransaction.TransactionStatus txStatus = IntercompanyTransaction.TransactionStatus.valueOf(status.toUpperCase());
                transactions = transactions.stream().filter(tx -> tx.getStatus() == txStatus).collect(Collectors.toList());
            } catch (IllegalArgumentException e) { log.warn("Invalid status filter: {}", status); }
        }
        
        transactions.sort((a, b) -> {
            if (a.getCreatedAt() == null && b.getCreatedAt() == null) return 0;
            if (a.getCreatedAt() == null) return 1;
            if (b.getCreatedAt() == null) return -1;
            return b.getCreatedAt().compareTo(a.getCreatedAt());
        });
        
        int fromIndex = page * size;
        int toIndex = Math.min(fromIndex + size, transactions.size());
        if (fromIndex >= transactions.size()) return Collections.emptyList();
        
        return transactions.subList(fromIndex, toIndex).stream().map(this::mapToTransactionResponse).collect(Collectors.toList());
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private BigDecimal calculateCharges(BigDecimal amount, String transactionType) {
        BigDecimal charge = amount.multiply(new BigDecimal("0.001")).setScale(2, RoundingMode.HALF_UP);
        if (charge.compareTo(new BigDecimal("10")) < 0) charge = new BigDecimal("10");
        if (charge.compareTo(new BigDecimal("500")) > 0) charge = new BigDecimal("500");
        return charge;
    }

    private EntityWithPositionResponse mapToEntityWithPosition(LegalEntity entity) {
        BigDecimal creditLimit = entity.getIhbCreditLimit() != null ? entity.getIhbCreditLimit() : BigDecimal.ZERO;
        BigDecimal currentExposure = entity.getIhbCurrentExposure() != null ? entity.getIhbCurrentExposure() : BigDecimal.ZERO;
        BigDecimal availableLimit = entity.getIhbAvailableLimit() != null ? entity.getIhbAvailableLimit() : creditLimit.subtract(currentExposure);
        
        return EntityWithPositionResponse.builder()
                .entityId(entity.getId())
                .entityCode(entity.getEntityCode())
                .entityName(entity.getEntityName())
                .status(entity.getStatus().name())
                .creditLimit(creditLimit)
                .currentExposure(currentExposure)
                .availableLimit(availableLimit)
                .build();
    }

    private IntercompanyTransactionResponse mapToTransactionResponse(IntercompanyTransaction tx) {
        return IntercompanyTransactionResponse.builder()
                .id(tx.getId())
                .transactionRef(tx.getTransactionRef())
                .transactionType(tx.getTransactionType().name())
                .payingEntityId(tx.getPayingEntityId())
                .payingEntityCode(tx.getPayingEntityCode())
                .payingEntityName(tx.getPayingEntityName())
                .behalfEntityId(tx.getBehalfEntityId())
                .behalfEntityCode(tx.getBehalfEntityCode())
                .behalfEntityName(tx.getBehalfEntityName())
                .amount(tx.getAmount())
                .currencyCode(tx.getCurrencyCode())
                .charges(tx.getCharges())
                .netAmount(tx.getNetAmount())
                .status(tx.getStatus().name())
                .createdAt(tx.getCreatedAt())
                .processedAt(tx.getProcessedAt())
                .build();
    }

    private IntercompanyTransactionDetailResponse mapToTransactionDetailResponse(IntercompanyTransaction tx) {
        return IntercompanyTransactionDetailResponse.builder()
                .id(tx.getId())
                .transactionRef(tx.getTransactionRef())
                .transactionType(tx.getTransactionType().name())
                .payingEntity(EntitySummary.builder().id(tx.getPayingEntityId()).code(tx.getPayingEntityCode()).name(tx.getPayingEntityName()).build())
                .behalfEntity(EntitySummary.builder().id(tx.getBehalfEntityId()).code(tx.getBehalfEntityCode()).name(tx.getBehalfEntityName()).build())
                .amount(tx.getAmount())
                .currencyCode(tx.getCurrencyCode())
                .charges(tx.getCharges())
                .netAmount(tx.getNetAmount())
                .ihbLoanId(tx.getIhbLoanId())
                .ihbDepositId(tx.getIhbDepositId())
                .viban(tx.getViban())
                .vibanId(tx.getVibanId())
                .status(tx.getStatus().name())
                .statusReason(tx.getStatusReason())
                .createdAt(tx.getCreatedAt())
                .processedAt(tx.getProcessedAt())
                .settledAt(tx.getSettledAt())
                .settlementRef(tx.getSettlementRef())
                .createdBy(tx.getCreatedBy())
                .build();
    }
}