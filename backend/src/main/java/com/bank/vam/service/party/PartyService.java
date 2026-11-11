package com.bank.vam.service.party;

import com.bank.vam.dto.party.PartyDto.*;
import com.bank.vam.entity.party.Party;
import com.bank.vam.entity.party.Party.*;
import com.bank.vam.entity.party.PartyBankAccount;
import com.bank.vam.entity.party.PartyDocument;
import com.bank.vam.entity.party.PartyDocument.*;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.party.PartyRepository;
import com.bank.vam.repository.party.PartyBankAccountRepository;
import com.bank.vam.repository.party.PartyDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Party Service - Enhanced with POBO/IC methods
 * 
 * Phase 1: Adds Payment Factory capabilities:
 * - POBO eligibility management
 * - Intercompany party management
 * - Netting eligibility queries
 * - IC credit limit management
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartyService {

    private final PartyRepository partyRepository;
    private final PartyBankAccountRepository bankAccountRepository;
    private final PartyDocumentRepository documentRepository;

// Thread-safe counter for party code generation (initialized with current time for uniqueness across restarts)
    private static final AtomicLong PARTY_CODE_COUNTER = new AtomicLong(System.currentTimeMillis() % 100000);
    
    // Maximum retry attempts for party code generation conflicts
    private static final int MAX_RETRY_ATTEMPTS = 5;

    // ========================================================================
    // PARTY CRUD (Preserved with Phase 1 enhancements)
    // ========================================================================

    @Transactional(readOnly = true)
    public PartyListResponse getAllParties(UUID corporateId, PartySearchRequest request) {
        int page = request != null && request.getPage() != null ? request.getPage() : 0;
        int size = request != null && request.getPageSize() != null ? request.getPageSize() : 20;
        String sortBy = request != null && request.getSortBy() != null ? request.getSortBy() : "createdAt";
        Sort.Direction direction = request != null && "asc".equalsIgnoreCase(request.getSortOrder()) 
            ? Sort.Direction.ASC : Sort.Direction.DESC;
        
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortBy));
        
        Page<Party> parties;

