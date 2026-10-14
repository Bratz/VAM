package com.bank.vam.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Which route a corporate chose for one payment, and the quote it chose from.
 *
 * <p>The evidence, not the decision. uc05 decides; this records what it offered, what was picked and
 * what the world looked like at that moment -- the route, its labels, the charge and arrival quoted,
 * the on-time estimate, whether the learner considered itself {@code proven} on it, the route sheet
 * version and whether the corridor was one the learner was built with. Without that, "why did this
 * payment go by correspondent" has no answer a month later, because uc05 keeps nothing: a quote is a
 * read at one moment and it saves nothing.
 *
 * <p>Its own table rather than columns on {@code va_movements}: only a minority of payments are
 * routed, and ten mostly-null columns on the highest-volume table in the schema is a worse trade than
 * one narrow table joined when asked for.
 *
 * <p>The confirm-time fields are the second half. A quote does not know what the desk has already
 * sent that day, so the charge and arrival are re-read at confirm; when they moved, both the quoted
 * and the confirmed figures are kept, because "it cost more than you were shown" is a question that
 * gets asked.
 */
@Entity
@Table(name = "payment_route_selections")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentRouteSelection extends BaseEntity {

    /** The payment this route was chosen for. Set once the payment is actually executed. */
    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "from_va_id")
    private UUID fromVaId;

    // ---------------------------------------------------------------- the choice

    /** uc05's route id, e.g. {@code rtgs_usd}. The stable identifier; the name is for people. */
    @Column(name = "route", nullable = false, length = 64)
    private String route;

    @Column(name = "route_name", length = 128)
    private String routeName;

    /** uc05's route family: rtgs, instant, batch, correspondent, operator, card_push, token, stablecoin. */
    @Column(name = "route_kind", length = 32)
    private String routeKind;

    /** The labels it carried when offered (cheapest / fastest / surest), comma-joined as shown. */
    @Column(name = "labels", length = 128)
    private String labels;

    // ----------------------------------------------------------------- the ask

    @Column(name = "currency_code", length = 3)
    private String currencyCode;

    @Column(name = "amount", precision = 19, scale = 4)
    private BigDecimal amount;

    /** Alpha-2 beneficiary country. Null means the payment was quoted as domestic. */
    @Column(name = "destination", length = 2)
    private String destination;

    /** The deadline the corporate gave, which is what "on time" was measured against. */
    @Column(name = "by_when")
    private LocalDateTime byWhen;

    // --------------------------------------------------------------- the quote

    @Column(name = "quoted_charge", precision = 19, scale = 4)
    private BigDecimal quotedCharge;

    @Column(name = "quoted_arrival")
    private LocalDateTime quotedArrival;

    @Column(name = "quoted_on_time", precision = 6, scale = 4)
    private BigDecimal quotedOnTime;

    /** The learner's own confidence on this route. False means the estimate is weakly supported. */
    @Column(name = "proven")
    private Boolean proven;

    @Column(name = "sheet_version")
    private Integer sheetVersion;

    /** Null for a domestic payment; false when the learner had never seen this corridor. */
    @Column(name = "destination_known")
    private Boolean destinationKnown;

    @Column(name = "quoted_at")
    private LocalDateTime quotedAt;

    // ------------------------------------------------------- the re-quote at confirm

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "confirmed_charge", precision = 19, scale = 4)
    private BigDecimal confirmedCharge;

    @Column(name = "confirmed_arrival")
    private LocalDateTime confirmedArrival;

    /** What moved between the quote and the confirm, when anything did. */
    @Column(name = "requote_note", length = 512)
    private String requoteNote;
}
