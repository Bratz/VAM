package com.bank.vam.controller.hierarchy;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.dto.hierarchy.AccountAttachmentDto;
import com.bank.vam.entity.hierarchy.AccountAttachment;
import com.bank.vam.entity.hierarchy.AccountAttachment.*;
import com.bank.vam.service.hierarchy.AccountAttachmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * AccountAttachmentController - REST API for VA-Entity relationships.
 * 
 * Endpoints:
 * - GET  /api/v1/account-attachments                     - List all (paginated)
 * - GET  /api/v1/account-attachments/{id}                - Get by ID
 * - GET  /api/v1/account-attachments/va/{vaId}           - Get by virtual account
 * - GET  /api/v1/account-attachments/entity/{entityId}   - Get by legal entity
 * - GET  /api/v1/account-attachments/va/{vaId}/owner     - Get primary owner
 * - GET  /api/v1/account-attachments/va/{vaId}/authorized- Get authorized entities
 * - POST /api/v1/account-attachments                     - Create
 * - POST /api/v1/account-attachments/owner               - Create owner (quick)
 * - POST /api/v1/account-attachments/authorized          - Create authorized (quick)
 * - POST /api/v1/account-attachments/transfer-ownership  - Transfer ownership
 * - PUT  /api/v1/account-attachments/{id}                - Update
 * - PUT  /api/v1/account-attachments/{id}/approve        - Approve
 * - PUT  /api/v1/account-attachments/{id}/suspend        - Suspend
 * - PUT  /api/v1/account-attachments/{id}/terminate      - Terminate
 * - DELETE /api/v1/account-attachments/{id}              - Delete
 * - POST /api/v1/account-attachments/check-authorization - Check authorization
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/account-attachments")
@RequiredArgsConstructor
@Tag(name = "Account Attachments", description = "VA to Legal Entity relationship management")
public class AccountAttachmentController {

    private final AccountAttachmentService service;

    // ========================================================================
    // READ ENDPOINTS
    // ========================================================================

