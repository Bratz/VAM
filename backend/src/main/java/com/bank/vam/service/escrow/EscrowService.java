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
import com.bank.vam.config.MarketProfileProperties;
import com.bank.vam.service.treasury.FeePostingService;
import com.bank.vam.service.treasury.FxRateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
    private final FxRateService fxRateService;
    private final MarketProfileProperties marketProfile;
    
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

        // Setup fee is due on first funding. It is NOT withheld from the credit
        // here: postEscrowSetupFee below debits the escrow VA through
        // FeePostingService, which is the single place the fee leaves the
        // account. Withholding it as well charged the buyer twice.
        BigDecimal setupFeeDue = BigDecimal.ZERO;
        if (!Boolean.TRUE.equals(escrow.getSetupFeeCharged())
            && escrow.getSetupFee() != null
            && escrow.getSetupFee().compareTo(BigDecimal.ZERO) > 0) {

            setupFeeDue = escrow.getSetupFee();
            if (fundAmount.compareTo(setupFeeDue) < 0) {
                throw new BusinessException("Funding amount must be greater than setup fee: " + setupFeeDue);
            }
        }

        String correlationId = "ESC-FUND-" + System.currentTimeMillis();

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
            .feeAmount(setupFeeDue)
            .status(TransactionStatus.COMPLETED)
            .transactionDate(LocalDateTime.now())
            .valueDate(LocalDate.now())
            .channel("ESCROW")
            .build();

        txn = transactionRepository.save(txn);

        // Post setup fee to Settlement VA. Only mark it charged and take it off
        // the contract balance if it actually posted — a failed posting left the
        // money in the VA, so the contract must still show it.
        BigDecimal setupFeeCharged = BigDecimal.ZERO;
        if (setupFeeDue.compareTo(BigDecimal.ZERO) > 0
            && postEscrowSetupFee(escrowVa, setupFeeDue, txn, correlationId, escrow)) {
            setupFeeCharged = setupFeeDue;
            escrow.setSetupFeeCharged(true);
            escrow.recordFeeCharged(setupFeeDue);
            escrowRepository.save(escrow);
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

        // Debit the released amount only. The fee leaves the VA once, via
        // postEscrowReleaseFee below; debiting it here as well took it twice.
        escrowVa.debit(request.getAmount());
        vaRepository.save(escrowVa);

        // Update escrow contract
        escrow.recordRelease(request.getAmount());
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

        // Post release fee to Settlement VA; only charge it to the contract if it posted.
        BigDecimal releaseFeeCharged = BigDecimal.ZERO;
        if (releaseFee.compareTo(BigDecimal.ZERO) > 0
            && postEscrowReleaseFee(escrowVa, releaseFee, txn, correlationId, request.getMilestone(), escrow)) {
            releaseFeeCharged = releaseFee;
            escrow.recordFeeCharged(releaseFee);
            escrowRepository.save(escrow);
        }

        log.info("Released from escrow {}: amount={}, fee={}, remaining balance={}", 
            escrow.getEscrowReference(), request.getAmount(), releaseFee, escrowVa.getCurrentBalance());
        
        return ReleaseEscrowResponse.builder()
            .escrowId(escrowId)
            .escrowReference(escrow.getEscrowReference())
            .releasedAmount(request.getAmount())
            .releaseFeeCharged(releaseFeeCharged)
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
        
        // Extension fee comes out of the escrow balance, once, via FeePostingService
        // (which writes its own debit/credit transaction pair). The earlier inline
        // debit plus fee transaction on top of that charged it twice.
        if (escrowVa.getCurrentBalance().compareTo(ESCROW_EXTENSION_FEE) >= 0
            && postEscrowExtensionFee(escrowVa, null, correlationId, escrow)) {
            extensionFeeCharged = ESCROW_EXTENSION_FEE;
            escrow.recordFeeCharged(ESCROW_EXTENSION_FEE);
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

    /**
     * List contracts, optionally narrowed by corporate and/or status.
     * ponytail: filters in memory off findAll/findByCorporateId — escrow is a
     * low-volume ledger. Push status into the query if the table ever grows.
     */
    @Transactional(readOnly = true)
    public List<EscrowResponse> listEscrows(UUID corporateId, EscrowStatus status) {
        List<EscrowContract> rows = corporateId != null
            ? escrowRepository.findByCorporateId(corporateId)
            : escrowRepository.findAll();
        return rows.stream()
            .filter(e -> status == null || e.getStatus() == status)
            .sorted(java.util.Comparator.comparing(EscrowContract::getCreatedAt,
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
            .map(e -> toEscrowResponse(e, e.getEscrowVaId() != null
                ? vaRepository.findById(e.getEscrowVaId()).orElse(null) : null))
            .toList();
    }

    /**
     * Portfolio totals. Contracts can be held in different currencies, so each
     * currency is summed separately and then converted to the market reporting
     * currency — adding raw amounts across currencies gives a meaningless total.
     * Currencies with no available rate are reported rather than silently dropped.
     */
    @Transactional(readOnly = true)
    public EscrowStatsResponse getStats(UUID corporateId) {
        List<EscrowContract> rows = corporateId != null
            ? escrowRepository.findByCorporateId(corporateId)
            : escrowRepository.findAll();

        Map<String, BigDecimal> contractByCurrency = new TreeMap<>();
        Map<String, BigDecimal> balanceByCurrency = new TreeMap<>();
        Map<String, BigDecimal> releasedByCurrency = new TreeMap<>();
        long active = 0, pendingRelease = 0, disputed = 0;

        for (EscrowContract e : rows) {
            String ccy = e.getCurrencyCode();
            if (ccy != null) {
                contractByCurrency.merge(ccy, nz(e.getContractAmount()), BigDecimal::add);
                balanceByCurrency.merge(ccy, nz(e.getCurrentBalance()), BigDecimal::add);
                releasedByCurrency.merge(ccy, nz(e.getReleasedAmount()), BigDecimal::add);
            }
            switch (e.getStatus()) {
                case FUNDED, PARTIALLY_FUNDED -> active++;
                case PARTIALLY_RELEASED -> { active++; pendingRelease++; }
                case DISPUTED -> disputed++;
                default -> { }
            }
        }

        String reportingCurrency = marketProfile.getDefaultCurrency();
        List<String> excluded = new ArrayList<>();
        return EscrowStatsResponse.builder()
            .totalEscrows(rows.size())
            .activeEscrows(active)
            .pendingRelease(pendingRelease)
            .disputed(disputed)
            .totalValue(convertTotal(contractByCurrency, reportingCurrency, excluded))
            .escrowBalance(convertTotal(balanceByCurrency, reportingCurrency, excluded))
            .releasedTotal(convertTotal(releasedByCurrency, reportingCurrency, excluded))
            .reportingCurrency(reportingCurrency)
            .valueByCurrency(contractByCurrency)
            .excludedCurrencies(excluded.stream().distinct().toList())
            .build();
    }

    private BigDecimal convertTotal(Map<String, BigDecimal> byCurrency, String to, List<String> excluded) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : byCurrency.entrySet()) {
            try {
                total = total.add(fxRateService.convert(e.getValue(), e.getKey(), to));
            } catch (RuntimeException ex) {
                log.warn("No FX rate {} -> {} for escrow stats; excluding", e.getKey(), to);
                excluded.add(e.getKey());
            }
        }
        return total;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    // ========================================================================
    // DISPUTES
    // ========================================================================

    @Transactional
    public EscrowResponse disputeEscrow(UUID escrowId, DisputeEscrowRequest request) {
        EscrowContract escrow = escrowRepository.findById(escrowId)
            .orElseThrow(() -> new ResourceNotFoundException("Escrow not found: " + escrowId));

        if (escrow.getStatus() == EscrowStatus.RELEASED || escrow.getStatus() == EscrowStatus.CANCELLED) {
            throw new BusinessException("Cannot dispute escrow in status: " + escrow.getStatus());
        }

        escrow.dispute(request.getReason());
        escrow.setLastModifiedBy(request.getRaisedBy());
        escrow = escrowRepository.save(escrow);

        log.info("Escrow {} disputed by {}: {}", escrow.getEscrowReference(), request.getRaisedBy(), request.getReason());
        VirtualAccount va = escrow.getEscrowVaId() != null
            ? vaRepository.findById(escrow.getEscrowVaId()).orElse(null) : null;
        return toEscrowResponse(escrow, va);
    }

    // ========================================================================
    // FEE POSTING
    // ========================================================================
    
    private boolean postEscrowSetupFee(VirtualAccount escrowVa, BigDecimal fee,
                                       Transaction sourceTransaction, String correlationId,
                                       EscrowContract escrow) {
        return postFee(escrowVa, fee, "ESCROW_SETUP_FEE", sourceTransaction,
            "Escrow setup fee - " + escrow.getEscrowReference(), escrow);
    }

    private boolean postEscrowReleaseFee(VirtualAccount escrowVa, BigDecimal fee,
                                         Transaction sourceTransaction, String correlationId,
                                         String milestone, EscrowContract escrow) {
        String description = "Escrow release fee - " + escrow.getEscrowReference();
        if (milestone != null) {
            description += " - " + milestone;
        }
        return postFee(escrowVa, fee, "ESCROW_RELEASE_FEE", sourceTransaction, description, escrow);
    }

    private boolean postEscrowExtensionFee(VirtualAccount escrowVa, Transaction sourceTransaction,
                                           String correlationId, EscrowContract escrow) {
        return postFee(escrowVa, ESCROW_EXTENSION_FEE, "ESCROW_EXTENSION_FEE", sourceTransaction,
            "Escrow extension fee - " + escrow.getEscrowReference(), escrow);
    }

    /**
     * Hands the fee to FeePostingService, which debits the escrow VA and credits
     * the settlement VA. Returns true only when it actually posted, so callers
     * never charge the contract for a fee that stayed in the account.
     */
    private boolean postFee(VirtualAccount escrowVa, BigDecimal fee, String feeType,
                            Transaction sourceTransaction, String description, EscrowContract escrow) {
        try {
            FeePostingService.FeePostingResult result = feePostingService.postFee(
                escrowVa.getId(),
                fee,
                feeType,
                sourceTransaction != null ? sourceTransaction.getId() : escrow.getId(),
                description
            );
            boolean posted = "POSTED".equals(result.getStatus());
            if (posted) {
                log.info("Posted {} {} for {}", feeType, fee, escrow.getEscrowReference());
            } else {
                log.warn("{} {} not posted for {}: status={}", feeType, fee,
                    escrow.getEscrowReference(), result.getStatus());
            }
            return posted;
        } catch (Exception e) {
            log.error("Failed to post {} for {}: {}", feeType, escrow.getEscrowReference(), e.getMessage());
            return false;
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

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class DisputeEscrowRequest {
        private String reason;
        private String raisedBy;
        private List<String> evidence;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class EscrowStatsResponse {
        private long totalEscrows;
        private long activeEscrows;
        private long pendingRelease;
        private long disputed;
        /** Converted to reportingCurrency. */
        private BigDecimal totalValue;
        private BigDecimal escrowBalance;
        private BigDecimal releasedTotal;
        private String reportingCurrency;
        /** Contract value per holding currency, before conversion. */
        private Map<String, BigDecimal> valueByCurrency;
        /** Currencies left out of the totals because no rate was available. */
        private List<String> excludedCurrencies;
    }
}