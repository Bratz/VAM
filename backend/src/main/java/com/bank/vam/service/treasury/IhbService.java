package com.bank.vam.service.treasury;

import com.bank.vam.dto.treasury.IhbDto;
import com.bank.vam.entity.treasury.*;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.treasury.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * @deprecated Use IhbUnifiedService instead.
 * This service uses the legacy IhbEntity model.
 * All new development should use IhbUnifiedService (LegalEntity-based).
 */
@Deprecated(since = "2.0", forRemoval = true)
@Slf4j
@Service
@RequiredArgsConstructor
public class IhbService {

    private final IhbEntityRepository entityRepository;
    private final IhbLoanRepository loanRepository;
    private final IhbDepositRepository depositRepository;
    private final FeePostingService feePostingService;  // NEW: Fee posting integration
    private final com.bank.vam.config.MarketProfileProperties marketProfile;

    private static final AtomicInteger loanSequence = new AtomicInteger(1);
    private static final AtomicInteger depositSequence = new AtomicInteger(1);
    
    // NEW: IHB fee constants
    private static final BigDecimal LOAN_ARRANGEMENT_FEE_RATE = new BigDecimal("0.001");   // 0.1%
    private static final BigDecimal LOAN_ARRANGEMENT_FEE_MIN = new BigDecimal("100.00");
    private static final BigDecimal LOAN_ARRANGEMENT_FEE_MAX = new BigDecimal("5000.00");
    private static final BigDecimal DEPOSIT_ARRANGEMENT_FEE_RATE = new BigDecimal("0.0005"); // 0.05%
    private static final BigDecimal DEPOSIT_ARRANGEMENT_FEE_MIN = new BigDecimal("50.00");

    // ========================================================================
    // ENTITY OPERATIONS
    // ========================================================================

