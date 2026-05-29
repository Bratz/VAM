package com.bank.vam.controller;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.Corporate;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.PhysicalAccount;
import com.bank.vam.entity.treasury.NettingCycle;
import com.bank.vam.entity.treasury.NotionalPool;
import com.bank.vam.entity.treasury.SweepRule;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.CorporateRepository;
import com.bank.vam.repository.PhysicalAccountRepository;
import com.bank.vam.repository.treasury.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "Dashboard", description = "Dashboard and analytics APIs")
public class DashboardController {

    private final VirtualAccountRepository virtualAccountRepository;
    private final TransactionRepository transactionRepository;
    private final CorporateRepository corporateRepository;
    private final PhysicalAccountRepository physicalAccountRepository;
    private final SweepRuleRepository sweepRuleRepository;
    private final NotionalPoolRepository notionalPoolRepository;
    private final NettingCycleRepository nettingCycleRepository;
    private final com.bank.vam.config.MarketProfileProperties marketProfile;

    // ============================================================================
    // MAIN DASHBOARD STATS
    // ============================================================================

    @GetMapping("/stats")
    @Operation(summary = "Get main dashboard statistics")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDashboardStats() {
        Map<String, Object> stats = new HashMap<>();
        
        try {
            // Account stats
            long totalAccounts = virtualAccountRepository.count();
            long activeAccounts = virtualAccountRepository.countByStatus(VirtualAccount.VaStatus.ACTIVE);
            stats.put("totalAccounts", totalAccounts);
            stats.put("activeAccounts", activeAccounts);
            
            // Physical accounts
            long totalPhysicalAccounts = physicalAccountRepository.count();
            stats.put("totalPhysicalAccounts", totalPhysicalAccounts);
            
            // Transaction stats
            long totalTransactions = transactionRepository.count();
            stats.put("totalTransactions", totalTransactions);
            
            // Today's transactions - use demo value if method doesn't exist
            long todayTransactions = 156; // Demo default
            stats.put("todayTransactions", todayTransactions);
            
            // Today's volume (demo data)
            BigDecimal todayVolume = BigDecimal.valueOf(2340000);
            stats.put("todayVolume", todayVolume);
            
            // Pending transactions
            long pendingTransactions = transactionRepository.countByStatus(Transaction.TransactionStatus.PENDING);
            stats.put("pendingTransactions", pendingTransactions);
            
            // Corporate stats
            long totalCorporates = corporateRepository.count();
            long activeCorporates = corporateRepository.countByStatus(Corporate.CorporateStatus.ACTIVE);
            stats.put("totalCorporates", totalCorporates);
            stats.put("activeCorporates", activeCorporates);
            
            // Treasury stats - Fixed: Use enum instead of String
            long activeSweepRules = sweepRuleRepository.countActive();
            long activePools = notionalPoolRepository.countActive();
            long pendingNettingCycles = nettingCycleRepository.countByStatus(NettingCycle.CycleStatus.PENDING_APPROVAL);
            stats.put("activeSweepRules", activeSweepRules);
            stats.put("activePools", activePools);
            stats.put("pendingNettingCycles", pendingNettingCycles);
            
            // Balance summary - use repository methods with null check
            BigDecimal totalBalance = null;
            BigDecimal availableBalance = null;
            try {
                totalBalance = virtualAccountRepository.sumCurrentBalance();
                availableBalance = virtualAccountRepository.sumAvailableBalance();
            } catch (Exception e) {
                log.warn("Could not fetch balance sums: {}", e.getMessage());
            }
            
            if (totalBalance == null || totalBalance.compareTo(BigDecimal.ZERO) == 0) {
                totalBalance = BigDecimal.valueOf(125000000);
            }
            if (availableBalance == null || availableBalance.compareTo(BigDecimal.ZERO) == 0) {
                availableBalance = BigDecimal.valueOf(118500000);
            }
            stats.put("totalBalance", totalBalance);
            stats.put("availableBalance", availableBalance);
            
            // Balance change percentage (mock - would be calculated from historical data)
            stats.put("balanceChange", 12.5);
            
            // Pending KYC
            long pendingKyc = corporateRepository.countByKycStatus(Corporate.KycStatus.PENDING);
            stats.put("pendingKyc", pendingKyc);
            
        } catch (Exception e) {
            log.error("Error fetching dashboard stats", e);
            // Return demo data on error
            stats.put("totalAccounts", 24);
            stats.put("activeAccounts", 22);
            stats.put("totalTransactions", 1560);
            stats.put("todayTransactions", 156);
            stats.put("todayVolume", BigDecimal.valueOf(2340000));
            stats.put("pendingTransactions", 3);
            stats.put("totalCorporates", 15);
            stats.put("activeCorporates", 12);
            stats.put("activeSweepRules", 5);
            stats.put("activePools", 3);
            stats.put("pendingNettingCycles", 2);
            stats.put("totalBalance", BigDecimal.valueOf(125000000));
            stats.put("availableBalance", BigDecimal.valueOf(118500000));
            stats.put("balanceChange", 12.5);
            stats.put("pendingKyc", 4);
        }
        
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    // ============================================================================
    // BALANCE TREND
    // ============================================================================

    @GetMapping("/balance-trend")
    @Operation(summary = "Get balance trend over time")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getBalanceTrend(
            @RequestParam(defaultValue = "30") int days) {
        List<Map<String, Object>> trend = new ArrayList<>();
        LocalDate today = LocalDate.now();
        Random random = new Random(42); // Deterministic for demo
        
        double baseBalance = 100000000;
        
        for (int i = days; i >= 0; i--) {
            Map<String, Object> point = new HashMap<>();
            LocalDate date = today.minusDays(i);
            point.put("date", date.format(DateTimeFormatter.ofPattern("MMM dd")));
            point.put("fullDate", date.toString());
            
            double variation = (random.nextDouble() - 0.3) * 5000000;
            double balance = baseBalance + variation + (days - i) * 100000; // Upward trend
            double available = balance * 0.95;
            
            point.put("balance", Math.round(balance));
            point.put("available", Math.round(available));
            point.put("inflow", Math.round(random.nextDouble() * 5000000));
            point.put("outflow", Math.round(random.nextDouble() * 4000000));
            trend.add(point);
        }
        
        return ResponseEntity.ok(ApiResponse.success(trend));
    }

    // ============================================================================
    // CASH FLOW ANALYTICS
    // ============================================================================

    @GetMapping("/cash-flow")
    @Operation(summary = "Get cash flow analytics")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCashFlow(
            @RequestParam(defaultValue = "30") int days) {
        Map<String, Object> cashFlow = new HashMap<>();
        
        // Summary
        cashFlow.put("totalInflow", BigDecimal.valueOf(45000000));
        cashFlow.put("totalOutflow", BigDecimal.valueOf(38000000));
        cashFlow.put("netCashFlow", BigDecimal.valueOf(7000000));
        cashFlow.put("inflowChange", 15.3);
        cashFlow.put("outflowChange", 8.7);
        
        // By category
        List<Map<String, Object>> inflowByCategory = Arrays.asList(
            Map.of("category", "Collections", "amount", 25000000, "percentage", 55.6),
            Map.of("category", "Transfers In", "amount", 12000000, "percentage", 26.7),
            Map.of("category", "Interest", "amount", 5000000, "percentage", 11.1),
            Map.of("category", "Other", "amount", 3000000, "percentage", 6.6)
        );
        cashFlow.put("inflowByCategory", inflowByCategory);
        
        List<Map<String, Object>> outflowByCategory = Arrays.asList(
            Map.of("category", "Payments", "amount", 20000000, "percentage", 52.6),
            Map.of("category", "Transfers Out", "amount", 10000000, "percentage", 26.3),
            Map.of("category", "Fees", "amount", 5000000, "percentage", 13.2),
            Map.of("category", "Other", "amount", 3000000, "percentage", 7.9)
        );
        cashFlow.put("outflowByCategory", outflowByCategory);
        
        // Daily trend
        List<Map<String, Object>> dailyTrend = new ArrayList<>();
        Random random = new Random(42);
        LocalDate today = LocalDate.now();
        
        for (int i = days; i >= 0; i--) {
            Map<String, Object> point = new HashMap<>();
            point.put("date", today.minusDays(i).format(DateTimeFormatter.ofPattern("MMM dd")));
            point.put("inflow", Math.round(random.nextDouble() * 2000000));
            point.put("outflow", Math.round(random.nextDouble() * 1800000));
            dailyTrend.add(point);
        }
        cashFlow.put("dailyTrend", dailyTrend);
        
        return ResponseEntity.ok(ApiResponse.success(cashFlow));
    }

    // ============================================================================
    // TOP ACCOUNTS
    // ============================================================================

    @GetMapping("/top-accounts")
    @Operation(summary = "Get top accounts by balance")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getTopAccounts(
            @RequestParam(defaultValue = "5") int limit) {
        List<Map<String, Object>> accounts = new ArrayList<>();
        
        try {
            // Try to get real data using Pageable
            Pageable pageable = PageRequest.of(0, limit);
            var topAccountsPage = virtualAccountRepository.findTopByBalanceDesc(pageable);
            
            if (topAccountsPage != null && topAccountsPage.hasContent()) {
                for (VirtualAccount va : topAccountsPage.getContent()) {
                    Map<String, Object> account = new HashMap<>();
                    account.put("id", va.getId().toString());
                    account.put("vaNumber", va.getVaNumber());
                    account.put("viban", va.getViban());
                    account.put("name", va.getVaName());
                    account.put("balance", va.getCurrentBalance());
                    account.put("availableBalance", va.getAvailableBalance());
                    account.put("currency", va.getCurrencyCode());
                    account.put("status", va.getStatus().name());
                    accounts.add(account);
                }
            }
        } catch (Exception e) {
            log.warn("Using demo data for top accounts: {}", e.getMessage());
        }
        
        // Use demo data if no real data
        if (accounts.isEmpty()) {
            accounts = Arrays.asList(
                createDemoAccount("VA-HQ-001", "AE150410000011234567890", "Operating Account", 5250000, 5100000),
                createDemoAccount("VA-HQ-002", "AE150410000011234567891", "Payroll Account", 3100000, 3100000),
                createDemoAccount("VA-HQ-003", "AE150410000011234567892", "Vendor Payments", 2450000, 2400000),
                createDemoAccount("VA-HQ-004", "AE150410000011234567893", "Collections", 1650000, 1650000),
                createDemoAccount("VA-HQ-005", "AE150410000011234567894", "Treasury Reserve", 1200000, 1200000)
            );
        }
        
        return ResponseEntity.ok(ApiResponse.success(accounts));
    }
    
    private Map<String, Object> createDemoAccount(String vaNumber, String viban, String name, 
                                                   double balance, double availableBalance) {
        Map<String, Object> account = new HashMap<>();
        account.put("id", UUID.randomUUID().toString());
        account.put("vaNumber", vaNumber);
        account.put("viban", viban);
        account.put("name", name);
        account.put("balance", balance);
        account.put("availableBalance", availableBalance);
        account.put("currency", marketProfile.getDefaultCurrency());
        account.put("status", "ACTIVE");
        return account;
    }

    // ============================================================================
    // RECENT TRANSACTIONS
    // ============================================================================

    @GetMapping("/recent-transactions")
    @Operation(summary = "Get recent transactions")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getRecentTransactions(
            @RequestParam(defaultValue = "10") int limit) {
        List<Map<String, Object>> transactions = new ArrayList<>();
        
        try {
            // Get recent transactions using existing findAll with stream limit
            List<Transaction> recentTxns = transactionRepository.findAll().stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .limit(limit)
                .collect(Collectors.toList());
            
            if (!recentTxns.isEmpty()) {
                for (Transaction tx : recentTxns) {
                    Map<String, Object> txn = new HashMap<>();
                    txn.put("id", tx.getId().toString());
                    txn.put("reference", tx.getReferenceNumber());
                    txn.put("beneficiary", tx.getDescription() != null ? tx.getDescription() : "N/A");
                    txn.put("amount", tx.getAmount());
                    txn.put("currency", tx.getCurrencyCode());
                    txn.put("type", tx.getMovementType().name());
                    txn.put("status", tx.getStatus().name());
                    txn.put("date", tx.getCreatedAt().toString());
                    transactions.add(txn);
                }
            }
        } catch (Exception e) {
            log.warn("Using demo data for transactions: {}", e.getMessage());
        }
        
        // Use demo data if no real data
        if (transactions.isEmpty()) {
            LocalDateTime now = LocalDateTime.now();
            transactions = Arrays.asList(
                createDemoTransaction("TXN-2024-001234", "Emirates Trading LLC", 125000, "DEBIT", "COMPLETED", now.minusHours(1)),
                createDemoTransaction("TXN-2024-001235", "Dubai Logistics Co", 85000, "DEBIT", "PROCESSING", now.minusHours(2)),
                createDemoTransaction("TXN-2024-001236", "Gulf Services FZE", 250000, "CREDIT", "PENDING", now.minusHours(3)),
                createDemoTransaction("TXN-2024-001237", "Abu Dhabi Steel", 175000, "DEBIT", "COMPLETED", now.minusHours(4))
            );
        }
        
        return ResponseEntity.ok(ApiResponse.success(transactions));
    }
    
    private Map<String, Object> createDemoTransaction(String reference, String beneficiary, 
                                                       double amount, String type, String status, LocalDateTime date) {
        Map<String, Object> txn = new HashMap<>();
        txn.put("id", UUID.randomUUID().toString());
        txn.put("reference", reference);
        txn.put("beneficiary", beneficiary);
        txn.put("amount", amount);
        txn.put("currency", marketProfile.getDefaultCurrency());
        txn.put("type", type);
        txn.put("status", status);
        txn.put("date", date.toString());
        return txn;
    }

    // Keep backward compatibility
    @GetMapping("/recent-activity")
    @Operation(summary = "Get recent activity (legacy)")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getRecentActivity(
            @RequestParam(defaultValue = "10") int limit) {
        return getRecentTransactions(limit);
    }

    // ============================================================================
    // TREASURY SUMMARY
    // ============================================================================

    @GetMapping("/treasury-summary")
    @Operation(summary = "Get treasury summary including pools, sweeping, netting")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTreasurySummary() {
        Map<String, Object> summary = new HashMap<>();
        
        // Notional Pooling
        Map<String, Object> pooling = new HashMap<>();
        try {
            long activePools = notionalPoolRepository.countActive();
            BigDecimal totalPooledBalance = notionalPoolRepository.sumTotalPooledBalance();
            pooling.put("activePools", activePools);
            pooling.put("totalPooledBalance", totalPooledBalance != null ? totalPooledBalance : BigDecimal.ZERO);
            pooling.put("interestSavingsYtd", BigDecimal.valueOf(1250000)); // Demo
            pooling.put("memberCount", 12); // Demo
        } catch (Exception e) {
            pooling.put("activePools", 3);
            pooling.put("totalPooledBalance", BigDecimal.valueOf(45000000));
            pooling.put("interestSavingsYtd", BigDecimal.valueOf(1250000));
            pooling.put("memberCount", 12);
        }
        summary.put("pooling", pooling);
        
        // Cash Sweeping
        Map<String, Object> sweeping = new HashMap<>();
        try {
            long activeRules = sweepRuleRepository.countActive();
            sweeping.put("activeRules", activeRules);
            sweeping.put("totalSweptToday", BigDecimal.valueOf(8500000)); // Demo
            sweeping.put("executionsToday", 12); // Demo
            sweeping.put("nextExecution", LocalDateTime.now().plusHours(2).toString());
        } catch (Exception e) {
            sweeping.put("activeRules", 5);
            sweeping.put("totalSweptToday", BigDecimal.valueOf(8500000));
            sweeping.put("executionsToday", 12);
            sweeping.put("nextExecution", LocalDateTime.now().plusHours(2).toString());
        }
        summary.put("sweeping", sweeping);
        
        // Netting
        Map<String, Object> netting = new HashMap<>();
        try {
            long pendingCycles = nettingCycleRepository.countByStatus(NettingCycle.CycleStatus.PENDING_APPROVAL);
            BigDecimal totalSavings = nettingCycleRepository.sumTotalSavings();
            netting.put("pendingCycles", pendingCycles);
            netting.put("totalSavingsYtd", totalSavings != null ? totalSavings : BigDecimal.valueOf(2800000));
            netting.put("averageSavingsPercent", 35.5); // Demo
            netting.put("settledCyclesMtd", 8); // Demo
        } catch (Exception e) {
            netting.put("pendingCycles", 2);
            netting.put("totalSavingsYtd", BigDecimal.valueOf(2800000));
            netting.put("averageSavingsPercent", 35.5);
            netting.put("settledCyclesMtd", 8);
        }
        summary.put("netting", netting);
        
        // IHB Summary
        Map<String, Object> ihb = new HashMap<>();
        ihb.put("totalLoansOutstanding", BigDecimal.valueOf(15000000));
        ihb.put("totalDeposits", BigDecimal.valueOf(22000000));
        ihb.put("netInterestIncome", BigDecimal.valueOf(350000));
        ihb.put("activeEntities", 8);
        summary.put("inHouseBank", ihb);
        
        return ResponseEntity.ok(ApiResponse.success(summary));
    }

    // ============================================================================
    // RECEIVABLES & PAYABLES SUMMARY
    // ============================================================================

    @GetMapping("/receivables-payables")
    @Operation(summary = "Get receivables and payables summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getReceivablesPayablesSummary() {
        Map<String, Object> summary = new HashMap<>();
        
        // Receivables
        Map<String, Object> receivables = new HashMap<>();
        receivables.put("totalOutstanding", BigDecimal.valueOf(8500000));
        receivables.put("overdueAmount", BigDecimal.valueOf(1200000));
        receivables.put("collectedThisMonth", BigDecimal.valueOf(12500000));
        receivables.put("pendingCount", 45);
        receivables.put("overdueCount", 8);
        receivables.put("dsoAverage", 32); // Days Sales Outstanding
        
        // Aging breakdown
        List<Map<String, Object>> receivablesAging = Arrays.asList(
            Map.of("bucket", "Current", "amount", 4500000, "percentage", 52.9),
            Map.of("bucket", "1-30 Days", "amount", 2000000, "percentage", 23.5),
            Map.of("bucket", "31-60 Days", "amount", 800000, "percentage", 9.4),
            Map.of("bucket", "61-90 Days", "amount", 600000, "percentage", 7.1),
            Map.of("bucket", "90+ Days", "amount", 600000, "percentage", 7.1)
        );
        receivables.put("aging", receivablesAging);
        summary.put("receivables", receivables);
        
        // Payables
        Map<String, Object> payables = new HashMap<>();
        payables.put("totalOutstanding", BigDecimal.valueOf(6200000));
        payables.put("overdueAmount", BigDecimal.valueOf(450000));
        payables.put("paidThisMonth", BigDecimal.valueOf(9800000));
        payables.put("pendingApproval", 12);
        payables.put("scheduledPayments", 28);
        payables.put("dpoAverage", 28); // Days Payables Outstanding
        
        // Aging breakdown
        List<Map<String, Object>> payablesAging = Arrays.asList(
            Map.of("bucket", "Current", "amount", 3800000, "percentage", 61.3),
            Map.of("bucket", "1-30 Days", "amount", 1500000, "percentage", 24.2),
            Map.of("bucket", "31-60 Days", "amount", 450000, "percentage", 7.3),
            Map.of("bucket", "61-90 Days", "amount", 250000, "percentage", 4.0),
            Map.of("bucket", "90+ Days", "amount", 200000, "percentage", 3.2)
        );
        payables.put("aging", payablesAging);
        summary.put("payables", payables);
        
        // Net position
        summary.put("netPosition", BigDecimal.valueOf(2300000)); // Receivables - Payables
        summary.put("workingCapitalRatio", 1.37);
        
        return ResponseEntity.ok(ApiResponse.success(summary));
    }

    // ============================================================================
    // PENDING APPROVALS (Drill-down feature)
    // ============================================================================

    @GetMapping("/pending-approvals")
    @Operation(summary = "Get all pending approvals across modules")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPendingApprovals() {
        Map<String, Object> approvals = new HashMap<>();
        
        // Pending netting cycles
        List<Map<String, Object>> pendingNetting = new ArrayList<>();
        try {
            List<NettingCycle> cycles = nettingCycleRepository.findByStatus(NettingCycle.CycleStatus.PENDING_APPROVAL);
            for (NettingCycle cycle : cycles) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", cycle.getId().toString());
                item.put("reference", cycle.getCycleReference());
                item.put("name", cycle.getCycleName());
                item.put("amount", cycle.getTotalNet());
                item.put("savingsAmount", cycle.getSavingsAmount());
                item.put("createdAt", cycle.getCreatedAt() != null ? cycle.getCreatedAt().toString() : null);
                pendingNetting.add(item);
            }
        } catch (Exception e) {
            log.warn("Error fetching pending netting cycles: {}", e.getMessage());
            // Demo data
            Map<String, Object> demo = new HashMap<>();
            demo.put("id", UUID.randomUUID().toString());
            demo.put("reference", "NET-202412-001");
            demo.put("name", "December Netting Cycle");
            demo.put("amount", 5600000);
            demo.put("savingsAmount", 2100000);
            demo.put("createdAt", LocalDateTime.now().minusDays(1).toString());
            pendingNetting.add(demo);
        }
        approvals.put("nettingCycles", pendingNetting);
        approvals.put("nettingCount", pendingNetting.size());
        
        // Pending KYC
        List<Map<String, Object>> pendingKyc = new ArrayList<>();
        try {
            List<Corporate> corps = corporateRepository.findByStatus(Corporate.CorporateStatus.PENDING);
            for (Corporate corp : corps) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", corp.getId().toString());
                item.put("corporateId", corp.getCorporateId());
                item.put("name", corp.getLegalName());
                item.put("submittedAt", corp.getCreatedAt() != null ? corp.getCreatedAt().toString() : null);
                pendingKyc.add(item);
            }
        } catch (Exception e) {
            log.warn("Error fetching pending KYC: {}", e.getMessage());
            // Demo data
            pendingKyc.add(Map.of("id", UUID.randomUUID().toString(), "corporateId", "CORP-001",
                    "name", "Acme Trading LLC", "submittedAt", LocalDateTime.now().minusDays(2).toString()));
            pendingKyc.add(Map.of("id", UUID.randomUUID().toString(), "corporateId", "CORP-002",
                    "name", "Gulf Enterprises FZE", "submittedAt", LocalDateTime.now().minusDays(3).toString()));
        }
        approvals.put("kycApplications", pendingKyc);
        approvals.put("kycCount", pendingKyc.size());
        
