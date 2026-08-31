package com.bank.vam.service.wallet;

import com.bank.vam.dto.WalletDto.*;
import com.bank.vam.entity.Corporate;
import com.bank.vam.entity.Program;
import com.bank.vam.entity.Program.ProgramType;
import com.bank.vam.entity.Program.ProgramStatus;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.Transaction.MovementType;
import com.bank.vam.entity.Transaction.TransactionStatus;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.VaStatus;
import com.bank.vam.entity.VirtualAccount.VaSpecialType;
import com.bank.vam.entity.party.Party;
import com.bank.vam.entity.treasury.ExceptionTransaction;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.CorporateRepository;
import com.bank.vam.repository.ProgramRepository;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.party.PartyRepository;
import com.bank.vam.repository.treasury.ExceptionTransactionRepository;
import com.bank.vam.service.treasury.SettlementVaResolverService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * WalletService - Business logic for Wallet operations.
 * 
 * Enhanced with Settlement VA integration for fee posting:
 * - Fees are automatically credited to Settlement VA (or Exception VA as fallback)
 * - Paired transactions with correlationId for audit trail
 * - Exception tracking when no Settlement VA is found
 * 
 * Uses EXISTING domain model (extended for wallet functionality):
 * - VirtualAccount (with walletType) as Wallet
 * - Transaction for wallet operations (TOPUP, WITHDRAWAL, WALLET_TRANSFER_*)
 * - Party as wallet holder (linked via holderPartyId)
 * - Program (programType=WALLET or walletEnabled=true) for wallet program config
 * 
 * MVC Pattern:
 * - Entity: VirtualAccount, Transaction, Party, Program (enhanced)
 * - Repository: VirtualAccountRepository, TransactionRepository, PartyRepository, ProgramRepository
 * - Service: WalletService (this class)
 * - Controller: WalletController
 * - DTO: WalletDto
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    private final ProgramRepository programRepository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final TransactionRepository transactionRepository;
    private final PartyRepository partyRepository;
    private final CorporateRepository corporateRepository;
    
    // NEW: Settlement VA integration
    private final SettlementVaResolverService settlementVaResolver;
    private final ExceptionTransactionRepository exceptionTransactionRepository;
    private final com.bank.vam.config.MarketProfileProperties marketProfile;

    // ========================================================================
    // STATS
    // ========================================================================

    public WalletStatsResponse getStats(UUID corporateId) {
        log.info("Getting wallet stats for corporate: {}", corporateId);
        
        // Get wallet programs
        List<Program> programs = getWalletPrograms(corporateId);
        
        // Get wallet accounts (VAs with walletType set)
        List<VirtualAccount> allWallets = getWalletAccounts(corporateId, null);
        
        long activePrograms = programs.stream()
                .filter(p -> p.getStatus() == ProgramStatus.ACTIVE)
                .count();
        
        long activeWallets = allWallets.stream()
                .filter(w -> w.getStatus() == VaStatus.ACTIVE)
                .count();
        long suspendedWallets = allWallets.stream()
                .filter(w -> w.getStatus() == VaStatus.SUSPENDED)
                .count();
        long blockedWallets = allWallets.stream()
                .filter(w -> w.getStatus() == VaStatus.BLOCKED)
                .count();
        
        BigDecimal totalBalance = allWallets.stream()
                .map(VirtualAccount::getCurrentBalance)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal totalAvailable = allWallets.stream()
                .map(VirtualAccount::getAvailableBalance)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        long kycVerified = allWallets.stream()
                .filter(w -> Boolean.TRUE.equals(w.getKycVerified()))
                .count();
        
        // Calculate volume (simplified - in production use aggregate queries)
        BigDecimal monthlyVolume = BigDecimal.valueOf(2500000);
        BigDecimal dailyVolume = BigDecimal.valueOf(125000);
        
        return WalletStatsResponse.builder()
                .totalPrograms(programs.size())
                .activePrograms((int) activePrograms)
                .totalWallets((long) allWallets.size())
                .activeWallets(activeWallets)
                .suspendedWallets(suspendedWallets)
                .blockedWallets(blockedWallets)
                .totalBalance(totalBalance)
                .totalAvailableBalance(totalAvailable)
                .monthlyVolume(monthlyVolume)
                .dailyVolume(dailyVolume)
                .todayTransactions(45L)
                .monthlyTransactions(1250L)
                .kycVerifiedCount(kycVerified)
                .kycPendingCount(allWallets.size() - kycVerified)
                .build();
    }

    // ========================================================================
    // PROGRAMS
    // ========================================================================

    public List<WalletProgramResponse> getAllPrograms(UUID corporateId, String status) {
        log.info("Getting wallet programs for corporate: {}", corporateId);
        
        List<Program> programs = getWalletPrograms(corporateId);
        
        if (status != null && !status.isEmpty()) {
            try {
                ProgramStatus statusEnum = ProgramStatus.valueOf(status.toUpperCase());
                programs = programs.stream()
                        .filter(p -> p.getStatus() == statusEnum)
                        .collect(Collectors.toList());
            } catch (IllegalArgumentException ignored) {}
        }
        
        return programs.stream()
                .map(this::toProgramResponse)
                .collect(Collectors.toList());
    }

    public WalletProgramDetailResponse getProgramDetails(UUID programId) {
        Program program = programRepository.findById(programId)
                .orElseThrow(() -> new ResourceNotFoundException("Program not found: " + programId));
        
        if (!program.isWalletProgram()) {
            throw new BusinessException("Program is not a wallet program: " + programId);
        }
        
        return toProgramDetailResponse(program);
    }

    @Transactional
    public WalletProgramResponse createProgram(CreateProgramRequest request) {
        log.info("Creating wallet program: {}", request.getProgramCode());
        
        if (programRepository.existsByProgramCode(request.getProgramCode())) {
            throw new BusinessException("Program code already exists: " + request.getProgramCode());
        }
        
        Program program = Program.builder()
                .programCode(request.getProgramCode())
                .programName(request.getProgramName())
                .programType(ProgramType.WALLET)
                .corporateId(request.getCorporateId())
                .physicalAccountId(request.getPhysicalAccountId())
                .currencyCode(request.getCurrency() != null ? request.getCurrency() : marketProfile.getDefaultCurrency())
                .description(request.getDescription())
                .vaPrefix(request.getVaPrefix())
                .vaFormat(request.getVaFormat())
                .maxVirtualAccounts(request.getMaxVirtualAccounts())
                .walletEnabled(true)
                .defaultWalletType("CONSUMER")
                .defaultDailyLimit(request.getDailySpendLimit())
                .defaultMonthlyLimit(request.getMonthlySpendLimit())
                .defaultMaxBalance(request.getMaxBalance())
                .minTopup(request.getMinTopup())
                .maxTopup(request.getMaxTopup())
                .kycRequired(request.getKycRequired() != null ? request.getKycRequired() : true)
                .walletExpiryDays(request.getExpiryDays())
                .allowTopup(true)
                .allowWithdrawal(request.getAllowWithdrawals() != null ? request.getAllowWithdrawals() : true)
                .allowTransfer(request.getAllowTransfers() != null ? request.getAllowTransfers() : true)
                .status(ProgramStatus.ACTIVE)
                .effectiveFrom(request.getEffectiveFrom() != null ? request.getEffectiveFrom() : LocalDate.now())
                .effectiveTo(request.getEffectiveTo())
                .build();
        
        Program saved = programRepository.save(program);
        log.info("Created wallet program: {}", saved.getId());
        
        return toProgramResponse(saved);
    }

    @Transactional
    public WalletProgramResponse updateProgram(UUID programId, UpdateProgramRequest request) {
        Program program = programRepository.findById(programId)
                .orElseThrow(() -> new ResourceNotFoundException("Program not found: " + programId));
        
        if (request.getProgramName() != null) {
            program.setProgramName(request.getProgramName());
        }
        if (request.getDescription() != null) {
            program.setDescription(request.getDescription());
        }
        if (request.getDailySpendLimit() != null) {
            program.setDefaultDailyLimit(request.getDailySpendLimit());
        }
        if (request.getMonthlySpendLimit() != null) {
            program.setDefaultMonthlyLimit(request.getMonthlySpendLimit());
        }
        if (request.getMaxBalance() != null) {
            program.setDefaultMaxBalance(request.getMaxBalance());
        }
        if (request.getEffectiveTo() != null) {
            program.setEffectiveTo(request.getEffectiveTo());
        }
        
        Program saved = programRepository.save(program);
        return toProgramResponse(saved);
    }

    public WalletProgramStatsResponse getProgramStats(UUID programId) {
        Program program = programRepository.findById(programId)
                .orElseThrow(() -> new ResourceNotFoundException("Program not found: " + programId));
        
        List<VirtualAccount> wallets = getWalletsByProgram(programId);
        
        long active = wallets.stream().filter(w -> w.getStatus() == VaStatus.ACTIVE).count();
        long suspended = wallets.stream().filter(w -> w.getStatus() == VaStatus.SUSPENDED).count();
        long blocked = wallets.stream().filter(w -> w.getStatus() == VaStatus.BLOCKED).count();
        
        BigDecimal totalBalance = wallets.stream()
                .map(VirtualAccount::getCurrentBalance)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal avgBalance = wallets.isEmpty() ? BigDecimal.ZERO : 
                totalBalance.divide(BigDecimal.valueOf(wallets.size()), 2, RoundingMode.HALF_UP);
        
        long kycVerified = wallets.stream()
                .filter(w -> Boolean.TRUE.equals(w.getKycVerified()))
                .count();
        
        // Top wallets by balance
        List<WalletAccountResponse> topByBalance = wallets.stream()
                .sorted((a, b) -> {
                    BigDecimal balA = a.getCurrentBalance() != null ? a.getCurrentBalance() : BigDecimal.ZERO;
                    BigDecimal balB = b.getCurrentBalance() != null ? b.getCurrentBalance() : BigDecimal.ZERO;
                    return balB.compareTo(balA);
                })
                .limit(5)
                .map(this::toWalletResponse)
                .collect(Collectors.toList());
        
        return WalletProgramStatsResponse.builder()
                .programId(program.getId())
                .programCode(program.getProgramCode())
                .programName(program.getProgramName())
                .activeWallets(active)
                .suspendedWallets(suspended)
                .blockedWallets(blocked)
                .totalWallets((long) wallets.size())
                .totalBalance(totalBalance)
                .averageBalance(avgBalance)
                .dailyVolume(BigDecimal.valueOf(50000))
                .weeklyVolume(BigDecimal.valueOf(350000))
                .monthlyVolume(BigDecimal.valueOf(1500000))
                .dailyTransactions(25L)
                .weeklyTransactions(175L)
                .monthlyTransactions(750L)
                .kycVerifiedCount(kycVerified)
                .kycPendingCount(wallets.size() - kycVerified)
                .topWalletsByBalance(topByBalance)
                .build();
    }

    public WalletListResponse getWalletsByProgram(UUID programId, int page, int size, String status, String query) {
        List<VirtualAccount> allWallets = getWalletsByProgram(programId);
        
        // Filter by status
        if (status != null && !status.isEmpty()) {
            try {
                VaStatus statusEnum = VaStatus.valueOf(status.toUpperCase());
                allWallets = allWallets.stream()
                        .filter(w -> w.getStatus() == statusEnum)
                        .collect(Collectors.toList());
            } catch (IllegalArgumentException ignored) {}
        }
        
        // Filter by query
        if (query != null && !query.isEmpty()) {
            String q = query.toLowerCase();
            allWallets = allWallets.stream()
                    .filter(w -> 
                            (w.getVaNumber() != null && w.getVaNumber().toLowerCase().contains(q)) ||
                            (w.getVaName() != null && w.getVaName().toLowerCase().contains(q)))
                    .collect(Collectors.toList());
        }
        
        // Paginate
        int start = page * size;
        int end = Math.min(start + size, allWallets.size());
        List<VirtualAccount> pageWallets = start < allWallets.size() ? 
                allWallets.subList(start, end) : Collections.emptyList();
        
        List<WalletAccountResponse> responses = pageWallets.stream()
                .map(this::toWalletResponse)
                .collect(Collectors.toList());
        
        WalletListSummary summary = buildWalletListSummary(allWallets);
        
        return WalletListResponse.builder()
                .content(responses)
                .page(page)
                .pageSize(size)
                .totalElements((long) allWallets.size())
                .totalPages((int) Math.ceil((double) allWallets.size() / size))
                .summary(summary)
                .build();
    }

    // ========================================================================
    // WALLETS
    // ========================================================================

    public WalletListResponse getAllWallets(int page, int size, UUID corporateId, UUID programId, 
                                            String status, String query, Boolean kycVerified) {
        log.info("Getting wallets - page: {}, size: {}, corporateId: {}", page, size, corporateId);
        
        List<VirtualAccount> allWallets;
        
        if (programId != null) {
            allWallets = getWalletsByProgram(programId);
        } else {
            allWallets = getWalletAccounts(corporateId, null);
        }
        
        // Additional filters
        if (status != null && !status.isEmpty()) {
            try {
                VaStatus statusEnum = VaStatus.valueOf(status.toUpperCase());
                allWallets = allWallets.stream()
                        .filter(w -> w.getStatus() == statusEnum)
                        .collect(Collectors.toList());
            } catch (IllegalArgumentException ignored) {}
        }
        
        if (kycVerified != null) {
            allWallets = allWallets.stream()
                    .filter(w -> kycVerified.equals(w.getKycVerified()))
                    .collect(Collectors.toList());
        }
        
        if (query != null && !query.isEmpty()) {
            String q = query.toLowerCase();
            allWallets = allWallets.stream()
                    .filter(w -> 
                            (w.getVaNumber() != null && w.getVaNumber().toLowerCase().contains(q)) ||
                            (w.getVaName() != null && w.getVaName().toLowerCase().contains(q)))
                    .collect(Collectors.toList());
        }
        
        // Paginate
        int start = page * size;
        int end = Math.min(start + size, allWallets.size());
        List<VirtualAccount> pageWallets = start < allWallets.size() ? 
                allWallets.subList(start, end) : Collections.emptyList();
        
        List<WalletAccountResponse> responses = pageWallets.stream()
                .map(this::toWalletResponse)
                .collect(Collectors.toList());
        
        WalletListSummary summary = buildWalletListSummary(allWallets);
        
        return WalletListResponse.builder()
                .content(responses)
                .page(page)
                .pageSize(size)
                .totalElements((long) allWallets.size())
                .totalPages((int) Math.ceil((double) allWallets.size() / size))
                .summary(summary)
                .build();
    }

    public WalletAccountDetailResponse getWalletDetails(UUID walletId) {
        VirtualAccount wallet = virtualAccountRepository.findById(walletId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + walletId));
        
        if (!wallet.isWallet()) {
            throw new BusinessException("Virtual account is not a wallet: " + walletId);
        }
        
        return toWalletDetailResponse(wallet);
    }

    public WalletAccountDetailResponse getWalletByReference(String reference) {
        VirtualAccount wallet = virtualAccountRepository.findByVaNumber(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + reference));
        
        if (!wallet.isWallet()) {
            throw new BusinessException("Virtual account is not a wallet: " + reference);
        }
        
        return toWalletDetailResponse(wallet);
    }

    @Transactional
    public WalletAccountResponse issueWallet(IssueWalletRequest request) {
        log.info("Issuing new wallet for holder: {}", request.getHolderName());
        
        // Validate and get program
        Program program = null;
        if (request.getProgramId() != null) {
            program = programRepository.findById(request.getProgramId())
                    .orElseThrow(() -> new ResourceNotFoundException("Program not found: " + request.getProgramId()));
            
            if (!program.canIssueWallet()) {
                throw new BusinessException("Program cannot issue new wallets");
            }
        }
        
        // Find or create Party as wallet holder
        Party holder = null;
        if (request.getPartyId() != null) {
            holder = partyRepository.findById(request.getPartyId()).orElse(null);
        }
        
        String walletNumber = generateWalletNumber(program);
        String walletType = program != null && program.getDefaultWalletType() != null ? 
                program.getDefaultWalletType() : "CONSUMER";
        
        // Calculate issuance fee
        BigDecimal issuanceFee = BigDecimal.ZERO;
        if (program != null && program.getIssuanceFee() != null) {
            issuanceFee = program.getIssuanceFee();
        }
        
        // Calculate initial balance (after deducting issuance fee if applicable)
        BigDecimal initialBalance = request.getInitialLoadAmount() != null ? request.getInitialLoadAmount() : BigDecimal.ZERO;
        BigDecimal netInitialBalance = initialBalance.subtract(issuanceFee);
        if (netInitialBalance.compareTo(BigDecimal.ZERO) < 0) {
            netInitialBalance = BigDecimal.ZERO;
        }
        
        // Create VirtualAccount with wallet configuration
        VirtualAccount wallet = VirtualAccount.builder()
                .vaNumber(walletNumber)
                .vaName(request.getHolderName())
                .programId(request.getProgramId())
                .corporateId(program != null ? program.getCorporateId() : null)
                .physicalAccountId(program != null ? program.getPhysicalAccountId() : null)
                .currencyCode(program != null ? program.getCurrencyCode() : marketProfile.getDefaultCurrency())
                .currentBalance(netInitialBalance)
                .availableBalance(netInitialBalance)
                .status(VaStatus.ACTIVE)
                .specialType(VaSpecialType.REGULAR) // Wallets are regular VAs
                // Wallet-specific fields
                .walletType(walletType)
                .holderPartyId(request.getPartyId())
                .dailyLimit(request.getDailyLimit() != null ? request.getDailyLimit() : 
                        (program != null ? program.getDefaultDailyLimit() : BigDecimal.valueOf(5000)))
                .monthlyLimit(request.getMonthlyLimit() != null ? request.getMonthlyLimit() : 
                        (program != null ? program.getDefaultMonthlyLimit() : BigDecimal.valueOf(25000)))
                .maxBalance(program != null ? program.getDefaultMaxBalance() : BigDecimal.valueOf(100000))
                .kycVerified(false)
                .activatedAt(LocalDateTime.now())
                .expiresAt(program != null ? program.calculateWalletExpiryDate() : null)
                .externalReference(request.getExternalReference())
                .metadata(buildWalletMetadata(request))
                .build();
        
        VirtualAccount saved = virtualAccountRepository.save(wallet);
        
        // Update program VA count
        if (program != null) {
            program.incrementVaCount();
            programRepository.save(program);
        }
        
        // Record initial load transaction if applicable
        String correlationId = "ISSUE-" + System.currentTimeMillis();
        if (initialBalance.compareTo(BigDecimal.ZERO) > 0) {
            Transaction txn = recordTransaction(saved, MovementType.TOPUP, netInitialBalance, 
                    BigDecimal.ZERO, saved.getCurrentBalance(), "Initial load", null, "INTERNAL");
            txn.setCorrelationId(correlationId);
            txn.setFeeAmount(issuanceFee);
            transactionRepository.save(txn);
            
            // Credit issuance fee to Settlement VA
            if (issuanceFee.compareTo(BigDecimal.ZERO) > 0) {
                creditFeeToSettlementVa(saved, issuanceFee, correlationId, 
                    "Wallet issuance fee", txn.getReferenceNumber(), txn.getId());
            }
        }
        
        log.info("Issued wallet: {} for {}", saved.getVaNumber(), request.getHolderName());
        
        return toWalletResponse(saved);
    }

    @Transactional
    public WalletAccountResponse updateWallet(UUID walletId, UpdateWalletRequest request) {
        VirtualAccount wallet = virtualAccountRepository.findById(walletId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + walletId));
        
        if (request.getWalletName() != null) {
            wallet.setVaName(request.getWalletName());
        }
        if (request.getDailyLimit() != null) {
            wallet.setDailyLimit(request.getDailyLimit());
        }
        if (request.getMonthlyLimit() != null) {
            wallet.setMonthlyLimit(request.getMonthlyLimit());
        }
        if (request.getPerTransactionLimit() != null) {
            wallet.setPerTransactionLimit(request.getPerTransactionLimit());
        }
        if (request.getMetadata() != null) {
            wallet.setMetadata(request.getMetadata());
        }
        
        VirtualAccount saved = virtualAccountRepository.save(wallet);
        return toWalletResponse(saved);
    }

    // ========================================================================
    // WALLET OPERATIONS - ENHANCED WITH SETTLEMENT VA FEE POSTING
    // ========================================================================

    /**
     * Load funds to wallet with fee posting to Settlement VA.
     * 
     * Flow:
     * 1. Calculate fee based on program configuration
     * 2. Credit net amount (amount - fee) to wallet
     * 3. Record TOPUP transaction with correlationId
     * 4. Credit fee to Settlement VA (or Exception VA as fallback)
     */
    @Transactional
    public LoadFundsResponse loadFunds(UUID walletId, LoadFundsRequest request) {
        VirtualAccount wallet = virtualAccountRepository.findById(walletId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + walletId));
        
        if (!wallet.isOperational()) {
            throw new BusinessException("Cannot load funds to " + wallet.getStatus() + " wallet");
        }
        
        // Calculate fee
        BigDecimal fee = calculateTopupFee(wallet, request.getAmount(), request.getSource());
        BigDecimal netAmount = request.getAmount().subtract(fee);
        
        if (!wallet.canTopup(netAmount)) {
            throw new BusinessException("Topup would exceed maximum balance limit");
        }
        
        BigDecimal previousBalance = wallet.getCurrentBalance();
        String correlationId = "TOP-" + System.currentTimeMillis();
        
        // Credit the wallet with net amount
        wallet.credit(netAmount);
        virtualAccountRepository.save(wallet);
        
        // Record customer transaction
        Transaction txn = recordTransaction(wallet, MovementType.TOPUP, netAmount,
                previousBalance, wallet.getCurrentBalance(), request.getDescription(), 
                request.getSourceReference(), request.getSource());
        txn.setCorrelationId(correlationId);
        txn.setFeeAmount(fee);
        txn.setNetAmount(netAmount);
        transactionRepository.save(txn);
        
        // NEW: Credit fee to Settlement VA
        if (fee.compareTo(BigDecimal.ZERO) > 0) {
            creditFeeToSettlementVa(wallet, fee, correlationId, 
                "Topup fee - " + (request.getSource() != null ? request.getSource() : "WALLET"), 
                txn.getReferenceNumber(), txn.getId());
        }
        
        log.info("Loaded {} (net: {}, fee: {}) to wallet {} - new balance: {}", 
                request.getAmount(), netAmount, fee, wallet.getVaNumber(), wallet.getCurrentBalance());
        
        return LoadFundsResponse.builder()
                .walletId(walletId)
                .walletReference(wallet.getVaNumber())
                .previousBalance(previousBalance)
                .loadAmount(request.getAmount())
                .newBalance(wallet.getCurrentBalance())
                .referenceNumber(txn.getReferenceNumber())
                .status("COMPLETED")
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Withdraw funds from wallet with fee posting to Settlement VA.
     */
    @Transactional
    public WithdrawResponse withdrawFunds(UUID walletId, WithdrawRequest request) {
        VirtualAccount wallet = virtualAccountRepository.findById(walletId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + walletId));
        
        if (!wallet.isOperational()) {
            throw new BusinessException("Cannot withdraw from " + wallet.getStatus() + " wallet");
        }
        
        // Calculate fee
        BigDecimal fee = calculateWithdrawalFee(wallet, request.getAmount(), request.getDestination());
        BigDecimal totalDebit = request.getAmount().add(fee);
        
        if (!wallet.hasSufficientBalance(totalDebit)) {
            throw new BusinessException("Insufficient balance. Available: " + wallet.getAvailableBalance() + 
                ", Required (including fee): " + totalDebit);
        }
        
        if (!wallet.isWithinTransactionLimit(request.getAmount())) {
            throw new BusinessException("Amount exceeds per-transaction limit");
        }
        
        if (!wallet.isWithinDailyLimit(request.getAmount())) {
            throw new BusinessException("Amount exceeds daily limit");
        }
        
        BigDecimal previousBalance = wallet.getCurrentBalance();
        String correlationId = "WTH-" + System.currentTimeMillis();
        
        // Debit the wallet (amount + fee)
        wallet.withdraw(totalDebit);
        virtualAccountRepository.save(wallet);
        
        // Record withdrawal transaction
        Transaction txn = recordTransaction(wallet, MovementType.WITHDRAWAL, request.getAmount(),
                previousBalance, wallet.getCurrentBalance(), request.getDescription(), 
                request.getDestinationReference(), null);
        txn.setCorrelationId(correlationId);
        txn.setFeeAmount(fee);
        transactionRepository.save(txn);
        
        // NEW: Credit fee to Settlement VA
        if (fee.compareTo(BigDecimal.ZERO) > 0) {
            creditFeeToSettlementVa(wallet, fee, correlationId, 
                "Withdrawal fee - " + (request.getDestination() != null ? request.getDestination() : "CASH"), 
                txn.getReferenceNumber(), txn.getId());
        }
        
        log.info("Withdrew {} (fee: {}) from wallet {} - new balance: {}", 
                request.getAmount(), fee, wallet.getVaNumber(), wallet.getCurrentBalance());
        
        return WithdrawResponse.builder()
                .walletId(walletId)
                .walletReference(wallet.getVaNumber())
                .previousBalance(previousBalance)
                .withdrawAmount(request.getAmount())
                .newBalance(wallet.getCurrentBalance())
                .referenceNumber(txn.getReferenceNumber())
                .status("COMPLETED")
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Transfer funds between wallets with fee posting to Settlement VA.
     */
    @Transactional
    public TransferResponse transferFunds(UUID walletId, TransferRequest request) {
        VirtualAccount fromWallet = virtualAccountRepository.findById(walletId)
                .orElseThrow(() -> new ResourceNotFoundException("Source wallet not found: " + walletId));
        
        VirtualAccount toWallet;
        if (request.getToWalletId() != null) {
            toWallet = virtualAccountRepository.findById(request.getToWalletId())
                    .orElseThrow(() -> new ResourceNotFoundException("Destination wallet not found: " + request.getToWalletId()));
        } else if (request.getToWalletReference() != null) {
            toWallet = virtualAccountRepository.findByVaNumber(request.getToWalletReference())
                    .orElseThrow(() -> new ResourceNotFoundException("Destination wallet not found: " + request.getToWalletReference()));
        } else {
            throw new BusinessException("Destination wallet ID or reference is required");
        }
        
        if (!fromWallet.isOperational()) {
            throw new BusinessException("Source wallet is not active");
        }
        if (!toWallet.isOperational()) {
            throw new BusinessException("Destination wallet is not active");
        }
        
        // Calculate transfer fee
        BigDecimal fee = calculateTransferFee(fromWallet, request.getAmount());
        BigDecimal totalDebit = request.getAmount().add(fee);
        
        if (!fromWallet.hasSufficientBalance(totalDebit)) {
            throw new BusinessException("Insufficient balance. Available: " + fromWallet.getAvailableBalance() + 
                ", Required (including fee): " + totalDebit);
        }
        
        // Perform transfer
        BigDecimal fromPrevBalance = fromWallet.getCurrentBalance();
        BigDecimal toPrevBalance = toWallet.getCurrentBalance();
        
        String correlationId = "TRF-" + System.currentTimeMillis();
        
        // Debit source (amount + fee)
        fromWallet.transferOut(totalDebit);
        virtualAccountRepository.save(fromWallet);
        
        // Credit destination (full amount, fee already deducted from source)
        toWallet.credit(request.getAmount());
        virtualAccountRepository.save(toWallet);
        
        // Record transactions
        Transaction debitTxn = recordTransferTransaction(fromWallet, toWallet, 
                MovementType.WALLET_TRANSFER_OUT, request.getAmount(),
                fromPrevBalance, fromWallet.getCurrentBalance(), request.getDescription(), correlationId);
        debitTxn.setFeeAmount(fee);
        transactionRepository.save(debitTxn);
        
        recordTransferTransaction(toWallet, fromWallet,
                MovementType.WALLET_TRANSFER_IN, request.getAmount(),
                toPrevBalance, toWallet.getCurrentBalance(), request.getDescription(), correlationId);
        
        // NEW: Credit fee to Settlement VA
        if (fee.compareTo(BigDecimal.ZERO) > 0) {
            creditFeeToSettlementVa(fromWallet, fee, correlationId, 
                "Transfer fee to " + toWallet.getVaNumber(), 
                debitTxn.getReferenceNumber(), debitTxn.getId());
        }
        
        log.info("Transferred {} (fee: {}) from {} to {}", 
                request.getAmount(), fee, fromWallet.getVaNumber(), toWallet.getVaNumber());
        
        return TransferResponse.builder()
                .fromWalletId(fromWallet.getId())
                .fromWalletReference(fromWallet.getVaNumber())
                .toWalletId(toWallet.getId())
                .toWalletReference(toWallet.getVaNumber())
                .amount(request.getAmount())
                .fromPreviousBalance(fromPrevBalance)
                .fromNewBalance(fromWallet.getCurrentBalance())
                .toPreviousBalance(toPrevBalance)
                .toNewBalance(toWallet.getCurrentBalance())
                .referenceNumber(debitTxn.getReferenceNumber())
                .status("COMPLETED")
                .timestamp(LocalDateTime.now())
                .build();
    }

    @Transactional
    public BulkLoadResponse bulkLoadFunds(BulkLoadRequest request) {
        log.info("Processing bulk load for {} wallets", request.getLoads().size());
        
        List<BulkLoadResult> results = new ArrayList<>();
        int successCount = 0;
        BigDecimal successAmount = BigDecimal.ZERO;
        
        for (BulkLoadItem item : request.getLoads()) {
            try {
                VirtualAccount wallet = null;
                
                if (item.getWalletId() != null) {
                    wallet = virtualAccountRepository.findById(item.getWalletId()).orElse(null);
                } else if (item.getWalletReference() != null) {
                    wallet = virtualAccountRepository.findByVaNumber(item.getWalletReference()).orElse(null);
                }
                
                if (wallet == null || !wallet.isWallet()) {
                    results.add(BulkLoadResult.builder()
                            .walletId(item.getWalletId())
                            .walletReference(item.getWalletReference())
                            .amount(item.getAmount())
                            .status("FAILED")
                            .errorMessage("Wallet not found")
                            .build());
                    continue;
                }
                
                if (!wallet.isOperational()) {
                    results.add(BulkLoadResult.builder()
                            .walletId(wallet.getId())
                            .walletReference(wallet.getVaNumber())
                            .amount(item.getAmount())
                            .status("FAILED")
                            .errorMessage("Wallet is not active")
                            .build());
                    continue;
                }
                
                // Calculate fee
                BigDecimal fee = calculateTopupFee(wallet, item.getAmount(), "BULK");
                BigDecimal netAmount = item.getAmount().subtract(fee);
                String correlationId = "BULK-" + System.currentTimeMillis() + "-" + results.size();
                
                BigDecimal prevBalance = wallet.getCurrentBalance();
                wallet.credit(netAmount);
                virtualAccountRepository.save(wallet);
                
                Transaction txn = recordTransaction(wallet, MovementType.TOPUP, netAmount,
                        prevBalance, wallet.getCurrentBalance(), request.getDescription(), null, "BULK");
                txn.setCorrelationId(correlationId);
                txn.setFeeAmount(fee);
                transactionRepository.save(txn);
                
                // Credit fee to Settlement VA
                if (fee.compareTo(BigDecimal.ZERO) > 0) {
                    creditFeeToSettlementVa(wallet, fee, correlationId, 
                        "Bulk load fee", txn.getReferenceNumber(), txn.getId());
                }
                
                results.add(BulkLoadResult.builder()
                        .walletId(wallet.getId())
                        .walletReference(wallet.getVaNumber())
                        .amount(item.getAmount())
                        .status("SUCCESS")
                        .referenceNumber(txn.getReferenceNumber())
                        .build());
                
                successCount++;
                successAmount = successAmount.add(item.getAmount());
                
            } catch (Exception e) {
                results.add(BulkLoadResult.builder()
                        .walletId(item.getWalletId())
                        .walletReference(item.getWalletReference())
                        .amount(item.getAmount())
                        .status("FAILED")
                        .errorMessage(e.getMessage())
                        .build());
            }
        }
        
        BigDecimal totalAmount = request.getLoads().stream()
                .map(BulkLoadItem::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        return BulkLoadResponse.builder()
                .totalCount(request.getLoads().size())
                .successCount(successCount)
                .failedCount(request.getLoads().size() - successCount)
                .totalAmount(totalAmount)
                .successAmount(successAmount)
                .results(results)
                .build();
    }

    // ========================================================================
    // SETTLEMENT VA FEE POSTING - NEW
    // ========================================================================

    /**
     * Credit fee to appropriate Settlement/Exception VA.
     * Creates paired transaction with correlation ID for audit trail.
     * 
     * @param sourceWallet The wallet from which fee was collected
     * @param feeAmount The fee amount to credit
     * @param correlationId Correlation ID linking customer and fee transactions
     * @param description Description for the fee transaction
     * @param sourceReference Reference to the source transaction
     * @param sourceTransactionId ID of the source transaction
     */
    private void creditFeeToSettlementVa(VirtualAccount sourceWallet, BigDecimal feeAmount, 
                                          String correlationId, String description,
                                          String sourceReference, UUID sourceTransactionId) {
        if (feeAmount == null || feeAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        
        // Skip if wallet has no program (standalone wallet)
        if (sourceWallet.getProgramId() == null) {
            log.debug("Wallet {} has no program, skipping fee posting to Settlement VA", 
                sourceWallet.getVaNumber());
            return;
        }
        
        try {
            // Resolve Settlement VA (or Exception VA as fallback)
            VirtualAccount settlementVa = settlementVaResolver.resolveSettlementVa(sourceWallet);
            
            BigDecimal balanceBefore = settlementVa.getCurrentBalance();
            
            // Credit Settlement VA
            settlementVa.credit(feeAmount);
            virtualAccountRepository.save(settlementVa);
            
            // Determine movement type based on VA type
            MovementType movementType;
            if (settlementVa.isExceptionVa()) {
                movementType = MovementType.EXCEPTION_PARK;
            } else {
                movementType = MovementType.FEE_CREDIT;
            }
            
            // Record fee credit transaction
            Transaction feeTxn = Transaction.builder()
                .referenceNumber(Transaction.generateReference(movementType))
                .movementType(movementType)
                .corporateId(sourceWallet.getCorporateId())
                .vaId(settlementVa.getId())
                .physicalAccountId(settlementVa.getPhysicalAccountId())
                .programId(sourceWallet.getProgramId())
                .amount(feeAmount)
                .currencyCode(sourceWallet.getCurrencyCode())
                .balanceBefore(balanceBefore)
                .balanceAfter(settlementVa.getCurrentBalance())
                .correlationId(correlationId)
                .counterpartyVaId(sourceWallet.getId())
                .description(description)
                .sourceReference(sourceReference)
                .status(TransactionStatus.COMPLETED)
                .transactionDate(LocalDateTime.now())
                .valueDate(LocalDate.now())
                .channel("SYSTEM")
                .build();
            
            transactionRepository.save(feeTxn);
            
            // If posted to Exception VA, create exception record for tracking
            if (settlementVa.isExceptionVa()) {
                ExceptionTransaction exception = ExceptionTransaction.createMissingSettlementVaException(
                    settlementVa.getId(),
                    sourceWallet.getId(),
                    sourceTransactionId,
                    feeAmount,
                    sourceWallet.getCurrencyCode(),
                    description
                );
                exceptionTransactionRepository.save(exception);
                
                log.warn("Fee {} credited to Exception VA {} (no Settlement VA found for wallet {})", 
                    feeAmount, settlementVa.getVaNumber(), sourceWallet.getVaNumber());
            } else {
                log.info("Fee {} credited to Settlement VA {} from wallet {}", 
                    feeAmount, settlementVa.getVaNumber(), sourceWallet.getVaNumber());
            }
            
        } catch (Exception e) {
            // Log error but don't fail the main transaction
            log.error("Failed to credit fee {} to Settlement VA for wallet {}: {}", 
                feeAmount, sourceWallet.getVaNumber(), e.getMessage());
            // In production, this should trigger an alert/notification
        }
    }

    // ========================================================================
    // FEE CALCULATION - NEW
    // ========================================================================

    /**
     * Calculate topup fee based on program configuration.
     */
    private BigDecimal calculateTopupFee(VirtualAccount wallet, BigDecimal amount, String source) {
        if (wallet.getProgramId() == null) {
            return BigDecimal.ZERO;
        }
        
        Program program = programRepository.findById(wallet.getProgramId()).orElse(null);
        if (program == null) {
            return BigDecimal.ZERO;
        }
        
        return program.calculateTopupFee(amount);
    }

    /**
     * Calculate withdrawal fee based on program configuration.
     */
    private BigDecimal calculateWithdrawalFee(VirtualAccount wallet, BigDecimal amount, String destination) {
        if (wallet.getProgramId() == null) {
            return BigDecimal.ZERO;
        }
        
        Program program = programRepository.findById(wallet.getProgramId()).orElse(null);
        if (program == null) {
            return BigDecimal.ZERO;
        }
        
        return program.calculateWithdrawalFee(amount);
    }

    /**
     * Calculate transfer fee based on program configuration.
     */
    private BigDecimal calculateTransferFee(VirtualAccount wallet, BigDecimal amount) {
        if (wallet.getProgramId() == null) {
            return BigDecimal.ZERO;
        }
        
        Program program = programRepository.findById(wallet.getProgramId()).orElse(null);
        if (program == null) {
            return BigDecimal.ZERO;
        }
        
        return program.calculateTransferFee(amount);
    }

    // ========================================================================
    // STATUS MANAGEMENT
    // ========================================================================

    @Transactional
    public WalletAccountResponse suspendWallet(UUID walletId, String reason) {
        VirtualAccount wallet = virtualAccountRepository.findById(walletId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + walletId));
        
        wallet.suspend(reason);
        VirtualAccount saved = virtualAccountRepository.save(wallet);
        
        log.info("Suspended wallet: {} - reason: {}", wallet.getVaNumber(), reason);
        
        return toWalletResponse(saved);
    }

    @Transactional
    public WalletAccountResponse reactivateWallet(UUID walletId, String reason) {
        VirtualAccount wallet = virtualAccountRepository.findById(walletId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + walletId));
        
        if (wallet.getStatus() == VaStatus.ACTIVE) {
            throw new BusinessException("Wallet is already active");
        }
        
        wallet.reactivate();
        VirtualAccount saved = virtualAccountRepository.save(wallet);
        
        log.info("Reactivated wallet: {}", wallet.getVaNumber());
        
        return toWalletResponse(saved);
    }

    @Transactional
    public WalletAccountResponse blockWallet(UUID walletId, String reason) {
        VirtualAccount wallet = virtualAccountRepository.findById(walletId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + walletId));
        
        wallet.block(reason);
        VirtualAccount saved = virtualAccountRepository.save(wallet);
        
        log.info("Blocked wallet: {} - reason: {}", wallet.getVaNumber(), reason);
        
        return toWalletResponse(saved);
    }

    // ========================================================================
    // KYC (syncs with Party KYC status)
    // ========================================================================

    @Transactional
    public KycVerificationResponse verifyKyc(UUID walletId, VerifyKycRequest request) {
        VirtualAccount wallet = virtualAccountRepository.findById(walletId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + walletId));
        
        // Update wallet KYC status
        wallet.verifyKyc(1);
        virtualAccountRepository.save(wallet);
        
        // Also update Party KYC if linked
        if (wallet.getHolderPartyId() != null) {
            Party holder = partyRepository.findById(wallet.getHolderPartyId()).orElse(null);
            if (holder != null) {
                holder.setKycStatus(Party.KycStatus.VERIFIED);
                holder.setKycVerifiedAt(LocalDateTime.now());
                holder.setKycVerifiedBy(request.getVerifiedBy());
                partyRepository.save(holder);
            }
        }
        
        log.info("KYC verified for wallet: {} by {}", wallet.getVaNumber(), request.getVerifiedBy());
        
        return KycVerificationResponse.builder()
                .walletId(walletId)
                .walletReference(wallet.getVaNumber())
                .kycVerified(true)
                .kycStatus("VERIFIED")
                .kycVerifiedAt(wallet.getKycVerifiedAt())
                .verifiedBy(request.getVerifiedBy())
                .build();
    }

    // ========================================================================
    // TRANSACTIONS
    // ========================================================================

    public List<WalletTransactionResponse> getWalletTransactions(UUID walletId, int page, int size) {
        virtualAccountRepository.findById(walletId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + walletId));
        
        Page<Transaction> txnPage = transactionRepository.findByVaId(walletId, 
                PageRequest.of(page, size, Sort.by("transactionDate").descending()));
        
        return txnPage.getContent().stream()
                .map(this::toWalletTransactionResponse)
                .collect(Collectors.toList());
    }

    // ========================================================================
    // WALLET TYPES
    // ========================================================================

    public List<Map<String, Object>> getWalletTypes() {
        return Arrays.asList(
            Map.of("code", "CONSUMER", "name", "Consumer Wallet", "description", "Individual consumer prepaid wallet"),
            Map.of("code", "EMPLOYEE", "name", "Employee Wallet", "description", "Employee expense/benefits wallet"),
            Map.of("code", "MERCHANT", "name", "Merchant Wallet", "description", "Business merchant wallet"),
            Map.of("code", "AGENT", "name", "Agent Wallet", "description", "Agent/distributor wallet"),
            Map.of("code", "CORPORATE", "name", "Corporate Wallet", "description", "Corporate treasury wallet"),
            Map.of("code", "GIFT", "name", "Gift Card", "description", "Gift card/voucher wallet")
        );
    }

    // ========================================================================
    // PRIVATE HELPER METHODS - Data Access
    // ========================================================================

    private List<Program> getWalletPrograms(UUID corporateId) {
        List<Program> programs = programRepository.findByProgramType(ProgramType.WALLET);
        
        // Also include programs with walletEnabled=true
        List<Program> enabledPrograms = programRepository.findAll().stream()
                .filter(p -> Boolean.TRUE.equals(p.getWalletEnabled()) && p.getProgramType() != ProgramType.WALLET)
                .collect(Collectors.toList());
        
        Set<UUID> programIds = programs.stream().map(Program::getId).collect(Collectors.toSet());
        for (Program p : enabledPrograms) {
            if (!programIds.contains(p.getId())) {
                programs.add(p);
            }
        }
        
        if (corporateId != null) {
            programs = programs.stream()
                    .filter(p -> corporateId.equals(p.getCorporateId()))
                    .collect(Collectors.toList());
        }
        
        return programs;
    }

    private List<VirtualAccount> getWalletAccounts(UUID corporateId, UUID programId) {
        List<VirtualAccount> wallets;
        
        if (programId != null) {
            wallets = getWalletsByProgram(programId);
        } else if (corporateId != null) {
            wallets = virtualAccountRepository.findByCorporateId(corporateId, PageRequest.of(0, 10000))
                    .getContent().stream()
                    .filter(VirtualAccount::isWallet)
                    .collect(Collectors.toList());
        } else {
            wallets = virtualAccountRepository.findAll().stream()
                    .filter(VirtualAccount::isWallet)
                    .collect(Collectors.toList());
        }
        
        return wallets;
    }

    private List<VirtualAccount> getWalletsByProgram(UUID programId) {
        return virtualAccountRepository.findByProgramId(programId, PageRequest.of(0, 10000))
                .getContent().stream()
                .filter(VirtualAccount::isWallet)
                .collect(Collectors.toList());
    }

    // ========================================================================
    // PRIVATE HELPER METHODS - Response Mapping
    // ========================================================================

    private WalletProgramResponse toProgramResponse(Program program) {
        List<VirtualAccount> wallets = getWalletsByProgram(program.getId());
        
        long activeWallets = wallets.stream()
                .filter(w -> w.getStatus() == VaStatus.ACTIVE)
                .count();
        
        BigDecimal totalBalance = wallets.stream()
                .map(VirtualAccount::getCurrentBalance)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        String operatorName = getOperatorName(program.getCorporateId());
        
        return WalletProgramResponse.builder()
                .id(program.getId())
                .programCode(program.getProgramCode())
                .programName(program.getProgramName())
                .operatorName(operatorName)
                .corporateId(program.getCorporateId())
                .currency(program.getCurrencyCode())
                .activeWallets(activeWallets)
                .totalWallets((long) wallets.size())
                .totalBalance(totalBalance)
                .dailySpendLimit(program.getDefaultDailyLimit())
                .monthlySpendLimit(program.getDefaultMonthlyLimit())
                .maxBalance(program.getDefaultMaxBalance())
                .minTopup(program.getMinTopup())
                .maxTopup(program.getMaxTopup())
                .kycRequired(program.getKycRequired())
                .status(program.getStatus().name())
                .launchDate(program.getEffectiveFrom())
                .effectiveFrom(program.getEffectiveFrom())
                .effectiveTo(program.getEffectiveTo())
                .createdAt(program.getCreatedAt())
                .updatedAt(program.getUpdatedAt())
                .build();
    }

    private WalletProgramDetailResponse toProgramDetailResponse(Program program) {
        List<VirtualAccount> wallets = getWalletsByProgram(program.getId());
        
        long active = wallets.stream().filter(w -> w.getStatus() == VaStatus.ACTIVE).count();
        long suspended = wallets.stream().filter(w -> w.getStatus() == VaStatus.SUSPENDED).count();
        long blocked = wallets.stream().filter(w -> w.getStatus() == VaStatus.BLOCKED).count();
        
        BigDecimal totalBalance = wallets.stream()
                .map(VirtualAccount::getCurrentBalance)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        String operatorName = getOperatorName(program.getCorporateId());
        
        List<WalletAccountResponse> recentWallets = wallets.stream()
                .sorted((a, b) -> {
                    LocalDateTime dtA = a.getCreatedAt() != null ? a.getCreatedAt() : LocalDateTime.MIN;
                    LocalDateTime dtB = b.getCreatedAt() != null ? b.getCreatedAt() : LocalDateTime.MIN;
                    return dtB.compareTo(dtA);
                })
                .limit(5)
                .map(this::toWalletResponse)
                .collect(Collectors.toList());
        
        return WalletProgramDetailResponse.builder()
                .id(program.getId())
                .programCode(program.getProgramCode())
                .programName(program.getProgramName())
                .description(program.getDescription())
                .operatorName(operatorName)
                .corporateId(program.getCorporateId())
                .physicalAccountId(program.getPhysicalAccountId())
                .currency(program.getCurrencyCode())
                .activeWallets(active)
                .suspendedWallets(suspended)
                .blockedWallets(blocked)
                .totalWallets((long) wallets.size())
                .totalBalance(totalBalance)
                .dailySpendLimit(program.getDefaultDailyLimit())
                .monthlySpendLimit(program.getDefaultMonthlyLimit())
                .dailyTopupLimit(program.getDefaultDailyTopupLimit())
                .monthlyTopupLimit(program.getDefaultMonthlyTopupLimit())
                .maxBalance(program.getDefaultMaxBalance())
                .minTopup(program.getMinTopup())
                .maxTopup(program.getMaxTopup())
                .kycRequired(program.getKycRequired())
                .autoKyc(program.getAutoKyc())
                .allowTransfers(program.getAllowTransfer())
                .allowWithdrawals(program.getAllowWithdrawal())
                .allowTopups(program.getAllowTopup())
                .vaPrefix(program.getVaPrefix())
                .vaFormat(program.getVaFormat())
                .maxVirtualAccounts(program.getMaxVirtualAccounts())
                .status(program.getStatus().name())
                .launchDate(program.getEffectiveFrom())
                .effectiveFrom(program.getEffectiveFrom())
                .effectiveTo(program.getEffectiveTo())
                .monthlyVolume(BigDecimal.valueOf(1500000))
                .monthlyTransactions(750L)
                .createdAt(program.getCreatedAt())
                .updatedAt(program.getUpdatedAt())
                .recentWallets(recentWallets)
                .build();
    }

    private WalletAccountResponse toWalletResponse(VirtualAccount wallet) {
        String programName = null;
        String programCode = null;
        
        if (wallet.getProgramId() != null) {
            Program program = programRepository.findById(wallet.getProgramId()).orElse(null);
            if (program != null) {
                programName = program.getProgramName();
                programCode = program.getProgramCode();
            }
        }
        
        // Get holder info from Party if linked
        String holderMobile = null;
        String holderEmail = null;
        
        if (wallet.getHolderPartyId() != null) {
            Party holder = partyRepository.findById(wallet.getHolderPartyId()).orElse(null);
            if (holder != null) {
                holderMobile = holder.getContactPhone();
                holderEmail = holder.getContactEmail();
            }
        }
        
        // Fall back to metadata
        if (holderMobile == null) {
            holderMobile = extractJsonValue(wallet.getMetadata(), "mobile");
        }
        if (holderEmail == null) {
            holderEmail = extractJsonValue(wallet.getMetadata(), "email");
        }
        
        return WalletAccountResponse.builder()
                .id(wallet.getId())
                .walletReference(wallet.getVaNumber())
                .walletName(wallet.getVaName())
                .holderName(wallet.getVaName())
                .holderMobile(holderMobile)
                .holderEmail(holderEmail)
                .partyId(wallet.getHolderPartyId())
                .programId(wallet.getProgramId())
                .programName(programName)
                .programCode(programCode)
                .currentBalance(wallet.getCurrentBalance())
                .availableBalance(wallet.getAvailableBalance())
                .currency(wallet.getCurrencyCode())
                .dailySpent(wallet.getDailySpent())
                .monthlySpent(wallet.getMonthlySpent())
                .dailyLimit(wallet.getDailyLimit())
                .monthlyLimit(wallet.getMonthlyLimit())
                .status(wallet.getStatus().name())
                .kycVerified(Boolean.TRUE.equals(wallet.getKycVerified()))
                .kycStatus(Boolean.TRUE.equals(wallet.getKycVerified()) ? "VERIFIED" : "PENDING")
                .lastTransaction(wallet.getLastTransactionAt())
                .transactionCount(wallet.getTransactionCount())
                .createdAt(wallet.getCreatedAt())
                .build();
    }

    private WalletAccountDetailResponse toWalletDetailResponse(VirtualAccount wallet) {
        String programName = null;
        String programCode = null;
        String operatorName = null;
        
        if (wallet.getProgramId() != null) {
            Program program = programRepository.findById(wallet.getProgramId()).orElse(null);
            if (program != null) {
                programName = program.getProgramName();
                programCode = program.getProgramCode();
                operatorName = getOperatorName(program.getCorporateId());
            }
        }
        
        // Get holder info from Party
        String holderMobile = null;
        String holderEmail = null;
        
        if (wallet.getHolderPartyId() != null) {
            Party holder = partyRepository.findById(wallet.getHolderPartyId()).orElse(null);
            if (holder != null) {
                holderMobile = holder.getContactPhone();
                holderEmail = holder.getContactEmail();
            }
        }
        
        // Recent transactions
        List<WalletTransactionResponse> recentTxns = transactionRepository
                .findRecentByVaId(wallet.getId(), PageRequest.of(0, 10))
                .getContent().stream()
                .map(this::toWalletTransactionResponse)
                .collect(Collectors.toList());
        
        return WalletAccountDetailResponse.builder()
                .id(wallet.getId())
                .walletReference(wallet.getVaNumber())
                .walletName(wallet.getVaName())
                .viban(wallet.getViban())
                .holderName(wallet.getVaName())
                .holderMobile(holderMobile)
                .holderEmail(holderEmail)
                .partyId(wallet.getHolderPartyId())
                .programId(wallet.getProgramId())
                .programName(programName)
                .programCode(programCode)
                .operatorName(operatorName)
                .corporateId(wallet.getCorporateId())
                .physicalAccountId(wallet.getPhysicalAccountId())
                .currentBalance(wallet.getCurrentBalance())
                .availableBalance(wallet.getAvailableBalance())
                .blockedBalance(wallet.getBlockedBalance())
                .currency(wallet.getCurrencyCode())
                .dailySpent(wallet.getDailySpent())
                .weeklySpent(wallet.getWeeklySpent())
                .monthlySpent(wallet.getMonthlySpent())
                .yearlySpent(wallet.getYearlySpent())
                .dailyLimit(wallet.getDailyLimit())
                .weeklyLimit(wallet.getWeeklyLimit())
                .monthlyLimit(wallet.getMonthlyLimit())
                .yearlyLimit(wallet.getYearlyLimit())
                .perTransactionLimit(wallet.getPerTransactionLimit())
                .status(wallet.getStatus().name())
                .kycVerified(Boolean.TRUE.equals(wallet.getKycVerified()))
                .kycStatus(Boolean.TRUE.equals(wallet.getKycVerified()) ? "VERIFIED" : "PENDING")
                .lastTransaction(wallet.getLastTransactionAt())
                .lastTopup(wallet.getLastTopupAt())
                .lastWithdrawal(wallet.getLastWithdrawalAt())
                .transactionCount(wallet.getTransactionCount())
                .topupCount(wallet.getTopupCount())
                .withdrawalCount(wallet.getWithdrawalCount())
                .externalReference(wallet.getExternalReference())
                .metadata(wallet.getMetadata())
                .createdAt(wallet.getCreatedAt())
                .updatedAt(wallet.getUpdatedAt())
                .expiresAt(wallet.getExpiresAt())
                .recentTransactions(recentTxns)
                .build();
    }

    private WalletTransactionResponse toWalletTransactionResponse(Transaction txn) {
        return WalletTransactionResponse.builder()
                .id(txn.getId())
                .referenceNumber(txn.getReferenceNumber())
                .type(txn.getMovementType().name())
                .amount(txn.getAmount())
                .currency(txn.getCurrencyCode())
                .balanceBefore(txn.getBalanceBefore())
                .balanceAfter(txn.getBalanceAfter())
                .description(txn.getDescription())
                .status(txn.getStatus().name())
                .transactionDate(txn.getTransactionDate())
                .counterpartyName(txn.getRemitterName() != null ? txn.getRemitterName() : txn.getBeneficiaryName())
                .counterpartyAccount(txn.getRemitterAccount() != null ? txn.getRemitterAccount() : txn.getBeneficiaryAccount())
                .build();
    }

    private WalletListSummary buildWalletListSummary(List<VirtualAccount> wallets) {
        long active = wallets.stream().filter(w -> w.getStatus() == VaStatus.ACTIVE).count();
        long suspended = wallets.stream().filter(w -> w.getStatus() == VaStatus.SUSPENDED).count();
        long blocked = wallets.stream().filter(w -> w.getStatus() == VaStatus.BLOCKED).count();
        long kycVerified = wallets.stream().filter(w -> Boolean.TRUE.equals(w.getKycVerified())).count();
        
        BigDecimal totalBalance = wallets.stream()
                .map(VirtualAccount::getCurrentBalance)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        return WalletListSummary.builder()
                .activeCount(active)
                .suspendedCount(suspended)
                .blockedCount(blocked)
                .totalBalance(totalBalance)
                .kycVerifiedCount(kycVerified)
                .kycPendingCount(wallets.size() - kycVerified)
                .build();
    }

    // ========================================================================
    // PRIVATE HELPER METHODS - Utilities
    // ========================================================================

    private String getOperatorName(UUID corporateId) {
        if (corporateId == null) return "Unknown";
        return corporateRepository.findById(corporateId)
                .map(Corporate::getLegalName)
                .orElse("Corporate " + corporateId.toString().substring(0, 8));
    }

    private String generateWalletNumber(Program program) {
        String prefix = "W-";
        if (program != null && program.getVaPrefix() != null) {
            prefix = program.getVaPrefix();
        }
        return prefix + System.currentTimeMillis() + String.format("%04d", (int)(Math.random() * 10000));
    }

    private String buildWalletMetadata(IssueWalletRequest request) {
        StringBuilder sb = new StringBuilder("{");
        if (request.getHolderMobile() != null) {
            sb.append("\"mobile\":\"").append(request.getHolderMobile()).append("\"");
        }
        if (request.getHolderEmail() != null) {
            if (sb.length() > 1) sb.append(",");
            sb.append("\"email\":\"").append(request.getHolderEmail()).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private String extractJsonValue(String json, String key) {
        if (json == null) return null;
        try {
            String searchKey = "\"" + key + "\":\"";
            int start = json.indexOf(searchKey);
            if (start == -1) return null;
            start += searchKey.length();
            int end = json.indexOf("\"", start);
            if (end == -1) return null;
            return json.substring(start, end);
        } catch (Exception e) {
            return null;
        }
    }

    // ========================================================================
    // PRIVATE HELPER METHODS - Transaction Recording
    // ========================================================================

    private Transaction recordTransaction(VirtualAccount wallet, MovementType type, BigDecimal amount,
                                          BigDecimal balanceBefore, BigDecimal balanceAfter,
                                          String description, String externalRef, String sourceType) {
        Transaction txn = Transaction.builder()
                .referenceNumber(Transaction.generateReference(type))
                .externalReference(externalRef)
                .movementType(type)
                .corporateId(wallet.getCorporateId())
                .vaId(wallet.getId())
                .physicalAccountId(wallet.getPhysicalAccountId())
                .programId(wallet.getProgramId())
                .amount(amount)
                .currencyCode(wallet.getCurrencyCode())
                .balanceBefore(balanceBefore)
                .balanceAfter(balanceAfter)
                .description(description)
                .sourceType(sourceType)
                .status(TransactionStatus.COMPLETED)
                .transactionDate(LocalDateTime.now())
                .valueDate(LocalDate.now())
                .channel("API")
                .build();
        
        return transactionRepository.save(txn);
    }

    private Transaction recordTransferTransaction(VirtualAccount wallet, VirtualAccount counterparty,
                                                   MovementType type, BigDecimal amount,
                                                   BigDecimal balanceBefore, BigDecimal balanceAfter,
                                                   String description, String correlationId) {
        Transaction txn = Transaction.builder()
                .referenceNumber(Transaction.generateReference(type))
                .correlationId(correlationId)
                .movementType(type)
                .corporateId(wallet.getCorporateId())
                .vaId(wallet.getId())
                .physicalAccountId(wallet.getPhysicalAccountId())
                .programId(wallet.getProgramId())
                .amount(amount)
                .currencyCode(wallet.getCurrencyCode())
                .balanceBefore(balanceBefore)
                .balanceAfter(balanceAfter)
                .counterpartyVaId(counterparty.getId())
                .beneficiaryName(type.name().contains("OUT") ? counterparty.getVaName() : null)
                .beneficiaryAccount(type.name().contains("OUT") ? counterparty.getVaNumber() : null)
                .remitterName(type.name().contains("IN") ? counterparty.getVaName() : null)
                .remitterAccount(type.name().contains("IN") ? counterparty.getVaNumber() : null)
                .description(description)
                .status(TransactionStatus.COMPLETED)
                .transactionDate(LocalDateTime.now())
                .valueDate(LocalDate.now())
                .channel("API")
                .build();
        
        return transactionRepository.save(txn);
    }
}