    @Transactional(readOnly = true)
    public List<IhbDto.EntityResponse> getAllEntities() {
        return entityRepository.findAll().stream()
                .map(this::toEntityResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public IhbDto.EntityResponse getEntityById(UUID id) {
        IhbEntity entity = entityRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("IHB Entity not found: " + id));
        return toEntityResponse(entity);
    }

    @Transactional
    public IhbDto.EntityResponse createEntity(IhbDto.CreateEntityRequest request) {
        if (entityRepository.findByEntityCode(request.getEntityCode()).isPresent()) {
            throw new BusinessException("Entity code already exists: " + request.getEntityCode());
        }

        IhbEntity entity = new IhbEntity();
        entity.setEntityCode(request.getEntityCode());
        entity.setEntityName(request.getEntityName());
        entity.setEntityType(request.getEntityType());
        entity.setCreditLimit(request.getCreditLimit());
        entity.setAvailableLimit(request.getCreditLimit());
        entity.setLendingRateSpread(request.getLendingRateSpread());
        entity.setBorrowingRateSpread(request.getBorrowingRateSpread());
        entity.setContactName(request.getContactName());
        entity.setContactEmail(request.getContactEmail());
        entity.setStatus(IhbEntity.EntityStatus.ACTIVE);

        entity = entityRepository.save(entity);
        log.info("Created IHB entity: {} - {}", entity.getEntityCode(), entity.getEntityName());
        return toEntityResponse(entity);
    }

    // ========================================================================
    // LOAN OPERATIONS
    // ========================================================================

    @Transactional(readOnly = true)
    public List<IhbDto.LoanResponse> getAllLoans() {
        return loanRepository.findAll().stream()
                .map(this::toLoanResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<IhbDto.LoanResponse> getActiveLoans() {
        return loanRepository.findByStatus(IhbLoan.LoanStatus.ACTIVE).stream()
                .map(this::toLoanResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public IhbDto.LoanResponse createLoan(IhbDto.CreateLoanRequest request) {
        IhbEntity lender = entityRepository.findById(request.getLenderEntityId())
                .orElseThrow(() -> new ResourceNotFoundException("Lender entity not found"));
        IhbEntity borrower = entityRepository.findById(request.getBorrowerEntityId())
                .orElseThrow(() -> new ResourceNotFoundException("Borrower entity not found"));

        // Check borrower credit limit
        if (borrower.getAvailableLimit().compareTo(request.getPrincipalAmount()) < 0) {
            throw new BusinessException("Borrower credit limit exceeded");
        }

        IhbLoan loan = new IhbLoan();
        loan.setLoanReference("IHB-L-" + String.format("%06d", loanSequence.getAndIncrement()));
        loan.setLenderEntity(lender);
        loan.setLenderEntityCode(lender.getEntityCode());
        loan.setBorrowerEntity(borrower);
        loan.setBorrowerEntityCode(borrower.getEntityCode());
        loan.setPrincipalAmount(request.getPrincipalAmount());
        loan.setCurrencyCode(request.getCurrencyCode() != null ? request.getCurrencyCode() : marketProfile.getDefaultCurrency());
        loan.setOutstandingAmount(request.getPrincipalAmount());
        loan.setInterestRate(request.getInterestRate());
        loan.setInterestType(request.getInterestType());
        loan.setBaseRateType(request.getBaseRateType());
        loan.setSpread(request.getSpread());
        loan.setDisbursementDate(request.getDisbursementDate());
        loan.setMaturityDate(request.getMaturityDate());
        loan.setRepaymentFrequency(request.getRepaymentFrequency());
        loan.setStatus(IhbLoan.LoanStatus.ACTIVE);

        // Update borrower exposure
        borrower.setCurrentExposure(borrower.getCurrentExposure().add(request.getPrincipalAmount()));
        borrower.setAvailableLimit(borrower.getCreditLimit().subtract(borrower.getCurrentExposure()));
        entityRepository.save(borrower);

        loan = loanRepository.save(loan);
        
        // NEW: Post loan arrangement fee
        postLoanArrangementFee(loan, borrower);
        
        log.info("Created IHB loan: {}", loan.getLoanReference());
        return toLoanResponse(loan);
    }

    @Transactional
    public IhbDto.LoanResponse repayLoan(UUID loanId, IhbDto.LoanRepaymentRequest request) {
        IhbLoan loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan not found: " + loanId));

        BigDecimal repayment = request.getAmount().min(loan.getOutstandingAmount());
        loan.setOutstandingAmount(loan.getOutstandingAmount().subtract(repayment));

        // Update borrower exposure
        IhbEntity borrower = loan.getBorrowerEntity();
        borrower.setCurrentExposure(borrower.getCurrentExposure().subtract(repayment));
        borrower.setAvailableLimit(borrower.getCreditLimit().subtract(borrower.getCurrentExposure()));
        entityRepository.save(borrower);

        if (loan.getOutstandingAmount().compareTo(BigDecimal.ZERO) <= 0) {
            loan.setStatus(IhbLoan.LoanStatus.MATURED);
        }

        loan = loanRepository.save(loan);
        log.info("Repaid loan {}: {}", loan.getLoanReference(), repayment);
        return toLoanResponse(loan);
    }

    // ========================================================================
    // DEPOSIT OPERATIONS
    // ========================================================================

    @Transactional(readOnly = true)
    public List<IhbDto.DepositResponse> getAllDeposits() {
        return depositRepository.findAll().stream()
                .map(this::toDepositResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public IhbDto.DepositResponse createDeposit(IhbDto.CreateDepositRequest request) {
        IhbEntity depositor = entityRepository.findById(request.getDepositorEntityId())
                .orElseThrow(() -> new ResourceNotFoundException("Depositor entity not found"));

        IhbDeposit deposit = new IhbDeposit();
        deposit.setDepositReference("IHB-D-" + String.format("%06d", depositSequence.getAndIncrement()));
        deposit.setDepositorEntity(depositor);
        deposit.setDepositorEntityCode(depositor.getEntityCode());
        deposit.setPrincipalAmount(request.getPrincipalAmount());
        deposit.setCurrencyCode(request.getCurrencyCode() != null ? request.getCurrencyCode() : marketProfile.getDefaultCurrency());
        deposit.setCurrentBalance(request.getPrincipalAmount());
        deposit.setInterestRate(request.getInterestRate());
        deposit.setDepositDate(request.getDepositDate());
        deposit.setMaturityDate(request.getMaturityDate());
        deposit.setDepositType(request.getDepositType());
        deposit.setNoticePeriodDays(request.getNoticePeriodDays());
        deposit.setStatus(IhbDeposit.DepositStatus.ACTIVE);

        deposit = depositRepository.save(deposit);
        
        // NEW: Post deposit arrangement fee
        postDepositArrangementFee(deposit, depositor);
        
        log.info("Created IHB deposit: {}", deposit.getDepositReference());
        return toDepositResponse(deposit);
    }

    @Transactional
    public IhbDto.DepositResponse withdrawDeposit(UUID depositId, IhbDto.WithdrawRequest request) {
        IhbDeposit deposit = depositRepository.findById(depositId)
                .orElseThrow(() -> new ResourceNotFoundException("Deposit not found: " + depositId));

        if (deposit.getDepositType() == IhbDeposit.DepositType.FIXED) {
            if (LocalDate.now().isBefore(deposit.getMaturityDate())) {
                throw new BusinessException("Cannot withdraw fixed-term deposit before maturity");
            }
        }

        BigDecimal withdrawal = request.getAmount().min(deposit.getCurrentBalance());
        deposit.setCurrentBalance(deposit.getCurrentBalance().subtract(withdrawal));

        if (deposit.getCurrentBalance().compareTo(BigDecimal.ZERO) <= 0) {
            deposit.setStatus(IhbDeposit.DepositStatus.MATURED);
        }

        deposit = depositRepository.save(deposit);
        log.info("Withdrew from deposit {}: {}", deposit.getDepositReference(), withdrawal);
        return toDepositResponse(deposit);
    }

    // ========================================================================
    // STATS & INTEREST
    // ========================================================================

    @Transactional(readOnly = true)
    public IhbDto.IhbStatsResponse getStats() {
        IhbDto.IhbStatsResponse stats = new IhbDto.IhbStatsResponse();
        stats.setTotalEntities(entityRepository.count());
        stats.setActiveLoans(loanRepository.findByStatus(IhbLoan.LoanStatus.ACTIVE).size());
        stats.setActiveDeposits(depositRepository.findByStatus(IhbDeposit.DepositStatus.ACTIVE).size());
        stats.setTotalOutstandingLoans(loanRepository.sumOutstandingLoans());
        stats.setTotalDepositsBalance(depositRepository.sumActiveDeposits());
        stats.setNetPosition(stats.getTotalDepositsBalance().subtract(stats.getTotalOutstandingLoans()));
        return stats;
    }

    @Transactional(readOnly = true)
    public IhbDto.EntityPositionResponse getEntityPosition(UUID entityId) {
        IhbEntity entity = entityRepository.findById(entityId)
                .orElseThrow(() -> new ResourceNotFoundException("Entity not found"));

        List<IhbLoan> loansAsLender = loanRepository.findByLenderId(entityId);
        List<IhbLoan> loansAsBorrower = loanRepository.findByBorrowerId(entityId);
        List<IhbDeposit> deposits = depositRepository.findByDepositorId(entityId);

        BigDecimal totalLent = loansAsLender.stream()
                .filter(l -> l.getStatus() == IhbLoan.LoanStatus.ACTIVE)
                .map(IhbLoan::getOutstandingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalBorrowed = loansAsBorrower.stream()
                .filter(l -> l.getStatus() == IhbLoan.LoanStatus.ACTIVE)
                .map(IhbLoan::getOutstandingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalDeposited = deposits.stream()
                .filter(d -> d.getStatus() == IhbDeposit.DepositStatus.ACTIVE)
                .map(IhbDeposit::getCurrentBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        IhbDto.EntityPositionResponse response = new IhbDto.EntityPositionResponse();
        response.setEntityId(entityId);
        response.setEntityCode(entity.getEntityCode());
        response.setEntityName(entity.getEntityName());
        response.setTotalLentOut(totalLent);
        response.setTotalBorrowed(totalBorrowed);
        response.setTotalDeposited(totalDeposited);
        response.setNetPosition(totalLent.add(totalDeposited).subtract(totalBorrowed));
        response.setLoansAsLender(loansAsLender.stream().map(this::toLoanResponse).collect(Collectors.toList()));
        response.setLoansAsBorrower(loansAsBorrower.stream().map(this::toLoanResponse).collect(Collectors.toList()));
        response.setDeposits(deposits.stream().map(this::toDepositResponse).collect(Collectors.toList()));
        return response;
    }

    @Transactional
    public IhbDto.CalculateInterestResponse calculateInterest(IhbDto.CalculateInterestRequest request) {
        LocalDate calcDate = request.getCalculationDate() != null ? request.getCalculationDate() : LocalDate.now();
        
        List<IhbLoan> activeLoans = loanRepository.findByStatus(IhbLoan.LoanStatus.ACTIVE);
        List<IhbDeposit> activeDeposits = depositRepository.findByStatus(IhbDeposit.DepositStatus.ACTIVE);

        BigDecimal totalLoanInterest = BigDecimal.ZERO;
        BigDecimal totalDepositInterest = BigDecimal.ZERO;
        BigDecimal totalInterestSpread = BigDecimal.ZERO;

        // Calculate loan interest
        for (IhbLoan loan : activeLoans) {
            BigDecimal dailyRate = loan.getInterestRate().divide(BigDecimal.valueOf(36500), 10, RoundingMode.HALF_UP);
            BigDecimal interest = loan.getOutstandingAmount().multiply(dailyRate);
            loan.setAccruedInterest(loan.getAccruedInterest().add(interest));
            totalLoanInterest = totalLoanInterest.add(interest);
            
            // NEW: Calculate and post interest spread
            BigDecimal spread = calculateInterestSpread(loan, interest);
            if (spread.compareTo(BigDecimal.ZERO) > 0) {
                postInterestSpread(loan, spread);
                totalInterestSpread = totalInterestSpread.add(spread);
            }
        }
        loanRepository.saveAll(activeLoans);

        // Calculate deposit interest
        for (IhbDeposit deposit : activeDeposits) {
            BigDecimal dailyRate = deposit.getInterestRate().divide(BigDecimal.valueOf(36500), 10, RoundingMode.HALF_UP);
            BigDecimal interest = deposit.getCurrentBalance().multiply(dailyRate);
            deposit.setAccruedInterest(deposit.getAccruedInterest().add(interest));
            totalDepositInterest = totalDepositInterest.add(interest);
        }
        depositRepository.saveAll(activeDeposits);

        IhbDto.CalculateInterestResponse response = new IhbDto.CalculateInterestResponse();
        response.setCalculationDate(calcDate);
        response.setLoansProcessed(activeLoans.size());
        response.setDepositsProcessed(activeDeposits.size());
        response.setTotalLoanInterest(totalLoanInterest);
        response.setTotalDepositInterest(totalDepositInterest);

        log.info("Calculated interest: {} loans ({}), {} deposits ({}), spread: {}",
                activeLoans.size(), totalLoanInterest, activeDeposits.size(), totalDepositInterest, totalInterestSpread);
        return response;
    }
    
    // ========================================================================
    // NEW: FEE POSTING INTEGRATION
    // ========================================================================
    
    /**
     * Post loan arrangement fee to Settlement VA.
     * 
     * Fee Schedule:
     * - 0.1% of loan principal
     * - Minimum: AED 100
     * - Maximum: AED 5,000
     */
    private void postLoanArrangementFee(IhbLoan loan, IhbEntity borrower) {
        // Calculate arrangement fee
        BigDecimal arrangementFee = loan.getPrincipalAmount()
            .multiply(LOAN_ARRANGEMENT_FEE_RATE)
            .setScale(2, RoundingMode.HALF_UP);
        
        if (arrangementFee.compareTo(LOAN_ARRANGEMENT_FEE_MIN) < 0) {
            arrangementFee = LOAN_ARRANGEMENT_FEE_MIN;
        }
        if (arrangementFee.compareTo(LOAN_ARRANGEMENT_FEE_MAX) > 0) {
            arrangementFee = LOAN_ARRANGEMENT_FEE_MAX;
        }
        
        // Resolve VA for borrower entity
        UUID borrowerVaId = resolveVaForEntity(borrower.getId());
        if (borrowerVaId == null) {
            log.warn("Cannot post loan arrangement fee - no VA found for borrower {}", 
                borrower.getEntityCode());
            return;
        }
        
        try {
            feePostingService.postFee(
                borrowerVaId,
                arrangementFee,
                "IHB_LOAN_ARRANGEMENT_FEE",
                loan.getId(),
                "Intercompany loan " + loan.getLoanReference() + " arrangement fee"
            );
            log.info("Posted loan arrangement fee {} for loan {}", 
                arrangementFee, loan.getLoanReference());
        } catch (Exception e) {
            log.error("Failed to post loan arrangement fee for loan {}: {}", 
                loan.getLoanReference(), e.getMessage());
        }
    }
    
    /**
     * Post deposit arrangement fee to Settlement VA.
     * 
     * Fee Schedule:
     * - 0.05% of deposit principal
     * - Minimum: AED 50
     */
    private void postDepositArrangementFee(IhbDeposit deposit, IhbEntity depositor) {
        // Calculate arrangement fee
        BigDecimal arrangementFee = deposit.getPrincipalAmount()
            .multiply(DEPOSIT_ARRANGEMENT_FEE_RATE)
            .setScale(2, RoundingMode.HALF_UP);
        
        if (arrangementFee.compareTo(DEPOSIT_ARRANGEMENT_FEE_MIN) < 0) {
            arrangementFee = DEPOSIT_ARRANGEMENT_FEE_MIN;
        }
        
        // Resolve VA for depositor entity
        UUID depositorVaId = resolveVaForEntity(depositor.getId());
        if (depositorVaId == null) {
            log.warn("Cannot post deposit arrangement fee - no VA found for depositor {}", 
                depositor.getEntityCode());
            return;
        }
        
        try {
            feePostingService.postFee(
                depositorVaId,
                arrangementFee,
                "IHB_DEPOSIT_ARRANGEMENT_FEE",
                deposit.getId(),
                "Intercompany deposit " + deposit.getDepositReference() + " arrangement fee"
            );
            log.info("Posted deposit arrangement fee {} for deposit {}", 
                arrangementFee, deposit.getDepositReference());
        } catch (Exception e) {
            log.error("Failed to post deposit arrangement fee for deposit {}: {}", 
                deposit.getDepositReference(), e.getMessage());
        }
    }
    
    /**
     * Calculate interest spread (bank's margin between internal and customer rate).
     * 
     * Spread = Customer Interest - Internal Interest
     * Internal rate is typically base rate (e.g., EIBOR)
     */
    private BigDecimal calculateInterestSpread(IhbLoan loan, BigDecimal customerInterest) {
        if (loan.getSpread() == null || loan.getSpread().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        
        // Calculate internal rate interest (without spread)
        BigDecimal baseRate = loan.getInterestRate().subtract(loan.getSpread());
        if (baseRate.compareTo(BigDecimal.ZERO) < 0) {
            baseRate = BigDecimal.ZERO;
        }
        
        BigDecimal dailyBaseRate = baseRate.divide(BigDecimal.valueOf(36500), 10, RoundingMode.HALF_UP);
        BigDecimal internalInterest = loan.getOutstandingAmount().multiply(dailyBaseRate);
        
        // Spread = Customer interest - Internal interest
        return customerInterest.subtract(internalInterest)
            .setScale(2, RoundingMode.HALF_UP)
            .max(BigDecimal.ZERO);
    }
    
    /**
     * Post interest spread to Settlement VA.
     * 
     * The spread represents the bank's margin on intercompany loans.
     */
    private void postInterestSpread(IhbLoan loan, BigDecimal spread) {
        if (spread.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        
        // Resolve VA for borrower
        IhbEntity borrower = loan.getBorrowerEntity();
        UUID borrowerVaId = resolveVaForEntity(borrower.getId());
        if (borrowerVaId == null) {
            log.warn("Cannot post interest spread - no VA found for borrower {}", 
                borrower.getEntityCode());
            return;
        }
        
        try {
            feePostingService.postFee(
                borrowerVaId,
                spread,
                "IHB_INTEREST_SPREAD",
                loan.getId(),
                "Intercompany loan " + loan.getLoanReference() + " interest spread"
            );
            log.debug("Posted interest spread {} for loan {}", spread, loan.getLoanReference());
        } catch (Exception e) {
            log.error("Failed to post interest spread for loan {}: {}", 
                loan.getLoanReference(), e.getMessage());
        }
    }
    
    /**
     * Resolve VA ID for an IHB entity.
     * 
     * TODO: Implement proper entity-to-VA mapping. Options:
     * 1. Add settlementAccountId to IhbEntity
     * 2. Lookup VA by entity code in VirtualAccountRepository
     * 3. Use a dedicated EntityVaMappingService
     * 
     * @param entityId The IHB entity ID
     * @return VA ID or null if not found
     */
    private UUID resolveVaForEntity(UUID entityId) {
        // For now, return null - fees will be skipped until VA mapping is established
        // This is safe: main IHB operations succeed, only fee posting is deferred
        log.debug("VA resolution for IHB entity {} not yet implemented - fee posting skipped", entityId);
        return null;
    }

    // ========================================================================
    // MAPPERS
    // ========================================================================

    private IhbDto.EntityResponse toEntityResponse(IhbEntity entity) {
        IhbDto.EntityResponse dto = new IhbDto.EntityResponse();
        dto.setId(entity.getId());
        dto.setEntityCode(entity.getEntityCode());
        dto.setEntityName(entity.getEntityName());
        dto.setEntityType(entity.getEntityType());
        dto.setCreditLimit(entity.getCreditLimit());
        dto.setCurrentExposure(entity.getCurrentExposure());
        dto.setAvailableLimit(entity.getAvailableLimit());
        dto.setLendingRateSpread(entity.getLendingRateSpread());
        dto.setBorrowingRateSpread(entity.getBorrowingRateSpread());
        dto.setContactName(entity.getContactName());
        dto.setContactEmail(entity.getContactEmail());
        dto.setStatus(entity.getStatus());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }

    private IhbDto.LoanResponse toLoanResponse(IhbLoan loan) {
        IhbDto.LoanResponse dto = new IhbDto.LoanResponse();
        dto.setId(loan.getId());
        dto.setLoanReference(loan.getLoanReference());
        dto.setLenderEntityId(loan.getLenderEntity().getId());
        dto.setLenderEntityCode(loan.getLenderEntityCode());
        dto.setLenderEntityName(loan.getLenderEntity().getEntityName());
        dto.setBorrowerEntityId(loan.getBorrowerEntity().getId());
        dto.setBorrowerEntityCode(loan.getBorrowerEntityCode());
        dto.setBorrowerEntityName(loan.getBorrowerEntity().getEntityName());
        dto.setPrincipalAmount(loan.getPrincipalAmount());
        dto.setCurrencyCode(loan.getCurrencyCode());
        dto.setOutstandingAmount(loan.getOutstandingAmount());
        dto.setInterestRate(loan.getInterestRate());
        dto.setInterestType(loan.getInterestType());
        dto.setBaseRateType(loan.getBaseRateType());
        dto.setSpread(loan.getSpread());
        dto.setAccruedInterest(loan.getAccruedInterest());
        dto.setTotalInterestPaid(loan.getTotalInterestPaid());
        dto.setDisbursementDate(loan.getDisbursementDate());
        dto.setMaturityDate(loan.getMaturityDate());
        dto.setRepaymentFrequency(loan.getRepaymentFrequency());
        dto.setStatus(loan.getStatus());
        dto.setCreatedAt(loan.getCreatedAt());
        return dto;
    }

    private IhbDto.DepositResponse toDepositResponse(IhbDeposit deposit) {
        IhbDto.DepositResponse dto = new IhbDto.DepositResponse();
        dto.setId(deposit.getId());
        dto.setDepositReference(deposit.getDepositReference());
        dto.setDepositorEntityId(deposit.getDepositorEntity().getId());
        dto.setDepositorEntityCode(deposit.getDepositorEntityCode());
        dto.setDepositorEntityName(deposit.getDepositorEntity().getEntityName());
        dto.setPrincipalAmount(deposit.getPrincipalAmount());
        dto.setCurrencyCode(deposit.getCurrencyCode());
        dto.setCurrentBalance(deposit.getCurrentBalance());
        dto.setInterestRate(deposit.getInterestRate());
        dto.setAccruedInterest(deposit.getAccruedInterest());
        dto.setTotalInterestEarned(deposit.getTotalInterestEarned());
        dto.setDepositDate(deposit.getDepositDate());
        dto.setMaturityDate(deposit.getMaturityDate());
        dto.setDepositType(deposit.getDepositType());
        dto.setNoticePeriodDays(deposit.getNoticePeriodDays());
        dto.setStatus(deposit.getStatus());
        dto.setCreatedAt(deposit.getCreatedAt());
        return dto;
    }
}