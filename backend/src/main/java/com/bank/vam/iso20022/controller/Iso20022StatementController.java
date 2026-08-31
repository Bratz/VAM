package com.bank.vam.iso20022.controller;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.iso20022.dto.Iso20022StatementDto.*;
import com.bank.vam.iso20022.service.Iso20022StatementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * ISO 20022 Statement Controller.
 *
 * Provides REST API endpoints for ISO 20022 account statements:
 * - camt.053 (Bank-to-Customer Statement)
 * - camt.054 (Bank-to-Customer Debit/Credit Notification)
 * - Aggregated statements with child account consolidation
 * - Statement history and retrieval
 *
 * Features:
 * - XML and JSON output formats
 * - Pagination for large statements
 * - Async generation for large date ranges
 * - Statement storage and retrieval
 *
 * @see <a href="https://www.iso20022.org/">ISO 20022</a>
 */
@RestController
@RequestMapping("/api/v1/iso20022")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "ISO 20022 Statements", description = "ISO 20022 compliant account statement APIs (camt.053/054)")
public class Iso20022StatementController {

    private final Iso20022StatementService statementService;

    // ========================================================================
    // CAMT.053 - BANK TO CUSTOMER STATEMENT
    // ========================================================================

    @PostMapping("/camt053/generate")
    @Operation(
            summary = "Generate camt.053 statement",
            description = """
                    Generate ISO 20022 camt.053 Bank-to-Customer Statement for a virtual account.

                    Features:
                    - Supports XML and JSON output formats
                    - Optional inclusion of child/subsidiary accounts
                    - Pagination for large statements
                    - Async processing for large date ranges (returns job ID)

                    For date ranges > 90 days or > 10,000 transactions, processing is async.
                    Use the returned jobId to check status and retrieve the statement.
                    """
    )
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Statement generated successfully",
                    content = @Content(schema = @Schema(implementation = Camt053Response.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "202",
                    description = "Statement generation started (async)",
                    content = @Content(schema = @Schema(implementation = AsyncJobResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Invalid request parameters"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Virtual account not found"
            )
    })
    public ResponseEntity<ApiResponse<?>> generateCamt053Statement(
            @Valid @RequestBody Camt053GenerateRequest request) {

        log.info("REST: Generate camt.053 statement - vaId={}, from={}, to={}, format={}, includeChildren={}",
                request.getVaId(), request.getFromDate(), request.getToDate(),
                request.getFormat(), request.isIncludeChildren());

        // Check if async processing is needed
        if (statementService.requiresAsyncProcessing(request)) {
            AsyncJobResponse asyncResponse = statementService.generateCamt053Async(request);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(ApiResponse.success(asyncResponse, "Statement generation started. Use jobId to check status."));
        }

        Camt053Response response = statementService.generateCamt053(request);
        return ResponseEntity.ok(ApiResponse.success(response, "camt.053 statement generated successfully"));
    }

    @GetMapping("/camt053/va/{vaId}")
    @Operation(
            summary = "Get camt.053 statement for VA",
            description = """
                    Generate or retrieve ISO 20022 camt.053 statement for a virtual account
                    using path parameters. Convenient GET endpoint for simple statement requests.
                    """
    )
    public ResponseEntity<ApiResponse<Camt053Response>> getCamt053Statement(
            @Parameter(description = "Virtual Account ID", required = true)
            @PathVariable UUID vaId,

            @Parameter(description = "Statement period start date", required = true, example = "2024-01-01")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,

            @Parameter(description = "Statement period end date", required = true, example = "2024-01-31")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,

            @Parameter(description = "Output format: XML or JSON", schema = @Schema(allowableValues = {"XML", "JSON", "xml", "json"}))
            @RequestParam(defaultValue = "JSON") String formatStr,

            @Parameter(description = "Include child accounts in statement")
            @RequestParam(defaultValue = "false") boolean includeChildren,

            @Parameter(description = "Page number (0-based)")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "Page size (max entries per page)")
            @RequestParam(defaultValue = "100") int size) {

        OutputFormat format = parseOutputFormat(formatStr);
        log.info("REST: GET camt.053 statement - vaId={}, from={}, to={}, format={}", vaId, fromDate, toDate, format);

        Camt053GenerateRequest request = Camt053GenerateRequest.builder()
                .vaId(vaId)
                .fromDate(fromDate)
                .toDate(toDate)
                .format(format)
                .includeChildren(includeChildren)
                .page(page)
                .maxEntries(size)
                .build();

        Camt053Response response = statementService.generateCamt053(request);
        return ResponseEntity.ok(ApiResponse.success(response, "camt.053 statement retrieved successfully"));
    }

