package com.bank.vam.entity.fileingest;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * The transform-reuse cache. Owned (written) exclusively by the separate
 * agent worker's FormatSignatureService — this app only ever reads it, via
 * IngestJob.formatSignatureId, to notice a transform has become ready.
 */
@Entity
@Table(name = "format_signature", uniqueConstraints = @UniqueConstraint(columnNames = "signatureHash"))
@Getter
@Setter
@NoArgsConstructor
public class FormatSignature {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 64)
    private String signatureHash;

    @Column(nullable = false)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IngestDomain domain;

    /** Git commit SHA in the transform-handlers repo this signature resolves to. Null
     * until the agent worker's coding-agent/test-gate loop succeeds. */
    private String transformRef;

    /** Serialized FileStructureProfile (columns, delimiter, controlTotalColumn, notes) from the
     * agent worker's analysis — lets GeneratedTransformRunner independently re-verify a
     * cache-hit run's reconciliation against the raw file, instead of trusting the generated
     * transform's own reported counts. Null for signatures recorded before this existed. */
    @Column(columnDefinition = "TEXT")
    private String analysisProfileJson;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
