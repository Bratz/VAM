package com.bank.vam.entity.receivables;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A subsidiary enrolled for collection on its behalf (COBO) in one currency: its invoices in that currency are
 * collected by the treasury centre into {@code collectionVaId} and credited to the subsidiary in-house
 * (spec docs/specs/cobo-in-house-bank.md, R1-R3, D1/D2). Effective from {@code effectiveFrom} (inclusive) until
 * {@code effectiveTo} (exclusive; null = open).
 */
@Entity
@Table(name = "cobo_enrolment", indexes = {
    @Index(name = "idx_cobo_enrolment_corporate", columnList = "corporate_id"),
    @Index(name = "idx_cobo_enrolment_subsidiary", columnList = "subsidiary_entity_id, currency")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CoboEnrolment extends BaseEntity {

    @Column(name = "corporate_id", nullable = false)
    private UUID corporateId;

    @Column(name = "subsidiary_entity_id", nullable = false)
    private UUID subsidiaryEntityId;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "collector_entity_id", nullable = false)
    private UUID collectorEntityId;

    @Column(name = "collection_va_id", nullable = false)
    private UUID collectionVaId;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    public boolean isActiveOn(LocalDate day) {
        return !effectiveFrom.isAfter(day) && (effectiveTo == null || day.isBefore(effectiveTo));
    }

    /** Not ended as of {@code day}: active now or starting later. */
    public boolean isOpenOn(LocalDate day) {
        return effectiveTo == null || day.isBefore(effectiveTo);
    }
}
