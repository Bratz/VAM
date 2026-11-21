package com.bank.vam.fx.hedge;

import com.bank.vam.config.CurrentUser;
import com.bank.vam.entity.payables.Payable;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.fx.HomeCurrency;
import com.bank.vam.fx.deal.DealStatus;
import com.bank.vam.fx.deal.FxDeal;
import com.bank.vam.fx.deal.FxDealRepository;
import com.bank.vam.fx.exposure.ExposureBucket;
import com.bank.vam.fx.exposure.ExposureSources;
import com.bank.vam.fx.hedge.FxHedgeLink.Kind;
import com.bank.vam.repository.payables.PayableRepository;
import com.bank.vam.repository.receivables.ReceivableRepository;
import com.bank.vam.service.audit.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Links FX deals to the invoices they hedge (spec docs/specs/fx-hedge-links.md). A link stores only what was agreed;
 * what it is still worth is worked out on every read from the invoice as it is now (R3), so paying, reducing or
 * cancelling an invoice shrinks its links with no hook in the invoice writers, and the deal shows what is left
 * unbacked (R4). "Open" is what FX exposure counts ({@link ExposureSources}).
 */
@Service
@RequiredArgsConstructor
public class FxHedgeLinkService {

    public static final String LINKED = "FX_HEDGE_LINKED";
    public static final String UNLINKED = "FX_HEDGE_UNLINKED";

    /** One invoice to hedge: {@code amount} in the invoice's currency. */
    public record LinkRequest(Kind kind, UUID invoiceId, BigDecimal amount) {
    }

    /**
     * A link as it stands. {@code effective} is what still hedges: min(linked, outstanding) while the invoice is open
     * and the deal live, else 0; {@code reason} says why it is below {@code linked} (PAID, CANCELLED, NETTED,
     * WRITTEN_OFF, NOT_OPEN, REDUCED, DEAL_ENDED), null when it is not. {@code warning}: the deal settles in another
     * exposure bucket than the invoice falls due (allowed, flagged).
     */
    public record LinkView(UUID id, UUID dealId, String dealRef, Kind kind, UUID invoiceId, String invoiceRef,
                           String counterparty, LocalDate dueDate, String currency, BigDecimal linked,
                           BigDecimal effective, String reason, String warning, String linkedBy, LocalDateTime linkedAt) {
    }

    /** One currency the deal's links are in: what remains of the deal in it, and how much of that invoices back. */
    public record CurrencyCover(String currency, BigDecimal remaining, BigDecimal linked, BigDecimal effective,
                                BigDecimal unbacked) {
    }

    /**
     * A deal's links. {@code released}: links have shrunk or lapsed (effective below linked) and left part of the live
     * deal unbacked, so treasury should roll, close out or relink it.
     */
    public record DealLinks(UUID dealId, String dealRef, List<LinkView> links, List<CurrencyCover> currencies,
                            boolean released) {
    }

    /** A link that passed the rules, not yet saved; the reference is kept for the audit line. */
    public record Prepared(FxHedgeLink link, String invoiceRef) {
    }

    /** What the rules and views need of a payable or receivable. */
    record Invoice(Kind kind, UUID id, UUID corporateId, String currency, BigDecimal outstanding, String status,
                   boolean intercompany, String ref, String counterparty, LocalDate dueDate) {

        boolean open() {
            return !(kind == Kind.PAYABLE ? ExposureSources.PAYABLE_NOT_OPEN : ExposureSources.RECEIVABLE_NOT_OPEN)
                    .contains(status);
        }

        static Invoice of(Payable p) {
            return new Invoice(Kind.PAYABLE, p.getId(), p.getCorporateId(), p.getCurrencyCode(),
                    outstanding(p.getOutstandingAmount(), p.getNetAmount()), name(p.getStatus()),
                    Boolean.TRUE.equals(p.getIsIntercompany()),
                    p.getInvoiceNumber() != null ? p.getInvoiceNumber() : p.getPayableNumber(), p.getVendorName(), p.getDueDate());
        }

        static Invoice of(Receivable r) {
            return new Invoice(Kind.RECEIVABLE, r.getId(), r.getCorporateId(), r.getCurrencyCode(),
                    outstanding(r.getOutstandingAmount(), r.getNetAmount()), name(r.getStatus()),
                    Boolean.TRUE.equals(r.getIsIntercompany()), r.getReceivableNumber(), r.getCustomerName(), r.getDueDate());
        }

        /** COALESCE(outstanding, net), as exposure reads it. */
        private static BigDecimal outstanding(BigDecimal outstanding, BigDecimal net) {
            BigDecimal v = outstanding != null ? outstanding : net;
            return v == null ? BigDecimal.ZERO : v;
        }

        private static String name(Enum<?> e) {
            return e == null ? null : e.name();
        }
    }

