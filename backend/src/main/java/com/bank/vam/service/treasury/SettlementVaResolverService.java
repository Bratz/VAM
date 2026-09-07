package com.bank.vam.service.treasury;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.entity.VirtualAccount.AccountType;
import com.bank.vam.entity.VirtualAccount.VaSpecialType;
import com.bank.vam.entity.VirtualAccount.VaStatus;
import com.bank.vam.entity.Program;
import com.bank.vam.entity.treasury.ExceptionTransaction;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.ProgramRepository;
import com.bank.vam.repository.treasury.ExceptionTransactionRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SettlementVaResolverService - Pure Virtual Architecture v5.2.0
 *
 * PURPOSE:
 * Resolves the appropriate Settlement VA for fee/charge posting.
 *
 * CRITICAL CHANGE (v5.2.0):
 * =========================
 * Settlement VAs are NO LONGER auto-created.
 * Settlement VAs MUST be pre-provisioned during program setup.
 *
 * If Settlement VA is not found:
 * 1. Transaction is parked in Exception VA
 * 2. Exception record is created with type MISSING_SETTLEMENT_VA
 * 3. Operations team must investigate and create Settlement VA manually
 *
 * RESOLUTION ORDER:
 * 1. Find existing Settlement VA for (programId, currency)
 * 2. If not found → Return Exception VA with exception raised
 * 3. Exception VA CAN be auto-created as it's a safety net
 *
 * RATIONALE:
 * ===========
 * - Settlement VAs require proper governance and approval workflow
 * - Auto-creation hides configuration errors
 * - Missing Settlement VA indicates improper program setup
 * - Operations team needs visibility into missing configurations
 *
 * Exception VA (Fallback - CAN be auto-created):
 * - Scope: Per Program + Per Currency (or Per Corporate if no program)
 * - Naming: EXCEPTION-{PROGRAM_CODE}-{CURRENCY}
 * - Parent: null (program level)
 * - Category: EXCEPTION
 * - SpecialType: EXCEPTION
 * - physicalAccountId: NULL (purely virtual)
 *
 * BACKWARD COMPATIBILITY:
 * =======================
 * The old resolveSettlementVa() method is preserved but returns a wrapper.
 * Use resolveSettlementVaWithResult() for new code that needs to handle
 * the exception case properly.
 *
 * PURE VIRTUAL PRINCIPLE:
 * =======================
 * Settlement and Exception VAs are purely virtual.
 * They have NO physicalAccountId - they don't represent real bank accounts.
 * They are aggregation points for fees, charges, and unmatched transactions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementVaResolverService {

    private final VirtualAccountRepository vaRepository;
    private final ProgramRepository programRepository;
    private final ExceptionTransactionRepository exceptionRepository;

    // Naming conventions
    private static final String SETTLEMENT_VA_PREFIX = "SETTLEMENT";
    private static final String EXCEPTION_VA_PREFIX = "EXCEPTION";

    // ========================================================================
    // RESULT WRAPPER (v5.2.0)
    // ========================================================================

    /**
     * Result wrapper for Settlement VA resolution.
     * Indicates whether a Settlement VA was found or if transaction was parked in Exception VA.
     */
    @Data
    @Builder
    public static class SettlementVaResolutionResult {
        /**
         * The resolved VA (either Settlement VA or Exception VA).
         */
        private VirtualAccount resolvedVa;

        /**
         * True if a Settlement VA was found, false if using Exception VA as fallback.
         */
        private boolean settlementVaFound;

        /**
         * Exception record created when Settlement VA not found.
         * Null if Settlement VA was found successfully.
         */
        private ExceptionTransaction exceptionRaised;

        /**
         * Reason for failure (when Settlement VA not found).
         */
        private String failureReason;

        /**
         * Create success result when Settlement VA is found.
         */
        public static SettlementVaResolutionResult success(VirtualAccount settlementVa) {
            return SettlementVaResolutionResult.builder()
                .resolvedVa(settlementVa)
                .settlementVaFound(true)
                .build();
        }

        /**
         * Create failure result when Settlement VA not found.
         * Transaction will be parked in Exception VA.
         */
        public static SettlementVaResolutionResult failedWithException(
                VirtualAccount exceptionVa,
                ExceptionTransaction exception,
                String reason) {
            return SettlementVaResolutionResult.builder()
                .resolvedVa(exceptionVa)
                .settlementVaFound(false)
                .exceptionRaised(exception)
                .failureReason(reason)
                .build();
        }

        /**
         * Check if resolution was successful (Settlement VA found).
         */
        public boolean isSuccess() {
            return settlementVaFound;
        }

        /**
         * Check if transaction needs to be parked in exception.
         */
        public boolean requiresExceptionHandling() {
            return !settlementVaFound;
        }
    }

    // ========================================================================
    // PRIMARY RESOLUTION METHOD (v5.3.0 - HIERARCHY TRAVERSAL + LEGAL ENTITY VALIDATION)
    // ========================================================================

    /**
     * Resolve Settlement VA for a source VA with full result details.
     *
     * ENHANCED BEHAVIOR (v5.3.0):
     * ===========================
     * 1. First check for Settlement VA as SIBLING of the transaction VA (same parent)
     * 2. If not found, TRAVERSE UP the hierarchy checking each level for Settlement VA
     * 3. At each level, validate LEGAL ENTITY OWNERSHIP matches
     * 4. If legal entity mismatch → park in Exception VA (ENTITY_MISMATCH)
     * 5. If not found at any level → park in Exception VA (MISSING_SETTLEMENT_VA)
     *
     * Settlement VAs MUST be pre-provisioned during program setup.
     *
     * @param sourceVa The VA that needs fee posting
     * @return SettlementVaResolutionResult with Settlement VA or Exception VA + exception details
     */
    @Transactional
    public SettlementVaResolutionResult resolveSettlementVaWithResult(VirtualAccount sourceVa) {
        return resolveSettlementVaWithResult(sourceVa, null, null, null);
    }

    /**
     * Resolve Settlement VA with transaction context for exception creation.
     *
     * @param sourceVa The VA that needs fee posting
     * @param amount Transaction amount (for exception record)
     * @param transactionId Original transaction ID (for exception record)
     * @return SettlementVaResolutionResult
     */
    @Transactional
    public SettlementVaResolutionResult resolveSettlementVaWithResult(
            VirtualAccount sourceVa, BigDecimal amount, UUID transactionId) {
        return resolveSettlementVaWithResult(sourceVa, amount, transactionId, null);
    }

    /**
     * Resolve Settlement VA for CROSS-CURRENCY transactions.
     *
     * For cross-currency transfers (e.g., GBP VA → USD VA):
     * - sourceVa: The VA initiating the transfer (GBP VA)
     * - contraVa: The counterparty VA (USD VA)
     * - Each side needs its own currency-specific Settlement VA
     *
     * Legal Entity Validation:
     * - If Settlement VA's owningEntityId differs from contraVa's owningEntityId
     * - Transaction is parked in Exception VA with ENTITY_MISMATCH type
     *
     * @param sourceVa The VA that needs fee posting
     * @param amount Transaction amount (for exception record)
     * @param transactionId Original transaction ID (for exception record)
     * @param contraVa The counterparty VA (for cross-currency and entity validation)
     * @return SettlementVaResolutionResult
     */
    @Transactional
    public SettlementVaResolutionResult resolveSettlementVaWithResult(
            VirtualAccount sourceVa, BigDecimal amount, UUID transactionId, VirtualAccount contraVa) {

        log.debug("Resolving Settlement VA for source: {}, program: {}, currency: {}, contra: {}",
            sourceVa.getVaNumber(), sourceVa.getProgramId(), sourceVa.getCurrencyCode(),
            contraVa != null ? contraVa.getVaNumber() : "N/A");

        // Determine if this is a cross-currency transaction
        boolean isCrossCurrency = contraVa != null &&
            !sourceVa.getCurrencyCode().equals(contraVa.getCurrencyCode());

        if (isCrossCurrency) {
            log.info("Cross-currency transaction detected: {} ({}) → {} ({})",
                sourceVa.getVaNumber(), sourceVa.getCurrencyCode(),
                contraVa.getVaNumber(), contraVa.getCurrencyCode());
        }

        // =====================================================================
        // STEP 1: Check for Settlement VA as SIBLING (same parent as sourceVa)
        // =====================================================================
        if (sourceVa.getParentAccountId() != null) {
            Optional<VirtualAccount> siblingSettlement = findSettlementVaByParent(
                sourceVa.getParentAccountId(), sourceVa.getCurrencyCode());

            if (siblingSettlement.isPresent()) {
                VirtualAccount settlementVa = siblingSettlement.get();
                log.debug("Found sibling Settlement VA: {}", settlementVa.getVaNumber());

                // Validate legal entity ownership if contra VA provided
                SettlementVaResolutionResult entityValidation =
                    validateLegalEntityOwnership(settlementVa, sourceVa, contraVa, amount, transactionId);
                if (entityValidation != null) {
                    return entityValidation; // Entity mismatch - parked in Exception VA
                }

                return SettlementVaResolutionResult.success(settlementVa);
            }
        }

        // =====================================================================
        // STEP 2: TRAVERSE UP the hierarchy looking for Settlement VA
        // =====================================================================
        Optional<VirtualAccount> hierarchySettlement = traverseHierarchyForSettlementVa(
            sourceVa, sourceVa.getCurrencyCode());

        if (hierarchySettlement.isPresent()) {
            VirtualAccount settlementVa = hierarchySettlement.get();
            log.debug("Found Settlement VA in hierarchy: {}", settlementVa.getVaNumber());

            // Validate legal entity ownership
            SettlementVaResolutionResult entityValidation =
                validateLegalEntityOwnership(settlementVa, sourceVa, contraVa, amount, transactionId);
            if (entityValidation != null) {
                return entityValidation;
            }

            return SettlementVaResolutionResult.success(settlementVa);
        }

        // =====================================================================
        // STEP 3: Fallback to program-level Settlement VA
        // =====================================================================
        Optional<VirtualAccount> programSettlement = findSettlementVa(
            sourceVa.getProgramId(), sourceVa.getCurrencyCode());

        if (programSettlement.isPresent()) {
            VirtualAccount settlementVa = programSettlement.get();
            log.debug("Found program-level Settlement VA: {}", settlementVa.getVaNumber());

            // Validate legal entity ownership
            SettlementVaResolutionResult entityValidation =
                validateLegalEntityOwnership(settlementVa, sourceVa, contraVa, amount, transactionId);
            if (entityValidation != null) {
                return entityValidation;
            }

            return SettlementVaResolutionResult.success(settlementVa);
        }

        // =====================================================================
        // STEP 4: Fallback to corporate-level Settlement VA
        // =====================================================================
        if (sourceVa.getCorporateId() != null) {
            Optional<VirtualAccount> corporateSettlement = findSettlementVaByCorporate(
                sourceVa.getCorporateId(), sourceVa.getCurrencyCode());

            if (corporateSettlement.isPresent()) {
                VirtualAccount settlementVa = corporateSettlement.get();
                log.debug("Found corporate-level Settlement VA: {}", settlementVa.getVaNumber());

                // Validate legal entity ownership
                SettlementVaResolutionResult entityValidation =
                    validateLegalEntityOwnership(settlementVa, sourceVa, contraVa, amount, transactionId);
                if (entityValidation != null) {
                    return entityValidation;
                }

                return SettlementVaResolutionResult.success(settlementVa);
            }
        }

        // =====================================================================
        // STEP 5: Settlement VA NOT FOUND - Park in Exception VA
        // =====================================================================
        String failureReason = String.format(
            "CONFIGURATION ERROR: Settlement VA not found for program %s currency %s. " +
            "Searched: sibling level, hierarchy traversal, program level, corporate level. " +
            "Settlement VA must be created during program setup.",
            sourceVa.getProgramId(), sourceVa.getCurrencyCode());

        log.warn(failureReason);

        VirtualAccount exceptionVa = resolveOrCreateExceptionVa(sourceVa);
        ExceptionTransaction exception = createMissingSettlementVaException(
            sourceVa, exceptionVa, amount, transactionId);

        log.error("Transaction parked in Exception VA {} due to missing Settlement VA. " +
                  "Exception ID: {}, Program: {}, Currency: {}",
            exceptionVa.getVaNumber(), exception.getExceptionNumber(),
            sourceVa.getProgramId(), sourceVa.getCurrencyCode());

        return SettlementVaResolutionResult.failedWithException(
            exceptionVa, exception, failureReason);
    }

    // ========================================================================
    // HIERARCHY TRAVERSAL (v5.3.0)
    // ========================================================================

    /**
     * Traverse up the VA hierarchy looking for a Settlement VA at each level.
     *
     * @param startVa The VA to start traversing from
     * @param currency The currency to match
     * @return Settlement VA if found, empty otherwise
     */
    private Optional<VirtualAccount> traverseHierarchyForSettlementVa(VirtualAccount startVa, String currency) {
        UUID currentParentId = startVa.getParentAccountId();
        int maxDepth = 10; // Prevent infinite loops
        int depth = 0;

        while (currentParentId != null && depth < maxDepth) {
            depth++;

            // Check for Settlement VA at this level (as sibling of current parent)
            Optional<VirtualAccount> settlementAtLevel = findSettlementVaByParent(currentParentId, currency);
            if (settlementAtLevel.isPresent()) {
                log.debug("Found Settlement VA at hierarchy depth {}: {}",
                    depth, settlementAtLevel.get().getVaNumber());
                return settlementAtLevel;
            }

            // Move up to the next level
            Optional<VirtualAccount> parentVa = vaRepository.findById(currentParentId);
            if (parentVa.isEmpty()) {
                log.warn("Parent VA not found during hierarchy traversal: {}", currentParentId);
                break;
            }

            currentParentId = parentVa.get().getParentAccountId();
        }

        if (depth >= maxDepth) {
            log.warn("Max hierarchy depth reached during Settlement VA search");
        }

        return Optional.empty();
    }

    /**
     * Find Settlement VA that is a sibling (same parent) of the given parent.
     *
     * @param parentVaId The parent VA ID
     * @param currency The currency to match
     * @return Settlement VA if found
     */
    public Optional<VirtualAccount> findSettlementVaByParent(UUID parentVaId, String currency) {
        return vaRepository.findByParentAccountIdAndAccountCategory(parentVaId, AccountCategory.SETTLEMENT)
            .stream()
            .filter(va -> currency.equals(va.getCurrencyCode()))
            .filter(va -> va.getStatus() == VaStatus.ACTIVE)
            .findFirst();
    }

    // ========================================================================
    // LEGAL ENTITY VALIDATION (v5.3.0)
    // ========================================================================

    /**
     * Validate that Settlement VA's legal entity matches the transaction parties.
     *
     * Business Rule:
     * - If Settlement VA belongs to a different legal entity than the contra VA,
     *   the transaction must be parked in Exception VA for manual review.
     * - This prevents incorrect fee allocation across legal entities.
     *
     * @param settlementVa The found Settlement VA
     * @param sourceVa The source VA initiating the transaction
     * @param contraVa The counterparty VA (if applicable)
     * @param amount Transaction amount
     * @param transactionId Original transaction ID
     * @return SettlementVaResolutionResult if entity mismatch (parked in exception), null if OK
     */
    private SettlementVaResolutionResult validateLegalEntityOwnership(
            VirtualAccount settlementVa, VirtualAccount sourceVa,
            VirtualAccount contraVa, BigDecimal amount, UUID transactionId) {

        // If no contra VA, no cross-entity validation needed
        if (contraVa == null) {
            return null;
        }

        UUID settlementEntityId = settlementVa.getOwningEntityId();
        UUID contraEntityId = contraVa.getOwningEntityId();

        // If either entity ID is null, skip validation (legacy data)
        if (settlementEntityId == null || contraEntityId == null) {
            log.debug("Skipping entity validation - Settlement entity: {}, Contra entity: {}",
                settlementEntityId, contraEntityId);
            return null;
        }

        // Check if entities match
        if (!settlementEntityId.equals(contraEntityId)) {
            log.warn("ENTITY MISMATCH: Settlement VA {} belongs to entity {}, " +
                     "but contra VA {} belongs to entity {}",
                settlementVa.getVaNumber(), settlementEntityId,
                contraVa.getVaNumber(), contraEntityId);

            // Park in Exception VA with ENTITY_MISMATCH type
            VirtualAccount exceptionVa = resolveOrCreateExceptionVa(sourceVa);
            ExceptionTransaction exception = createEntityMismatchException(
                sourceVa, contraVa, settlementVa, exceptionVa, amount, transactionId);

            String failureReason = String.format(
                "ENTITY MISMATCH: Settlement VA %s (entity %s) does not match contra VA %s (entity %s). " +
                "Transaction parked for manual review.",
                settlementVa.getVaNumber(), settlementEntityId,
                contraVa.getVaNumber(), contraEntityId);

            return SettlementVaResolutionResult.failedWithException(
                exceptionVa, exception, failureReason);
        }

        log.debug("Entity validation passed: Settlement and contra VA belong to same entity {}",
            settlementEntityId);
        return null; // Validation passed
    }

    /**
     * Create exception record for legal entity mismatch.
     */
    private ExceptionTransaction createEntityMismatchException(
            VirtualAccount sourceVa, VirtualAccount contraVa, VirtualAccount settlementVa,
            VirtualAccount exceptionVa, BigDecimal amount, UUID transactionId) {

        String description = String.format(
            "Legal entity mismatch detected during Settlement VA resolution. " +
            "Source VA: %s (entity: %s), Contra VA: %s (entity: %s), " +
            "Settlement VA: %s (entity: %s). " +
            "Transaction parked pending manual review and entity alignment.",
            sourceVa.getVaNumber(), sourceVa.getOwningEntityId(),
            contraVa.getVaNumber(), contraVa.getOwningEntityId(),
            settlementVa.getVaNumber(), settlementVa.getOwningEntityId());

        ExceptionTransaction exception = ExceptionTransaction.builder()
            .exceptionNumber(ExceptionTransaction.generateExceptionNumber())
            .exceptionType(ExceptionTransaction.ExceptionType.SYSTEM_ERROR) // Using SYSTEM_ERROR for entity mismatch
            .status(ExceptionTransaction.ExceptionStatus.OPEN)
            .priority(ExceptionTransaction.ExceptionPriority.HIGH)
            .programId(sourceVa.getProgramId())
            .exceptionVaId(exceptionVa.getId())
            .originalVaId(sourceVa.getId())
            .originalTransactionId(transactionId)
            .amount(amount != null ? amount : BigDecimal.ZERO)
            .currencyCode(sourceVa.getCurrencyCode())
            .description(description)
            .remitterInfo("Source: " + sourceVa.getVaNumber() +
                         ", Contra: " + contraVa.getVaNumber() +
                         ", Settlement: " + settlementVa.getVaNumber() +
                         ", ENTITY_MISMATCH")
            .build();

        exception = exceptionRepository.save(exception);

        log.info("Created ENTITY_MISMATCH exception: {} for transaction between entities {} and {}",
            exception.getExceptionNumber(), sourceVa.getOwningEntityId(), contraVa.getOwningEntityId());

        return exception;
    }

    // ========================================================================
    // CROSS-CURRENCY RESOLUTION (v5.3.0)
    // ========================================================================

    /**
     * Resolve Settlement VAs for BOTH sides of a cross-currency transaction.
     *
     * For a GBP → USD transfer:
     * - Source side needs GBP Settlement VA (for GBP fees)
     * - Destination side needs USD Settlement VA (for USD fees/FX settlement)
     *
     * @param sourceVa The source VA (e.g., GBP VA)
     * @param destVa The destination VA (e.g., USD VA)
     * @param amount Transaction amount
     * @param transactionId Transaction ID
     * @return CrossCurrencySettlementResult with both Settlement VAs or exceptions
     */
    @Transactional
    public CrossCurrencySettlementResult resolveCrossCurrencySettlementVas(
            VirtualAccount sourceVa, VirtualAccount destVa, BigDecimal amount, UUID transactionId) {

        log.info("Resolving Settlement VAs for cross-currency: {} ({}) → {} ({})",
            sourceVa.getVaNumber(), sourceVa.getCurrencyCode(),
            destVa.getVaNumber(), destVa.getCurrencyCode());

        // Resolve source currency Settlement VA
        SettlementVaResolutionResult sourceResult = resolveSettlementVaWithResult(
            sourceVa, amount, transactionId, destVa);

        // Resolve destination currency Settlement VA
        SettlementVaResolutionResult destResult = resolveSettlementVaWithResult(
            destVa, amount, transactionId, sourceVa);

        return CrossCurrencySettlementResult.builder()
            .sourceSettlementResult(sourceResult)
            .destSettlementResult(destResult)
            .sourceCurrency(sourceVa.getCurrencyCode())
            .destCurrency(destVa.getCurrencyCode())
            .build();
    }

    /**
     * Result wrapper for cross-currency Settlement VA resolution.
     */
    @Data
    @Builder
    public static class CrossCurrencySettlementResult {
        private SettlementVaResolutionResult sourceSettlementResult;
        private SettlementVaResolutionResult destSettlementResult;
        private String sourceCurrency;
        private String destCurrency;

        /**
         * Check if both Settlement VAs were found successfully.
         */
        public boolean isFullyResolved() {
            return sourceSettlementResult.isSuccess() && destSettlementResult.isSuccess();
        }

        /**
         * Check if source side Settlement VA was found.
         */
        public boolean isSourceResolved() {
            return sourceSettlementResult.isSuccess();
        }

        /**
         * Check if destination side Settlement VA was found.
         */
        public boolean isDestResolved() {
            return destSettlementResult.isSuccess();
        }

        /**
         * Get all exceptions raised (if any).
         */
        public List<ExceptionTransaction> getExceptions() {
            List<ExceptionTransaction> exceptions = new java.util.ArrayList<>();
            if (sourceSettlementResult.getExceptionRaised() != null) {
                exceptions.add(sourceSettlementResult.getExceptionRaised());
            }
            if (destSettlementResult.getExceptionRaised() != null) {
                exceptions.add(destSettlementResult.getExceptionRaised());
            }
            return exceptions;
        }
    }

    /**
     * Resolve Settlement VA for a source VA.
     *
     * @deprecated Use {@link #resolveSettlementVaWithResult(VirtualAccount)} instead
     *             to properly handle missing Settlement VA cases.
     *
     * BACKWARD COMPATIBILITY:
     * This method is preserved for backward compatibility but now returns
     * Exception VA when Settlement VA is not found (instead of auto-creating).
     *
     * @param sourceVa The VA that needs fee posting
     * @return Settlement VA if found, or Exception VA as fallback
     */
    @Deprecated
    @Transactional
    public VirtualAccount resolveSettlementVa(VirtualAccount sourceVa) {
        SettlementVaResolutionResult result = resolveSettlementVaWithResult(sourceVa);

        if (!result.isSuccess()) {
            log.warn("resolveSettlementVa() returning Exception VA as fallback. " +
                     "Consider using resolveSettlementVaWithResult() for proper exception handling. " +
                     "Exception: {}", result.getFailureReason());
        }

        return result.getResolvedVa();
    }

    /**
     * Create exception record for missing Settlement VA.
     */
    private ExceptionTransaction createMissingSettlementVaException(
            VirtualAccount sourceVa, VirtualAccount exceptionVa,
            BigDecimal amount, UUID transactionId) {

        String description = String.format(
            "Settlement VA not configured for program %s currency %s. " +
            "Transaction from VA %s parked pending Settlement VA creation. " +
            "This is a configuration error - Settlement VA must be provisioned during program setup.",
            getProgramCode(sourceVa.getProgramId()),
            sourceVa.getCurrencyCode(),
            sourceVa.getVaNumber());

        ExceptionTransaction exception = ExceptionTransaction.builder()
            .exceptionNumber(ExceptionTransaction.generateExceptionNumber())
            .exceptionType(ExceptionTransaction.ExceptionType.MISSING_SETTLEMENT_VA)
            .status(ExceptionTransaction.ExceptionStatus.OPEN)
            .priority(ExceptionTransaction.ExceptionPriority.HIGH) // High priority - config error
            .programId(sourceVa.getProgramId())
            // Note: ExceptionTransaction doesn't have corporateId - tracked via programId
            .exceptionVaId(exceptionVa.getId())
            .originalVaId(sourceVa.getId())
            // Deliberately NOT originalTransactionId(transactionId): every caller of
            // resolveSettlementVaWithResult evaluates this BEFORE creating any debit/credit
            // Transaction (it's a pre-check), so `transactionId` here is either null or a
            // caller-supplied correlation/reference id — never a persisted va_movements row.
            // Writing it into this FK-to-va_movements column deterministically violates the
            // constraint whenever it happens to look like a real UUID, aborting the whole
            // surrounding @Transactional call instead of gracefully parking the exception
            // (its entire purpose). The reference is preserved below in free-text remitterInfo
            // instead, where it doesn't need to resolve to a real row.
            .amount(amount != null ? amount : BigDecimal.ZERO)
            .currencyCode(sourceVa.getCurrencyCode())
            .description(description)
            .remitterInfo("Program: " + getProgramCode(sourceVa.getProgramId()) +
                         ", Currency: " + sourceVa.getCurrencyCode() +
                         ", Source VA: " + sourceVa.getVaNumber() +
                         ", Corporate: " + sourceVa.getCorporateId() +
                         (transactionId != null ? ", Reference: " + transactionId : ""))
            .build();

        exception = exceptionRepository.save(exception);

        log.info("Created MISSING_SETTLEMENT_VA exception: {} for program {} currency {}",
            exception.getExceptionNumber(), sourceVa.getProgramId(), sourceVa.getCurrencyCode());

        return exception;
    }

    /**
     * Resolve Exception VA for a source VA.
     * Auto-creates if not found.
     * 
     * @param sourceVa The VA that needs exception handling
     * @return Exception VA
     */
    @Transactional
    public VirtualAccount resolveOrCreateExceptionVa(VirtualAccount sourceVa) {
        log.debug("Resolving Exception VA for source: {}, program: {}, currency: {}", 
            sourceVa.getVaNumber(), sourceVa.getProgramId(), sourceVa.getCurrencyCode());

        // 1. Try to find existing Exception VA
        Optional<VirtualAccount> existingException = findExceptionVa(
            sourceVa.getProgramId(), sourceVa.getCurrencyCode());
        
        if (existingException.isPresent()) {
            log.debug("Found existing Exception VA: {}", existingException.get().getVaNumber());
            return existingException.get();
        }

        // 2. Try corporate-level if no program-level found
        if (sourceVa.getProgramId() != null) {
            Optional<VirtualAccount> corporateException = findExceptionVaByCorporate(
                sourceVa.getCorporateId(), sourceVa.getCurrencyCode());
            if (corporateException.isPresent()) {
                log.debug("Found corporate-level Exception VA: {}", corporateException.get().getVaNumber());
                return corporateException.get();
            }
        }

        // 3. Auto-create Exception VA
        log.info("Exception VA not found for program {} currency {}. Auto-creating...",
            sourceVa.getProgramId(), sourceVa.getCurrencyCode());
        
        VirtualAccount exceptionVa = createExceptionVa(sourceVa);
        log.info("Auto-created Exception VA: {} for program {} currency {}", 
            exceptionVa.getVaNumber(), sourceVa.getProgramId(), sourceVa.getCurrencyCode());
        
        return exceptionVa;
    }

    // ========================================================================
    // BACKWARD COMPATIBILITY METHODS (Used by Controller & ReconciliationService)
    // ========================================================================

    /**
     * Check if Exception VA exists for program/currency.
     * Used by SettlementVaController.checkExceptionVaExists()
     */
    public boolean hasExceptionVa(UUID programId, String currencyCode) {
        return findExceptionVa(programId, currencyCode).isPresent();
    }

    /**
     * Check if Settlement VA exists for program/currency.
     */
    public boolean hasSettlementVa(UUID programId, String currencyCode) {
        return findSettlementVa(programId, currencyCode).isPresent();
    }

    /**
     * Get Exception VA for program/currency.
     * Used by ReconciliationService.
     * Returns Optional to handle not-found case gracefully.
     */
    public Optional<VirtualAccount> getExceptionVa(UUID programId, String currencyCode) {
        return findExceptionVa(programId, currencyCode);
    }

    /**
     * Get Settlement VA for program/currency.
     * Returns Optional to handle not-found case gracefully.
     */
    public Optional<VirtualAccount> getSettlementVa(UUID programId, String currencyCode) {
        return findSettlementVa(programId, currencyCode);
    }

    /**
     * Get or create Exception VA - used by ReconciliationService.
     * This ensures Exception VA exists when needed for parking unmatched transactions.
     */
    @Transactional
    public VirtualAccount getOrCreateExceptionVa(UUID programId, String currencyCode, UUID corporateId) {
        Optional<VirtualAccount> existing = findExceptionVa(programId, currencyCode);
        if (existing.isPresent()) {
            return existing.get();
        }

        // Try corporate level
        if (programId != null && corporateId != null) {
            Optional<VirtualAccount> corporateException = findExceptionVaByCorporate(corporateId, currencyCode);
            if (corporateException.isPresent()) {
                return corporateException.get();
            }
        }

        // Create new Exception VA
        VirtualAccount contextVa = VirtualAccount.builder()
            .programId(programId)
            .currencyCode(currencyCode)
            .corporateId(corporateId)
            .build();

        return createExceptionVa(contextVa);
    }

    /**
     * Get Settlement VA if it exists.
     *
     * @deprecated Settlement VAs should NOT be auto-created.
     *             Use {@link #findSettlementVa(UUID, String)} to check existence,
     *             or {@link #resolveSettlementVaWithResult(VirtualAccount)} for transaction processing.
     *
     * BREAKING CHANGE (v5.2.0):
     * This method NO LONGER creates Settlement VAs.
     * Settlement VAs must be provisioned during program setup.
     * If Settlement VA doesn't exist, this returns Exception VA and logs a warning.
     */
    @Deprecated
    @Transactional
    public VirtualAccount getOrCreateSettlementVa(UUID programId, String currencyCode, UUID corporateId) {
        Optional<VirtualAccount> existing = findSettlementVa(programId, currencyCode);
        if (existing.isPresent()) {
            return existing.get();
        }

        // Try corporate level
        if (programId != null && corporateId != null) {
            Optional<VirtualAccount> corporateSettlement = findSettlementVaByCorporate(corporateId, currencyCode);
            if (corporateSettlement.isPresent()) {
                return corporateSettlement.get();
            }
        }

        // ❌ DO NOT CREATE Settlement VA
        // Settlement VAs must be pre-provisioned during program setup
        log.error("CONFIGURATION ERROR: Settlement VA not found for program {} currency {}. " +
                  "Settlement VA must be created during program setup. " +
                  "Returning Exception VA as fallback.", programId, currencyCode);

        // Return Exception VA as fallback
        return getOrCreateExceptionVa(programId, currencyCode, corporateId);
    }

    /**
     * Check if Settlement VA exists for program/currency.
     * Use this method to verify configuration before processing.
     *
     * @param programId Program ID
     * @param currencyCode Currency code
     * @return true if Settlement VA exists and is properly configured
     */
    public boolean isSettlementVaConfigured(UUID programId, String currencyCode) {
        return findSettlementVa(programId, currencyCode).isPresent();
    }

    /**
     * Validate Settlement VA configuration for a program.
     * Throws BusinessException if not configured.
     *
     * @param programId Program ID
     * @param currencyCode Currency code
     * @throws BusinessException if Settlement VA is not configured
     */
    public void validateSettlementVaConfiguration(UUID programId, String currencyCode) {
        if (!isSettlementVaConfigured(programId, currencyCode)) {
            throw new BusinessException(String.format(
                "Settlement VA not configured for program %s currency %s. " +
                "Please provision Settlement VA during program setup.", programId, currencyCode));
        }
    }

    // ========================================================================
    // FIND METHODS
    // ========================================================================

    /**
     * Find existing Settlement VA for program and currency.
     *
     * REFACTORED v5.1.1: Simplified to use AccountCategory only.
     * Naming conventions are fragile - AccountCategory.SETTLEMENT is the canonical identifier.
     *
     * Search order:
     * 1. By AccountCategory.SETTLEMENT + programId + currency (primary)
     * 2. By VaSpecialType.SETTLEMENT + programId + currency (fallback for legacy)
     */
    public Optional<VirtualAccount> findSettlementVa(UUID programId, String currencyCode) {
        // Primary: Find by AccountCategory.SETTLEMENT (catches ALL Settlement VAs regardless of naming)
        Optional<VirtualAccount> byCategory = vaRepository.findByProgramIdAndAccountCategory(programId, AccountCategory.SETTLEMENT)
            .stream()
            .filter(va -> currencyCode.equals(va.getCurrencyCode()))
            .filter(va -> va.getStatus() == VaStatus.ACTIVE)
            .findFirst();

        if (byCategory.isPresent()) {
            log.debug("Found Settlement VA by AccountCategory: {}", byCategory.get().getVaNumber());
            return byCategory;
        }

        // Fallback: Find by VaSpecialType.SETTLEMENT (for legacy VAs that might not have correct category)
        Optional<VirtualAccount> bySpecialType = vaRepository.findByProgramIdAndSpecialType(programId, VaSpecialType.SETTLEMENT)
            .stream()
            .filter(va -> currencyCode.equals(va.getCurrencyCode()))
            .filter(va -> va.getStatus() == VaStatus.ACTIVE)
            .findFirst();

        if (bySpecialType.isPresent()) {
            log.debug("Found Settlement VA by SpecialType: {}", bySpecialType.get().getVaNumber());
            return bySpecialType;
        }

        return Optional.empty();
    }

    /**
     * Find Settlement VA by corporate (fallback when no program).
     */
    public Optional<VirtualAccount> findSettlementVaByCorporate(UUID corporateId, String currencyCode) {
        return vaRepository.findSettlementVa(corporateId, currencyCode);
    }

    /**
     * Find existing Exception VA for program and currency.
     *
     * REFACTORED v5.1.1: Simplified to use AccountCategory only.
     */
    public Optional<VirtualAccount> findExceptionVa(UUID programId, String currencyCode) {
        // Primary: Find by AccountCategory.EXCEPTION
        Optional<VirtualAccount> byCategory = vaRepository.findByProgramIdAndAccountCategory(programId, AccountCategory.EXCEPTION)
            .stream()
            .filter(va -> currencyCode.equals(va.getCurrencyCode()))
            .filter(va -> va.getStatus() == VaStatus.ACTIVE)
            .findFirst();

        if (byCategory.isPresent()) {
            log.debug("Found Exception VA by AccountCategory: {}", byCategory.get().getVaNumber());
            return byCategory;
        }

        // Fallback: Find by VaSpecialType.EXCEPTION (for legacy)
        Optional<VirtualAccount> bySpecialType = vaRepository.findByProgramIdAndSpecialType(programId, VaSpecialType.EXCEPTION)
            .stream()
            .filter(va -> currencyCode.equals(va.getCurrencyCode()))
            .filter(va -> va.getStatus() == VaStatus.ACTIVE)
            .findFirst();

        if (bySpecialType.isPresent()) {
            log.debug("Found Exception VA by SpecialType: {}", bySpecialType.get().getVaNumber());
            return bySpecialType;
        }

        return Optional.empty();
    }

    /**
     * Find Exception VA by corporate (fallback when no program).
     */
    public Optional<VirtualAccount> findExceptionVaByCorporate(UUID corporateId, String currencyCode) {
        return vaRepository.findExceptionVa(corporateId, currencyCode);
    }

    /**
     * Find Settlement VA under a specific parent for a currency.
     * This catches hierarchy-positioned Settlement VAs (SETTLE- pattern from SettlementVaService).
     *
     * @since v5.1.0
     */
    private Optional<VirtualAccount> findSettlementVaUnderParent(UUID parentVaId, String currency) {
        if (parentVaId == null) {
            return Optional.empty();
        }

        return vaRepository.findByParentAccountIdAndAccountCategory(parentVaId, AccountCategory.SETTLEMENT)
            .stream()
            .filter(va -> currency.equals(va.getCurrencyCode()))
            .filter(va -> va.getStatus() == VaStatus.ACTIVE)
            .findFirst();
    }

    // ========================================================================
    // EXPLICIT PROVISIONING METHODS (v5.2.0)
    // ========================================================================

    /**
     * Provision Settlement VA during program setup.
     *
     * This is the ONLY method that should create Settlement VAs.
     * Must be called explicitly during program configuration, not during transaction processing.
     *
     * NOTE (v5.2.1): Removed Propagation.REQUIRES_NEW to avoid UnexpectedRollbackException
     * when called within a parent transaction (e.g., during program creation).
     * The Settlement VA creation will now participate in the parent transaction.
     *
     * @param sourceVa Context VA providing program, currency, and corporate info
     * @return The created Settlement VA
     * @throws BusinessException if Settlement VA already exists
     */
    @Transactional
    public VirtualAccount provisionSettlementVa(VirtualAccount sourceVa) {
        log.info("Provisioning Settlement VA for program {} currency {} (explicit request)",
            sourceVa.getProgramId(), sourceVa.getCurrencyCode());
        return createSettlementVaInternal(sourceVa);
    }

    /**
     * Provision Settlement VA during program setup (by IDs).
     *
     * @param programId Program ID
     * @param currencyCode Currency code
     * @param corporateId Corporate ID
     * @return The created Settlement VA
     * @throws BusinessException if Settlement VA already exists
     */
    /**
     * Provision Settlement VA during program setup (by program + currency only).
     * Resolves the owning corporate from the program itself — convenience overload for
     * callers (e.g. a controller) that don't have the corporate id on hand.
     *
     * @param programId Program ID
     * @param currencyCode Currency code
     * @return The created (or, if one already exists, the existing) Settlement VA
     */
    @Transactional
    public VirtualAccount provisionSettlementVa(UUID programId, String currencyCode) {
        UUID corporateId = programRepository.findById(programId)
                .map(Program::getCorporateId)
                .orElse(null);
        return provisionSettlementVa(programId, currencyCode, corporateId);
    }

    @Transactional
    public VirtualAccount provisionSettlementVa(UUID programId, String currencyCode, UUID corporateId) {
        log.info("Provisioning Settlement VA for program {} currency {} (explicit request)",
            programId, currencyCode);

        VirtualAccount contextVa = VirtualAccount.builder()
            .programId(programId)
            .currencyCode(currencyCode)
            .corporateId(corporateId)
            .build();

        return createSettlementVaInternal(contextVa);
    }

    /**
     * Internal method to create Settlement VA.
     * Should NOT be called directly - use provisionSettlementVa() instead.
     */
    private VirtualAccount createSettlementVaInternal(VirtualAccount sourceVa) {
        UUID programId = sourceVa.getProgramId();
        String currency = sourceVa.getCurrencyCode();
        UUID corporateId = sourceVa.getCorporateId();

        // Double-check it doesn't already exist (race condition protection)
        // This now also catches SETTLE- pattern from SettlementVaService
        Optional<VirtualAccount> existing = findSettlementVa(programId, currency);
        if (existing.isPresent()) {
            log.debug("Settlement VA already exists: {} - reusing instead of creating duplicate",
                existing.get().getVaNumber());
            return existing.get();
        }

        // FIX v5.1.0: Also check if a Settlement VA exists under the same parent
        // This catches hierarchy-positioned Settlement VAs that might not match by program lookup
        UUID parentAccountId = findParentForSettlement(sourceVa);
        if (parentAccountId != null) {
            Optional<VirtualAccount> siblingSettlement = findSettlementVaUnderParent(parentAccountId, currency);
            if (siblingSettlement.isPresent()) {
                log.debug("Found sibling Settlement VA: {} - reusing instead of creating duplicate",
                    siblingSettlement.get().getVaNumber());
                return siblingSettlement.get();
            }
        }

        // Get program details for naming
        String programCode = getProgramCode(programId);
        String vaNumber = buildSettlementVaNumber(programCode, currency);
        String vaName = buildSettlementVaName(programCode, currency);

        // parentAccountId already determined above (reuse it)

        VirtualAccount settlementVa = VirtualAccount.builder()
            // Identity
            .vaNumber(vaNumber)
            .vaName(vaName)
            
            // Ownership
            .corporateId(corporateId)
            .programId(programId)
            
            // Classification - PURE VIRTUAL
            .accountType(AccountType.VIRTUAL)
            .accountCategory(AccountCategory.SETTLEMENT)
            .specialType(VaSpecialType.SETTLEMENT)
            
            // Hierarchy
            .parentAccountId(parentAccountId)
            .hierarchyLevel(parentAccountId != null ? 
                getParentLevel(parentAccountId) + 1 : 0)
            
            // Currency
            .currencyCode(currency)
            
            // Balance - starts at zero
            .currentBalance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO)
            
            // NO physical account - purely virtual
            .physicalAccountId(null)
            
            // Status
            .status(VaStatus.ACTIVE)
            
            .build();

        settlementVa = vaRepository.save(settlementVa);
        
        log.info("AUTO-CREATED Settlement VA: {} (ID: {}) for program {} currency {}", 
            vaNumber, settlementVa.getId(), programCode, currency);

        return settlementVa;
    }

    /**
     * Auto-create Exception VA.
     *
     * NOTE (v5.2.1): Removed Propagation.REQUIRES_NEW to avoid UnexpectedRollbackException
     * when called within a parent transaction. The Exception VA creation will now
     * participate in the parent transaction.
     */
    @Transactional
    public VirtualAccount createExceptionVa(VirtualAccount sourceVa) {
        UUID programId = sourceVa.getProgramId();
        String currency = sourceVa.getCurrencyCode();
        UUID corporateId = sourceVa.getCorporateId();

        // Double-check it doesn't already exist (race condition protection)
        Optional<VirtualAccount> existing = findExceptionVa(programId, currency);
        if (existing.isPresent()) {
            return existing.get();
        }

        // Get program details for naming
        String programCode = getProgramCode(programId);
        String vaNumber = buildExceptionVaNumber(programCode, currency);
        String vaName = buildExceptionVaName(programCode, currency);

        VirtualAccount exceptionVa = VirtualAccount.builder()
            // Identity
            .vaNumber(vaNumber)
            .vaName(vaName)
            
            // Ownership
            .corporateId(corporateId)
            .programId(programId)
            
            // Classification - PURE VIRTUAL
            .accountType(AccountType.VIRTUAL)
            .accountCategory(AccountCategory.EXCEPTION)
            .specialType(VaSpecialType.EXCEPTION)
            
            // Hierarchy - at program level (no parent)
            .parentAccountId(null)
            .hierarchyLevel(0)
            
            // Currency
            .currencyCode(currency)
            
            // Balance - starts at zero
            .currentBalance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO)
            
            // NO physical account - purely virtual
            .physicalAccountId(null)
            
            // Status
            .status(VaStatus.ACTIVE)
            
            .build();

        exceptionVa = vaRepository.save(exceptionVa);
        
        log.info("AUTO-CREATED Exception VA: {} (ID: {}) for program {} currency {}", 
            vaNumber, exceptionVa.getId(), programCode, currency);

        return exceptionVa;
    }

    // ========================================================================
    // NAMING CONVENTION HELPERS
    // ========================================================================

    private String buildSettlementVaNumber(UUID programId, String currency) {
        String programCode = getProgramCode(programId);
        return buildSettlementVaNumber(programCode, currency);
    }

    private String buildSettlementVaNumber(String programCode, String currency) {
        return String.format("%s-%s-%s", SETTLEMENT_VA_PREFIX, 
            sanitizeForVaNumber(programCode), currency);
    }

    private String buildSettlementVaName(String programCode, String currency) {
        return String.format("Settlement Account - %s (%s)", programCode, currency);
    }

    private String buildExceptionVaNumber(UUID programId, String currency) {
        String programCode = getProgramCode(programId);
        return buildExceptionVaNumber(programCode, currency);
    }

    private String buildExceptionVaNumber(String programCode, String currency) {
        return String.format("%s-%s-%s", EXCEPTION_VA_PREFIX, 
            sanitizeForVaNumber(programCode), currency);
    }

    private String buildExceptionVaName(String programCode, String currency) {
        return String.format("Exception Account - %s (%s)", programCode, currency);
    }

    private String sanitizeForVaNumber(String input) {
        if (input == null) return "UNKNOWN";
        String sanitized = input.toUpperCase().replaceAll("[^A-Z0-9]", "");
        return sanitized.substring(0, Math.min(20, sanitized.length()));
    }

    // ========================================================================
    // HIERARCHY HELPERS
    // ========================================================================

    private String getProgramCode(UUID programId) {
        if (programId == null) return "DEFAULT";
        
        return programRepository.findById(programId)
            .map(Program::getProgramCode)
            .orElse("PROG" + programId.toString().substring(0, 8).toUpperCase());
    }

    /**
     * Find parent for Settlement VA.
     * Tries to find Currency Mirror at same currency, otherwise null.
     */
    private UUID findParentForSettlement(VirtualAccount sourceVa) {
        if (sourceVa.getParentAccountId() != null) {
            // Check if parent is a Currency Mirror
            Optional<VirtualAccount> parent = vaRepository.findById(sourceVa.getParentAccountId());
            if (parent.isPresent() && 
                parent.get().getAccountCategory() == AccountCategory.CURRENCY_MIRROR) {
                return parent.get().getId();
            }
        }
        
        // Try to find Currency Mirror for this program/currency
        if (sourceVa.getProgramId() != null) {
            Optional<VirtualAccount> currencyMirror = vaRepository
                .findCurrencyMirrorsByProgramAndCurrency(sourceVa.getProgramId(), sourceVa.getCurrencyCode())
                .stream()
                .filter(va -> va.getStatus() == VaStatus.ACTIVE)
                .findFirst();
            
            if (currencyMirror.isPresent()) {
                return currencyMirror.get().getId();
            }
        }
        
        return null;
    }

    private int getParentLevel(UUID parentAccountId) {
        return vaRepository.findById(parentAccountId)
            .map(va -> va.getHierarchyLevel() != null ? va.getHierarchyLevel() : 0)
            .orElse(0);
    }

    // ========================================================================
    // UTILITY METHODS
    // ========================================================================

    /**
     * Check if Settlement VA exists for program/currency.
     * Alias for hasSettlementVa for consistency.
     */
    public boolean settlementVaExists(UUID programId, String currencyCode) {
        return hasSettlementVa(programId, currencyCode);
    }

    /**
     * Check if Exception VA exists for program/currency.
     * Alias for hasExceptionVa for consistency.
     */
    public boolean exceptionVaExists(UUID programId, String currencyCode) {
        return hasExceptionVa(programId, currencyCode);
    }

    /**
     * Ensure both Settlement and Exception VAs exist for a program/currency.
     * Called during program initialization/setup.
     *
     * IMPORTANT (v5.2.0):
     * This is the correct method to use during program setup to provision
     * Settlement and Exception VAs. This is the ONLY context where Settlement
     * VA creation is allowed.
     */
    @Transactional
    public void ensureInfrastructureVasExist(UUID programId, String currencyCode, UUID corporateId) {
        // Ensure Settlement VA - explicit provisioning during setup
        if (!settlementVaExists(programId, currencyCode)) {
            log.info("Provisioning Settlement VA during program setup: program={}, currency={}",
                programId, currencyCode);
            provisionSettlementVa(programId, currencyCode, corporateId);
        }

        // Ensure Exception VA
        if (!exceptionVaExists(programId, currencyCode)) {
            getOrCreateExceptionVa(programId, currencyCode, corporateId);
        }
    }

    /**
     * Check Settlement VA configuration status for all currencies in a program.
     *
     * @param programId Program ID
     * @param currencies List of currency codes to check
     * @return Map of currency code to configuration status (true = configured)
     */
    public java.util.Map<String, Boolean> checkSettlementVaConfiguration(UUID programId, List<String> currencies) {
        java.util.Map<String, Boolean> result = new java.util.HashMap<>();
        for (String currency : currencies) {
            result.put(currency, isSettlementVaConfigured(programId, currency));
        }
        return result;
    }

    /**
     * Get missing Settlement VA configurations for a program.
     *
     * @param programId Program ID
     * @param currencies List of currency codes to check
     * @return List of currency codes that are missing Settlement VA
     */
    public List<String> getMissingSettlementVaConfigurations(UUID programId, List<String> currencies) {
        return currencies.stream()
            .filter(currency -> !isSettlementVaConfigured(programId, currency))
            .collect(java.util.stream.Collectors.toList());
    }
}