        // Pending payables (mock - would need PayablesRepository)
        List<Map<String, Object>> pendingPayables = Arrays.asList(
            Map.of("id", UUID.randomUUID().toString(), "invoiceNumber", "INV-2024-001", 
                   "vendorName", "Emirates Supplies Co", "amount", 125000, 
                   "dueDate", LocalDate.now().plusDays(5).toString()),
            Map.of("id", UUID.randomUUID().toString(), "invoiceNumber", "INV-2024-002",
                   "vendorName", "Dubai Logistics", "amount", 85000,
                   "dueDate", LocalDate.now().plusDays(7).toString())
        );
        approvals.put("payables", pendingPayables);
        approvals.put("payablesCount", pendingPayables.size());
        
        // Pending transactions
        List<Map<String, Object>> pendingTxns = new ArrayList<>();
        try {
            transactionRepository.findByStatus(Transaction.TransactionStatus.PENDING, PageRequest.of(0, 5))
                .getContent()
                .forEach(tx -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", tx.getId().toString());
                    item.put("reference", tx.getReferenceNumber());
                    item.put("amount", tx.getAmount());
                    item.put("description", tx.getDescription());
                    item.put("createdAt", tx.getCreatedAt() != null ? tx.getCreatedAt().toString() : null);
                    pendingTxns.add(item);
                });
        } catch (Exception e) {
            log.warn("Error fetching pending transactions: {}", e.getMessage());
            // Demo data
            pendingTxns.add(Map.of("id", UUID.randomUUID().toString(), "reference", "TXN-2024-001240",
                    "amount", 350000, "description", "Bulk Payment", 
                    "createdAt", LocalDateTime.now().minusHours(2).toString()));
        }
        approvals.put("transactions", pendingTxns);
        approvals.put("transactionsCount", pendingTxns.size());
        
        // Total pending count
        int totalPending = pendingNetting.size() + pendingKyc.size() + 
                          pendingPayables.size() + pendingTxns.size();
        approvals.put("totalPending", totalPending);
        
        return ResponseEntity.ok(ApiResponse.success(approvals));
    }

    // ============================================================================
    // ALERTS & NOTIFICATIONS
    // ============================================================================

    @GetMapping("/alerts")
    @Operation(summary = "Get system alerts and notifications")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getAlerts() {
        List<Map<String, Object>> alerts = new ArrayList<>();
        
        try {
            // Check for pending netting approvals - Fixed: Use enum
            long pendingNetting = nettingCycleRepository.countByStatus(NettingCycle.CycleStatus.PENDING_APPROVAL);
            if (pendingNetting > 0) {
                Map<String, Object> alert = new HashMap<>();
                alert.put("id", UUID.randomUUID().toString());
                alert.put("type", "WARNING");
                alert.put("severity", "MEDIUM");
                alert.put("title", "Pending Netting Approvals");
                alert.put("message", pendingNetting + " netting cycle(s) awaiting approval");
                alert.put("action", "/treasury/netting");
                alert.put("timestamp", LocalDateTime.now().toString());
                alerts.add(alert);
            }
            
            // Check for pending KYC
            long pendingKyc = corporateRepository.countByKycStatus(Corporate.KycStatus.PENDING);
            if (pendingKyc > 0) {
                Map<String, Object> alert = new HashMap<>();
                alert.put("id", UUID.randomUUID().toString());
                alert.put("type", "INFO");
                alert.put("severity", "LOW");
                alert.put("title", "KYC Applications Pending");
                alert.put("message", pendingKyc + " corporate(s) awaiting KYC review");
                alert.put("action", "/kyc");
                alert.put("timestamp", LocalDateTime.now().toString());
                alerts.add(alert);
            }
            
            // Check for pending transactions
            long pendingTxns = transactionRepository.countByStatus(Transaction.TransactionStatus.PENDING);
            if (pendingTxns > 0) {
                Map<String, Object> alert = new HashMap<>();
                alert.put("id", UUID.randomUUID().toString());
                alert.put("type", "INFO");
                alert.put("severity", "LOW");
                alert.put("title", "Transactions Pending");
                alert.put("message", pendingTxns + " transaction(s) pending processing");
                alert.put("action", "/transactions");
                alert.put("timestamp", LocalDateTime.now().toString());
                alerts.add(alert);
            }
            
        } catch (Exception e) {
            log.warn("Error fetching alerts: {}", e.getMessage());
        }
        
        // Add demo alerts if none exist
        if (alerts.isEmpty()) {
            Map<String, Object> successAlert = new HashMap<>();
            successAlert.put("id", UUID.randomUUID().toString());
            successAlert.put("type", "SUCCESS");
            successAlert.put("severity", "LOW");
            successAlert.put("title", "System Status");
            successAlert.put("message", "All systems operational");
            successAlert.put("action", "");
            successAlert.put("timestamp", LocalDateTime.now().toString());
            alerts.add(successAlert);
        }
        
        // Add scheduled maintenance alert (demo)
        Map<String, Object> maintenanceAlert = new HashMap<>();
        maintenanceAlert.put("id", UUID.randomUUID().toString());
        maintenanceAlert.put("type", "INFO");
        maintenanceAlert.put("severity", "LOW");
        maintenanceAlert.put("title", "Scheduled Maintenance");
        maintenanceAlert.put("message", "System maintenance scheduled for Sunday 2:00 AM - 4:00 AM");
        maintenanceAlert.put("action", "");
        maintenanceAlert.put("timestamp", LocalDateTime.now().toString());
        alerts.add(maintenanceAlert);
        
        return ResponseEntity.ok(ApiResponse.success(alerts));
    }

    // ============================================================================
    // QUICK STATS WIDGETS
    // ============================================================================

    @GetMapping("/widget/{widgetType}")
    @Operation(summary = "Get specific widget data")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getWidgetData(
            @PathVariable String widgetType) {
        Map<String, Object> data = new HashMap<>();
        
        switch (widgetType.toLowerCase()) {
            case "accounts":
                data.put("total", 24);
                data.put("active", 22);
                data.put("pending", 2);
                data.put("trend", Arrays.asList(18, 19, 20, 21, 22, 22, 22));
                break;
                
            case "transactions":
                data.put("todayCount", 156);
                data.put("todayVolume", 2340000);
                data.put("weekCount", 892);
                data.put("weekVolume", 15600000);
                data.put("trend", Arrays.asList(120, 145, 132, 156, 142, 168, 156));
                break;
                
            case "balance":
                data.put("current", 125000000);
                data.put("available", 118500000);
                data.put("change", 12.5);
                data.put("trend", Arrays.asList(110, 112, 115, 118, 120, 123, 125));
                break;
                
            case "treasury":
                data.put("pooledBalance", 45000000);
                data.put("sweptToday", 8500000);
                data.put("nettingSavings", 2800000);
                data.put("ihbLoans", 15000000);
                break;
                
            default:
                return ResponseEntity.notFound().build();
        }
        
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    // ============================================================================
    // ACCOUNT DISTRIBUTION
    // ============================================================================

    @GetMapping("/account-distribution")
    @Operation(summary = "Get account distribution by type/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAccountDistribution() {
        Map<String, Object> distribution = new HashMap<>();
        
        // By status
        List<Map<String, Object>> byStatus = Arrays.asList(
            Map.of("status", "ACTIVE", "count", 22, "balance", 118500000, "percentage", 91.7),
            Map.of("status", "PENDING", "count", 1, "balance", 0, "percentage", 4.2),
            Map.of("status", "FROZEN", "count", 1, "balance", 6500000, "percentage", 4.1)
        );
        distribution.put("byStatus", byStatus);
        
        // By type
        List<Map<String, Object>> byType = Arrays.asList(
            Map.of("type", "OPERATING", "count", 8, "balance", 45000000, "percentage", 36.0),
            Map.of("type", "COLLECTIONS", "count", 6, "balance", 35000000, "percentage", 28.0),
            Map.of("type", "PAYROLL", "count", 4, "balance", 25000000, "percentage", 20.0),
            Map.of("type", "ESCROW", "count", 3, "balance", 12500000, "percentage", 10.0),
            Map.of("type", "OTHER", "count", 3, "balance", 7500000, "percentage", 6.0)
        );
        distribution.put("byType", byType);
        
        // By currency
        List<Map<String, Object>> byCurrency = Arrays.asList(
            Map.of("currency", marketProfile.getDefaultCurrency(), "count", 18, "balance", 95000000, "percentage", 76.0),
            Map.of("currency", "USD", "count", 4, "balance", 22000000, "percentage", 17.6),
            Map.of("currency", "EUR", "count", 2, "balance", 8000000, "percentage", 6.4)
        );
        distribution.put("byCurrency", byCurrency);
        
        return ResponseEntity.ok(ApiResponse.success(distribution));
    }
}