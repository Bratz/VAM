package com.bank.vam.service.viban;

import com.bank.vam.dto.viban.VibanDto.*;
import com.bank.vam.entity.Program;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.viban.Viban;
import com.bank.vam.entity.viban.Viban.VibanType;
import com.bank.vam.entity.viban.VibanPool;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.ProgramRepository;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.party.PartyRepository;
import com.bank.vam.repository.viban.VibanPoolRepository;
import com.bank.vam.repository.viban.VibanRepository;
import com.bank.vam.entity.party.Party;
import com.bank.vam.service.treasury.FeePostingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Service for VIBAN management.
 * Handles VIBAN creation, lookup, pool management, and routing.
 * 
 * Enhanced with Settlement VA fee posting:
 * - VIBAN issuance fee: AED 10 flat per VIBAN
 * - Payment routing fee: 0.05% of payment amount (min AED 1, max AED 50)
 * 
 * CRITICAL: VIBAN lookup must be <5ms for ROBO routing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class VibanService {

    private final VibanRepository vibanRepository;
    private final VibanPoolRepository poolRepository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final ProgramRepository programRepository;
    private final TransactionRepository transactionRepository;
    private final PartyRepository partyRepository;
    private final FeePostingService feePostingService;
    private final com.bank.vam.config.MarketProfileProperties marketProfile;

    // Counter for VIBAN generation
    private final AtomicLong vibanCounter = new AtomicLong(System.currentTimeMillis() % 1000000);
    
    // NEW: VIBAN fee constants
    private static final BigDecimal VIBAN_ISSUANCE_FEE = new BigDecimal("10.00");
    private static final BigDecimal VIBAN_ROUTING_FEE_RATE = new BigDecimal("0.0005");  // 0.05%
    private static final BigDecimal VIBAN_ROUTING_FEE_MIN = new BigDecimal("1.00");
    private static final BigDecimal VIBAN_ROUTING_FEE_MAX = new BigDecimal("50.00");

    // ========================================================================
    // VIBAN Creation - Enhanced with Fee Posting
    // ========================================================================

    /**
     * Create a new VIBAN for a virtual account.
     * Posts issuance fee to Settlement VA.
     */
    public VibanResponse createViban(UUID programId, VibanCreateRequest request) {
        Program program = programRepository.findById(programId)
            .orElseThrow(() -> new ResourceNotFoundException("Program not found: " + programId));

        VirtualAccount va = virtualAccountRepository.findById(request.getVirtualAccountId())
            .orElseThrow(() -> new ResourceNotFoundException("Virtual account not found"));

        // Generate VIBAN string
        String vibanString = generateViban(program);

        // Check if trying to create PRIMARY when one exists
        if (request.getVibanType() == VibanType.PRIMARY) {
            if (vibanRepository.findPrimaryByVirtualAccountId(va.getId()).isPresent()) {
                throw new BusinessException("Virtual account already has a primary VIBAN");
            }
        }

        Viban viban = Viban.builder()
            .viban(vibanString)
            .virtualAccountId(va.getId())
            .programId(programId)
            .hierarchyNodeId(va.getHierarchyNodeId())
            .vibanType(request.getVibanType())
            .isPrimary(request.getVibanType() == VibanType.PRIMARY)
            .referenceType(request.getReferenceType())
            .referenceId(request.getReferenceId())
            .expectedAmount(request.getExpectedAmount())
            .amountTolerancePercent(request.getAmountTolerancePercent())
            .minAmount(request.getMinAmount())
            .maxAmount(request.getMaxAmount())
            .currencyCode(request.getCurrencyCode() != null ? request.getCurrencyCode() : va.getCurrencyCode())
            .validFrom(LocalDateTime.now())
            .validUntil(request.getValidUntil())
            .singleUse(request.getSingleUse() != null ? request.getSingleUse() : false)
            .customerName(request.getCustomerName())
            .purpose(request.getPurpose())
            .status(Viban.STATUS_ACTIVE)
            .build();

        Viban saved = vibanRepository.save(viban);

        // Update VA's primary VIBAN reference if this is primary
        if (saved.getIsPrimary()) {
            va.setPrimaryVibanId(saved.getId());
            va.setViban(vibanString);
            virtualAccountRepository.save(va);
        }
        
        // NEW: Post VIBAN issuance fee to Settlement VA
        postVibanIssuanceFee(va, saved);

        log.info("Created VIBAN {} of type {} for VA {}", vibanString, request.getVibanType(), va.getVaNumber());
        return toResponse(saved, va);
    }

    /**
     * Create VIBAN for invoice (auto-reconciliation).
     */
    public VibanResponse createInvoiceViban(UUID programId, UUID virtualAccountId, 
                                             String invoiceId, BigDecimal amount, 
                                             LocalDateTime validUntil) {
        VibanCreateRequest request = VibanCreateRequest.builder()
            .virtualAccountId(virtualAccountId)
            .vibanType(VibanType.INVOICE)
            .referenceType(Viban.REF_TYPE_INVOICE)
            .referenceId(invoiceId)
            .expectedAmount(amount)
            .validUntil(validUntil)
            .singleUse(false)
            .build();
        
        return createViban(programId, request);
    }

    /**
     * Create VIBAN for e-commerce order.
     */
    public VibanResponse createOrderViban(UUID programId, UUID virtualAccountId,
                                           String orderId, BigDecimal amount) {
        VibanCreateRequest request = VibanCreateRequest.builder()
            .virtualAccountId(virtualAccountId)
            .vibanType(VibanType.ORDER)
            .referenceType(Viban.REF_TYPE_ORDER)
            .referenceId(orderId)
            .expectedAmount(amount)
            .singleUse(true)
            .build();
        
        return createViban(programId, request);
    }

    // ========================================================================
    // VIBAN Lookup - CRITICAL for ROBO (<5ms)
    // ========================================================================
    
    /**
     * Get VIBAN by ID.
     */
    @Transactional(readOnly = true)
    public VibanResponse getViban(UUID vibanId) {
        Viban viban = vibanRepository.findById(vibanId)
            .orElseThrow(() -> new ResourceNotFoundException("VIBAN not found: " + vibanId));
        VirtualAccount va = virtualAccountRepository.findById(viban.getVirtualAccountId()).orElse(null);
        return toResponse(viban, va);
    }
    
    /**
     * Get VIBANs for a Virtual Account.
     */
    @Transactional(readOnly = true)
    public List<VibanResponse> getVibansForVa(UUID virtualAccountId) {
        VirtualAccount va = virtualAccountRepository.findById(virtualAccountId).orElse(null);
        return vibanRepository.findByVirtualAccountId(virtualAccountId)
            .stream()
            .map(v -> toResponse(v, va))
            .collect(Collectors.toList());
    }
    
    /**
     * Update VIBAN status.
     */
    @Transactional
    public VibanResponse updateStatus(UUID vibanId, String newStatus) {
        Viban viban = vibanRepository.findById(vibanId)
            .orElseThrow(() -> new ResourceNotFoundException("VIBAN not found: " + vibanId));
        
        viban.setStatus(newStatus);
        viban = vibanRepository.save(viban);
        
        VirtualAccount va = virtualAccountRepository.findById(viban.getVirtualAccountId()).orElse(null);
        log.info("Updated VIBAN {} status to {}", viban.getViban(), newStatus);
        return toResponse(viban, va);
    }

    /**
     * Lookup VIBAN for routing.
     * CRITICAL: This must be fast (<5ms) for ROBO.
     */
    @Transactional(readOnly = true)
    public VibanLookupResponse lookupViban(String vibanString) {
        long startTime = System.currentTimeMillis();

        Viban viban = vibanRepository.findByViban(vibanString).orElse(null);

        if (viban == null) {
            return VibanLookupResponse.builder()
                .viban(vibanString)
                .isValid(false)
                .validationMessage("VIBAN not found")
                .canAcceptPayment(false)
                .lookupTimeMs(System.currentTimeMillis() - startTime)
                .build();
        }

        // Check if VIBAN has an assigned virtual account
        if (viban.getVirtualAccountId() == null) {
            return VibanLookupResponse.builder()
                .vibanId(viban.getId())
                .viban(vibanString)
                .virtualAccountId(null)
                .programId(viban.getProgramId())
                .isValid(false)
                .validationMessage("VIBAN is not assigned to any virtual account")
                .canAcceptPayment(false)
                .lookupTimeMs(System.currentTimeMillis() - startTime)
                .build();
        }

        // Get VA info - safe to call findById now since we know virtualAccountId is not null
        VirtualAccount va = virtualAccountRepository.findById(viban.getVirtualAccountId()).orElse(null);
        String vaNumber = va != null ? va.getVaNumber() : null;

        // Validate
        boolean isValid = true;
        String validationMessage = "Valid";

        if (!viban.isActive()) {
            isValid = false;
            validationMessage = "VIBAN status: " + viban.getStatus();
        } else if (viban.isExpired()) {
            isValid = false;
            validationMessage = "VIBAN expired";
        } else if (va != null && !va.isActive()) {
            isValid = false;
            validationMessage = "Virtual account not active";
        }

        long lookupTime = System.currentTimeMillis() - startTime;
        if (lookupTime > 5) {
            log.warn("VIBAN lookup took {}ms (target: <5ms) for {}", lookupTime, vibanString);
        }

        return VibanLookupResponse.builder()
            .vibanId(viban.getId())
            .viban(vibanString)
            .virtualAccountId(viban.getVirtualAccountId())
            .vaNumber(vaNumber)
            .programId(viban.getProgramId())
            .hierarchyNodeId(viban.getHierarchyNodeId())
            .isValid(isValid)
            .validationMessage(validationMessage)
            .referenceType(viban.getReferenceType())
            .referenceId(viban.getReferenceId())
            .expectedAmount(viban.getExpectedAmount())
            .canAcceptPayment(isValid)
            .lookupTimeMs(lookupTime)
            .build();
    }

    // ========================================================================
    // Payment Routing - Enhanced with Fee Posting
    // ========================================================================

    /**
     * Route payment via VIBAN with fee posting.
     * 
     * Fee Schedule:
     * - Routing fee: 0.05% of payment amount
     * - Minimum: AED 1
     * - Maximum: AED 50
     */
    @Transactional
    public PaymentRoutingResponse routePayment(String vibanString, BigDecimal amount, 
                                                String remitterName, String remitterAccount,
                                                String paymentReference) {
        // Lookup VIBAN
        VibanLookupResponse lookup = lookupViban(vibanString);
        if (!lookup.getIsValid()) {
            return PaymentRoutingResponse.builder()
                .viban(vibanString)
                .success(false)
                .errorMessage(lookup.getValidationMessage())
                .build();
        }
        
        Viban viban = vibanRepository.findById(lookup.getVibanId())
            .orElseThrow(() -> new ResourceNotFoundException("VIBAN not found"));
        
        VirtualAccount va = virtualAccountRepository.findById(viban.getVirtualAccountId())
            .orElseThrow(() -> new ResourceNotFoundException("VA not found"));
        
        // Validate amount if expected amount is set
        if (viban.getExpectedAmount() != null && viban.getExpectedAmount().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal tolerance = viban.getAmountTolerancePercent() != null ? 
                viban.getAmountTolerancePercent() : BigDecimal.ZERO;
            BigDecimal minAllowed = viban.getExpectedAmount().multiply(BigDecimal.ONE.subtract(tolerance.divide(BigDecimal.valueOf(100))));
            BigDecimal maxAllowed = viban.getExpectedAmount().multiply(BigDecimal.ONE.add(tolerance.divide(BigDecimal.valueOf(100))));
            
            if (amount.compareTo(minAllowed) < 0 || amount.compareTo(maxAllowed) > 0) {
                return PaymentRoutingResponse.builder()
                    .viban(vibanString)
                    .success(false)
                    .errorMessage("Amount " + amount + " outside expected range " + minAllowed + " - " + maxAllowed)
                    .build();
            }
        }
        
        // Calculate routing fee
        BigDecimal routingFee = calculateRoutingFee(amount);
        BigDecimal netAmount = amount.subtract(routingFee);
        
        // Credit VA with net amount
        BigDecimal balanceBefore = va.getCurrentBalance();
        va.setCurrentBalance(balanceBefore.add(netAmount));
        va.setAvailableBalance(va.getCurrentBalance());
        virtualAccountRepository.save(va);
        
        String correlationId = "VIBAN-" + System.currentTimeMillis();
        
        // Record payment transaction
        Transaction txn = Transaction.builder()
            .referenceNumber(Transaction.generateReference(Transaction.MovementType.CREDIT))
            .movementType(Transaction.MovementType.CREDIT)
            .corporateId(va.getCorporateId())
            .vaId(va.getId())
            .physicalAccountId(va.getPhysicalAccountId())
            .programId(va.getProgramId())
            .amount(netAmount)
            .currencyCode(va.getCurrencyCode())
            .balanceBefore(balanceBefore)
            .balanceAfter(va.getCurrentBalance())
            .remitterName(remitterName)
            .remitterAccount(remitterAccount)
            .description("VIBAN payment: " + vibanString)
            .externalReference(paymentReference)
            .correlationId(correlationId)
            .feeAmount(routingFee)
            .status(Transaction.TransactionStatus.COMPLETED)
            .transactionDate(LocalDateTime.now())
            .channel("VIBAN_ROUTING")
            .build();
        txn = transactionRepository.save(txn);
        
        // Update VIBAN usage stats
        viban.recordPayment(amount);
        
        // Handle single-use VIBAN
        if (Boolean.TRUE.equals(viban.getSingleUse())) {
            viban.setStatus(Viban.STATUS_PAID);  // Mark as used
        }
        vibanRepository.save(viban);
        
        // NEW: Post routing fee to Settlement VA
        if (routingFee.compareTo(BigDecimal.ZERO) > 0) {
            postVibanRoutingFee(va, routingFee, txn, correlationId, vibanString);
        }
        
        log.info("Routed payment {} via VIBAN {} to VA {}, net={}, fee={}", 
            amount, vibanString, va.getVaNumber(), netAmount, routingFee);
        
        return PaymentRoutingResponse.builder()
            .viban(vibanString)
            .virtualAccountId(va.getId())
            .vaNumber(va.getVaNumber())
            .grossAmount(amount)
            .netAmount(netAmount)
            .routingFee(routingFee)
            .transactionReference(txn.getReferenceNumber())
            .correlationId(correlationId)
            .success(true)
            .routedAt(LocalDateTime.now())
            .build();
    }
    
    // ========================================================================
    // NEW: FEE POSTING INTEGRATION
    // ========================================================================
    
    /**
     * Post VIBAN issuance fee to Settlement VA.
     * 
     * Fee: AED 10 flat per VIBAN
     */
    private void postVibanIssuanceFee(VirtualAccount va, Viban viban) {
        // Skip fee for pool VIBANs (fees handled at pool level)
        if (viban.getPoolId() != null) {
            return;
        }
        
        try {
            feePostingService.postFee(
                va.getId(),
                VIBAN_ISSUANCE_FEE,
                "VIBAN_ISSUANCE_FEE",
                viban.getId(),
                "VIBAN issuance fee - " + viban.getViban()
            );
            log.debug("Posted VIBAN issuance fee {} for {}", VIBAN_ISSUANCE_FEE, viban.getViban());
        } catch (Exception e) {
            log.error("Failed to post VIBAN issuance fee for {}: {}", viban.getViban(), e.getMessage());
        }
    }
    
    /**
     * Post VIBAN routing fee to Settlement VA.
     * 
     * Fee Schedule:
     * - 0.05% of payment amount
     * - Minimum: AED 1
     * - Maximum: AED 50
     */
    private void postVibanRoutingFee(VirtualAccount va, BigDecimal fee, 
                                      Transaction sourceTransaction, String correlationId,
                                      String vibanString) {
        try {
            feePostingService.postFee(
                va.getId(),
                fee,
                "VIBAN_ROUTING_FEE",
                sourceTransaction.getId(),
                "VIBAN routing fee - " + vibanString
            );
            log.debug("Posted VIBAN routing fee {} for {}", fee, vibanString);
        } catch (Exception e) {
            log.error("Failed to post VIBAN routing fee for {}: {}", vibanString, e.getMessage());
        }
    }
    
    /**
     * Calculate routing fee for VIBAN payment.
     * 
     * @param amount Payment amount
     * @return Routing fee (0.05% of amount, min 1, max 50)
     */
    private BigDecimal calculateRoutingFee(BigDecimal amount) {
        BigDecimal fee = amount.multiply(VIBAN_ROUTING_FEE_RATE)
            .setScale(2, RoundingMode.HALF_UP);
        
        if (fee.compareTo(VIBAN_ROUTING_FEE_MIN) < 0) {
            fee = VIBAN_ROUTING_FEE_MIN;
        }
        if (fee.compareTo(VIBAN_ROUTING_FEE_MAX) > 0) {
            fee = VIBAN_ROUTING_FEE_MAX;
        }
        
        return fee;
    }

    // ========================================================================
    // VIBAN Pool Management
    // ========================================================================

    /**
     * Create a VIBAN pool.
     */
    public PoolResponse createPool(UUID programId, PoolCreateRequest request) {
        Program program = programRepository.findById(programId)
            .orElseThrow(() -> new ResourceNotFoundException("Program not found"));

        VibanPool pool = VibanPool.builder()
            .programId(programId)
            .poolName(request.getPoolName())
            .poolCode(request.getPoolCode())
            .countryCode(marketProfile.getActiveIbanCountryCode())
            .bankCode("0410")   // Default bank code
            .prefix(request.getPrefix())
            .poolSize(request.getPoolSize())
            .availableCount(request.getPoolSize())
            // assignedCount is calculated: poolSize - availableCount - reservedCount
            .assignmentTtlMinutes(request.getAssignmentTtlMinutes() != null ? request.getAssignmentTtlMinutes() : 60)
            .status("ACTIVE")
            .build();

        VibanPool savedPool = poolRepository.save(pool);

        // Generate VIBANs for the pool
        for (int i = 0; i < request.getPoolSize(); i++) {
            Viban viban = Viban.builder()
                .viban(generateVibanForPool(savedPool, i))
                .poolId(savedPool.getId())
                .programId(programId)
                .vibanType(VibanType.TEMPORARY)  // Pool VIBANs use TEMPORARY type
                .status(Viban.STATUS_RETURNED)   // RETURNED indicates available in pool
                .currencyCode(program.getCurrencyCode())
                .build();
            vibanRepository.save(viban);
        }

        log.info("Created VIBAN pool {} with {} VIBANs", request.getPoolCode(), request.getPoolSize());
        return toPoolResponse(savedPool);
    }

    /**
     * Assign VIBAN from pool.
     *
     * Enhanced to support:
     * - Primary VIBAN (permanent, no TTL)
     * - Party Master integration (instead of free-text customerName)
     *
     * @param poolId The pool to assign from
     * @param request Assignment request with VA ID, party ID, etc.
     * @return Assignment response with VIBAN details
     */
    public PoolAssignResponse assignFromPool(UUID poolId, PoolAssignRequest request) {
        VibanPool pool = poolRepository.findById(poolId)
            .orElseThrow(() -> new ResourceNotFoundException("Pool not found"));

        if (pool.getAvailableCount() <= 0) {
            throw new BusinessException("No VIBANs available in pool");
        }

        VirtualAccount va = virtualAccountRepository.findById(request.getVirtualAccountId())
            .orElseThrow(() -> new ResourceNotFoundException("Virtual account not found"));

        // Lookup Party from Party Master (if provided)
        Party party = null;
        String customerName = request.getCustomerName();
        if (request.getPartyId() != null) {
            party = partyRepository.findById(request.getPartyId())
                .orElseThrow(() -> new ResourceNotFoundException("Party not found: " + request.getPartyId()));
            customerName = party.getDisplayName() != null ? party.getDisplayName() : party.getLegalName();
        }

        // Get available VIBAN from pool (RETURNED status indicates available)
        List<Viban> availableVibans = vibanRepository.findAvailableInPool(poolId);
        if (availableVibans.isEmpty()) {
            throw new BusinessException("No available VIBAN in pool");
        }
        Viban viban = availableVibans.get(0);

        LocalDateTime now = LocalDateTime.now();
        boolean isPrimary = Boolean.TRUE.equals(request.getIsPrimary());

        // Primary VIBANs have no TTL (permanent assignment)
        LocalDateTime returnTime = isPrimary ? null : now.plusMinutes(pool.getAssignmentTtlMinutes());

        // Assign VIBAN
        viban.setVirtualAccountId(va.getId());
        viban.setHierarchyNodeId(va.getHierarchyNodeId());
        viban.setVibanType(isPrimary ? VibanType.PRIMARY : VibanType.TEMPORARY);
        viban.setIsPrimary(isPrimary);
        viban.setPartyId(request.getPartyId());
        viban.setReferenceType(request.getReferenceType());
        viban.setReferenceId(request.getReferenceId());
        viban.setExpectedAmount(request.getExpectedAmount());
        viban.setCustomerName(customerName);
        viban.setStatus(Viban.STATUS_ACTIVE);
        viban.setAssignedAt(now);
        viban.setReturnScheduledAt(returnTime);
        viban.setValidFrom(now);
        viban.setValidUntil(returnTime);  // null for primary (permanent)

        vibanRepository.save(viban);

        // Update VA's primary VIBAN reference if this is primary
        if (isPrimary) {
            va.setPrimaryVibanId(viban.getId());
            va.setViban(viban.getViban());
            virtualAccountRepository.save(va);
        }

        // Decrement pool available count (single decrement only)
        poolRepository.decrementAvailable(poolId);

        log.info("Assigned {} VIBAN {} from pool {} to VA {}",
            isPrimary ? "PRIMARY" : "TEMPORARY", viban.getViban(), pool.getPoolCode(), va.getVaNumber());

        return PoolAssignResponse.builder()
            .vibanId(viban.getId())
            .viban(viban.getViban())
            .virtualAccountId(va.getId())
            .vaNumber(va.getVaNumber())
            .partyId(request.getPartyId())
            .partyName(customerName)
            .isPrimary(isPrimary)
            .assignedAt(now)
            .returnScheduledAt(returnTime)
            .build();
    }

    /**
     * Bulk assign VIBANs from pool.
     * Assigns one VIBAN per VA in the request list.
     *
     * @param poolId The pool to assign from
     * @param request Bulk assignment request with list of VAs
     * @return Bulk response with success/failure counts
     */
    @Transactional
    public BulkAssignResponse bulkAssignFromPool(UUID poolId, BulkAssignRequest request) {
        VibanPool pool = poolRepository.findById(poolId)
            .orElseThrow(() -> new ResourceNotFoundException("Pool not found"));

        if (request.getAssignments() == null || request.getAssignments().isEmpty()) {
            throw new BusinessException("No assignments provided");
        }

        int requestCount = request.getAssignments().size();
        if (pool.getAvailableCount() < requestCount) {
            throw new BusinessException("Not enough VIBANs available. Requested: " + requestCount
                + ", Available: " + pool.getAvailableCount());
        }

        java.util.List<PoolAssignResponse> successful = new java.util.ArrayList<>();
        java.util.List<BulkAssignError> failed = new java.util.ArrayList<>();

        for (BulkAssignItem item : request.getAssignments()) {
            try {
                PoolAssignRequest assignRequest = PoolAssignRequest.builder()
                    .virtualAccountId(item.getVirtualAccountId())
                    .partyId(item.getPartyId())
                    .referenceType(item.getReferenceType())
                    .referenceId(item.getReferenceId())
                    .expectedAmount(item.getExpectedAmount())
                    .isPrimary(item.getIsPrimary())
                    .build();

                PoolAssignResponse response = assignFromPool(poolId, assignRequest);
                successful.add(response);
            } catch (Exception e) {
                log.error("Failed to assign VIBAN to VA {}: {}", item.getVirtualAccountId(), e.getMessage());
                failed.add(BulkAssignError.builder()
                    .virtualAccountId(item.getVirtualAccountId())
                    .error(e.getMessage())
                    .build());
            }
        }

        log.info("Bulk assignment completed: {} successful, {} failed out of {} requested",
            successful.size(), failed.size(), requestCount);

        return BulkAssignResponse.builder()
            .totalRequested(requestCount)
            .successCount(successful.size())
            .failedCount(failed.size())
            .successful(successful)
            .failed(failed)
            .build();
    }

    /**
     * Assign VIBAN for an Invoice (Accounts Receivable integration).
     * Creates a VIBAN tied to a specific invoice for auto-reconciliation.
     *
     * @param poolId The pool to assign from
     * @param request Invoice VIBAN request
     * @return Invoice VIBAN response with payment details
     */
    @Transactional
    public InvoiceVibanResponse assignVibanForInvoice(UUID poolId, InvoiceVibanRequest request) {
        VibanPool pool = poolRepository.findById(poolId)
            .orElseThrow(() -> new ResourceNotFoundException("Pool not found"));

        if (pool.getAvailableCount() <= 0) {
            throw new BusinessException("No VIBANs available in pool");
        }

        // Get or determine the collection VA
        VirtualAccount va;
        if (request.getVirtualAccountId() != null) {
            va = virtualAccountRepository.findById(request.getVirtualAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Virtual account not found"));
        } else {
            // Use default collection VA for the pool's program
            List<VirtualAccount> collectionVAs = virtualAccountRepository.findByProgramIdAndStatus(
                pool.getProgramId(), VirtualAccount.VaStatus.ACTIVE);
            if (collectionVAs.isEmpty()) {
                throw new BusinessException("No active collection VA found for program");
            }
            va = collectionVAs.get(0);
        }

        // Lookup Party (Customer/Debtor)
        Party party = partyRepository.findById(request.getPartyId())
            .orElseThrow(() -> new ResourceNotFoundException("Party not found: " + request.getPartyId()));

        // Calculate valid until based on due date (add grace period)
        LocalDateTime validUntil = request.getDueDate() != null
            ? request.getDueDate().plusDays(30)  // 30 days grace after due date
            : LocalDateTime.now().plusDays(90);  // Default 90 days

        // Create VIBAN assignment request
        PoolAssignRequest assignRequest = PoolAssignRequest.builder()
            .virtualAccountId(va.getId())
            .partyId(request.getPartyId())
            .referenceType("INVOICE")
            .referenceId(request.getInvoiceNumber())
            .expectedAmount(request.getInvoiceAmount())
            .isPrimary(false)  // Invoice VIBANs are always temporary
            .build();

        // Assign the VIBAN
        PoolAssignResponse assignResponse = assignFromPool(poolId, assignRequest);

        // Update the VIBAN with invoice-specific details
        Viban viban = vibanRepository.findById(assignResponse.getVibanId())
            .orElseThrow(() -> new ResourceNotFoundException("VIBAN not found after assignment"));

        viban.setValidUntil(validUntil);
        viban.setReturnScheduledAt(validUntil);
        viban.setCurrencyCode(request.getCurrencyCode());
        vibanRepository.save(viban);

        // Generate payment link (placeholder - integrate with payment gateway)
        String paymentLink = generatePaymentLink(viban.getViban(), request.getInvoiceAmount(), request.getCurrencyCode());

        log.info("Assigned VIBAN {} for invoice {} from party {}",
            viban.getViban(), request.getInvoiceNumber(), party.getPartyCode());

        return InvoiceVibanResponse.builder()
            .invoiceId(request.getInvoiceId())
            .invoiceNumber(request.getInvoiceNumber())
            .vibanId(viban.getId())
            .viban(viban.getViban())
            .virtualAccountId(va.getId())
            .vaNumber(va.getVaNumber())
            .partyId(party.getId())
            .partyName(party.getDisplayName() != null ? party.getDisplayName() : party.getLegalName())
            .expectedAmount(request.getInvoiceAmount())
            .currencyCode(request.getCurrencyCode())
            .validUntil(validUntil)
            .paymentLink(paymentLink)
            .build();
    }

    /**
     * Generate a payment link for invoice payment.
     * Placeholder - integrate with actual payment gateway.
     */
    private String generatePaymentLink(String viban, BigDecimal amount, String currency) {
        // TODO: Integrate with payment gateway
        return String.format("https://pay.bank.com/v/%s?amount=%s&currency=%s",
            viban, amount != null ? amount.toPlainString() : "0", currency != null ? currency : marketProfile.getDefaultCurrency());
    }

    /**
     * Return VIBAN to pool.
     */
    public void returnToPool(UUID vibanId) {
        Viban viban = vibanRepository.findById(vibanId)
            .orElseThrow(() -> new ResourceNotFoundException("VIBAN not found"));

        if (viban.getPoolId() == null) {
            throw new BusinessException("VIBAN is not from a pool");
        }

        viban.returnToPool();
        vibanRepository.save(viban);

        // Increment pool available count
        poolRepository.incrementAvailable(viban.getPoolId());

        log.info("Returned VIBAN {} to pool", viban.getViban());
    }

    /**
     * Get pool details.
     */
    @Transactional(readOnly = true)
    public PoolResponse getPool(UUID poolId) {
        VibanPool pool = poolRepository.findById(poolId)
            .orElseThrow(() -> new ResourceNotFoundException("Pool not found"));
        return toPoolResponse(pool);
    }

    /**
     * Get pools for program.
     */
    @Transactional(readOnly = true)
    public List<PoolResponse> getPoolsForProgram(UUID programId) {
        return poolRepository.findByProgramId(programId)
            .stream()
            .map(this::toPoolResponse)
            .collect(Collectors.toList());
    }

    // ========================================================================
    // Scheduled jobs
    // ========================================================================

    /**
     * Process expired VIBANs - mark as expired or return to pool.
     */
    @Scheduled(fixedRate = 60000) // Every minute
    @Transactional
    public void processExpiredVibans() {
        LocalDateTime now = LocalDateTime.now();
        
        // Mark expired VIBANs
        int expired = vibanRepository.markExpired(now);
        if (expired > 0) {
            log.info("Marked {} VIBANs as expired", expired);
        }

        // Return pool VIBANs scheduled for return
        List<Viban> toReturn = vibanRepository.findScheduledForReturn(now);
        for (Viban viban : toReturn) {
            try {
                returnToPool(viban.getId());
            } catch (Exception e) {
                log.error("Error returning VIBAN {} to pool: {}", viban.getViban(), e.getMessage());
            }
        }
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private String generateViban(Program program) {
        String countryCode = marketProfile.getActiveIbanCountryCode();
        String bankCode = program.getVibanBankCode() != null ? program.getVibanBankCode() : "0410";
        String prefix = program.getVibanPrefix() != null ? program.getVibanPrefix() : "00";
        
        long sequence = vibanCounter.incrementAndGet();
        String accountNumber = prefix + String.format("%016d", sequence);
        
        String checkDigits = calculateCheckDigits(countryCode, bankCode, accountNumber);
        
        return countryCode + checkDigits + bankCode + accountNumber;
    }

    private String generateVibanForPool(VibanPool pool, int index) {
        String countryCode = pool.getCountryCode();
        String bankCode = pool.getBankCode();
        String prefix = pool.getPrefix();
        
        String accountNumber = prefix + String.format("%010d", index);
        String checkDigits = calculateCheckDigits(countryCode, bankCode, accountNumber);
        
        return countryCode + checkDigits + bankCode + accountNumber;
    }

    private String calculateCheckDigits(String countryCode, String bankCode, String accountNumber) {
        String bban = bankCode + accountNumber;
        String rearranged = bban + countryCode + "00";
        
        StringBuilder numeric = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            if (Character.isLetter(c)) {
                numeric.append(Character.toUpperCase(c) - 'A' + 10);
            } else {
                numeric.append(c);
            }
        }
        
        java.math.BigInteger numericValue = new java.math.BigInteger(numeric.toString());
        int remainder = numericValue.mod(java.math.BigInteger.valueOf(97)).intValue();
        int checkDigit = 98 - remainder;
        
        return String.format("%02d", checkDigit);
    }

    private VibanResponse toResponse(Viban viban, VirtualAccount va) {
        return VibanResponse.builder()
            .id(viban.getId())
            .viban(viban.getViban())
            .virtualAccountId(viban.getVirtualAccountId())
            .vaNumber(va != null ? va.getVaNumber() : null)
            .programId(viban.getProgramId())
            .vibanType(viban.getVibanType())
            .isPrimary(viban.getIsPrimary())
            .referenceType(viban.getReferenceType())
            .referenceId(viban.getReferenceId())
            .status(viban.getStatus())
            .validFrom(viban.getValidFrom())
            .validUntil(viban.getValidUntil())
            .singleUse(viban.getSingleUse())
            .timesUsed(viban.getTimesUsed())
            .totalAmountReceived(viban.getTotalAmountReceived())
            .expectedAmount(viban.getExpectedAmount())
            .remainingAmount(viban.getRemainingAmount())
            .currencyCode(viban.getCurrencyCode())
            .customerName(viban.getCustomerName())
            .purpose(viban.getPurpose())
            .poolId(viban.getPoolId())
            .createdAt(viban.getCreatedAt())
            .build();
    }

    private PoolResponse toPoolResponse(VibanPool pool) {
        return PoolResponse.builder()
            .id(pool.getId())
            .programId(pool.getProgramId())
            .poolName(pool.getPoolName())
            .poolCode(pool.getPoolCode())
            .prefix(pool.getPrefix())
            .poolSize(pool.getPoolSize())
            .availableCount(pool.getAvailableCount())
            .assignedCount(pool.getAssignedCount())
            .utilizationPercent(pool.getUtilizationPercent())
            .assignmentTtlMinutes(pool.getAssignmentTtlMinutes())
            .status(pool.getStatus())
            .createdAt(pool.getCreatedAt())
            .build();
    }
    
    // ========================================================================
    // NEW: Response DTOs for routing
    // ========================================================================
    
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PaymentRoutingResponse {
        private String viban;
        private UUID virtualAccountId;
        private String vaNumber;
        private BigDecimal grossAmount;
        private BigDecimal netAmount;
        private BigDecimal routingFee;
        private String transactionReference;
        private String correlationId;
        private boolean success;
        private String errorMessage;
        private LocalDateTime routedAt;
    }
}