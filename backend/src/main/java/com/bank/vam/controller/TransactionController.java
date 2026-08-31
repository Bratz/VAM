package com.bank.vam.controller;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.dto.TransactionDto;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * TransactionController - REST API for transaction operations.
 * 
 * All responses are wrapped in ApiResponse for frontend compatibility.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@Tag(name = "Transactions", description = "Transaction operations including transfers, payments, and POBO")
public class TransactionController {

    private final TransactionService transactionService;
    private final VirtualAccountRepository virtualAccountRepository;

    // ========================================================================
    // LIST & SEARCH
    // ========================================================================

    @GetMapping
    @Operation(summary = "List transactions with optional filtering")
    public ResponseEntity<ApiResponse<TransactionDto.TransactionListResponse>> getAllTransactions(
            @RequestParam(required = false) String movementType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "transactionDate") String sortBy,
            @RequestParam(defaultValue = "desc") String sortOrder) {
        
        log.debug("GET /transactions - movementType={}, status={}, page={}", movementType, status, page);
        
        Sort sort = sortOrder.equalsIgnoreCase("asc") ? 
            Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, pageSize, sort);
        
        Page<Transaction> transactions;
        
        if (movementType != null && !movementType.isEmpty()) {
            try {
                Transaction.MovementType type = Transaction.MovementType.valueOf(movementType);
                transactions = transactionService.getByMovementType(type, pageable);
            } catch (IllegalArgumentException e) {
                transactions = transactionService.getAll(pageable);
            }
        } else if (status != null && !status.isEmpty()) {
            try {
                Transaction.TransactionStatus txnStatus = Transaction.TransactionStatus.valueOf(status);
                transactions = transactionService.getByStatus(txnStatus, pageable);
            } catch (IllegalArgumentException e) {
                transactions = transactionService.getAll(pageable);
            }
        } else {
            transactions = transactionService.getAll(pageable);
        }
        
        TransactionDto.TransactionListResponse response = TransactionDto.TransactionListResponse.builder()
            .content(transactions.getContent().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList()))
            .page(page)
            .pageSize(pageSize)
            .totalElements(transactions.getTotalElements())
            .totalPages(transactions.getTotalPages())
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ========================================================================
    // STATS & RECENT - MUST BE BEFORE /{id}
    // ========================================================================

    @GetMapping("/stats")
    @Operation(summary = "Get transaction statistics")
    public ResponseEntity<ApiResponse<TransactionDto.TransactionStatsResponse>> getStats(
            @RequestParam(required = false) UUID corporateId) {
        
        TransactionDto.TransactionStatsResponse stats = transactionService.getTransactionStats(corporateId);
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @GetMapping("/recent")
    @Operation(summary = "Get recent transactions")
    public ResponseEntity<ApiResponse<List<TransactionDto.TransactionResponse>>> getRecentTransactions(
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) UUID corporateId) {
        
        List<Transaction> transactions = transactionService.getRecentTransactions(limit, corporateId);
        List<TransactionDto.TransactionResponse> response = transactions.stream()
            .map(this::mapToResponse)
            .collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ========================================================================
    // READ OPERATIONS
    // ========================================================================

    @GetMapping("/{id}")
    @Operation(summary = "Get transaction by ID with related transactions")
    public ResponseEntity<ApiResponse<TransactionDto.TransactionDetailResponse>> getById(
            @PathVariable UUID id) {
        
        Transaction txn = transactionService.getById(id);
        
        // Fetch related transactions by correlationId
        List<Transaction> relatedTxns = null;
        if (txn.getCorrelationId() != null && !txn.getCorrelationId().isEmpty()) {
            relatedTxns = transactionService.getByCorrelationId(txn.getCorrelationId());
            relatedTxns = relatedTxns.stream()
                .filter(t -> !t.getId().equals(txn.getId()))
                .collect(Collectors.toList());
        }
        
        return ResponseEntity.ok(ApiResponse.success(mapToDetailResponse(txn, relatedTxns)));
    }

    @GetMapping("/{id}/iso-message")
    @Operation(summary = "Get ISO 20022 XML message for a transaction")
    public ResponseEntity<ApiResponse<TransactionDto.IsoMessageResponse>> getIsoMessage(
            @PathVariable UUID id) {
        
        Transaction txn = transactionService.getById(id);
        
        // Only generate for outbound payments
        if (txn.getMovementType() != Transaction.MovementType.DEBIT && 
            txn.getMovementType() != Transaction.MovementType.POBO_DEBIT) {
            return ResponseEntity.ok(ApiResponse.success(null));
        }
        
        VirtualAccount sourceVa = txn.getVaId() != null ? 
            virtualAccountRepository.findById(txn.getVaId()).orElse(null) : null;
        
        String xml = generatePain001Xml(txn, sourceVa);
        String messageId = "MSG" + txn.getReferenceNumber();
        
        TransactionDto.IsoMessageResponse response = TransactionDto.IsoMessageResponse.builder()
            .messageType("pain.001.001.03")
            .messageId(messageId)
            .xml(xml)
            .transactionId(txn.getId())
            .referenceNumber(txn.getReferenceNumber())
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/reference/{referenceNumber}")
    @Operation(summary = "Get transaction by reference number")
    public ResponseEntity<ApiResponse<TransactionDto.TransactionDetailResponse>> getByReference(
            @PathVariable String referenceNumber) {
        
        Transaction txn = transactionService.getByReference(referenceNumber);
        
        List<Transaction> relatedTxns = null;
        if (txn.getCorrelationId() != null && !txn.getCorrelationId().isEmpty()) {
            relatedTxns = transactionService.getByCorrelationId(txn.getCorrelationId());
            relatedTxns = relatedTxns.stream()
                .filter(t -> !t.getId().equals(txn.getId()))
                .collect(Collectors.toList());
        }
        
        return ResponseEntity.ok(ApiResponse.success(mapToDetailResponse(txn, relatedTxns)));
    }

    @GetMapping("/va/{vaId}")
    @Operation(summary = "Get transactions for a VA")
    public ResponseEntity<ApiResponse<TransactionDto.TransactionListResponse>> getByVaId(
            @PathVariable UUID vaId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        Pageable pageable = PageRequest.of(page, size, Sort.by("transactionDate").descending());
        Page<Transaction> transactions = transactionService.getByVaId(vaId, pageable);
        
        TransactionDto.TransactionListResponse response = TransactionDto.TransactionListResponse.builder()
            .content(transactions.getContent().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList()))
            .page(page)
            .pageSize(size)
            .totalElements(transactions.getTotalElements())
            .totalPages(transactions.getTotalPages())
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/correlation/{correlationId}")
    @Operation(summary = "Get all transactions by correlation ID")
    public ResponseEntity<ApiResponse<List<TransactionDto.TransactionResponse>>> getByCorrelationId(
            @PathVariable String correlationId) {
        
        List<Transaction> transactions = transactionService.getByCorrelationId(correlationId);
        List<TransactionDto.TransactionResponse> response = transactions.stream()
            .map(this::mapToResponse)
            .collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/va/{vaId}/recent")
    @Operation(summary = "Get recent transactions for a VA")
    public ResponseEntity<ApiResponse<List<TransactionDto.TransactionResponse>>> getRecentByVaId(
            @PathVariable UUID vaId,
            @RequestParam(defaultValue = "10") int limit) {

        List<Transaction> transactions = transactionService.getRecentByVaId(vaId, limit);
        List<TransactionDto.TransactionResponse> response = transactions.stream()
            .map(this::mapToResponse)
            .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ========================================================================
    // GROUPED BUSINESS VIEW (Option B - Simplified View)
    // ========================================================================

    @GetMapping("/grouped")
    @Operation(summary = "Get transactions grouped by business operation",
               description = "Returns transactions grouped by correlationId, showing net effect to user's account " +
                           "with expandable accounting entries. This provides a simplified business view where " +
                           "multi-leg operations (4-leg collections/payments) appear as single line items.\n\n" +
                           "By default, only shows entries for operating VAs (CFO view). " +
                           "Use includeInternal=true to see full audit trail with Shadow VA and Settlement VA entries.")
    public ResponseEntity<ApiResponse<TransactionDto.GroupedTransactionListResponse>> getGroupedTransactions(
            @RequestParam(required = false) UUID vaId,
            @RequestParam(required = false) UUID corporateId,
            @RequestParam(required = false) String direction,  // INBOUND, OUTBOUND, ALL
            @RequestParam(defaultValue = "false") boolean includeInternal,  // Show Shadow VA & Settlement VA entries
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {

        log.debug("GET /transactions/grouped - vaId={}, corporateId={}, direction={}, includeInternal={}, page={}",
                  vaId, corporateId, direction, includeInternal, page);

        TransactionDto.GroupedTransactionListResponse response =
            transactionService.getGroupedTransactions(vaId, corporateId, direction, includeInternal, page, pageSize);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/grouped/{correlationId}")
    @Operation(summary = "Get single grouped transaction with all accounting entries",
               description = "Returns a single business operation with all its accounting legs expanded.\n\n" +
                           "By default, only shows entries for operating VAs (CFO view). " +
                           "Use includeInternal=true to see full audit trail with Shadow VA and Settlement VA entries.")
    public ResponseEntity<ApiResponse<TransactionDto.GroupedTransactionResponse>> getGroupedTransactionByCorrelationId(
            @PathVariable String correlationId,
            @RequestParam(required = false) UUID userVaId,
            @RequestParam(defaultValue = "false") boolean includeInternal) {  // Show Shadow VA & Settlement VA entries

        log.debug("GET /transactions/grouped/{} - userVaId={}, includeInternal={}", correlationId, userVaId, includeInternal);

        TransactionDto.GroupedTransactionResponse response =
            transactionService.getGroupedTransactionByCorrelationId(correlationId, userVaId, includeInternal);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/va/{vaId}/grouped")
    @Operation(summary = "Get grouped transactions for a specific VA",
               description = "Returns transactions for a VA grouped by business operation.\n\n" +
                           "By default, only shows entries for operating VAs (CFO view). " +
                           "Use includeInternal=true to see full audit trail with Shadow VA and Settlement VA entries.")
    public ResponseEntity<ApiResponse<TransactionDto.GroupedTransactionListResponse>> getGroupedTransactionsByVaId(
            @PathVariable UUID vaId,
            @RequestParam(required = false) String direction,
            @RequestParam(defaultValue = "false") boolean includeInternal,  // Show Shadow VA & Settlement VA entries
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {

        log.debug("GET /transactions/va/{}/grouped - direction={}, includeInternal={}, page={}", vaId, direction, includeInternal, page);

        TransactionDto.GroupedTransactionListResponse response =
            transactionService.getGroupedTransactions(vaId, null, direction, includeInternal, page, pageSize);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ========================================================================
    // OPERATIONS
    // ========================================================================

    @PostMapping("/credit")
    @Operation(summary = "Credit a VA")
    public ResponseEntity<ApiResponse<TransactionDto.TransactionResponse>> credit(
            @Valid @RequestBody TransactionDto.CreditRequest request) {
        
        Transaction txn = transactionService.credit(request);
        return ResponseEntity.ok(ApiResponse.success(mapToResponse(txn)));
    }

    @PostMapping("/debit")
    @Operation(summary = "Debit a VA")
    public ResponseEntity<ApiResponse<TransactionDto.TransactionResponse>> debit(
            @Valid @RequestBody TransactionDto.DebitRequest request) {
        
        Transaction txn = transactionService.debit(request);
        return ResponseEntity.ok(ApiResponse.success(mapToResponse(txn)));
    }

    @PostMapping("/transfer/preview")
    @Operation(summary = "Preview transfer to show fees before execution")
    public ResponseEntity<ApiResponse<TransactionDto.TransferPreviewResponse>> previewTransfer(
            @Valid @RequestBody TransactionDto.TransferPreviewRequest request) {

        TransactionDto.TransferPreviewResponse preview = transactionService.previewTransfer(request);
        return ResponseEntity.ok(ApiResponse.success(preview));
    }

    @PostMapping("/transfer")
    @Operation(summary = "Transfer between VAs via Settlement VA")
    public ResponseEntity<ApiResponse<TransactionDto.TransactionResponse>> transfer(
            @Valid @RequestBody TransactionDto.TransferRequest request) {

        Transaction txn = transactionService.transfer(request);
        return ResponseEntity.ok(ApiResponse.success(mapToResponse(txn)));
    }

    @PostMapping("/payment/preview")
    @Operation(summary = "Preview outbound payment to show fees before execution")
    public ResponseEntity<ApiResponse<TransactionDto.PaymentPreviewResponse>> previewPayment(
            @Valid @RequestBody TransactionDto.PaymentPreviewRequest request) {

        TransactionDto.PaymentPreviewResponse preview = transactionService.previewPayment(request);
        return ResponseEntity.ok(ApiResponse.success(preview));
    }

    @PostMapping("/bulk-transfer")
    @Operation(summary = "Bulk transfer")
    public ResponseEntity<ApiResponse<TransactionDto.BulkTransferResponse>> bulkTransfer(
            @Valid @RequestBody TransactionDto.BulkTransferRequest request) {
        
        int successCount = 0;
        int failedCount = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        List<TransactionDto.TransferResult> results = new java.util.ArrayList<>();
        
        for (TransactionDto.TransferItem item : request.getTransfers()) {
            try {
                TransactionDto.TransferRequest transferRequest = TransactionDto.TransferRequest.builder()
                    .fromVaId(request.getSourceVaId())
                    .toVaId(item.getDestinationVaId())
                    .amount(item.getAmount())
                    .description(item.getDescription() != null ? item.getDescription() : request.getDescription())
                    .valueDate(item.getValueDate())
                    .build();
                
                Transaction txn = transactionService.transfer(transferRequest);
                
                results.add(TransactionDto.TransferResult.builder()
                    .destinationVaId(item.getDestinationVaId())
                    .amount(item.getAmount())
                    .status("SUCCESS")
                    .referenceNumber(txn.getReferenceNumber())
                    .build());
                
                successCount++;
                totalAmount = totalAmount.add(item.getAmount());
            } catch (Exception e) {
                results.add(TransactionDto.TransferResult.builder()
                    .destinationVaId(item.getDestinationVaId())
                    .amount(item.getAmount())
                    .status("FAILED")
                    .errorMessage(e.getMessage())
                    .build());
                failedCount++;
            }
        }
        
        TransactionDto.BulkTransferResponse response = TransactionDto.BulkTransferResponse.builder()
            .totalCount(request.getTransfers().size())
            .successCount(successCount)
            .failedCount(failedCount)
            .totalAmount(totalAmount)
            .results(results)
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/payment")
    @Operation(summary = "Outbound payment via Shadow VA")
    public ResponseEntity<ApiResponse<TransactionDto.PaymentResponse>> makePayment(
            @Valid @RequestBody TransactionDto.PaymentRequest request) {
        
        Transaction txn = transactionService.makePayment(request);
        
        TransactionDto.PaymentResponse response = TransactionDto.PaymentResponse.builder()
            .transactionId(txn.getId())
            .referenceNumber(txn.getReferenceNumber())
            .correlationId(txn.getCorrelationId())
            .status(txn.getStatus().name())
            .sourceVaId(txn.getVaId())
            .amount(txn.getAmount())
            .feeAmount(txn.getFeeAmount())
            .totalDebited(txn.getAmount().add(txn.getFeeAmount() != null ? txn.getFeeAmount() : BigDecimal.ZERO))
            .currencyCode(txn.getCurrencyCode())
            .beneficiaryName(txn.getBeneficiaryName())
            .beneficiaryAccount(txn.getBeneficiaryAccount())
            .transactionDate(txn.getTransactionDate())
            .valueDate(txn.getValueDate())
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/pobo")
    @Operation(summary = "POBO payment")
    public ResponseEntity<ApiResponse<TransactionDto.PoboPaymentResponse>> makePoboPayment(
            @Valid @RequestBody TransactionDto.PoboPaymentRequest request) {
        
        Transaction txn = transactionService.makePoboPayment(request);
        
        TransactionDto.PoboPaymentResponse response = TransactionDto.PoboPaymentResponse.builder()
            .transactionId(txn.getId())
            .referenceNumber(txn.getReferenceNumber())
            .correlationId(txn.getCorrelationId())
            .status(txn.getStatus().name())
            .ownerVaId(txn.getVaId())
            .amount(txn.getAmount())
            .poboFee(txn.getFeeAmount())
            .totalDebited(txn.getAmount().add(txn.getFeeAmount() != null ? txn.getFeeAmount() : BigDecimal.ZERO))
            .currencyCode(txn.getCurrencyCode())
            .beneficiaryName(txn.getBeneficiaryName())
            .beneficiaryAccount(txn.getBeneficiaryAccount())
            .behalfOfEntity(txn.getBehalfOfEntity())
            .transactionDate(txn.getTransactionDate())
            .valueDate(txn.getValueDate())
            .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ========================================================================
    // MAPPING HELPERS
    // ========================================================================

    private TransactionDto.TransactionResponse mapToResponse(Transaction txn) {
        String vaNumber = null;
        String vaName = null;
        if (txn.getVaId() != null) {
            VirtualAccount va = virtualAccountRepository.findById(txn.getVaId()).orElse(null);
            if (va != null) {
                vaNumber = va.getVaNumber();
                vaName = va.getVaName();
            }
        }
        
        String counterpartyName = null;
        String counterpartyAccount = null;
        if (txn.getCounterpartyVaId() != null) {
            VirtualAccount counterpartyVa = virtualAccountRepository.findById(txn.getCounterpartyVaId()).orElse(null);
            if (counterpartyVa != null) {
                counterpartyName = counterpartyVa.getVaName();
                counterpartyAccount = counterpartyVa.getVaNumber();
            }
        }
        if (counterpartyName == null) {
            counterpartyName = txn.getBeneficiaryName();
        }
        if (counterpartyAccount == null) {
            counterpartyAccount = txn.getBeneficiaryAccount();
        }
        
        return TransactionDto.TransactionResponse.builder()
            .id(txn.getId())
            .referenceNumber(txn.getReferenceNumber())
            .movementType(txn.getMovementType() != null ? txn.getMovementType().name() : null)
            .transactionCategory(txn.getTransactionCategory() != null ? txn.getTransactionCategory().name() : null)
            .amount(txn.getAmount())
            .currencyCode(txn.getCurrencyCode())
            .vaId(txn.getVaId())
            .vaNumber(vaNumber)
            .vaName(vaName)
            .counterpartyVaId(txn.getCounterpartyVaId())
            .counterpartyName(counterpartyName)
            .counterpartyAccount(counterpartyAccount)
            .description(txn.getDescription())
            .channel(txn.getChannel())
            .status(txn.getStatus() != null ? txn.getStatus().name() : null)
            .transactionDate(txn.getTransactionDate())
            .valueDate(txn.getValueDate())
            .createdAt(txn.getCreatedAt())
            .balanceBefore(txn.getBalanceBefore())
            .balanceAfter(txn.getBalanceAfter())
            .feeAmount(txn.getFeeAmount())
            .isPobo(txn.getIsPobo())
            .behalfOfEntity(txn.getBehalfOfEntity())
            .behalfOfVaId(txn.getBehalfOfVaId())
            .externalReference(txn.getExternalReference())
            .bancsReference(txn.getBancsReference())
            .correlationId(txn.getCorrelationId())
            .build();
    }

    private TransactionDto.TransactionDetailResponse mapToDetailResponse(Transaction txn, List<Transaction> relatedTxns) {
        String vaNumber = null;
        String vaName = null;
        if (txn.getVaId() != null) {
            VirtualAccount va = virtualAccountRepository.findById(txn.getVaId()).orElse(null);
            if (va != null) {
                vaNumber = va.getVaNumber();
                vaName = va.getVaName();
            }
        }

        // Resolve counterparty info from counterpartyVaId
        String counterpartyName = null;
        String counterpartyAccount = null;
        String counterpartyVaNumber = null;
        if (txn.getCounterpartyVaId() != null) {
            VirtualAccount counterpartyVa = virtualAccountRepository.findById(txn.getCounterpartyVaId()).orElse(null);
            if (counterpartyVa != null) {
                counterpartyName = counterpartyVa.getVaName();
                counterpartyAccount = counterpartyVa.getVaNumber();
                counterpartyVaNumber = counterpartyVa.getVaNumber();
            }
        }
        // Fall back to beneficiary info for external transactions
        if (counterpartyName == null) {
            counterpartyName = txn.getBeneficiaryName();
        }
        if (counterpartyAccount == null) {
            counterpartyAccount = txn.getBeneficiaryAccount();
        }

        // Map related transactions
        List<TransactionDto.RelatedTransaction> relatedList = null;
        if (relatedTxns != null && !relatedTxns.isEmpty()) {
            relatedList = relatedTxns.stream()
                .map(rt -> {
                    String rtVaNumber = null;
                    String rtVaName = null;
                    if (rt.getVaId() != null) {
                        VirtualAccount rtVa = virtualAccountRepository.findById(rt.getVaId()).orElse(null);
                        if (rtVa != null) {
                            rtVaNumber = rtVa.getVaNumber();
                            rtVaName = rtVa.getVaName();
                        }
                    }
                    return TransactionDto.RelatedTransaction.builder()
                        .id(rt.getId())
                        .referenceNumber(rt.getReferenceNumber())
                        .movementType(rt.getMovementType() != null ? rt.getMovementType().name() : null)
                        .amount(rt.getAmount())
                        .vaNumber(rtVaNumber)
                        .vaName(rtVaName)
                        .status(rt.getStatus() != null ? rt.getStatus().name() : null)
                        .build();
                })
                .collect(Collectors.toList());
        }
        
        return TransactionDto.TransactionDetailResponse.builder()
            .id(txn.getId())
            .referenceNumber(txn.getReferenceNumber())
            .movementType(txn.getMovementType() != null ? txn.getMovementType().name() : null)
            .transactionCategory(txn.getTransactionCategory() != null ? txn.getTransactionCategory().name() : null)
            .amount(txn.getAmount())
            .currencyCode(txn.getCurrencyCode())
            .vaId(txn.getVaId())
            .vaNumber(vaNumber)
            .vaName(vaName)
            .corporateId(txn.getCorporateId())
            .physicalAccountId(txn.getPhysicalAccountId())
            .legalEntityId(txn.getLegalEntityId())
            .counterpartyVaId(txn.getCounterpartyVaId())
            .counterpartyVaNumber(counterpartyVaNumber)
            .counterpartyName(counterpartyName)
            .counterpartyAccount(counterpartyAccount)
            .remitterName(txn.getRemitterName())
            .remitterAccount(txn.getRemitterAccount())
            .beneficiaryName(txn.getBeneficiaryName())
            .beneficiaryAccount(txn.getBeneficiaryAccount())
            .description(txn.getDescription())
            .channel(txn.getChannel())
            .status(txn.getStatus() != null ? txn.getStatus().name() : null)
            .transactionDate(txn.getTransactionDate())
            .valueDate(txn.getValueDate())
            .createdAt(txn.getCreatedAt())
            .updatedAt(txn.getUpdatedAt())
            .balanceBefore(txn.getBalanceBefore())
            .balanceAfter(txn.getBalanceAfter())
            .feeAmount(txn.getFeeAmount())
            .feeBreakdown(txn.getFeeBreakdown())
            .isPobo(txn.getIsPobo())
            .isRobo(txn.getIsRobo())
            .behalfOfEntity(txn.getBehalfOfEntity())
            .behalfOfVaId(txn.getBehalfOfVaId())
            .externalReference(txn.getExternalReference())
            .bancsReference(txn.getBancsReference())
            .correlationId(txn.getCorrelationId())
            .processingNotes(txn.getProcessingNotes())
            .initiatedBy(txn.getInitiatedBy())
            .approvedBy(txn.getApprovedBy())
            .relatedTransactions(relatedList)
            .build();
    }

    private String generatePain001Xml(Transaction txn, VirtualAccount sourceVa) {
        String debtorName = sourceVa != null ? sourceVa.getVaName() : "Unknown";
        String debtorAccount = sourceVa != null ? sourceVa.getVaNumber() : "";
        String debtorBic = "TEABORGG";
        
        String creditorName = txn.getBeneficiaryName() != null ? txn.getBeneficiaryName() : "Unknown";
        String creditorAccount = txn.getBeneficiaryAccount() != null ? txn.getBeneficiaryAccount() : "";
        
        String msgId = "MSG" + txn.getReferenceNumber();
        String pmtInfId = "PMT" + txn.getReferenceNumber();
        String endToEndId = txn.getCorrelationId() != null ? txn.getCorrelationId() : txn.getReferenceNumber();
        
        String creationDateTime = DateTimeFormatter.ISO_DATE_TIME
            .format(txn.getTransactionDate().atZone(ZoneId.systemDefault()));
        
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pain.001.001.03\">\n");
        xml.append("  <CstmrCdtTrfInitn>\n");
        xml.append("    <GrpHdr>\n");
        xml.append("      <MsgId>").append(escapeXml(msgId)).append("</MsgId>\n");
        xml.append("      <CreDtTm>").append(creationDateTime).append("</CreDtTm>\n");
        xml.append("      <NbOfTxs>1</NbOfTxs>\n");
        xml.append("      <CtrlSum>").append(txn.getAmount()).append("</CtrlSum>\n");
        xml.append("      <InitgPty>\n");
        xml.append("        <Nm>").append(escapeXml(debtorName)).append("</Nm>\n");
        xml.append("      </InitgPty>\n");
        xml.append("    </GrpHdr>\n");
        xml.append("    <PmtInf>\n");
        xml.append("      <PmtInfId>").append(escapeXml(pmtInfId)).append("</PmtInfId>\n");
        xml.append("      <PmtMtd>TRF</PmtMtd>\n");
        xml.append("      <NbOfTxs>1</NbOfTxs>\n");
        xml.append("      <CtrlSum>").append(txn.getAmount()).append("</CtrlSum>\n");
        xml.append("      <ReqdExctnDt>").append(txn.getValueDate()).append("</ReqdExctnDt>\n");
        xml.append("      <Dbtr>\n");
        xml.append("        <Nm>").append(escapeXml(debtorName)).append("</Nm>\n");
        xml.append("      </Dbtr>\n");
        xml.append("      <DbtrAcct>\n");
        xml.append("        <Id><Othr><Id>").append(escapeXml(debtorAccount)).append("</Id></Othr></Id>\n");
        xml.append("        <Ccy>").append(txn.getCurrencyCode()).append("</Ccy>\n");
        xml.append("      </DbtrAcct>\n");
        xml.append("      <DbtrAgt>\n");
        xml.append("        <FinInstnId><BIC>").append(debtorBic).append("</BIC></FinInstnId>\n");
        xml.append("      </DbtrAgt>\n");
        xml.append("      <CdtTrfTxInf>\n");
        xml.append("        <PmtId>\n");
        xml.append("          <EndToEndId>").append(escapeXml(endToEndId)).append("</EndToEndId>\n");
        xml.append("        </PmtId>\n");
        xml.append("        <Amt>\n");
        xml.append("          <InstdAmt Ccy=\"").append(txn.getCurrencyCode()).append("\">")
           .append(txn.getAmount()).append("</InstdAmt>\n");
        xml.append("        </Amt>\n");
        xml.append("        <Cdtr>\n");
        xml.append("          <Nm>").append(escapeXml(creditorName)).append("</Nm>\n");
        xml.append("        </Cdtr>\n");
        xml.append("        <CdtrAcct>\n");
        xml.append("          <Id>\n");
        if (creditorAccount.length() > 20) {
            xml.append("            <IBAN>").append(escapeXml(creditorAccount)).append("</IBAN>\n");
        } else {
            xml.append("            <Othr><Id>").append(escapeXml(creditorAccount)).append("</Id></Othr>\n");
        }
        xml.append("          </Id>\n");
        xml.append("        </CdtrAcct>\n");
        if (txn.getDescription() != null && !txn.getDescription().isEmpty()) {
            xml.append("        <RmtInf>\n");
            xml.append("          <Ustrd>").append(escapeXml(txn.getDescription())).append("</Ustrd>\n");
            xml.append("        </RmtInf>\n");
        }
        xml.append("      </CdtTrfTxInf>\n");
        xml.append("    </PmtInf>\n");
        xml.append("  </CstmrCdtTrfInitn>\n");
        xml.append("</Document>");
        
        return xml.toString();
    }
    
    private String escapeXml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
}