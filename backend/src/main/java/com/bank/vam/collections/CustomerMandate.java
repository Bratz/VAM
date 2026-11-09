package com.bank.vam.collections;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A customer's authority for the corporate to collect from the customer's bank account by direct
 * debit. Not {@code ExternalMandate}: that one is the corporate's authority over its own external
 * accounts, used by sweeps. Scheme-neutral: {@code schemeCode} is a label (UAE_DDS, SEPA_CORE, ...).
 */
@Entity
@Table(name = "customer_mandates",
        uniqueConstraints = @UniqueConstraint(name = "uk_cust_mandate_ref", columnNames = {"corporate_id", "mandate_reference"}),
        indexes = @Index(name = "idx_cust_mandate_party", columnList = "customer_party_id"))
@Getter
@Setter
public class CustomerMandate extends BaseEntity {

    @Column(name = "corporate_id", nullable = false)
    private UUID corporateId;

    @Column(name = "customer_party_id", nullable = false)
    private UUID customerPartyId;

    @Column(name = "debtor_name", length = 140)
    private String debtorName;

    @Column(name = "debtor_iban", nullable = false, length = 34)
    private String debtorIban;

    @Column(name = "debtor_bic", length = 11)
    private String debtorBic;

    /** The corporate's account the collections are credited to. */
    @Column(name = "creditor_va_id", nullable = false)
    private UUID creditorVaId;

    @Column(name = "mandate_reference", nullable = false, length = 35)
    private String mandateReference;

    @Column(name = "scheme_code", nullable = false, length = 20)
    private String schemeCode;

    /** The debtor account's currency, which is what is collected. */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "mandate_type", nullable = false, length = 12)
    private Type mandateType = Type.RECURRING;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "signed_date")
    private LocalDate signedDate;

    @Column(name = "max_per_collection", precision = 19, scale = 2)
    private BigDecimal maxPerCollection;

    /** At most {@code frequencyCount} collections per {@code frequencyPeriod}; both null = no cap. */
    @Column(name = "frequency_count")
    private Integer frequencyCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency_period", length = 10)
    private Period frequencyPeriod;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private Status status = Status.ACTIVE;

    @Column(name = "status_reason", length = 200)
    private String statusReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 10)
    private Source source = Source.API;

    public enum Type { ONE_OFF, RECURRING }

    public enum Period { WEEK, MONTH, QUARTER, YEAR }

    /** PENDING: sent to the payer's bank (e-mandate), not yet accepted. USED: a one-off that has collected. */
    public enum Status { PENDING, ACTIVE, SUSPENDED, CANCELLED, USED }

    public enum Source { PORTAL, FILE, API, EMANDATE }
}