    private record Cover(BigDecimal effective, String reason) {
    }

    private static final Set<String> NAMED_REASONS = Set.of("PAID", "CANCELLED", "NETTED", "WRITTEN_OFF");

    private final FxHedgeLinkRepository links;
    private final FxDealRepository deals;
    private final PayableRepository payables;
    private final ReceivableRepository receivables;
    private final HomeCurrency homeCurrency;
    private final AuditLogService audit;

    /** The corporate's deal, or 404: a deal of another corporate is not found. */
    public FxDeal deal(UUID corporateId, UUID dealId) {
        return deals.findById(dealId).filter(d -> d.getCorporateId().equals(corporateId))
                .orElseThrow(() -> new ResourceNotFoundException("Deal not found: " + dealId));
    }

    @Transactional(readOnly = true)
    public DealLinks forDeal(UUID corporateId, UUID dealId) {
        return view(deal(corporateId, dealId));
    }

    /** Links the deal to the invoices, all or nothing (R2). */
    @Transactional
    public DealLinks link(UUID corporateId, UUID dealId, List<LinkRequest> requests) {
        FxDeal deal = deal(corporateId, dealId);
        save(deal, check(deal, requests));
        return view(deal);
    }

    @Transactional
    public DealLinks unlink(UUID corporateId, UUID dealId, UUID linkId) {
        FxDeal deal = deal(corporateId, dealId);
        FxHedgeLink l = links.findById(linkId).filter(x -> x.getDealId().equals(dealId))
                .orElseThrow(() -> new ResourceNotFoundException("Hedge link not found: " + linkId));
        Invoice inv = invoices(List.of(l)).get(l.getInvoiceId());
        links.delete(l);
        String ref = inv == null ? l.getInvoiceId().toString() : inv.ref();
        audit.record(UNLINKED, "FX_DEAL", deal.getId(), deal.getCorporateId(),
                deal.getDealRef() + " no longer hedges " + ref + " (" + l.getCurrency() + " " + l.getAmount().toPlainString() + ")",
                payload(deal, l, ref));
        return view(deal);
    }

    /** Each invoice's links (with their deals), for the Payables / Receivables lists. Invoices without links are left out. */
    @Transactional(readOnly = true)
    public Map<UUID, List<LinkView>> forInvoices(UUID corporateId, Kind kind, Collection<UUID> invoiceIds) {
        if (invoiceIds == null || invoiceIds.isEmpty()) {
            return Map.of();
        }
        List<FxHedgeLink> found = links.findByKindAndInvoiceIdIn(kind, invoiceIds).stream()
                .filter(l -> l.getCorporateId().equals(corporateId)).toList();
        Map<UUID, FxDeal> byId = dealsOf(found);
        Map<UUID, Invoice> invs = invoices(found);
        Map<UUID, List<LinkView>> out = new LinkedHashMap<>();
        found.forEach(l -> out.computeIfAbsent(l.getInvoiceId(), k -> new ArrayList<>())
                .add(view(l, byId.get(l.getDealId()), invs.get(l.getInvoiceId()))));
        return out;
    }

    /**
     * What of each deal is reserved in {@code currency} for the invoices it hedges: its links' effective amounts (R3),
     * leaving out the links to the payable {@code except} (the one being paid, null for none). A payment of anything
     * else must not spend it (R6). Deals without such links are left out. One read for all the deals.
     */
    public Map<UUID, BigDecimal> reserved(Collection<FxDeal> forDeals, String currency, UUID except) {
        if (forDeals.isEmpty()) {
            return Map.of();
        }
        Map<UUID, FxDeal> byId = forDeals.stream().collect(Collectors.toMap(FxDeal::getId, Function.identity(), (a, b) -> a));
        List<FxHedgeLink> found = links.findByDealIdIn(byId.keySet()).stream()
                .filter(l -> l.getCurrency().equals(currency))
                .filter(l -> !(l.getKind() == Kind.PAYABLE && l.getInvoiceId().equals(except))).toList();
        Map<UUID, Invoice> invs = invoices(found);
        Map<UUID, BigDecimal> out = new HashMap<>();
        found.forEach(l -> {
            BigDecimal e = cover(l, byId.get(l.getDealId()), invs.get(l.getInvoiceId())).effective();
            if (e.signum() > 0) {
                out.merge(l.getDealId(), e, BigDecimal::add);
            }
        });
        return out;
    }

