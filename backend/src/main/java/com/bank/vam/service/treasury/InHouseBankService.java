package com.bank.vam.service.treasury;

import com.bank.vam.dto.treasury.InHouseBankDto;
import com.bank.vam.dto.treasury.InHouseBankDto.*;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.treasury.*;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.treasury.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
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
public class InHouseBankService {

    private final IhbEntityRepository entityRepository;
    private final IhbLoanRepository loanRepository;
    private final IhbDepositRepository depositRepository;
    private final IhbTransactionRepository transactionRepository;
    private final IhbInterestAccrualRepository accrualRepository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final com.bank.vam.config.MarketProfileProperties marketProfile;

    private static final AtomicInteger txnSequence = new AtomicInteger(1);
    private static final BigDecimal BASE_LENDING_RATE = new BigDecimal("4.0");  // Rate IHB charges
    private static final BigDecimal BASE_BORROWING_RATE = new BigDecimal("2.5"); // Rate IHB pays

    // ========================================================================
    // PARTICIPANTS
    // ========================================================================

    @Transactional(readOnly = true)
    public List<ParticipantResponse> getAllParticipants() {
        List<IhbEntity> entities = entityRepository.findAll();
        if (entities.isEmpty()) {
            return buildDemoParticipants();
        }
        return entities.stream()
                .map(this::toParticipantResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ParticipantResponse getParticipantById(UUID id) {
        IhbEntity entity = entityRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Participant not found: " + id));
        return toParticipantResponse(entity);
    }

    @Transactional
    public ParticipantResponse createParticipant(CreateParticipantRequest request) {
        if (entityRepository.findByEntityCode(request.getEntityCode()).isPresent()) {
            throw new BusinessException("Entity code already exists: " + request.getEntityCode());
        }

        IhbEntity entity = new IhbEntity();
        entity.setEntityCode(request.getEntityCode());
        entity.setEntityName(request.getName());
        entity.setEntityType(mapEntityType(request.getEntityType()));
        entity.setCreditLimit(request.getCreditLimit() != null ? request.getCreditLimit() : BigDecimal.ZERO);
        entity.setAvailableLimit(entity.getCreditLimit());
        entity.setLendingRateSpread(request.getInterestRateLend() != null ? 
                request.getInterestRateLend().subtract(BASE_BORROWING_RATE) : BigDecimal.ZERO);
        entity.setBorrowingRateSpread(request.getInterestRateBorrow() != null ? 
                request.getInterestRateBorrow().subtract(BASE_LENDING_RATE) : BigDecimal.ZERO);
        entity.setContactName(request.getContactName());
        entity.setContactEmail(request.getContactEmail());
        entity.setStatus(IhbEntity.EntityStatus.ACTIVE);

        entity = entityRepository.save(entity);
        log.info("Created IHB participant: {} - {}", entity.getEntityCode(), entity.getEntityName());
        return toParticipantResponse(entity);
    }

    @Transactional
    public ParticipantResponse updateParticipant(UUID id, UpdateParticipantRequest request) {
        IhbEntity entity = entityRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Participant not found: " + id));

        if (request.getName() != null) entity.setEntityName(request.getName());
        if (request.getCreditLimit() != null) {
            entity.setCreditLimit(request.getCreditLimit());
            entity.setAvailableLimit(request.getCreditLimit().subtract(entity.getCurrentExposure()));
        }
        if (request.getInterestRateLend() != null) {
            entity.setLendingRateSpread(request.getInterestRateLend().subtract(BASE_BORROWING_RATE));
        }
        if (request.getInterestRateBorrow() != null) {
            entity.setBorrowingRateSpread(request.getInterestRateBorrow().subtract(BASE_LENDING_RATE));
        }
        if (request.getStatus() != null) {
            entity.setStatus(mapParticipantStatus(request.getStatus()));
        }
        if (request.getContactName() != null) entity.setContactName(request.getContactName());
        if (request.getContactEmail() != null) entity.setContactEmail(request.getContactEmail());

        entity = entityRepository.save(entity);
        return toParticipantResponse(entity);
    }

    @Transactional
    public void deleteParticipant(UUID id) {
        IhbEntity entity = entityRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Participant not found: " + id));
        
        // Check for active loans/deposits
        List<IhbLoan> loans = loanRepository.findByBorrowerId(id);
        if (loans.stream().anyMatch(l -> l.getStatus() == IhbLoan.LoanStatus.ACTIVE)) {
            throw new BusinessException("Cannot delete participant with active loans");
        }
        
        entity.setStatus(IhbEntity.EntityStatus.INACTIVE);
        entityRepository.save(entity);
        log.info("Deactivated IHB participant: {}", entity.getEntityCode());
    }

    // ========================================================================
    // TRANSACTIONS
    // ========================================================================

    @Transactional(readOnly = true)
    public List<FundingTransactionResponse> getAllTransactions() {
        List<IhbTransaction> transactions = transactionRepository.findRecentTransactions(PageRequest.of(0, 100));
        if (transactions.isEmpty()) {
            return buildDemoTransactions();
        }
        return transactions.stream()
                .map(this::toTransactionResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<FundingTransactionResponse> getTransactionsByEntity(UUID entityId) {
        return transactionRepository.findByEntityId(entityId).stream()
                .map(this::toTransactionResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public FundingTransactionResponse createFundingTransaction(CreateFundingRequest request) {
        IhbEntity fromEntity = null;
        IhbEntity toEntity = null;

        if (request.getFromEntityId() != null) {
            fromEntity = entityRepository.findById(request.getFromEntityId())
                    .orElseThrow(() -> new ResourceNotFoundException("From entity not found"));
        }
        if (request.getToEntityId() != null) {
            toEntity = entityRepository.findById(request.getToEntityId())
                    .orElseThrow(() -> new ResourceNotFoundException("To entity not found"));
        }

        IhbTransaction txn = new IhbTransaction();
        txn.setTransactionRef(generateTransactionRef());
        txn.setTransactionType(mapTransactionType(request.getTransactionType()));
        txn.setFromEntity(fromEntity);
        txn.setFromEntityCode(fromEntity != null ? fromEntity.getEntityCode() : "IHB-MASTER");
        txn.setToEntity(toEntity);
        txn.setToEntityCode(toEntity != null ? toEntity.getEntityCode() : "IHB-MASTER");
        txn.setAmount(request.getAmount());
        txn.setCurrencyCode(request.getCurrency() != null ? request.getCurrency() : marketProfile.getDefaultCurrency());
        txn.setInterestRate(request.getInterestRate());
        txn.setTransactionDate(LocalDate.now());
        txn.setValueDate(request.getValueDate() != null ? request.getValueDate() : LocalDate.now());
        txn.setStatus(IhbTransaction.TransactionStatus.COMPLETED);
        txn.setDescription(request.getDescription());

        txn = transactionRepository.save(txn);

        // Create corresponding loan or deposit
        if (request.getTransactionType() == TransactionType.FUNDING && toEntity != null) {
            createLoanFromTransaction(txn, toEntity, request);
        } else if (request.getTransactionType() == TransactionType.BORROWING && fromEntity != null) {
            createDepositFromTransaction(txn, fromEntity, request);
        }

        log.info("Created IHB transaction: {} - {} {}", txn.getTransactionRef(), 
                request.getTransactionType(), request.getAmount());
        return toTransactionResponse(txn);
    }

    private void createLoanFromTransaction(IhbTransaction txn, IhbEntity borrower, CreateFundingRequest request) {
        // Find or create treasury as lender
        IhbEntity treasury = entityRepository.findByEntityCode("TREASURY")
                .orElseGet(() -> {
                    IhbEntity t = new IhbEntity();
                    t.setEntityCode("TREASURY");
                    t.setEntityName("Group Treasury (IHB Master)");
                    t.setEntityType(IhbEntity.EntityType.HEADQUARTERS);
                    t.setStatus(IhbEntity.EntityStatus.ACTIVE);
                    return entityRepository.save(t);
                });

        IhbLoan loan = new IhbLoan();
        loan.setLoanReference("IHB-L-" + txn.getTransactionRef().substring(8));
        loan.setLenderEntity(treasury);
        loan.setLenderEntityCode(treasury.getEntityCode());
        loan.setBorrowerEntity(borrower);
        loan.setBorrowerEntityCode(borrower.getEntityCode());
        loan.setPrincipalAmount(request.getAmount());
        loan.setOutstandingAmount(request.getAmount());
        loan.setCurrencyCode(request.getCurrency() != null ? request.getCurrency() : marketProfile.getDefaultCurrency());
        loan.setInterestRate(request.getInterestRate() != null ? request.getInterestRate() : BASE_LENDING_RATE);
        loan.setDisbursementDate(txn.getValueDate());
        loan.setMaturityDate(request.getMaturityDate() != null ? request.getMaturityDate() : txn.getValueDate().plusYears(1));
        loan.setStatus(IhbLoan.LoanStatus.ACTIVE);
        loanRepository.save(loan);

        // Update borrower exposure
        borrower.setCurrentExposure(borrower.getCurrentExposure().add(request.getAmount()));
        borrower.setAvailableLimit(borrower.getCreditLimit().subtract(borrower.getCurrentExposure()));
        entityRepository.save(borrower);
    }

    private void createDepositFromTransaction(IhbTransaction txn, IhbEntity depositor, CreateFundingRequest request) {
        IhbDeposit deposit = new IhbDeposit();
        deposit.setDepositReference("IHB-D-" + txn.getTransactionRef().substring(8));
        deposit.setDepositorEntity(depositor);
        deposit.setDepositorEntityCode(depositor.getEntityCode());
        deposit.setPrincipalAmount(request.getAmount());
        deposit.setCurrentBalance(request.getAmount());
        deposit.setCurrencyCode(request.getCurrency() != null ? request.getCurrency() : marketProfile.getDefaultCurrency());
        deposit.setInterestRate(request.getInterestRate() != null ? request.getInterestRate() : BASE_BORROWING_RATE);
        deposit.setDepositDate(txn.getValueDate());
        deposit.setDepositType(IhbDeposit.DepositType.CALL);
        deposit.setStatus(IhbDeposit.DepositStatus.ACTIVE);
        depositRepository.save(deposit);
    }

    // ========================================================================
    // INTEREST ACCRUALS
    // ========================================================================

    @Transactional(readOnly = true)
    public List<InterestAccrualResponse> getAllAccruals() {
        List<IhbInterestAccrual> accruals = accrualRepository.findAll();
        if (accruals.isEmpty()) {
            return buildDemoAccruals();
        }
        return accruals.stream()
                .map(this::toAccrualResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<InterestAccrualResponse> getAccrualsByPeriod(LocalDate start, LocalDate end) {
        return accrualRepository.findByPeriod(start, end).stream()
                .map(this::toAccrualResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public CalculateInterestResponse calculateInterest(CalculateInterestRequest request) {
        LocalDate periodStart = request.getPeriodStart() != null ? request.getPeriodStart() : 
                YearMonth.now().atDay(1);
        LocalDate periodEnd = request.getPeriodEnd() != null ? request.getPeriodEnd() : 
                LocalDate.now();

        List<IhbEntity> entities = entityRepository.findByStatus(IhbEntity.EntityStatus.ACTIVE);
        List<InterestAccrualResponse> accrualResponses = new ArrayList<>();
        BigDecimal totalPayable = BigDecimal.ZERO;
        BigDecimal totalReceivable = BigDecimal.ZERO;

        int dayCount = (int) java.time.temporal.ChronoUnit.DAYS.between(periodStart, periodEnd) + 1;

        for (IhbEntity entity : entities) {
            if (entity.getEntityType() == IhbEntity.EntityType.HEADQUARTERS) continue;

            // Calculate net position
            BigDecimal deposits = depositRepository.findByDepositorId(entity.getId()).stream()
                    .filter(d -> d.getStatus() == IhbDeposit.DepositStatus.ACTIVE)
                    .map(IhbDeposit::getCurrentBalance)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal loans = loanRepository.findByBorrowerId(entity.getId()).stream()
                    .filter(l -> l.getStatus() == IhbLoan.LoanStatus.ACTIVE)
                    .map(IhbLoan::getOutstandingAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal netPosition = deposits.subtract(loans);

            if (netPosition.compareTo(BigDecimal.ZERO) == 0) continue;

            IhbInterestAccrual.PositionType posType = netPosition.compareTo(BigDecimal.ZERO) > 0 ?
                    IhbInterestAccrual.PositionType.SURPLUS : IhbInterestAccrual.PositionType.DEFICIT;

            BigDecimal rate = posType == IhbInterestAccrual.PositionType.SURPLUS ?
                    BASE_BORROWING_RATE.add(entity.getLendingRateSpread()) :
                    BASE_LENDING_RATE.add(entity.getBorrowingRateSpread());

            // Calculate interest: (balance * rate * days) / 36500
            BigDecimal interest = netPosition.abs()
                    .multiply(rate)
                    .multiply(BigDecimal.valueOf(dayCount))
                    .divide(BigDecimal.valueOf(36500), 2, RoundingMode.HALF_UP);

            // Accrued amount is positive for surplus (payable by IHB), negative for deficit (receivable by IHB)
            BigDecimal accruedAmount = posType == IhbInterestAccrual.PositionType.SURPLUS ? 
                    interest : interest.negate();

            IhbInterestAccrual accrual = new IhbInterestAccrual();
            accrual.setEntity(entity);
            accrual.setEntityCode(entity.getEntityCode());
            accrual.setPeriodStart(periodStart);
            accrual.setPeriodEnd(periodEnd);
            accrual.setPositionType(posType);
            accrual.setAverageBalance(netPosition.abs());
            accrual.setInterestRate(rate);
            accrual.setAccruedAmount(accruedAmount);
            accrual.setStatus(request.isPostImmediately() ? 
                    IhbInterestAccrual.AccrualStatus.POSTED : IhbInterestAccrual.AccrualStatus.ACCRUED);
            accrual.setCalculatedAt(LocalDateTime.now());

            accrual = accrualRepository.save(accrual);
            accrualResponses.add(toAccrualResponse(accrual));

            if (posType == IhbInterestAccrual.PositionType.SURPLUS) {
                totalPayable = totalPayable.add(interest);
            } else {
                totalReceivable = totalReceivable.add(interest);
            }
        }

        log.info("Calculated interest for period {} to {}: {} entities, payable={}, receivable={}",
                periodStart, periodEnd, accrualResponses.size(), totalPayable, totalReceivable);

        return CalculateInterestResponse.builder()
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .entitiesProcessed(accrualResponses.size())
                .totalInterestPayable(totalPayable)
                .totalInterestReceivable(totalReceivable)
                .netSpreadIncome(totalReceivable.subtract(totalPayable))
                .accruals(accrualResponses)
                .build();
    }

    @Transactional
    public List<InterestAccrualResponse> postInterest(PostInterestRequest request) {
        List<IhbInterestAccrual> accruals = accrualRepository.findAllById(request.getAccrualIds());
        LocalDate postingDate = request.getPostingDate() != null ? request.getPostingDate() : LocalDate.now();

        for (IhbInterestAccrual accrual : accruals) {
            if (accrual.getStatus() != IhbInterestAccrual.AccrualStatus.ACCRUED) continue;

            // Create transaction
            IhbTransaction txn = new IhbTransaction();
            txn.setTransactionRef(generateTransactionRef());
            txn.setTransactionType(accrual.getPositionType() == IhbInterestAccrual.PositionType.SURPLUS ?
                    IhbTransaction.TransactionType.INTEREST_CREDIT : IhbTransaction.TransactionType.INTEREST_DEBIT);
            txn.setToEntity(accrual.getEntity());
            txn.setToEntityCode(accrual.getEntityCode());
            txn.setFromEntityCode("IHB-MASTER");
            txn.setAmount(accrual.getAccruedAmount().abs());
            txn.setCurrencyCode(accrual.getCurrencyCode());
            txn.setInterestRate(accrual.getInterestRate());
            txn.setTransactionDate(postingDate);
            txn.setValueDate(postingDate);
            txn.setStatus(IhbTransaction.TransactionStatus.COMPLETED);
            txn.setDescription("Interest for " + accrual.getPeriodStart() + " to " + accrual.getPeriodEnd());
            txn.setSourceType("ACCRUAL");
            txn.setSourceId(accrual.getId());
            transactionRepository.save(txn);

            // Update accrual
            accrual.setStatus(IhbInterestAccrual.AccrualStatus.POSTED);
            accrual.setPostedDate(postingDate);
            accrual.setPostingReference(txn.getTransactionRef());
            accrual.setPostedAt(LocalDateTime.now());
        }

        accrualRepository.saveAll(accruals);
        return accruals.stream().map(this::toAccrualResponse).collect(Collectors.toList());
    }

    // ========================================================================
    // SUMMARY / STATS
    // ========================================================================

    @Transactional(readOnly = true)
    public IhbSummaryResponse getSummary() {
        List<IhbEntity> entities = entityRepository.findByStatus(IhbEntity.EntityStatus.ACTIVE);
        
        if (entities.isEmpty()) {
            return buildDemoSummary();
        }

        // Find treasury master
        BigDecimal poolBalance = depositRepository.sumActiveDeposits()
                .subtract(loanRepository.sumOutstandingLoans());

        int surplusCount = 0, deficitCount = 0, neutralCount = 0;
        BigDecimal totalSurplus = BigDecimal.ZERO, totalDeficit = BigDecimal.ZERO;

        for (IhbEntity entity : entities) {
            if (entity.getEntityType() == IhbEntity.EntityType.HEADQUARTERS) continue;
            
            BigDecimal position = calculateNetPosition(entity.getId());
            if (position.compareTo(BigDecimal.ZERO) > 0) {
                surplusCount++;
                totalSurplus = totalSurplus.add(position);
            } else if (position.compareTo(BigDecimal.ZERO) < 0) {
                deficitCount++;
                totalDeficit = totalDeficit.add(position.abs());
            } else {
                neutralCount++;
            }
        }

        // Calculate MTD interest
        LocalDate monthStart = YearMonth.now().atDay(1);
        BigDecimal mtdInterest = accrualRepository.sumInterestForMonth(monthStart, LocalDate.now());

        return IhbSummaryResponse.builder()
                .ihbPoolBalance(poolBalance)
                .participantCount(entities.size() - 1) // Exclude treasury
                .totalSurplus(totalSurplus)
                .totalDeficit(totalDeficit)
                .netInterestMTD(mtdInterest)
                .surplusEntityCount(surplusCount)
                .deficitEntityCount(deficitCount)
                .neutralEntityCount(neutralCount)
                .masterCurrency(marketProfile.getDefaultCurrency())
                .lastCalculationDate(LocalDateTime.now())
                .build();
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private BigDecimal calculateNetPosition(UUID entityId) {
        BigDecimal deposits = depositRepository.findByDepositorId(entityId).stream()
                .filter(d -> d.getStatus() == IhbDeposit.DepositStatus.ACTIVE)
                .map(IhbDeposit::getCurrentBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal loans = loanRepository.findByBorrowerId(entityId).stream()
                .filter(l -> l.getStatus() == IhbLoan.LoanStatus.ACTIVE)
                .map(IhbLoan::getOutstandingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return deposits.subtract(loans);
    }

    private String generateTransactionRef() {
        return "IHB-TXN-" + LocalDate.now().getYear() + "-" + 
                String.format("%06d", txnSequence.getAndIncrement());
    }

    // ========================================================================
    // MAPPERS
    // ========================================================================

    private ParticipantResponse toParticipantResponse(IhbEntity entity) {
        BigDecimal netPosition = calculateNetPosition(entity.getId());
        BigDecimal accruedInterest = accrualRepository.sumAccruedInterestByEntity(entity.getId());

        Position position;
        if (netPosition.compareTo(BigDecimal.ZERO) > 0) position = Position.SURPLUS;
        else if (netPosition.compareTo(BigDecimal.ZERO) < 0) position = Position.DEFICIT;
        else position = Position.NEUTRAL;

        // Try to find linked virtual account
        String vaNumber = null;
        UUID vaId = null;
        Page<VirtualAccount> vas = virtualAccountRepository.search(entity.getEntityName(), PageRequest.of(0, 1));
        if (!vas.isEmpty()) {
            vaNumber = vas.getContent().get(0).getVaNumber();
            vaId = vas.getContent().get(0).getId();
        }

        return ParticipantResponse.builder()
                .id(entity.getId())
                .name(entity.getEntityName())
                .entityCode(entity.getEntityCode())
                .entityType(mapToEntityType(entity.getEntityType()))
                .virtualAccountId(vaId)
                .virtualAccountNumber(vaNumber != null ? vaNumber : "VA-" + entity.getEntityCode())
                .currency(marketProfile.getDefaultCurrency())
                .currentBalance(netPosition)
                .availableBalance(entity.getAvailableLimit())
                .position(position)
                .creditLimit(entity.getCreditLimit())
                .interestRateLend(BASE_BORROWING_RATE.add(entity.getLendingRateSpread()))
                .interestRateBorrow(BASE_LENDING_RATE.add(entity.getBorrowingRateSpread()))
                .accruedInterest(accruedInterest != null ? accruedInterest : BigDecimal.ZERO)
                .status(mapToParticipantStatus(entity.getStatus()))
                .joinedDate(entity.getCreatedAt() != null ? entity.getCreatedAt().toLocalDate() : LocalDate.now())
                .build();
    }

    private FundingTransactionResponse toTransactionResponse(IhbTransaction txn) {
        return FundingTransactionResponse.builder()
                .id(txn.getId())
                .transactionRef(txn.getTransactionRef())
                .transactionType(mapToTransactionType(txn.getTransactionType()))
                .fromEntity(txn.getFromEntity() != null ? txn.getFromEntity().getEntityName() : "Group Treasury")
                .fromEntityId(txn.getFromEntity() != null ? txn.getFromEntity().getId() : null)
                .toEntity(txn.getToEntity() != null ? txn.getToEntity().getEntityName() : "Group Treasury")
                .toEntityId(txn.getToEntity() != null ? txn.getToEntity().getId() : null)
                .amount(txn.getAmount())
                .currency(txn.getCurrencyCode())
                .interestRate(txn.getInterestRate())
                .transactionDate(txn.getTransactionDate())
                .valueDate(txn.getValueDate())
                .status(mapToTransactionStatus(txn.getStatus()))
                .description(txn.getDescription())
                .createdAt(txn.getCreatedAt())
                .build();
    }

    private InterestAccrualResponse toAccrualResponse(IhbInterestAccrual accrual) {
        return InterestAccrualResponse.builder()
                .id(accrual.getId())
                .periodStart(accrual.getPeriodStart())
                .periodEnd(accrual.getPeriodEnd())
                .entityId(accrual.getEntity().getId())
                .entityName(accrual.getEntity().getEntityName())
                .position(accrual.getPositionType() == IhbInterestAccrual.PositionType.SURPLUS ? 
                        Position.SURPLUS : Position.DEFICIT)
                .avgBalance(accrual.getAverageBalance())
                .rate(accrual.getInterestRate())
                .accruedAmount(accrual.getAccruedAmount())
                .status(mapToAccrualStatus(accrual.getStatus()))
                .calculatedAt(accrual.getCalculatedAt())
                .build();
    }

    // Type mappers
    private IhbEntity.EntityType mapEntityType(EntityType type) {
        return switch (type) {
            case PARENT -> IhbEntity.EntityType.HEADQUARTERS;
            case SUBSIDIARY -> IhbEntity.EntityType.SUBSIDIARY;
            case BRANCH, DIVISION -> IhbEntity.EntityType.BRANCH;
        };
    }

    private EntityType mapToEntityType(IhbEntity.EntityType type) {
        return switch (type) {
            case HEADQUARTERS -> EntityType.PARENT;
            case SUBSIDIARY -> EntityType.SUBSIDIARY;
            case BRANCH -> EntityType.BRANCH;
        };
    }

    private IhbEntity.EntityStatus mapParticipantStatus(ParticipantStatus status) {
        return switch (status) {
            case ACTIVE -> IhbEntity.EntityStatus.ACTIVE;
            case INACTIVE -> IhbEntity.EntityStatus.INACTIVE;
            case SUSPENDED -> IhbEntity.EntityStatus.SUSPENDED;
        };
    }

    private ParticipantStatus mapToParticipantStatus(IhbEntity.EntityStatus status) {
        return switch (status) {
            case ACTIVE -> ParticipantStatus.ACTIVE;
            case INACTIVE -> ParticipantStatus.INACTIVE;
            case SUSPENDED -> ParticipantStatus.SUSPENDED;
        };
    }

    private IhbTransaction.TransactionType mapTransactionType(TransactionType type) {
        return switch (type) {
            case FUNDING -> IhbTransaction.TransactionType.FUNDING;
            case BORROWING -> IhbTransaction.TransactionType.BORROWING;
            case INTEREST_CREDIT -> IhbTransaction.TransactionType.INTEREST_CREDIT;
            case INTEREST_DEBIT -> IhbTransaction.TransactionType.INTEREST_DEBIT;
        };
    }

    private TransactionType mapToTransactionType(IhbTransaction.TransactionType type) {
        return switch (type) {
            case FUNDING -> TransactionType.FUNDING;
            case BORROWING -> TransactionType.BORROWING;
            case INTEREST_CREDIT -> TransactionType.INTEREST_CREDIT;
            case INTEREST_DEBIT -> TransactionType.INTEREST_DEBIT;
            case REPAYMENT, WITHDRAWAL -> TransactionType.FUNDING;
        };
    }

    private TransactionStatus mapToTransactionStatus(IhbTransaction.TransactionStatus status) {
        return switch (status) {
            case PENDING -> TransactionStatus.PENDING;
            case COMPLETED -> TransactionStatus.COMPLETED;
            case REVERSED -> TransactionStatus.REVERSED;
        };
    }

    private AccrualStatus mapToAccrualStatus(IhbInterestAccrual.AccrualStatus status) {
        return switch (status) {
            case ACCRUED -> AccrualStatus.ACCRUED;
            case POSTED -> AccrualStatus.POSTED;
            case SETTLED, REVERSED -> AccrualStatus.SETTLED;
        };
    }

    // ========================================================================
    // DEMO DATA
    // ========================================================================

    private List<ParticipantResponse> buildDemoParticipants() {
        return Arrays.asList(
            ParticipantResponse.builder()
                .id(UUID.randomUUID()).name("Group Treasury (IHB Master)").entityCode("TREASURY")
                .entityType(EntityType.PARENT).virtualAccountNumber("VA-IHB-MASTER").currency(marketProfile.getDefaultCurrency())
                .currentBalance(new BigDecimal("25000000")).availableBalance(new BigDecimal("25000000"))
                .position(Position.SURPLUS).creditLimit(BigDecimal.ZERO)
                .interestRateLend(new BigDecimal("3.0")).interestRateBorrow(new BigDecimal("4.0"))
                .accruedInterest(BigDecimal.ZERO).status(ParticipantStatus.ACTIVE)
                .joinedDate(LocalDate.of(2023, 1, 1)).build(),
            ParticipantResponse.builder()
                .id(UUID.randomUUID()).name("Dubai Operations").entityCode("DXB-OPS")
                .entityType(EntityType.SUBSIDIARY).virtualAccountNumber("VA-DXB-001").currency(marketProfile.getDefaultCurrency())
                .currentBalance(new BigDecimal("8500000")).availableBalance(new BigDecimal("8500000"))
                .position(Position.SURPLUS).creditLimit(new BigDecimal("5000000"))
                .interestRateLend(new BigDecimal("2.5")).interestRateBorrow(new BigDecimal("4.0"))
                .accruedInterest(new BigDecimal("17708")).status(ParticipantStatus.ACTIVE)
                .joinedDate(LocalDate.of(2023, 1, 15)).build(),
            ParticipantResponse.builder()
                .id(UUID.randomUUID()).name("Abu Dhabi Manufacturing").entityCode("AUH-MFG")
                .entityType(EntityType.SUBSIDIARY).virtualAccountNumber("VA-AUH-001").currency(marketProfile.getDefaultCurrency())
                .currentBalance(new BigDecimal("-3500000")).availableBalance(new BigDecimal("1500000"))
                .position(Position.DEFICIT).creditLimit(new BigDecimal("5000000"))
                .interestRateLend(new BigDecimal("2.5")).interestRateBorrow(new BigDecimal("4.5"))
                .accruedInterest(new BigDecimal("-13125")).status(ParticipantStatus.ACTIVE)
                .joinedDate(LocalDate.of(2023, 2, 1)).build(),
            ParticipantResponse.builder()
                .id(UUID.randomUUID()).name("European Subsidiary").entityCode("EU-SUB")
                .entityType(EntityType.SUBSIDIARY).virtualAccountNumber("VA-EU-001").currency("EUR")
                .currentBalance(new BigDecimal("4200000")).availableBalance(new BigDecimal("4200000"))
                .position(Position.SURPLUS).creditLimit(new BigDecimal("3000000"))
                .interestRateLend(new BigDecimal("2.0")).interestRateBorrow(new BigDecimal("3.5"))
                .accruedInterest(new BigDecimal("7000")).status(ParticipantStatus.ACTIVE)
                .joinedDate(LocalDate.of(2023, 3, 15)).build(),
            ParticipantResponse.builder()
                .id(UUID.randomUUID()).name("Singapore Trading").entityCode("SG-TRD")
                .entityType(EntityType.SUBSIDIARY).virtualAccountNumber("VA-SG-001").currency("USD")
                .currentBalance(new BigDecimal("-1800000")).availableBalance(new BigDecimal("1200000"))
                .position(Position.DEFICIT).creditLimit(new BigDecimal("3000000"))
                .interestRateLend(new BigDecimal("2.0")).interestRateBorrow(new BigDecimal("4.0"))
                .accruedInterest(new BigDecimal("-6000")).status(ParticipantStatus.ACTIVE)
                .joinedDate(LocalDate.of(2023, 4, 1)).build(),
            ParticipantResponse.builder()
                .id(UUID.randomUUID()).name("Sharjah Warehouse").entityCode("SHJ-WH")
                .entityType(EntityType.BRANCH).virtualAccountNumber("VA-SHJ-001").currency(marketProfile.getDefaultCurrency())
                .currentBalance(new BigDecimal("1200000")).availableBalance(new BigDecimal("1200000"))
                .position(Position.NEUTRAL).creditLimit(new BigDecimal("2000000"))
                .interestRateLend(new BigDecimal("2.5")).interestRateBorrow(new BigDecimal("4.0"))
                .accruedInterest(new BigDecimal("2500")).status(ParticipantStatus.ACTIVE)
                .joinedDate(LocalDate.of(2023, 5, 1)).build()
        );
    }

    private List<FundingTransactionResponse> buildDemoTransactions() {
        return Arrays.asList(
            FundingTransactionResponse.builder()
                .id(UUID.randomUUID()).transactionRef("IHB-TXN-2024-001").transactionType(TransactionType.BORROWING)
                .fromEntity("Dubai Operations").toEntity("Group Treasury")
                .amount(new BigDecimal("5000000")).currency(marketProfile.getDefaultCurrency()).interestRate(new BigDecimal("2.5"))
                .transactionDate(LocalDate.of(2024, 2, 12)).valueDate(LocalDate.of(2024, 2, 12))
                .status(TransactionStatus.COMPLETED).description("Surplus funding to IHB").build(),
            FundingTransactionResponse.builder()
                .id(UUID.randomUUID()).transactionRef("IHB-TXN-2024-002").transactionType(TransactionType.FUNDING)
                .fromEntity("Group Treasury").toEntity("Abu Dhabi Manufacturing")
                .amount(new BigDecimal("3500000")).currency(marketProfile.getDefaultCurrency()).interestRate(new BigDecimal("4.5"))
                .transactionDate(LocalDate.of(2024, 2, 12)).valueDate(LocalDate.of(2024, 2, 12))
                .status(TransactionStatus.COMPLETED).description("Deficit funding from IHB").build(),
            FundingTransactionResponse.builder()
                .id(UUID.randomUUID()).transactionRef("IHB-TXN-2024-003").transactionType(TransactionType.FUNDING)
                .fromEntity("Group Treasury").toEntity("Singapore Trading")
                .amount(new BigDecimal("1800000")).currency("USD").interestRate(new BigDecimal("4.0"))
                .transactionDate(LocalDate.of(2024, 2, 10)).valueDate(LocalDate.of(2024, 2, 10))
                .status(TransactionStatus.COMPLETED).description("Working capital funding").build(),
            FundingTransactionResponse.builder()
                .id(UUID.randomUUID()).transactionRef("IHB-TXN-2024-004").transactionType(TransactionType.INTEREST_CREDIT)
                .fromEntity("Group Treasury").toEntity("Dubai Operations")
                .amount(new BigDecimal("17708")).currency(marketProfile.getDefaultCurrency()).interestRate(new BigDecimal("2.5"))
                .transactionDate(LocalDate.of(2024, 1, 31)).valueDate(LocalDate.of(2024, 1, 31))
                .status(TransactionStatus.COMPLETED).description("January interest on surplus").build()
        );
    }

    private List<InterestAccrualResponse> buildDemoAccruals() {
        return Arrays.asList(
            InterestAccrualResponse.builder()
                .id(UUID.randomUUID()).periodStart(LocalDate.of(2024, 2, 1)).periodEnd(LocalDate.of(2024, 2, 12))
                .entityName("Dubai Operations").position(Position.SURPLUS)
                .avgBalance(new BigDecimal("8500000")).rate(new BigDecimal("2.5"))
                .accruedAmount(new BigDecimal("6979")).status(AccrualStatus.ACCRUED).build(),
            InterestAccrualResponse.builder()
                .id(UUID.randomUUID()).periodStart(LocalDate.of(2024, 2, 1)).periodEnd(LocalDate.of(2024, 2, 12))
                .entityName("Abu Dhabi Manufacturing").position(Position.DEFICIT)
                .avgBalance(new BigDecimal("3500000")).rate(new BigDecimal("4.5"))
                .accruedAmount(new BigDecimal("-5178")).status(AccrualStatus.ACCRUED).build(),
            InterestAccrualResponse.builder()
                .id(UUID.randomUUID()).periodStart(LocalDate.of(2024, 2, 1)).periodEnd(LocalDate.of(2024, 2, 12))
                .entityName("European Subsidiary").position(Position.SURPLUS)
                .avgBalance(new BigDecimal("4200000")).rate(new BigDecimal("2.0"))
                .accruedAmount(new BigDecimal("2767")).status(AccrualStatus.ACCRUED).build(),
            InterestAccrualResponse.builder()
                .id(UUID.randomUUID()).periodStart(LocalDate.of(2024, 2, 1)).periodEnd(LocalDate.of(2024, 2, 12))
                .entityName("Singapore Trading").position(Position.DEFICIT)
                .avgBalance(new BigDecimal("1800000")).rate(new BigDecimal("4.0"))
                .accruedAmount(new BigDecimal("-2367")).status(AccrualStatus.ACCRUED).build()
        );
    }

    private IhbSummaryResponse buildDemoSummary() {
        return IhbSummaryResponse.builder()
                .ihbPoolBalance(new BigDecimal("25000000"))
                .participantCount(5)
                .totalSurplus(new BigDecimal("13900000"))
                .totalDeficit(new BigDecimal("5300000"))
                .netInterestMTD(new BigDecimal("8583"))
                .surplusEntityCount(3)
                .deficitEntityCount(2)
                .neutralEntityCount(1)
                .masterCurrency(marketProfile.getDefaultCurrency())
                .lastCalculationDate(LocalDateTime.now())
                .build();
    }
}
