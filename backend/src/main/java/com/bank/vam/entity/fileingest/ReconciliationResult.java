package com.bank.vam.entity.fileingest;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The control-total check: compares a total extracted from the source file
 * against what the transform actually produced, before anything is allowed
 * to leave STAGED for PROCESSING.
 */
@Entity
@Table(name = "reconciliation_result", indexes = {
        @Index(name = "idx_reconciliation_result_job", columnList = "ingest_job_id")
})
@Getter
@Setter
@NoArgsConstructor
public class ReconciliationResult {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID ingestJobId;

    @Column(nullable = false)
    private int sourceRowCount;

    @Column(nullable = false)
    private int transformedRowCount;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal sourceControlTotal;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal transformedControlTotal;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal delta;

    @Column(nullable = false)
    private boolean withinTolerance;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
