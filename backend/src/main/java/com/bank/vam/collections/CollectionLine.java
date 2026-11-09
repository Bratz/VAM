package com.bank.vam.collections;

import com.bank.vam.collections.CollectionRoutingService.Method;
import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** One invoice in a collection run: how it is collected and where it has got to. */
@Entity
@Table(name = "collection_lines", indexes = {
        @Index(name = "idx_collection_line_run", columnList = "run_id"),
        @Index(name = "idx_collection_line_receivable", columnList = "receivable_id"),
        @Index(name = "idx_collection_line_mandate", columnList = "mandate_id"),
        @Index(name = "idx_collection_line_status", columnList = "status")
})
@Getter
@Setter
public class CollectionLine extends BaseEntity {

    /** A collection already under way for the invoice: it must not be collected again. */
    public static final Set<Status> IN_FLIGHT = EnumSet.of(Status.SENT, Status.SETTLED);
    /** Collections a mandate has made or is making: what its frequency cap and one-off use count. */
    public static final Set<Status> USES_MANDATE = EnumSet.of(Status.SENT, Status.SETTLED, Status.AVAILABLE);

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "corporate_id", nullable = false)
    private UUID corporateId;

    @Column(name = "receivable_id", nullable = false)
    private UUID receivableId;

    @Column(name = "receivable_number", length = 50)
    private String receivableNumber;

    @Column(name = "customer_name", length = 200)
    private String customerName;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 15)
    private Method method;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status;

    /** Why it is not a direct debit, why it was left out, or why it has not settled. */
    @Column(name = "reason", length = 300)
    private String reason;

    @Column(name = "amount", precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", length = 3)
    private String currency;

    // ---- direct debit ----

    @Column(name = "mandate_id")
    private UUID mandateId;

    @Column(name = "mandate_reference", length = 35)
    private String mandateReference;

    @Column(name = "scheme_code", length = 20)
    private String schemeCode;

    /** FRST, RCUR or OOFF in the pain.008. */
    @Column(name = "sequence_type", length = 4)
    private String sequenceType;

    @Column(name = "collection_date")
    private LocalDate collectionDate;

    @Column(name = "end_to_end_id", length = 35)
    private String endToEndId;

    @Column(name = "creditor_va_id")
    private UUID creditorVaId;

    @Column(name = "debtor_name", length = 140)
    private String debtorName;

    @Column(name = "debtor_iban", length = 34)
    private String debtorIban;

    @Column(name = "debtor_bic", length = 11)
    private String debtorBic;

    // ---- settlement ----

    @Column(name = "settled_on")
    private LocalDate settledOn;

    /** What reached the account after the collection fee; this is what is held. */
    @Column(name = "credited_amount", precision = 19, scale = 2)
    private BigDecimal creditedAmount;

    @Column(name = "transaction_ref", length = 50)
    private String transactionRef;

    /** The account's currency and the gross credited in it, when it differs from the invoice's (converted on credit). */
    @Column(name = "credit_currency", length = 3)
    private String creditCurrency;

    @Column(name = "credit_gross", precision = 19, scale = 2)
    private BigDecimal creditGross;

    @Column(name = "fx_rate", precision = 19, scale = 8)
    private BigDecimal fxRate;

    @Column(name = "fx_deal_ref", length = 60)
    private String fxDealRef;

    @Column(name = "hold_until")
    private LocalDate holdUntil;

    @Column(name = "released_on")
    private LocalDate releasedOn;

    // ---- return / reject ----

    /** ISO 20022 reason code, e.g. AM04 (insufficient funds). */
    @Column(name = "return_code", length = 4)
    private String returnCode;

    @Column(name = "returned_on")
    private LocalDate returnedOn;

    /** A Request to Pay went to the customer after the return. */
    @Column(name = "rtp_after_return")
    private Boolean rtpAfterReturn;

    // ---- how the customer pays a Request to Pay: filled in when a run is read, not stored on the line ----

    /** The invoice's public "pay this invoice" token; the portal builds the link and QR from it. */
    @Transient
    private String paymentLinkToken;

    /** The invoice's virtual IBAN, to pay into. */
    @Transient
    private String payViban;

    /** The invoice's last pain.013 is kept and can be downloaded. */
    @Transient
    private Boolean requestToPayKept;

    /**
     * SENT: in the pain.008, awaiting its collection date. SETTLED: credited and held. AVAILABLE: the hold has
     * lifted. RTP_SENT: a Request to Pay went instead. EXCLUDED: not collected (see reason). FAILED: could not be
     * sent, or the invoice no longer needed collecting on the date. REJECTED: refused before it settled (pain.002),
     * no money moved. RETURNED: came back after it settled (pacs.004); the money was reversed and the invoice reopened.
     */
    public enum Status { SENT, SETTLED, AVAILABLE, RTP_SENT, EXCLUDED, FAILED, REJECTED, RETURNED }
}