// Apply filters including Phase 1 filters
        if (request != null && hasFilters(request)) {
            PartyType partyType = request.getPartyType() != null ? 
                PartyType.valueOf(request.getPartyType()) : null;
            KycStatus kycStatus = request.getKycStatus() != null ? 
                KycStatus.valueOf(request.getKycStatus()) : null;
            RiskRating riskRating = request.getRiskRating() != null ? 
                RiskRating.valueOf(request.getRiskRating()) : null;
            PartyStatus status = request.getStatus() != null ? 
                PartyStatus.valueOf(request.getStatus()) : null;
            
            parties = partyRepository.searchWithFilters(
                corporateId, request.getQuery(), partyType, kycStatus, 
                riskRating, status, request.getRole(), pageable);
        } else {
            parties = partyRepository.findByCorporateId(corporateId, pageable);
        }
        
        List<PartyResponse> partyResponses = parties.getContent().stream()
            .map(this::mapToResponse)
            .collect(Collectors.toList());
        
        PartyStatsResponse stats = getExtendedStats(corporateId);
        
        return PartyListResponse.builder()
            .parties(partyResponses)
            .totalCount(parties.getTotalElements())
            .page(page)
            .pageSize(size)
            .stats(stats)
            .build();
    }

    @Transactional(readOnly = true)
    public PartyResponse getParty(UUID partyId) {
        Party party = partyRepository.findById(partyId)
            .orElseThrow(() -> new RuntimeException("Party not found: " + partyId));
        return mapToResponse(party);
    }

    @Transactional(readOnly = true)
    public PartyDetailResponse getPartyDetail(UUID partyId) {
        Party party = partyRepository.findById(partyId)
            .orElseThrow(() -> new ResourceNotFoundException("Party not found: " + partyId));

        List<BankAccountResponse> bankAccounts = bankAccountRepository.findByPartyId(partyId)
            .stream().map(this::mapToBankAccountResponse).collect(Collectors.toList());
        
        List<PartyDocument> partyDocuments = documentRepository.findByPartyId(partyId);
        List<DocumentResponse> documents = partyDocuments
            .stream().map(this::mapToDocumentResponse).collect(Collectors.toList());

        ComplianceDetailResponse compliance = buildComplianceDetail(party, partyDocuments);
        // ponytail: nothing writes party events to the audit log yet, so there is no activity to show
        List<ActivityLogResponse> activityLog = List.of();

        // Phase 1: Build POBO and IC config
        PoboConfigResponse poboConfig = buildPoboConfig(party);
        IntercompanyConfigResponse icConfig = buildIntercompanyConfig(party);
        
        return PartyDetailResponse.builder()
            .party(mapToResponse(party))
            .bankAccounts(bankAccounts)
            .documents(documents)
            .compliance(compliance)
            .activityLog(activityLog)
            .poboConfig(poboConfig)
            .intercompanyConfig(icConfig)
            .build();
    }

    @Transactional
    public PartyResponse createParty(UUID corporateId, CreatePartyRequest request) {
        if (corporateId == null) throw new BusinessException("corporateId is required");
// Retry loop to handle potential duplicate key conflicts
        for (int attempt = 0; attempt < MAX_RETRY_ATTEMPTS; attempt++) {
            String partyCode = generateUniquePartyCode(request.getPartyType(), request.getRoles());
            
            // Check if code already exists (optimistic check before save)
            if (partyRepository.findByPartyCode(partyCode).isPresent()) {
                log.warn("Party code {} already exists, generating new code (attempt {})", partyCode, attempt + 1);
                continue;
            }
            
            try {
                Party party = Party.builder()
                    .corporateId(corporateId)
                    .partyCode(partyCode)
                    .partyType(PartyType.valueOf(request.getPartyType()))
                    .legalName(request.getLegalName())
                    .displayName(request.getDisplayName())
                    .tradeName(request.getTradeName())
                    .taxId(request.getTaxId())
                    .registrationNumber(request.getRegistrationNumber())
                    .registrationCountry(request.getRegistrationCountry())
                    .contactName(request.getContactName())
                    .contactEmail(request.getContactEmail())
                    .contactPhone(request.getContactPhone())
                    .addressLine1(request.getAddressLine1())
                    .addressLine2(request.getAddressLine2())
                    .city(request.getCity())
                    .state(request.getState())
                    .postalCode(request.getPostalCode())
                    .country(request.getCountry())
                    .ecommerceEnabled(request.getEcommerceEnabled())
                    .ecommercePlatforms(request.getEcommercePlatforms() != null ? 
                        String.join(",", request.getEcommercePlatforms()) : null)
                    .employeeId(request.getEmployeeId())
                    .department(request.getDepartment())
                    .roles(request.getRoles() != null ? String.join(",", request.getRoles()) : "")
                    // Phase 1: Entity context
                    .owningEntityId(request.getOwningEntityId())
                    .owningEntityCode(request.getOwningEntityCode())
                    // Defaults
                    .kycStatus(KycStatus.PENDING)
                    .riskRating(RiskRating.MEDIUM)
                    .sanctionsStatus(null) // not screened yet
.status(PartyStatus.ACTIVE)
                    .poboEligible(false)
                    .isIntercompany(false)
                    .nettingEligible(false)
                    .build();
                
                party = partyRepository.save(party);
                log.info("Created party: {} - {} for owning entity: {}", 
                    party.getPartyCode(), party.getLegalName(), party.getOwningEntityCode());
                
                return mapToResponse(party);
                
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // Duplicate key constraint violation - retry with a new code
                log.warn("Duplicate key violation for party code {}, retrying (attempt {})", partyCode, attempt + 1);
                if (attempt == MAX_RETRY_ATTEMPTS - 1) {
                    throw new RuntimeException("Failed to generate unique party code after " + MAX_RETRY_ATTEMPTS + " attempts", e);
                }
            }
        }
        
        // Should not reach here, but just in case
        throw new RuntimeException("Failed to create party: unable to generate unique party code");
    }

    @Transactional
    public PartyResponse updateParty(UUID partyId, UpdatePartyRequest request) {
        Party party = partyRepository.findById(partyId)
            .orElseThrow(() -> new RuntimeException("Party not found: " + partyId));
        
        if (request.getDisplayName() != null) party.setDisplayName(request.getDisplayName());
        if (request.getTradeName() != null) party.setTradeName(request.getTradeName());
        if (request.getRoles() != null) party.setRoles(String.join(",", request.getRoles()));
        if (request.getTaxId() != null) party.setTaxId(request.getTaxId());
        if (request.getRegistrationNumber() != null) party.setRegistrationNumber(request.getRegistrationNumber());
        if (request.getContactName() != null) party.setContactName(request.getContactName());
        if (request.getContactEmail() != null) party.setContactEmail(request.getContactEmail());
        if (request.getContactPhone() != null) party.setContactPhone(request.getContactPhone());
        if (request.getAddressLine1() != null) party.setAddressLine1(request.getAddressLine1());
        if (request.getAddressLine2() != null) party.setAddressLine2(request.getAddressLine2());
        if (request.getCity() != null) party.setCity(request.getCity());
        if (request.getState() != null) party.setState(request.getState());
        if (request.getPostalCode() != null) party.setPostalCode(request.getPostalCode());
        if (request.getCountry() != null) party.setCountry(request.getCountry());
        if (request.getEcommerceEnabled() != null) party.setEcommerceEnabled(request.getEcommerceEnabled());
        if (request.getEcommercePlatforms() != null) party.setEcommercePlatforms(String.join(",", request.getEcommercePlatforms()));
        if (request.getEmployeeId() != null) party.setEmployeeId(request.getEmployeeId());
        if (request.getDepartment() != null) party.setDepartment(request.getDepartment());
        if (request.getStatus() != null) party.setStatus(PartyStatus.valueOf(request.getStatus()));
        // Phase 1: Entity context
        if (request.getOwningEntityId() != null) party.setOwningEntityId(request.getOwningEntityId());
        if (request.getOwningEntityCode() != null) party.setOwningEntityCode(request.getOwningEntityCode());
        
        party = partyRepository.save(party);
        log.info("Updated party: {}", party.getPartyCode());
        
        return mapToResponse(party);
    }

    @Transactional
    public void deleteParty(UUID partyId) {
        Party party = partyRepository.findById(partyId)
            .orElseThrow(() -> new RuntimeException("Party not found: " + partyId));
        
        party.setStatus(PartyStatus.INACTIVE);
        partyRepository.save(party);
        log.info("Deactivated party: {}", party.getPartyCode());
    }

    // ========================================================================
    // PHASE 1: POBO ELIGIBILITY MANAGEMENT
    // ========================================================================
    
    /**
     * Get all POBO-eligible vendors for a corporate.
     */
    @Transactional(readOnly = true)
    public List<PartyResponse> getPoboEligibleVendors(UUID corporateId) {
        List<Party> vendors = partyRepository.findPoboEligibleVendors(corporateId);

        return vendors.stream()
            .map(this::mapToResponse)
            .collect(Collectors.toList());
    }
    
    /**
     * Get POBO-eligible vendors for a specific owning entity.
     */
    @Transactional(readOnly = true)
    public List<PartyResponse> getPoboEligibleVendorsByOwningEntity(UUID corporateId, UUID owningEntityId) {
        List<Party> vendors = partyRepository.findPoboEligibleVendorsByOwningEntity(corporateId, owningEntityId);
        
        return vendors.stream()
            .map(this::mapToResponse)
            .collect(Collectors.toList());
    }
    
    /**
     * Update POBO eligibility for a party.
     */
    @Transactional
    public PartyResponse updatePoboEligibility(UUID partyId, UpdatePoboEligibilityRequest request) {
        Party party = partyRepository.findById(partyId)
            .orElseThrow(() -> new RuntimeException("Party not found: " + partyId));
        
        // Validate: Only vendors can be POBO eligible
        if (Boolean.TRUE.equals(request.getPoboEligible()) && !party.isVendor()) {
            throw new RuntimeException("Only vendor parties can be POBO eligible");
        }
        
        // Validate: Must be KYC verified
        if (Boolean.TRUE.equals(request.getPoboEligible()) && 
            party.getKycStatus() != KycStatus.VERIFIED && 
            party.getKycStatus() != KycStatus.EXEMPTED) {
            throw new RuntimeException("Party must be KYC verified to enable POBO");
        }
        
        party.setPoboEligible(request.getPoboEligible());
        party.setPoboDefaultPayerEntityId(request.getDefaultPayerEntityId());
        party.setPoboDefaultPayerEntityCode(request.getDefaultPayerEntityCode());
        
        party = partyRepository.save(party);
        log.info("Updated POBO eligibility for party {}: eligible={}, defaultPayer={}", 
            party.getPartyCode(), party.getPoboEligible(), party.getPoboDefaultPayerEntityCode());
        
        return mapToResponse(party);
    }
    
    /**
     * Validate if a party can receive POBO payment.
     */
    @Transactional(readOnly = true)
    public PoboValidationResponse validatePoboEligibility(ValidatePoboRequest request) {
        Party party = partyRepository.findById(request.getPartyId())
            .orElse(null);
        
        if (party == null) {
            return PoboValidationResponse.builder()
                .isValid(false)
                .reason("Party not found")
                .warnings(Collections.emptyList())
                .build();
        }
        
        List<String> warnings = new ArrayList<>();
        
        // Check party status
        if (party.getStatus() != PartyStatus.ACTIVE) {
            return PoboValidationResponse.builder()
                .isValid(false)
                .reason("Party is not active")
                .partyStatus(party.getStatus().name())
                .kycStatus(party.getKycStatus().name())
                .poboEligible(party.getPoboEligible())
                .warnings(warnings)
                .build();
        }
        
        // Check KYC status
        if (party.getKycStatus() != KycStatus.VERIFIED && party.getKycStatus() != KycStatus.EXEMPTED) {
            return PoboValidationResponse.builder()
                .isValid(false)
                .reason("Party KYC not verified")
                .partyStatus(party.getStatus().name())
                .kycStatus(party.getKycStatus().name())
                .poboEligible(party.getPoboEligible())
                .warnings(warnings)
                .build();
        }
        
        // Check POBO eligibility
        if (!Boolean.TRUE.equals(party.getPoboEligible())) {
            return PoboValidationResponse.builder()
                .isValid(false)
                .reason("Party not POBO eligible")
                .partyStatus(party.getStatus().name())
                .kycStatus(party.getKycStatus().name())
                .poboEligible(party.getPoboEligible())
                .warnings(warnings)
                .build();
        }
        
        // Check IC credit limit if intercompany
        BigDecimal availableCredit = null;
        if (party.isGroupEntity() && party.getIcCreditLimit() != null) {
            availableCredit = party.getAvailableIcCredit();
            
            if (availableCredit != null && request.getAmount() != null && 
                availableCredit.compareTo(request.getAmount()) < 0) {
                return PoboValidationResponse.builder()
                    .isValid(false)
                    .reason(String.format("Insufficient IC credit limit. Available: %s, Required: %s", 
                        availableCredit, request.getAmount()))
                    .partyStatus(party.getStatus().name())
                    .kycStatus(party.getKycStatus().name())
                    .poboEligible(party.getPoboEligible())
                    .availableCredit(availableCredit)
                    .warnings(warnings)
                    .build();
            }
            
            // Add warning if utilization is high
            if (availableCredit != null && party.getIcCreditLimit() != null) {
                BigDecimal utilization = party.getIcCurrentExposure()
                    .divide(party.getIcCreditLimit(), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
                if (utilization.compareTo(BigDecimal.valueOf(80)) >= 0) {
                    warnings.add(String.format("IC credit utilization at %.1f%%", utilization));
                }
            }
        }
        
        // Add warning for high-value transactions
        if (request.getAmount() != null && request.getAmount().compareTo(BigDecimal.valueOf(100000)) > 0) {
            warnings.add("Large payment amount - additional approval may be required");
        }
        
        return PoboValidationResponse.builder()
            .isValid(true)
            .reason("POBO eligible")
            .partyStatus(party.getStatus().name())
            .kycStatus(party.getKycStatus().name())
            .poboEligible(party.getPoboEligible())
            .availableCredit(availableCredit)
            .warnings(warnings)
            .build();
    }

    // ========================================================================
    // PHASE 1: INTERCOMPANY PARTY MANAGEMENT
    // ========================================================================
    
    /**
     * Get all intercompany parties for a corporate.
     */
    @Transactional(readOnly = true)
    public List<PartyResponse> getIntercompanyParties(UUID corporateId) {
        List<Party> parties = partyRepository.findIntercompanyParties(corporateId);

return parties.stream()
            .map(this::mapToResponse)
            .collect(Collectors.toList());
    }
    
    /**
     * Get intercompany parties for a specific owning entity.
     */
    @Transactional(readOnly = true)
    public List<PartyResponse> getIntercompanyPartiesByOwningEntity(UUID corporateId, UUID owningEntityId) {
        List<Party> parties = partyRepository.findIntercompanyPartiesByOwningEntity(corporateId, owningEntityId);
        
        return parties.stream()
            .map(this::mapToResponse)
            .collect(Collectors.toList());
    }
    
    /**
     * Create an intercompany party (represent a group entity as vendor/customer).
     */
    @Transactional
    public PartyResponse createIntercompanyParty(UUID corporateId, CreateIntercompanyPartyRequest request) {
        if (corporateId == null) throw new BusinessException("corporateId is required");
// Check if IC party already exists for this combination
        Optional<Party> existing = partyRepository.findIntercompanyPartyByLinkedEntity(
            corporateId, request.getLinkedLegalEntityId(), request.getOwningEntityId());
        
        if (existing.isPresent()) {
            throw new RuntimeException("Intercompany party already exists for this entity combination");
        }
        
        // Generate unique IC party code with retry logic
        for (int attempt = 0; attempt < MAX_RETRY_ATTEMPTS; attempt++) {
            String roleType = request.getRoles().contains("VENDOR") ? "VEN" : "CUS";
            String baseCode = "IC-" + request.getLinkedLegalEntityCode() + "-" + roleType;
            
            // Add counter suffix if this is a retry
            String partyCode = attempt == 0 ? baseCode : baseCode + "-" + (PARTY_CODE_COUNTER.incrementAndGet() % 1000);
            
            // Check if code already exists
            if (partyRepository.findByPartyCode(partyCode).isPresent()) {
                log.warn("IC party code {} already exists, generating new code (attempt {})", partyCode, attempt + 1);
                continue;
            }
            
            try {
                Party party = Party.builder()
                    .corporateId(corporateId)
                    .partyCode(partyCode)
                    .partyType(PartyType.COMPANY)
                    .legalName(request.getLinkedLegalEntityName() + " (Intercompany)")
                    .displayName(request.getLinkedLegalEntityCode() + " (IC)")
                    .registrationCountry("--")
                    .country("--")
                    .roles(String.join(",", request.getRoles()))
                    // Entity context
                    .owningEntityId(request.getOwningEntityId())
                    .owningEntityCode(request.getOwningEntityCode())
                    // Intercompany settings
                    .isIntercompany(true)
                    .linkedLegalEntityId(request.getLinkedLegalEntityId())
                    .linkedLegalEntityCode(request.getLinkedLegalEntityCode())
                    .nettingEligible(true)
                    .icSettlementMethod(request.getIcSettlementMethod() != null ? 
                        IntercompanySettlementMethod.valueOf(request.getIcSettlementMethod()) : 
                        IntercompanySettlementMethod.NETTING)
                    .icCreditLimit(request.getIcCreditLimit())
                    .icCurrency(request.getIcCurrency())
                    .icCurrentExposure(BigDecimal.ZERO)
                    // POBO always eligible for IC
                    .poboEligible(true)
                    // Compliance
                    .kycStatus(KycStatus.EXEMPTED)
                    .riskRating(RiskRating.LOW)
                    .sanctionsStatus(null) // not screened yet
.status(PartyStatus.ACTIVE)
                    .build();
                
                party = partyRepository.save(party);
                log.info("Created intercompany party: {} linking {} -> {}", 
                    party.getPartyCode(), party.getOwningEntityCode(), party.getLinkedLegalEntityCode());
                
                return mapToResponse(party);
                
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                log.warn("Duplicate key violation for IC party code {}, retrying (attempt {})", partyCode, attempt + 1);
                if (attempt == MAX_RETRY_ATTEMPTS - 1) {
                    throw new RuntimeException("Failed to generate unique IC party code after " + MAX_RETRY_ATTEMPTS + " attempts", e);
                }
            }
        }
        
        throw new RuntimeException("Failed to create intercompany party: unable to generate unique party code");
    }
    
    /**
     * Update intercompany configuration for a party.
     */
    @Transactional
    public PartyResponse updateIntercompanyConfig(UUID partyId, UpdateIntercompanyConfigRequest request) {
        Party party = partyRepository.findById(partyId)
            .orElseThrow(() -> new RuntimeException("Party not found: " + partyId));
        
        party.setIsIntercompany(request.getIsIntercompany());
        party.setLinkedLegalEntityId(request.getLinkedLegalEntityId());
        party.setLinkedLegalEntityCode(request.getLinkedLegalEntityCode());
        party.setNettingEligible(request.getNettingEligible());
        
        if (request.getIcSettlementMethod() != null) {
            party.setIcSettlementMethod(IntercompanySettlementMethod.valueOf(request.getIcSettlementMethod()));
        }
        
        // If marking as intercompany, auto-enable netting and POBO
        if (Boolean.TRUE.equals(request.getIsIntercompany())) {
            party.setNettingEligible(true);
            party.setPoboEligible(true);
            if (party.getKycStatus() == KycStatus.PENDING) {
                party.setKycStatus(KycStatus.EXEMPTED);
            }
        }
        
        party = partyRepository.save(party);
        log.info("Updated IC config for party {}: isIC={}, linkedEntity={}", 
            party.getPartyCode(), party.getIsIntercompany(), party.getLinkedLegalEntityCode());
        
        return mapToResponse(party);
    }
    
    /**
     * Update IC credit limit for a party.
     */
    @Transactional
    public PartyResponse updateIcCreditLimit(UUID partyId, UpdateIcCreditLimitRequest request) {
        Party party = partyRepository.findById(partyId)
            .orElseThrow(() -> new RuntimeException("Party not found: " + partyId));
        
        if (!Boolean.TRUE.equals(party.getIsIntercompany())) {
            throw new RuntimeException("IC credit limit can only be set for intercompany parties");
        }
        
        party.setIcCreditLimit(request.getCreditLimit());
        party.setIcCurrency(request.getCurrency());
        
        party = partyRepository.save(party);
        log.info("Updated IC credit limit for party {}: limit={} {}", 
            party.getPartyCode(), party.getIcCreditLimit(), party.getIcCurrency());
        
        return mapToResponse(party);
    }

    // ========================================================================
    // PHASE 1: NETTING ELIGIBILITY QUERIES
    // ========================================================================
    
    /**
     * Get all netting-eligible parties for a corporate.
     */
    @Transactional(readOnly = true)
    public List<PartyResponse> getNettingEligibleParties(UUID corporateId) {
        List<Party> parties = partyRepository.findNettingEligibleParties(corporateId);

return parties.stream()
            .map(this::mapToResponse)
            .collect(Collectors.toList());
    }
    
    /**
     * Get netting-eligible parties for a specific owning entity.
     */
    @Transactional(readOnly = true)
    public List<PartyResponse> getNettingEligiblePartiesByOwningEntity(UUID corporateId, UUID owningEntityId) {
        List<Party> parties = partyRepository.findNettingEligiblePartiesByOwningEntity(corporateId, owningEntityId);
        
        return parties.stream()
            .map(this::mapToResponse)
            .collect(Collectors.toList());
    }

    // ========================================================================
    // PHASE 1: ENTITY CONTEXT QUERIES
    // ========================================================================
    
    /**
     * Get parties by owning entity.
     */
    @Transactional(readOnly = true)
    public List<PartyResponse> getPartiesByOwningEntity(UUID corporateId, UUID owningEntityId) {
        List<Party> parties = partyRepository.findByOwningEntityId(corporateId, owningEntityId);
        
        return parties.stream()
            .map(this::mapToResponse)
            .collect(Collectors.toList());
    }
    
    /**
     * Get vendors by owning entity.
     */
    @Transactional(readOnly = true)
    public Page<PartyResponse> getVendorsByOwningEntity(UUID corporateId, UUID owningEntityId, Pageable pageable) {
        Page<Party> parties = partyRepository.findByOwningEntityIdAndRole(
            corporateId, owningEntityId, "VENDOR", pageable);
        
        return parties.map(this::mapToResponse);
    }
    
    /**
     * Get customers by owning entity.
     */
    @Transactional(readOnly = true)
    public Page<PartyResponse> getCustomersByOwningEntity(UUID corporateId, UUID owningEntityId, Pageable pageable) {
        Page<Party> parties = partyRepository.findByOwningEntityIdAndRole(
            corporateId, owningEntityId, "CUSTOMER", pageable);
        
        return parties.map(this::mapToResponse);
    }

    // ========================================================================
    // PHASE 1: EXTENDED STATS
    // ========================================================================
    
    @Transactional(readOnly = true)
    public PartyStatsResponse getExtendedStats(UUID corporateId) {
        List<Object[]> results = partyRepository.getExtendedStats(corporateId);
        
        if (results.isEmpty() || results.get(0)[0] == null || ((Number) results.get(0)[0]).longValue() == 0) {
            return PartyStatsResponse.builder()
                .totalParties(0L).customers(0L).vendors(0L).employees(0L)
                .government(0L).financial(0L).kycPending(0L).kycExpired(0L)
                .highRisk(0L).sanctionsAlerts(0L)
                .poboEligibleVendors(0L).intercompanyParties(0L).nettingEligibleParties(0L)
                .build();
        }
        
        Object[] row = results.get(0);
        return PartyStatsResponse.builder()
            .totalParties(((Number) row[0]).longValue())
            .customers(((Number) row[1]).longValue())
            .vendors(((Number) row[2]).longValue())
            .employees(((Number) row[3]).longValue())
            .government(((Number) row[4]).longValue())
            .financial(((Number) row[5]).longValue())
            .kycPending(((Number) row[6]).longValue())
            .kycExpired(((Number) row[7]).longValue())
            .highRisk(((Number) row[8]).longValue())
            .sanctionsAlerts(((Number) row[9]).longValue())
            .poboEligibleVendors(((Number) row[10]).longValue())
            .intercompanyParties(((Number) row[11]).longValue())
            .nettingEligibleParties(((Number) row[12]).longValue())
            .build();
    }

    // ========================================================================
    // EXISTING METHODS (Preserved for backward compatibility)
    // ========================================================================

    @Transactional(readOnly = true)
    public List<BankAccountResponse> getPartyBankAccounts(UUID partyId) {
        return bankAccountRepository.findByPartyId(partyId).stream().map(this::mapToBankAccountResponse).collect(Collectors.toList());
    }

    @Transactional
    public BankAccountResponse addBankAccount(UUID partyId, AddBankAccountRequest request) {
        Party party = partyRepository.findById(partyId)
            .orElseThrow(() -> new RuntimeException("Party not found: " + partyId));
        
        if (Boolean.TRUE.equals(request.getIsPrimary())) {
            bankAccountRepository.findByPartyIdAndIsPrimaryTrue(partyId)
                .ifPresent(existing -> {
                    existing.setIsPrimary(false);
                    bankAccountRepository.save(existing);
                });
        }
        
        PartyBankAccount account = PartyBankAccount.builder()
            .party(party)
            .label(request.getLabel())
            .holderName(request.getHolderName())
            .bankName(request.getBankName())
            .bankCode(request.getBankCode())
            .iban(request.getIban())
            .accountNumber(request.getAccountNumber())
            .routingNumber(request.getRoutingNumber())
            .currency(request.getCurrency())
            .isPrimary(request.getIsPrimary())
            .isVerified(false)
            .status(PartyBankAccount.AccountStatus.ACTIVE)
            .build();
        
        account = bankAccountRepository.save(account);
        log.info("Added bank account {} to party {}", account.getLabel(), party.getPartyCode());
        
        return mapToBankAccountResponse(account);
    }

    @Transactional
    public void deleteBankAccount(UUID accountId) {
        bankAccountRepository.deleteById(accountId);
        log.info("Deleted bank account: {}", accountId);
    }

    @Transactional
    public BankAccountResponse verifyBankAccount(UUID accountId, String verifiedBy) {
        PartyBankAccount account = bankAccountRepository.findById(accountId)
            .orElseThrow(() -> new RuntimeException("Bank account not found: " + accountId));
        
        account.setIsVerified(true);
        account.setVerifiedAt(LocalDateTime.now());
        account.setVerifiedBy(verifiedBy);
        
        account = bankAccountRepository.save(account);
        log.info("Verified bank account: {}", accountId);
        
        return mapToBankAccountResponse(account);
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> getPartyDocuments(UUID partyId) {
        return documentRepository.findByPartyId(partyId).stream().map(this::mapToDocumentResponse).collect(Collectors.toList());
    }

    @Transactional
    public DocumentResponse uploadDocument(UUID partyId, UploadDocumentRequest request) {
        Party party = partyRepository.findById(partyId)
            .orElseThrow(() -> new RuntimeException("Party not found: " + partyId));
        
        PartyDocument document = PartyDocument.builder()
            .party(party)
            .documentType(DocumentType.valueOf(request.getDocumentType()))
            .category(DocumentCategory.valueOf(request.getCategory()))
            .name(request.getName())
            .documentNumber(request.getDocumentNumber())
            .issueDate(request.getIssueDate())
            .expiryDate(request.getExpiryDate())
            .issuingAuthority(request.getIssuingAuthority())
            .issuingCountry(request.getIssuingCountry())
            .verificationStatus(VerificationStatus.PENDING)
            .fileType("PDF")
            .build();
        
        document = documentRepository.save(document);
        log.info("Uploaded document {} for party {}", document.getName(), party.getPartyCode());
        
        return mapToDocumentResponse(document);
    }

    @Transactional
    public DocumentResponse verifyDocument(UUID documentId, VerifyDocumentRequest request) {
        PartyDocument document = documentRepository.findById(documentId)
            .orElseThrow(() -> new RuntimeException("Document not found: " + documentId));
        
        document.setVerificationStatus(VerificationStatus.valueOf(request.getStatus()));
        document.setVerifiedAt(LocalDateTime.now());
        
        if ("REJECTED".equals(request.getStatus())) {
            document.setRejectionReason(request.getRejectionReason());
        }
        
        document = documentRepository.save(document);
        log.info("Verified document {}: {}", documentId, request.getStatus());
        
        return mapToDocumentResponse(document);
    }

    @Transactional
    public void deleteDocument(UUID documentId) {
        documentRepository.deleteById(documentId);
        log.info("Deleted document: {}", documentId);
    }

    @Transactional
    public PartyResponse updateKycStatus(UUID partyId, UpdateKycRequest request) {
        Party party = partyRepository.findById(partyId)
            .orElseThrow(() -> new RuntimeException("Party not found: " + partyId));
        
        party.setKycStatus(KycStatus.valueOf(request.getKycStatus()));
        if (request.getKycExpiresAt() != null) {
            party.setKycExpiresAt(request.getKycExpiresAt());
        }
        if ("VERIFIED".equals(request.getKycStatus())) {
            party.setKycVerifiedAt(LocalDateTime.now());
            party.setOnboardedAt(LocalDateTime.now());
        }
        
        party = partyRepository.save(party);
        log.info("Updated KYC status for party {}: {}", party.getPartyCode(), request.getKycStatus());
        
        return mapToResponse(party);
    }

    @Transactional
    public PartyResponse updateRiskRating(UUID partyId, UpdateRiskRequest request) {
        Party party = partyRepository.findById(partyId)
            .orElseThrow(() -> new RuntimeException("Party not found: " + partyId));
        
        party.setRiskRating(RiskRating.valueOf(request.getRiskRating()));
        if (request.getRiskScore() != null) {
            party.setRiskScore(request.getRiskScore());
        }
        
        party = partyRepository.save(party);
        log.info("Updated risk rating for party {}: {}", party.getPartyCode(), request.getRiskRating());
        
        return mapToResponse(party);
    }

    @Transactional
    public ScreeningResponse runScreening(UUID partyId, ScreeningRequest request) {
        Party party = partyRepository.findById(partyId)
            .orElseThrow(() -> new RuntimeException("Party not found: " + partyId));
        
        throw new BusinessException("Not available: sanctions screening — no screening provider is connected, so "
            + party.getLegalName() + " cannot be marked clear");
    }

    @Transactional(readOnly = true)
    public PartyStatsResponse getStats(UUID corporateId) {
        return getExtendedStats(corporateId);
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private boolean hasFilters(PartySearchRequest request) {
        return (request.getQuery() != null && !request.getQuery().isEmpty()) ||
               request.getPartyType() != null ||
               request.getKycStatus() != null ||
               request.getRiskRating() != null ||
               request.getStatus() != null ||
               (request.getRole() != null && !request.getRole().isEmpty()) ||
               request.getOwningEntityId() != null ||
               Boolean.TRUE.equals(request.getPoboEligibleOnly()) ||
               Boolean.TRUE.equals(request.getIntercompanyOnly()) ||
               Boolean.TRUE.equals(request.getNettingEligibleOnly());
    }

    /**
     * Generates a unique party code using an atomic counter for thread-safety.
     * Format: PREFIX-XXXXX (e.g., PARTY-00001, EMP-00042, VEN-00123)
     * 
     * The counter uses modular arithmetic to keep codes reasonably short while
     * ensuring uniqueness within a practical range (100,000 unique codes per prefix).
     * Combined with retry logic in createParty(), this handles concurrent access safely.
     * 
     * @param partyType The type of party (COMPANY, INDIVIDUAL, etc.)
     * @param roles The roles assigned to the party (VENDOR, CUSTOMER, EMPLOYEE, etc.)
     * @return A unique party code string
     */
    private String generateUniquePartyCode(String partyType, Set<String> roles) {
        String prefix = determinePartyCodePrefix(roles);
        long counter = PARTY_CODE_COUNTER.incrementAndGet() % 100000;
        return prefix + "-" + String.format("%05d", counter);
    }
    
    /**
     * Determines the party code prefix based on roles.
     * Follows MVC pattern - this is business logic that maps roles to code prefixes.
     * 
     * Priority order (first match wins):
     * 1. EMPLOYEE -> EMP
     * 2. GOVERNMENT -> GOV
     * 3. FINANCIAL -> FIN
     * 4. VENDOR -> VEN
     * 5. CUSTOMER -> CUS
     * 6. Default -> PARTY
     * 
     * @param roles The set of roles assigned to the party
     * @return The appropriate prefix string for the party code
     */
    private String determinePartyCodePrefix(Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return "PARTY";
        }
        
        if (roles.contains("EMPLOYEE")) {
            return "EMP";
        } else if (roles.contains("GOVERNMENT")) {
            return "GOV";
        } else if (roles.contains("FINANCIAL")) {
            return "FIN";
        } else if (roles.contains("VENDOR")) {
            return "VEN";
        } else if (roles.contains("CUSTOMER")) {
            return "CUS";
        }
        return "PARTY";
    }
    
    /**
     * @deprecated Use {@link #generateUniquePartyCode(String, Set)} instead.
     * Kept for backward compatibility but no longer used in new code.
     */
    @Deprecated
    private String generatePartyCode(String partyType, Set<String> roles) {
        return generateUniquePartyCode(partyType, roles);
    }

    private PartyResponse mapToResponse(Party party) {
        List<String> platforms = party.getEcommercePlatforms() != null && !party.getEcommercePlatforms().isEmpty()
            ? Arrays.asList(party.getEcommercePlatforms().split(","))
            : Collections.emptyList();
        
        Set<String> roles = party.getRoles() != null && !party.getRoles().isEmpty()
            ? new HashSet<>(Arrays.asList(party.getRoles().split(",")))
            : Collections.emptySet();
        
        // Calculate IC metrics
        BigDecimal availableCredit = null;
        BigDecimal utilizationPercent = null;
        if (party.isGroupEntity() && party.getIcCreditLimit() != null && 
            party.getIcCreditLimit().compareTo(BigDecimal.ZERO) > 0) {
            availableCredit = party.getAvailableIcCredit();
            utilizationPercent = party.getIcCurrentExposure()
                .divide(party.getIcCreditLimit(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        }
        
        return PartyResponse.builder()
            .id(party.getId())
            .corporateId(party.getCorporateId())
            .partyCode(party.getPartyCode())
            .partyType(party.getPartyType().name())
            .legalName(party.getLegalName())
            .displayName(party.getDisplayName())
            .tradeName(party.getTradeName())
            .roles(roles)
            .taxId(party.getTaxId())
            .registrationNumber(party.getRegistrationNumber())
            .registrationCountry(party.getRegistrationCountry())
            .contactName(party.getContactName())
            .contactEmail(party.getContactEmail())
            .contactPhone(party.getContactPhone())
            .addressLine1(party.getAddressLine1())
            .addressLine2(party.getAddressLine2())
            .city(party.getCity())
            .state(party.getState())
            .postalCode(party.getPostalCode())
            .country(party.getCountry())
            .ecommerceEnabled(party.getEcommerceEnabled())
            .ecommercePlatforms(platforms)
            .employeeId(party.getEmployeeId())
            .department(party.getDepartment())
            .kycStatus(party.getKycStatus().name())
            .kycExpiresAt(party.getKycExpiresAt())
            .riskRating(party.getRiskRating().name())
            .riskScore(party.getRiskScore())
            .sanctionsStatus(party.getSanctionsStatus() != null ? party.getSanctionsStatus().name() : null)
            .pepStatus(party.getPepStatus())
            .adverseMediaStatus(party.getAdverseMediaStatus())
            .status(party.getStatus().name())
            .bankAccountsCount(party.getBankAccountsCount())
            .documentsCount(party.getDocumentsCount())
            .lastTransactionAt(party.getLastTransactionAt())
            .createdAt(party.getCreatedAt())
            .onboardedAt(party.getOnboardedAt())
            // Phase 1 fields
            .owningEntityId(party.getOwningEntityId())
            .owningEntityCode(party.getOwningEntityCode())
            .poboEligible(party.getPoboEligible())
            .poboDefaultPayerEntityId(party.getPoboDefaultPayerEntityId())
            .poboDefaultPayerEntityCode(party.getPoboDefaultPayerEntityCode())
            .isIntercompany(party.getIsIntercompany())
            .linkedLegalEntityId(party.getLinkedLegalEntityId())
            .linkedLegalEntityCode(party.getLinkedLegalEntityCode())
            .nettingEligible(party.getNettingEligible())
            .icSettlementMethod(party.getIcSettlementMethod() != null ? party.getIcSettlementMethod().name() : null)
            .icCreditLimit(party.getIcCreditLimit())
            .icCurrentExposure(party.getIcCurrentExposure())
            .icCurrency(party.getIcCurrency())
            .icAvailableCredit(availableCredit)
            .icCreditUtilizationPercent(utilizationPercent)
            .canReceivePoboPayment(party.canReceivePoboPayment())
            .canParticipateInNetting(party.canParticipateInNetting())
            .build();
    }

    private BankAccountResponse mapToBankAccountResponse(PartyBankAccount account) {
        return BankAccountResponse.builder()
            .id(account.getId())
            .label(account.getLabel())
            .holderName(account.getHolderName())
            .bankName(account.getBankName())
            .bankCode(account.getBankCode())
            .iban(account.getIban())
            .accountNumber(account.getAccountNumber())
            .routingNumber(account.getRoutingNumber())
            .currency(account.getCurrency())
            .isPrimary(account.getIsPrimary())
            .isVerified(account.getIsVerified())
            .verifiedAt(account.getVerifiedAt())
            .status(account.getStatus().name())
            .build();
    }

    private DocumentResponse mapToDocumentResponse(PartyDocument document) {
        return DocumentResponse.builder()
            .id(document.getId())
            .documentType(document.getDocumentType().name())
            .category(document.getCategory().name())
            .name(document.getName())
            .documentNumber(document.getDocumentNumber())
            .issueDate(document.getIssueDate())
            .expiryDate(document.getExpiryDate())
            .issuingAuthority(document.getIssuingAuthority())
            .verificationStatus(document.getVerificationStatus().name())
            .fileType(document.getFileType())
            .fileSize(document.getFileSize())
            .isExpired(document.isExpired())
            .build();
    }

    private PoboConfigResponse buildPoboConfig(Party party) {
        return PoboConfigResponse.builder()
            .poboEligible(party.getPoboEligible())
            .defaultPayerEntityId(party.getPoboDefaultPayerEntityId())
            .defaultPayerEntityCode(party.getPoboDefaultPayerEntityCode())
            .build();
    }

    private IntercompanyConfigResponse buildIntercompanyConfig(Party party) {
        IcCreditConfigResponse creditConfig = null;
        if (party.isGroupEntity() && party.getIcCreditLimit() != null) {
            BigDecimal available = party.getAvailableIcCredit();
            BigDecimal utilization = party.getIcCurrentExposure()
                .divide(party.getIcCreditLimit(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
            
            String creditStatus = "AVAILABLE";
            if (utilization.compareTo(BigDecimal.valueOf(100)) >= 0) {
                creditStatus = "EXCEEDED";
            } else if (utilization.compareTo(BigDecimal.valueOf(80)) >= 0) {
                creditStatus = "WARNING";
            }
            
            creditConfig = IcCreditConfigResponse.builder()
                .creditLimit(party.getIcCreditLimit())
                .currency(party.getIcCurrency())
                .currentExposure(party.getIcCurrentExposure())
                .availableCredit(available)
                .utilizationPercent(utilization)
                .creditStatus(creditStatus)
                .build();
        }
        
        return IntercompanyConfigResponse.builder()
            .isIntercompany(party.getIsIntercompany())
            .linkedLegalEntityId(party.getLinkedLegalEntityId())
            .linkedLegalEntityCode(party.getLinkedLegalEntityCode())
            .settlementMethod(party.getIcSettlementMethod() != null ? party.getIcSettlementMethod().name() : null)
            .nettingEligible(party.getNettingEligible())
            .creditConfig(creditConfig)
            .build();
    }

    /** Compliance as recorded: the party's own fields and the documents it has actually provided. */
    private ComplianceDetailResponse buildComplianceDetail(Party party, List<PartyDocument> documents) {
        long verified = documents.stream().filter(d -> d.getVerificationStatus() == VerificationStatus.VERIFIED).count();
        List<RequiredDocumentResponse> provided = documents.stream()
            .map(d -> RequiredDocumentResponse.builder()
                .documentType(d.getDocumentType().name())
                .name(d.getName())
                .isProvided(true)
                .status(d.getVerificationStatus().name())
                .build())
            .collect(Collectors.toList());

        return ComplianceDetailResponse.builder()
            .kycStatus(party.getKycStatus().name())
            .kycExpiresAt(party.getKycExpiresAt())
            .kycVerifiedAt(party.getKycVerifiedAt())
            .kycVerifiedBy(party.getKycVerifiedBy())
            .verificationProgress(documents.isEmpty() ? null : (int) (verified * 100 / documents.size()))
            .riskRating(party.getRiskRating().name())
            .riskScore(party.getRiskScore())
            .riskFactors(List.of())
            .sanctionsStatus(party.getSanctionsStatus() != null ? party.getSanctionsStatus().name() : null)
            .sanctionsLastChecked(party.getSanctionsLastChecked())
            .pepStatus(party.getPepStatus())
            .adverseMediaStatus(party.getAdverseMediaStatus())
            .requiredDocuments(provided)
            .build();
    }
}
