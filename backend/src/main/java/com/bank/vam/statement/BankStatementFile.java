package com.bank.vam.statement;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** One statement file taken in (spec R2a): where it came from and what became of its statements. */
@Entity
@Table(name = "bank_statement_files", indexes = @Index(name = "idx_bank_statement_file_received", columnList = "received_at"))
@Getter
@Setter
public class BankStatementFile extends BaseEntity {

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 10)
    private Channel channel;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    /** PROCESSED: read, each statement has its own outcome; FAILED: nothing in it could be read ({@code error}). */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status;

    @Column(name = "applied_count", nullable = false)
    private int appliedCount;

    @Column(name = "duplicate_count", nullable = false)
    private int duplicateCount;

    @Column(name = "refused_count", nullable = false)
    private int refusedCount;

    @Column(name = "error", length = 1000)
    private String error;

    public enum Channel { INBOX, API }

    public enum Status { PROCESSED, FAILED }
}
