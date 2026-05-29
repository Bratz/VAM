package com.bank.vam.service.credit;

import com.bank.vam.entity.credit.CreditAgreement;
import com.bank.vam.entity.credit.CreditAgreement.AgreementStatus;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.credit.CreditAgreementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * CreditAgreementService - Manages master credit agreements from CBS.
 * 
 * UNIFIED ARCHITECTURE v4.2:
 * Credit Agreements are master agreements from Core Banking System (CBS).
 * They contain overall credit limits allocated to a corporate.
 * Each agreement can have multiple Credit Facilities.
 * 
 * Hierarchy:
 * - CreditAgreement (Master) -> CreditFacility (N) -> CreditLimit (N)
 * 
 * CBS Integration:
 * - Agreements are synced from CBS
 * - Utilization is tracked locally and synced back
 * - Interest rates come from CBS
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditAgreementService {

    private final CreditAgreementRepository agreementRepository;

    // ========================================================================
    // CRUD OPERATIONS
    // ========================================================================

    /**
     * Get agreement by ID.
     */
    @Transactional(readOnly = true)
    public CreditAgreement getById(UUID id) {
        return agreementRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Credit agreement not found: " + id));
    }

    /**
     * Get agreement by external reference.
     */
    @Transactional(readOnly = true)
    public CreditAgreement getByExternalReference(String externalReference) {
        return agreementRepository.findByExternalReference(externalReference)
            .orElseThrow(() -> new ResourceNotFoundException("Credit agreement not found: " + externalReference));
    }

    /**
     * Get all agreements for a corporate.
     */
    @Transactional(readOnly = true)
    public List<CreditAgreement> getByCorporate(UUID corporateId) {
        return agreementRepository.findByCorporateIdOrderByAgreementName(corporateId);
    }

    /**
     * Get active agreements for a corporate.
     */
    @Transactional(readOnly = true)
    public List<CreditAgreement> getActiveByCorporate(UUID corporateId) {
        return agreementRepository.findActiveByCorporate(corporateId);
    }

    /**
     * Create a new credit agreement.
     */
    @Transactional
    public CreditAgreement create(CreditAgreement agreement) {
        log.info("Creating credit agreement: {} for corporate: {}", 
            agreement.getAgreementName(), agreement.getCorporateId());

        // Validate unique external reference if provided
        if (agreement.getExternalReference() != null && 
            agreementRepository.existsByExternalReference(agreement.getExternalReference())) {
            throw new BusinessException("External reference already exists: " + agreement.getExternalReference());
        }

        // Set defaults
        if (agreement.getStatus() == null) {
            agreement.setStatus(AgreementStatus.ACTIVE);
        }
        if (agreement.getTotalUtilized() == null) {
            agreement.setTotalUtilized(BigDecimal.ZERO);
        }
        if (agreement.getEffectiveDate() == null) {
            agreement.setEffectiveDate(LocalDate.now());
        }
        
        // Calculate available limit
        agreement.recalculateAvailable();

        CreditAgreement saved = agreementRepository.save(agreement);
        log.info("Created credit agreement: {} (ID: {})", saved.getAgreementName(), saved.getId());
        return saved;
    }

    /**
     * Update agreement.
     */
    @Transactional
    public CreditAgreement update(UUID id, CreditAgreement updates) {
        CreditAgreement agreement = getById(id);

        // Update allowed fields
        if (updates.getAgreementName() != null) {
            agreement.setAgreementName(updates.getAgreementName());
        }
        if (updates.getTotalLimit() != null) {
            agreement.setTotalLimit(updates.getTotalLimit());
            agreement.recalculateAvailable();
        }
        if (updates.getExpiryDate() != null) {
            agreement.setExpiryDate(updates.getExpiryDate());
        }
        if (updates.getNextReviewDate() != null) {
            agreement.setNextReviewDate(updates.getNextReviewDate());
        }
        if (updates.getBaseRateType() != null) {
            agreement.setBaseRateType(updates.getBaseRateType());
        }
        if (updates.getSpreadBps() != null) {
            agreement.setSpreadBps(updates.getSpreadBps());
        }
        if (updates.getBankCode() != null) {
            agreement.setBankCode(updates.getBankCode());
        }
        if (updates.getBankName() != null) {
            agreement.setBankName(updates.getBankName());
        }
        if (updates.getNotes() != null) {
            agreement.setNotes(updates.getNotes());
        }

        return agreementRepository.save(agreement);
    }

    /**
     * Delete agreement (soft delete by status change).
     */
    @Transactional
    public void delete(UUID id) {
        CreditAgreement agreement = getById(id);
        
        // Check if has utilization
        if (agreement.getTotalUtilized() != null && 
            agreement.getTotalUtilized().compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException("Cannot delete agreement with utilized amount");
        }

        agreementRepository.updateStatus(id, AgreementStatus.CANCELLED);
        log.info("Deleted (cancelled) credit agreement: {}", agreement.getAgreementName());
    }

    // ========================================================================
    // UTILIZATION OPERATIONS
    // ========================================================================

    /**
     * Utilize (draw down) from agreement.
     */
    @Transactional
    public CreditAgreement utilize(UUID agreementId, BigDecimal amount) {
        CreditAgreement agreement = getById(agreementId);
        
        if (!agreement.isActive()) {
            throw new BusinessException("Cannot utilize from non-active agreement");
        }

        BigDecimal available = agreement.getAvailableLimit() != null ? 
            agreement.getAvailableLimit() : BigDecimal.ZERO;
        
        if (amount.compareTo(available) > 0) {
            throw new BusinessException("Insufficient available limit. Available: " + available + 
                ", Requested: " + amount);
        }

        // Use entity's helper method
        agreement.utilize(amount);

        log.info("Utilized {} from agreement {}. New available: {}", 
            amount, agreement.getAgreementName(), agreement.getAvailableLimit());
        
        return agreementRepository.save(agreement);
    }

    /**
     * Release utilization back to agreement.
     */
    @Transactional
    public CreditAgreement release(UUID agreementId, BigDecimal amount) {
        CreditAgreement agreement = getById(agreementId);

        if (agreement.getTotalUtilized() == null || 
            agreement.getTotalUtilized().compareTo(amount) < 0) {
            throw new BusinessException("Cannot release more than utilized amount");
        }

        // Use entity's helper method
        agreement.release(amount);

        log.info("Released {} to agreement {}. New available: {}", 
            amount, agreement.getAgreementName(), agreement.getAvailableLimit());
        
        return agreementRepository.save(agreement);
    }

    // ========================================================================
    // STATUS OPERATIONS
    // ========================================================================

    /**
     * Activate an agreement.
     */
    @Transactional
    public CreditAgreement activate(UUID id) {
        CreditAgreement agreement = getById(id);
        
        if (agreement.getStatus() == AgreementStatus.TERMINATED || 
            agreement.getStatus() == AgreementStatus.CANCELLED) {
            throw new BusinessException("Cannot activate terminated/cancelled agreement");
        }

        agreementRepository.updateStatus(id, AgreementStatus.ACTIVE);
        agreement.setStatus(AgreementStatus.ACTIVE);
        return agreement;
    }

    /**
     * Suspend an agreement.
     */
    @Transactional
    public CreditAgreement suspend(UUID id) {
        agreementRepository.updateStatus(id, AgreementStatus.SUSPENDED);
        CreditAgreement agreement = getById(id);
        return agreement;
    }

    /**
     * Mark as expired.
     */
    @Transactional
    public CreditAgreement expire(UUID id) {
        agreementRepository.updateStatus(id, AgreementStatus.EXPIRED);
        CreditAgreement agreement = getById(id);
        return agreement;
    }

    // ========================================================================
    // CBS SYNC OPERATIONS
    // ========================================================================

    /**
     * Sync agreement from CBS data.
     */
    @Transactional
    public CreditAgreement syncFromCbs(String externalReference, Map<String, Object> cbsData) {
        log.info("Syncing agreement from CBS: {}", externalReference);

        // Try to find existing
        Optional<CreditAgreement> existingOpt = agreementRepository.findByExternalReference(externalReference);

        CreditAgreement agreement = existingOpt.orElse(new CreditAgreement());

        // Update from CBS data
        agreement.setExternalReference(externalReference);
        
        if (cbsData.containsKey("agreementName")) {
            agreement.setAgreementName((String) cbsData.get("agreementName"));
        }
        if (cbsData.containsKey("totalLimit")) {
            agreement.setTotalLimit(new BigDecimal(cbsData.get("totalLimit").toString()));
        }
        if (cbsData.containsKey("totalUtilized")) {
            agreement.setTotalUtilized(new BigDecimal(cbsData.get("totalUtilized").toString()));
        }
        if (cbsData.containsKey("limitCurrency")) {
            agreement.setLimitCurrency((String) cbsData.get("limitCurrency"));
        }
        if (cbsData.containsKey("effectiveDate")) {
            agreement.setEffectiveDate(LocalDate.parse((String) cbsData.get("effectiveDate")));
        }
        if (cbsData.containsKey("expiryDate")) {
            agreement.setExpiryDate(LocalDate.parse((String) cbsData.get("expiryDate")));
        }
        if (cbsData.containsKey("bankCode")) {
            agreement.setBankCode((String) cbsData.get("bankCode"));
        }
        if (cbsData.containsKey("bankName")) {
            agreement.setBankName((String) cbsData.get("bankName"));
        }
        if (cbsData.containsKey("corporateId")) {
            agreement.setCorporateId(UUID.fromString((String) cbsData.get("corporateId")));
        }

        // Recalculate available
        agreement.recalculateAvailable();

        // Update sync info
        agreement.setLastSyncAt(LocalDateTime.now());
        agreement.setSourceSystem("CORE_BANKING");

        CreditAgreement saved = agreementRepository.save(agreement);
        log.info("Synced agreement: {} (External: {})", saved.getAgreementName(), externalReference);
        return saved;
    }

    /**
     * Get agreements needing sync (not synced in last N hours).
     */
    @Transactional(readOnly = true)
    public List<CreditAgreement> getAgreementsNeedingSync(int hours) {
        LocalDateTime threshold = LocalDateTime.now().minusHours(hours);
        return agreementRepository.findAll().stream()
            .filter(a -> a.getExternalReference() != null)
            .filter(a -> a.getLastSyncAt() == null || a.getLastSyncAt().isBefore(threshold))
            .toList();
    }

    // ========================================================================
    // QUERY OPERATIONS
    // ========================================================================

    /**
     * Get agreements expiring within N days.
     */
    @Transactional(readOnly = true)
    public List<CreditAgreement> getExpiringWithin(int days) {
        LocalDate expiryThreshold = LocalDate.now().plusDays(days);
        return agreementRepository.findExpiringSoon(expiryThreshold);
    }

    /**
     * Get agreements expiring within N days for a corporate.
     */
    @Transactional(readOnly = true)
    public List<CreditAgreement> getExpiringWithin(UUID corporateId, int days) {
        LocalDate expiryThreshold = LocalDate.now().plusDays(days);
        return agreementRepository.findByCorporateIdOrderByAgreementName(corporateId).stream()
            .filter(a -> a.getExpiryDate() != null)
            .filter(a -> a.getExpiryDate().isBefore(expiryThreshold))
            .filter(CreditAgreement::isActive)
            .toList();
    }

    /**
     * Get agreements needing review.
     */
    @Transactional(readOnly = true)
    public List<CreditAgreement> getAgreementsForReview(UUID corporateId) {
        LocalDate today = LocalDate.now();
        return agreementRepository.findByCorporateIdOrderByAgreementName(corporateId).stream()
            .filter(a -> a.getNextReviewDate() != null)
            .filter(a -> !a.getNextReviewDate().isAfter(today))
            .filter(CreditAgreement::isActive)
            .toList();
    }

    /**
     * Get total credit limit for corporate.
     */
    @Transactional(readOnly = true)
    public BigDecimal getTotalCreditLimit(UUID corporateId) {
        return agreementRepository.getTotalLimitByCorporate(corporateId);
    }

    /**
     * Get total utilized amount for corporate.
     */
    @Transactional(readOnly = true)
    public BigDecimal getTotalUtilized(UUID corporateId) {
        return agreementRepository.getTotalUtilizedByCorporate(corporateId);
    }

    /**
     * Get utilization summary for corporate.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getUtilizationSummary(UUID corporateId) {
        List<CreditAgreement> agreements = getActiveByCorporate(corporateId);
        
        BigDecimal totalLimit = agreementRepository.getTotalLimitByCorporate(corporateId);
        BigDecimal totalUtilized = agreementRepository.getTotalUtilizedByCorporate(corporateId);
        BigDecimal totalAvailable = totalLimit.subtract(totalUtilized);
        
        double utilizationPercent = totalLimit.compareTo(BigDecimal.ZERO) > 0 ?
            totalUtilized.divide(totalLimit, 4, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue() : 0.0;

        Map<String, Object> summary = new HashMap<>();
        summary.put("agreementCount", agreements.size());
        summary.put("totalLimit", totalLimit);
        summary.put("totalUtilized", totalUtilized);
        summary.put("totalAvailable", totalAvailable);
        summary.put("utilizationPercent", utilizationPercent);
        summary.put("currency", agreements.isEmpty() ? "EUR" : agreements.get(0).getLimitCurrency());
        summary.put("distinctBanks", agreementRepository.findDistinctBanks(corporateId));
        
        return summary;
    }

    /**
     * Get agreements count for corporate.
     */
    @Transactional(readOnly = true)
    public long getActiveCount(UUID corporateId) {
        return agreementRepository.countByCorporateIdAndStatus(corporateId, AgreementStatus.ACTIVE);
    }

    /**
     * Get all agreements (for admin).
     */
    @Transactional(readOnly = true)
    public List<CreditAgreement> getAll() {
        return agreementRepository.findAll();
    }
}