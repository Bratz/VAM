package com.bank.vam.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public class TransactionDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreditRequest {
        private UUID vaId;
        private BigDecimal amount;
        private LocalDate valueDate;
        private String description;
        private String channel;
        private String remitterName;
        private String remitterAccount;
        private String externalReference;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DebitRequest {
        private UUID vaId;
        private BigDecimal amount;
        private LocalDate valueDate;
        private String description;
        private String channel;
        private String beneficiaryName;
        private String beneficiaryAccount;
        private String externalReference;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransferRequest {
        private UUID fromVaId;
        private UUID toVaId;
        private BigDecimal amount;
        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private UUID id;
        private String movementType;
        private UUID vaId;
        private BigDecimal amount;
        private String currencyCode;
        private BigDecimal balanceBefore;
        private BigDecimal balanceAfter;
        private LocalDateTime transactionDate;
        private LocalDate valueDate;
        private String referenceNumber;
        private String description;
        private String status;
    }
}
