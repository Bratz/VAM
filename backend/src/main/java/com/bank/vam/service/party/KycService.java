package com.bank.vam.service.party;

import com.bank.vam.entity.party.Party;
import com.bank.vam.entity.party.Party.KycStatus;
import com.bank.vam.entity.party.Party.RiskRating;
import com.bank.vam.entity.party.PartyDocument;
import com.bank.vam.entity.party.PartyDocument.VerificationStatus;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.party.PartyDocumentRepository;
import com.bank.vam.repository.party.PartyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * KYC review over the Party model, which already carries every piece of state
 * this needs: kycStatus, kycExpiresAt/VerifiedAt/VerifiedBy, riskRating,
 * riskScore, sanctionsStatus and a set of PartyDocuments with their own
 * verification status.
 *
 * Nothing here invents a case file. A "KYC case" is a party whose kycStatus is
 * still open, and a decision writes that status back to the party row.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KycService {

    /** How long an approval is good for when the caller does not say. */
    private static final int DEFAULT_VALIDITY_MONTHS = 12;

    private final PartyRepository partyRepository;
    private final PartyDocumentRepository documentRepository;

    /** Statuses that still need a compliance decision. */
    private static final List<KycStatus> OPEN_STATUSES =
        List.of(KycStatus.PENDING, KycStatus.IN_PROGRESS, KycStatus.EXPIRED);

    @Transactional(readOnly = true)
    public List<KycCaseResponse> getPendingReview(UUID corporateId) {
        return partyRepository.findByCorporateId(corporateId).stream()
            .filter(p -> OPEN_STATUSES.contains(p.getKycStatus()))
            .sorted(Comparator.comparing(Party::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())))
            .map(this::toCase)
            .toList();
    }

    @Transactional(readOnly = true)
    public KycDetailResponse getKycDetail(UUID partyId) {
        Party party = party(partyId);
        List<PartyDocument> docs = documentRepository.findByPartyId(partyId);
        return KycDetailResponse.builder()
            .summary(toCase(party))
            .partyType(party.getPartyType() != null ? party.getPartyType().name() : null)
            .registrationNumber(party.getRegistrationNumber())
            .taxId(party.getTaxId())
            .country(party.getCountry())
            .registrationCountry(party.getRegistrationCountry())
            .status(party.getStatus() != null ? party.getStatus().name() : null)
            .sanctionsStatus(party.getSanctionsStatus() != null ? party.getSanctionsStatus().name() : null)
            .sanctionsLastChecked(party.getSanctionsLastChecked())
            .kycVerifiedAt(party.getKycVerifiedAt())
            .kycVerifiedBy(party.getKycVerifiedBy())
            .documents(docs.stream().map(this::toDocument).toList())
            .build();
    }

    @Transactional
    public KycCaseResponse approve(UUID partyId, KycDecisionRequest request) {
        Party party = party(partyId);
        if (party.getKycStatus() == KycStatus.VERIFIED) {
            throw new BusinessException("KYC is already verified for " + party.getLegalName());
        }
        if (party.getSanctionsStatus() == Party.SanctionsStatus.CONFIRMED_MATCH) {
            throw new BusinessException("Cannot verify a party with a confirmed sanctions match");
        }

        party.setKycStatus(KycStatus.VERIFIED);
        party.setKycVerifiedAt(LocalDateTime.now());
        party.setKycVerifiedBy(request.getDecidedBy());
        party.setKycExpiresAt(request.getValidUntil() != null
            ? request.getValidUntil()
            : LocalDate.now().plusMonths(DEFAULT_VALIDITY_MONTHS));
        party = partyRepository.save(party);

        log.info("KYC verified for party {} by {} until {}",
            party.getPartyCode(), request.getDecidedBy(), party.getKycExpiresAt());
        return toCase(party);
    }

    @Transactional
    public KycCaseResponse reject(UUID partyId, KycDecisionRequest request) {
        Party party = party(partyId);
        if (request.getReason() == null || request.getReason().isBlank()) {
            throw new BusinessException("A rejection reason is required");
        }

        party.setKycStatus(KycStatus.REJECTED);
        party.setKycVerifiedAt(null);
        party.setKycVerifiedBy(request.getDecidedBy());
        party.setKycExpiresAt(null);
        party = partyRepository.save(party);

        log.info("KYC rejected for party {} by {}: {}",
            party.getPartyCode(), request.getDecidedBy(), request.getReason());
        return toCase(party);
    }

    /**
     * Move a case into review and report what is actually missing: the document
     * types the party has not uploaded, plus any it has uploaded that are still
     * unverified or expired. There is no wish-list field on the model, so the
     * gap is computed from the documents rather than recorded as free text.
     */
    @Transactional
    public RequestInfoResponse requestAdditionalInfo(UUID partyId, RequestInfoRequest request) {
        Party party = party(partyId);
        List<PartyDocument> docs = documentRepository.findByPartyId(partyId);

        List<String> held = docs.stream()
            .map(d -> d.getDocumentType().name())
            .toList();
        List<String> missing = request.getRequiredDocuments() == null ? List.of()
            : request.getRequiredDocuments().stream()
                .filter(t -> !held.contains(t))
                .toList();
        List<String> unverified = docs.stream()
            .filter(d -> d.getVerificationStatus() != VerificationStatus.VERIFIED)
            .map(d -> d.getDocumentType().name())
            .toList();

        party.setKycStatus(KycStatus.IN_PROGRESS);
        partyRepository.save(party);

        log.info("KYC moved to in-progress for party {} by {}; missing={}, unverified={}",
            party.getPartyCode(), request.getRequestedBy(), missing, unverified);

        return RequestInfoResponse.builder()
            .partyId(partyId)
            .kycStatus(party.getKycStatus().name())
            .missingDocuments(missing)
            .unverifiedDocuments(unverified)
            .requestedBy(request.getRequestedBy())
            .requestedAt(LocalDateTime.now())
            .build();
    }

    @Transactional(readOnly = true)
    public KycStatsResponse getStats(UUID corporateId) {
        List<Party> parties = partyRepository.findByCorporateId(corporateId);
        LocalDate horizon = LocalDate.now().plusDays(30);

        return KycStatsResponse.builder()
            .totalParties(parties.size())
            .pendingReview(parties.stream().filter(p -> OPEN_STATUSES.contains(p.getKycStatus())).count())
            .verified(count(parties, KycStatus.VERIFIED))
            .rejected(count(parties, KycStatus.REJECTED))
            .exempted(count(parties, KycStatus.EXEMPTED))
            .expired(count(parties, KycStatus.EXPIRED))
            .inProgress(count(parties, KycStatus.IN_PROGRESS))
            .highRisk(parties.stream().filter(p -> p.getRiskRating() == RiskRating.HIGH
                || p.getRiskRating() == RiskRating.PROHIBITED).count())
            .sanctionsAlerts(parties.stream().filter(p -> p.getSanctionsStatus() != null
                && p.getSanctionsStatus() != Party.SanctionsStatus.CLEAR).count())
            .expiringWithin30Days(parties.stream().filter(p -> p.getKycExpiresAt() != null
                && !p.getKycExpiresAt().isAfter(horizon)
                && p.getKycStatus() == KycStatus.VERIFIED).count())
            .build();
    }

    @Transactional(readOnly = true)
    public List<KycCaseResponse> getExpiring(UUID corporateId, int daysAhead) {
        return partyRepository.findKycExpiringSoon(corporateId, LocalDate.now().plusDays(daysAhead))
            .stream()
            .sorted(Comparator.comparing(Party::getKycExpiresAt,
                Comparator.nullsLast(Comparator.naturalOrder())))
            .map(this::toCase)
            .toList();
    }

    // ------------------------------------------------------------------
    // mapping
    // ------------------------------------------------------------------

    private Party party(UUID partyId) {
        return partyRepository.findById(partyId)
            .orElseThrow(() -> new ResourceNotFoundException("Party not found: " + partyId));
    }

    private static long count(List<Party> parties, KycStatus status) {
        return parties.stream().filter(p -> p.getKycStatus() == status).count();
    }

    private KycCaseResponse toCase(Party party) {
        long total = documentRepository.countByPartyId(party.getId());
        long verified = documentRepository.countByPartyIdAndVerificationStatus(
            party.getId(), VerificationStatus.VERIFIED);
        return KycCaseResponse.builder()
            .partyId(party.getId())
            .partyCode(party.getPartyCode())
            .legalName(party.getLegalName())
            .displayName(party.getDisplayName())
            .kycStatus(party.getKycStatus() != null ? party.getKycStatus().name() : null)
            .riskRating(party.getRiskRating() != null ? party.getRiskRating().name() : null)
            .riskScore(party.getRiskScore())
            .kycExpiresAt(party.getKycExpiresAt())
            .documentsSubmitted(total)
            .documentsVerified(verified)
            .submittedAt(party.getCreatedAt())
            .build();
    }

    private KycDocumentResponse toDocument(PartyDocument d) {
        return KycDocumentResponse.builder()
            .id(d.getId())
            .documentType(d.getDocumentType() != null ? d.getDocumentType().name() : null)
            .name(d.getName())
            .fileName(d.getFileName())
            .verificationStatus(d.getVerificationStatus() != null ? d.getVerificationStatus().name() : null)
            .verifiedAt(d.getVerifiedAt())
            .verifiedBy(d.getVerifiedBy())
            .expiryDate(d.getExpiryDate())
            .rejectionReason(d.getRejectionReason())
            .build();
    }

    // ------------------------------------------------------------------
    // DTOs
    // ------------------------------------------------------------------

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class KycCaseResponse {
        private UUID partyId;
        private String partyCode;
        private String legalName;
        private String displayName;
        private String kycStatus;
        private String riskRating;
        private Integer riskScore;
        private LocalDate kycExpiresAt;
        private long documentsSubmitted;
        private long documentsVerified;
        private LocalDateTime submittedAt;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class KycDetailResponse {
        private KycCaseResponse summary;
        private String partyType;
        private String registrationNumber;
        private String taxId;
        private String country;
        private String registrationCountry;
        private String status;
        private String sanctionsStatus;
        private LocalDateTime sanctionsLastChecked;
        private LocalDateTime kycVerifiedAt;
        private String kycVerifiedBy;
        private List<KycDocumentResponse> documents;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class KycDocumentResponse {
        private UUID id;
        private String documentType;
        private String name;
        private String fileName;
        private String verificationStatus;
        private LocalDateTime verifiedAt;
        private String verifiedBy;
        private LocalDate expiryDate;
        private String rejectionReason;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class KycStatsResponse {
        private long totalParties;
        private long pendingReview;
        private long inProgress;
        private long verified;
        private long rejected;
        private long exempted;
        private long expired;
        private long highRisk;
        private long sanctionsAlerts;
        private long expiringWithin30Days;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class KycDecisionRequest {
        private String decidedBy;
        private String reason;
        private String comments;
        /** Optional; defaults to 12 months from approval. */
        private LocalDate validUntil;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class RequestInfoRequest {
        private List<String> requiredDocuments;
        private String requestedBy;
        private String message;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class RequestInfoResponse {
        private UUID partyId;
        private String kycStatus;
        /** Requested types the party has not uploaded at all. */
        private List<String> missingDocuments;
        /** Uploaded but not yet verified. */
        private List<String> unverifiedDocuments;
        private String requestedBy;
        private LocalDateTime requestedAt;
    }
}