    /**
     * The rules of R2 for linking {@code deal} to the requested invoices; the first line that breaks one refuses the
     * whole request, naming the invoice. Writes nothing. {@code deal} may be a draft not booked yet (no id), which is
     * how booking with links refuses before anything is booked.
     */
    public List<Prepared> check(FxDeal deal, List<LinkRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw refused("Choose at least one invoice to link");
        }
        String dealName = deal.getId() == null ? "the deal" : deal.getDealRef();
        if (!DealStatus.OPEN.contains(deal.getStatus())) {
            throw refused(dealName + " is " + deal.getStatus() + ": only a live deal can hedge an invoice");
        }
        for (LinkRequest r : requests) {
            if (r == null || r.kind() == null || r.invoiceId() == null) {
                throw refused("Each link needs the kind (PAYABLE or RECEIVABLE) and the invoice");
            }
            if (r.amount() == null || r.amount().setScale(2, RoundingMode.HALF_UP).signum() <= 0) {
                throw refused("The amount to link must be more than 0 (invoice " + r.invoiceId() + ")");
            }
        }
        Map<UUID, Invoice> invs = load(requests.stream().collect(Collectors.groupingBy(LinkRequest::kind,
                Collectors.mapping(LinkRequest::invoiceId, Collectors.toSet()))));

        // Already hedged per invoice, by any live deal (a settled or closed deal's link no longer hedges anything).
        List<FxHedgeLink> onInvoices = Stream.of(Kind.values())
                .flatMap(k -> {
                    Set<UUID> ids = requests.stream().filter(r -> r.kind() == k).map(LinkRequest::invoiceId)
                            .collect(Collectors.toSet());
                    return ids.isEmpty() ? Stream.empty() : links.findByKindAndInvoiceIdIn(k, ids).stream();
                }).toList();
        Map<UUID, FxDeal> linkDeals = dealsOf(onInvoices);
        Map<UUID, BigDecimal> perInvoice = new HashMap<>();
        onInvoices.stream().filter(l -> live(linkDeals.get(l.getDealId())))
                .forEach(l -> perInvoice.merge(l.getInvoiceId(), l.getAmount(), BigDecimal::add));

        // Already used of the deal per currency: what its links still hedge, so a lapsed link frees the deal to relink.
        Map<String, BigDecimal> perCurrency = new HashMap<>();
        if (deal.getId() != null) {
            List<FxHedgeLink> own = links.findByDealIdOrderByLinkedAtAsc(deal.getId());
            Map<UUID, Invoice> ownInvs = invoices(own);
            own.forEach(l -> perCurrency.merge(l.getCurrency(), cover(l, deal, ownInvs.get(l.getInvoiceId())).effective(),
                    BigDecimal::add));
        }

