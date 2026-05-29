package com.bank.vam.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Implementation of BaNCS Client
 * Connects to Core Banking system via REST API
 * Falls back to mock responses when BaNCS is unavailable
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BancsClientImpl implements BancsClient {

    private final RestTemplate restTemplate;

    @Value("${bancs.api.base-url:http://localhost:8081}")
    private String baseUrl;

    @Value("${bancs.api.timeout:5000}")
    private int timeout;

    @Value("${bancs.api.mock-enabled:true}")
    private boolean mockEnabled;

    private static final DateTimeFormatter DATETIME_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    // ========================================================================
    // ACCOUNT OPERATIONS
    // ========================================================================

    @Override
    public BalanceResponse getBalance(String accountNumber) {
        if (mockEnabled || !isAvailable()) {
            return mockBalance(accountNumber);
        }

        try {
            String url = baseUrl + "/api/accounts/" + accountNumber + "/balance";
            ResponseEntity<BalanceResponse> response = restTemplate.getForEntity(url, BalanceResponse.class);
            return response.getBody();
        } catch (RestClientException e) {
            log.error("Failed to get balance from BaNCS: {}", e.getMessage());
            return mockBalance(accountNumber);
        }
    }

    @Override
    public AccountCreationResponse createVirtualAccount(CreateAccountRequest request) {
        if (mockEnabled || !isAvailable()) {
            return mockAccountCreation(request);
        }

        try {
            String url = baseUrl + "/api/accounts/virtual";
            HttpEntity<CreateAccountRequest> entity = new HttpEntity<>(request, createHeaders());
            ResponseEntity<AccountCreationResponse> response = restTemplate.postForEntity(
                    url, entity, AccountCreationResponse.class);
            return response.getBody();
        } catch (RestClientException e) {
            log.error("Failed to create account in BaNCS: {}", e.getMessage());
            throw new RuntimeException("Core Banking unavailable", e);
        }
    }

    @Override
    public void closeAccount(String accountNumber, String reason) {
        if (mockEnabled || !isAvailable()) {
            log.info("Mock: Closing account {} with reason: {}", accountNumber, reason);
            return;
        }

        try {
            String url = baseUrl + "/api/accounts/" + accountNumber + "/close";
            Map<String, String> body = Map.of("reason", reason);
            restTemplate.postForEntity(url, new HttpEntity<>(body, createHeaders()), Void.class);
        } catch (RestClientException e) {
            log.error("Failed to close account in BaNCS: {}", e.getMessage());
            throw new RuntimeException("Core Banking unavailable", e);
        }
    }

    @Override
    public void updateAccountStatus(String accountNumber, String status) {
        if (mockEnabled || !isAvailable()) {
            log.info("Mock: Updating account {} status to {}", accountNumber, status);
            return;
        }

        try {
            String url = baseUrl + "/api/accounts/" + accountNumber + "/status";
            Map<String, String> body = Map.of("status", status);
            restTemplate.put(url, new HttpEntity<>(body, createHeaders()));
        } catch (RestClientException e) {
            log.error("Failed to update account status in BaNCS: {}", e.getMessage());
        }
    }

    // ========================================================================
    // TRANSACTION OPERATIONS
    // ========================================================================

    @Override
    public TransactionResponse postTransaction(TransactionRequest request) {
        if (mockEnabled || !isAvailable()) {
            return mockTransaction(request);
        }

        try {
            String url = baseUrl + "/api/transactions";
            HttpEntity<TransactionRequest> entity = new HttpEntity<>(request, createHeaders());
            ResponseEntity<TransactionResponse> response = restTemplate.postForEntity(
                    url, entity, TransactionResponse.class);
            return response.getBody();
        } catch (RestClientException e) {
            log.error("Failed to post transaction to BaNCS: {}", e.getMessage());
            throw new RuntimeException("Core Banking unavailable", e);
        }
    }

    @Override
    public TransferResponse executeTransfer(TransferRequest request) {
        if (mockEnabled || !isAvailable()) {
            return mockTransfer(request);
        }

        try {
            String url = baseUrl + "/api/transfers";
            HttpEntity<TransferRequest> entity = new HttpEntity<>(request, createHeaders());
            ResponseEntity<TransferResponse> response = restTemplate.postForEntity(
                    url, entity, TransferResponse.class);
            return response.getBody();
        } catch (RestClientException e) {
            log.error("Failed to execute transfer in BaNCS: {}", e.getMessage());
            throw new RuntimeException("Core Banking unavailable", e);
        }
    }

    @Override
    public List<TransactionHistoryItem> getTransactionHistory(String accountNumber, int days) {
        if (mockEnabled || !isAvailable()) {
            return mockTransactionHistory(accountNumber);
        }

        try {
            String url = baseUrl + "/api/accounts/" + accountNumber + "/transactions?days=" + days;
            ResponseEntity<TransactionHistoryItem[]> response = restTemplate.getForEntity(
                    url, TransactionHistoryItem[].class);
            return Arrays.asList(response.getBody());
        } catch (RestClientException e) {
            log.error("Failed to get transaction history from BaNCS: {}", e.getMessage());
            return mockTransactionHistory(accountNumber);
        }
    }

    // ========================================================================
    // CUSTOMER OPERATIONS
    // ========================================================================

    @Override
    public CustomerResponse createCustomer(CustomerRequest request) {
        if (mockEnabled || !isAvailable()) {
            return mockCustomer(request);
        }

        try {
            String url = baseUrl + "/api/customers";
            HttpEntity<CustomerRequest> entity = new HttpEntity<>(request, createHeaders());
            ResponseEntity<CustomerResponse> response = restTemplate.postForEntity(
                    url, entity, CustomerResponse.class);
            return response.getBody();
        } catch (RestClientException e) {
            log.error("Failed to create customer in BaNCS: {}", e.getMessage());
            throw new RuntimeException("Core Banking unavailable", e);
        }
    }

    @Override
    public CustomerResponse getCustomer(String cifId) {
        if (mockEnabled || !isAvailable()) {
            return new CustomerResponse(cifId, "Mock Customer", "ACTIVE", LocalDateTime.now().format(DATETIME_FORMAT));
        }

        try {
            String url = baseUrl + "/api/customers/" + cifId;
            ResponseEntity<CustomerResponse> response = restTemplate.getForEntity(url, CustomerResponse.class);
            return response.getBody();
        } catch (RestClientException e) {
            log.error("Failed to get customer from BaNCS: {}", e.getMessage());
            return null;
        }
    }

    // ========================================================================
    // STATEMENT OPERATIONS
    // ========================================================================

    @Override
    public StatementResponse generateStatement(StatementRequest request) {
        if (mockEnabled || !isAvailable()) {
            return mockStatement(request);
        }

        try {
            String url = baseUrl + "/api/statements/generate";
            HttpEntity<StatementRequest> entity = new HttpEntity<>(request, createHeaders());
            ResponseEntity<StatementResponse> response = restTemplate.postForEntity(
                    url, entity, StatementResponse.class);
            return response.getBody();
        } catch (RestClientException e) {
            log.error("Failed to generate statement from BaNCS: {}", e.getMessage());
            return mockStatement(request);
        }
    }

    // ========================================================================
    // HEALTH CHECK
    // ========================================================================

    @Override
    public boolean isAvailable() {
        try {
            String url = baseUrl + "/health";
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            return response.getStatusCode() == HttpStatus.OK;
        } catch (RestClientException e) {
            log.warn("BaNCS health check failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public HealthStatus getHealthStatus() {
        long startTime = System.currentTimeMillis();
        boolean healthy = isAvailable();
        long responseTime = System.currentTimeMillis() - startTime;

        return new HealthStatus(
                healthy,
                healthy ? "UP" : "DOWN",
                responseTime,
                LocalDateTime.now().format(DATETIME_FORMAT),
                "BaNCS v24.1"
        );
    }

    // ========================================================================
    // MOCK RESPONSES
    // ========================================================================

    private BalanceResponse mockBalance(String accountNumber) {
        BigDecimal balance = BigDecimal.valueOf(Math.random() * 10000000).setScale(2, java.math.RoundingMode.HALF_UP);
        return new BalanceResponse(
                accountNumber,
                balance,
                balance.multiply(BigDecimal.valueOf(0.95)),
                balance.multiply(BigDecimal.valueOf(0.05)),
                "AED",
                LocalDateTime.now().format(DATETIME_FORMAT)
        );
    }

    private AccountCreationResponse mockAccountCreation(CreateAccountRequest request) {
        String accountNumber = "VA" + System.currentTimeMillis();
        String iban = "AE" + String.format("%021d", System.currentTimeMillis() % 1000000000000000000L);
        return new AccountCreationResponse(accountNumber, iban, "ACTIVE", LocalDateTime.now().format(DATETIME_FORMAT));
    }

    private TransactionResponse mockTransaction(TransactionRequest request) {
        return new TransactionResponse(
                "TXN" + System.currentTimeMillis(),
                request.reference(),
                "SUCCESS",
                BigDecimal.valueOf(Math.random() * 1000000),
                LocalDateTime.now().format(DATETIME_FORMAT)
        );
    }

    private TransferResponse mockTransfer(TransferRequest request) {
        return new TransferResponse(
                "TRF" + System.currentTimeMillis(),
                "DR" + System.currentTimeMillis(),
                "CR" + System.currentTimeMillis(),
                "SUCCESS",
                LocalDateTime.now().format(DATETIME_FORMAT)
        );
    }

    private List<TransactionHistoryItem> mockTransactionHistory(String accountNumber) {
        List<TransactionHistoryItem> history = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            history.add(new TransactionHistoryItem(
                    "TXN" + i,
                    "REF" + i,
                    i % 2 == 0 ? "CREDIT" : "DEBIT",
                    BigDecimal.valueOf(Math.random() * 10000),
                    BigDecimal.valueOf(Math.random() * 100000),
                    "Mock transaction " + i,
                    LocalDateTime.now().minusDays(i).format(DateTimeFormatter.ISO_LOCAL_DATE),
                    LocalDateTime.now().minusDays(i).format(DATETIME_FORMAT)
            ));
        }
        return history;
    }

    private CustomerResponse mockCustomer(CustomerRequest request) {
        return new CustomerResponse(
                "CIF" + System.currentTimeMillis(),
                request.customerName(),
                "ACTIVE",
                LocalDateTime.now().format(DATETIME_FORMAT)
        );
    }

    private StatementResponse mockStatement(StatementRequest request) {
        return new StatementResponse(
                "STM" + System.currentTimeMillis(),
                request.accountNumber(),
                request.fromDate() + " to " + request.toDate(),
                (int) (Math.random() * 100),
                "/statements/mock/" + System.currentTimeMillis() + ".pdf"
        );
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-API-Key", "vam-service-key");
        headers.set("X-Correlation-Id", UUID.randomUUID().toString());
        return headers;
    }
}
