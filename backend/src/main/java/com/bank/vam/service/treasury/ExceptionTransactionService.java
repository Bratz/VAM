package com.bank.vam.service.treasury;

import com.bank.vam.dto.treasury.ExceptionTransactionDto.*;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.Transaction.MovementType;
import com.bank.vam.entity.Transaction.TransactionStatus;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.treasury.ExceptionTransaction;
import com.bank.vam.entity.treasury.ExceptionTransaction.ExceptionStatus;
import com.bank.vam.entity.treasury.ExceptionTransaction.ExceptionType;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.treasury.ExceptionTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ExceptionTransactionService {

    private final ExceptionTransactionRepository exceptionRepository;
    private final VirtualAccountRepository vaRepository;
    private final TransactionRepository transactionRepository;

    // ========================================================================
    // QUERIES
    // ========================================================================

    @Transactional(readOnly = true)
    public ExceptionListResponse listExceptions(UUID programId, ExceptionStatus status,
                                                  ExceptionType type, String currency,
                                                  int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        
        Page<ExceptionTransaction> exceptionPage = exceptionRepository.findWithFilters(
            programId, status, type, currency, pageable);
        
        List<ExceptionResponse> exceptions = exceptionPage.getContent().stream()
            .map(this::toExceptionResponse)
            .collect(Collectors.toList());
        
        ExceptionSummary summary = getExceptionSummary(programId);
        
        return ExceptionListResponse.builder()
            .exceptions(exceptions)
            .summary(summary)
            .totalElements((int) exceptionPage.getTotalElements())
            .totalPages(exceptionPage.getTotalPages())
            .currentPage(page)
            .build();
    }

    @Transactional(readOnly = true)
    public ExceptionResponse getException(UUID exceptionId) {
        ExceptionTransaction exception = exceptionRepository.findById(exceptionId)
            .orElseThrow(() -> new ResourceNotFoundException("Exception not found: " + exceptionId));
        return toExceptionResponse(exception);
    }

    @Transactional(readOnly = true)
    public ExceptionSummary getExceptionSummary(UUID programId) {
        List<Object[]> statusSummary = exceptionRepository.getSummaryByStatus();
        List<Object[]> typeSummary = exceptionRepository.getOpenSummaryByType();
        
        Map<String, Long> countByType = new HashMap<>();
        Map<String, BigDecimal> amountByType = new HashMap<>();
        
        for (Object[] row : typeSummary) {
            ExceptionType type = (ExceptionType) row[0];
            Long count = (Long) row[1];
            BigDecimal amount = (BigDecimal) row[2];
            countByType.put(type.name(), count);
            amountByType.put(type.name(), amount);
        }
        
        long openCount = 0, inProgressCount = 0, resolvedCount = 0, writtenOffCount = 0, returnedCount = 0;
        BigDecimal openAmount = BigDecimal.ZERO, inProgressAmount = BigDecimal.ZERO, resolvedAmount = BigDecimal.ZERO;
        
        for (Object[] row : statusSummary) {
            ExceptionStatus status = (ExceptionStatus) row[0];
            Long count = (Long) row[1];
            BigDecimal amount = (BigDecimal) row[2];
            
            switch (status) {
                case OPEN -> { openCount = count; openAmount = amount; }
                case IN_PROGRESS -> { inProgressCount = count; inProgressAmount = amount; }
                case RESOLVED -> { resolvedCount = count; resolvedAmount = amount; }
                case WRITTEN_OFF -> writtenOffCount = count;
                case RETURNED -> returnedCount = count;
            }
        }
        
        return ExceptionSummary.builder()
            .openCount(openCount)
            .inProgressCount(inProgressCount)
            .resolvedCount(resolvedCount)
            .writtenOffCount(writtenOffCount)
            .returnedCount(returnedCount)
            .openAmount(openAmount)
            .inProgressAmount(inProgressAmount)
            .resolvedAmount(resolvedAmount)
            .countByType(countByType)
            .amountByType(amountByType)
            .build();
    }

    @Transactional(readOnly = true)
    public List<ExceptionResponse> getOpenExceptionsByVa(UUID exceptionVaId) {
        return exceptionRepository.findOpenByExceptionVaId(exceptionVaId).stream()
            .map(this::toExceptionResponse)
            .collect(Collectors.toList());
    }

    // ========================================================================
    // ACTIONS
    // ========================================================================

    public ExceptionResponse startInvestigation(UUID exceptionId, String investigator) {
        ExceptionTransaction exception = exceptionRepository.findById(exceptionId)
            .orElseThrow(() -> new ResourceNotFoundException("Exception not found: " + exceptionId));
        
        if (exception.getStatus() != ExceptionStatus.OPEN) {
            throw new BusinessException("Can only start investigation on OPEN exceptions");
        }
        
        exception.startInvestigation(investigator);
        exception = exceptionRepository.save(exception);
        
        log.info("Started investigation on exception {} by {}", 
            exception.getExceptionNumber(), investigator);
        
        return toExceptionResponse(exception);
    }

    public ExceptionResponse allocateException(UUID exceptionId, UUID targetVaId, String notes) {
        ExceptionTransaction exception = exceptionRepository.findById(exceptionId)
            .orElseThrow(() -> new ResourceNotFoundException("Exception not found: " + exceptionId));
        
        if (!exception.canBeResolved()) {
            throw new BusinessException("Exception cannot be resolved in current status: " + exception.getStatus());
        }
        
        VirtualAccount targetVa = vaRepository.findById(targetVaId)
            .orElseThrow(() -> new ResourceNotFoundException("Target VA not found: " + targetVaId));
        
        VirtualAccount exceptionVa = vaRepository.findById(exception.getExceptionVaId())
            .orElseThrow(() -> new ResourceNotFoundException("Exception VA not found"));
        
        String correlationId = "ALLOC-" + System.currentTimeMillis();
        
        // 1. Debit Exception VA
        BigDecimal exceptionVaBalanceBefore = exceptionVa.getCurrentBalance();
        exceptionVa.debit(exception.getAmount());
        vaRepository.save(exceptionVa);
        
        Transaction debitTxn = Transaction.builder()
            .referenceNumber(Transaction.generateReference(MovementType.EXCEPTION_RELEASE))
            .movementType(MovementType.EXCEPTION_RELEASE)
            .corporateId(exceptionVa.getCorporateId())
            .vaId(exceptionVa.getId())
            .physicalAccountId(exceptionVa.getPhysicalAccountId())
            .programId(exceptionVa.getProgramId())
            .amount(exception.getAmount())
            .currencyCode(exception.getCurrencyCode())
            .balanceBefore(exceptionVaBalanceBefore)
            .balanceAfter(exceptionVa.getCurrentBalance())
            .counterpartyVaId(targetVaId)
            .correlationId(correlationId)
            .description("Exception release: " + exception.getExceptionNumber())
            .status(TransactionStatus.COMPLETED)
            .transactionDate(LocalDateTime.now())
            .valueDate(LocalDate.now())
            .channel("TREASURY")
            .build();
        transactionRepository.save(debitTxn);
        
        // 2. Credit Target VA
        BigDecimal targetVaBalanceBefore = targetVa.getCurrentBalance();
        targetVa.credit(exception.getAmount());
        vaRepository.save(targetVa);
        
        Transaction creditTxn = Transaction.builder()
            .referenceNumber(Transaction.generateReference(MovementType.CREDIT))
            .movementType(MovementType.CREDIT)
            .corporateId(targetVa.getCorporateId())
            .vaId(targetVa.getId())
            .physicalAccountId(targetVa.getPhysicalAccountId())
            .programId(targetVa.getProgramId())
            .amount(exception.getAmount())
            .currencyCode(exception.getCurrencyCode())
            .balanceBefore(targetVaBalanceBefore)
            .balanceAfter(targetVa.getCurrentBalance())
            .counterpartyVaId(exceptionVa.getId())
            .correlationId(correlationId)
            .description("Exception allocation from: " + exception.getExceptionNumber())
            .status(TransactionStatus.COMPLETED)
            .transactionDate(LocalDateTime.now())
            .valueDate(LocalDate.now())
            .channel("TREASURY")
            .build();
        transactionRepository.save(creditTxn);
        
        // 3. Update exception status
        // Get current user from security context in real implementation
        exception.resolve(targetVaId, notes, "Treasury User");
        exception = exceptionRepository.save(exception);
        
        log.info("Allocated exception {} to VA {} - amount {}", 
            exception.getExceptionNumber(), targetVa.getVaNumber(), exception.getAmount());
        
        return toExceptionResponse(exception);
    }

    public ExceptionResponse writeOffException(UUID exceptionId, String reason) {
        ExceptionTransaction exception = exceptionRepository.findById(exceptionId)
            .orElseThrow(() -> new ResourceNotFoundException("Exception not found: " + exceptionId));
        
        if (!exception.canBeResolved()) {
            throw new BusinessException("Exception cannot be written off in current status");
        }
        
        // Get current user from security context in real implementation
        exception.writeOff(reason, "Treasury Manager");
        exception = exceptionRepository.save(exception);
        
        log.info("Wrote off exception {}: {}", exception.getExceptionNumber(), reason);
        
        return toExceptionResponse(exception);
    }

    // ========================================================================
    // SUGGESTIONS
    // ========================================================================

    @Transactional(readOnly = true)
    public List<SuggestedAllocationResponse> getSuggestedAllocations(UUID exceptionId) {
        ExceptionTransaction exception = exceptionRepository.findById(exceptionId)
            .orElseThrow(() -> new ResourceNotFoundException("Exception not found: " + exceptionId));
        
        List<SuggestedAllocationResponse> suggestions = new ArrayList<>();
        
        // If original VA exists, suggest it
        if (exception.getOriginalVaId() != null) {
            vaRepository.findById(exception.getOriginalVaId()).ifPresent(va -> {
                suggestions.add(SuggestedAllocationResponse.builder()
                    .vaId(va.getId())
                    .vaNumber(va.getVaNumber())
                    .vaName(va.getVaName())
                    .matchReason("Original source VA")
                    .confidence(90)
                    .build());
            });
        }
        
        // Search by remitter info
        if (exception.getRemitterInfo() != null) {
            List<VirtualAccount> matches = vaRepository.searchAll(exception.getRemitterInfo());
            for (VirtualAccount va : matches.subList(0, Math.min(3, matches.size()))) {
                if (!suggestions.stream().anyMatch(s -> s.getVaId().equals(va.getId()))) {
                    suggestions.add(SuggestedAllocationResponse.builder()
                        .vaId(va.getId())
                        .vaNumber(va.getVaNumber())
                        .vaName(va.getVaName())
                        .matchReason("Name match: " + exception.getRemitterInfo())
                        .confidence(70)
                        .build());
                }
            }
        }
        
        return suggestions;
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private ExceptionResponse toExceptionResponse(ExceptionTransaction exception) {
        String originalVaNumber = null;
        if (exception.getOriginalVaId() != null) {
            originalVaNumber = vaRepository.findById(exception.getOriginalVaId())
                .map(VirtualAccount::getVaNumber).orElse(null);
        }
        
        String exceptionVaNumber = vaRepository.findById(exception.getExceptionVaId())
            .map(VirtualAccount::getVaNumber).orElse(null);
        
        String targetVaNumber = null;
        if (exception.getTargetVaId() != null) {
            targetVaNumber = vaRepository.findById(exception.getTargetVaId())
                .map(VirtualAccount::getVaNumber).orElse(null);
        }
        
        return ExceptionResponse.builder()
            .id(exception.getId())
            .exceptionNumber(exception.getExceptionNumber())
            .exceptionType(exception.getExceptionType().name())
            .exceptionTypeDisplay(formatExceptionType(exception.getExceptionType()))
            .amount(exception.getAmount())
            .currency(exception.getCurrencyCode())
            .status(exception.getStatus().name())
            .bankReference(exception.getBankReference())
            .remitterInfo(exception.getRemitterInfo())
            .description(exception.getDescription())
            .originalVaId(exception.getOriginalVaId())
            .originalVaNumber(originalVaNumber)
            .originalTransactionId(exception.getOriginalTransactionId())
            .exceptionVaId(exception.getExceptionVaId())
            .exceptionVaNumber(exceptionVaNumber)
            .targetVaId(exception.getTargetVaId())
            .targetVaNumber(targetVaNumber)
            .resolutionNotes(exception.getResolutionNotes())
            .resolvedBy(exception.getResolvedBy())
            .resolvedAt(exception.getResolvedAt())
            .daysSinceCreated(exception.getDaysSinceCreated())
            .agingBucket(exception.getAgingBucket())
            .createdAt(exception.getCreatedAt())
            .build();
    }

    private String formatExceptionType(ExceptionType type) {
        return switch (type) {
            case UNMATCHED_PAYMENT -> "Unmatched Payment";
            case RECONCILIATION_DIFF -> "Reconciliation Difference";
            case FAILED_PAYMENT -> "Failed Payment";
            case INVALID_VIBAN -> "Invalid VIBAN";
            case AMOUNT_MISMATCH -> "Amount Mismatch";
            case DUPLICATE_PAYMENT -> "Duplicate Payment";
            case BANK_INTEREST -> "Bank Interest";
            case FX_DIFFERENCE -> "FX Difference";
            case SYSTEM_ERROR -> "System Error";
            case MISSING_SETTLEMENT_VA -> "Missing Settlement VA";
            default -> type.name();
        };
    }
}