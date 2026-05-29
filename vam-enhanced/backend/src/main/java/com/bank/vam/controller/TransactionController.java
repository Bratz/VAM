package com.bank.vam.controller;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.dto.TransactionDto;
import com.bank.vam.entity.Transaction;
import com.bank.vam.service.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Transaction>> getById(@PathVariable UUID id) {
        Transaction txn = transactionService.getById(id);
        return ResponseEntity.ok(ApiResponse.success(txn));
    }

    @GetMapping("/reference/{referenceNumber}")
    public ResponseEntity<ApiResponse<Transaction>> getByReference(@PathVariable String referenceNumber) {
        Transaction txn = transactionService.getByReference(referenceNumber);
        return ResponseEntity.ok(ApiResponse.success(txn));
    }

    @GetMapping("/va/{vaId}")
    public ResponseEntity<ApiResponse<List<Transaction>>> getByVaId(
            @PathVariable UUID vaId,
            Pageable pageable) {
        Page<Transaction> page = transactionService.getByVaId(vaId, pageable);
        return ResponseEntity.ok(ApiResponse.paged(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements()));
    }

    @GetMapping("/va/{vaId}/recent")
    public ResponseEntity<ApiResponse<List<Transaction>>> getRecentByVaId(
            @PathVariable UUID vaId,
            @RequestParam(defaultValue = "10") int limit) {
        List<Transaction> transactions = transactionService.getRecentByVaId(vaId, limit);
        return ResponseEntity.ok(ApiResponse.success(transactions));
    }

    @PostMapping("/credit")
    public ResponseEntity<ApiResponse<Transaction>> credit(@RequestBody TransactionDto.CreditRequest request) {
        Transaction txn = transactionService.credit(request);
        return ResponseEntity.ok(ApiResponse.success(txn, "Credit processed successfully"));
    }

    @PostMapping("/debit")
    public ResponseEntity<ApiResponse<Transaction>> debit(@RequestBody TransactionDto.DebitRequest request) {
        Transaction txn = transactionService.debit(request);
        return ResponseEntity.ok(ApiResponse.success(txn, "Debit processed successfully"));
    }

    @PostMapping("/transfer")
    public ResponseEntity<ApiResponse<Transaction>> transfer(@RequestBody TransactionDto.TransferRequest request) {
        Transaction txn = transactionService.transfer(request);
        return ResponseEntity.ok(ApiResponse.success(txn, "Transfer processed successfully"));
    }
}
