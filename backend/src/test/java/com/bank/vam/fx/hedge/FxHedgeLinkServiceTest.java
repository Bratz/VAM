package com.bank.vam.fx.hedge;

import com.bank.vam.entity.payables.Payable;
import com.bank.vam.entity.payables.Payable.PayableStatus;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.entity.receivables.Receivable.ReceivableStatus;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.fx.HomeCurrency;
import com.bank.vam.fx.deal.DealStatus;
import com.bank.vam.fx.deal.FxDeal;
import com.bank.vam.fx.deal.FxDealRepository;
import com.bank.vam.fx.deal.Instrument;
import com.bank.vam.fx.deal.Side;
import com.bank.vam.fx.hedge.FxHedgeLink.Kind;
import com.bank.vam.fx.hedge.FxHedgeLinkService.CurrencyCover;
import com.bank.vam.fx.hedge.FxHedgeLinkService.DealLinks;
import com.bank.vam.fx.hedge.FxHedgeLinkService.LinkRequest;
import com.bank.vam.fx.hedge.FxHedgeLinkService.LinkView;
import com.bank.vam.repository.payables.PayableRepository;
import com.bank.vam.repository.receivables.ReceivableRepository;
import com.bank.vam.service.audit.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spec fx-hedge-links: the linking rules (R2), what a link is still worth once its invoice is paid, cancelled, netted
 * or reduced (R3), and what of the deal that leaves unbacked (R4). Repositories are in-memory maps.
 */
class FxHedgeLinkServiceTest {

    private static final UUID CORP = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.now();

