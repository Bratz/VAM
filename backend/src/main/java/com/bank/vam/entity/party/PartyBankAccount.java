package com.bank.vam.entity.party;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "party_bank_accounts", indexes = {
    @Index(name = "idx_pba_party", columnList = "party_id"),
    @Index(name = "idx_pba_iban", columnList = "iban")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PartyBankAccount extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "party_id", nullable = false)
    private Party party;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "holder_name", nullable = false)
    private String holderName;

    @Column(name = "bank_name", nullable = false)
    private String bankName;

    @Column(name = "bank_code", length = 11)
    private String bankCode; // BIC/SWIFT

    @Column(name = "iban", length = 34)
    private String iban;

    @Column(name = "account_number", length = 34)
    private String accountNumber;

    @Column(name = "routing_number", length = 20)
    private String routingNumber;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "is_primary")
    @Builder.Default
    private Boolean isPrimary = false;

    @Column(name = "is_verified")
    @Builder.Default
    private Boolean isVerified = false;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "verified_by")
    private String verifiedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    @Builder.Default
    private AccountStatus status = AccountStatus.ACTIVE;

    public enum AccountStatus {
        ACTIVE, SUSPENDED, CLOSED
    }
}
