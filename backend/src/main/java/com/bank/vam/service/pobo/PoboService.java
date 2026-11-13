package com.bank.vam.service.pobo;

import com.bank.vam.dto.pobo.PoboDto.*;
import com.bank.vam.entity.pobo.*;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.pobo.*;
import com.bank.vam.service.tax.ChargeService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * POBO Service - Pay On Behalf Of validation and management.
 * 
 * Phase 5 Enhanced Features:
 * - Intercompany recharge creation
 * - IHB integration for settlements (Phase 5)
 * - Transfer pricing compliance
 * 
 * VAM Compliance:
 * - Centralized treasury payments
 * - Arm's length validation
 * - Regulatory documentation
 * 
 * Integration Points:
 * - Payables module for payment processing
 * - IHB module for intercompany loans (Phase 5)
 * - Netting module for settlement cycles
 * 
 * @see <a href="https://www.capgemini.com/wp-content/uploads/2020/12/Virtual-Acc-Management-in-Transaction-Banking.pdf">
 *      Capgemini VAM Reference</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PoboService {

    private final IntercompanyRechargeRepository rechargeRepository;
    private final com.bank.vam.repository.hierarchy.LegalEntityRepository legalEntityRepository;
    private final ChargeService chargeService;
    private final com.bank.vam.config.MarketProfileProperties marketProfile;

    private static final AtomicLong rechargeSequence = new AtomicLong(1);

    // ========================================================================
    // INTERCOMPANY RECHARGE
    // ========================================================================

    @Transactional(readOnly = true)
    public List<RechargeResponse> getAllRecharges() {
        return rechargeRepository.findAll().stream()
            .map(this::toRechargeResponse)
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<RechargeResponse> getPendingRecharges(UUID corporateId) {
        // The corporate's recharges only: either party is one of its legal entities.
        Set<UUID> entityIds = legalEntityRepository.findByCorporateIdOrderByHierarchyPath(corporateId).stream()
            .map(com.bank.vam.entity.hierarchy.LegalEntity::getId).collect(Collectors.toSet());
        return rechargeRepository.findPendingRecharges().stream()
            .filter(r -> entityIds.contains(r.getPayerEntityId()) || entityIds.contains(r.getBehalfEntityId()))
            .map(this::toRechargeResponse)
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<RechargeResponse> getRechargesForEntity(UUID entityId) {
        List<IntercompanyRecharge> asPayerEntity = rechargeRepository.findByPayerEntityId(entityId);
        List<IntercompanyRecharge> asBehalfEntity = rechargeRepository.findByBehalfEntityId(entityId);
        
        Set<IntercompanyRecharge> all = new HashSet<>();
        all.addAll(asPayerEntity);
        all.addAll(asBehalfEntity);
        
        return all.stream()
            .map(this::toRechargeResponse)
            .collect(Collectors.toList());
    }

    /**
     * Create intercompany recharge record.
     * Called after POBO payment is executed.
     */
    @Transactional
    public RechargeResponse createRecharge(CreateRechargeRequest request) {
        String rechargeRef = generateRechargeReference();

        // Calculate service fee
        BigDecimal serviceFee = BigDecimal.ZERO;
        if (request.getServiceFeeRate() != null && request.getServiceFeeRate().compareTo(BigDecimal.ZERO) > 0) {
            serviceFee = chargeService.calculatePoboFee(
                request.getOriginalAmount(), 
                request.getServiceFeeRate(),
                new BigDecimal("25"),   // min fee
                new BigDecimal("1000")  // max fee
            );
        } else {
            serviceFee = chargeService.calculatePoboFee(request.getOriginalAmount());
        }

        BigDecimal adminFee = request.getAdminFee() != null ? request.getAdminFee() : BigDecimal.ZERO;
        BigDecimal totalRecharge = request.getOriginalAmount().add(serviceFee).add(adminFee);

        IntercompanyRecharge recharge = IntercompanyRecharge.builder()
            .rechargeReference(rechargeRef)
            .payerEntityId(request.getPayerEntityId())
            .payerEntityCode(request.getPayerEntityCode())
            .payerEntityName(request.getPayerEntityName())
            .payerVaId(request.getPayerVaId())
            .behalfEntityId(request.getBehalfEntityId())
            .behalfEntityCode(request.getBehalfEntityCode())
            .behalfEntityName(request.getBehalfEntityName())
            .behalfVaId(request.getBehalfVaId())
            .originalPayableId(request.getOriginalPayableId())
            .originalPaymentExecutionId(request.getOriginalPaymentExecutionId())
            .originalPaymentReference(request.getOriginalPaymentReference())
            .originalAmount(request.getOriginalAmount())
            .rechargeAmount(request.getOriginalAmount())
            .currencyCode(request.getCurrencyCode() != null ? request.getCurrencyCode() : marketProfile.getDefaultCurrency())
            .serviceFee(serviceFee)
            .adminFee(adminFee)
            .totalRecharge(totalRecharge)
            .approvalRequired(request.getRequiresApproval())
            .status(IntercompanyRecharge.RechargeStatus.PENDING)
            .build();

        recharge = rechargeRepository.save(recharge);
        log.info("Created intercompany recharge: {} - {} -> {}, total={}", 
            rechargeRef, request.getPayerEntityCode(), request.getBehalfEntityCode(), totalRecharge);

        return toRechargeResponse(recharge);
    }

    /**
     * Approve intercompany recharge.
     */
    @Transactional
    public RechargeResponse approveRecharge(UUID rechargeId, String approver) {
        IntercompanyRecharge recharge = rechargeRepository.findById(rechargeId)
            .orElseThrow(() -> new ResourceNotFoundException("Recharge not found: " + rechargeId));

        if (recharge.getStatus() != IntercompanyRecharge.RechargeStatus.PENDING) {
            throw new BusinessException("Recharge is not pending approval");
        }

        recharge.approve(approver);
        recharge = rechargeRepository.save(recharge);

        log.info("Approved recharge: {} by {}", recharge.getRechargeReference(), approver);
        return toRechargeResponse(recharge);
    }

    /**
     * Reject intercompany recharge.
     */
    @Transactional
    public RechargeResponse rejectRecharge(UUID rechargeId, String rejector, String reason) {
        IntercompanyRecharge recharge = rechargeRepository.findById(rechargeId)
            .orElseThrow(() -> new ResourceNotFoundException("Recharge not found: " + rechargeId));

        if (recharge.getStatus() != IntercompanyRecharge.RechargeStatus.PENDING) {
            throw new BusinessException("Recharge is not pending approval");
        }

        recharge.reject(rejector, reason);
        recharge = rechargeRepository.save(recharge);

        log.info("Rejected recharge: {} by {} - Reason: {}", recharge.getRechargeReference(), rejector, reason);
        return toRechargeResponse(recharge);
    }

    /**
     * Settle recharge via netting.
     */
    @Transactional
    public RechargeResponse settleViaNetting(UUID rechargeId, String nettingReference) {
        IntercompanyRecharge recharge = rechargeRepository.findById(rechargeId)
            .orElseThrow(() -> new ResourceNotFoundException("Recharge not found: " + rechargeId));

        recharge.settle(IntercompanyRecharge.SettlementMethod.NETTING, nettingReference);
        recharge = rechargeRepository.save(recharge);
        
        log.info("Settled recharge {} via netting: {}", recharge.getRechargeReference(), nettingReference);
        return toRechargeResponse(recharge);
    }

    /**
     * Validate arm's length pricing.
     */
    @Transactional
    public RechargeResponse validateArmLength(UUID rechargeId, String notes) {
        IntercompanyRecharge recharge = rechargeRepository.findById(rechargeId)
            .orElseThrow(() -> new ResourceNotFoundException("Recharge not found: " + rechargeId));

        recharge.validateArmLength(notes);
        recharge = rechargeRepository.save(recharge);
        
        log.info("Validated arm's length for recharge: {}", recharge.getRechargeReference());
        return toRechargeResponse(recharge);
    }

    // ========================================================================
    // POBO PAYMENT PROCESSING (Full Flow) - Phase 5 Enhanced
    // ========================================================================

    // ========================================================================
    // STATISTICS
    // ========================================================================

    @Transactional(readOnly = true)
    public PoboStatsResponse getStats() {
        List<IntercompanyRecharge> pendingRecharges = rechargeRepository.findPendingRecharges();
        List<IntercompanyRecharge> settledToday = rechargeRepository.findBySettlementDateRange(
            LocalDate.now(), LocalDate.now());
        
        BigDecimal totalOutstanding = rechargeRepository.findUnsettledRecharges().stream()
            .map(IntercompanyRecharge::getTotalRecharge)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalServiceFees = rechargeRepository.findAll().stream()
            .filter(r -> r.getStatus() == IntercompanyRecharge.RechargeStatus.SETTLED)
            .map(IntercompanyRecharge::getServiceFee)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        int pendingArmLength = rechargeRepository.findPendingArmLengthValidation().size();

        return PoboStatsResponse.builder()
            .pendingRecharges(pendingRecharges.size())
            .settledRecharges(settledToday.size())
            .totalRechargesOutstanding(totalOutstanding)
            .totalServiceFeesCollected(totalServiceFees)
            .armLengthValidationsPending(pendingArmLength)
            .asOfDate(LocalDateTime.now())
            .build();
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private String generateRechargeReference() {
        String date = LocalDate.now().toString().replace("-", "");
        return String.format("ICR-%s-%06d", date, rechargeSequence.getAndIncrement());
    }

    // ========================================================================
    // MAPPERS
    // ========================================================================

    private RechargeResponse toRechargeResponse(IntercompanyRecharge r) {
        return RechargeResponse.builder()
            .id(r.getId())
            .rechargeReference(r.getRechargeReference())
            .payerEntityId(r.getPayerEntityId())
            .payerEntityCode(r.getPayerEntityCode())
            .payerEntityName(r.getPayerEntityName())
            .behalfEntityId(r.getBehalfEntityId())
            .behalfEntityCode(r.getBehalfEntityCode())
            .behalfEntityName(r.getBehalfEntityName())
            .originalPayableId(r.getOriginalPayableId())
            .originalPaymentReference(r.getOriginalPaymentReference())
            .originalAmount(r.getOriginalAmount())
            .rechargeAmount(r.getRechargeAmount())
            .serviceFee(r.getServiceFee())
            .adminFee(r.getAdminFee())
            .fxMarkup(r.getFxMarkup())
            .totalRecharge(r.getTotalRecharge())
            .currencyCode(r.getCurrencyCode())
            .armLengthValidated(r.getArmLengthValidated())
            .armLengthNotes(r.getArmLengthNotes())
            .status(r.getStatus())
            .settlementDate(r.getSettlementDate())
            .settlementReference(r.getSettlementReference())
            .settledVia(r.getSettledVia())
            .approvedBy(r.getApprovedBy())
            .approvedAt(r.getApprovedAt())
            .createdAt(r.getCreatedAt())
            .build();
    }

    // ========================================================================
    // PHASE 5: INNER CLASSES
    // ========================================================================
}