    @GetMapping
    @Operation(summary = "List all account attachments (paginated)")
    public ResponseEntity<ApiResponse<Page<AccountAttachmentDto.Response>>> getAll(Pageable pageable) {
        Page<AccountAttachment> attachments = service.findAll(pageable);
        return ResponseEntity.ok(ApiResponse.success(attachments.map(this::toResponse)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get account attachment by ID")
    public ResponseEntity<ApiResponse<AccountAttachmentDto.Response>> getById(@PathVariable UUID id) {
        AccountAttachment attachment = service.getById(id);
        return ResponseEntity.ok(ApiResponse.success(toResponse(attachment)));
    }

    @GetMapping("/va/{vaId}")
    @Operation(summary = "Get attachments by virtual account")
    public ResponseEntity<ApiResponse<List<AccountAttachmentDto.Response>>> getByVirtualAccount(
            @PathVariable UUID vaId) {
        List<AccountAttachment> attachments = service.getByVirtualAccount(vaId);
        return ResponseEntity.ok(ApiResponse.success(
            attachments.stream().map(this::toResponse).collect(Collectors.toList())
        ));
    }

    @GetMapping("/va/{vaId}/active")
    @Operation(summary = "Get active attachments by virtual account")
    public ResponseEntity<ApiResponse<List<AccountAttachmentDto.Response>>> getActiveByVirtualAccount(
            @PathVariable UUID vaId) {
        List<AccountAttachment> attachments = service.getActiveByVirtualAccount(vaId);
        return ResponseEntity.ok(ApiResponse.success(
            attachments.stream().map(this::toResponse).collect(Collectors.toList())
        ));
    }

    @GetMapping("/entity/{entityId}")
    @Operation(summary = "Get attachments by legal entity")
    public ResponseEntity<ApiResponse<List<AccountAttachmentDto.Response>>> getByLegalEntity(
            @PathVariable UUID entityId) {
        List<AccountAttachment> attachments = service.getByLegalEntity(entityId);
        return ResponseEntity.ok(ApiResponse.success(
            attachments.stream().map(this::toResponse).collect(Collectors.toList())
        ));
    }

    @GetMapping("/entity/{entityId}/active")
    @Operation(summary = "Get active attachments by legal entity")
    public ResponseEntity<ApiResponse<List<AccountAttachmentDto.Response>>> getActiveByLegalEntity(
            @PathVariable UUID entityId) {
        List<AccountAttachment> attachments = service.getActiveByLegalEntity(entityId);
        return ResponseEntity.ok(ApiResponse.success(
            attachments.stream().map(this::toResponse).collect(Collectors.toList())
        ));
    }

    @GetMapping("/va/{vaId}/owner")
    @Operation(summary = "Get primary owner of virtual account")
    public ResponseEntity<ApiResponse<AccountAttachmentDto.Response>> getPrimaryOwner(
            @PathVariable UUID vaId) {
        AccountAttachment owner = service.getPrimaryOwner(vaId);
        return ResponseEntity.ok(ApiResponse.success(toResponse(owner)));
    }

    @GetMapping("/va/{vaId}/authorized")
    @Operation(summary = "Get authorized entities for virtual account")
    public ResponseEntity<ApiResponse<List<AccountAttachmentDto.Response>>> getAuthorizedEntities(
            @PathVariable UUID vaId) {
        List<AccountAttachment> attachments = service.getAuthorizedEntities(vaId);
        return ResponseEntity.ok(ApiResponse.success(
            attachments.stream().map(this::toResponse).collect(Collectors.toList())
        ));
    }

    @GetMapping("/va/{vaId}/type/{type}")
    @Operation(summary = "Get attachments by virtual account and relationship type")
    public ResponseEntity<ApiResponse<List<AccountAttachmentDto.Response>>> getByType(
            @PathVariable UUID vaId,
            @PathVariable RelationshipType type) {
        List<AccountAttachment> attachments = service.getByRelationshipType(vaId, type);
        return ResponseEntity.ok(ApiResponse.success(
            attachments.stream().map(this::toResponse).collect(Collectors.toList())
        ));
    }

    @GetMapping("/facility/{facilityId}/collaterals")
    @Operation(summary = "Get collateral attachments for a facility")
    public ResponseEntity<ApiResponse<List<AccountAttachmentDto.Response>>> getCollateralsForFacility(
            @PathVariable UUID facilityId) {
        List<AccountAttachment> attachments = service.getCollateralsForFacility(facilityId);
        return ResponseEntity.ok(ApiResponse.success(
            attachments.stream().map(this::toResponse).collect(Collectors.toList())
        ));
    }

    @GetMapping("/expiring/{days}")
    @Operation(summary = "Get attachments expiring within days")
    public ResponseEntity<ApiResponse<List<AccountAttachmentDto.Response>>> getExpiringSoon(
            @PathVariable int days) {
        List<AccountAttachment> attachments = service.getExpiringSoon(days);
        return ResponseEntity.ok(ApiResponse.success(
            attachments.stream().map(this::toResponse).collect(Collectors.toList())
        ));
    }

    @GetMapping("/va/{vaId}/statistics")
    @Operation(summary = "Get attachment statistics for virtual account")
    public ResponseEntity<ApiResponse<AccountAttachmentDto.Statistics>> getStatistics(
            @PathVariable UUID vaId) {
        AccountAttachmentDto.Statistics stats = service.getStatistics(vaId);
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    // ========================================================================
    // CREATE ENDPOINTS
    // ========================================================================

    @PostMapping
    @Operation(summary = "Create account attachment")
    public ResponseEntity<ApiResponse<AccountAttachmentDto.Response>> create(
            @RequestBody AccountAttachmentDto.CreateRequest request) {
        log.info("Creating account attachment: VA={}, Entity={}", 
            request.getVirtualAccountId(), request.getLegalEntityId());
        AccountAttachment attachment = service.create(request);
        return ResponseEntity.ok(ApiResponse.success(toResponse(attachment)));
    }

    @PostMapping("/owner")
    @Operation(summary = "Create owner attachment (quick)")
    public ResponseEntity<ApiResponse<AccountAttachmentDto.Response>> createOwner(
            @RequestParam UUID virtualAccountId,
            @RequestParam UUID legalEntityId) {
        AccountAttachment attachment = service.createOwner(virtualAccountId, legalEntityId);
        return ResponseEntity.ok(ApiResponse.success(toResponse(attachment)));
    }

    @PostMapping("/beneficiary")
    @Operation(summary = "Create beneficiary attachment (quick)")
    public ResponseEntity<ApiResponse<AccountAttachmentDto.Response>> createBeneficiary(
            @RequestParam UUID virtualAccountId,
            @RequestParam UUID legalEntityId) {
        AccountAttachment attachment = service.createBeneficiary(virtualAccountId, legalEntityId);
        return ResponseEntity.ok(ApiResponse.success(toResponse(attachment)));
    }

    @PostMapping("/authorized")
    @Operation(summary = "Create authorized attachment with limits")
    public ResponseEntity<ApiResponse<AccountAttachmentDto.Response>> createAuthorized(
            @RequestParam UUID virtualAccountId,
            @RequestParam UUID legalEntityId,
            @RequestParam(required = false) BigDecimal maxTransactionAmount,
            @RequestParam(required = false) BigDecimal dailyLimit) {
        AccountAttachment attachment = service.createAuthorized(
            virtualAccountId, legalEntityId, maxTransactionAmount, dailyLimit);
        return ResponseEntity.ok(ApiResponse.success(toResponse(attachment)));
    }

    @PostMapping("/collateral")
    @Operation(summary = "Create collateral attachment")
    public ResponseEntity<ApiResponse<AccountAttachmentDto.Response>> createCollateral(
            @RequestParam UUID virtualAccountId,
            @RequestParam UUID legalEntityId,
            @RequestParam UUID securedFacilityId,
            @RequestParam BigDecimal collateralPercent) {
        AccountAttachment attachment = service.createCollateral(
            virtualAccountId, legalEntityId, securedFacilityId, collateralPercent);
        return ResponseEntity.ok(ApiResponse.success(toResponse(attachment)));
    }

    // ========================================================================
    // UPDATE ENDPOINTS
    // ========================================================================

    @PutMapping("/{id}")
    @Operation(summary = "Update account attachment")
    public ResponseEntity<ApiResponse<AccountAttachmentDto.Response>> update(
            @PathVariable UUID id,
            @RequestBody AccountAttachmentDto.UpdateRequest request) {
        AccountAttachment attachment = service.update(id, request);
        return ResponseEntity.ok(ApiResponse.success(toResponse(attachment)));
    }

    @PutMapping("/{id}/limits")
    @Operation(summary = "Update authorization limits")
    public ResponseEntity<ApiResponse<AccountAttachmentDto.Response>> updateLimits(
            @PathVariable UUID id,
            @RequestParam BigDecimal maxTransactionAmount,
            @RequestParam BigDecimal dailyLimit) {
        AccountAttachment attachment = service.updateLimits(id, maxTransactionAmount, dailyLimit);
        return ResponseEntity.ok(ApiResponse.success(toResponse(attachment)));
    }

    // ========================================================================
    // STATUS ENDPOINTS
    // ========================================================================

    @PutMapping("/{id}/approve")
    @Operation(summary = "Approve account attachment")
    public ResponseEntity<ApiResponse<AccountAttachmentDto.Response>> approve(
            @PathVariable UUID id,
            @RequestParam String approver) {
        AccountAttachment attachment = service.approve(id, approver);
        return ResponseEntity.ok(ApiResponse.success(toResponse(attachment)));
    }

    @PutMapping("/{id}/suspend")
    @Operation(summary = "Suspend account attachment")
    public ResponseEntity<ApiResponse<AccountAttachmentDto.Response>> suspend(@PathVariable UUID id) {
        AccountAttachment attachment = service.suspend(id);
        return ResponseEntity.ok(ApiResponse.success(toResponse(attachment)));
    }

    @PutMapping("/{id}/reactivate")
    @Operation(summary = "Reactivate suspended attachment")
    public ResponseEntity<ApiResponse<AccountAttachmentDto.Response>> reactivate(@PathVariable UUID id) {
        AccountAttachment attachment = service.reactivate(id);
        return ResponseEntity.ok(ApiResponse.success(toResponse(attachment)));
    }

    @PutMapping("/{id}/terminate")
    @Operation(summary = "Terminate account attachment")
    public ResponseEntity<ApiResponse<AccountAttachmentDto.Response>> terminate(@PathVariable UUID id) {
        AccountAttachment attachment = service.terminate(id);
        return ResponseEntity.ok(ApiResponse.success(toResponse(attachment)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete account attachment")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ========================================================================
    // OWNERSHIP TRANSFER
    // ========================================================================

    @PostMapping("/transfer-ownership")
    @Operation(summary = "Transfer ownership to new entity")
    public ResponseEntity<ApiResponse<AccountAttachmentDto.Response>> transferOwnership(
            @RequestBody AccountAttachmentDto.TransferOwnershipRequest request) {
        AccountAttachment newOwner = service.transferOwnership(
            request.getVirtualAccountId(), 
            request.getNewOwnerId(), 
            request.getTransferReason()
        );
        return ResponseEntity.ok(ApiResponse.success(toResponse(newOwner)));
    }

    // ========================================================================
    // AUTHORIZATION CHECK
    // ========================================================================

    @PostMapping("/check-authorization")
    @Operation(summary = "Check if entity is authorized for transaction")
    public ResponseEntity<ApiResponse<AccountAttachmentDto.AuthorizationCheckResponse>> checkAuthorization(
            @RequestBody AccountAttachmentDto.AuthorizationCheckRequest request) {
        boolean authorized = service.canTransact(
            request.getVirtualAccountId(),
            request.getLegalEntityId(),
            request.getTransactionAmount(),
            request.getTransactionType()
        );
        
        AccountAttachmentDto.AuthorizationCheckResponse response = AccountAttachmentDto.AuthorizationCheckResponse.builder()
            .authorized(authorized)
            .authorizationType(authorized ? "AUTHORIZED" : "DENIED")
            .rejectionReason(authorized ? null : "Entity not authorized for this transaction")
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/check")
    @Operation(summary = "Quick authorization check")
    public ResponseEntity<ApiResponse<Boolean>> isAuthorized(
            @RequestParam UUID virtualAccountId,
            @RequestParam UUID legalEntityId) {
        boolean authorized = service.isEntityAuthorized(virtualAccountId, legalEntityId);
        return ResponseEntity.ok(ApiResponse.success(authorized));
    }

    // ========================================================================
    // MAPPING
    // ========================================================================

    private AccountAttachmentDto.Response toResponse(AccountAttachment attachment) {
        return AccountAttachmentDto.Response.builder()
            .id(attachment.getId())
            .virtualAccountId(attachment.getVirtualAccountId())
            .legalEntityId(attachment.getLegalEntityId())
            .relationshipType(attachment.getRelationshipType())
            .isPrimary(attachment.getIsPrimary())
            .description(attachment.getDescription())
            .effectiveFrom(attachment.getEffectiveFrom())
            .effectiveTo(attachment.getEffectiveTo())
            .isCurrentlyValid(attachment.isCurrentlyValid())
            .maxTransactionAmount(attachment.getMaxTransactionAmount())
            .dailyLimit(attachment.getDailyLimit())
            .authorizedTransactionTypes(attachment.getAuthorizedTransactionTypes())
            .requiresDualAuth(attachment.getRequiresDualAuth())
            .collateralPercent(attachment.getCollateralPercent())
            .securedFacilityId(attachment.getSecuredFacilityId())
            .status(attachment.getStatus())
            .approvedBy(attachment.getApprovedBy())
            .approvedAt(attachment.getApprovedAt())
            .createdAt(attachment.getCreatedAt())
            .updatedAt(attachment.getUpdatedAt())
            .build();
    }
}