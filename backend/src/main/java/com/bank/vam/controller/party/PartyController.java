package com.bank.vam.controller.party;

import com.bank.vam.dto.party.PartyDto.*;
import com.bank.vam.service.party.PartyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Party Controller - Enhanced with POBO/IC endpoints
 * 
 * Phase 1: Adds REST endpoints for Payment Factory capabilities:
 * - POBO eligibility management
 * - Intercompany party management
 * - Netting eligibility queries
 * - Entity context filtering
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/parties")
@RequiredArgsConstructor
@Tag(name = "Party Management", description = "APIs for managing parties (vendors, customers, employees)")
public class PartyController {

    private final PartyService partyService;

    // Demo corporate ID
    private static final UUID DEMO_CORPORATE_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    // ========================================================================
    // EXISTING ENDPOINTS (Preserved for backward compatibility)
    // ========================================================================

    @GetMapping
    @Operation(summary = "Get all parties", description = "Returns paginated list of all parties with optional filters")
    public ResponseEntity<PartyListResponse> getAllParties(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId,
            @Parameter(description = "Search query") @RequestParam(required = false) String query,
            @Parameter(description = "Party type filter") @RequestParam(required = false) String partyType,
            @Parameter(description = "Role filter") @RequestParam(required = false) String role,
            @Parameter(description = "KYC status filter") @RequestParam(required = false) String kycStatus,
            @Parameter(description = "Risk rating filter") @RequestParam(required = false) String riskRating,
            @Parameter(description = "Status filter") @RequestParam(required = false) String status,
            @Parameter(description = "Page number") @RequestParam(defaultValue = "0") Integer page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") Integer pageSize,
            @Parameter(description = "Sort by field") @RequestParam(defaultValue = "createdAt") String sortBy,
            @Parameter(description = "Sort order") @RequestParam(defaultValue = "desc") String sortOrder,
            // Phase 1: Additional filters
            @Parameter(description = "Filter by owning entity") @RequestParam(required = false) UUID owningEntityId,
            @Parameter(description = "Show only POBO eligible") @RequestParam(required = false) Boolean poboEligibleOnly,
            @Parameter(description = "Show only intercompany") @RequestParam(required = false) Boolean intercompanyOnly,
            @Parameter(description = "Show only netting eligible") @RequestParam(required = false) Boolean nettingEligibleOnly
    ) {
        PartySearchRequest request = PartySearchRequest.builder()
            .query(query)
            .partyType(partyType)
            .role(role)
            .kycStatus(kycStatus)
            .riskRating(riskRating)
            .status(status)
            .page(page)
            .pageSize(pageSize)
            .sortBy(sortBy)
            .sortOrder(sortOrder)
            .owningEntityId(owningEntityId)
            .poboEligibleOnly(poboEligibleOnly)
            .intercompanyOnly(intercompanyOnly)
            .nettingEligibleOnly(nettingEligibleOnly)
            .build();

        return ResponseEntity.ok(partyService.getAllParties(
            corporateId != null ? corporateId : DEMO_CORPORATE_ID, request));
    }

    @GetMapping("/{partyId}")
    @Operation(summary = "Get party by ID", description = "Returns basic party information")
    public ResponseEntity<PartyResponse> getParty(
            @PathVariable UUID partyId
    ) {
        return ResponseEntity.ok(partyService.getParty(partyId));
    }

    @GetMapping("/{partyId}/detail")
    @Operation(summary = "Get party details", description = "Returns full party details including bank accounts, documents, and compliance info")
    public ResponseEntity<PartyDetailResponse> getPartyDetail(
            @PathVariable UUID partyId
    ) {
        return ResponseEntity.ok(partyService.getPartyDetail(partyId));
    }

    @PostMapping
    @Operation(summary = "Create party", description = "Creates a new party (vendor, customer, employee, etc.)")
    public ResponseEntity<PartyResponse> createParty(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId,
            @RequestBody CreatePartyRequest request
    ) {
        return ResponseEntity.ok(partyService.createParty(
            corporateId != null ? corporateId : DEMO_CORPORATE_ID, request));
    }

    @PutMapping("/{partyId}")
    @Operation(summary = "Update party", description = "Updates an existing party")
    public ResponseEntity<PartyResponse> updateParty(
            @PathVariable UUID partyId,
            @RequestBody UpdatePartyRequest request
    ) {
        return ResponseEntity.ok(partyService.updateParty(partyId, request));
    }

