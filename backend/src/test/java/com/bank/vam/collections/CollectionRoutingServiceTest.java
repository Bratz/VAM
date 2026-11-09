package com.bank.vam.collections;

import com.bank.vam.collections.CollectionRoutingService.Line;
import com.bank.vam.collections.CollectionRoutingService.Method;
import com.bank.vam.collections.CollectionRoutingService.Preview;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.entity.receivables.Receivable.ReceivableStatus;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.receivables.ReceivableRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CollectionRoutingServiceTest {

    private final ReceivableRepository receivables = mock(ReceivableRepository.class);
    private final CustomerMandateRepository mandates = mock(CustomerMandateRepository.class);
    private final CollectionLineRepository lines = mock(CollectionLineRepository.class);
    private final VirtualAccountRepository accounts = mock(VirtualAccountRepository.class);
    private final CollectionRoutingService routing = new CollectionRoutingService(receivables, mandates, lines, accounts);
    private static final UUID AED_ACCOUNT = UUID.randomUUID();

    @org.junit.jupiter.api.BeforeEach
    void aedAccount() {
        VirtualAccount va = VirtualAccount.builder().currencyCode("AED").build();
        va.setId(AED_ACCOUNT);
        when(accounts.findAllById(any())).thenReturn(List.of(va));
    }

    private static final UUID CORP = UUID.randomUUID();
    private static final UUID PARTY = UUID.randomUUID();
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 9);

    private Receivable invoice(String number, String amount, ReceivableStatus status, LocalDate due, UUID party) {
        Receivable r = new Receivable();
        r.setId(UUID.randomUUID());
        r.setCorporateId(CORP);
        r.setReceivableNumber(number);
        r.setOutstandingAmount(new BigDecimal(amount));
        r.setCurrencyCode("AED");
        r.setStatus(status);
        r.setDueDate(due);
        r.setCustomerPartyId(party);
        return r;
    }

    private CustomerMandate mandate(String ref, String max) {
        CustomerMandate m = new CustomerMandate();
        m.setId(UUID.randomUUID());
        m.setCorporateId(CORP);
        m.setCustomerPartyId(PARTY);
        m.setMandateReference(ref);
        m.setSchemeCode("UAE_DDS");
        m.setCurrency("AED");
        m.setValidFrom(MONDAY.minusMonths(1));
        m.setMaxPerCollection(max == null ? null : new BigDecimal(max));
        m.setCreditorVaId(AED_ACCOUNT);
        return m;
    }

    private Preview run(List<Receivable> invoices, List<CustomerMandate> ms, UUID... extraIds) {
        when(receivables.findAllById(any())).thenReturn(invoices);
        when(mandates.findByCorporateIdAndCustomerPartyIdIn(eq(CORP), any())).thenReturn(ms);
        List<UUID> ids = new java.util.ArrayList<>(invoices.stream().map(Receivable::getId).toList());
        ids.addAll(List.of(extraIds));
        return routing.preview(CORP, ids, MONDAY);
    }

    @Test
    void mandateFirstThenRequestToPayThenExcluded() {
        Receivable due = invoice("INV-1", "1000.00", ReceivableStatus.OPEN, MONDAY.plusDays(10), PARTY);
        Receivable tooBig = invoice("INV-2", "9000.00", ReceivableStatus.OVERDUE, MONDAY.minusDays(3), PARTY);
        Receivable paid = invoice("INV-3", "0.00", ReceivableStatus.PAID, null, PARTY);
        Receivable noCustomer = invoice("INV-4", "50.00", ReceivableStatus.OPEN, null, null);
        UUID missing = UUID.randomUUID();

        Preview p = run(List.of(due, tooBig, paid, noCustomer), List.of(mandate("MND-1", "5000")), missing);
        List<Line> l = p.lines();

        assertEquals(Method.DIRECT_DEBIT, l.get(0).method());
        assertEquals("MND-1", l.get(0).mandateReference());
        assertEquals(MONDAY.plusDays(10), l.get(0).collectionDate());
        assertEquals("FRST", l.get(0).sequenceType());

        assertEquals(Method.REQUEST_TO_PAY, l.get(1).method());
        assertEquals("Mandate MND-1 allows up to 5,000.00 a collection", l.get(1).reason());

        assertEquals(Method.EXCLUDED, l.get(2).method());
        assertEquals("Invoice is PAID", l.get(2).reason());
        assertEquals("Invoice has no customer to collect from", l.get(3).reason());
        assertEquals("Invoice not found", l.get(4).reason());

        assertEquals(List.of(Method.DIRECT_DEBIT, Method.REQUEST_TO_PAY, Method.EXCLUDED),
                p.totals().stream().map(CollectionRoutingService.Total::method).toList());
        assertEquals(new BigDecimal("50.00"), p.totals().get(2).amount());
    }

    @Test
    void pastDueCollectsAfterTheLeadTimeSkippingTheWeekend() {
        Receivable overdue = invoice("INV-1", "100.00", ReceivableStatus.OVERDUE, MONDAY.minusDays(20), PARTY);
        LocalDate friday = LocalDate.of(2026, 11, 13);
        when(receivables.findAllById(any())).thenReturn(List.of(overdue));
        when(mandates.findByCorporateIdAndCustomerPartyIdIn(eq(CORP), any())).thenReturn(List.of(mandate("MND-1", null)));

        Line line = routing.preview(CORP, List.of(overdue.getId()), friday).lines().get(0);

        assertEquals(LocalDate.of(2026, 11, 16), line.collectionDate()); // Friday + 1 business day = Monday
    }

    @Test
    void unusableMandatesFallToRequestToPayWithTheFirstReason() {
        Receivable inv = invoice("INV-1", "100.00", ReceivableStatus.OPEN, MONDAY.plusDays(5), PARTY);
        CustomerMandate suspended = mandate("MND-S", null);
        suspended.setStatus(CustomerMandate.Status.SUSPENDED);
        CustomerMandate usd = mandate("MND-U", null);
        usd.setCurrency("USD");
        usd.setValidFrom(MONDAY.minusMonths(2));
        CustomerMandate ended = mandate("MND-E", null);
        ended.setValidFrom(MONDAY.minusMonths(3));
        ended.setValidTo(MONDAY);

        Line line = run(List.of(inv), List.of(suspended, usd, ended)).lines().get(0);

        assertEquals(Method.REQUEST_TO_PAY, line.method());
        assertEquals("Mandate MND-S is suspended", line.reason());
        assertEquals("is in USD, the invoice in AED", CollectionRoutingService.unusable(usd, BigDecimal.TEN, "AED", MONDAY));
        assertEquals("ended on " + MONDAY, CollectionRoutingService.unusable(ended, BigDecimal.TEN, "AED", MONDAY.plusDays(1)));
        assertNull(CollectionRoutingService.unusable(mandate("OK", null), BigDecimal.TEN, "AED", MONDAY));
    }

    private CollectionLine used(CustomerMandate m, LocalDate on) {
        CollectionLine l = new CollectionLine();
        l.setMandateId(m.getId());
        l.setCollectionDate(on);
        l.setStatus(CollectionLine.Status.AVAILABLE);
        return l;
    }

    @Test
    void frequencyCapCountsEarlierCollectionsAndThisBatch() {
        CustomerMandate monthly = mandate("MND-M", null);
        monthly.setFrequencyCount(2);
        monthly.setFrequencyPeriod(CustomerMandate.Period.MONTH);
        when(lines.findByMandateIdInAndStatusIn(any(), any())).thenReturn(List.of(used(monthly, LocalDate.of(2026, 11, 2))));
        Receivable a = invoice("INV-A", "10.00", ReceivableStatus.OPEN, LocalDate.of(2026, 11, 20), PARTY);
        Receivable b = invoice("INV-B", "10.00", ReceivableStatus.OPEN, LocalDate.of(2026, 11, 25), PARTY);
        Receivable c = invoice("INV-C", "10.00", ReceivableStatus.OPEN, LocalDate.of(2026, 12, 1), PARTY);

        List<Line> l = run(List.of(a, b, c), List.of(monthly)).lines();

        assertEquals(Method.DIRECT_DEBIT, l.get(0).method());
        assertEquals("RCUR", l.get(0).sequenceType());
        assertEquals(Method.REQUEST_TO_PAY, l.get(1).method());
        assertEquals("Mandate MND-M allows 2 a month and has 2 in the one starting 2026-11-01", l.get(1).reason());
        assertEquals(Method.DIRECT_DEBIT, l.get(2).method()); // December is a new month
    }

    @Test
    void oneOffMandateCollectsOnceAndAsOoff() {
        CustomerMandate once = mandate("MND-1X", null);
        once.setMandateType(CustomerMandate.Type.ONE_OFF);
        Receivable a = invoice("INV-A", "10.00", ReceivableStatus.OPEN, MONDAY.plusDays(3), PARTY);
        Receivable b = invoice("INV-B", "10.00", ReceivableStatus.OPEN, MONDAY.plusDays(4), PARTY);

        List<Line> l = run(List.of(a, b), List.of(once)).lines();

        assertEquals("OOFF", l.get(0).sequenceType());
        assertEquals("Mandate MND-1X is one-off and has been used", l.get(1).reason());
    }

    @Test
    void anInvoiceAlreadyBeingCollectedIsLeftOut() {
        Receivable a = invoice("INV-A", "10.00", ReceivableStatus.OPEN, MONDAY.plusDays(3), PARTY);
        CollectionLine sent = new CollectionLine();
        sent.setReceivableId(a.getId());
        sent.setCollectionDate(MONDAY.plusDays(3));
        when(lines.findByReceivableIdInAndStatusIn(any(), any())).thenReturn(List.of(sent));

        Line line = run(List.of(a), List.of(mandate("MND-1", null))).lines().get(0);

        assertEquals(Method.EXCLUDED, line.method());
        assertEquals("Already being collected by direct debit on " + MONDAY.plusDays(3), line.reason());
    }

    @Test
    void receivingAccountInAnotherCurrencyFallsToRequestToPay() {
        assertEquals("pays into a USD account and the invoice is in AED",
                CollectionRoutingService.unusableNow(mandate("M", null), List.of(), MONDAY, "USD", "AED"));
    }
}
