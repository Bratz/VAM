package com.bank.vam.collections;

import com.bank.vam.collections.CollectionIntake.IntakeLine;
import com.bank.vam.collections.CollectionIntake.Resolution;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.receivables.ReceivableRepository;
import com.bank.vam.service.fileingest.ReceivableInvoiceProcessor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CollectionIntakeTest {

    private final ReceivableRepository receivables = mock(ReceivableRepository.class);
    private final VirtualAccountRepository accounts = mock(VirtualAccountRepository.class);
    private final ReceivableInvoiceProcessor invoices = mock(ReceivableInvoiceProcessor.class);
    private final CollectionIntake intake = new CollectionIntake(receivables, accounts, invoices, mock(CollectionRunService.class));

    private static final UUID CORP = UUID.randomUUID();
    private final VirtualAccount va = VirtualAccount.builder().vaNumber("VA-1").build();
    private final Receivable inv = new Receivable();
    private final UUID raised = UUID.randomUUID();
    private final UUID raised2 = UUID.randomUUID();

    CollectionIntakeTest() {
        va.setId(UUID.randomUUID());
        va.setCorporateId(CORP);
        when(accounts.findByViban(any())).thenReturn(Optional.empty());
        when(accounts.findByViban("AE11VIBAN")).thenReturn(Optional.of(va));
        when(accounts.findByVaNumber(any())).thenReturn(Optional.empty());
        VirtualAccount other = VirtualAccount.builder().vaNumber("VA-X").build();
        other.setId(UUID.randomUUID());
        other.setCorporateId(UUID.randomUUID());
        when(accounts.findByVaNumber("VA-X")).thenReturn(Optional.of(other));

        inv.setId(UUID.randomUUID());
        inv.setCorporateId(CORP);
        inv.setReceivableNumber("INV-1");
        inv.setCurrencyCode("AED");
        inv.setOutstandingAmount(new BigDecimal("100.00"));
        inv.setVirtualAccountId(va.getId());
        when(receivables.findByReceivableNumber(any())).thenReturn(Optional.empty());
        when(receivables.findByReceivableNumber("INV-1")).thenReturn(Optional.of(inv));
        when(invoices.raiseInvoice(eq(CORP), eq(va), eq("New Co"), any(), any(), eq("AED"), any(), any())).thenReturn(raised, raised2);
    }

    private static IntakeLine line(String invoice, String customer, String account, String amount) {
        return new IntakeLine(invoice, customer, null, account, new BigDecimal(amount), "aed", null, null);
    }

    @Test
    void existingInvoicesAreCollectedAndOthersRaised() {
        Resolution r = intake.resolveAll(CORP, List.of(
                line("INV-1", null, "AE11 VIBAN", "100.00"),
                line("", "New Co", "AE11VIBAN", "40.00"),
                line("INV-404", "New Co", "AE11VIBAN", "40.00"),
                line("INV-1", null, "AE11VIBAN", "100.00"),
                line("INV-1", null, "AE11VIBAN", "90.00"),
                line("INV-404", null, "AE11VIBAN", "40.00"),
                line("", "New Co", "VA-X", "40.00"),
                line("", "New Co", "NOPE", "40.00")));

        assertEquals(Map.of(1, inv.getId(), 2, raised, 3, raised2), r.invoices());
        assertEquals("The invoice is already on an earlier line", r.reasons().get(4));
        assertEquals("Invoice INV-1 has 100.00 outstanding; the line says 90.00", r.reasons().get(5));
        assertEquals("No invoice INV-404, and no customer to raise one for", r.reasons().get(6));
        assertEquals("Account VA-X belongs to another corporate", r.reasons().get(7));
        assertEquals("No account NOPE", r.reasons().get(8));
        // a new line is due today, so it collects at the first possible date; an unknown number becomes its description
        verify(invoices).raiseInvoice(eq(CORP), eq(va), eq("New Co"), any(), eq(new BigDecimal("40.00")), eq("AED"), eq(LocalDate.now()), eq(null));
        verify(invoices).raiseInvoice(eq(CORP), eq(va), eq("New Co"), any(), eq(new BigDecimal("40.00")), eq("AED"), eq(LocalDate.now()), eq("INV-404"));
    }
}
