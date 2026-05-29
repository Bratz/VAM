package com.bank.vam.service.credit;

import com.bank.vam.entity.credit.CreditAgreement;
import com.bank.vam.entity.credit.CreditFacility;
import com.bank.vam.entity.credit.CreditFacility.FacilityStatus;
import com.bank.vam.entity.credit.CreditFacility.FacilityType;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.credit.CreditAgreementRepository;
import com.bank.vam.repository.credit.CreditFacilityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * CreditFacilityService - Manages specific credit facilities under master agreements.
 * 
 * UNIFIED ARCHITECTURE v4.2:
 * Credit Facilities are specific credit lines under master agreements.
 * Each facility links to:
 * - CreditAgreement (parent)
 * - PhysicalAccount (optional - for overdraft facilities)
 * 
 * Facility Types:
 * - OVERDRAFT: Overdraft on physical account
 * - REVOLVING_CREDIT: Revolving credit line
 * - TERM_LOAN: Fixed term loan
 * - WORKING_CAPITAL: Working capital facility
 * - LETTER_OF_CREDIT: L/C facility
 * - BANK_GUARANTEE: BG facility
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditFacilityService {

    private final CreditFacilityRepository facilityRepository;
    private final CreditAgreementRepository agreementRepository;

    // ========================================================================
    // CRUD OPERATIONS
    // ========================================================================

    /**
     * Get facility by ID.
     */
    @Transactional(readOnly = true)
    public CreditFacility getById(UUID id) {
        return facilityRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Credit facility not found: " + id));
    }

    /**
     * Get facility by external reference.
     */
    @Transactional(readOnly = true)
    public CreditFacility getByExternalReference(String externalReference) {
        return facilityRepository.findByExternalReference(externalReference)
            .orElseThrow(() -> new ResourceNotFoundException("Facility not found: " + externalReference));
    }

    /**
     * Get all facilities for an agreement.
     */
    @Transactional(readOnly = true)
    public List<CreditFacility> getByAgreement(UUID agreementId) {
        return facilityRepository.findByCreditAgreementIdOrderByFacilityName(agreementId);
    }

    /**
     * Get active facilities for an agreement.
     */
    @Transactional(readOnly = true)
    public List<CreditFacility> getActiveByAgreement(UUID agreementId) {
        return facilityRepository.findActiveByAgreement(agreementId);
    }

    /**
     * Get all facilities for a corporate.
     */
    @Transactional(readOnly = true)
    public List<CreditFacility> getByCorporate(UUID corporateId) {
        return facilityRepository.findByCorporateIdOrderByFacilityName(corporateId);
    }

    /**
     * Get active facilities for a corporate.
     */
    @Transactional(readOnly = true)
    public List<CreditFacility> getActiveByCorporate(UUID corporateId) {
        return facilityRepository.findActiveByCorporate(corporateId);
    }

    /**
     * Get facility linked to a physical account.
     */
    @Transactional(readOnly = true)
    public Optional<CreditFacility> getByPhysicalAccount(UUID physicalAccountId) {
        return facilityRepository.findByPhysicalAccountId(physicalAccountId);
    }

    /**
     * Create a new facility.
     */
    @Transactional
    public CreditFacility create(CreditFacility facility) {
        log.info("Creating credit facility: {} under agreement: {}", 
            facility.getFacilityName(), facility.getCreditAgreementId());

        // Validate agreement exists
        CreditAgreement agreement = agreementRepository.findById(facility.getCreditAgreementId())
            .orElseThrow(() -> new ResourceNotFoundException("Agreement not found: " + facility.getCreditAgreementId()));

        // Validate unique external reference if provided
        if (facility.getExternalReference() != null && 
            facilityRepository.existsByExternalReference(facility.getExternalReference())) {
            throw new BusinessException("External reference already exists");
        }

        // Validate sanctioned limit doesn't exceed agreement limit
        BigDecimal totalFacilityLimit = facilityRepository.getTotalSanctionedByAgreement(facility.getCreditAgreementId());
        BigDecimal newTotal = totalFacilityLimit.add(
            facility.getSanctionedLimit() != null ? facility.getSanctionedLimit() : BigDecimal.ZERO);
        
        if (agreement.getTotalLimit() != null && 
            newTotal.compareTo(agreement.getTotalLimit()) > 0) {
            throw new BusinessException("Total facility limit would exceed agreement limit. " +
                "Agreement limit: " + agreement.getTotalLimit() + ", Would be: " + newTotal);
        }

        // Set defaults
        if (facility.getStatus() == null) {
            facility.setStatus(FacilityStatus.ACTIVE);
        }
        if (facility.getCurrentOutstanding() == null) {
            facility.setCurrentOutstanding(BigDecimal.ZERO);
        }
        if (facility.getEffectiveDate() == null) {
            facility.setEffectiveDate(LocalDate.now());
        }
        
        // Calculate available limit
        facility.recalculateAvailable();

        CreditFacility saved = facilityRepository.save(facility);
        log.info("Created credit facility: {} (ID: {})", saved.getFacilityName(), saved.getId());
        return saved;
    }

    /**
     * Update facility.
     */
    @Transactional
    public CreditFacility update(UUID id, CreditFacility updates) {
        CreditFacility facility = getById(id);

        if (updates.getFacilityName() != null) {
            facility.setFacilityName(updates.getFacilityName());
        }
        if (updates.getSanctionedLimit() != null) {
            facility.setSanctionedLimit(updates.getSanctionedLimit());
            facility.recalculateAvailable();
        }
        if (updates.getDrawingPower() != null) {
            facility.setDrawingPower(updates.getDrawingPower());
            facility.recalculateAvailable();
        }
        if (updates.getExpiryDate() != null) {
            facility.setExpiryDate(updates.getExpiryDate());
        }
        if (updates.getInterestRateType() != null) {
            facility.setInterestRateType(updates.getInterestRateType());
        }
        if (updates.getBaseRateType() != null) {
            facility.setBaseRateType(updates.getBaseRateType());
        }
        if (updates.getBaseRateValue() != null) {
            facility.setBaseRateValue(updates.getBaseRateValue());
            facility.calculateEffectiveRate();
        }
        if (updates.getSpreadPercent() != null) {
            facility.setSpreadPercent(updates.getSpreadPercent());
            facility.calculateEffectiveRate();
        }
        if (updates.getNotes() != null) {
            facility.setNotes(updates.getNotes());
        }

        return facilityRepository.save(facility);
    }

    // ========================================================================
    // UTILIZATION OPERATIONS
    // ========================================================================

    /**
     * Draw down from facility.
     */
    @Transactional
    public CreditFacility drawDown(UUID facilityId, BigDecimal amount) {
        CreditFacility facility = getById(facilityId);
        
        if (!facility.isActive()) {
            throw new BusinessException("Cannot draw from non-active facility");
        }

        BigDecimal available = facility.getAvailableLimit() != null ? 
            facility.getAvailableLimit() : BigDecimal.ZERO;
        
        if (amount.compareTo(available) > 0) {
            throw new BusinessException("Insufficient available limit. Available: " + available + 
                ", Requested: " + amount);
        }

        // Use entity's helper method
        facility.drawDown(amount);

        log.info("Drew down {} from facility {}. New available: {}", 
            amount, facility.getFacilityName(), facility.getAvailableLimit());
        
        return facilityRepository.save(facility);
    }

    /**
     * Repay to facility.
     */
    @Transactional
    public CreditFacility repay(UUID facilityId, BigDecimal amount) {
        CreditFacility facility = getById(facilityId);

        if (facility.getCurrentOutstanding() == null || 
            facility.getCurrentOutstanding().compareTo(amount) < 0) {
            throw new BusinessException("Cannot repay more than outstanding amount");
        }

        // Use entity's helper method
        facility.repay(amount);

        log.info("Repaid {} to facility {}. New available: {}", 
            amount, facility.getFacilityName(), facility.getAvailableLimit());
        
        return facilityRepository.save(facility);
    }

    /**
     * Update drawing power (for working capital facilities).
     */
    @Transactional
    public CreditFacility updateDrawingPower(UUID facilityId, BigDecimal newDrawingPower) {
        CreditFacility facility = getById(facilityId);
        
        if (newDrawingPower.compareTo(facility.getSanctionedLimit()) > 0) {
            throw new BusinessException("Drawing power cannot exceed sanctioned limit");
        }

        facilityRepository.updateDrawingPower(facilityId, newDrawingPower);
        facility.setDrawingPower(newDrawingPower);
        facility.recalculateAvailable();
        
        log.info("Updated drawing power for facility {} to {}", 
            facility.getFacilityName(), newDrawingPower);
        
        return facility;
    }

    // ========================================================================
    // LINK OPERATIONS
    // ========================================================================

    /**
     * Link facility to physical account.
     */
    @Transactional
    public CreditFacility linkToPhysicalAccount(UUID facilityId, UUID physicalAccountId) {
        CreditFacility facility = getById(facilityId);
        facility.setPhysicalAccountId(physicalAccountId);
        log.info("Linked facility {} to physical account {}", facility.getFacilityName(), physicalAccountId);
        return facilityRepository.save(facility);
    }

    // ========================================================================
    // STATUS OPERATIONS
    // ========================================================================

    /**
     * Suspend facility.
     */
    @Transactional
    public CreditFacility suspend(UUID id) {
        facilityRepository.updateStatus(id, FacilityStatus.SUSPENDED);
        return getById(id);
    }

    /**
     * Activate facility.
     */
    @Transactional
    public CreditFacility activate(UUID id) {
        CreditFacility facility = getById(id);
        if (facility.getStatus() == FacilityStatus.CLOSED || 
            facility.getStatus() == FacilityStatus.CANCELLED) {
            throw new BusinessException("Cannot activate closed/cancelled facility");
        }
        facilityRepository.updateStatus(id, FacilityStatus.ACTIVE);
        facility.setStatus(FacilityStatus.ACTIVE);
        return facility;
    }

    /**
     * Close facility.
     */
    @Transactional
    public CreditFacility close(UUID id) {
        CreditFacility facility = getById(id);
        
        if (facility.getCurrentOutstanding() != null && 
            facility.getCurrentOutstanding().compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException("Cannot close facility with outstanding balance");
        }
        
        facilityRepository.updateStatus(id, FacilityStatus.CLOSED);
        facility.setStatus(FacilityStatus.CLOSED);
        return facility;
    }

    // ========================================================================
    // CBS SYNC OPERATIONS
    // ========================================================================

    /**
     * Sync facility from CBS.
     */
    @Transactional
    public CreditFacility syncFromCbs(String externalReference, Map<String, Object> cbsData) {
        log.info("Syncing facility from CBS: {}", externalReference);

        Optional<CreditFacility> existingOpt = facilityRepository.findByExternalReference(externalReference);
        CreditFacility facility = existingOpt.orElse(new CreditFacility());

        facility.setExternalReference(externalReference);
        
        if (cbsData.containsKey("facilityName")) {
            facility.setFacilityName((String) cbsData.get("facilityName"));
        }
        if (cbsData.containsKey("facilityType")) {
            facility.setFacilityType(FacilityType.valueOf((String) cbsData.get("facilityType")));
        }
        if (cbsData.containsKey("sanctionedLimit")) {
            facility.setSanctionedLimit(new BigDecimal(cbsData.get("sanctionedLimit").toString()));
        }
        if (cbsData.containsKey("currentOutstanding")) {
            facility.setCurrentOutstanding(new BigDecimal(cbsData.get("currentOutstanding").toString()));
        }
        if (cbsData.containsKey("drawingPower")) {
            facility.setDrawingPower(new BigDecimal(cbsData.get("drawingPower").toString()));
        }
        if (cbsData.containsKey("facilityCurrency")) {
            facility.setFacilityCurrency((String) cbsData.get("facilityCurrency"));
        }
        if (cbsData.containsKey("effectiveRate")) {
            facility.setEffectiveRate(new BigDecimal(cbsData.get("effectiveRate").toString()));
        }
        if (cbsData.containsKey("creditAgreementId")) {
            facility.setCreditAgreementId(UUID.fromString((String) cbsData.get("creditAgreementId")));
        }
        if (cbsData.containsKey("corporateId")) {
            facility.setCorporateId(UUID.fromString((String) cbsData.get("corporateId")));
        }

        facility.recalculateAvailable();
        facility.setLastSyncAt(LocalDateTime.now());
        facility.setSourceSystem("CORE_BANKING");

        return facilityRepository.save(facility);
    }

    // ========================================================================
    // QUERY OPERATIONS
    // ========================================================================

    /**
     * Get facilities by type for a corporate.
     */
    @Transactional(readOnly = true)
    public List<CreditFacility> getByType(UUID corporateId, FacilityType type) {
        return facilityRepository.findByCorporateIdAndFacilityType(corporateId, type);
    }

    /**
     * Get overdraft facilities for a corporate.
     */
    @Transactional(readOnly = true)
    public List<CreditFacility> getOverdrafts(UUID corporateId) {
        return facilityRepository.findActiveOverdrafts(corporateId);
    }

    /**
     * Get expiring facilities.
     */
    @Transactional(readOnly = true)
    public List<CreditFacility> getExpiringWithin(int days) {
        LocalDate threshold = LocalDate.now().plusDays(days);
        return facilityRepository.findExpiringSoon(threshold);
    }

    /**
     * Get high utilization facilities for a corporate.
     */
    @Transactional(readOnly = true)
    public List<CreditFacility> getHighUtilization(UUID corporateId) {
        return facilityRepository.findHighUtilizationFacilities(corporateId);
    }

    /**
     * Get facility summary for agreement.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getFacilitySummary(UUID agreementId) {
        List<CreditFacility> facilities = getActiveByAgreement(agreementId);
        
        BigDecimal totalLimit = facilityRepository.getTotalSanctionedByAgreement(agreementId);
        BigDecimal totalOutstanding = facilityRepository.getTotalOutstandingByAgreement(agreementId);

        Map<FacilityType, Long> byType = new HashMap<>();
        for (CreditFacility f : facilities) {
            if (f.getFacilityType() != null) {
                byType.merge(f.getFacilityType(), 1L, Long::sum);
            }
        }

        Map<String, Object> summary = new HashMap<>();
        summary.put("facilityCount", facilities.size());
        summary.put("totalSanctioned", totalLimit);
        summary.put("totalOutstanding", totalOutstanding);
        summary.put("totalAvailable", totalLimit.subtract(totalOutstanding));
        summary.put("byType", byType);
        
        return summary;
    }

    /**
     * Get distinct facility types for a corporate.
     */
    @Transactional(readOnly = true)
    public List<FacilityType> getDistinctTypes(UUID corporateId) {
        return facilityRepository.findDistinctFacilityTypes(corporateId);
    }

    /**
     * Get facility count for agreement.
     */
    @Transactional(readOnly = true)
    public long getActiveCount(UUID agreementId) {
        return facilityRepository.countByCreditAgreementIdAndStatus(agreementId, FacilityStatus.ACTIVE);
    }

    /**
     * Get all facilities (for admin).
     */
    @Transactional(readOnly = true)
    public List<CreditFacility> getAll() {
        return facilityRepository.findAll();
    }
}