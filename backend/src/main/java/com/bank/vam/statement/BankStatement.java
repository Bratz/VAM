package com.bank.vam.statement;

import com.bank.vam.entity.BaseEntity;
import com.bank.vam.statement.parse.ParsedStatement.Format;
import com.bank.vam.statement.parse.ParsedStatement.Kind;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One statement or intraday report from a file (spec §4, R3–R10). Balances are signed (debit negative). A REFUSED
 * statement may have no account (unknown) and, when it could not be read, no format or balances either.
 */
@Entity
@Table(name = "bank_statements", indexes = {
        @Index(name = "idx_bank_statement_file", columnList = "file_id"),
        @Index(name = "idx_bank_statement_account_kind_date", columnList = "physical_account_id, kind, statement_date"),
        @Index(name = "idx_bank_statement_duplicate_key", columnList = "duplicate_key")
})
@Getter
@Setter
public class BankStatement extends BaseEntity {

    @Column(name = "file_id", nullable = false)
    private UUID fileId;

    @Column(name = "physical_account_id")
    private UUID physicalAccountId;

    @Column(name = "shadow_va_id")
    private UUID shadowVaId;

    @Column(name = "corporate_id")
    private UUID corporateId;

    /** The account as the statement names it (kept for refused statements). */
    @Column(name = "iban", length = 34)
    private String iban;

    @Column(name = "account_number", length = 50)
    private String accountNumber;

    @Column(name = "bic", length = 11)
    private String bic;

    @Enumerated(EnumType.STRING)
    @Column(name = "format", length = 10)
    private Format format;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 12)
    private Kind kind;

    /** The bank's statement id (MT :20:, camt Stmt/Id or Rpt/Id). */
    @Column(name = "statement_id", length = 35)
    private String statementId;

    @Column(name = "sequence_number")
    private Long sequenceNumber;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "statement_date")
    private LocalDate statementDate;

    @Column(name = "reported_at")
    private LocalDateTime reportedAt;

    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "opening_booked", precision = 19, scale = 4)
    private BigDecimal openingBooked;

    @Column(name = "closing_booked", precision = 19, scale = 4)
    private BigDecimal closingBooked;

    @Column(name = "closing_available", precision = 19, scale = 4)
    private BigDecimal closingAvailable;

    @Column(name = "interim_booked", precision = 19, scale = 4)
    private BigDecimal interimBooked;

    @Column(name = "interim_available", precision = 19, scale = 4)
    private BigDecimal interimAvailable;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status;

    /** Why it was refused or is a duplicate. */
    @Column(name = "reason", length = 500)
    private String reason;

    /** Sequence number skips one or more after the previous applied statement of the same kind (R6). */
    @Column(name = "gap", nullable = false)
    private boolean gap;

    /** Opening ≠ the previous applied end-of-day closing booked (R8, continuity); the only thing that raises it. */
    @Column(name = "break_flag", nullable = false)
    private boolean breakFlag;

    /**
     * Informational: closing booked − the shadow's bank balance just before applying (R9); null when nothing to compare
     * or zero. On an external account this is mostly the bank's own day movements, so it does not raise the break flag.
     */
    @Column(name = "difference_to_platform", precision = 19, scale = 4)
    private BigDecimal differenceToPlatform;

    /** Dated before the latest applied end-of-day statement: balance left alone (R7). */
    @Column(name = "older", nullable = false)
    private boolean older;

    /** The account is fed by core banking: stored, balance left to core banking. */
    @Column(name = "cbs_fed", nullable = false)
    private boolean cbsFed;

    /** Account + kind + statement id + sequence/page + date + closing booked (intraday: reported at); R5. */
    @Column(name = "duplicate_key", length = 300)
    private String duplicateKey;

    public enum Status { APPLIED, DUPLICATE, REFUSED }
}
