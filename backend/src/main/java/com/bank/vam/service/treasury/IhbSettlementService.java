package com.bank.vam.service.treasury;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.treasury.IhbDeposit;
import com.bank.vam.entity.treasury.IhbLoan;
import com.bank.vam.entity.treasury.SweepExecution;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.treasury.IhbDepositRepository;
import com.bank.vam.repository.treasury.IhbLoanRepository;
import com.bank.vam.repository.treasury.SweepExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * IHB Settlement Service - EOD Settlement Processing.
 *
 * Processes COMMITTED IHB positions and executes actual fund movements.
 *
 * OPTION B ARCHITECTURE:
 * - Sweeps create positions only (no immediate fund movement)
 * - This service runs at EOD to settle all committed positions
 * - Netting: Multiple positions between same entities net to single transfer
 * - Maturity handling: Maturing positions are processed in same run
 *
 * Settlement Flow:
 * 1. Collect all COMMITTED deposits and loans
 * 2. Collect all maturing SETTLED positions
 * 3. Calculate net positions per entity pair
 * 4. Execute netted fund transfers
 * 5. Update position statuses
 * 6. Clear committed balances on VAs
 * 7. Post interest on maturing positions
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IhbSettlementService {

    private final IhbDepositRepository depositRepository;
    private final IhbLoanRepository loanRepository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final SweepExecutionRepository sweepExecutionRepository;
    private final com.bank.vam.config.HomeBankProperties homeBankProperties;

    // ========================================================================
    // DATA CLASSES
    // ========================================================================

    /**
     * Settlement request parameters.
     */
    public record SettlementRequest(
        UUID corporateId,
        LocalDate settlementDate,
        boolean processMaturing,  // Also process maturing positions
        boolean dryRun            // Simulate without executing
    ) {
        public SettlementRequest(UUID corporateId) {
            this(corporateId, LocalDate.now(), true, false);
        }
    }

    /**
     * Settlement result summary.
     */
    public record SettlementResult(
        String batchReference,
        LocalDate settlementDate,
        int depositsSettled,
        int loansSettled,
        int positionsMatured,
        int transfersExecuted,
        BigDecimal totalSettledAmount,
        BigDecimal totalMatureAmount,
        BigDecimal totalInterestPosted,
        BigDecimal netTreasuryMovement,
        List<SettlementTransaction> transactions,
        List<String> errors
    ) {}

    /**
     * Individual settlement transaction.
     */
    public record SettlementTransaction(
        UUID sourceVaId,
        UUID targetVaId,
        String sourceEntityCode,
        String targetEntityCode,
        BigDecimal amount,
        String currency,
        String type,  // DEPOSIT_SETTLEMENT, LOAN_SETTLEMENT, MATURITY_RETURN, INTEREST
        List<UUID> positionIds
    ) {}

    /**
     * Net position for an entity pair.
     */
    private record NetPosition(
        UUID entityId,
        String entityCode,
        UUID vaId,
        UUID treasuryVaId,
        BigDecimal netDeposits,      // Deposits TO treasury (outflow)
        BigDecimal netLoans,         // Loans FROM treasury (inflow)
        BigDecimal maturedDeposits,  // Maturing deposits (return inflow)
        BigDecimal maturedLoans,     // Maturing loans (return outflow)
        BigDecimal interestEarned,   // Interest on maturing deposits
        BigDecimal interestOwed,     // Interest on maturing loans
        List<IhbDeposit> deposits,
        List<IhbLoan> loans,
        List<IhbDeposit> maturingDeposits,
        List<IhbLoan> maturingLoans
    ) {
        BigDecimal getNetMovement() {
            // Positive = funds TO treasury, Negative = funds FROM treasury
            return netDeposits.subtract(netLoans)
                .subtract(maturedDeposits).add(maturedLoans)
                .subtract(interestEarned).add(interestOwed);
        }
    }

    // ========================================================================
    // MAIN SETTLEMENT METHODS
    // ========================================================================

    /**
     * Run EOD settlement for a corporate.
     * Processes all COMMITTED positions and optionally maturing positions.
     */
    @Transactional
    public SettlementResult runSettlement(SettlementRequest request) {
        String batchRef = generateBatchReference();
        LocalDate settlementDate = request.settlementDate() != null ? request.settlementDate() : LocalDate.now();
        List<String> errors = new ArrayList<>();
        List<SettlementTransaction> transactions = new ArrayList<>();

        log.info("Starting IHB settlement batch {} for corporate {} on {}",
            batchRef, request.corporateId(), settlementDate);

        // 1. Collect COMMITTED positions
        List<IhbDeposit> committedDeposits = depositRepository
            .findByCorporateIdAndStatus(request.corporateId(), IhbDeposit.DepositStatus.COMMITTED);
        List<IhbLoan> committedLoans = loanRepository
            .findByCorporateIdAndStatus(request.corporateId(), IhbLoan.LoanStatus.COMMITTED);

        log.info("Found {} committed deposits, {} committed loans",
            committedDeposits.size(), committedLoans.size());

        // 2. Collect maturing positions (if requested)
        List<IhbDeposit> maturingDeposits = new ArrayList<>();
        List<IhbLoan> maturingLoans = new ArrayList<>();

        if (request.processMaturing()) {
            maturingDeposits = depositRepository.findMaturingDeposits(request.corporateId(), settlementDate);
            maturingLoans = loanRepository.findMaturingLoans(request.corporateId(), settlementDate);
            log.info("Found {} maturing deposits, {} maturing loans",
                maturingDeposits.size(), maturingLoans.size());
        }

        // 3. Calculate net positions per entity
        Map<UUID, NetPosition> netPositions = calculateNetPositions(
            committedDeposits, committedLoans, maturingDeposits, maturingLoans);

        // 4. Execute netted settlements
        BigDecimal totalSettled = BigDecimal.ZERO;
        BigDecimal totalMatured = BigDecimal.ZERO;
        BigDecimal totalInterest = BigDecimal.ZERO;
        BigDecimal netTreasuryMovement = BigDecimal.ZERO;
        int transferCount = 0;

        for (NetPosition position : netPositions.values()) {
            if (request.dryRun()) {
                log.info("[DRY RUN] Would settle net {} for entity {}",
                    position.getNetMovement(), position.entityCode());
                continue;
            }

            try {
                SettlementTransaction tx = executeNetSettlement(position, batchRef, settlementDate);
                if (tx != null) {
                    transactions.add(tx);
                    transferCount++;
                    netTreasuryMovement = netTreasuryMovement.add(position.getNetMovement());
                }

                // Update position statuses and VA balances
                settleDeposits(position.deposits(), batchRef, settlementDate);
                settleLoans(position.loans(), batchRef, settlementDate);
                matureDeposits(position.maturingDeposits(), batchRef, settlementDate);
                matureLoans(position.maturingLoans(), batchRef, settlementDate);

                totalSettled = totalSettled.add(position.netDeposits()).add(position.netLoans());
                totalMatured = totalMatured.add(position.maturedDeposits()).add(position.maturedLoans());
                totalInterest = totalInterest.add(position.interestEarned()).add(position.interestOwed());

            } catch (Exception e) {
                log.error("Settlement failed for entity {}: {}", position.entityCode(), e.getMessage());
                errors.add("Entity " + position.entityCode() + ": " + e.getMessage());
            }
        }

        // 5. Update sweep executions
        updateSweepExecutions(committedDeposits, batchRef, settlementDate);

        log.info("Settlement batch {} completed: {} deposits, {} loans settled, {} matured, {} transfers",
            batchRef, committedDeposits.size(), committedLoans.size(),
            maturingDeposits.size() + maturingLoans.size(), transferCount);

        return new SettlementResult(
            batchRef,
            settlementDate,
            committedDeposits.size(),
            committedLoans.size(),
            maturingDeposits.size() + maturingLoans.size(),
            transferCount,
            totalSettled,
            totalMatured,
            totalInterest,
            netTreasuryMovement,
            transactions,
            errors
        );
    }

    // ========================================================================
    // NET POSITION CALCULATION
    // ========================================================================

    /**
     * Calculate net positions for each entity.
     * Groups all positions by depositor/borrower entity and calculates net movement.
     */
    private Map<UUID, NetPosition> calculateNetPositions(
            List<IhbDeposit> deposits,
            List<IhbLoan> loans,
            List<IhbDeposit> maturingDeposits,
            List<IhbLoan> maturingLoans) {

        Map<UUID, NetPosition> result = new HashMap<>();

        // Process committed deposits (outflow from depositor to treasury)
        for (IhbDeposit deposit : deposits) {
            UUID entityId = deposit.getDepositorLegalEntityId();
            NetPosition existing = result.get(entityId);

            BigDecimal newDeposits = (existing != null ? existing.netDeposits() : BigDecimal.ZERO)
                .add(deposit.getCurrentBalance());
            List<IhbDeposit> depositList = existing != null ?
                new ArrayList<>(existing.deposits()) : new ArrayList<>();
            depositList.add(deposit);

            result.put(entityId, new NetPosition(
                entityId,
                deposit.getDepositorEntityCode(),
                deposit.getDepositorVaId(),
                deposit.getTreasuryVaId(),
                newDeposits,
                existing != null ? existing.netLoans() : BigDecimal.ZERO,
                existing != null ? existing.maturedDeposits() : BigDecimal.ZERO,
                existing != null ? existing.maturedLoans() : BigDecimal.ZERO,
                existing != null ? existing.interestEarned() : BigDecimal.ZERO,
                existing != null ? existing.interestOwed() : BigDecimal.ZERO,
                depositList,
                existing != null ? existing.loans() : new ArrayList<>(),
                existing != null ? existing.maturingDeposits() : new ArrayList<>(),
                existing != null ? existing.maturingLoans() : new ArrayList<>()
            ));
        }

        // Process committed loans (inflow from treasury to borrower)
        for (IhbLoan loan : loans) {
            UUID entityId = loan.getBorrowerLegalEntityId();
            NetPosition existing = result.get(entityId);

            BigDecimal newLoans = (existing != null ? existing.netLoans() : BigDecimal.ZERO)
                .add(loan.getOutstandingAmount());
            List<IhbLoan> loanList = existing != null ?
                new ArrayList<>(existing.loans()) : new ArrayList<>();
            loanList.add(loan);

            result.put(entityId, new NetPosition(
                entityId,
                loan.getBorrowerEntityCode(),
                loan.getBorrowerVaId(),
                loan.getLenderVaId(),
                existing != null ? existing.netDeposits() : BigDecimal.ZERO,
                newLoans,
                existing != null ? existing.maturedDeposits() : BigDecimal.ZERO,
                existing != null ? existing.maturedLoans() : BigDecimal.ZERO,
                existing != null ? existing.interestEarned() : BigDecimal.ZERO,
                existing != null ? existing.interestOwed() : BigDecimal.ZERO,
                existing != null ? existing.deposits() : new ArrayList<>(),
                loanList,
                existing != null ? existing.maturingDeposits() : new ArrayList<>(),
                existing != null ? existing.maturingLoans() : new ArrayList<>()
            ));
        }

        // Process maturing deposits (return from treasury to depositor)
        for (IhbDeposit deposit : maturingDeposits) {
            UUID entityId = deposit.getDepositorLegalEntityId();
            NetPosition existing = result.get(entityId);

            BigDecimal maturedAmt = (existing != null ? existing.maturedDeposits() : BigDecimal.ZERO)
                .add(deposit.getCurrentBalance());
            BigDecimal interest = (existing != null ? existing.interestEarned() : BigDecimal.ZERO)
                .add(deposit.getAccruedInterest() != null ? deposit.getAccruedInterest() : BigDecimal.ZERO);
            List<IhbDeposit> matList = existing != null ?
                new ArrayList<>(existing.maturingDeposits()) : new ArrayList<>();
            matList.add(deposit);

            result.put(entityId, new NetPosition(
                entityId,
                deposit.getDepositorEntityCode(),
                deposit.getDepositorVaId(),
                deposit.getTreasuryVaId(),
                existing != null ? existing.netDeposits() : BigDecimal.ZERO,
                existing != null ? existing.netLoans() : BigDecimal.ZERO,
                maturedAmt,
                existing != null ? existing.maturedLoans() : BigDecimal.ZERO,
                interest,
                existing != null ? existing.interestOwed() : BigDecimal.ZERO,
                existing != null ? existing.deposits() : new ArrayList<>(),
                existing != null ? existing.loans() : new ArrayList<>(),
                matList,
                existing != null ? existing.maturingLoans() : new ArrayList<>()
            ));
        }

        // Process maturing loans (repayment from borrower to treasury)
        for (IhbLoan loan : maturingLoans) {
            UUID entityId = loan.getBorrowerLegalEntityId();
            NetPosition existing = result.get(entityId);

            BigDecimal maturedAmt = (existing != null ? existing.maturedLoans() : BigDecimal.ZERO)
                .add(loan.getOutstandingAmount());
            BigDecimal interest = (existing != null ? existing.interestOwed() : BigDecimal.ZERO)
                .add(loan.getAccruedInterest() != null ? loan.getAccruedInterest() : BigDecimal.ZERO);
            List<IhbLoan> matList = existing != null ?
                new ArrayList<>(existing.maturingLoans()) : new ArrayList<>();
            matList.add(loan);

            result.put(entityId, new NetPosition(
                entityId,
                loan.getBorrowerEntityCode(),
                loan.getBorrowerVaId(),
                loan.getLenderVaId(),
                existing != null ? existing.netDeposits() : BigDecimal.ZERO,
                existing != null ? existing.netLoans() : BigDecimal.ZERO,
                existing != null ? existing.maturedDeposits() : BigDecimal.ZERO,
                maturedAmt,
                existing != null ? existing.interestEarned() : BigDecimal.ZERO,
                interest,
                existing != null ? existing.deposits() : new ArrayList<>(),
                existing != null ? existing.loans() : new ArrayList<>(),
                existing != null ? existing.maturingDeposits() : new ArrayList<>(),
                matList
            ));
        }

        return result;
    }

    // ========================================================================
    // SETTLEMENT EXECUTION
    // ========================================================================

    /**
     * Execute a netted settlement for an entity.
     * Moves the net amount between entity VA and Treasury VA.
     */
    private SettlementTransaction executeNetSettlement(NetPosition position, String batchRef, LocalDate settlementDate) {
        BigDecimal netMovement = position.getNetMovement();

        if (netMovement.abs().compareTo(new BigDecimal("0.01")) < 0) {
            log.debug("Net movement for {} is zero - no transfer needed", position.entityCode());
            return null;
        }

        VirtualAccount entityVa = virtualAccountRepository.findById(position.vaId()).orElse(null);
        VirtualAccount treasuryVa = virtualAccountRepository.findById(position.treasuryVaId()).orElse(null);

        if (entityVa == null || treasuryVa == null) {
            log.error("Cannot find VAs for settlement: entity={}, treasury={}",
                position.vaId(), position.treasuryVaId());
            throw new IllegalStateException("VA not found for settlement");
        }

        String transactionType;
        List<UUID> positionIds = new ArrayList<>();

        if (netMovement.compareTo(BigDecimal.ZERO) > 0) {
            // Net outflow from entity to treasury (more deposits than returns)
            entityVa.settleOutflow(netMovement);
            mirrorIfHomeBankShadow(entityVa, netMovement.negate());
            treasuryVa.credit(netMovement);
            mirrorIfHomeBankShadow(treasuryVa, netMovement);
            transactionType = "NET_DEPOSIT";
            position.deposits().forEach(d -> positionIds.add(d.getId()));
        } else {
            // Net inflow to entity from treasury (more returns/loans than deposits)
            BigDecimal absAmount = netMovement.abs();
            treasuryVa.debit(absAmount);
            mirrorIfHomeBankShadow(treasuryVa, absAmount.negate());
            entityVa.settleInflow(absAmount);
            mirrorIfHomeBankShadow(entityVa, absAmount);
            transactionType = "NET_LOAN_OR_RETURN";
            position.loans().forEach(l -> positionIds.add(l.getId()));
            position.maturingDeposits().forEach(d -> positionIds.add(d.getId()));
        }

        virtualAccountRepository.save(entityVa);
        virtualAccountRepository.save(treasuryVa);

        log.info("Settlement transfer: {} {} {} -> {} ({})",
            netMovement.abs(), entityVa.getCurrencyCode(),
            netMovement.compareTo(BigDecimal.ZERO) > 0 ? position.entityCode() : "Treasury",
            netMovement.compareTo(BigDecimal.ZERO) > 0 ? "Treasury" : position.entityCode(),
            transactionType);

        return new SettlementTransaction(
            netMovement.compareTo(BigDecimal.ZERO) > 0 ? entityVa.getId() : treasuryVa.getId(),
            netMovement.compareTo(BigDecimal.ZERO) > 0 ? treasuryVa.getId() : entityVa.getId(),
            position.entityCode(),
            "Treasury",
            netMovement.abs(),
            entityVa.getCurrencyCode(),
            transactionType,
            positionIds
        );
    }

    /**
     * If {@code va} is a home-bank-held shadow (PHYSICAL_MIRROR), mirror the same delta
     * that was just applied to its ledger balance into {@code bankBalance} — otherwise
     * the CBS-mirrored balance silently drifts from what the ledger says moved.
     * No-op for ordinary operational VAs and for external/other-bank shadows (those
     * require a mandated rail instruction, not a same-process ledger mirror — see
     * ExternalMandate).
     */
    private void mirrorIfHomeBankShadow(VirtualAccount va, BigDecimal delta) {
        if (va.isPhysicalMirror() && va.isHomeBankHeld(homeBankProperties.getBic())) {
            va.mirrorBankBalance(delta);
        }
    }

    // ========================================================================
    // POSITION STATUS UPDATES
    // ========================================================================

    private void settleDeposits(List<IhbDeposit> deposits, String batchRef, LocalDate settlementDate) {
        for (IhbDeposit deposit : deposits) {
            deposit.setStatus(IhbDeposit.DepositStatus.SETTLED);
            deposit.setSettlementDate(settlementDate);
            deposit.setSettlementBatchRef(batchRef);
            depositRepository.save(deposit);
        }
    }

    private void settleLoans(List<IhbLoan> loans, String batchRef, LocalDate settlementDate) {
        for (IhbLoan loan : loans) {
            loan.setStatus(IhbLoan.LoanStatus.SETTLED);
            loan.setSettlementDate(settlementDate);
            loan.setSettlementBatchRef(batchRef);
            loanRepository.save(loan);
        }
    }

    private void matureDeposits(List<IhbDeposit> deposits, String batchRef, LocalDate settlementDate) {
        for (IhbDeposit deposit : deposits) {
            // Post accrued interest
            if (deposit.getAccruedInterest() != null && deposit.getAccruedInterest().compareTo(BigDecimal.ZERO) > 0) {
                deposit.setTotalInterestEarned(
                    deposit.getTotalInterestEarned().add(deposit.getAccruedInterest()));
                deposit.setAccruedInterest(BigDecimal.ZERO);
            }
            deposit.setStatus(IhbDeposit.DepositStatus.MATURED);
            deposit.setCurrentBalance(BigDecimal.ZERO);
            depositRepository.save(deposit);
        }
    }

    private void matureLoans(List<IhbLoan> loans, String batchRef, LocalDate settlementDate) {
        for (IhbLoan loan : loans) {
            // Post accrued interest
            if (loan.getAccruedInterest() != null && loan.getAccruedInterest().compareTo(BigDecimal.ZERO) > 0) {
                loan.setTotalInterestPaid(
                    loan.getTotalInterestPaid().add(loan.getAccruedInterest()));
                loan.setAccruedInterest(BigDecimal.ZERO);
            }
            loan.setStatus(IhbLoan.LoanStatus.MATURED);
            loan.setOutstandingAmount(BigDecimal.ZERO);
            loanRepository.save(loan);
        }
    }

    private void updateSweepExecutions(List<IhbDeposit> deposits, String batchRef, LocalDate settlementDate) {
        for (IhbDeposit deposit : deposits) {
            if (deposit.getSweepExecutionReference() != null) {
                sweepExecutionRepository.findByExecutionReference(deposit.getSweepExecutionReference())
                    .ifPresent(exec -> {
                        exec.setStatus(SweepExecution.ExecutionStatus.SETTLED);
                        exec.setSettledAt(LocalDateTime.now());
                        exec.setSettlementBatchRef(batchRef);
                        exec.setCompletedAt(LocalDateTime.now());
                        sweepExecutionRepository.save(exec);
                    });
            }
        }
    }

    // ========================================================================
    // POSITION CANCELLATION
    // ========================================================================

    /**
     * Cancel a manual position (PENDING or APPROVED only).
     */
    @Transactional
    public void cancelDeposit(UUID depositId, String reason, String cancelledBy) {
        IhbDeposit deposit = depositRepository.findById(depositId)
            .orElseThrow(() -> new IllegalArgumentException("Deposit not found: " + depositId));

        if (!deposit.canCancel()) {
            throw new IllegalStateException("Cannot cancel deposit in status: " + deposit.getStatus());
        }

        // Release committed balance on VA
        if (deposit.getDepositorVaId() != null) {
            VirtualAccount va = virtualAccountRepository.findById(deposit.getDepositorVaId()).orElse(null);
            if (va != null) {
                va.releaseCommittedOutflow(deposit.getCurrentBalance());
                virtualAccountRepository.save(va);
            }
        }

        deposit.setStatus(IhbDeposit.DepositStatus.CANCELLED);
        deposit.setRejectionReason(reason);
        depositRepository.save(deposit);

        log.info("Cancelled deposit {} by {}: {}", deposit.getDepositReference(), cancelledBy, reason);
    }

    /**
     * Cancel a manual loan (PENDING or APPROVED only).
     */
    @Transactional
    public void cancelLoan(UUID loanId, String reason, String cancelledBy) {
        IhbLoan loan = loanRepository.findById(loanId)
            .orElseThrow(() -> new IllegalArgumentException("Loan not found: " + loanId));

        if (!loan.canCancel()) {
            throw new IllegalStateException("Cannot cancel loan in status: " + loan.getStatus());
        }

        // Release committed balance on VA
        if (loan.getBorrowerVaId() != null) {
            VirtualAccount va = virtualAccountRepository.findById(loan.getBorrowerVaId()).orElse(null);
            if (va != null) {
                va.releaseCommittedInflow(loan.getOutstandingAmount());
                virtualAccountRepository.save(va);
            }
        }

        loan.setStatus(IhbLoan.LoanStatus.CANCELLED);
        loan.setRejectionReason(reason);
        loanRepository.save(loan);

        log.info("Cancelled loan {} by {}: {}", loan.getLoanReference(), cancelledBy, reason);
    }

    // ========================================================================
    // APPROVAL WORKFLOW (Manual Positions)
    // ========================================================================

    /**
     * Approve a manual deposit (PENDING → APPROVED).
     */
    @Transactional
    public void approveDeposit(UUID depositId, String approvedBy) {
        IhbDeposit deposit = depositRepository.findById(depositId)
            .orElseThrow(() -> new IllegalArgumentException("Deposit not found: " + depositId));

        if (deposit.getStatus() != IhbDeposit.DepositStatus.PENDING) {
            throw new IllegalStateException("Can only approve PENDING deposits, current: " + deposit.getStatus());
        }

        deposit.setStatus(IhbDeposit.DepositStatus.APPROVED);
        deposit.setApprovedBy(approvedBy);
        deposit.setApprovedAt(LocalDateTime.now());
        depositRepository.save(deposit);

        log.info("Approved deposit {} by {}", deposit.getDepositReference(), approvedBy);
    }

    /**
     * Approve a manual loan (PENDING → APPROVED).
     */
    @Transactional
    public void approveLoan(UUID loanId, String approvedBy) {
        IhbLoan loan = loanRepository.findById(loanId)
            .orElseThrow(() -> new IllegalArgumentException("Loan not found: " + loanId));

        if (loan.getStatus() != IhbLoan.LoanStatus.PENDING) {
            throw new IllegalStateException("Can only approve PENDING loans, current: " + loan.getStatus());
        }

        loan.setStatus(IhbLoan.LoanStatus.APPROVED);
        loan.setApprovedBy(approvedBy);
        loan.setApprovedAt(LocalDateTime.now());
        loanRepository.save(loan);

        log.info("Approved loan {} by {}", loan.getLoanReference(), approvedBy);
    }

    /**
     * Reject a manual position (PENDING → REJECTED).
     */
    @Transactional
    public void rejectDeposit(UUID depositId, String reason, String rejectedBy) {
        IhbDeposit deposit = depositRepository.findById(depositId)
            .orElseThrow(() -> new IllegalArgumentException("Deposit not found: " + depositId));

        if (deposit.getStatus() != IhbDeposit.DepositStatus.PENDING) {
            throw new IllegalStateException("Can only reject PENDING deposits");
        }

        // Release any committed balance
        if (deposit.getDepositorVaId() != null) {
            VirtualAccount va = virtualAccountRepository.findById(deposit.getDepositorVaId()).orElse(null);
            if (va != null && va.getCommittedOutflow() != null &&
                va.getCommittedOutflow().compareTo(BigDecimal.ZERO) > 0) {
                va.releaseCommittedOutflow(deposit.getCurrentBalance());
                virtualAccountRepository.save(va);
            }
        }

        deposit.setStatus(IhbDeposit.DepositStatus.REJECTED);
        deposit.setRejectionReason(reason);
        depositRepository.save(deposit);

        log.info("Rejected deposit {} by {}: {}", deposit.getDepositReference(), rejectedBy, reason);
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private String generateBatchReference() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        return "STLMT-" + timestamp;
    }

    /**
     * Get settlement status for a corporate (positions awaiting settlement).
     */
    @Transactional(readOnly = true)
    public SettlementStatusResponse getSettlementStatus(UUID corporateId) {
        List<IhbDeposit> committedDeposits = depositRepository
            .findByCorporateIdAndStatus(corporateId, IhbDeposit.DepositStatus.COMMITTED);
        List<IhbLoan> committedLoans = loanRepository
            .findByCorporateIdAndStatus(corporateId, IhbLoan.LoanStatus.COMMITTED);
        List<IhbDeposit> pendingDeposits = depositRepository
            .findByCorporateIdAndStatus(corporateId, IhbDeposit.DepositStatus.PENDING);
        List<IhbLoan> pendingLoans = loanRepository
            .findByCorporateIdAndStatus(corporateId, IhbLoan.LoanStatus.PENDING);
        List<IhbDeposit> approvedDeposits = depositRepository
            .findByCorporateIdAndStatus(corporateId, IhbDeposit.DepositStatus.APPROVED);
        List<IhbLoan> approvedLoans = loanRepository
            .findByCorporateIdAndStatus(corporateId, IhbLoan.LoanStatus.APPROVED);

        BigDecimal totalCommittedDeposits = committedDeposits.stream()
            .map(IhbDeposit::getCurrentBalance)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCommittedLoans = committedLoans.stream()
            .map(IhbLoan::getOutstandingAmount)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new SettlementStatusResponse(
            committedDeposits.size(),
            committedLoans.size(),
            pendingDeposits.size() + pendingLoans.size(),
            approvedDeposits.size() + approvedLoans.size(),
            totalCommittedDeposits,
            totalCommittedLoans,
            totalCommittedDeposits.subtract(totalCommittedLoans)
        );
    }

    public record SettlementStatusResponse(
        int committedDeposits,
        int committedLoans,
        int pendingApproval,
        int approvedAwaitingSettlement,
        BigDecimal totalCommittedDeposits,
        BigDecimal totalCommittedLoans,
        BigDecimal netCommitted
    ) {}
}
