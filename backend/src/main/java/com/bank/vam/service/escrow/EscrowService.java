package com.bank.vam.service.escrow;

import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.Transaction.MovementType;
import com.bank.vam.entity.Transaction.TransactionStatus;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.VaStatus;
import com.bank.vam.entity.escrow.EscrowContract;
import com.bank.vam.entity.escrow.EscrowContract.EscrowStatus;
import com.bank.vam.entity.escrow.EscrowContract.EscrowType;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.escrow.EscrowContractRepository;
import com.bank.vam.service.treasury.FeePostingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Escrow Service - Manages escrow contracts and milestone-based releases.
 * 
 * Uses dedicated EscrowContract entity (not VirtualAccount).
 * Creates a linked VA for holding escrow funds.
 * 
 * Enhanced with Settlement VA fee posting:
 * - Setup fee: 0.2% of contract value (min AED 100, max AED 2,000)
 * - Release fee: 0.1% of release amount (min AED 25)
 * - Extension fee: AED 50 flat
 * 
 * Flow:
 * 1. Create escrow contract + linked VA (PENDING_FUNDING)
 * 2. Fund escrow VA from buyer (FUNDED)
 * 3. Release funds upon milestone completion (PARTIALLY_RELEASED -> RELEASED)
 * 4. Handle disputes
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EscrowService {

    private final EscrowContractRepository escrowRepository;
    private final VirtualAccountRepository vaRepository;
    private final TransactionRepository transactionRepository;
    private final FeePostingService feePostingService;
    
    // Fee constants
    private static final BigDecimal ESCROW_SETUP_FEE_RATE = new BigDecimal("0.002");  // 0.2%
    private static final BigDecimal ESCROW_SETUP_FEE_MIN = new BigDecimal("100.00");
    private static final BigDecimal ESCROW_SETUP_FEE_MAX = new BigDecimal("2000.00");
    private static final BigDecimal ESCROW_RELEASE_FEE_RATE = new BigDecimal("0.001");  // 0.1%
    private static final BigDecimal ESCROW_RELEASE_FEE_MIN = new BigDecimal("25.00");
    private static final BigDecimal ESCROW_EXTENSION_FEE = new BigDecimal("50.00");
    
    // ========================================================================
    // ESCROW CREATION
    // ========================================================================
    
    /**
     * Create a new escrow contract with linked VA.
     */
    @Transactional
    public EscrowResponse createEscrow(CreateEscrowRequest request) {
        log.info("Creating escrow: type={}, amount={}, buyer={}, seller={}", 
            request.getEscrowType(), request.getContractAmount(), 
            request.getBuyerName(), request.getSellerName());
        
        // Generate escrow reference
        String escrowReference = generateEscrowReference(request.getEscrowType());
        
        // Calculate setup fee
        BigDecimal setupFee = calculateSetupFee(request.getContractAmount());
        
        // Create escrow VA to hold funds
        VirtualAccount escrowVa = VirtualAccount.builder()
            .vaNumber("ESC-VA-" + System.currentTimeMillis())
            .vaName("Escrow: " + request.getBuyerName() + " / " + request.getSellerName())
            .corporateId(request.getCorporateId())
            .programId(request.getProgramId())
            .physicalAccountId(request.getPhysicalAccountId())
            .currencyCode(request.getCurrencyCode() != null ? request.getCurrencyCode() : "AED")
            .currentBalance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO)
            .status(VaStatus.ACTIVE)
            .build();
        
        escrowVa = vaRepository.save(escrowVa);
        
        // Create escrow contract
        EscrowContract escrow = EscrowContract.builder()
            .escrowReference(escrowReference)
            .escrowType(EscrowType.valueOf(request.getEscrowType()))
            .buyerId(request.getBuyerId())
            .buyerName(request.getBuyerName())
            .sellerId(request.getSellerId())
            .sellerName(request.getSellerName())
            .escrowVaId(escrowVa.getId())
            .corporateId(request.getCorporateId())
            .programId(request.getProgramId())
            .contractAmount(request.getContractAmount())
            .currencyCode(request.getCurrencyCode() != null ? request.getCurrencyCode() : "AED")
            .setupFee(setupFee)
            .expiryDate(request.getExpiryDate())
            .releaseConditions(request.getReleaseConditions() != null ? 
                String.join(";", request.getReleaseConditions()) : null)
            .status(EscrowStatus.PENDING_FUNDING)
            .build();
        
        escrow = escrowRepository.save(escrow);
        
        log.info("Created escrow contract: {}, VA: {}, setup fee: {}", 
            escrowReference, escrowVa.getVaNumber(), setupFee);
        
        return toEscrowResponse(escrow, escrowVa);
    }
    
    // ========================================================================
    // ESCROW FUNDING
    // ========================================================================
    
    /**
     * Fund the escrow account.
     * Charges setup fee on first funding.
     */
    @Transactional
    public FundEscrowResponse fundEscrow(UUID escrowId, FundEscrowRequest request) {
        EscrowContract escrow = escrowRepository.findById(escrowId)
            .orElseThrow(() -> new ResourceNotFoundException("Escrow not found: " + escrowId));
        
        if (!escrow.canFund()) {
            throw new BusinessException("Cannot fund escrow in status: " + escrow.getStatus());
        }
        
        VirtualAccount escrowVa = vaRepository.findById(escrow.getEscrowVaId())
            .orElseThrow(() -> new ResourceNotFoundException("Escrow VA not found"));
        
        BigDecimal balanceBefore = escrowVa.getCurrentBalance();
        BigDecimal fundAmount = request.getAmount();
        BigDecimal setupFeeCharged = BigDecimal.ZERO;
        
        String correlationId = "ESC-FUND-" + System.currentTimeMillis();
        
        // Charge setup fee on first funding
        if (!Boolean.TRUE.equals(escrow.getSetupFeeCharged()) && 
            escrow.getSetupFee() != null &&
            escrow.getSetupFee().compareTo(BigDecimal.ZERO) > 0) {
            
            setupFeeCharged = escrow.getSetupFee();
            fundAmount = fundAmount.subtract(setupFeeCharged);
            
            if (fundAmount.compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException("Funding amount must be greater than setup fee: " + setupFeeCharged);
            }
            
            escrow.setSetupFeeCharged(true);
            escrow.recordFeeCharged(setupFeeCharged);
        }
        
        // Credit escrow VA
        escrowVa.credit(fundAmount);
        vaRepository.save(escrowVa);
        
        // Update escrow contract
        escrow.recordFunding(fundAmount);
        escrowRepository.save(escrow);
        
        // Record funding transaction
        Transaction txn = Transaction.builder()
            .referenceNumber(generateTransactionReference("ESCFND"))
            .movementType(MovementType.CREDIT)  // Use CREDIT for funding
            .corporateId(escrow.getCorporateId())
            .vaId(escrowVa.getId())
            .physicalAccountId(escrowVa.getPhysicalAccountId())
            .programId(escrow.getProgramId())
            .amount(fundAmount)
            .currencyCode(escrow.getCurrencyCode())
            .balanceBefore(balanceBefore)
            .balanceAfter(escrowVa.getCurrentBalance())
            .correlationId(correlationId)
            .remitterName(request.getFunderName())
            .remitterAccount(request.getSourceAccount())
            .description("Escrow funding: " + escrow.getEscrowReference())
            .externalReference(request.getReference())
            .feeAmount(setupFeeCharged)
            .status(TransactionStatus.COMPLETED)
            .transactionDate(LocalDateTime.now())
            .valueDate(LocalDate.now())
            .channel("ESCROW")
            .build();
        
        txn = transactionRepository.save(txn);
        
        // Post setup fee to Settlement VA
        if (setupFeeCharged.compareTo(BigDecimal.ZERO) > 0) {
            postEscrowSetupFee(escrowVa, setupFeeCharged, txn, correlationId, escrow);
        }
        
        log.info("Funded escrow {}: amount={}, fee={}, new balance={}", 
            escrow.getEscrowReference(), fundAmount, setupFeeCharged, escrowVa.getCurrentBalance());
        
        return FundEscrowResponse.builder()
            .escrowId(escrowId)
            .escrowReference(escrow.getEscrowReference())
            .fundedAmount(fundAmount)
            .setupFeeCharged(setupFeeCharged)
            .totalBalance(escrowVa.getCurrentBalance())
            .escrowStatus(escrow.getStatus().name())
            .transactionReference(txn.getReferenceNumber())
            .correlationId(correlationId)
            .fundedAt(LocalDateTime.now())
            .build();
    }
    
    // ========================================================================
    // ESCROW RELEASE
    // ========================================================================
    
    /**
     * Release funds from escrow (milestone completion).
     * Charges release fee.
     */
    @Transactional
    public ReleaseEscrowResponse releaseFunds(UUID escrowId, ReleaseEscrowRequest request) {
        EscrowContract escrow = escrowRepository.findById(escrowId)
            .orElseThrow(() -> new ResourceNotFoundException("Escrow not found: " + escrowId));
        
        if (!escrow.canRelease()) {
            throw new BusinessException("Cannot release from escrow in status: " + escrow.getStatus());
        }
        
        VirtualAccount escrowVa = vaRepository.findById(escrow.getEscrowVaId())
            .orElseThrow(() -> new ResourceNotFoundException("Escrow VA not found"));
        
        // Calculate release fee
        BigDecimal releaseFee = calculateReleaseFee(request.getAmount());
        BigDecimal totalDebit = request.getAmount().add(releaseFee);
        
        if (escrowVa.getCurrentBalance().compareTo(totalDebit) < 0) {
            throw new BusinessException("Insufficient escrow balance. Available: " + 
                escrowVa.getCurrentBalance() + ", Required: " + totalDebit);
        }
        
        BigDecimal balanceBefore = escrowVa.getCurrentBalance();
        String correlationId = "ESC-REL-" + System.currentTimeMillis();
        
        // Debit escrow VA
        escrowVa.debit(totalDebit);
        vaRepository.save(escrowVa);
        
        // Update escrow contract
        escrow.recordRelease(request.getAmount());
        escrow.recordFeeCharged(releaseFee);
        escrowRepository.save(escrow);
        
        // Record release transaction
        Transaction txn = Transaction.builder()
            .referenceNumber(generateTransactionReference("ESCREL"))
            .movementType(MovementType.DEBIT)  // Use DEBIT for release
            .corporateId(escrow.getCorporateId())
            .vaId(escrowVa.getId())
            .physicalAccountId(escrowVa.getPhysicalAccountId())
            .programId(escrow.getProgramId())
            .amount(request.getAmount())
            .currencyCode(escrow.getCurrencyCode())
            .balanceBefore(balanceBefore)
            .balanceAfter(escrowVa.getCurrentBalance())
            .correlationId(correlationId)
            .beneficiaryName(request.getReleaseTo())
            .beneficiaryAccount(request.getDestinationAccount())
            .description("Escrow release: " + (request.getMilestone() != null ? request.getMilestone() : "Full release"))
            .feeAmount(releaseFee)
            .status(TransactionStatus.COMPLETED)
            .transactionDate(LocalDateTime.now())
            .valueDate(LocalDate.now())
            .channel("ESCROW")
            .processingNotes("Approved by: " + request.getApprovedBy())
            .build();
        
        txn = transactionRepository.save(txn);
        
        // Post release fee to Settlement VA
        if (releaseFee.compareTo(BigDecimal.ZERO) > 0) {
            postEscrowReleaseFee(escrowVa, releaseFee, txn, correlationId, request.getMilestone(), escrow);
        }
        
        log.info("Released from escrow {}: amount={}, fee={}, remaining balance={}", 
            escrow.getEscrowReference(), request.getAmount(), releaseFee, escrowVa.getCurrentBalance());
        
        return ReleaseEscrowResponse.builder()
            .escrowId(escrowId)
            .escrowReference(escrow.getEscrowReference())
            .releasedAmount(request.getAmount())
            .releaseFeeCharged(releaseFee)
            .remainingBalance(escrowVa.getCurrentBalance())
            .escrowStatus(escrow.getStatus().name())
            .releasedTo(request.getReleaseTo())
            .transactionReference(txn.getReferenceNumber())
            .correlationId(correlationId)
            .milestone(request.getMilestone())
            .approvedBy(request.getApprovedBy())
            .releasedAt(LocalDateTime.now())
            .build();
    }
    
    // ========================================================================
    // ESCROW EXTENSION
    // ========================================================================
    
    /**
     * Extend escrow expiry date.
     * Charges extension fee from escrow balance.
     */
    @Transactional
    public ExtendEscrowResponse extendEscrow(UUID escrowId, ExtendEscrowRequest request) {
        EscrowContract escrow = escrowRepository.findById(escrowId)
            .orElseThrow(() -> new ResourceNotFoundException("Escrow not found: " + escrowId));
        
        if (escrow.getStatus() == EscrowStatus.RELEASED ||
            escrow.getStatus() == EscrowStatus.CANCELLED) {
            throw new BusinessException("Cannot extend escrow in status: " + escrow.getStatus());
        }
        
        VirtualAccount escrowVa = vaRepository.findById(escrow.getEscrowVaId())
            .orElseThrow(() -> new ResourceNotFoundException("Escrow VA not found"));
        
        LocalDate previousExpiry = escrow.getExpiryDate();
        escrow.setExpiryDate(request.getNewExpiryDate());
        
        String correlationId = "ESC-EXT-" + System.currentTimeMillis();
        BigDecimal extensionFeeCharged = BigDecimal.ZERO;
        
        // Check if escrow has balance to pay extension fee
        if (escrowVa.getCurrentBalance().compareTo(ESCROW_EXTENSION_FEE) >= 0) {
            // Debit extension fee from escrow
            BigDecimal balanceBefore = escrowVa.getCurrentBalance();
            escrowVa.debit(ESCROW_EXTENSION_FEE);
            vaRepository.save(escrowVa);
            
            escrow.recordFeeCharged(ESCROW_EXTENSION_FEE);
            extensionFeeCharged = ESCROW_EXTENSION_FEE;
            
            // Record fee transaction
            Transaction txn = Transaction.builder()
                .referenceNumber(generateTransactionReference("ESCEXT"))
                .movementType(MovementType.FEE)
                .corporateId(escrow.getCorporateId())
                .vaId(escrowVa.getId())
                .physicalAccountId(escrowVa.getPhysicalAccountId())
                .programId(escrow.getProgramId())
                .amount(ESCROW_EXTENSION_FEE)
                .currencyCode(escrow.getCurrencyCode())
                .balanceBefore(balanceBefore)
                .balanceAfter(escrowVa.getCurrentBalance())
                .correlationId(correlationId)
                .description("Escrow extension fee")
                .feeAmount(ESCROW_EXTENSION_FEE)
                .status(TransactionStatus.COMPLETED)
                .transactionDate(LocalDateTime.now())
                .valueDate(LocalDate.now())
                .channel("ESCROW")
                .build();
            
            txn = transactionRepository.save(txn);
            
            // Post extension fee to Settlement VA
            postEscrowExtensionFee(escrowVa, txn, correlationId, escrow);
        }
        
        escrowRepository.save(escrow);
        
        log.info("Extended escrow {} from {} to {}", 
            escrow.getEscrowReference(), previousExpiry, request.getNewExpiryDate());
        
        return ExtendEscrowResponse.builder()
            .escrowId(escrowId)
            .escrowReference(escrow.getEscrowReference())
            .previousExpiryDate(previousExpiry)
            .newExpiryDate(request.getNewExpiryDate())
            .extensionFee(extensionFeeCharged)
            .correlationId(correlationId)
            .build();
    }
    
    // ========================================================================
    // ESCROW QUERIES
    // ========================================================================
    
    @Transactional(readOnly = true)
    public EscrowResponse getEscrow(UUID escrowId) {
        EscrowContract escrow = escrowRepository.findById(escrowId)
            .orElseThrow(() -> new ResourceNotFoundException("Escrow not found: " + escrowId));
        VirtualAccount escrowVa = vaRepository.findById(escrow.getEscrowVaId()).orElse(null);
        return toEscrowResponse(escrow, escrowVa);
    }
    
    @Transactional(readOnly = true)
    public List<EscrowResponse> getEscrowsByCorporate(UUID corporateId) {
        return escrowRepository.findByCorporateId(corporateId).stream()
            .map(e -> {
                VirtualAccount va = vaRepository.findById(e.getEscrowVaId()).orElse(null);
                return toEscrowResponse(e, va);
            })
            .toList();
    }
    
    // ========================================================================
    // FEE POSTING
    // ========================================================================
    
    private void postEscrowSetupFee(VirtualAccount escrowVa, BigDecimal fee, 
                                     Transaction sourceTransaction, String correlationId,
                                     EscrowContract escrow) {
        try {
            feePostingService.postFee(
                escrowVa.getId(),
                fee,
                "ESCROW_SETUP_FEE",
                sourceTransaction.getId(),
                "Escrow setup fee - " + escrow.getEscrowReference()
            );
            log.info("Posted escrow setup fee {} for {}", fee, escrow.getEscrowReference());
        } catch (Exception e) {
            log.error("Failed to post escrow setup fee for {}: {}", escrow.getEscrowReference(), e.getMessage());
        }
    }
    
    private void postEscrowReleaseFee(VirtualAccount escrowVa, BigDecimal fee,
                                       Transaction sourceTransaction, String correlationId,
                                       String milestone, EscrowContract escrow) {
        try {
            String description = "Escrow release fee - " + escrow.getEscrowReference();
            if (milestone != null) {
                description += " - " + milestone;
            }
            
            feePostingService.postFee(
                escrowVa.getId(),
                fee,
                "ESCROW_RELEASE_FEE",
                sourceTransaction.getId(),
                description
            );
            log.info("Posted escrow release fee {} for {}", fee, escrow.getEscrowReference());
        } catch (Exception e) {
            log.error("Failed to post escrow release fee for {}: {}", escrow.getEscrowReference(), e.getMessage());
        }
    }
    
    private void postEscrowExtensionFee(VirtualAccount escrowVa, Transaction sourceTransaction,
                                         String correlationId, EscrowContract escrow) {
        try {
            feePostingService.postFee(
                escrowVa.getId(),
                ESCROW_EXTENSION_FEE,
                "ESCROW_EXTENSION_FEE",
                sourceTransaction.getId(),
                "Escrow extension fee - " + escrow.getEscrowReference()
            );
            log.info("Posted escrow extension fee {} for {}", ESCROW_EXTENSION_FEE, escrow.getEscrowReference());
        } catch (Exception e) {
            log.error("Failed to post escrow extension fee for {}: {}", escrow.getEscrowReference(), e.getMessage());
        }
    }
    
    // ========================================================================
    // FEE CALCULATION
    // ========================================================================
    
    private BigDecimal calculateSetupFee(BigDecimal contractAmount) {
        BigDecimal fee = contractAmount.multiply(ESCROW_SETUP_FEE_RATE)
            .setScale(2, RoundingMode.HALF_UP);
        
        if (fee.compareTo(ESCROW_SETUP_FEE_MIN) < 0) {
            fee = ESCROW_SETUP_FEE_MIN;
        }
        if (fee.compareTo(ESCROW_SETUP_FEE_MAX) > 0) {
            fee = ESCROW_SETUP_FEE_MAX;
        }
        
        return fee;
    }
    
    private BigDecimal calculateReleaseFee(BigDecimal releaseAmount) {
        BigDecimal fee = releaseAmount.multiply(ESCROW_RELEASE_FEE_RATE)
            .setScale(2, RoundingMode.HALF_UP);
        
        if (fee.compareTo(ESCROW_RELEASE_FEE_MIN) < 0) {
            fee = ESCROW_RELEASE_FEE_MIN;
        }
        
        return fee;
    }
    
    // ========================================================================
    // HELPER METHODS
    // ========================================================================
    
    private String generateEscrowReference(String escrowType) {
        String prefix = "ESC";
        if (escrowType != null) {
            switch (escrowType.toUpperCase()) {
                case "TRADE" -> prefix = "ESC-TR";
                case "REAL_ESTATE" -> prefix = "ESC-RE";
                case "M_AND_A" -> prefix = "ESC-MA";
                case "MILESTONE" -> prefix = "ESC-MS";
                case "RENT" -> prefix = "ESC-RT";
            }
        }
        return prefix + "-" + LocalDate.now().getYear() + "-" + 
               String.format("%05d", System.currentTimeMillis() % 100000);
    }
    
    private String generateTransactionReference(String prefix) {
        return prefix + System.currentTimeMillis() + String.format("%04d", (int)(Math.random() * 10000));
    }
    
    private EscrowResponse toEscrowResponse(EscrowContract escrow, VirtualAccount escrowVa) {
        return EscrowResponse.builder()
            .id(escrow.getId())
            .escrowReference(escrow.getEscrowReference())
            .escrowType(escrow.getEscrowType().name())
            .buyerId(escrow.getBuyerId())
            .buyerName(escrow.getBuyerName())
            .sellerId(escrow.getSellerId())
            .sellerName(escrow.getSellerName())
            .contractAmount(escrow.getContractAmount())
            .fundedAmount(escrow.getFundedAmount())
            .currentBalance(escrowVa != null ? escrowVa.getCurrentBalance() : escrow.getCurrentBalance())
            .releasedAmount(escrow.getReleasedAmount())
            .currencyCode(escrow.getCurrencyCode())
            .status(escrow.getStatus().name())
            .expiryDate(escrow.getExpiryDate())
            .setupFee(escrow.getSetupFee())
            .totalFeesCharged(escrow.getTotalFeesCharged())
            .escrowVaId(escrow.getEscrowVaId())
            .createdAt(escrow.getCreatedAt())
            .build();
    }
    
    // ========================================================================
    // DTOs
    // ========================================================================
    
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class CreateEscrowRequest {
        private UUID corporateId;
        private UUID programId;
        private UUID physicalAccountId;
        private String escrowType;
        private UUID buyerId;
        private String buyerName;
        private UUID sellerId;
        private String sellerName;
        private BigDecimal contractAmount;
        private String currencyCode;
        private LocalDate expiryDate;
        private List<String> releaseConditions;
    }
    
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class EscrowResponse {
        private UUID id;
        private String escrowReference;
        private String escrowType;
        private UUID buyerId;
        private String buyerName;
        private UUID sellerId;
        private String sellerName;
        private BigDecimal contractAmount;
        private BigDecimal fundedAmount;
        private BigDecimal currentBalance;
        private BigDecimal releasedAmount;
        private String currencyCode;
        private String status;
        private LocalDate expiryDate;
        private BigDecimal setupFee;
        private BigDecimal totalFeesCharged;
        private UUID escrowVaId;
        private LocalDateTime createdAt;
    }
    
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class FundEscrowRequest {
        private BigDecimal amount;
        private String funderName;
        private String sourceAccount;
        private String reference;
    }
    
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class FundEscrowResponse {
        private UUID escrowId;
        private String escrowReference;
        private BigDecimal fundedAmount;
        private BigDecimal setupFeeCharged;
        private BigDecimal totalBalance;
        private String escrowStatus;
        private String transactionReference;
        private String correlationId;
        private LocalDateTime fundedAt;
    }
    
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ReleaseEscrowRequest {
        private BigDecimal amount;
        private String releaseTo;
        private String destinationAccount;
        private String milestone;
        private String approvedBy;
        private String notes;
    }
    
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ReleaseEscrowResponse {
        private UUID escrowId;
        private String escrowReference;
        private BigDecimal releasedAmount;
        private BigDecimal releaseFeeCharged;
        private BigDecimal remainingBalance;
        private String escrowStatus;
        private String releasedTo;
        private String transactionReference;
        private String correlationId;
        private String milestone;
        private String approvedBy;
        private LocalDateTime releasedAt;
    }
    
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ExtendEscrowRequest {
        private LocalDate newExpiryDate;
        private String reason;
    }
    
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ExtendEscrowResponse {
        private UUID escrowId;
        private String escrowReference;
        private LocalDate previousExpiryDate;
        private LocalDate newExpiryDate;
        private BigDecimal extensionFee;
        private String correlationId;
    }
}