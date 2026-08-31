package com.bank.vam.controller;

import com.bank.vam.dto.ProgramDto.*;
import com.bank.vam.service.ProgramService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * REST Controller for Program Management
 * 
 * Programs define configurations for different Virtual Account use cases:
 * - COLLECTION: Receivables collection programs
 * - VIBAN: Virtual IBAN assignment programs
 * - ESCROW: Digital escrow programs
 * - WALLET: Prepaid wallet programs
 * - IHB: In-house banking programs
 * - PAYABLES: Payables management programs
 */
@RestController
@RequestMapping("/api/v1/programs")
@RequiredArgsConstructor
@Tag(name = "Programs", description = "Virtual Account Program Management APIs")
@CrossOrigin(origins = "*")
public class ProgramController {

    private final ProgramService programService;

    // ========================================================================
    // PROGRAM CRUD
    // ========================================================================

    @GetMapping
    @Operation(
        summary = "Get all programs",
        description = "List all programs with optional filtering, pagination, and statistics"
    )
    public ResponseEntity<Map<String, Object>> getAllPrograms(
            @Parameter(description = "Corporate ID filter (from header)")
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId,
            
            @Parameter(description = "Search query for program code, name, or description")
            @RequestParam(required = false) String query,
            
            @Parameter(description = "Filter by program type: COLLECTION, VIBAN, ESCROW, WALLET, IHB, PAYABLES")
            @RequestParam(required = false) String programType,
            
            @Parameter(description = "Filter by status: ACTIVE, INACTIVE, SUSPENDED, PENDING_APPROVAL")
            @RequestParam(required = false) String status,
            
            @Parameter(description = "Filter by currency code")
            @RequestParam(required = false) String currencyCode,
            
            @Parameter(description = "Filter by VIBAN enabled")
            @RequestParam(required = false) Boolean vibanEnabled,
            
            @Parameter(description = "Filter by Wallet enabled")
            @RequestParam(required = false) Boolean walletEnabled,
            
            @Parameter(description = "Filter by Escrow enabled")
            @RequestParam(required = false) Boolean escrowEnabled,
            
            @Parameter(description = "Page number (0-based)")
            @RequestParam(defaultValue = "0") Integer page,
            
            @Parameter(description = "Page size")
            @RequestParam(defaultValue = "20") Integer pageSize,
            
            @Parameter(description = "Sort field")
            @RequestParam(defaultValue = "createdAt") String sortBy,
            
            @Parameter(description = "Sort order: asc or desc")
            @RequestParam(defaultValue = "desc") String sortOrder) {

        ProgramSearchRequest request = ProgramSearchRequest.builder()
            .query(query)
            .programType(programType)
            .status(status)
            .corporateId(corporateId)
            .currencyCode(currencyCode)
            .vibanEnabled(vibanEnabled)
            .walletEnabled(walletEnabled)
            .escrowEnabled(escrowEnabled)
            .page(page)
            .pageSize(pageSize)
            .sortBy(sortBy)
            .sortOrder(sortOrder)
            .build();

        ProgramListResponse result = programService.getAllPrograms(corporateId, request);

        return ResponseEntity.ok(Map.of(
            "success", true,
            "data", result
        ));
    }

