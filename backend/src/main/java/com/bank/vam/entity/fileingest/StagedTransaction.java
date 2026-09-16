package com.bank.vam.entity.fileingest;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per source row from an uploaded file — this IS the audit trail and
 * the per-row quarantine mechanism: a bad row here never blocks its siblings
 * from reaching PROCESSED.
 */
@Entity
@Table(name = "staged_transaction", indexes = {
        @Index(name = "idx_staged_transaction_job", columnList = "ingest_job_id"),
        @Index(name = "idx_staged_transaction_job_status", columnList = "ingest_job_id, status")
})
@Getter
@Setter
@NoArgsConstructor
public class StagedTransaction {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID ingestJobId;

    /** 1-based position of this row in the source file, for tracing a complaint back to "row 4,812." */
    @Column(nullable = false)
    private int sourceRowNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RowStatus status = RowStatus.STAGED;

    /** Populated only when status = QUARANTINED or FAILED. */
    @Column(length = 2000)
    private String reason;

    private BigDecimal amount;

    private String currency;

    /** VIBAN/account reference this row targets — kept here (not just in the source file) so a
     * support engineer can answer "what was row 4,812" without needing the original upload. */
    private String targetAccountReference;

    /** FK to whatever real entity got created once status = PROCESSED (a Transaction id, a Payable id, ...). */
    private UUID processedEntityId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
