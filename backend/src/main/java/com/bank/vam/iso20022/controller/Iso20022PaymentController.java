package com.bank.vam.iso20022.controller;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.iso20022.dto.Iso20022PaymentDto.*;
import com.bank.vam.iso20022.service.Iso20022InwardPaymentService;
import com.bank.vam.iso20022.service.Iso20022OutwardPaymentService;
import com.bank.vam.iso20022.service.Iso20022StatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * ISO 20022 Payment Controller.
 *
 * Provides REST API endpoints for ISO 20022 payment processing:
 * - Inward payments (ROBO credits via pacs.008/camt.054)
 * - Outward payments (POBO debits via pain.001)
 * - Payment status queries
 *
 * All endpoints integrate with existing Transaction and VA entities.
 */
@RestController
@RequestMapping("/api/v1/iso20022")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "ISO 20022 Payments", description = "ISO 20022 compliant payment processing APIs")
public class Iso20022PaymentController {

    private final Iso20022InwardPaymentService inwardPaymentService;
    private final Iso20022OutwardPaymentService outwardPaymentService;
    private final Iso20022StatusService statusService;

    // ========================================================================
    // INWARD PAYMENTS (Credits via VIBAN routing)
    // ========================================================================

    @PostMapping("/inward/payment")
    @Operation(summary = "Process inward payment",
            description = "Process incoming payment and credit to VA via VIBAN routing. " +
                    "Uses existing ROBO flow with auto-reconciliation.")
    public ResponseEntity<ApiResponse<InwardPaymentResponse>> processInwardPayment(
            @RequestBody InwardPaymentRequest request) {
        log.info("REST: Process inward payment - amount={} {}, viban={}",
                request.getAmount(), request.getCurrency(), request.getCreditorAccount());

        InwardPaymentResponse response = inwardPaymentService.processInwardPayment(request);

        if (response.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.success(response, "Inward payment processed successfully"));
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(response.getErrorMessage()));
        }
    }

    @PostMapping(value = "/inward/pacs008", consumes = MediaType.APPLICATION_XML_VALUE)
    @Operation(summary = "Process pacs.008 message",
            description = "Parse and process ISO 20022 pacs.008 (FI-to-FI Customer Credit Transfer) XML message")
    public ResponseEntity<ApiResponse<InwardPaymentResponse>> processPacs008(
            @RequestBody String pacs008Xml) {
        log.info("REST: Process pacs.008 XML message");

        try {
            InwardPaymentRequest request = inwardPaymentService.parsePacs008(pacs008Xml);
            InwardPaymentResponse response = inwardPaymentService.processInwardPayment(request);

            if (response.isSuccess()) {
                return ResponseEntity.ok(ApiResponse.success(response, "pacs.008 processed successfully"));
            } else {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(ApiResponse.error(response.getErrorMessage()));
            }
        } catch (Exception e) {
            log.error("Failed to process pacs.008: ", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error("Failed to parse pacs.008: " + e.getMessage()));
        }
    }

    @PostMapping(value = "/inward/camt054", consumes = MediaType.APPLICATION_XML_VALUE)
    @Operation(summary = "Process camt.054 notification",
            description = "Parse and process ISO 20022 camt.054 (Bank-to-Customer Debit/Credit Notification) XML message")
    public ResponseEntity<ApiResponse<InwardPaymentResponse>> processCamt054(
            @RequestBody String camt054Xml) {
        log.info("REST: Process camt.054 XML message");

        try {
            InwardPaymentRequest request = inwardPaymentService.parseCamt054(camt054Xml);
            InwardPaymentResponse response = inwardPaymentService.processInwardPayment(request);

            if (response.isSuccess()) {
                return ResponseEntity.ok(ApiResponse.success(response, "camt.054 processed successfully"));
            } else {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(ApiResponse.error(response.getErrorMessage()));
            }
        } catch (Exception e) {
            log.error("Failed to process camt.054: ", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error("Failed to parse camt.054: " + e.getMessage()));
        }
    }

    // ========================================================================
    // OUTWARD PAYMENTS (Debits with pain.001 generation)
    // ========================================================================

    @PostMapping("/outward/payment")
    @Operation(summary = "Process outward payment",
            description = "Process outbound payment from VA. Generates ISO 20022 pain.001 XML. " +
                    "Includes hierarchy-based funds check and fee calculation.")
    public ResponseEntity<ApiResponse<OutwardPaymentResponse>> processOutwardPayment(
            @RequestBody OutwardPaymentRequest request) {
        log.info("REST: Process outward payment - sourceVa={}, amount={} {}, beneficiary={}",
                request.getSourceVaId(), request.getAmount(), request.getCurrency(), request.getCreditorName());

        OutwardPaymentResponse response = outwardPaymentService.processOutwardPayment(request);

        if (response.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.success(response, "Outward payment processed successfully"));
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(response.getErrorMessage()));
        }
    }

    @PostMapping("/outward/pobo")
    @Operation(summary = "Process POBO payment",
            description = "Process Pay-On-Behalf-Of payment from VA. Generates ISO 20022 pain.001 XML.")
    public ResponseEntity<ApiResponse<OutwardPaymentResponse>> processPoboPayment(
            @RequestBody OutwardPaymentRequest request) {
        log.info("REST: Process POBO payment - sourceVa={}, behalfOf={}, amount={} {}",
                request.getSourceVaId(), request.getBehalfOfEntity(), request.getAmount(), request.getCurrency());

        // Set POBO flag
        request.setPobo(true);

        OutwardPaymentResponse response = outwardPaymentService.processOutwardPayment(request);

        if (response.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.success(response, "POBO payment processed successfully"));
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(response.getErrorMessage()));
        }
    }

    @PostMapping("/outward/bulk")
    @Operation(summary = "Process bulk outward payments",
            description = "Process multiple outbound payments from a single VA in one request. " +
                    "Generates single ISO 20022 pain.001 XML with multiple credit transfer instructions.")
    public ResponseEntity<ApiResponse<BulkPaymentResponse>> processBulkOutwardPayment(
            @RequestBody BulkPaymentRequest request) {
        log.info("REST: Process bulk outward payment - sourceVa={}, instructionCount={}",
                request.getSourceVaId(), request.getInstructions().size());

        try {
            BulkPaymentResponse response = outwardPaymentService.processBulkOutwardPayment(request);

            if (response.isSuccess()) {
                return ResponseEntity.ok(ApiResponse.success(response,
                        String.format("Bulk payment processed: %d/%d successful",
                                response.getSuccessCount(), response.getTotalCount())));
            } else {
                return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                        .body(ApiResponse.success(response,
                                String.format("Bulk payment partial: %d/%d successful",
                                        response.getSuccessCount(), response.getTotalCount())));
            }
        } catch (Exception e) {
            log.error("Bulk payment failed: ", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error("Bulk payment failed: " + e.getMessage()));
        }
    }

    // ========================================================================
    // PAIN.001 GENERATION (XML Download)
    // ========================================================================

    @PostMapping(value = "/outward/pain001/generate", produces = MediaType.APPLICATION_XML_VALUE)
    @Operation(summary = "Generate pain.001 XML",
            description = "Generate ISO 20022 pain.001 Customer Credit Transfer Initiation XML for an outward payment")
    public ResponseEntity<String> generatePain001(
            @RequestBody OutwardPaymentRequest request) {
        log.info("REST: Generate pain.001 XML for outward payment");

        OutwardPaymentResponse response = outwardPaymentService.processOutwardPayment(request);

        if (response.isSuccess() && response.getPain001Xml() != null) {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_XML)
                    .header("Content-Disposition", "attachment; filename=pain001_" + response.getMessageId() + ".xml")
                    .body(response.getPain001Xml());
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("<!-- Error: " + response.getErrorMessage() + " -->");
        }
    }

    @PostMapping(value = "/outward/bulk/pain001/generate", produces = MediaType.APPLICATION_XML_VALUE)
    @Operation(summary = "Generate bulk pain.001 XML",
            description = "Generate ISO 20022 pain.001 XML with multiple credit transfer instructions")
    public ResponseEntity<String> generateBulkPain001(
            @RequestBody BulkPaymentRequest request) {
        log.info("REST: Generate bulk pain.001 XML");

        try {
            BulkPaymentResponse response = outwardPaymentService.processBulkOutwardPayment(request);

            if (response.getPain001Xml() != null) {
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_XML)
                        .header("Content-Disposition", "attachment; filename=pain001_bulk_" + response.getMessageId() + ".xml")
                        .body(response.getPain001Xml());
            } else {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("<!-- Error generating pain.001 -->");
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("<!-- Error: " + e.getMessage() + " -->");
        }
    }

    // ========================================================================
    // PAYMENT STATUS (pain.002)
    // ========================================================================

    @PostMapping("/status")
    @Operation(summary = "Get payment status",
            description = "Query payment status by message ID, instruction ID, end-to-end ID, or transaction reference. " +
                    "Returns ISO 20022 pain.002 compliant status report.")
    public ResponseEntity<ApiResponse<PaymentStatusResponse>> getPaymentStatus(
            @RequestBody PaymentStatusRequest request) {
        log.info("REST: Get payment status - msgId={}, txnRef={}",
                request.getOriginalMessageId(), request.getTransactionReference());

        try {
            PaymentStatusResponse response = statusService.getPaymentStatus(request);
            return ResponseEntity.ok(ApiResponse.success(response, "Payment status retrieved"));
        } catch (Exception e) {
            log.error("Failed to get payment status: ", e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("Payment not found: " + e.getMessage()));
        }
    }

    @PostMapping(value = "/status/pain002", produces = MediaType.APPLICATION_XML_VALUE)
    @Operation(summary = "Get payment status as pain.002 XML",
            description = "Query payment status and return ISO 20022 pain.002 XML")
    public ResponseEntity<String> getPaymentStatusXml(
            @RequestBody PaymentStatusRequest request) {
        log.info("REST: Get payment status as pain.002 XML");

        try {
            PaymentStatusResponse response = statusService.getPaymentStatus(request);
            if (response.getPain002Xml() != null) {
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_XML)
                        .body(response.getPain002Xml());
            } else {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("<!-- Failed to generate pain.002 -->");
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("<!-- Payment not found: " + e.getMessage() + " -->");
        }
    }

    // ========================================================================
    // BANK STATEMENT (camt.053)
    // ========================================================================

    @PostMapping("/statement")
    @Operation(summary = "Generate bank statement",
            description = "Generate ISO 20022 camt.053 bank statement for a virtual account")
    public ResponseEntity<ApiResponse<StatementResponse>> generateStatement(
            @RequestBody StatementRequest request) {
        log.info("REST: Generate statement - va={}, from={}, to={}",
                request.getVirtualAccountId(), request.getFromDate(), request.getToDate());

        try {
            StatementResponse response = statusService.generateStatement(request);
            return ResponseEntity.ok(ApiResponse.success(response, "Statement generated successfully"));
        } catch (Exception e) {
            log.error("Failed to generate statement: ", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error("Failed to generate statement: " + e.getMessage()));
        }
    }

    @PostMapping(value = "/statement/camt053", produces = MediaType.APPLICATION_XML_VALUE)
    @Operation(summary = "Generate camt.053 XML statement",
            description = "Generate and download ISO 20022 camt.053 Bank-to-Customer Statement XML")
    public ResponseEntity<String> generateCamt053(
            @RequestBody StatementRequest request) {
        log.info("REST: Generate camt.053 XML statement");

        try {
            StatementResponse response = statusService.generateStatement(request);
            if (response.getCamt053Xml() != null) {
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_XML)
                        .header("Content-Disposition", "attachment; filename=camt053_" + response.getStatementId() + ".xml")
                        .body(response.getCamt053Xml());
            } else {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("<!-- Failed to generate camt.053 -->");
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("<!-- Error: " + e.getMessage() + " -->");
        }
    }

    @GetMapping("/statement/{vaId}")
    @Operation(summary = "Get statement for VA by ID",
            description = "Generate ISO 20022 statement for a VA using path parameter")
    public ResponseEntity<ApiResponse<StatementResponse>> getStatementByVaId(
            @PathVariable UUID vaId,
            @RequestParam(required = false) java.time.LocalDate fromDate,
            @RequestParam(required = false) java.time.LocalDate toDate) {
        log.info("REST: Get statement for VA {} from {} to {}", vaId, fromDate, toDate);

        try {
            StatementRequest request = StatementRequest.builder()
                    .virtualAccountId(vaId)
                    .fromDate(fromDate)
                    .toDate(toDate)
                    .build();

            StatementResponse response = statusService.generateStatement(request);
            return ResponseEntity.ok(ApiResponse.success(response, "Statement generated successfully"));
        } catch (Exception e) {
            log.error("Failed to generate statement: ", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error("Failed to generate statement: " + e.getMessage()));
        }
    }
}
