package com.bank.vam.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public class VirtualAccountDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        private String vaName;
        private String vaPrefix;
        private String viban;
        private UUID programId;
        private UUID corporateId;
        private UUID physicalAccountId;
        private String currencyCode;
        private String externalReference;
        private String metadata;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        private String vaName;
        private String externalReference;
        private String metadata;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private UUID id;
        private String vaNumber;
        private String viban;
        private String vaName;
        private UUID programId;
        private UUID corporateId;
        private UUID physicalAccountId;
        private String currencyCode;
        private BigDecimal currentBalance;
        private BigDecimal availableBalance;
        private String status;
        private String externalReference;
        private Boolean kycVerified;
    }
}
