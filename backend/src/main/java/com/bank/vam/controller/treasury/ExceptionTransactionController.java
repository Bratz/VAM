package com.bank.vam.controller.treasury;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.dto.treasury.ExceptionTransactionDto.*;
import com.bank.vam.entity.treasury.ExceptionTransaction;
import com.bank.vam.entity.treasury.ExceptionTransaction.ExceptionStatus;
import com.bank.vam.entity.treasury.ExceptionTransaction.ExceptionType;
import com.bank.vam.service.treasury.ExceptionTransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/treasury/exceptions")
@RequiredArgsConstructor
@Slf4j
public class ExceptionTransactionController {

    private final ExceptionTransactionService exceptionService;

    /**
     * List exception transactions with filters.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<ExceptionListResponse>> listExceptions(
            @RequestParam(required = false) UUID programId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String currency,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        ExceptionStatus statusEnum = status != null ? ExceptionStatus.valueOf(status) : null;
        ExceptionType typeEnum = type != null ? ExceptionType.valueOf(type) : null;
        
        ExceptionListResponse response = exceptionService.listExceptions(
            programId, statusEnum, typeEnum, currency, page, size);
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Get exception details.
     */
    @GetMapping("/{exceptionId}")
    public ResponseEntity<ApiResponse<ExceptionResponse>> getException(
            @PathVariable UUID exceptionId) {
        
        ExceptionResponse response = exceptionService.getException(exceptionId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Get exception summary by program.
     */
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<ExceptionSummary>> getExceptionSummary(
            @RequestParam(required = false) UUID programId) {
        
        ExceptionSummary summary = exceptionService.getExceptionSummary(programId);
        return ResponseEntity.ok(ApiResponse.success(summary));
    }

    /**
     * Start investigation on an exception.
     */
    @PostMapping("/{exceptionId}/investigate")
    public ResponseEntity<ApiResponse<ExceptionResponse>> startInvestigation(
            @PathVariable UUID exceptionId,
            @RequestBody StartInvestigationRequest request) {
        
        log.info("Starting investigation on exception: {}", exceptionId);
        
        ExceptionResponse response = exceptionService.startInvestigation(
            exceptionId, request.getInvestigator());
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Allocate exception to target VA.
     */
    @PostMapping("/{exceptionId}/allocate")
    public ResponseEntity<ApiResponse<ExceptionResponse>> allocateException(
            @PathVariable UUID exceptionId,
            @RequestBody AllocateExceptionRequest request) {
        
        log.info("Allocating exception {} to VA {}", exceptionId, request.getTargetVaId());
        
        ExceptionResponse response = exceptionService.allocateException(
            exceptionId, request.getTargetVaId(), request.getNotes());
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Write off exception.
     */
    @PostMapping("/{exceptionId}/write-off")
    public ResponseEntity<ApiResponse<ExceptionResponse>> writeOffException(
            @PathVariable UUID exceptionId,
            @RequestBody WriteOffExceptionRequest request) {
        
        log.info("Writing off exception: {}", exceptionId);
        
        ExceptionResponse response = exceptionService.writeOffException(
            exceptionId, request.getReason());
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Get suggested allocations for an exception.
     */
    @GetMapping("/{exceptionId}/suggestions")
    public ResponseEntity<ApiResponse<List<SuggestedAllocationResponse>>> getSuggestedAllocations(
            @PathVariable UUID exceptionId) {
        
        List<SuggestedAllocationResponse> suggestions = 
            exceptionService.getSuggestedAllocations(exceptionId);
        
        return ResponseEntity.ok(ApiResponse.success(suggestions));
    }

    /**
     * Get open exceptions by Exception VA.
     */
    @GetMapping("/by-va/{exceptionVaId}")
    public ResponseEntity<ApiResponse<List<ExceptionResponse>>> getExceptionsByVa(
            @PathVariable UUID exceptionVaId) {
        
        List<ExceptionResponse> exceptions = exceptionService.getOpenExceptionsByVa(exceptionVaId);
        return ResponseEntity.ok(ApiResponse.success(exceptions));
    }
}