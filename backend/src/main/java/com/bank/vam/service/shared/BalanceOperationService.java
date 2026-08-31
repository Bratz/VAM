package com.bank.vam.service.shared;

import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.Transaction.MovementType;
import com.bank.vam.entity.Transaction.TransactionCategory;
import com.bank.vam.entity.Transaction.TransactionStatus;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.VaStatus;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BalanceOperationService - Pure Virtual Architecture v5.0
 * 
 * KEY CHANGES (v5.0):
 * ==================
 * 1. physicalAccountId is OPTIONAL for all operations
 * 2. Added TransactionCategory (INTERNAL vs EXTERNAL) auto-detection
 * 3. Removed physicalAccountId validation and fallback logic
 * 4. All internal transactions (fees, sweeps, transfers) don't require physicalAccountId
 * 
 * PURE VIRTUAL PRINCIPLE:
 * =======================
 * All VA-to-VA operations are internal bookkeeping.
 * No physicalAccountId required for internal movements.
 * Reconciliation uses VA hierarchy traversal to SHADOW account.
 * 
 * This is the FOUNDATION service that all other services use for:
 * - Credit operations (topup, incoming transfer, refund, fee credit)
 * - Debit operations (withdrawal, payment, fee debit)
 * - Transfer operations (atomic debit + credit)
 * - Hold operations (pending authorizations)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceOperationService {

    private final VirtualAccountRepository vaRepository;
    private final TransactionRepository transactionRepository;

    private static final AtomicLong sequence = new AtomicLong(System.currentTimeMillis() % 100000);

    // Movement types that are always INTERNAL (no physical movement)
    private static final Set<MovementType> INTERNAL_MOVEMENT_TYPES = Set.of(
        MovementType.FEE,
        MovementType.FEE_CREDIT,
        MovementType.CHARGE_CREDIT,
        MovementType.TAX_CREDIT,
        MovementType.SETTLEMENT_CREDIT,
        MovementType.INTEREST,
        MovementType.INTEREST_ALLOCATE,
        MovementType.SWEEP_IN,
        MovementType.SWEEP_OUT,
        MovementType.POOL_CREDIT,
        MovementType.POOL_DEBIT,
        MovementType.NETTING,
        MovementType.WALLET_TRANSFER_IN,
        MovementType.WALLET_TRANSFER_OUT,
        MovementType.TRANSFER_IN,
        MovementType.TRANSFER_OUT,
        MovementType.EXCEPTION_PARK,
        MovementType.EXCEPTION_RELEASE,
        MovementType.HIERARCHY_TRANSFER
    );

    // ========================================================================
    // CREDIT OPERATIONS
    // ========================================================================

    /**
     * Credit funds to a Virtual Account.
     * 
     * PURE VIRTUAL: physicalAccountId is optional for internal credits.
     */
    @Transactional
    public OperationResult credit(CreditRequest request) {
        log.debug("Processing credit: VA={}, Amount={}, Type={}", 
            request.getVaId(), request.getAmount(), request.getMovementType());

        // 1. Get and validate VA
        VirtualAccount va = getAndValidateVa(request.getVaId());
        validateForCredit(va, request.getAmount());

        // 2. Calculate new balances
        BigDecimal balanceBefore = va.getCurrentBalance();
        BigDecimal balanceAfter = balanceBefore.add(request.getAmount());

        // 3. Update VA balances
        va.setCurrentBalance(balanceAfter);
        va.setAvailableBalance(va.getAvailableBalance().add(request.getAmount()));
        va.setLastTransactionAt(LocalDateTime.now());
        va.setLastActivityDate(LocalDate.now());
        
        // 4. Update counters based on type
        updateCreditCounters(va, request.getMovementType(), request.getAmount());
        vaRepository.save(va);

        // 5. Create transaction record (physicalAccountId optional)
        Transaction txn = buildTransaction(va, request.getMovementType(), request.getAmount(),
            balanceBefore, balanceAfter, request);
        txn = transactionRepository.save(txn);

        log.info("Credit completed: VA={}, Amount={}, Balance={}, Txn={}", 
            va.getVaNumber(), request.getAmount(), balanceAfter, txn.getReferenceNumber());

        return OperationResult.builder()
            .vaId(va.getId())
            .vaNumber(va.getVaNumber())
            .transactionId(txn.getId())
            .transactionReference(txn.getReferenceNumber())
            .amount(request.getAmount())
            .balanceBefore(balanceBefore)
            .balanceAfter(balanceAfter)
            .currencyCode(va.getCurrencyCode())
            .status("COMPLETED")
            .processedAt(LocalDateTime.now())
            .build();
    }

    // ========================================================================
    // DEBIT OPERATIONS
    // ========================================================================

    /**
     * Debit funds from a Virtual Account.
     * 
     * PURE VIRTUAL: physicalAccountId is optional for internal debits.
     */
    @Transactional
    public OperationResult debit(DebitRequest request) {
        log.debug("Processing debit: VA={}, Amount={}, Type={}", 
            request.getVaId(), request.getAmount(), request.getMovementType());

        // 1. Get and validate VA
        VirtualAccount va = getAndValidateVa(request.getVaId());
        validateForDebit(va, request.getAmount(), request.isValidateLimits());

        // 2. Calculate new balances
        BigDecimal balanceBefore = va.getCurrentBalance();
        BigDecimal balanceAfter = balanceBefore.subtract(request.getAmount());

        // 3. Update VA balances
        va.setCurrentBalance(balanceAfter);
        va.setAvailableBalance(va.getAvailableBalance().subtract(request.getAmount()));
        va.setLastTransactionAt(LocalDateTime.now());
        va.setLastActivityDate(LocalDate.now());
        
        // 4. Update counters and spent tracking
        updateDebitCounters(va, request.getMovementType(), request.getAmount());
        vaRepository.save(va);

        // 5. Create transaction record (physicalAccountId optional)
        Transaction txn = buildTransaction(va, request.getMovementType(), request.getAmount(),
            balanceBefore, balanceAfter, request);
        txn = transactionRepository.save(txn);

        log.info("Debit completed: VA={}, Amount={}, Balance={}, Txn={}", 
            va.getVaNumber(), request.getAmount(), balanceAfter, txn.getReferenceNumber());

        return OperationResult.builder()
            .vaId(va.getId())
            .vaNumber(va.getVaNumber())
            .transactionId(txn.getId())
            .transactionReference(txn.getReferenceNumber())
            .amount(request.getAmount())
            .balanceBefore(balanceBefore)
            .balanceAfter(balanceAfter)
            .currencyCode(va.getCurrencyCode())
            .status("COMPLETED")
            .processedAt(LocalDateTime.now())
            .build();
    }

    // ========================================================================
    // TRANSFER OPERATIONS (Atomic Debit + Credit)
    // ========================================================================

    /**
     * Transfer funds between two Virtual Accounts.
     * Atomic operation - both legs succeed or both fail.
     * 
     * PURE VIRTUAL: This is internal bookkeeping, no physicalAccountId required.
     */
    @Transactional
    public TransferResult transfer(TransferRequest request) {
        log.info("Processing transfer: From={}, To={}, Amount={}", 
            request.getFromVaId(), request.getToVaId(), request.getAmount());

        // Generate correlation ID if not provided
        String correlationId = request.getCorrelationId() != null ? 
            request.getCorrelationId() : generateCorrelationId();

        // 1. Debit source VA
        OperationResult debitResult = debit(DebitRequest.builder()
            .vaId(request.getFromVaId())
            .amount(request.getAmount())
            .movementType(request.getDebitMovementType())
            .description(request.getDescription())
            .correlationId(correlationId)
            .counterpartyVaId(request.getToVaId())
            .channel(request.getChannel())
            .validateLimits(request.isValidateLimits())
            .externalReference(request.getExternalReference())
            .build());

        // 2. Credit destination VA
        OperationResult creditResult = credit(CreditRequest.builder()
            .vaId(request.getToVaId())
            .amount(request.getAmount())
            .movementType(request.getCreditMovementType())
            .description(request.getDescription())
            .correlationId(correlationId)
            .counterpartyVaId(request.getFromVaId())
            .channel(request.getChannel())
            .externalReference(request.getExternalReference())
            .build());

        log.info("Transfer completed: {} -> {}, Amount={}, Correlation={}", 
            debitResult.getVaNumber(), creditResult.getVaNumber(), 
            request.getAmount(), correlationId);

        return TransferResult.builder()
            .correlationId(correlationId)
            .debitResult(debitResult)
            .creditResult(creditResult)
            .amount(request.getAmount())
            .status("COMPLETED")
            .processedAt(LocalDateTime.now())
            .build();
    }

    // ========================================================================
    // HOLD OPERATIONS
    // ========================================================================

    /**
     * Place a hold on available balance.
     */
    @Transactional
    public HoldResult placeHold(UUID vaId, BigDecimal amount, String reason, LocalDateTime expiresAt) {
        VirtualAccount va = getAndValidateVa(vaId);
        
        if (va.getAvailableBalance().compareTo(amount) < 0) {
            throw new BusinessException("Insufficient available balance for hold: " + va.getAvailableBalance());
        }

        va.setAvailableBalance(va.getAvailableBalance().subtract(amount));
        BigDecimal currentHeld = va.getHeldBalance() != null ? va.getHeldBalance() : BigDecimal.ZERO;
        va.setHeldBalance(currentHeld.add(amount));
        vaRepository.save(va);

        String holdReference = generateHoldReference();
        
        log.info("Hold placed: VA={}, Amount={}, Ref={}, Expires={}", 
            va.getVaNumber(), amount, holdReference, expiresAt);

        return HoldResult.builder()
            .holdReference(holdReference)
            .vaId(vaId)
            .amount(amount)
            .reason(reason)
            .expiresAt(expiresAt)
            .status("ACTIVE")
            .build();
    }

    /**
     * Release a hold.
     */
    @Transactional
    public void releaseHold(UUID vaId, BigDecimal amount, String holdReference) {
        VirtualAccount va = getAndValidateVa(vaId);
        
        va.setAvailableBalance(va.getAvailableBalance().add(amount));
        BigDecimal currentHeld = va.getHeldBalance() != null ? va.getHeldBalance() : BigDecimal.ZERO;
        va.setHeldBalance(currentHeld.subtract(amount));
        vaRepository.save(va);

        log.info("Hold released: VA={}, Amount={}, Ref={}", 
            va.getVaNumber(), amount, holdReference);
    }

    /**
     * Capture a held amount (convert hold to actual debit).
     * 
     * PURE VIRTUAL: physicalAccountId is optional.
     */
    @Transactional
    public OperationResult captureHold(UUID vaId, BigDecimal amount, String holdReference,
                                        MovementType movementType, String description) {
        VirtualAccount va = getAndValidateVa(vaId);
        
        BigDecimal currentHeld = va.getHeldBalance() != null ? va.getHeldBalance() : BigDecimal.ZERO;
        if (currentHeld.compareTo(amount) < 0) {
            throw new BusinessException("Insufficient held balance: " + currentHeld);
        }

        // Reduce held balance and current balance
        va.setHeldBalance(currentHeld.subtract(amount));
        BigDecimal balanceBefore = va.getCurrentBalance();
        BigDecimal balanceAfter = balanceBefore.subtract(amount);
        va.setCurrentBalance(balanceAfter);
        va.setLastTransactionAt(LocalDateTime.now());
        vaRepository.save(va);

        // Determine transaction category
        TransactionCategory category = determineTransactionCategory(movementType, null);

        // Create transaction (physicalAccountId is optional)
        Transaction txn = Transaction.builder()
            .referenceNumber(generateReference(movementType))
            .movementType(movementType)
            .transactionCategory(category)
            .corporateId(va.getCorporateId())
            .vaId(va.getId())
            .physicalAccountId(va.getPhysicalAccountId())  // Can be null
            .programId(va.getProgramId())
            .amount(amount)
            .currencyCode(va.getCurrencyCode())
            .balanceBefore(balanceBefore)
            .balanceAfter(balanceAfter)
            .description(description + " (captured from hold " + holdReference + ")")
            .status(TransactionStatus.COMPLETED)
            .transactionDate(LocalDateTime.now())
            .valueDate(LocalDate.now())
            .build();
        txn = transactionRepository.save(txn);

        log.info("Hold captured: VA={}, Amount={}, HoldRef={}, TxnRef={}", 
            va.getVaNumber(), amount, holdReference, txn.getReferenceNumber());

        return OperationResult.builder()
            .vaId(va.getId())
            .vaNumber(va.getVaNumber())
            .transactionId(txn.getId())
            .transactionReference(txn.getReferenceNumber())
            .amount(amount)
            .balanceBefore(balanceBefore)
            .balanceAfter(balanceAfter)
            .status("COMPLETED")
            .build();
    }

    // ========================================================================
    // VALIDATION METHODS
    // ========================================================================

    private VirtualAccount getAndValidateVa(UUID vaId) {
        return vaRepository.findById(vaId)
            .orElseThrow(() -> new BusinessException("Virtual account not found: " + vaId));
    }

    private void validateForCredit(VirtualAccount va, BigDecimal amount) {
        if (va.getStatus() == VaStatus.CLOSED) {
            throw new BusinessException("Cannot credit closed account: " + va.getVaNumber());
        }
        
        if (va.getMaxBalance() != null) {
            BigDecimal newBalance = va.getCurrentBalance().add(amount);
            if (newBalance.compareTo(va.getMaxBalance()) > 0) {
                throw new BusinessException(
                    "Credit would exceed maximum balance limit: " + va.getMaxBalance() + 
                    ". Current: " + va.getCurrentBalance() + ", Credit: " + amount);
            }
        }
    }

    private void validateForDebit(VirtualAccount va, BigDecimal amount, boolean validateLimits) {
        if (va.getStatus() == VaStatus.CLOSED) {
            throw new BusinessException("Cannot debit closed account: " + va.getVaNumber());
        }
        if (va.getStatus() == VaStatus.BLOCKED) {
            throw new BusinessException("Cannot debit blocked account: " + va.getVaNumber());
        }
        if (va.getStatus() == VaStatus.SUSPENDED) {
            throw new BusinessException("Cannot debit suspended account: " + va.getVaNumber());
        }

        if (va.getCurrentBalance().compareTo(amount) < 0) {
            throw new BusinessException(
                "Insufficient balance. Required: " + amount + ", Available: " + va.getCurrentBalance());
        }
        if (va.getAvailableBalance().compareTo(amount) < 0) {
            throw new BusinessException(
                "Insufficient available balance. Required: " + amount + 
                ", Available: " + va.getAvailableBalance() + 
                " (Held: " + (va.getHeldBalance() != null ? va.getHeldBalance() : BigDecimal.ZERO) + ")");
        }

        if (validateLimits) {
            validateLimits(va, amount);
        }
    }

    private void validateLimits(VirtualAccount va, BigDecimal amount) {
        if (va.getPerTransactionLimit() != null && amount.compareTo(va.getPerTransactionLimit()) > 0) {
            throw new BusinessException(
                "Amount " + amount + " exceeds per-transaction limit: " + va.getPerTransactionLimit());
        }

        if (va.getDailyLimit() != null) {
            BigDecimal dailyUsed = va.getDailyUsed() != null ? va.getDailyUsed() : BigDecimal.ZERO;
            if (dailyUsed.add(amount).compareTo(va.getDailyLimit()) > 0) {
                BigDecimal remaining = va.getDailyLimit().subtract(dailyUsed);
                throw new BusinessException(
                    "Amount " + amount + " exceeds remaining daily limit: " + remaining);
            }
        }

        if (va.getWeeklyLimit() != null) {
            BigDecimal weeklyUsed = va.getWeeklyUsed() != null ? va.getWeeklyUsed() : BigDecimal.ZERO;
            if (weeklyUsed.add(amount).compareTo(va.getWeeklyLimit()) > 0) {
                BigDecimal remaining = va.getWeeklyLimit().subtract(weeklyUsed);
                throw new BusinessException(
                    "Amount " + amount + " exceeds remaining weekly limit: " + remaining);
            }
        }

        if (va.getMonthlyLimit() != null) {
            BigDecimal monthlyUsed = va.getMonthlyUsed() != null ? va.getMonthlyUsed() : BigDecimal.ZERO;
            if (monthlyUsed.add(amount).compareTo(va.getMonthlyLimit()) > 0) {
                BigDecimal remaining = va.getMonthlyLimit().subtract(monthlyUsed);
                throw new BusinessException(
                    "Amount " + amount + " exceeds remaining monthly limit: " + remaining);
            }
        }

        if (va.getAnnualLimit() != null) {
            BigDecimal annualUsed = va.getAnnualUsed() != null ? va.getAnnualUsed() : BigDecimal.ZERO;
            if (annualUsed.add(amount).compareTo(va.getAnnualLimit()) > 0) {
                BigDecimal remaining = va.getAnnualLimit().subtract(annualUsed);
                throw new BusinessException(
                    "Amount " + amount + " exceeds remaining annual limit: " + remaining);
            }
        }
    }

    // ========================================================================
    // COUNTER UPDATE METHODS
    // ========================================================================

    private void updateCreditCounters(VirtualAccount va, MovementType type, BigDecimal amount) {
        va.setTransactionCount(va.getTransactionCount() != null ? va.getTransactionCount() + 1 : 1);
        
        if (type == MovementType.TOPUP) {
            va.setTopupCount(va.getTopupCount() != null ? va.getTopupCount() + 1 : 1);
            va.setLastTopupAt(LocalDateTime.now());
            va.setDailyTopupUsed(va.getDailyTopupUsed() != null ? 
                va.getDailyTopupUsed().add(amount) : amount);
            va.setMonthlyTopupUsed(va.getMonthlyTopupUsed() != null ? 
                va.getMonthlyTopupUsed().add(amount) : amount);
        }
    }

    private void updateDebitCounters(VirtualAccount va, MovementType type, BigDecimal amount) {
        va.setTransactionCount(va.getTransactionCount() != null ? va.getTransactionCount() + 1 : 1);
        
        if (type == MovementType.WITHDRAWAL) {
            va.setWithdrawalCount(va.getWithdrawalCount() != null ? va.getWithdrawalCount() + 1 : 1);
            va.setLastWithdrawalAt(LocalDateTime.now());
        }

        // Fee debits should not count towards limits
        if (type != MovementType.FEE && !isFeeMovement(type)) {
            va.setDailyUsed(va.getDailyUsed() != null ? va.getDailyUsed().add(amount) : amount);
            va.setWeeklyUsed(va.getWeeklyUsed() != null ? va.getWeeklyUsed().add(amount) : amount);
            va.setMonthlyUsed(va.getMonthlyUsed() != null ? va.getMonthlyUsed().add(amount) : amount);
            va.setAnnualUsed(va.getAnnualUsed() != null ? va.getAnnualUsed().add(amount) : amount);
        }
    }

    private boolean isFeeMovement(MovementType type) {
        return type == MovementType.FEE || 
               type == MovementType.FEE_CREDIT ||
               type == MovementType.CHARGE_CREDIT ||
               type == MovementType.TAX_CREDIT;
    }

    // ========================================================================
    // TRANSACTION BUILDING - PURE VIRTUAL (physicalAccountId optional)
    // ========================================================================

    /**
     * Build a transaction record.
     * 
     * PURE VIRTUAL v5.0:
     * - physicalAccountId is OPTIONAL (can be null)
     * - TransactionCategory is auto-determined based on MovementType
     * - No validation or fallback for physicalAccountId
     */
    private Transaction buildTransaction(VirtualAccount va, MovementType type, BigDecimal amount,
                                          BigDecimal balanceBefore, BigDecimal balanceAfter,
                                          OperationRequest request) {
        
        // Determine transaction category (INTERNAL vs EXTERNAL)
        TransactionCategory category = determineTransactionCategory(type, request.getCounterpartyVaId());
        
        // physicalAccountId is optional - just use whatever VA has (can be null)
        UUID physicalAccountId = va.getPhysicalAccountId();
        
        // NO validation, NO fallback - this is pure virtual

        return Transaction.builder()
            .referenceNumber(generateReference(type))
            .movementType(type)
            .transactionCategory(category)
            .corporateId(va.getCorporateId())
            .vaId(va.getId())
            .physicalAccountId(physicalAccountId)  // Can be null for internal transactions
            .programId(va.getProgramId())
            .amount(amount)
            .currencyCode(va.getCurrencyCode())
            .balanceBefore(balanceBefore)
            .balanceAfter(balanceAfter)
            .counterpartyVaId(request.getCounterpartyVaId())
            .correlationId(request.getCorrelationId())
            .externalReference(request.getExternalReference())
            .description(request.getDescription())
            .channel(request.getChannel() != null ? request.getChannel() : "API")
            .status(TransactionStatus.COMPLETED)
            .transactionDate(LocalDateTime.now())
            .valueDate(LocalDate.now())
            .build();
    }

    /**
     * Determine transaction category based on movement type.
     * 
     * INTERNAL: Pure VA bookkeeping (fees, transfers, sweeps)
     * EXTERNAL: Real money movement (deposits, withdrawals, payments)
     */
    private TransactionCategory determineTransactionCategory(MovementType type, UUID counterpartyVaId) {
        // Known internal movement types
        if (INTERNAL_MOVEMENT_TYPES.contains(type)) {
            return TransactionCategory.INTERNAL;
        }
        
        // VA-to-VA transfer is internal
        if (counterpartyVaId != null) {
            return TransactionCategory.INTERNAL;
        }
        
        // Default to external for real money movement
        return TransactionCategory.EXTERNAL;
    }

    // ========================================================================
    // REFERENCE GENERATION
    // ========================================================================

    private String generateReference(MovementType type) {
        String prefix = switch (type) {
            case CREDIT -> "CRD";
            case DEBIT -> "DBT";
            case TOPUP -> "TOP";
            case WITHDRAWAL -> "WDR";
            case WALLET_TRANSFER_IN, WALLET_TRANSFER_OUT -> "TRF";
            case SWEEP_IN, SWEEP_OUT -> "SWP";
            case POOL_CREDIT, POOL_DEBIT -> "POL";
            case NETTING -> "NET";
            case FEE, FEE_CREDIT, CHARGE_CREDIT, TAX_CREDIT -> "FEE";
            case INTEREST, INTEREST_ALLOCATE -> "INT";
            case PAYMENT, PURCHASE -> "PAY";
            case REFUND -> "REF";
            case ROBO_CREDIT, POBO_DEBIT -> "OBO";
            case SETTLEMENT_CREDIT -> "SET";
            case EXCEPTION_PARK -> "EXP";
            case EXCEPTION_RELEASE -> "EXR";
            default -> "TXN";
        };
        return String.format("%s-%d-%04d", prefix, 
            System.currentTimeMillis() % 100000000, 
            sequence.incrementAndGet() % 10000);
    }

    private String generateCorrelationId() {
        return "COR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private String generateHoldReference() {
        return "HOLD-" + System.currentTimeMillis() + "-" + 
            String.format("%04d", sequence.incrementAndGet() % 10000);
    }

    // ========================================================================
    // REQUEST INTERFACE AND DTOs
    // ========================================================================

    public interface OperationRequest {
        String getCorrelationId();
        String getExternalReference();
        UUID getCounterpartyVaId();
        String getDescription();
        String getChannel();
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class CreditRequest implements OperationRequest {
        private UUID vaId;
        private BigDecimal amount;
        private MovementType movementType;
        private String description;
        private String correlationId;
        private String externalReference;
        private UUID counterpartyVaId;
        private String channel;
        private String remitterName;
        private String remitterAccount;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class DebitRequest implements OperationRequest {
        private UUID vaId;
        private BigDecimal amount;
        private MovementType movementType;
        private String description;
        private String correlationId;
        private String externalReference;
        private UUID counterpartyVaId;
        private String channel;
        private boolean validateLimits;
        private String beneficiaryName;
        private String beneficiaryAccount;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TransferRequest {
        private UUID fromVaId;
        private UUID toVaId;
        private BigDecimal amount;
        private MovementType debitMovementType;
        private MovementType creditMovementType;
        private String description;
        private String correlationId;
        private String externalReference;
        private String channel;
        private boolean validateLimits;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class OperationResult {
        private UUID vaId;
        private String vaNumber;
        private UUID transactionId;
        private String transactionReference;
        private BigDecimal amount;
        private BigDecimal balanceBefore;
        private BigDecimal balanceAfter;
        private String currencyCode;
        private String status;
        private LocalDateTime processedAt;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TransferResult {
        private String correlationId;
        private OperationResult debitResult;
        private OperationResult creditResult;
        private BigDecimal amount;
        private String status;
        private LocalDateTime processedAt;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class HoldResult {
        private String holdReference;
        private UUID vaId;
        private BigDecimal amount;
        private String reason;
        private LocalDateTime expiresAt;
        private String status;
    }
}