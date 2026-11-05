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

    private final FxDealEventRepository events;
    private final AuditLogService audit;

    void quote(FxQuoteRequest q, QuoteStatus from, QuoteStatus to, String detail) {
        write(FxDealEvent.QUOTE, q.getId(), q.getCorporateId(), from == null ? null : from.name(), to.name(), detail);
    }

    void deal(FxDeal d, DealStatus from, DealStatus to, String detail) {
        write(FxDealEvent.DEAL, d.getId(), d.getCorporateId(), from == null ? null : from.name(), to.name(), detail);
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