    @GetMapping(value = "/camt053/va/{vaId}/xml", produces = MediaType.APPLICATION_XML_VALUE)
    @Operation(
            summary = "Download camt.053 XML statement",
            description = "Generate and download ISO 20022 camt.053 statement as XML file"
    )
    public ResponseEntity<String> downloadCamt053Xml(
            @PathVariable UUID vaId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "false") boolean includeChildren) {

        log.info("REST: Download camt.053 XML - vaId={}, from={}, to={}", vaId, fromDate, toDate);

        Camt053GenerateRequest request = Camt053GenerateRequest.builder()
                .vaId(vaId)
                .fromDate(fromDate)
                .toDate(toDate)
                .format(OutputFormat.XML)
                .includeChildren(includeChildren)
                .build();

        Camt053Response response = statementService.generateCamt053(request);

        if (response.getCamt053Xml() != null) {
            String filename = String.format("camt053_%s_%s_%s.xml",
                    response.getVaNumber(), fromDate, toDate);

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_XML)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .body(response.getCamt053Xml());
        }

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("<!-- Failed to generate camt.053 XML -->");
    }

    // ========================================================================
    // CAMT.054 - DEBIT/CREDIT NOTIFICATION
    // ========================================================================

    @PostMapping("/camt054/generate")
    @Operation(
            summary = "Generate camt.054 notification",
            description = """
                    Generate ISO 20022 camt.054 Bank-to-Customer Debit/Credit Notification.

                    Use cases:
                    - Real-time credit notifications for incoming payments
                    - Debit notifications for outgoing payments
                    - Filtered notifications (credits only, debits only)
                    - Single transaction notification by transaction ID
                    """
    )
    public ResponseEntity<ApiResponse<Camt054Response>> generateCamt054Notification(
            @Valid @RequestBody Camt054GenerateRequest request) {

        log.info("REST: Generate camt.054 notification - vaId={}, from={}, to={}, creditsOnly={}, debitsOnly={}",
                request.getVaId(), request.getFromDate(), request.getToDate(),
                request.isCreditsOnly(), request.isDebitsOnly());

        Camt054Response response = statementService.generateCamt054(request);
        return ResponseEntity.ok(ApiResponse.success(response, "camt.054 notification generated successfully"));
    }

    @GetMapping("/camt054/va/{vaId}")
    @Operation(
            summary = "Get camt.054 notifications for VA",
            description = "Retrieve debit/credit notifications for a virtual account"
    )
    public ResponseEntity<ApiResponse<Camt054Response>> getCamt054Notifications(
            @PathVariable UUID vaId,

            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,

            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,

            @Parameter(description = "Filter for credits only")
            @RequestParam(defaultValue = "false") boolean creditsOnly,

            @Parameter(description = "Filter for debits only")
            @RequestParam(defaultValue = "false") boolean debitsOnly) {

        log.info("REST: GET camt.054 notifications - vaId={}, from={}, to={}", vaId, fromDate, toDate);

        Camt054GenerateRequest request = Camt054GenerateRequest.builder()
                .vaId(vaId)
                .fromDate(fromDate != null ? fromDate : LocalDate.now())
                .toDate(toDate != null ? toDate : LocalDate.now())
                .creditsOnly(creditsOnly)
                .debitsOnly(debitsOnly)
                .format(OutputFormat.JSON)
                .build();

        Camt054Response response = statementService.generateCamt054(request);
        return ResponseEntity.ok(ApiResponse.success(response, "camt.054 notifications retrieved successfully"));
    }

    @GetMapping(value = "/camt054/va/{vaId}/xml", produces = MediaType.APPLICATION_XML_VALUE)
    @Operation(
            summary = "Download camt.054 XML notification",
            description = "Generate and download ISO 20022 camt.054 notification as XML file"
    )
    public ResponseEntity<String> downloadCamt054Xml(
            @PathVariable UUID vaId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {

        log.info("REST: Download camt.054 XML - vaId={}, from={}, to={}", vaId, fromDate, toDate);

        Camt054GenerateRequest request = Camt054GenerateRequest.builder()
                .vaId(vaId)
                .fromDate(fromDate != null ? fromDate : LocalDate.now())
                .toDate(toDate != null ? toDate : LocalDate.now())
                .format(OutputFormat.XML)
                .build();

        Camt054Response response = statementService.generateCamt054(request);

        if (response.getCamt054Xml() != null) {
            String filename = String.format("camt054_%s_%s_%s.xml",
                    response.getVaNumber(),
                    request.getFromDate(),
                    request.getToDate());

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_XML)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .body(response.getCamt054Xml());
        }

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("<!-- Failed to generate camt.054 XML -->");
    }

    // ========================================================================
    // AGGREGATED STATEMENT
    // ========================================================================

    @GetMapping("/camt053/va/{vaId}/aggregated")
    @Operation(
            summary = "Get aggregated statement",
            description = """
                    Generate aggregated statement across account hierarchy.

                    Features:
                    - Consolidates balances and transactions from child accounts
                    - Groups summaries by currency
                    - Configurable hierarchy depth
                    - Optional detailed entries per account
                    """
    )
    public ResponseEntity<ApiResponse<AggregatedStatementResponse>> getAggregatedStatement(
            @Parameter(description = "Parent Virtual Account ID", required = true)
            @PathVariable UUID vaId,

            @Parameter(description = "Statement period start date", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,

            @Parameter(description = "Statement period end date", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,

            @Parameter(description = "Include child accounts in aggregation")
            @RequestParam(defaultValue = "true") boolean includeChildren,

            @Parameter(description = "Hierarchy depth (-1 for unlimited)")
            @RequestParam(defaultValue = "-1") int depth,

            @Parameter(description = "Group summaries by currency")
            @RequestParam(defaultValue = "true") boolean groupByCurrency,

            @Parameter(description = "Include detailed transaction entries")
            @RequestParam(defaultValue = "false") boolean includeDetails) {

        log.info("REST: Get aggregated statement - vaId={}, from={}, to={}, depth={}", vaId, fromDate, toDate, depth);

        AggregatedStatementRequest request = AggregatedStatementRequest.builder()
                .vaId(vaId)
                .fromDate(fromDate)
                .toDate(toDate)
                .includeChildren(includeChildren)
                .depth(depth)
                .groupByCurrency(groupByCurrency)
                .includeDetails(includeDetails)
                .format(OutputFormat.JSON)
                .build();

        AggregatedStatementResponse response = statementService.generateAggregatedStatement(request);
        return ResponseEntity.ok(ApiResponse.success(response, "Aggregated statement generated successfully"));
    }

    @PostMapping("/camt053/aggregated/generate")
    @Operation(
            summary = "Generate aggregated statement (POST)",
            description = "Generate aggregated statement with full request customization"
    )
    public ResponseEntity<ApiResponse<AggregatedStatementResponse>> generateAggregatedStatement(
            @Valid @RequestBody AggregatedStatementRequest request) {

        log.info("REST: Generate aggregated statement - vaId={}, from={}, to={}, depth={}",
                request.getVaId(), request.getFromDate(), request.getToDate(), request.getDepth());

        AggregatedStatementResponse response = statementService.generateAggregatedStatement(request);
        return ResponseEntity.ok(ApiResponse.success(response, "Aggregated statement generated successfully"));
    }

    // ========================================================================
    // STATEMENT HISTORY
    // ========================================================================

    @GetMapping("/statements/va/{vaId}/history")
    @Operation(
            summary = "Get statement history for VA",
            description = "Retrieve previously generated statements for a virtual account"
    )
    public ResponseEntity<ApiResponse<StatementHistoryResponse>> getStatementHistoryByVa(
            @PathVariable UUID vaId,

            @Parameter(description = "Statement type filter")
            @RequestParam(required = false) StatementType type,

            @Parameter(description = "Status filter")
            @RequestParam(required = false) GenerationStatus status,

            @RequestParam(defaultValue = "0") int page,

            @RequestParam(defaultValue = "20") int size,

            @RequestParam(defaultValue = "generatedAt") String sortBy,

            @RequestParam(defaultValue = "desc") String sortDirection) {

        log.info("REST: Get statement history - vaId={}, type={}, page={}", vaId, type, page);

        StatementHistoryRequest request = StatementHistoryRequest.builder()
                .vaId(vaId)
                .type(type)
                .status(status)
                .page(page)
                .size(size)
                .sortBy(sortBy)
                .sortDirection(sortDirection)
                .build();

        StatementHistoryResponse response = statementService.getStatementHistory(request);
        return ResponseEntity.ok(ApiResponse.success(response, "Statement history retrieved successfully"));
    }

    @GetMapping("/statements/corporate/{corporateId}/history")
    @Operation(
            summary = "Get statement history for corporate",
            description = "Retrieve previously generated statements for all accounts under a corporate"
    )
    public ResponseEntity<ApiResponse<StatementHistoryResponse>> getStatementHistoryByCorporate(
            @PathVariable UUID corporateId,

            @RequestParam(required = false) StatementType type,

            @RequestParam(required = false) GenerationStatus status,

            @RequestParam(defaultValue = "0") int page,

            @RequestParam(defaultValue = "20") int size) {

        log.info("REST: Get corporate statement history - corporateId={}, page={}", corporateId, page);

        StatementHistoryRequest request = StatementHistoryRequest.builder()
                .corporateId(corporateId)
                .type(type)
                .status(status)
                .page(page)
                .size(size)
                .build();

        StatementHistoryResponse response = statementService.getStatementHistory(request);
        return ResponseEntity.ok(ApiResponse.success(response, "Statement history retrieved successfully"));
    }

    // ========================================================================
    // STATEMENT RETRIEVAL & DOWNLOAD
    // ========================================================================

    @GetMapping("/statements/{statementId}")
    @Operation(
            summary = "Get statement details",
            description = "Retrieve detailed information about a specific statement including entries"
    )
    public ResponseEntity<ApiResponse<StatementDetail>> getStatementById(
            @Parameter(description = "Statement ID", required = true)
            @PathVariable String statementId,

            @Parameter(description = "Include transaction entries")
            @RequestParam(defaultValue = "true") boolean includeEntries,

            @Parameter(description = "Entry page number (0-based)")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "Entries per page")
            @RequestParam(defaultValue = "100") int size) {

        log.info("REST: Get statement by ID - statementId={}, includeEntries={}", statementId, includeEntries);

        StatementDetail response = statementService.getStatementById(statementId, includeEntries, page, size);
        return ResponseEntity.ok(ApiResponse.success(response, "Statement details retrieved successfully"));
    }

    @GetMapping("/statements/{statementId}/download")
    @Operation(
            summary = "Download statement file",
            description = "Download the generated statement file (XML, JSON, PDF, or CSV)"
    )
    public ResponseEntity<?> downloadStatement(
            @Parameter(description = "Statement ID", required = true)
            @PathVariable String statementId,

            @Parameter(description = "Override format (optional, defaults to original)")
            @RequestParam(required = false) String formatStr) {

        OutputFormat format = formatStr != null ? parseOutputFormat(formatStr) : null;
        log.info("REST: Download statement - statementId={}, format={}", statementId, format);

        StatementDownloadResponse download = statementService.downloadStatement(statementId, format);

        if (download.getContent() != null) {
            MediaType mediaType = getMediaType(download.getContentType());

            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + download.getFileName() + "\"")
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(download.getFileSize()))
                    .body(download.getContent());
        }

        // Return download URL if content not inline
        return ResponseEntity.ok(ApiResponse.success(download, "Download URL generated"));
    }

    @GetMapping(value = "/statements/{statementId}/xml", produces = MediaType.APPLICATION_XML_VALUE)
    @Operation(
            summary = "Download statement as XML",
            description = "Download statement in ISO 20022 XML format"
    )
    public ResponseEntity<String> downloadStatementXml(
            @PathVariable String statementId) {

        log.info("REST: Download statement XML - statementId={}", statementId);

        StatementDownloadResponse download = statementService.downloadStatement(statementId, OutputFormat.XML);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + download.getFileName() + "\"")
                .body(download.getContent());
    }

    // ========================================================================
    // ASYNC JOB STATUS
    // ========================================================================

    @GetMapping("/statements/jobs/{jobId}")
    @Operation(
            summary = "Get async job status",
            description = "Check the status of an async statement generation job"
    )
    public ResponseEntity<ApiResponse<AsyncJobStatus>> getAsyncJobStatus(
            @Parameter(description = "Async job ID", required = true)
            @PathVariable String jobId) {

        log.info("REST: Get async job status - jobId={}", jobId);

        AsyncJobStatus status = statementService.getAsyncJobStatus(jobId);
        return ResponseEntity.ok(ApiResponse.success(status, "Job status retrieved"));
    }

    @DeleteMapping("/statements/jobs/{jobId}")
    @Operation(
            summary = "Cancel async job",
            description = "Cancel a pending or processing async statement generation job"
    )
    public ResponseEntity<ApiResponse<Void>> cancelAsyncJob(
            @PathVariable String jobId) {

        log.info("REST: Cancel async job - jobId={}", jobId);

        statementService.cancelAsyncJob(jobId);
        return ResponseEntity.ok(ApiResponse.ok("Job cancelled successfully"));
    }

    // ========================================================================
    // UTILITY METHODS
    // ========================================================================

    private MediaType getMediaType(String contentType) {
        if (contentType == null) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        return switch (contentType.toLowerCase()) {
            case "application/xml", "text/xml" -> MediaType.APPLICATION_XML;
            case "application/json" -> MediaType.APPLICATION_JSON;
            case "application/pdf" -> MediaType.APPLICATION_PDF;
            case "text/csv" -> new MediaType("text", "csv");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    /**
     * Parse output format string to enum (case-insensitive).
     */
    private OutputFormat parseOutputFormat(String formatStr) {
        if (formatStr == null || formatStr.isBlank()) {
            return OutputFormat.JSON;
        }
        try {
            return OutputFormat.valueOf(formatStr.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown output format '{}', defaulting to JSON", formatStr);
            return OutputFormat.JSON;
        }
    }
}