    @GetMapping("/{programId}")
    @Operation(
        summary = "Get program by ID",
        description = "Get basic program information"
    )
    public ResponseEntity<Map<String, Object>> getProgram(
            @Parameter(description = "Program UUID")
            @PathVariable UUID programId) {
        
        ProgramResponse program = programService.getProgram(programId);
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "data", program
        ));
    }

    @GetMapping("/{programId}/detail")
    @Operation(
        summary = "Get program detail",
        description = "Get full program details including corporate info, physical account, virtual accounts, and usage stats"
    )
    public ResponseEntity<Map<String, Object>> getProgramDetail(
            @Parameter(description = "Program UUID")
            @PathVariable UUID programId) {
        
        ProgramDetailResponse detail = programService.getProgramDetail(programId);
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "data", detail
        ));
    }

    @GetMapping("/code/{programCode}")
    @Operation(
        summary = "Get program by code",
        description = "Get program by its unique program code"
    )
    public ResponseEntity<Map<String, Object>> getProgramByCode(
            @Parameter(description = "Program code")
            @PathVariable String programCode) {
        
        // This would need a new service method
        // For now, delegating to search
        ProgramSearchRequest request = ProgramSearchRequest.builder()
            .query(programCode)
            .page(0)
            .pageSize(1)
            .build();
        
        ProgramListResponse result = programService.getAllPrograms(null, request);
        
        if (result.getPrograms().isEmpty()) {
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "Program not found with code: " + programCode
            ));
        }
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "data", result.getPrograms().get(0)
        ));
    }

    @PostMapping
    @Operation(
        summary = "Create program",
        description = "Create a new Virtual Account program"
    )
    public ResponseEntity<Map<String, Object>> createProgram(
            @Parameter(description = "Corporate ID (from header, optional if in request body)")
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId,
            
            @RequestBody CreateProgramRequest request) {
        
        ProgramResponse program = programService.createProgram(corporateId, request);
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "data", program,
            "message", "Program created successfully"
        ));
    }

    @PutMapping("/{programId}")
    @Operation(
        summary = "Update program",
        description = "Update program configuration"
    )
    public ResponseEntity<Map<String, Object>> updateProgram(
            @Parameter(description = "Program UUID")
            @PathVariable UUID programId,
            
            @RequestBody UpdateProgramRequest request) {
        
        ProgramResponse program = programService.updateProgram(programId, request);
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "data", program,
            "message", "Program updated successfully"
        ));
    }

    @PatchMapping("/{programId}/status")
    @Operation(
        summary = "Update program status",
        description = "Change program status (ACTIVE, INACTIVE, SUSPENDED, PENDING_APPROVAL)"
    )
    public ResponseEntity<Map<String, Object>> updateProgramStatus(
            @Parameter(description = "Program UUID")
            @PathVariable UUID programId,
            
            @RequestBody UpdateProgramStatusRequest request) {
        
        ProgramResponse program = programService.updateProgramStatus(programId, request);
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "data", program,
            "message", "Program status updated successfully"
        ));
    }

    @DeleteMapping("/{programId}")
    @Operation(
        summary = "Delete program",
        description = "Deactivate a program (soft delete). Programs with active virtual accounts cannot be deleted."
    )
    public ResponseEntity<Map<String, Object>> deleteProgram(
            @Parameter(description = "Program UUID")
            @PathVariable UUID programId) {
        
        programService.deleteProgram(programId);
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "Program deactivated successfully"
        ));
    }

    // ========================================================================
    // PROGRAM ACTIONS
    // ========================================================================

    @PostMapping("/{programId}/clone")
    @Operation(
        summary = "Clone program",
        description = "Create a copy of an existing program with a new code and name"
    )
    public ResponseEntity<Map<String, Object>> cloneProgram(
            @Parameter(description = "Source program UUID")
            @PathVariable UUID programId,
            
            @RequestBody CloneProgramRequest request) {
        
        ProgramResponse program = programService.cloneProgram(programId, request);
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "data", program,
            "message", "Program cloned successfully"
        ));
    }

    @PostMapping("/{programId}/activate")
    @Operation(
        summary = "Activate program",
        description = "Shortcut to activate a program"
    )
    public ResponseEntity<Map<String, Object>> activateProgram(
            @Parameter(description = "Program UUID")
            @PathVariable UUID programId) {
        
        UpdateProgramStatusRequest request = UpdateProgramStatusRequest.builder()
            .status("ACTIVE")
            .reason("Activated via API")
            .build();
        
        ProgramResponse program = programService.updateProgramStatus(programId, request);
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "data", program,
            "message", "Program activated successfully"
        ));
    }

    @PostMapping("/{programId}/suspend")
    @Operation(
        summary = "Suspend program",
        description = "Suspend a program. New virtual accounts cannot be created under suspended programs."
    )
    public ResponseEntity<Map<String, Object>> suspendProgram(
            @Parameter(description = "Program UUID")
            @PathVariable UUID programId,
            
            @Parameter(description = "Suspension reason")
            @RequestParam(required = false) String reason) {
        
        UpdateProgramStatusRequest request = UpdateProgramStatusRequest.builder()
            .status("SUSPENDED")
            .reason(reason != null ? reason : "Suspended via API")
            .build();
        
        ProgramResponse program = programService.updateProgramStatus(programId, request);
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "data", program,
            "message", "Program suspended successfully"
        ));
    }

    // ========================================================================
    // STATISTICS
    // ========================================================================

    @GetMapping("/stats")
    @Operation(
        summary = "Get program statistics",
        description = "Get aggregated statistics about programs"
    )
    public ResponseEntity<Map<String, Object>> getStats(
            @Parameter(description = "Filter stats by corporate ID")
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId) {
        
        ProgramStatsResponse stats = programService.getStats(corporateId);
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "data", stats
        ));
    }

    // ========================================================================
    // LOOKUP ENDPOINTS
    // ========================================================================

    @GetMapping("/types")
    @Operation(
        summary = "Get program types",
        description = "Get list of available program types with labels"
    )
    public ResponseEntity<Map<String, Object>> getProgramTypes() {
        return ResponseEntity.ok(Map.of(
            "success", true,
            "data", Map.of(
                "COLLECTION", Map.of("label", "Collection", "description", "Receivables collection program"),
                "VIBAN", Map.of("label", "VIBAN", "description", "Virtual IBAN program"),
                "ESCROW", Map.of("label", "Escrow", "description", "Digital escrow program"),
                "WALLET", Map.of("label", "Wallet", "description", "Prepaid wallet program"),
                "IHB", Map.of("label", "In-House Bank", "description", "In-house banking program"),
                "PAYABLES", Map.of("label", "Payables", "description", "Payables management program")
            )
        ));
    }

    @GetMapping("/statuses")
    @Operation(
        summary = "Get program statuses",
        description = "Get list of available program statuses with labels and variants"
    )
    public ResponseEntity<Map<String, Object>> getProgramStatuses() {
        return ResponseEntity.ok(Map.of(
            "success", true,
            "data", Map.of(
                "ACTIVE", Map.of("label", "Active", "variant", "success"),
                "INACTIVE", Map.of("label", "Inactive", "variant", "neutral"),
                "SUSPENDED", Map.of("label", "Suspended", "variant", "warning"),
                "PENDING_APPROVAL", Map.of("label", "Pending Approval", "variant", "info")
            )
        ));
    }

    @GetMapping("/settlement-frequencies")
    @Operation(
        summary = "Get settlement frequencies",
        description = "Get list of available settlement frequency options"
    )
    public ResponseEntity<Map<String, Object>> getSettlementFrequencies() {
        return ResponseEntity.ok(Map.of(
            "success", true,
            "data", Map.of(
                "REAL_TIME", Map.of("label", "Real-time", "description", "Immediate settlement"),
                "HOURLY", Map.of("label", "Hourly", "description", "Settlement every hour"),
                "DAILY", Map.of("label", "Daily", "description", "Settlement once per day"),
                "WEEKLY", Map.of("label", "Weekly", "description", "Settlement once per week"),
                "MONTHLY", Map.of("label", "Monthly", "description", "Settlement once per month")
            )
        ));
    }
}
