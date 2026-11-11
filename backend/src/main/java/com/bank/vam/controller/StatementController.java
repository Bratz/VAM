package com.bank.vam.controller;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.exception.BusinessException;
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
        
        throw new BusinessException("Not available: " + format.toUpperCase() + " statement file for "
                + account.getVaNumber() + " — no statement file generator is implemented; use the on-screen statement");
    }

    @GetMapping("/download/{statementRef}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> downloadStatement(
            @PathVariable String statementRef) {
        throw new BusinessException("Not available: statement " + statementRef + " — no statement files are stored");
    }

    /** No statement files are generated or stored, so there is no history to list. */
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getStatementHistory(
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) UUID corporateId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(List.of()));
    }

    /** Only what is actually produced: the on-screen statements here and camt.053 (JSON / XML) from /iso20022. */
    @GetMapping("/types")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getStatementTypes() {
        List<Map<String, Object>> types = Arrays.asList(
            createStatementType("ACCOUNT", "Account Statement",
                "Detailed transaction history for a single account",
                Arrays.asList("JSON", "CAMT.053")),
            createStatementType("CORPORATE", "Corporate Statement",
                "Consolidated statement across all corporate accounts",
                Arrays.asList("JSON"))
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