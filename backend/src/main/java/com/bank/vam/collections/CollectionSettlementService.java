package com.bank.vam.collections;

import com.bank.vam.collections.CollectionLine.Status;
import com.bank.vam.dto.TransactionDto;
import com.bank.vam.dto.receivables.ReceivablesDto.RecordPaymentRequest;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.receivables.ReceivableRepository;
import com.bank.vam.service.TransactionService;
import com.bank.vam.service.receivables.ReceivablesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Direct debits on and after their collection date. There is no clearing link, so a debit settles on its date
 * (build step 3 adds returns): the account is credited through the usual 4-leg collection, the net amount is held
 * for the corporate's hold period (the window for returns), and the invoice is marked paid. When the hold ends
 * the money becomes available.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollectionSettlementService {

    private final CollectionLineRepository lines;
    private final CollectionRunRepository runs;
    private final CustomerMandateRepository mandates;
    private final CollectionRunService runService;
    private final TransactionService transactions;
    private final ReceivablesService receivablesService;
    private final ReceivableRepository receivables;
    private final VirtualAccountRepository accounts;
    private final PlatformTransactionManager transactionManager;

    public record Result(LocalDate asOf, int settled, int released, int notCollected, int failed) {
    }

    @Scheduled(cron = "${vam.collections.eod-cron:0 30 18 * * *}")
    void endOfDay() {
        try {
            Result r = process(LocalDate.now());
            log.info("Collections end of day: settled {}, released {}, not collected {}, failed {}", r.settled(), r.released(), r.notCollected(), r.failed());
        } catch (Exception e) {
            log.error("Collections end of day failed", e);
        }
    }

    /** Settles debits due by {@code asOf} and lifts holds that end by it. Each line in its own transaction. */
    public Result process(LocalDate asOf) {
        TransactionTemplate own = new TransactionTemplate(transactionManager);
        own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        int settled = 0, released = 0, notCollected = 0, failed = 0;
        for (UUID id : lines.dueToSettle(asOf)) {
            try {
                Status s = own.execute(t -> settle(id));
                if (s == Status.SETTLED) settled++; else notCollected++;
            } catch (RuntimeException e) {
                // stays SENT and is tried again at the next run; the reason says what went wrong
                failed++;
                log.warn("Direct debit line {} did not settle: {}", id, e.getMessage());
                own.executeWithoutResult(t -> lines.findById(id).ifPresent(l -> l.setReason("Not settled yet: " + e.getMessage())));
            }
        }
        for (UUID id : lines.dueToRelease(asOf)) {
            own.executeWithoutResult(t -> release(id, asOf));
            released++;
        }
        return new Result(asOf, settled, released, notCollected, failed);
    }

    /**
     * A bank credit (camt.054) for a direct debit still waiting to settle: settles that line now instead of on the
     * simulated date. Empty when no waiting debit has this EndToEndId, amount and currency, or when the line cannot
     * settle (its invoice was paid meanwhile) -- the caller then posts the credit as an ordinary inward payment, so
     * money that arrived is never dropped.
     */
    public Optional<CollectionLine> settleFromBank(String endToEndId, BigDecimal amount, String currency) {
        if (endToEndId == null || amount == null) return Optional.empty();
        Optional<CollectionLine> waiting = lines.findByEndToEndId(endToEndId)
                .filter(l -> l.getStatus() == Status.SENT && l.getAmount().compareTo(amount) == 0 && l.getCurrency().equalsIgnoreCase(currency));
        if (waiting.isEmpty()) return Optional.empty();
        TransactionTemplate own = new TransactionTemplate(transactionManager);
        own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Status s = own.execute(t -> settle(waiting.get().getId()));
        return s == Status.SETTLED ? lines.findById(waiting.get().getId()) : Optional.empty();
    }

    private Status settle(UUID lineId) {
        CollectionLine l = lines.findById(lineId).orElseThrow();
        Receivable invoice = receivables.findById(l.getReceivableId()).orElse(null);
        if (invoice == null || !CollectionRoutingService.COLLECTABLE.contains(invoice.getStatus())
                || invoice.getOutstandingAmount() == null || invoice.getOutstandingAmount().compareTo(l.getAmount()) < 0) {
            l.setStatus(Status.FAILED);
            l.setReason("Not collected: the invoice " + (invoice == null ? "no longer exists"
                    : "is " + invoice.getStatus() + " with " + invoice.getOutstandingAmount() + " outstanding") + " on the collection date");
            return Status.FAILED;
        }

        TransactionDto.CollectionResponse credit = transactions.processCollection(TransactionDto.CollectionRequest.builder()
                .targetVaId(l.getCreditorVaId())
                .amount(l.getAmount())
                .currencyCode(l.getCurrency())
                .valueDate(l.getCollectionDate())
                .remitterName(l.getDebtorName())
                .remitterAccount(l.getDebtorIban())
                .remitterBic(l.getDebtorBic())
                .bankReference(runs.findById(l.getRunId()).map(CollectionRun::getRunRef).orElse(null))
                .endToEndId(l.getEndToEndId())
                .invoiceReference(l.getReceivableNumber())
                .remittanceInfo("Direct debit " + l.getMandateReference() + " for " + l.getReceivableNumber())
                .externalReference(l.getMandateReference())
                .channel("DIRECT_DEBIT")
                .build());

        VirtualAccount account = accounts.findById(l.getCreditorVaId()).orElseThrow();
        account.hold(credit.getNetAmount());

        LocalDate holdUntil = CollectionRoutingService.addBusinessDays(l.getCollectionDate(), runService.holdDays(l.getCorporateId()));
        l.setStatus(Status.SETTLED);
        l.setSettledOn(l.getCollectionDate());
        l.setCreditedAmount(credit.getNetAmount());
        l.setTransactionRef(credit.getReferenceNumber());
        l.setHoldUntil(holdUntil);
        l.setReason(null);

        RecordPaymentRequest paid = new RecordPaymentRequest();
        paid.setAmount(l.getAmount());
        paid.setPaymentDate(l.getCollectionDate());
        paid.setPaymentReference(l.getEndToEndId());
        paid.setMatchType("DIRECT_DEBIT");
        paid.setTransactionId(credit.getTransactionId());
        paid.setPayerName(l.getDebtorName());
        paid.setPayerAccount(l.getDebtorIban());
        receivablesService.recordPayment(l.getReceivableId(), paid);

        mandates.findById(l.getMandateId()).filter(m -> m.getMandateType() == CustomerMandate.Type.ONE_OFF)
                .ifPresent(m -> m.setStatus(CustomerMandate.Status.USED));
        return Status.SETTLED;
    }

    private void release(UUID lineId, LocalDate asOf) {
        CollectionLine l = lines.findById(lineId).orElseThrow();
        accounts.findById(l.getCreditorVaId()).orElseThrow().releaseHold(l.getCreditedAmount());
        l.setStatus(Status.AVAILABLE);
        l.setReleasedOn(asOf);
    }
}