    private final FxHedgeLinkRepository linkRepo = mock(FxHedgeLinkRepository.class);
    private final FxDealRepository dealRepo = mock(FxDealRepository.class);
    private final PayableRepository payableRepo = mock(PayableRepository.class);
    private final ReceivableRepository receivableRepo = mock(ReceivableRepository.class);
    private final HomeCurrency homeCurrency = mock(HomeCurrency.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final FxHedgeLinkService service =
            new FxHedgeLinkService(linkRepo, dealRepo, payableRepo, receivableRepo, homeCurrency, audit);

    private final List<FxHedgeLink> stored = new ArrayList<>();
    private final Map<UUID, FxDeal> deals = new HashMap<>();
    private final Map<UUID, Payable> payables = new HashMap<>();
    private final Map<UUID, Receivable> receivables = new HashMap<>();

    /** Buys EUR 100,000, sells USD 110,000 (EUR/USD 1.10), value in 20 days. */
    private FxDeal deal;

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void wiring() {
        when(homeCurrency.of(CORP)).thenReturn("AED");
        when(linkRepo.save(any(FxHedgeLink.class))).thenAnswer(inv -> {
            FxHedgeLink l = inv.getArgument(0);
            l.setId(UUID.randomUUID());
            stored.add(l);
            return l;
        });
        when(linkRepo.findByDealIdOrderByLinkedAtAsc(any())).thenAnswer(inv ->
                stored.stream().filter(l -> l.getDealId().equals(inv.getArgument(0))).toList());
        when(linkRepo.findByKindAndInvoiceIdIn(any(), any())).thenAnswer(inv -> stored.stream()
                .filter(l -> l.getKind() == inv.getArgument(0) && ((Collection<UUID>) inv.getArgument(1)).contains(l.getInvoiceId()))
                .toList());
        when(linkRepo.findById(any())).thenAnswer(inv ->
                stored.stream().filter(l -> l.getId().equals(inv.getArgument(0))).findFirst());
        org.mockito.Mockito.doAnswer(inv -> stored.remove(inv.<FxHedgeLink>getArgument(0))).when(linkRepo).delete(any());
        when(dealRepo.findById(any())).thenAnswer(inv -> Optional.ofNullable(deals.get(inv.<UUID>getArgument(0))));
        when(dealRepo.findAllById(any())).thenAnswer(inv -> ((Collection<UUID>) inv.getArgument(0)).stream()
                .map(deals::get).filter(java.util.Objects::nonNull).toList());
        when(payableRepo.findAllById(any())).thenAnswer(inv -> ((Collection<UUID>) inv.getArgument(0)).stream()
                .map(payables::get).filter(java.util.Objects::nonNull).toList());
        when(receivableRepo.findAllById(any())).thenAnswer(inv -> ((Collection<UUID>) inv.getArgument(0)).stream()
                .map(receivables::get).filter(java.util.Objects::nonNull).toList());
        deal = deal(Side.BUY, TODAY.plusDays(20));
    }

    private FxDeal deal(Side side, LocalDate valueDate) {
        FxDeal d = new FxDeal();
        d.setId(UUID.randomUUID());
        d.setDealRef("FXD-" + d.getId().toString().substring(0, 6).toUpperCase());
        d.setCorporateId(CORP);
        d.setInstrument(Instrument.FORWARD);
        d.setPair("EUR/USD");
        d.setSide(side);
        boolean buy = side == Side.BUY;
        d.setBuyCurrency(buy ? "EUR" : "USD");
        d.setBuyAmount(buy ? bd("100000.00") : bd("110000.00"));
        d.setSellCurrency(buy ? "USD" : "EUR");
        d.setSellAmount(buy ? bd("110000.00") : bd("100000.00"));
        d.setRate(bd("1.10"));
        d.setValueDate(valueDate);
        d.setStatus(DealStatus.ACTIVE);
        deals.put(d.getId(), d);
        return d;
    }

    private Payable payable(String ccy, String outstanding, PayableStatus status) {
        Payable p = new Payable();
        p.setId(UUID.randomUUID());
        p.setCorporateId(CORP);
        p.setInvoiceNumber("INV-" + p.getId().toString().substring(0, 4));
        p.setVendorName("Acme GmbH");
        p.setCurrencyCode(ccy);
        p.setNetAmount(bd(outstanding));
        p.setOutstandingAmount(bd(outstanding));
        p.setStatus(status);
        p.setIsIntercompany(false);
        p.setDueDate(TODAY.plusDays(25));
        payables.put(p.getId(), p);
        return p;
    }

    private Receivable receivable(String ccy, String outstanding, ReceivableStatus status) {
        Receivable r = new Receivable();
        r.setId(UUID.randomUUID());
        r.setCorporateId(CORP);
        r.setReceivableNumber("REC-" + r.getId().toString().substring(0, 4));
        r.setCustomerName("Globex Inc");
        r.setCurrencyCode(ccy);
        r.setNetAmount(bd(outstanding));
        r.setOutstandingAmount(bd(outstanding));
        r.setStatus(status);
        r.setIsIntercompany(false);
        r.setDueDate(TODAY.plusDays(25));
        receivables.put(r.getId(), r);
        return r;
    }

    private static LinkRequest pay(Payable p, String amount) {
        return new LinkRequest(Kind.PAYABLE, p.getId(), bd(amount));
    }

    private static LinkRequest rec(Receivable r, String amount) {
        return new LinkRequest(Kind.RECEIVABLE, r.getId(), bd(amount));
    }

    private DealLinks link(FxDeal d, LinkRequest... rs) {
        return service.link(CORP, d.getId(), List.of(rs));
    }

    private void refused(String message, LinkRequest... rs) {
        assertThatThrownBy(() -> link(deal, rs)).isInstanceOf(BusinessException.class).hasMessageContaining(message);
    }

    private static CurrencyCover cover(DealLinks dl, String ccy) {
        return dl.currencies().stream().filter(c -> c.currency().equals(ccy)).findFirst().orElseThrow();
    }

    // ---- linking (R2) ----

    @Test
    void aPayableIsHedgedByADealThatBuysItsCurrencyAndTheRestOfTheDealIsUnbacked() {
        Payable p = payable("EUR", "40000.00", PayableStatus.APPROVED);

        DealLinks dl = link(deal, pay(p, "40000"));

        LinkView v = dl.links().get(0);
        assertThat(v.invoiceRef()).isEqualTo(p.getInvoiceNumber());
        assertThat(v.counterparty()).isEqualTo("Acme GmbH");
        assertThat(v.linked()).isEqualByComparingTo("40000");
        assertThat(v.effective()).isEqualByComparingTo("40000");
        assertThat(v.reason()).isNull();
        assertThat(v.warning()).isNull(); // value date and due date both in 0-30 days
        assertThat(cover(dl, "EUR").remaining()).isEqualByComparingTo("100000");
        assertThat(cover(dl, "EUR").unbacked()).isEqualByComparingTo("60000");
        assertThat(dl.released()).isFalse(); // under-linked from the start is not a release
        verify(audit).record(eq(FxHedgeLinkService.LINKED), eq("FX_DEAL"), eq(deal.getId()), eq(CORP),
                eq(deal.getDealRef() + " hedges " + p.getInvoiceNumber() + " (EUR 40000.00)"), anyMap());
    }

    @Test
    void aReceivableIsHedgedByADealThatSellsItsCurrencyMeasuredInThatCurrency() {
        Receivable r = receivable("USD", "200000.00", ReceivableStatus.OPEN);
        deal.setDrawnAmount(bd("50000.00")); // half of the EUR drawn: USD 55,000 of the 110,000 remains

        refused("more than remains of it (USD 55000.00)", rec(r, "55000.01"));
        DealLinks dl = link(deal, rec(r, "55000"));

        assertThat(cover(dl, "USD").remaining()).isEqualByComparingTo("55000");
        assertThat(cover(dl, "USD").unbacked()).isEqualByComparingTo("0");
    }

    @Test
    void refusesAnInvoiceInACurrencyTheDealDoesNotTrade() {
        refused("is in GBP; " + deal.getDealRef() + " trades EUR/USD", pay(payable("GBP", "1000.00", PayableStatus.APPROVED), "1000"));
    }

    @Test
    void refusesAnInvoiceInTheCorporatesBaseCurrency() {
        when(homeCurrency.of(CORP)).thenReturn("USD");
        refused("is in USD, the corporate's base currency", rec(receivable("USD", "1000.00", ReceivableStatus.OPEN), "1000"));
    }

    @Test
    void refusesTheWrongDirection() {
        refused("is a payable in USD: it is hedged by a deal that buys USD",
                pay(payable("USD", "1000.00", PayableStatus.APPROVED), "1000"));
        refused("is a receivable in EUR: it is hedged by a deal that sells EUR",
                rec(receivable("EUR", "1000.00", ReceivableStatus.OPEN), "1000"));
    }

    @Test
    void refusesADealThatIsNoLongerLive() {
        Payable p = payable("EUR", "1000.00", PayableStatus.APPROVED);
        for (DealStatus s : List.of(DealStatus.SETTLED, DealStatus.CLOSED_OUT, DealStatus.ROLLED, DealStatus.CANCELLED)) {
            deal.setStatus(s);
            refused("is " + s + ": only a live deal", pay(p, "1000"));
        }
    }

    @Test
    void refusesMoreThanTheInvoiceOutstandingCountingOtherLiveDealsLinks() {
        Payable p = payable("EUR", "40000.00", PayableStatus.PARTIAL);
        refused("more than its outstanding EUR 40000.00", pay(p, "40000.01"));

        FxDeal other = deal(Side.BUY, TODAY.plusDays(20));
        link(other, pay(p, "30000"));
        refused("would hedge EUR 45000.00 of it, more than its outstanding EUR 40000.00", pay(p, "15000"));

        other.setStatus(DealStatus.CLOSED_OUT); // a closed deal's link no longer hedges the invoice
        assertThat(link(deal, pay(p, "15000")).links()).hasSize(1);
    }

    @Test
    void refusesMoreThanRemainsOfTheDealInThatCurrency() {
        Payable p = payable("EUR", "150000.00", PayableStatus.APPROVED);
        refused("more than remains of it (EUR 100000.00)", pay(p, "100000.01"));
    }

    @Test
    void refusesAnInvoiceThatIsNotOpenIntercompanyOrAnotherCorporates() {
        refused("is PAID: only an open invoice", pay(payable("EUR", "1000.00", PayableStatus.PAID), "1"));
        refused("is DRAFT: only an open invoice", pay(payable("EUR", "1000.00", PayableStatus.DRAFT), "1"));
        refused("is WRITTEN_OFF: only an open invoice", rec(receivable("USD", "1000.00", ReceivableStatus.WRITTEN_OFF), "1"));

        Payable ic = payable("EUR", "1000.00", PayableStatus.APPROVED);
        ic.setIsIntercompany(true);
        refused("is intercompany", pay(ic, "1"));

        Payable foreign = payable("EUR", "1000.00", PayableStatus.APPROVED);
        foreign.setCorporateId(UUID.randomUUID());
        refused("No payable " + foreign.getId() + " for this corporate", pay(foreign, "1"));
        refused("for this corporate", new LinkRequest(Kind.PAYABLE, UUID.randomUUID(), bd("1")));
    }

    @Test
    void refusesAnEmptyRequestOrAnAmountThatIsNotPositive() {
        assertThatThrownBy(() -> service.link(CORP, deal.getId(), List.of())).hasMessageContaining("at least one invoice");
        refused("more than 0", pay(payable("EUR", "1000.00", PayableStatus.APPROVED), "0"));
        refused("more than 0", pay(payable("EUR", "1000.00", PayableStatus.APPROVED), "-5"));
    }

    @Test
    void oneRefusedLineRefusesTheWholeRequest() {
        Payable good = payable("EUR", "1000.00", PayableStatus.APPROVED);
        Payable paid = payable("EUR", "1000.00", PayableStatus.PAID);

        refused(paid.getInvoiceNumber() + " is PAID", pay(good, "1000"), pay(paid, "1000"));

        assertThat(stored).isEmpty();
        verify(audit, never()).record(anyString(), anyString(), any(), any(), anyString(), anyMap());
    }

    @Test
    void aValueDateInAnotherBucketThanTheDueDateIsAllowedButFlagged() {
        FxDeal late = deal(Side.BUY, TODAY.plusDays(100));
        Payable p = payable("EUR", "1000.00", PayableStatus.APPROVED); // due in 25 days

        LinkView v = link(late, pay(p, "1000")).links().get(0);

        assertThat(v.warning()).contains("91-180 days").contains("0-30 days");
    }

    // ---- what a link is still worth (R3) and the unbacked deal (R4) ----

    @Test
    void aPaidCancelledOrNettedInvoiceLeavesNothingEffectiveAndTheDealReleased() {
        for (PayableStatus s : List.of(PayableStatus.PAID, PayableStatus.CANCELLED, PayableStatus.NETTED)) {
            stored.clear();
            Payable p = payable("EUR", "100000.00", PayableStatus.APPROVED);
            link(deal, pay(p, "100000"));

            p.setStatus(s);
            DealLinks dl = service.forDeal(CORP, deal.getId());

            assertThat(dl.links().get(0).effective()).isEqualByComparingTo("0");
            assertThat(dl.links().get(0).reason()).isEqualTo(s.name());
            assertThat(cover(dl, "EUR").unbacked()).isEqualByComparingTo("100000");
            assertThat(dl.released()).isTrue();
        }
    }

    @Test
    void aReducedInvoiceShrinksTheLinkToItsOutstanding() {
        Payable p = payable("EUR", "100000.00", PayableStatus.APPROVED);
        link(deal, pay(p, "100000"));

        p.setStatus(PayableStatus.PARTIAL);
        p.setOutstandingAmount(bd("25000.00"));
        DealLinks dl = service.forDeal(CORP, deal.getId());

        assertThat(dl.links().get(0).effective()).isEqualByComparingTo("25000");
        assertThat(dl.links().get(0).reason()).isEqualTo("REDUCED");
        assertThat(cover(dl, "EUR").linked()).isEqualByComparingTo("100000");
        assertThat(cover(dl, "EUR").effective()).isEqualByComparingTo("25000");
        assertThat(cover(dl, "EUR").unbacked()).isEqualByComparingTo("75000");
        assertThat(dl.released()).isTrue();
    }

    @Test
    void aWrittenOffReceivableLapsesItsLink() {
        Receivable r = receivable("USD", "10000.00", ReceivableStatus.OPEN);
        link(deal, rec(r, "10000"));

        r.setStatus(ReceivableStatus.WRITTEN_OFF);

        assertThat(service.forDeal(CORP, deal.getId()).links().get(0).reason()).isEqualTo("WRITTEN_OFF");
    }

    @Test
    void whatALapsedLinkReleasesCanBeRelinked() {
        Payable first = payable("EUR", "100000.00", PayableStatus.APPROVED);
        link(deal, pay(first, "100000"));
        Payable second = payable("EUR", "60000.00", PayableStatus.APPROVED);
        refused("more than remains of it", pay(second, "60000"));

        first.setStatus(PayableStatus.PAID);
        DealLinks dl = link(deal, pay(second, "60000"));

        assertThat(cover(dl, "EUR").effective()).isEqualByComparingTo("60000");
        assertThat(cover(dl, "EUR").unbacked()).isEqualByComparingTo("40000");
    }

    @Test
    void anEndedDealsLinksHedgeNothingAndLeaveNothingUnbacked() {
        Payable p = payable("EUR", "40000.00", PayableStatus.APPROVED);
        link(deal, pay(p, "40000"));

        deal.setStatus(DealStatus.SETTLED);
        DealLinks dl = service.forDeal(CORP, deal.getId());

        assertThat(dl.links().get(0).reason()).isEqualTo("DEAL_ENDED");
        assertThat(cover(dl, "EUR").unbacked()).isEqualByComparingTo("0");
        assertThat(dl.released()).isFalse();
    }

    // ---- scope, unlink, invoice lists ----

    @Test
    void aDealOfAnotherCorporateIsNotFound() {
        UUID other = UUID.randomUUID();
        Payable p = payable("EUR", "1000.00", PayableStatus.APPROVED);

        assertThatThrownBy(() -> service.forDeal(other, deal.getId())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.link(other, deal.getId(), List.of(pay(p, "1"))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.unlink(other, deal.getId(), UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(stored).isEmpty();
    }

    @Test
    void unlinkingDeletesTheLinkAndAuditsIt() {
        Payable p = payable("EUR", "1000.00", PayableStatus.APPROVED);
        UUID linkId = link(deal, pay(p, "1000")).links().get(0).id();
        FxDeal other = deal(Side.BUY, TODAY.plusDays(20));

        assertThatThrownBy(() -> service.unlink(CORP, other.getId(), linkId)).isInstanceOf(ResourceNotFoundException.class);
        DealLinks dl = service.unlink(CORP, deal.getId(), linkId);

        assertThat(dl.links()).isEmpty();
        verify(audit).record(eq(FxHedgeLinkService.UNLINKED), eq("FX_DEAL"), eq(deal.getId()), eq(CORP),
                eq(deal.getDealRef() + " no longer hedges " + p.getInvoiceNumber() + " (EUR 1000.00)"), anyMap());
    }

    @Test
    void invoiceListsGetEachInvoicesLinksWithTheirDeals() {
        Payable p = payable("EUR", "50000.00", PayableStatus.APPROVED);
        Payable unhedged = payable("EUR", "50000.00", PayableStatus.APPROVED);
        FxDeal other = deal(Side.BUY, TODAY.plusDays(20));
        link(deal, pay(p, "20000"));
        link(other, pay(p, "10000"));

        Map<UUID, List<LinkView>> byInvoice = service.forInvoices(CORP, Kind.PAYABLE, List.of(p.getId(), unhedged.getId()));

        assertThat(byInvoice).containsOnlyKeys(p.getId());
        assertThat(byInvoice.get(p.getId())).extracting(LinkView::dealRef).containsExactly(deal.getDealRef(), other.getDealRef());
        assertThat(service.forInvoices(UUID.randomUUID(), Kind.PAYABLE, List.of(p.getId()))).isEmpty();
    }

    @Test
    void theBucketFlagComparesTheLaddersBuckets() {
        LocalDate today = LocalDate.of(2026, 11, 21);
        assertThat(FxHedgeLinkService.bucketWarning(today.plusDays(10), today.plusDays(30), today)).isNull();
        assertThat(FxHedgeLinkService.bucketWarning(today.plusDays(10), today.plusDays(31), today))
                .isEqualTo("The deal's value date 2026-12-01 (0-30 days) is outside the invoice's bucket (due 2026-12-22, 31-60 days)");
    }
}
