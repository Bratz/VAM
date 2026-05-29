package com.bank.vam.service.hierarchy;

import com.bank.vam.dto.hierarchy.AccountAttachmentDto;
import com.bank.vam.entity.hierarchy.AccountAttachment;
import com.bank.vam.entity.hierarchy.AccountAttachment.*;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.hierarchy.AccountAttachmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * AccountAttachmentService - Manages VA to Legal Entity relationships.
 * 
 * UNIFIED ARCHITECTURE v4.2:
 * This service manages the many-to-many relationship between VirtualAccounts
 * and LegalEntities, enabling:
 * - One VA to be owned by one entity (OWNER relationship)
 * - One VA to be used by multiple entities (BENEFICIARY, AUTHORIZED)
 * - Tracking of relationship validity periods
 * - Authorization limit management
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountAttachmentService {

    private final AccountAttachmentRepository repository;

    // ========================================================================
    // READ OPERATIONS
    // ========================================================================

    @Transactional(readOnly = true)
    public AccountAttachment getById(UUID id) {
        return repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Account attachment not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<AccountAttachment> getByVirtualAccount(UUID virtualAccountId) {
        return repository.findByVirtualAccountId(virtualAccountId);
    }

    @Transactional(readOnly = true)
    public List<AccountAttachment> getActiveByVirtualAccount(UUID virtualAccountId) {
        return repository.findByVirtualAccountIdAndStatus(virtualAccountId, AttachmentStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public List<AccountAttachment> getByLegalEntity(UUID legalEntityId) {
        return repository.findByLegalEntityId(legalEntityId);
    }

    @Transactional(readOnly = true)
    public List<AccountAttachment> getActiveByLegalEntity(UUID legalEntityId) {
        return repository.findByLegalEntityIdAndStatus(legalEntityId, AttachmentStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public Page<AccountAttachment> findAll(Pageable pageable) {
        return repository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public List<AccountAttachment> getByRelationshipType(UUID virtualAccountId, RelationshipType type) {
        return repository.findByVirtualAccountIdAndRelationshipType(virtualAccountId, type);
    }

    /**
     * Get the primary owner of a virtual account.
     */
    @Transactional(readOnly = true)
    public AccountAttachment getPrimaryOwner(UUID virtualAccountId) {
        return repository.findByVirtualAccountIdAndRelationshipTypeAndIsPrimary(
            virtualAccountId, RelationshipType.OWNER, true)
            .orElseThrow(() -> new ResourceNotFoundException("No primary owner found for VA: " + virtualAccountId));
    }

    /**
     * Get all authorized entities for a virtual account.
     */
    @Transactional(readOnly = true)
    public List<AccountAttachment> getAuthorizedEntities(UUID virtualAccountId) {
        return repository.findActiveByVaAndType(virtualAccountId, RelationshipType.AUTHORIZED);
    }

    /**
     * Check if entity is authorized on account.
     */
    @Transactional(readOnly = true)
    public boolean isEntityAuthorized(UUID virtualAccountId, UUID legalEntityId) {
        return repository.existsActiveAttachment(virtualAccountId, legalEntityId, AttachmentStatus.ACTIVE);
    }

    /**
     * Get collateral attachments for a facility.
     */
    @Transactional(readOnly = true)
    public List<AccountAttachment> getCollateralsForFacility(UUID facilityId) {
        return repository.findBySecuredFacilityIdAndRelationshipType(facilityId, RelationshipType.COLLATERAL);
    }

    /**
     * Get attachments expiring soon.
     */
    @Transactional(readOnly = true)
    public List<AccountAttachment> getExpiringSoon(int days) {
        LocalDate threshold = LocalDate.now().plusDays(days);
        return repository.findExpiringBefore(threshold, AttachmentStatus.ACTIVE);
    }

    // ========================================================================
    // CREATE OPERATIONS
    // ========================================================================

    @Transactional
    public AccountAttachment create(AccountAttachmentDto.CreateRequest request) {
        log.info("Creating account attachment: VA={}, Entity={}, Type={}", 
            request.getVirtualAccountId(), request.getLegalEntityId(), request.getRelationshipType());

        // Validate no duplicate active attachment of same type
        if (repository.existsByVaEntityAndType(request.getVirtualAccountId(), 
                request.getLegalEntityId(), request.getRelationshipType())) {
            throw new BusinessException("Attachment already exists for this VA, Entity, and relationship type");
        }

        // If creating OWNER, ensure no other primary owner exists
        if (request.getRelationshipType() == RelationshipType.OWNER && 
            Boolean.TRUE.equals(request.getIsPrimary())) {
            repository.findByVirtualAccountIdAndRelationshipTypeAndIsPrimary(
                request.getVirtualAccountId(), RelationshipType.OWNER, true)
                .ifPresent(existing -> {
                    throw new BusinessException("Primary owner already exists. Remove existing before adding new.");
                });
        }

        AccountAttachment attachment = AccountAttachment.builder()
            .virtualAccountId(request.getVirtualAccountId())
            .legalEntityId(request.getLegalEntityId())
            .relationshipType(request.getRelationshipType())
            .isPrimary(request.getIsPrimary() != null ? request.getIsPrimary() : false)
            .description(request.getDescription())
            .effectiveFrom(request.getEffectiveFrom() != null ? request.getEffectiveFrom() : LocalDate.now())
            .effectiveTo(request.getEffectiveTo())
            .maxTransactionAmount(request.getMaxTransactionAmount())
            .dailyLimit(request.getDailyLimit())
            .authorizedTransactionTypes(request.getAuthorizedTransactionTypes())
            .requiresDualAuth(request.getRequiresDualAuth() != null ? request.getRequiresDualAuth() : false)
            .collateralPercent(request.getCollateralPercent())
            .securedFacilityId(request.getSecuredFacilityId())
            .status(request.getRequiresApproval() != null && request.getRequiresApproval() ? 
                AttachmentStatus.PENDING_APPROVAL : AttachmentStatus.ACTIVE)
            .build();

        attachment = repository.save(attachment);
        log.info("Created account attachment: id={}", attachment.getId());
        return attachment;
    }

    @Transactional
    public AccountAttachment createOwner(UUID virtualAccountId, UUID legalEntityId) {
        AccountAttachment attachment = AccountAttachment.createOwner(virtualAccountId, legalEntityId);
        return repository.save(attachment);
    }

    @Transactional
    public AccountAttachment createBeneficiary(UUID virtualAccountId, UUID legalEntityId) {
        AccountAttachment attachment = AccountAttachment.createBeneficiary(virtualAccountId, legalEntityId);
        return repository.save(attachment);
    }

    @Transactional
    public AccountAttachment createAuthorized(UUID virtualAccountId, UUID legalEntityId,
                                               BigDecimal maxTransaction, BigDecimal dailyLimit) {
        AccountAttachment attachment = AccountAttachment.createAuthorized(
            virtualAccountId, legalEntityId, maxTransaction, dailyLimit);
        return repository.save(attachment);
    }

    @Transactional
    public AccountAttachment createCollateral(UUID virtualAccountId, UUID legalEntityId,
                                               UUID securedFacilityId, BigDecimal collateralPercent) {
        AccountAttachment attachment = AccountAttachment.createCollateral(
            virtualAccountId, legalEntityId, securedFacilityId, collateralPercent);
        return repository.save(attachment);
    }

    // ========================================================================
    // UPDATE OPERATIONS
    // ========================================================================

    @Transactional
    public AccountAttachment update(UUID id, AccountAttachmentDto.UpdateRequest request) {
        AccountAttachment attachment = getById(id);
        log.info("Updating account attachment: id={}", id);

        if (request.getDescription() != null) {
            attachment.setDescription(request.getDescription());
        }
        if (request.getEffectiveTo() != null) {
            attachment.setEffectiveTo(request.getEffectiveTo());
        }
        if (request.getMaxTransactionAmount() != null) {
            attachment.setMaxTransactionAmount(request.getMaxTransactionAmount());
        }
        if (request.getDailyLimit() != null) {
            attachment.setDailyLimit(request.getDailyLimit());
        }
        if (request.getAuthorizedTransactionTypes() != null) {
            attachment.setAuthorizedTransactionTypes(request.getAuthorizedTransactionTypes());
        }
        if (request.getRequiresDualAuth() != null) {
            attachment.setRequiresDualAuth(request.getRequiresDualAuth());
        }
        if (request.getCollateralPercent() != null) {
            attachment.setCollateralPercent(request.getCollateralPercent());
        }

        return repository.save(attachment);
    }

    @Transactional
    public AccountAttachment updateLimits(UUID id, BigDecimal maxTransaction, BigDecimal dailyLimit) {
        AccountAttachment attachment = getById(id);
        attachment.setMaxTransactionAmount(maxTransaction);
        attachment.setDailyLimit(dailyLimit);
        return repository.save(attachment);
    }

    // ========================================================================
    // STATUS OPERATIONS
    // ========================================================================

    @Transactional
    public AccountAttachment approve(UUID id, String approver) {
        AccountAttachment attachment = getById(id);
        if (attachment.getStatus() != AttachmentStatus.PENDING_APPROVAL) {
            throw new BusinessException("Attachment is not pending approval");
        }
        attachment.approve(approver);
        return repository.save(attachment);
    }

    @Transactional
    public AccountAttachment suspend(UUID id) {
        AccountAttachment attachment = getById(id);
        attachment.suspend();
        return repository.save(attachment);
    }

    @Transactional
    public AccountAttachment reactivate(UUID id) {
        AccountAttachment attachment = getById(id);
        attachment.reactivate();
        return repository.save(attachment);
    }

    @Transactional
    public AccountAttachment terminate(UUID id) {
        AccountAttachment attachment = getById(id);
        
        if (attachment.isOwner() && Boolean.TRUE.equals(attachment.getIsPrimary())) {
            throw new BusinessException("Cannot terminate primary owner. Transfer ownership first.");
        }
        
        attachment.terminate();
        return repository.save(attachment);
    }

    @Transactional
    public void delete(UUID id) {
        AccountAttachment attachment = getById(id);
        if (attachment.getStatus() == AttachmentStatus.ACTIVE) {
            throw new BusinessException("Cannot delete active attachment. Terminate it first.");
        }
        repository.delete(attachment);
        log.info("Deleted account attachment: id={}", id);
    }

    // ========================================================================
    // TRANSFER OWNERSHIP
    // ========================================================================

    @Transactional
    public AccountAttachment transferOwnership(UUID virtualAccountId, UUID newOwnerId, String transferReason) {
        log.info("Transferring ownership of VA {} to entity {}", virtualAccountId, newOwnerId);

        AccountAttachment currentOwner = repository.findByVirtualAccountIdAndRelationshipTypeAndIsPrimary(
            virtualAccountId, RelationshipType.OWNER, true)
            .orElseThrow(() -> new ResourceNotFoundException("No primary owner found for VA"));

        // Demote current owner
        currentOwner.setIsPrimary(false);
        currentOwner.setDescription("Former owner - " + transferReason);
        currentOwner.setEffectiveTo(LocalDate.now());
        repository.save(currentOwner);

        // Create new owner
        AccountAttachment newOwner = AccountAttachment.createOwner(virtualAccountId, newOwnerId);
        newOwner.setDescription("Ownership transferred: " + transferReason);
        newOwner = repository.save(newOwner);

        log.info("Ownership transferred from {} to {}", currentOwner.getLegalEntityId(), newOwnerId);
        return newOwner;
    }

    // ========================================================================
    // AUTHORIZATION CHECKS
    // ========================================================================

    @Transactional(readOnly = true)
    public boolean canTransact(UUID virtualAccountId, UUID legalEntityId, BigDecimal amount, String txnType) {
        List<AccountAttachment> attachments = repository.findActiveByVaAndEntity(virtualAccountId, legalEntityId);
        
        for (AccountAttachment att : attachments) {
            if (att.isOwner() || att.isBeneficiary()) {
                return true;
            }
            
            if (att.isAuthorized()) {
                if (!att.isWithinTransactionLimit(amount)) {
                    continue;
                }
                if (att.getAuthorizedTransactionTypes() != null && 
                    !att.getAuthorizedTransactionTypes().contains(txnType)) {
                    continue;
                }
                return true;
            }
        }
        
        return false;
    }

    // ========================================================================
    // STATISTICS
    // ========================================================================

    @Transactional(readOnly = true)
    public AccountAttachmentDto.Statistics getStatistics(UUID virtualAccountId) {
        List<AccountAttachment> attachments = repository.findByVirtualAccountId(virtualAccountId);
        
        long activeCount = attachments.stream().filter(a -> a.getStatus() == AttachmentStatus.ACTIVE).count();
        long pendingCount = attachments.stream().filter(a -> a.getStatus() == AttachmentStatus.PENDING_APPROVAL).count();
        long ownerCount = attachments.stream().filter(AccountAttachment::isOwner).count();
        long authorizedCount = attachments.stream().filter(AccountAttachment::isAuthorized).count();
        long collateralCount = attachments.stream().filter(AccountAttachment::isCollateral).count();
        long expiringCount = attachments.stream().filter(a -> a.willExpireWithin(30)).count();

        return AccountAttachmentDto.Statistics.builder()
            .totalAttachments(attachments.size())
            .activeAttachments((int) activeCount)
            .pendingApproval((int) pendingCount)
            .ownerRelationships((int) ownerCount)
            .authorizedRelationships((int) authorizedCount)
            .collateralRelationships((int) collateralCount)
            .expiringIn30Days((int) expiringCount)
            .build();
    }
}