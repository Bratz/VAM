package com.bank.vam.controller.statement;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.dto.statement.Camt053Dto.*;
import com.bank.vam.service.statement.VaStatementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * REST Controller for hierarchical VA account statements (camt.053/054).
 *
 * <h2>Endpoints:</h2>
 * <ul>
 *   <li>Single VA Statement: GET /api/v1/statements/va/{vaId}</li>
 *   <li>Aggregated Statement: GET /api/v1/statements/va/{vaId}/aggregated</li>
 *   <li>Intraday Statement: GET /api/v1/statements/va/{vaId}/intraday</li>
 *   <li>Transaction Notification: GET /api/v1/statements/notification/{transactionId}</li>
 *   <li>VA Hierarchy: GET /api/v1/statements/va/{vaId}/hierarchy</li>
 *   <li>Aggregated Balance: GET /api/v1/statements/va/{vaId}/balance</li>
 * </ul>
 *
 * <h2>Response Formats:</h2>
 * <ul>
 *   <li>JSON: Default response with statement object</li>
 *   <li>XML: camt.053/052/054 compliant XML (Accept: application/xml)</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/statements")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "VA Statements", description = "Hierarchical VA account statements (camt.053/054)")
public class VaStatementController {

    private final VaStatementService statementService;

    // ========================================================================
    // SINGLE VA STATEMENT
    // ========================================================================

