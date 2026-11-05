package com.bank.vam.fx.deal;

import com.bank.vam.service.audit.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Writes every quote and deal transition to the timeline and to the tamper-evident audit log, in one place. */
@Component
@RequiredArgsConstructor
class FxEvents {

    static final int MAX_ACTION_LENGTH = 20;

    private final FxDealEventRepository events;
    private final AuditLogService audit;

    void quote(FxQuoteRequest q, QuoteStatus from, QuoteStatus to, String detail) {
        write(FxDealEvent.QUOTE, q.getId(), q.getCorporateId(), from == null ? null : from.name(), to.name(), detail);
    }

    /** A counter-offer round: the quote stays QUOTED, so this is a step, not a status change. */
    void counter(FxQuoteRequest q, String detail) {
        write(FxDealEvent.QUOTE, q.getId(), q.getCorporateId(), null, "COUNTER_OFFER", detail);
    }

    void deal(FxDeal d, DealStatus from, DealStatus to, String detail) {
        write(FxDealEvent.DEAL, d.getId(), d.getCorporateId(), from == null ? null : from.name(), to.name(), detail);
    }

    /** A step that is not a status change, such as a confirmation being issued, affirmed or disputed. */
    void note(FxDeal d, String action, String detail) {
        // The column is varchar(20) and Hibernate's ddl-auto never widens it, so a longer code would fail only at runtime.
        if (action.length() > MAX_ACTION_LENGTH) {
            throw new IllegalArgumentException("Event action '" + action + "' is longer than " + MAX_ACTION_LENGTH + " characters");
        }
        write(FxDealEvent.DEAL, d.getId(), d.getCorporateId(), null, action, detail);
    }

    static String actor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() ? auth.getName() : "SYSTEM";
    }

    private void write(String type, UUID id, UUID corporateId, String from, String to, String detail) {
        FxDealEvent e = new FxDealEvent();
        e.setEntityType(type);
        e.setEntityId(id);
        e.setCorporateId(corporateId);
        e.setFromStatus(from);
        e.setToStatus(to);
        e.setActor(actor());
        e.setDetail(detail);
        events.save(e);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", from);
        payload.put("to", to);
        audit.record("FX_" + type + "_" + to, "FX_" + type, id, corporateId, detail, payload);
    }
}
