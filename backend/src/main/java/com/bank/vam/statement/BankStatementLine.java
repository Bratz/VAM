package com.bank.vam.statement;

import com.bank.vam.entity.BaseEntity;
import com.bank.vam.statement.parse.ParsedLine.CreditDebit;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One statement line (R11). Amount positive, {@code creditDebit} the effect on the account. Intraday lines are
 * PROVISIONAL until the end-of-day statement for their day arrives, which makes them SUPERSEDED (R9a).
 */
@Entity
@Table(name = "bank_statement_lines", indexes = {
        @Index(name = "idx_bank_statement_line_statement", columnList = "bank_statement_id"),
        @Index(name = "idx_bank_statement_line_account_status", columnList = "physical_account_id, status")
})
@Getter
@Setter
public class BankStatementLine extends BaseEntity {

    @Column(name = "bank_statement_id", nullable = false)
    private UUID bankStatementId;

    @Column(name = "physical_account_id", nullable = false)
    private UUID physicalAccountId;

    @Column(name = "value_date")
    private LocalDate valueDate;

    @Column(name = "booking_date")
    private LocalDate bookingDate;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "credit_debit", nullable = false, length = 1)
    private CreditDebit creditDebit;

    @Column(name = "reversal", nullable = false)
    private boolean reversal;

    @Column(name = "bank_reference", length = 35)
    private String bankReference;

    @Column(name = "end_to_end_id", length = 35)
    private String endToEndId;

    @Column(name = "customer_reference", length = 35)
    private String customerReference;

    @Column(name = "remittance_text", length = 2000)
    private String remittanceText;

    @Column(name = "transaction_code", length = 35)
    private String transactionCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private Status status;

    public enum Status { FINAL, PROVISIONAL, SUPERSEDED }
}
