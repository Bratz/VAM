package com.bank.vam.entity.party;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "party_documents", indexes = {
    @Index(name = "idx_pdoc_party", columnList = "party_id"),
    @Index(name = "idx_pdoc_type", columnList = "document_type"),
    @Index(name = "idx_pdoc_status", columnList = "verification_status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PartyDocument extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "party_id", nullable = false)
    private Party party;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 30)
    private DocumentType documentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private DocumentCategory category;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "document_number", length = 50)
    private String documentNumber;

    @Column(name = "issue_date")
    private LocalDate issueDate;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "issuing_authority")
    private String issuingAuthority;

    @Column(name = "issuing_country", length = 2)
    private String issuingCountry;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 20)
    @Builder.Default
    private VerificationStatus verificationStatus = VerificationStatus.PENDING;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "verified_by")
    private String verifiedBy;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    // File storage
    @Column(name = "file_name")
    private String fileName;

    @Column(name = "file_type", length = 20)
    private String fileType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "file_path")
    private String filePath;

    @Column(name = "file_hash")
    private String fileHash;

    public enum DocumentType {
        TRADE_LICENSE,
        TAX_CERTIFICATE,
        VAT_CERTIFICATE,
        INCORPORATION_CERT,
        MEMORANDUM_ARTICLES,
        BANK_STATEMENT,
        POWER_OF_ATTORNEY,
        BOARD_RESOLUTION,
        SHAREHOLDER_REGISTER,
        PASSPORT,
        NATIONAL_ID,
        VISA,
        EMIRATES_ID,
        UTILITY_BILL,
        AUDIT_REPORT,
        FINANCIAL_STATEMENT,
        OTHER
    }

    public enum DocumentCategory {
        KYC, LEGAL, FINANCIAL, IDENTITY, OTHER
    }

    public enum VerificationStatus {
        PENDING, VERIFIED, REJECTED, EXPIRED
    }

    public boolean isExpired() {
        return expiryDate != null && expiryDate.isBefore(LocalDate.now());
    }
}
