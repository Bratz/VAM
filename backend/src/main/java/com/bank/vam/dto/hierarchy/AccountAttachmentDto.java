package com.bank.vam.dto.hierarchy;

import com.bank.vam.entity.hierarchy.AccountAttachment.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTOs for Account Attachment operations.
 */
public class AccountAttachmentDto {

    // ========================================================================
    // RESPONSE DTO
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private UUID id;
        private UUID virtualAccountId;
        private UUID legalEntityId;
        
        // Relationship details
        private RelationshipType relationshipType;
        private Boolean isPrimary;
        private String description;
        
        // Validity
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
        private Boolean isCurrentlyValid;
        
        // Authorization limits
        private BigDecimal maxTransactionAmount;
        private BigDecimal dailyLimit;
        private String authorizedTransactionTypes;
        private Boolean requiresDualAuth;
        
        // Collateral details
        private BigDecimal collateralPercent;
        private UUID securedFacilityId;
        
        // Status
        private AttachmentStatus status;
        private String approvedBy;
        private LocalDateTime approvedAt;
        
        // Audit
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        
        // Derived (for display)
        private String vaNumber;
        private String entityName;
    }

    // ========================================================================
    // CREATE REQUEST
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        private UUID virtualAccountId;
        private UUID legalEntityId;
        
        // Relationship
        private RelationshipType relationshipType;
        private Boolean isPrimary;
        private String description;
        
        // Validity
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
        
        // Authorization limits (for AUTHORIZED type)
        private BigDecimal maxTransactionAmount;
        private BigDecimal dailyLimit;
        private String authorizedTransactionTypes;
        private Boolean requiresDualAuth;
        
        // Collateral details (for COLLATERAL type)
        private BigDecimal collateralPercent;
        private UUID securedFacilityId;
        
        // Approval
        private Boolean requiresApproval;
    }

    // ========================================================================
    // UPDATE REQUEST
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        private String description;
        private LocalDate effectiveTo;
        
        // Authorization limits
        private BigDecimal maxTransactionAmount;
        private BigDecimal dailyLimit;
        private String authorizedTransactionTypes;
        private Boolean requiresDualAuth;
        
        // Collateral
        private BigDecimal collateralPercent;
    }

    // ========================================================================
    // TRANSFER OWNERSHIP REQUEST
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransferOwnershipRequest {
        private UUID virtualAccountId;
        private UUID newOwnerId;
        private String transferReason;
    }

    // ========================================================================
    // AUTHORIZATION CHECK REQUEST
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuthorizationCheckRequest {
        private UUID virtualAccountId;
        private UUID legalEntityId;
        private BigDecimal transactionAmount;
        private String transactionType;
    }

    // ========================================================================
    // AUTHORIZATION CHECK RESPONSE
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuthorizationCheckResponse {
        private Boolean authorized;
        private String authorizationType;
        private BigDecimal maxAllowed;
        private BigDecimal dailyRemaining;
        private Boolean requiresDualAuth;
        private String rejectionReason;
    }

    // ========================================================================
    // SUMMARY DTO
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Summary {
        private UUID id;
        private UUID virtualAccountId;
        private String vaNumber;
        private UUID legalEntityId;
        private String entityName;
        private RelationshipType relationshipType;
        private Boolean isPrimary;
        private AttachmentStatus status;
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
    }

    // ========================================================================
    // STATISTICS
    // ========================================================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Statistics {
        private int totalAttachments;
        private int activeAttachments;
        private int pendingApproval;
        private int ownerRelationships;
        private int authorizedRelationships;
        private int collateralRelationships;
        private int expiringIn30Days;
    }
}