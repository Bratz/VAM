package com.bank.vam.collections;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.service.VirtualAccountService;
import com.bank.vam.repository.receivables.ReceivableRepository;
import com.bank.vam.service.fileingest.ReceivableInvoiceProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Turns collection lines from a file or the corporate's ERP into invoices to collect. A line naming an existing
 * invoice of the corporate collects that invoice, once its amount, currency and account agree with it. A line
 * naming none raises the invoice first (the customer found by legal name or onboarded, as for "Raise Invoices"),
 * due on the line's date or today, so it collects at the first possible date.
 */
@Service
@RequiredArgsConstructor
public class CollectionIntake {

    private final ReceivableRepository receivables;
    private final VirtualAccountRepository accounts;
    private final VirtualAccountService virtualAccounts;
    private final ReceivableInvoiceProcessor invoices;
    private final CollectionRunService runs;

    /** {@code account}: the virtual IBAN or account number the money is paid into. */
    public record IntakeLine(String invoiceNumber, String customer, String customerAccount, String account,
                             BigDecimal amount, String currency, LocalDate dueDate, String description) {
    }

    public record Rejected(int line, String reason) {
    }

    /** The run sent, and the lines that could not be turned into an invoice to collect (numbered from 1). */
    public record IntakeResult(CollectionRunService.RunDetail run, List<Rejected> rejected) {
    }

    /** Line number (from 1) → the invoice it collects, for lines that resolved; line number → why, for the rest. */
    public record Resolution(Map<Integer, UUID> invoices, Map<Integer, String> reasons) {
    }

    /** Resolves every line, then sends the ones that resolved as one run. */
    public IntakeResult collect(UUID corporateId, List<IntakeLine> lines, CollectionRun.Source source) {
        Resolution res = resolveAll(corporateId, lines);
        List<Rejected> rejected = res.reasons().entrySet().stream().map(e -> new Rejected(e.getKey(), e.getValue())).toList();
        if (res.invoices().isEmpty()) {
            throw new BusinessException("Nothing to collect: " + rejected.stream().map(r -> "line " + r.line() + ": " + r.reason()).toList());
        }
        return new IntakeResult(runs.submit(corporateId, new ArrayList<>(res.invoices().values()), source), rejected);
    }

    public Resolution resolveAll(UUID corporateId, List<IntakeLine> lines) {
        Map<Integer, UUID> invoices = new LinkedHashMap<>();
        Map<Integer, String> reasons = new LinkedHashMap<>();
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            try {
                UUID id = resolve(corporateId, lines.get(i));
                if (!seen.add(id)) throw new BusinessException("The invoice is already on an earlier line");
                invoices.put(i + 1, id);
            } catch (RuntimeException e) {
                reasons.put(i + 1, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        }
        return new Resolution(invoices, reasons);
    }

    UUID resolve(UUID corporateId, IntakeLine l) {
        if (l.amount() == null || l.amount().signum() <= 0) throw new BusinessException("Amount must be positive");
        if (l.currency() == null || !l.currency().trim().matches("[A-Za-z]{3}")) throw new BusinessException("Currency must be a 3-letter code");
        VirtualAccount va = account(corporateId, l.account());
        String currency = l.currency().trim().toUpperCase(Locale.ROOT);

        String number = l.invoiceNumber() == null ? "" : l.invoiceNumber().trim();
        Optional<Receivable> existing = number.isEmpty() ? Optional.empty()
                : receivables.findByReceivableNumber(number).filter(r -> corporateId.equals(r.getCorporateId()));
        if (existing.isPresent()) {
            Receivable r = existing.get();
            if (!currency.equals(r.getCurrencyCode())) {
                throw new BusinessException("Invoice " + number + " is in " + r.getCurrencyCode() + ", the line in " + currency);
            }
            if (r.getOutstandingAmount() == null || r.getOutstandingAmount().compareTo(l.amount()) != 0) {
                throw new BusinessException("Invoice " + number + " has " + r.getOutstandingAmount() + " outstanding; the line says " + l.amount());
            }
            if (r.getVirtualAccountId() != null && !r.getVirtualAccountId().equals(va.getId())) {
                throw new BusinessException("Invoice " + number + " is paid into another account than " + l.account());
            }
            return r.getId();
        }
        if (l.customer() == null || l.customer().isBlank()) {
            throw new BusinessException(number.isEmpty() ? "No invoice number and no customer to raise one for"
                    : "No invoice " + number + ", and no customer to raise one for");
        }
        return invoices.raiseInvoice(corporateId, va, l.customer().trim(), l.customerAccount(), l.amount(), currency,
                l.dueDate() != null ? l.dueDate() : LocalDate.now(),
                l.description() != null ? l.description() : (number.isEmpty() ? null : number));
    }

    private VirtualAccount account(UUID corporateId, String ref) {
        if (ref == null || ref.isBlank()) throw new BusinessException("No account to collect into");
        String r = ref.replaceAll("\\s+", "");
        VirtualAccount va;
        try {
            va = virtualAccounts.getByViban(r); // a virtual IBAN, from the vibans table
        } catch (ResourceNotFoundException notAViban) {
            va = accounts.findByVaNumber(r).orElseThrow(() -> new BusinessException("No account " + ref));
        }
        if (!corporateId.equals(va.getCorporateId())) throw new BusinessException("Account " + ref + " belongs to another corporate");
        return va;
    }
}
