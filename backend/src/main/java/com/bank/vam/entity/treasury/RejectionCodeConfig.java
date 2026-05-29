package com.bank.vam.entity.treasury;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Maps ISO 20022 (and bilateral SWIFT) rejection reason codes to a
 * {@link SweepInstruction.RejectionCategory}. Seeded at startup; editable
 * at runtime if a bank's rail returns a code we haven't classified.
 *
 * Decision logic ({@code RejectionCodeRegistry}):
 *  - If the code matches → category drives auto-pause vs retry.
 *  - If no row matches → treat as RECOVERABLE by default (safer than pausing
 *    on an unknown code; surfaces in the dashboard for ops to triage).
 */
@Entity
@Table(name = "rejection_code_configs", indexes = {
        @Index(name = "idx_rejection_code", columnList = "code", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RejectionCodeConfig extends BaseEntity {

    /** ISO external code (e.g. AC04, MD07, AM04, MS03) or a synthetic local code (CUT_OFF_MISSED). */
    @Column(name = "code", nullable = false, unique = true, length = 30)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    private SweepInstruction.RejectionCategory category;

    @Column(name = "description", length = 200)
    private String description;
}