    @DeleteMapping("/{partyId}")
    @Operation(summary = "Delete party", description = "Soft deletes a party (sets status to INACTIVE)")
    public ResponseEntity<Void> deleteParty(
            @PathVariable UUID partyId
    ) {
        partyService.deleteParty(partyId);
        return ResponseEntity.noContent().build();
    }

    // ========================================================================
    // BANK ACCOUNTS
    // ========================================================================

    @GetMapping("/{partyId}/bank-accounts")
    @Operation(summary = "Get party bank accounts", description = "Returns all bank accounts for a party")
    public ResponseEntity<List<BankAccountResponse>> getBankAccounts(
            @PathVariable UUID partyId
    ) {
        return ResponseEntity.ok(partyService.getPartyBankAccounts(partyId));
    }

    @PostMapping("/{partyId}/bank-accounts")
    @Operation(summary = "Add bank account", description = "Adds a new bank account to a party")
    public ResponseEntity<BankAccountResponse> addBankAccount(
            @PathVariable UUID partyId,
            @RequestBody AddBankAccountRequest request
    ) {
        return ResponseEntity.ok(partyService.addBankAccount(partyId, request));
    }

    @DeleteMapping("/bank-accounts/{accountId}")
    @Operation(summary = "Delete bank account", description = "Deletes a bank account")
    public ResponseEntity<Void> deleteBankAccount(
            @PathVariable UUID accountId
    ) {
        partyService.deleteBankAccount(accountId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/bank-accounts/{accountId}/verify")
    @Operation(summary = "Verify bank account", description = "Marks a bank account as verified")
    public ResponseEntity<BankAccountResponse> verifyBankAccount(
            @PathVariable UUID accountId,
            @RequestParam String verifiedBy
    ) {
        return ResponseEntity.ok(partyService.verifyBankAccount(accountId, verifiedBy));
    }

    // ========================================================================
    // DOCUMENTS
    // ========================================================================

    @GetMapping("/{partyId}/documents")
    @Operation(summary = "Get party documents", description = "Returns all documents for a party")
    public ResponseEntity<List<DocumentResponse>> getDocuments(
            @PathVariable UUID partyId
    ) {
        return ResponseEntity.ok(partyService.getPartyDocuments(partyId));
    }

    @PostMapping("/{partyId}/documents")
    @Operation(summary = "Upload document", description = "Uploads a new document for a party")
    public ResponseEntity<DocumentResponse> uploadDocument(
            @PathVariable UUID partyId,
            @RequestBody UploadDocumentRequest request
    ) {
        return ResponseEntity.ok(partyService.uploadDocument(partyId, request));
    }

    @PostMapping("/documents/{documentId}/verify")
    @Operation(summary = "Verify document", description = "Verifies or rejects a document")
    public ResponseEntity<DocumentResponse> verifyDocument(
            @PathVariable UUID documentId,
            @RequestBody VerifyDocumentRequest request
    ) {
        return ResponseEntity.ok(partyService.verifyDocument(documentId, request));
    }

    @DeleteMapping("/documents/{documentId}")
    @Operation(summary = "Delete document", description = "Deletes a document")
    public ResponseEntity<Void> deleteDocument(
            @PathVariable UUID documentId
    ) {
        partyService.deleteDocument(documentId);
        return ResponseEntity.noContent().build();
    }

    // ========================================================================
    // COMPLIANCE
    // ========================================================================

    @PutMapping("/{partyId}/kyc")
    @Operation(summary = "Update KYC status", description = "Updates the KYC status of a party")
    public ResponseEntity<PartyResponse> updateKycStatus(
            @PathVariable UUID partyId,
            @RequestBody UpdateKycRequest request
    ) {
        return ResponseEntity.ok(partyService.updateKycStatus(partyId, request));
    }

    @PutMapping("/{partyId}/risk")
    @Operation(summary = "Update risk rating", description = "Updates the risk rating of a party")
    public ResponseEntity<PartyResponse> updateRiskRating(
            @PathVariable UUID partyId,
            @RequestBody UpdateRiskRequest request
    ) {
        return ResponseEntity.ok(partyService.updateRiskRating(partyId, request));
    }

    @PostMapping("/{partyId}/screening")
    @Operation(summary = "Run screening", description = "Runs compliance screening (sanctions, PEP, adverse media)")
    public ResponseEntity<ScreeningResponse> runScreening(
            @PathVariable UUID partyId,
            @RequestBody ScreeningRequest request
    ) {
        return ResponseEntity.ok(partyService.runScreening(partyId, request));
    }

    // ========================================================================
    // STATS
    // ========================================================================

    @GetMapping("/stats")
    @Operation(summary = "Get party statistics", description = "Returns party statistics including POBO/IC counts")
    public ResponseEntity<PartyStatsResponse> getStats(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId
    ) {
        return ResponseEntity.ok(partyService.getExtendedStats(
            corporateId != null ? corporateId : DEMO_CORPORATE_ID));
    }

    // ========================================================================
    // PHASE 1: POBO ELIGIBILITY ENDPOINTS
    // ========================================================================

    @GetMapping("/pobo-eligible")
    @Operation(summary = "Get POBO eligible vendors", 
               description = "Returns all vendors eligible for POBO (Pay On Behalf Of) payments")
    public ResponseEntity<List<PartyResponse>> getPoboEligibleVendors(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId
    ) {
        return ResponseEntity.ok(partyService.getPoboEligibleVendors(
            corporateId != null ? corporateId : DEMO_CORPORATE_ID));
    }

    @GetMapping("/pobo-eligible/by-entity/{owningEntityId}")
    @Operation(summary = "Get POBO eligible vendors by owning entity", 
               description = "Returns POBO-eligible vendors for a specific subsidiary/entity")
    public ResponseEntity<List<PartyResponse>> getPoboEligibleVendorsByOwningEntity(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId,
            @PathVariable UUID owningEntityId
    ) {
        return ResponseEntity.ok(partyService.getPoboEligibleVendorsByOwningEntity(
            corporateId != null ? corporateId : DEMO_CORPORATE_ID, owningEntityId));
    }

    @PutMapping("/{partyId}/pobo-eligibility")
    @Operation(summary = "Update POBO eligibility", 
               description = "Enable or disable POBO eligibility for a vendor party")
    public ResponseEntity<PartyResponse> updatePoboEligibility(
            @PathVariable UUID partyId,
            @RequestBody UpdatePoboEligibilityRequest request
    ) {
        return ResponseEntity.ok(partyService.updatePoboEligibility(partyId, request));
    }

    @PostMapping("/pobo/validate")
    @Operation(summary = "Validate POBO eligibility", 
               description = "Validate if a party can receive POBO payment for a given amount")
    public ResponseEntity<PoboValidationResponse> validatePoboEligibility(
            @RequestBody ValidatePoboRequest request
    ) {
        return ResponseEntity.ok(partyService.validatePoboEligibility(request));
    }

    // ========================================================================
    // PHASE 1: INTERCOMPANY PARTY ENDPOINTS
    // ========================================================================

    @GetMapping("/intercompany")
    @Operation(summary = "Get intercompany parties", 
               description = "Returns all intercompany parties (group entities as vendors/customers)")
    public ResponseEntity<List<PartyResponse>> getIntercompanyParties(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId
    ) {
        return ResponseEntity.ok(partyService.getIntercompanyParties(
            corporateId != null ? corporateId : DEMO_CORPORATE_ID));
    }

    @GetMapping("/intercompany/by-entity/{owningEntityId}")
    @Operation(summary = "Get intercompany parties by owning entity", 
               description = "Returns intercompany parties for a specific subsidiary")
    public ResponseEntity<List<PartyResponse>> getIntercompanyPartiesByOwningEntity(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId,
            @PathVariable UUID owningEntityId
    ) {
        return ResponseEntity.ok(partyService.getIntercompanyPartiesByOwningEntity(
            corporateId != null ? corporateId : DEMO_CORPORATE_ID, owningEntityId));
    }

    @PostMapping("/intercompany")
    @Operation(summary = "Create intercompany party", 
               description = "Create a party representing a group entity as vendor/customer")
    public ResponseEntity<PartyResponse> createIntercompanyParty(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId,
            @RequestBody CreateIntercompanyPartyRequest request
    ) {
        return ResponseEntity.ok(partyService.createIntercompanyParty(
            corporateId != null ? corporateId : DEMO_CORPORATE_ID, request));
    }

    @PutMapping("/{partyId}/intercompany-config")
    @Operation(summary = "Update intercompany configuration", 
               description = "Update intercompany settings for a party")
    public ResponseEntity<PartyResponse> updateIntercompanyConfig(
            @PathVariable UUID partyId,
            @RequestBody UpdateIntercompanyConfigRequest request
    ) {
        return ResponseEntity.ok(partyService.updateIntercompanyConfig(partyId, request));
    }

    @PutMapping("/{partyId}/ic-credit-limit")
    @Operation(summary = "Update IC credit limit", 
               description = "Update intercompany credit limit for an IC party")
    public ResponseEntity<PartyResponse> updateIcCreditLimit(
            @PathVariable UUID partyId,
            @RequestBody UpdateIcCreditLimitRequest request
    ) {
        return ResponseEntity.ok(partyService.updateIcCreditLimit(partyId, request));
    }

    // ========================================================================
    // PHASE 1: NETTING ELIGIBILITY ENDPOINTS
    // ========================================================================

    @GetMapping("/netting-eligible")
    @Operation(summary = "Get netting eligible parties", 
               description = "Returns all parties eligible for netting (intercompany + explicitly enabled)")
    public ResponseEntity<List<PartyResponse>> getNettingEligibleParties(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId
    ) {
        return ResponseEntity.ok(partyService.getNettingEligibleParties(
            corporateId != null ? corporateId : DEMO_CORPORATE_ID));
    }

    @GetMapping("/netting-eligible/by-entity/{owningEntityId}")
    @Operation(summary = "Get netting eligible parties by owning entity", 
               description = "Returns netting-eligible parties for a specific subsidiary")
    public ResponseEntity<List<PartyResponse>> getNettingEligiblePartiesByOwningEntity(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId,
            @PathVariable UUID owningEntityId
    ) {
        return ResponseEntity.ok(partyService.getNettingEligiblePartiesByOwningEntity(
            corporateId != null ? corporateId : DEMO_CORPORATE_ID, owningEntityId));
    }

    // ========================================================================
    // PHASE 1: ENTITY CONTEXT ENDPOINTS
    // ========================================================================

    @GetMapping("/by-entity/{owningEntityId}")
    @Operation(summary = "Get parties by owning entity", 
               description = "Returns all parties owned by a specific legal entity (subsidiary)")
    public ResponseEntity<List<PartyResponse>> getPartiesByOwningEntity(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId,
            @PathVariable UUID owningEntityId
    ) {
        return ResponseEntity.ok(partyService.getPartiesByOwningEntity(
            corporateId != null ? corporateId : DEMO_CORPORATE_ID, owningEntityId));
    }

    @GetMapping("/vendors/by-entity/{owningEntityId}")
    @Operation(summary = "Get vendors by owning entity", 
               description = "Returns vendors for a specific subsidiary with pagination")
    public ResponseEntity<Page<PartyResponse>> getVendorsByOwningEntity(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId,
            @PathVariable UUID owningEntityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "legalName") String sortBy,
            @RequestParam(defaultValue = "asc") String sortOrder
    ) {
        Sort sort = "asc".equalsIgnoreCase(sortOrder) ? 
            Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        return ResponseEntity.ok(partyService.getVendorsByOwningEntity(
            corporateId != null ? corporateId : DEMO_CORPORATE_ID, 
            owningEntityId, 
            PageRequest.of(page, size, sort)));
    }

    @GetMapping("/customers/by-entity/{owningEntityId}")
    @Operation(summary = "Get customers by owning entity", 
               description = "Returns customers for a specific subsidiary with pagination")
    public ResponseEntity<Page<PartyResponse>> getCustomersByOwningEntity(
            @RequestHeader(value = "X-Corporate-Id", required = false) UUID corporateId,
            @PathVariable UUID owningEntityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "legalName") String sortBy,
            @RequestParam(defaultValue = "asc") String sortOrder
    ) {
        Sort sort = "asc".equalsIgnoreCase(sortOrder) ? 
            Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        return ResponseEntity.ok(partyService.getCustomersByOwningEntity(
            corporateId != null ? corporateId : DEMO_CORPORATE_ID, 
            owningEntityId, 
            PageRequest.of(page, size, sort)));
    }
}