        String base = homeCurrency.of(deal.getCorporateId());
        List<Prepared> out = new ArrayList<>();
        for (LinkRequest r : requests) {
            String what = r.kind() == Kind.PAYABLE ? "payable" : "receivable";
            Invoice inv = invs.get(r.invoiceId());
            if (inv == null || !deal.getCorporateId().equals(inv.corporateId())) {
                throw refused("No " + what + " " + r.invoiceId() + " for this corporate");
            }
            String ref = inv.ref();
            String ccy = inv.currency();
            if (!inv.open()) {
                throw refused(ref + " is " + inv.status() + ": only an open invoice can be hedged");
            }
            if (inv.intercompany()) {
                throw refused(ref + " is intercompany: it nets inside the group and is not an FX exposure");
            }
            if (ccy == null || ccy.equals(base)) {
                throw refused(ref + " is in " + base + ", the corporate's base currency: there is nothing to hedge");
            }
            if (!ccy.equals(deal.getBuyCurrency()) && !ccy.equals(deal.getSellCurrency())) {
                throw refused(ref + " is in " + ccy + "; " + dealName + " trades " + deal.getPair());
            }
            if (r.kind() == Kind.PAYABLE && !ccy.equals(deal.getBuyCurrency())) {
                throw refused(ref + " is a payable in " + ccy + ": it is hedged by a deal that buys " + ccy
                        + ", and " + dealName + " sells it");
            }
            if (r.kind() == Kind.RECEIVABLE && !ccy.equals(deal.getSellCurrency())) {
                throw refused(ref + " is a receivable in " + ccy + ": it is hedged by a deal that sells " + ccy
                        + ", and " + dealName + " buys it");
            }
            BigDecimal amount = r.amount().setScale(2, RoundingMode.HALF_UP);
            BigDecimal hedged = perInvoice.merge(inv.id(), amount, BigDecimal::add);
            if (hedged.compareTo(inv.outstanding()) > 0) {
                throw refused(ref + ": linking " + money(ccy, amount) + " would hedge " + money(ccy, hedged)
                        + " of it, more than its outstanding " + money(ccy, inv.outstanding()));
            }
            BigDecimal used = perCurrency.merge(ccy, amount, BigDecimal::add);
            BigDecimal remaining = remaining(deal, ccy);
            if (used.compareTo(remaining) > 0) {
                throw refused(ref + ": linking " + money(ccy, amount) + " would back " + money(ccy, used) + " of "
                        + dealName + ", more than remains of it (" + money(ccy, remaining) + ")");
            }
            FxHedgeLink l = new FxHedgeLink();
            l.setCorporateId(deal.getCorporateId());
            l.setDealId(deal.getId());
            l.setKind(r.kind());
            l.setInvoiceId(inv.id());
            l.setCurrency(ccy);
            l.setAmount(amount);
            out.add(new Prepared(l, ref));
        }
        return out;
    }

    /** Saves links {@link #check}ed against {@code deal} (now booked) and audits each one. */
    public void save(FxDeal deal, List<Prepared> prepared) {
        String who = CurrentUser.name();
        LocalDateTime now = LocalDateTime.now();
        for (Prepared p : prepared) {
            FxHedgeLink l = p.link();
            l.setDealId(deal.getId());
            l.setCorporateId(deal.getCorporateId());
            l.setLinkedBy(who);
            l.setLinkedAt(now);
            links.save(l);
            audit.record(LINKED, "FX_DEAL", deal.getId(), deal.getCorporateId(),
                    deal.getDealRef() + " hedges " + p.invoiceRef() + " (" + l.getCurrency() + " " + l.getAmount().toPlainString() + ")",
                    payload(deal, l, p.invoiceRef()));
        }
    }

    DealLinks view(FxDeal deal) {
        List<FxHedgeLink> own = links.findByDealIdOrderByLinkedAtAsc(deal.getId());
        Map<UUID, Invoice> invs = invoices(own);
        List<LinkView> views = own.stream().map(l -> view(l, deal, invs.get(l.getInvoiceId()))).toList();
        Map<String, List<LinkView>> byCurrency = views.stream()
                .collect(Collectors.groupingBy(LinkView::currency, LinkedHashMap::new, Collectors.toList()));
        List<CurrencyCover> covers = byCurrency.entrySet().stream().map(e -> {
            BigDecimal linked = sum(e.getValue(), LinkView::linked);
            BigDecimal effective = sum(e.getValue(), LinkView::effective);
            BigDecimal remaining = remaining(deal, e.getKey());
            return new CurrencyCover(e.getKey(), remaining, linked, effective, remaining.subtract(effective).max(BigDecimal.ZERO));
        }).toList();
        boolean released = live(deal) && covers.stream()
                .anyMatch(c -> c.effective().compareTo(c.linked()) < 0 && c.unbacked().signum() > 0);
        return new DealLinks(deal.getId(), deal.getDealRef(), views, covers, released);
    }

    /**
     * What remains of a live deal in one of its currencies, from its open hedge positions (the undrawn share of each
     * leg, as the exposure ladder counts it); 0 once the deal has ended.
     */
    static BigDecimal remaining(FxDeal deal, String currency) {
        if (!live(deal)) {
            return BigDecimal.ZERO;
        }
        return deal.hedgePositions().stream().filter(p -> p.getCurrency().equals(currency))
                .map(p -> p.getAmount().abs()).findFirst().orElse(BigDecimal.ZERO);
    }

    /** R3: what the link still hedges, and why it is less than linked. */
    private static Cover cover(FxHedgeLink l, FxDeal deal, Invoice inv) {
        if (inv == null) {
            return new Cover(BigDecimal.ZERO, "NOT_OPEN");
        }
        if (!inv.open()) {
            return new Cover(BigDecimal.ZERO, NAMED_REASONS.contains(inv.status()) ? inv.status() : "NOT_OPEN");
        }
        if (!live(deal)) {
            return new Cover(BigDecimal.ZERO, "DEAL_ENDED");
        }
        if (inv.outstanding().compareTo(l.getAmount()) < 0) {
            return new Cover(inv.outstanding().max(BigDecimal.ZERO), "REDUCED");
        }
        return new Cover(l.getAmount(), null);
    }

    private static LinkView view(FxHedgeLink l, FxDeal deal, Invoice inv) {
        Cover c = cover(l, deal, inv);
        return new LinkView(l.getId(), l.getDealId(), deal == null ? null : deal.getDealRef(), l.getKind(), l.getInvoiceId(),
                inv == null ? null : inv.ref(), inv == null ? null : inv.counterparty(), inv == null ? null : inv.dueDate(),
                l.getCurrency(), l.getAmount(), c.effective(), c.reason(),
                deal == null || inv == null ? null : bucketWarning(deal.getValueDate(), inv.dueDate(), LocalDate.now()),
                l.getLinkedBy(), l.getLinkedAt());
    }

    /** Flag, not a refusal: the deal settles in another exposure bucket than the invoice falls due. */
    static String bucketWarning(LocalDate valueDate, LocalDate dueDate, LocalDate today) {
        ExposureBucket deal = ExposureBucket.forDate(valueDate, today);
        ExposureBucket invoice = ExposureBucket.forDate(dueDate, today);
        return deal == invoice ? null : "The deal's value date " + valueDate + " (" + deal.label()
                + ") is outside the invoice's bucket (due " + dueDate + ", " + invoice.label() + ")";
    }

    private static boolean live(FxDeal d) {
        return d != null && DealStatus.OPEN.contains(d.getStatus());
    }

    private Map<UUID, FxDeal> dealsOf(List<FxHedgeLink> ls) {
        Set<UUID> ids = ls.stream().map(FxHedgeLink::getDealId).filter(Objects::nonNull).collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of()
                : deals.findAllById(ids).stream().collect(Collectors.toMap(FxDeal::getId, Function.identity()));
    }

    private Map<UUID, Invoice> invoices(List<FxHedgeLink> ls) {
        return load(ls.stream().collect(Collectors.groupingBy(FxHedgeLink::getKind,
                Collectors.mapping(FxHedgeLink::getInvoiceId, Collectors.toSet()))));
    }

    private Map<UUID, Invoice> load(Map<Kind, Set<UUID>> ids) {
        Map<UUID, Invoice> out = new HashMap<>();
        Set<UUID> p = ids.getOrDefault(Kind.PAYABLE, Set.of());
        Set<UUID> r = ids.getOrDefault(Kind.RECEIVABLE, Set.of());
        if (!p.isEmpty()) {
            payables.findAllById(p).forEach(x -> out.put(x.getId(), Invoice.of(x)));
        }
        if (!r.isEmpty()) {
            receivables.findAllById(r).forEach(x -> out.put(x.getId(), Invoice.of(x)));
        }
        return out;
    }

    private static Map<String, Object> payload(FxDeal deal, FxHedgeLink l, String invoiceRef) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dealRef", deal.getDealRef());
        m.put("linkId", l.getId());
        m.put("kind", l.getKind().name());
        m.put("invoiceId", l.getInvoiceId());
        m.put("invoiceRef", invoiceRef);
        m.put("currency", l.getCurrency());
        m.put("amount", l.getAmount());
        return m;
    }

    private static BigDecimal sum(List<LinkView> vs, Function<LinkView, BigDecimal> f) {
        return vs.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String money(String ccy, BigDecimal v) {
        return ccy + " " + v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static BusinessException refused(String message) {
        return new BusinessException("FX_HEDGE_LINK_REFUSED", message);
    }
}
