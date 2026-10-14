package com.bank.vam.service;

import com.bank.vam.client.PaymentRoutingClient;
import com.bank.vam.client.PaymentRoutingClient.Ask;
import com.bank.vam.client.PaymentRoutingClient.Option;
import com.bank.vam.client.PaymentRoutingClient.Quote;
import com.bank.vam.config.PaymentRoutingProperties;
import com.bank.vam.dto.PaymentRoutingDto.OptionsRequest;
import com.bank.vam.dto.PaymentRoutingDto.OptionsResponse;
import com.bank.vam.dto.PaymentRoutingDto.SelectRequest;
import com.bank.vam.dto.PaymentRoutingDto.SelectResponse;
import com.bank.vam.entity.PaymentRouteSelection;
import com.bank.vam.repository.PaymentRouteSelectionRepository;
import com.bank.vam.service.audit.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Offers route options for a payment, and holds the corporate to one.
 *
 * <p>VAM does not decide here and does not second-guess. uc05 owns eligibility, limits, cut-offs and
 * the estimates; this maps a VAM payment intent onto its question and records the answer. In
 * particular there is no local cut-off check: the route sheet already carries each route's open and
 * cut-off times, and a re-implementation of a rule that lives somewhere else is how the settlement
 * resolver came to disagree with itself.
 *
 * <p>The one piece of real logic is {@link #select}: a quote is a read at one moment, and the uc05
 * author is explicit that it does not know what the desk has already sent that day. So confirming
 * re-asks, and refuses when the answer has changed enough to matter -- the route withdrawn, the
 * charge up beyond tolerance, the arrival later beyond tolerance, or an arrival that now misses the
 * deadline altogether. Better news is never a reason to interrupt someone.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRoutingService {

    private final PaymentRoutingClient client;
    private final PaymentRoutingProperties props;
    private final PaymentRouteSelectionRepository selections;
    private final AuditLogService audit;

    private static final Set<String> CHARGE_BEARERS = Set.of("OUR", "SHA", "BEN");

    // ------------------------------------------------------------------ offering

    public OptionsResponse options(OptionsRequest request) {
        if (!props.isEnabled()) {
            return OptionsResponse.notQuotable("Routing is not configured in this environment.");
        }
        if (!props.quotable(request.currency())) {
            return OptionsResponse.notQuotable(request.currency()
                    + " is not a currency the routing service quotes. It quotes "
                    + String.join(", ", props.getCurrencies()) + ".");
        }
        if (request.byWhen() == null || !request.byWhen().isAfter(LocalDateTime.now())) {
            return OptionsResponse.notQuotable("Give a deadline in the future to see route options.");
        }
        Quote quote = client.quote(ask(request));
        audit.record("PAYMENT_ROUTE_QUOTED", "VirtualAccount", request.fromVaId(),
                summaryOf(request, quote),
                Map.of("currency", String.valueOf(request.currency()),
                        "amount", String.valueOf(request.amount()),
                        "destination", String.valueOf(request.beneficiaryCountry()),
                        "options", quote != null && quote.options() != null
                                ? quote.options().stream().map(Option::route).toList() : List.of(),
                        "sheetVersion", String.valueOf(quote != null ? quote.sheetVersion() : null)));
        return OptionsResponse.of(quote);
    }

    // ----------------------------------------------------------------- confirming

    @Transactional
    public SelectResponse select(SelectRequest request) {
        OptionsRequest ask = request.ask();
        Quote fresh = client.quote(ask(ask));
        Option chosen = fresh != null ? fresh.option(request.route()) : null;

        if (chosen == null) {
            return refuse(ask, request, fresh,
                    "The route you chose is no longer being offered. Choose from the current options.");
        }

        List<String> moved = drift(request, chosen, ask.byWhen());
        if (!moved.isEmpty()) {
            return refuse(ask, request, fresh, String.join(" ", moved));
        }

        PaymentRouteSelection saved = selections.save(PaymentRouteSelection.builder()
                .fromVaId(ask.fromVaId())
                .route(chosen.route())
                .routeName(chosen.name())
                .routeKind(chosen.kind())
                .labels(chosen.labels() == null ? null : String.join(",", chosen.labels()))
                .currencyCode(ask.currency())
                .amount(ask.amount())
                .destination(blankToNull(ask.beneficiaryCountry()))
                .byWhen(ask.byWhen())
                .quotedCharge(request.quotedCharge())
                .quotedArrival(request.quotedArrival())
                .quotedOnTime(chosen.onTime() == null ? null
                        : BigDecimal.valueOf(chosen.onTime()).setScale(4, RoundingMode.HALF_UP))
                .proven(chosen.proven())
                .sheetVersion(fresh.sheetVersion())
                .destinationKnown(fresh.destinationKnown())
                .quotedAt(fresh.asked() != null ? fresh.asked().at() : LocalDateTime.now())
                .confirmedAt(LocalDateTime.now())
                .confirmedCharge(chosen.charge())
                .confirmedArrival(chosen.arrival())
                .requoteNote(betterNews(request, chosen))
                .build());

        audit.record("PAYMENT_ROUTE_SELECTED", "PaymentRouteSelection", saved.getId(),
                "Route " + chosen.route() + " (" + chosen.kind() + ") chosen for "
                        + ask.amount() + " " + ask.currency(),
                Map.of("route", chosen.route(),
                        "kind", String.valueOf(chosen.kind()),
                        "labels", chosen.labels() == null ? List.of() : chosen.labels(),
                        "confirmedCharge", String.valueOf(chosen.charge()),
                        "confirmedArrival", String.valueOf(chosen.arrival()),
                        "proven", String.valueOf(chosen.proven()),
                        "sheetVersion", String.valueOf(fresh.sheetVersion())));

        return new SelectResponse(true, saved.getId(), chosen.kind(), saved.getRequoteNote(), null);
    }

    /**
     * Links a recorded selection to the payment that was actually made, once it exists.
     *
     * <p>Separate from {@link #select} because the two happen at different moments: the route is
     * chosen before the payment is submitted, and the transaction id only exists afterwards.
     */
    @Transactional
    public void attachToTransaction(UUID selectionId, UUID transactionId) {
        selections.findById(selectionId).ifPresent(s -> {
            s.setTransactionId(transactionId);
            selections.save(s);
        });
    }

    // --------------------------------------------------------------------- mapping

    private Ask ask(OptionsRequest r) {
        String destination = blankToNull(r.beneficiaryCountry());
        return new Ask(
                r.currency() == null ? null : r.currency().toUpperCase(),
                r.amount(),
                r.byWhen(),
                // Present means cross-border, which is the domestic/cross-border discriminator VAM
                // otherwise does not have. Blank is not absent: it would fail the alpha-2 pattern.
                destination == null ? null : destination.toUpperCase(),
                chargeBearer(r.chargeBearer()),
                purpose(r.purposeCode()),
                r.reachCard(),
                r.reachToken());
    }

    /** uc05 takes OUR, SHA or BEN; anything else is left unsaid rather than guessed at. */
    private String chargeBearer(String value) {
        String v = blankToNull(value);
        return v != null && CHARGE_BEARERS.contains(v.toUpperCase()) ? v.toUpperCase() : null;
    }

    /**
     * VAM stores an ISO purpose code; uc05 takes one of seven purposes, and uses it as a feature
     * rather than a rule. Only the three unambiguous mappings are made -- purpose is optional, and a
     * wrong one is worse than none, because it feeds the estimate.
     */
    private String purpose(String purposeCode) {
        String code = blankToNull(purposeCode);
        if (code == null) {
            return null;
        }
        return switch (code.toUpperCase()) {
            case "SUPP" -> "supplier";
            case "SALA" -> "payroll";
            case "TREA" -> "treasury";
            default -> null;   // ponytail: add a mapping when a screen actually sends that code
        };
    }

    // ----------------------------------------------------------------------- drift

    /** What changed enough to go back and ask. Empty means go ahead. */
    private List<String> drift(SelectRequest request, Option fresh, LocalDateTime byWhen) {
        List<String> moved = new ArrayList<>();

        if (request.quotedCharge() != null && fresh.charge() != null
                && request.quotedCharge().signum() > 0) {
            BigDecimal up = fresh.charge().subtract(request.quotedCharge());
            if (up.signum() > 0
                    && up.divide(request.quotedCharge(), 4, RoundingMode.HALF_UP).doubleValue()
                        > props.getChargeTolerance()) {
                moved.add("The charge is now " + fresh.charge() + ", up from " + request.quotedCharge() + ".");
            }
        }

        if (request.quotedArrival() != null && fresh.arrival() != null) {
            long later = Duration.between(request.quotedArrival(), fresh.arrival()).toMinutes();
            if (later > props.getArrivalToleranceMinutes()) {
                moved.add("It now arrives " + later + " minutes later than quoted.");
            }
        }

        // Independent of tolerance: an arrival past the deadline is not drift, it is a different answer.
        if (byWhen != null && fresh.arrival() != null && fresh.arrival().isAfter(byWhen)) {
            moved.add("It would no longer arrive by your deadline.");
        }
        return moved;
    }

    /** Drift inside tolerance, or in the corporate's favour: recorded, never a reason to interrupt. */
    private String betterNews(SelectRequest request, Option fresh) {
        List<String> notes = new ArrayList<>();
        if (request.quotedCharge() != null && fresh.charge() != null
                && fresh.charge().compareTo(request.quotedCharge()) != 0) {
            notes.add("charge " + request.quotedCharge() + " -> " + fresh.charge());
        }
        if (request.quotedArrival() != null && fresh.arrival() != null
                && !request.quotedArrival().equals(fresh.arrival())) {
            notes.add("arrival " + request.quotedArrival() + " -> " + fresh.arrival());
        }
        return notes.isEmpty() ? null : "Re-quoted at confirm: " + String.join(", ", notes);
    }

    private SelectResponse refuse(OptionsRequest ask, SelectRequest request, Quote fresh, String note) {
        log.info("Route {} refused at confirm for VA {}: {}", request.route(), ask.fromVaId(), note);
        audit.record("PAYMENT_ROUTE_REQUOTE_REFUSED", "VirtualAccount", ask.fromVaId(), note,
                Map.of("route", String.valueOf(request.route()),
                        "quotedCharge", String.valueOf(request.quotedCharge()),
                        "quotedArrival", String.valueOf(request.quotedArrival())));
        return new SelectResponse(false, null, null, note, fresh);
    }

    // ----------------------------------------------------------------------- bits

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private String summaryOf(OptionsRequest r, Quote quote) {
        int n = quote != null && quote.options() != null ? quote.options().size() : 0;
        return n + " route option(s) for " + r.amount() + " " + r.currency()
                + (blankToNull(r.beneficiaryCountry()) == null ? " domestic" : " to " + r.beneficiaryCountry());
    }
}
