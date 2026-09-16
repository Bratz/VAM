package com.bank.vam.fileingest.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * The transform-reuse cache (design doc decision 4): the first time a
 * customer+domain+column-shape combination is seen, the coding agent builds
 * and tests a transform; this row remembers it so every later upload of the
 * same shape skips the agent entirely (build order step 5).
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

    /** SHA-256(customerId | domain | normalized-column-headers | delimiter) — see the design doc. */
    @Column(nullable = false, length = 64)
    private String signatureHash;

    @Column(nullable = false)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IngestDomain domain;

    /** Git commit SHA in the transform-handlers repo this signature resolves to. Null until step 6's test gate passes. */
    private String transformRef;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