    @GetMapping("/va/{vaId}")
    @Operation(summary = "Generate statement for single VA",
               description = "Generates a camt.053 statement for a single VA (leaf level)")
    public ResponseEntity<ApiResponse<Camt053Statement>> getStatement(
            @Parameter(description = "Virtual Account ID")
            @PathVariable UUID vaId,
            @Parameter(description = "Statement start date (inclusive)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "Statement end date (inclusive)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @Parameter(description = "Include transaction entries")
            @RequestParam(defaultValue = "true") boolean includeEntries) {

        log.info("Generating statement for VA {} from {} to {}", vaId, fromDate, toDate);

        Camt053Statement statement = statementService.generateStatement(
            StatementRequest.builder()
                .vaId(vaId)
                .fromDate(fromDate)
                .toDate(toDate)
                .includeEntries(includeEntries)
                .build()
        );

        return ResponseEntity.ok(ApiResponse.success(statement));
    }

    @GetMapping(value = "/va/{vaId}/xml", produces = MediaType.APPLICATION_XML_VALUE)
    @Operation(summary = "Generate camt.053 XML for single VA",
               description = "Returns ISO 20022 camt.053 compliant XML")
    public ResponseEntity<String> getStatementXml(
            @PathVariable UUID vaId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {

        Camt053Statement statement = statementService.generateStatement(vaId, fromDate, toDate);

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + statement.getStatementId() + ".xml\"")
            .contentType(MediaType.APPLICATION_XML)
            .body(statement.getXml());
    }

    // ========================================================================
    // AGGREGATED STATEMENT (HIERARCHICAL)
    // ========================================================================

    @GetMapping("/va/{vaId}/aggregated")
    @Operation(summary = "Generate aggregated statement including all child VAs",
               description = "Generates a consolidated camt.053 statement that includes all transactions from child VAs recursively")
    public ResponseEntity<ApiResponse<Camt053Statement>> getAggregatedStatement(
            @Parameter(description = "Aggregation VA ID (parent)")
            @PathVariable UUID vaId,
            @Parameter(description = "Statement start date (inclusive)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "Statement end date (inclusive)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @Parameter(description = "Include transaction entries from child VAs")
            @RequestParam(defaultValue = "true") boolean includeChildEntries,
            @Parameter(description = "Include VA identifiers in entry details")
            @RequestParam(defaultValue = "true") boolean includeVaIdentifiers,
            @Parameter(description = "Include summary per child VA")
            @RequestParam(defaultValue = "false") boolean includeSummaryByVa,
            @Parameter(description = "Maximum hierarchy depth to traverse (null = unlimited)")
            @RequestParam(required = false) Integer maxDepth) {

        log.info("Generating aggregated statement for VA {} from {} to {} (maxDepth: {})",
            vaId, fromDate, toDate, maxDepth);

        Camt053Statement statement = statementService.generateAggregatedStatement(
            AggregatedStatementRequest.builder()
                .aggregationVaId(vaId)
                .fromDate(fromDate)
                .toDate(toDate)
                .includeChildEntries(includeChildEntries)
                .includeVaIdentifiers(includeVaIdentifiers)
                .includeSummaryByVa(includeSummaryByVa)
                .maxDepth(maxDepth)
                .build()
        );

        return ResponseEntity.ok(ApiResponse.success(statement));
    }

    @GetMapping(value = "/va/{vaId}/aggregated/xml", produces = MediaType.APPLICATION_XML_VALUE)
    @Operation(summary = "Generate aggregated camt.053 XML",
               description = "Returns ISO 20022 camt.053 compliant XML for aggregated statement")
    public ResponseEntity<String> getAggregatedStatementXml(
            @PathVariable UUID vaId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Integer maxDepth) {

        Camt053Statement statement = statementService.generateAggregatedStatement(
            AggregatedStatementRequest.builder()
                .aggregationVaId(vaId)
                .fromDate(fromDate)
                .toDate(toDate)
                .maxDepth(maxDepth)
                .build()
        );

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + statement.getStatementId() + ".xml\"")
            .contentType(MediaType.APPLICATION_XML)
            .body(statement.getXml());
    }

    // ========================================================================
    // INTRADAY STATEMENT (camt.052)
    // ========================================================================

    @GetMapping("/va/{vaId}/intraday")
    @Operation(summary = "Generate intraday statement",
               description = "Generates a camt.052 intraday statement with interim balances")
    public ResponseEntity<ApiResponse<Camt053Statement>> getIntradayStatement(
            @PathVariable UUID vaId,
            @Parameter(description = "As-of timestamp (defaults to now)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime asOfTime,
            @Parameter(description = "Include child VAs in statement")
            @RequestParam(defaultValue = "false") boolean aggregated) {

        LocalDateTime effectiveTime = asOfTime != null ? asOfTime : LocalDateTime.now();
        log.info("Generating intraday statement for VA {} as of {}", vaId, effectiveTime);

        Camt053Statement statement = statementService.generateIntradayStatement(vaId, effectiveTime, aggregated);

        return ResponseEntity.ok(ApiResponse.success(statement));
    }

    // ========================================================================
    // TRANSACTION NOTIFICATION (camt.054)
    // ========================================================================

    @GetMapping("/notification/{transactionId}")
    @Operation(summary = "Generate transaction notification",
               description = "Generates a camt.054 debit/credit notification for a specific transaction")
    public ResponseEntity<ApiResponse<Camt053Statement>> getTransactionNotification(
            @PathVariable UUID transactionId) {

        log.info("Generating notification for transaction {}", transactionId);

        Camt053Statement statement = statementService.generateTransactionNotification(transactionId);

        return ResponseEntity.ok(ApiResponse.success(statement));
    }

    @GetMapping(value = "/notification/{transactionId}/xml", produces = MediaType.APPLICATION_XML_VALUE)
    @Operation(summary = "Generate camt.054 XML notification",
               description = "Returns ISO 20022 camt.054 compliant XML for transaction notification")
    public ResponseEntity<String> getTransactionNotificationXml(
            @PathVariable UUID transactionId) {

        Camt053Statement statement = statementService.generateTransactionNotification(transactionId);
        String xml = statementService.generateCamt054Xml(statement);

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + statement.getStatementId() + "-notification.xml\"")
            .contentType(MediaType.APPLICATION_XML)
            .body(xml);
    }

    // ========================================================================
    // VA HIERARCHY
    // ========================================================================

    @GetMapping("/va/{vaId}/hierarchy")
    @Operation(summary = "Get VA hierarchy tree",
               description = "Returns the complete VA hierarchy tree starting from the specified VA")
    public ResponseEntity<ApiResponse<VaHierarchyNode>> getVaHierarchy(
            @PathVariable UUID vaId,
            @Parameter(description = "Maximum depth to traverse (null = unlimited)")
            @RequestParam(required = false) Integer maxDepth) {

        log.info("Getting VA hierarchy for {} with maxDepth {}", vaId, maxDepth);

        VaHierarchyNode tree = statementService.getVaHierarchyTree(vaId, maxDepth);

        return ResponseEntity.ok(ApiResponse.success(tree));
    }

    @GetMapping("/va/{vaId}/children")
    @Operation(summary = "Get all child VA IDs",
               description = "Returns list of all child VA IDs recursively")
    public ResponseEntity<ApiResponse<List<UUID>>> getChildVaIds(
            @PathVariable UUID vaId,
            @RequestParam(required = false) Integer maxDepth) {

        List<UUID> childIds = statementService.getAllChildVaIds(vaId, maxDepth);

        return ResponseEntity.ok(ApiResponse.success(childIds));
    }

    // ========================================================================
    // BALANCE QUERIES
    // ========================================================================

    @GetMapping("/va/{vaId}/balance")
    @Operation(summary = "Calculate aggregated balance",
               description = "Calculates aggregated balance for a VA including all child VAs")
    public ResponseEntity<ApiResponse<AggregatedBalance>> getAggregatedBalance(
            @PathVariable UUID vaId,
            @Parameter(description = "As-of date (defaults to today)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate,
            @Parameter(description = "Include child VAs in calculation")
            @RequestParam(defaultValue = "true") boolean includeChildren,
            @RequestParam(required = false) Integer maxDepth) {

        LocalDate effectiveDate = asOfDate != null ? asOfDate : LocalDate.now();
        log.info("Calculating aggregated balance for VA {} as of {}", vaId, effectiveDate);

        AggregatedBalance balance = statementService.calculateAggregatedBalance(
            BalanceCalculationRequest.builder()
                .vaId(vaId)
                .asOfDate(effectiveDate)
                .includeChildren(includeChildren)
                .maxDepth(maxDepth)
                .build()
        );

        return ResponseEntity.ok(ApiResponse.success(balance));
    }

    @GetMapping("/va/{vaId}/balance/opening")
    @Operation(summary = "Get opening balance",
               description = "Returns the opening balance (OPBD) for a VA at the start of a specific date")
    public ResponseEntity<ApiResponse<Balance>> getOpeningBalance(
            @PathVariable UUID vaId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        Balance balance = statementService.calculateOpeningBalance(vaId, date);
        return ResponseEntity.ok(ApiResponse.success(balance));
    }

    @GetMapping("/va/{vaId}/balance/closing")
    @Operation(summary = "Get closing balance",
               description = "Returns the closing balance (CLBD) for a VA at the end of a specific date")
    public ResponseEntity<ApiResponse<Balance>> getClosingBalance(
            @PathVariable UUID vaId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        Balance balance = statementService.calculateClosingBalance(vaId, date);
        return ResponseEntity.ok(ApiResponse.success(balance));
    }

    // ========================================================================
    // CACHE MANAGEMENT
    // ========================================================================

    @PostMapping("/cache/invalidate/{vaId}")
    @Operation(summary = "Invalidate hierarchy cache",
               description = "Invalidates the cached VA hierarchy for a root VA")
    public ResponseEntity<ApiResponse<String>> invalidateCache(@PathVariable UUID vaId) {
        statementService.invalidateHierarchyCache(vaId);
        return ResponseEntity.ok(ApiResponse.success("Cache invalidated for VA: " + vaId));
    }

    @PostMapping("/cache/preload/{corporateId}")
    @Operation(summary = "Preload hierarchy cache",
               description = "Preloads VA hierarchy cache for all root VAs in a corporate")
    public ResponseEntity<ApiResponse<String>> preloadCache(@PathVariable UUID corporateId) {
        statementService.preloadHierarchyCache(corporateId);
        return ResponseEntity.ok(ApiResponse.success("Cache preloaded for corporate: " + corporateId));
    }
}
