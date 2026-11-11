package com.bank.vam.collections;

import com.bank.vam.collections.CollectionLine.Status;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.entity.receivables.Receivable.ReceivableStatus;
import com.bank.vam.exception.BusinessException;
import static com.bank.vam.collections.IsoXml.*;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.iso20022.service.Iso20022RequestToPayService;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.receivables.ReceivableRepository;
import com.bank.vam.service.TransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Direct debits that come back. Rejected before settling (pain.002): no money moved, the line is REJECTED.
 * Returned after settling (pacs.004, a camt.054 debit, or marked in the portal): the hold is released, the
 * gross goes back to the payer, the invoice reopens and the line is RETURNED. Either way the customer is then
 * sent a Request to Pay, and a reason that says the mandate is unusable suspends it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollectionReturnService {

    /** The ISO 20022 external return reason codes a direct debit commonly comes back with. */
    public static final Map<String, String> REASONS = Map.ofEntries(
            Map.entry("AC01", "Account number incorrect"), Map.entry("AC04", "Account closed"),
            Map.entry("AC06", "Account blocked"), Map.entry("AG01", "Transaction forbidden on this account"),
            Map.entry("AM04", "Insufficient funds"), Map.entry("AM05", "Duplicate collection"),
            Map.entry("BE05", "Creditor not recognised"), Map.entry("MD01", "No valid mandate"),
            Map.entry("MD06", "Refund requested by the payer"), Map.entry("MD07", "Payer deceased"),
            Map.entry("MS02", "Refused by the payer"), Map.entry("MS03", "Reason not specified"),
            Map.entry("SL01", "Blocked by the payer's bank"));

    /** Reasons that mean the mandate cannot be used again until someone looks at it. */
    static final Set<String> MANDATE_UNUSABLE = Set.of("AC01", "AC04", "AC06", "AG01", "MD01", "MD07");

    private final CollectionLineRepository lines;
    private final CollectionRunRepository runs;
    private final CustomerMandateRepository mandates;
    private final ReceivableRepository receivables;
    private final VirtualAccountRepository accounts;
    private final TransactionService transactions;
    private final com.bank.vam.repository.TransactionRepository ledger;
    private final Iso20022RequestToPayService requestToPay;
    private final PlatformTransactionManager transactionManager;

    /** One returned or rejected debit, by its EndToEndId. */
    public record Return(String endToEndId, String reasonCode) {
    }

    /** {@code result}: REJECTED, RETURNED, UNKNOWN (no such debit) or UNCHANGED (already returned, never sent ...). */
    public record Outcome(String endToEndId, String result, String detail, Boolean requestToPaySent) {
    }

    public List<Outcome> apply(List<Return> returns) {
        TransactionTemplate own = new TransactionTemplate(transactionManager);
        own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        List<Outcome> out = new ArrayList<>();
        for (Return r : returns) {
            Outcome o;
            try {
                o = own.execute(t -> one(r));
            } catch (RuntimeException e) {
                log.warn("Return of {} not applied: {}", r.endToEndId(), e.getMessage());
                out.add(new Outcome(r.endToEndId(), "FAILED", e.getMessage(), null));
                continue;
            }
            if ("REJECTED".equals(o.result()) || "RETURNED".equals(o.result())) {
                o = withRequestToPay(own, o);
            }
            out.add(o);
        }
        return out;
    }

    /** "Mark returned" in the portal. */
    public Outcome returnLine(UUID lineId, String reasonCode) {
        CollectionLine l = lines.findById(lineId).orElseThrow(() -> new ResourceNotFoundException("Collection line not found: " + lineId));
        if (l.getEndToEndId() == null) throw new BusinessException("Only a direct debit can be returned");
        Outcome o = apply(List.of(new Return(l.getEndToEndId(), reasonCode))).get(0);
        if ("UNCHANGED".equals(o.result()) || "FAILED".equals(o.result())) throw new BusinessException(o.detail());
        return o;
    }

    private Outcome one(Return r) {
        // A return without a code is recorded as such, not as MS03.
        String code = r.reasonCode() == null || r.reasonCode().isBlank() ? null : r.reasonCode().trim().toUpperCase(Locale.ROOT);
        String why = code == null ? "no reason given" : code + " " + REASONS.getOrDefault(code, "Returned");
        CollectionLine l = lines.findByEndToEndId(r.endToEndId()).orElse(null);
        if (l == null) return new Outcome(r.endToEndId(), "UNKNOWN", "No direct debit has this EndToEndId", null);

        Status was = l.getStatus();
        if (was == Status.SENT) {
            l.setStatus(Status.REJECTED);
            l.setReason("Rejected before collection: " + why);
        } else if (was == Status.SETTLED || was == Status.AVAILABLE) {
            if (was == Status.SETTLED) {
                accounts.findById(l.getCreditorVaId()).orElseThrow().releaseHold(l.getCreditedAmount());
            }
            // ponytail: a converted collection is reversed at its original rate (what was credited goes back out); a
            // bank would reconvert at the day's rate, and the FX deal's utilisation is not undone.
            transactions.reverseCollection(l.getCreditorVaId(),
                    l.getCreditGross() != null ? l.getCreditGross() : l.getAmount(),
                    l.getCreditCurrency() != null ? l.getCreditCurrency() : l.getCurrency(), l.getTransactionRef(),
                    why, l.getDebtorName(), l.getDebtorIban(), "DIRECT_DEBIT");
            reopen(l, why);
            l.setStatus(Status.RETURNED);
            l.setReason("Returned after collection: " + why);
        } else {
            return new Outcome(r.endToEndId(), "UNCHANGED", "The debit is " + was + "; nothing to return", null);
        }
        l.setReturnCode(code);
        l.setReturnedOn(LocalDate.now());

        mandates.findById(l.getMandateId()).ifPresent(m -> {
            if (code != null && MANDATE_UNUSABLE.contains(code) && m.getStatus() != CustomerMandate.Status.CANCELLED) {
                m.setStatus(CustomerMandate.Status.SUSPENDED);
                m.setStatusReason("Suspended after " + why + " on " + l.getReceivableNumber());
            } else if (m.getStatus() == CustomerMandate.Status.USED) {
                m.setStatus(CustomerMandate.Status.ACTIVE); // a returned one-off never collected
            }
        });
        return new Outcome(r.endToEndId(), l.getStatus().name(), l.getReason(), null);
    }

    /**
     * Undoes the payment the settlement recorded against the invoice; for one collected on behalf (COBO), also the
     * in-house credit the settlement gave the subsidiary for it (spec R16).
     */
    private void reopen(CollectionLine l, String why) {
        Receivable inv = receivables.findById(l.getReceivableId()).orElse(null);
        if (inv == null) return;
        if (Boolean.TRUE.equals(inv.getIsCobo()) && l.getTransactionRef() != null) {
            ledger.findByReferenceNumber(l.getTransactionRef())
                    .ifPresent(credit -> transactions.reverseCoboInHouseCredit(credit.getId(), "direct debit " + l.getEndToEndId() + " returned, " + why));
            if (inv.getCoboRequestStatus() == Receivable.CoboRequestStatus.COLLECTED) inv.setCoboRequestStatus(null);
        }
        BigDecimal paid = inv.getPaidAmount().subtract(l.getAmount()).max(BigDecimal.ZERO);
        inv.setPaidAmount(paid);
        inv.setOutstandingAmount(inv.getNetAmount().subtract(paid));
        inv.setStatus(inv.getDueDate() != null && inv.getDueDate().isBefore(LocalDate.now()) ? ReceivableStatus.OVERDUE
                : paid.signum() > 0 ? ReceivableStatus.PARTIAL : ReceivableStatus.OPEN);
    }

    private Outcome withRequestToPay(TransactionTemplate own, Outcome o) {
        CollectionLine l = lines.findByEndToEndId(o.endToEndId()).orElseThrow();
        boolean sent;
        String note;
        try {
            own.executeWithoutResult(t -> requestToPay.sendRequestToPay(l.getReceivableId()));
            sent = true;
            note = "; Request to Pay sent";
        } catch (RuntimeException e) {
            sent = false;
            note = "; Request to Pay could not be sent: " + e.getMessage();
        }
        boolean s = sent;
        String n = note;
        own.executeWithoutResult(t -> lines.findById(l.getId()).ifPresent(x -> {
            x.setRtpAfterReturn(s);
            x.setReason(x.getReason() + n);
        }));
        return new Outcome(o.endToEndId(), o.result(), o.detail() + note, sent);
    }

    // ------------------------------------------------------------------------------------------------
    // Bank messages
    // ------------------------------------------------------------------------------------------------

    /** pacs.004 PaymentReturn: every TxInf's OrgnlEndToEndId with its RtrRsnInf/Rsn/Cd. */
    public static List<Return> parsePacs004(String xml) {
        List<Return> out = new ArrayList<>();
        for (Element tx : all(parse(xml), "TxInf")) {
            out.add(new Return(text(tx, "OrgnlEndToEndId"), path(tx, "RtrRsnInf", "Rsn", "Cd")));
        }
        return out;
    }

    /**
     * pain.002 status report on a run's pain.008: rejected transactions (TxSts RJCT) by OrgnlEndToEndId; a payment
     * block or the whole message rejected (PmtInfSts / GrpSts RJCT) rejects every debit still waiting in that run.
     */
    public List<Return> parsePain002(String xml) {
        Document doc = parse(xml);
        List<Return> out = new ArrayList<>();
        String groupStatus = text(doc.getDocumentElement(), "GrpSts");
        String runRef = text(doc.getDocumentElement(), "OrgnlMsgId");
        if ("RJCT".equals(groupStatus) && runRef != null) {
            String code = path(doc.getDocumentElement(), "StsRsnInf", "Rsn", "Cd");
            runs.findByRunRef(runRef).ifPresent(run -> lines.findByRunIdOrderByCreatedAtAsc(run.getId()).stream()
                    .filter(l -> l.getStatus() == Status.SENT && l.getEndToEndId() != null)
                    .forEach(l -> out.add(new Return(l.getEndToEndId(), code))));
            return out;
        }
        for (Element pmt : all(doc, "OrgnlPmtInfAndSts")) {
            boolean blockRejected = "RJCT".equals(direct(pmt, "PmtInfSts"));
            String blockCode = path(pmt, "StsRsnInf", "Rsn", "Cd");
            for (Element tx : all(pmt, "TxInfAndSts")) {
                if (blockRejected || "RJCT".equals(text(tx, "TxSts"))) {
                    String code = path(tx, "StsRsnInf", "Rsn", "Cd");
                    out.add(new Return(text(tx, "OrgnlEndToEndId"), code != null ? code : blockCode));
                }
            }
        }
        return out;
    }

    /** camt.054 debit entries for returned direct debits: TxDtls with an EndToEndId and a RtrInf reason. */
    public static List<Return> parseCamt054Returns(String xml) {
        List<Return> out = new ArrayList<>();
        for (Element ntry : all(parse(xml), "Ntry")) {
            if (!"DBIT".equals(direct(ntry, "CdtDbtInd"))) continue;
            for (Element tx : all(ntry, "TxDtls")) {
                String code = path(tx, "RtrInf", "Rsn", "Cd");
                String e2e = path(tx, "Refs", "EndToEndId");
                if (code != null && e2e != null) out.add(new Return(e2e, code));
            }
        }
        return out;
    }

}
