package com.bank.vam.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Core Banking (BaNCS) Integration Client
 * Provides interface for all Core Banking operations
 */
public interface BancsClient {

    // ========================================================================
    // ACCOUNT OPERATIONS
    // ========================================================================

    /**
     * Get real-time balance for an account
     */
    BalanceResponse getBalance(String accountNumber);

    /**
     * Create a virtual account in Core Banking
     */
    AccountCreationResponse createVirtualAccount(CreateAccountRequest request);

    /**
     * Close an account
     */
    void closeAccount(String accountNumber, String reason);

    /**
     * Freeze/Unfreeze account
     */
    void updateAccountStatus(String accountNumber, String status);

    // ========================================================================
    // TRANSACTION OPERATIONS
    // ========================================================================

    /**
     * Post a transaction (credit/debit)
     */
    TransactionResponse postTransaction(TransactionRequest request);

    /**
     * Execute a transfer between accounts
     */
    TransferResponse executeTransfer(TransferRequest request);

    /**
     * Get transaction history
     */
    List<TransactionHistoryItem> getTransactionHistory(String accountNumber, int days);

    // ========================================================================
    // CUSTOMER OPERATIONS
    // ========================================================================

    /**
     * Create or update customer in CIF
     */
    CustomerResponse createCustomer(CustomerRequest request);

    /**
     * Get customer details by CIF ID
     */
    CustomerResponse getCustomer(String cifId);

    // ========================================================================
    // STATEMENT OPERATIONS
    // ========================================================================

    /**
     * Generate account statement
     */
    StatementResponse generateStatement(StatementRequest request);

    // ========================================================================
    // HEALTH CHECK
    // ========================================================================

    /**
     * Check if Core Banking is available
     */
    boolean isAvailable();

    /**
     * Get Core Banking health status
     */
    HealthStatus getHealthStatus();

    // ========================================================================
    // REQUEST/RESPONSE DTOs
    // ========================================================================

    record BalanceResponse(
            String accountNumber,
            BigDecimal currentBalance,
            BigDecimal availableBalance,
            BigDecimal holdAmount,
            String currency,
            String lastUpdated
    ) {}

    record CreateAccountRequest(
            String customerCif,
            String accountName,
            String currency,
            String accountType,
            String schemeCode,
            String branchCode
    ) {}

    record AccountCreationResponse(
            String accountNumber,
            String iban,
            String status,
            String createdAt
    ) {}

    record TransactionRequest(
            String accountNumber,
            BigDecimal amount,
            String currency,
            String transactionType,
            String description,
            String reference,
            String valueDate
    ) {}

    record TransactionResponse(
            String transactionId,
            String reference,
            String status,
            BigDecimal balanceAfter,
            String processedAt
    ) {}

    record TransferRequest(
            String debitAccount,
            String creditAccount,
            BigDecimal amount,
            String currency,
            String description,
            String reference,
            String valueDate
    ) {}

    record TransferResponse(
            String transferId,
            String debitReference,
            String creditReference,
            String status,
            String processedAt
    ) {}

    record TransactionHistoryItem(
            String transactionId,
            String reference,
            String type,
            BigDecimal amount,
            BigDecimal balanceAfter,
            String description,
            String valueDate,
            String processedAt
    ) {}

    record CustomerRequest(
            String customerName,
            String customerType,
            String idType,
            String idNumber,
            String email,
            String phone,
            String address
    ) {}

    record CustomerResponse(
            String cifId,
            String customerName,
            String status,
            String createdAt
    ) {}

    record StatementRequest(
            String accountNumber,
            String fromDate,
            String toDate,
            String format
    ) {}

    record StatementResponse(
            String statementId,
            String accountNumber,
            String period,
            int transactionCount,
            String downloadUrl
    ) {}

    record HealthStatus(
            boolean healthy,
            String status,
            long responseTimeMs,
            String lastChecked,
            String version
    ) {}
}
