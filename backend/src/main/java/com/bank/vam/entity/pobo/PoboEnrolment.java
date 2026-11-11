package com.bank.vam.entity.pobo;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A subsidiary enrolled for payment on its behalf (POBO) in one currency: its payables in that currency are paid by
 * the treasury centre from {@code paymentVaId} and booked against the subsidiary's IHB current account
 * (spec docs/specs/pobo-in-house-bank.md R1-R2, D1/D2). Effective from {@code effectiveFrom} (inclusive) until
 * {@code effectiveTo} (exclusive; null = open).
 */
@Entity
@Table(name = "pobo_enrolment", indexes = {
    @Index(name = "idx_pobo_enrolment_corporate", columnList = "corporate_id"),
    @Index(name = "idx_pobo_enrolment_subsidiary", columnList = "subsidiary_entity_id, currency")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PoboEnrolment extends BaseEntity {

    @Column(name = "corporate_id", nullable = false)
    private UUID corporateId;

    @Column(name = "subsidiary_entity_id", nullable = false)
    private UUID subsidiaryEntityId;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "payer_entity_id", nullable = false)
    private UUID payerEntityId;

    @Column(name = "payment_va_id", nullable = false)
    private UUID paymentVaId;

    /** The subsidiary's IHB current account it settles on (D2 of docs/specs/ihb-fx.md); cross-currency when its currency
     * differs from {@code currency}. Null: the subsidiary's IHB current account in {@code currency}, as before D2. */
    @Column(name = "ihb_account_id")
    private UUID ihbAccountId;

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
