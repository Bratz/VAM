package com.bank.vam.controller;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/statements")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class StatementController {

    private final VirtualAccountRepository virtualAccountRepository;
    private final TransactionRepository transactionRepository;

    @GetMapping("/account/{accountId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAccountStatement(
            @PathVariable UUID accountId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        
        VirtualAccount account = virtualAccountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountId));
        
        // Use findByVaIdAndDateRange which queries transactionDate field
        List<Transaction> transactions = transactionRepository.findByVaIdAndDateRange(
                accountId, 
                fromDate.atStartOfDay(), 
                toDate.plusDays(1).atStartOfDay());
        
        // Calculate totals - credits include CREDIT, TRANSFER_IN, SWEEP_IN, POOL_CREDIT
        BigDecimal totalCredits = transactions.stream()
                .filter(t -> isCredit(t.getMovementType()))
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        // Debits include DEBIT, TRANSFER_OUT, SWEEP_OUT, POOL_DEBIT
        BigDecimal totalDebits = transactions.stream()
                .filter(t -> isDebit(t.getMovementType()))
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        // Calculate opening balance (current - credits + debits for the period)
        BigDecimal openingBalance = account.getCurrentBalance()
                .subtract(totalCredits)
                .add(totalDebits);
        
        Map<String, Object> statement = new LinkedHashMap<>(); // Preserve order
        statement.put("accountId", account.getId());
        statement.put("vaNumber", account.getVaNumber());
        statement.put("vaName", account.getVaName());
        statement.put("currencyCode", account.getCurrencyCode());
        statement.put("corporateId", account.getCorporateId());
        statement.put("fromDate", fromDate);
        statement.put("toDate", toDate);
        statement.put("openingBalance", openingBalance);
        statement.put("closingBalance", account.getCurrentBalance());
        statement.put("totalCredits", totalCredits);
        statement.put("totalDebits", totalDebits);
        statement.put("netMovement", totalCredits.subtract(totalDebits));
        statement.put("transactionCount", transactions.size());
        statement.put("transactions", transactions);
        statement.put("generatedAt", LocalDateTime.now());
        
        return ResponseEntity.ok(ApiResponse.success(statement));
    }

    @GetMapping("/corporate/{corporateId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCorporateStatement(
            @PathVariable UUID corporateId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        
        // Get all transactions for the corporate
        List<Transaction> transactions = transactionRepository.findByCorporateIdAndDateRange(
                corporateId, 
                fromDate.atStartOfDay(), 
                toDate.plusDays(1).atStartOfDay());
        
        BigDecimal totalCredits = transactions.stream()
                .filter(t -> isCredit(t.getMovementType()))
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal totalDebits = transactions.stream()
                .filter(t -> isDebit(t.getMovementType()))
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        Map<String, Object> statement = new LinkedHashMap<>();
        statement.put("corporateId", corporateId);
        statement.put("fromDate", fromDate);
        statement.put("toDate", toDate);
        statement.put("totalCredits", totalCredits);
        statement.put("totalDebits", totalDebits);
        statement.put("netMovement", totalCredits.subtract(totalDebits));
        statement.put("transactionCount", transactions.size());
        statement.put("transactions", transactions);
        statement.put("generatedAt", LocalDateTime.now());
        
        return ResponseEntity.ok(ApiResponse.success(statement));
    }

    @GetMapping("/generate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generateStatement(
            @RequestParam UUID accountId,
            @RequestParam String format,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        
        VirtualAccount account = virtualAccountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountId));
        
        // Generate unique statement reference
        String statementRef = String.format("STMT-%s-%s-%d",
                account.getVaNumber().substring(0, Math.min(8, account.getVaNumber().length())),
                LocalDate.now().toString().replace("-", ""),
                System.currentTimeMillis() % 10000);
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("statementReference", statementRef);
        response.put("accountId", account.getId());
        response.put("vaNumber", account.getVaNumber());
        response.put("vaName", account.getVaName());
        response.put("format", format.toUpperCase());
        response.put("fromDate", fromDate);
        response.put("toDate", toDate);
        response.put("status", "GENERATED");
        response.put("downloadUrl", "/api/v1/statements/download/" + statementRef);
        response.put("expiresAt", LocalDateTime.now().plusDays(7));
        response.put("generatedAt", LocalDateTime.now());
        
        log.info("Generated {} statement {} for account {}", format, statementRef, account.getVaNumber());
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/download/{statementRef}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> downloadStatement(
            @PathVariable String statementRef) {
        
        // In production, this would fetch from storage and return file
        Map<String, Object> response = new HashMap<>();
        response.put("statementReference", statementRef);
        response.put("status", "DOWNLOAD_READY");
        response.put("message", "Statement download endpoint - implement file generation");
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getStatementHistory(
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) UUID corporateId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        // TODO: Implement actual statement history from database
        // For now, return mock data
        List<Map<String, Object>> history = new ArrayList<>();
        for (int i = 0; i < Math.min(size, 10); i++) {
            Map<String, Object> stmt = new LinkedHashMap<>();
            stmt.put("id", UUID.randomUUID());
            stmt.put("statementReference", String.format("STMT-2024-%05d", i + 1 + (page * size)));
            stmt.put("vaNumber", "VA" + String.format("%010d", 1000000 + i));
            stmt.put("fromDate", LocalDate.now().minusMonths(i + 1));
            stmt.put("toDate", LocalDate.now().minusMonths(i));
            stmt.put("format", i % 2 == 0 ? "PDF" : "CSV");
            stmt.put("status", "AVAILABLE");
            stmt.put("fileSize", (100 + i * 50) + " KB");
            stmt.put("generatedAt", LocalDateTime.now().minusDays(i));
            stmt.put("expiresAt", LocalDateTime.now().plusDays(30 - i));
            history.add(stmt);
        }
        
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    @GetMapping("/types")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getStatementTypes() {
        List<Map<String, Object>> types = Arrays.asList(
            createStatementType("ACCOUNT", "Account Statement", 
                "Detailed transaction history for a single account",
                Arrays.asList("PDF", "CSV", "MT940", "CAMT.053")),
            createStatementType("CORPORATE", "Corporate Statement", 
                "Consolidated statement across all corporate accounts",
                Arrays.asList("PDF", "CSV", "XLSX")),
            createStatementType("BALANCE", "Balance Confirmation", 
                "Point-in-time balance certificate",
                Arrays.asList("PDF")),
            createStatementType("AUDIT", "Audit Report", 
                "Detailed audit trail with timestamps",
                Arrays.asList("PDF", "XLSX")),
            createStatementType("RECONCILIATION", "Reconciliation Report", 
                "Account reconciliation with opening/closing balances",
                Arrays.asList("PDF", "CSV", "XLSX"))
        );
        return ResponseEntity.ok(ApiResponse.success(types));
    }

    // Helper methods
    private boolean isCredit(Transaction.MovementType type) {
        return type == Transaction.MovementType.CREDIT ||
               type == Transaction.MovementType.TRANSFER_IN ||
               type == Transaction.MovementType.SWEEP_IN ||
               type == Transaction.MovementType.POOL_CREDIT;
    }

    private boolean isDebit(Transaction.MovementType type) {
        return type == Transaction.MovementType.DEBIT ||
               type == Transaction.MovementType.TRANSFER_OUT ||
               type == Transaction.MovementType.SWEEP_OUT ||
               type == Transaction.MovementType.POOL_DEBIT;
    }

    private Map<String, Object> createStatementType(String code, String name, String description, List<String> formats) {
        Map<String, Object> type = new LinkedHashMap<>();
        type.put("code", code);
        type.put("name", name);
        type.put("description", description);
        type.put("formats", formats);
        return type;
    }
}