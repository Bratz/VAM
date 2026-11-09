package com.bank.vam.collections;

import com.bank.vam.collections.CollectionLine.Status;
import com.bank.vam.collections.CollectionRoutingService.Method;
import com.bank.vam.collections.CollectionRoutingService.Preview;
import com.bank.vam.config.HomeBankProperties;
import com.bank.vam.entity.Corporate;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.iso20022.service.Iso20022RequestToPayService;
import com.bank.vam.repository.CorporateRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Submits collection runs: direct debits into one pain.008, Request to Pay for the rest. No approval step. */
@Service
@RequiredArgsConstructor
public class CollectionRunService {

    static final int DEFAULT_HOLD_BUSINESS_DAYS = 5;

    private final CollectionRoutingService routing;
    private final CollectionRunRepository runs;
    private final CollectionLineRepository lines;
    private final CustomerMandateRepository mandates;
    private final VirtualAccountRepository accounts;
    private final CorporateRepository corporates;
    private final Iso20022RequestToPayService requestToPay;
    private final HomeBankProperties homeBank;
    private final PlatformTransactionManager transactionManager;

    public record RunSummary(UUID id, String runRef, LocalDateTime createdAt, CollectionRun.Source source,
                             Map<Status, Long> lineStatuses, List<CollectionRoutingService.Total> totals,
                             boolean hasPain008, boolean completed) {
    }

    public record RunDetail(RunSummary run, List<CollectionLine> lines) {
    }

    @Transactional
    public RunDetail submit(UUID corporateId, List<UUID> receivableIds, CollectionRun.Source source) {
        Corporate corporate = corporates.findById(corporateId).orElseThrow(() -> new ResourceNotFoundException("Corporate not found: " + corporateId));
        Preview plan = routing.preview(corporateId, receivableIds);
        if (plan.lines().stream().allMatch(l -> l.method() == Method.EXCLUDED)) {
            throw new BusinessException("Nothing to collect: " + plan.lines().stream().map(l -> l.reason()).distinct().collect(Collectors.joining("; ")));
        }

        CollectionRun run = new CollectionRun();
        run.setCorporateId(corporateId);
        run.setRunRef("CR-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + random(6));
        run.setSource(source);
        run = runs.save(run);

        Map<UUID, CustomerMandate> used = mandates.findAllById(plan.lines().stream().map(CollectionRoutingService.Line::mandateId)
                .filter(Objects::nonNull).collect(Collectors.toSet())).stream().collect(Collectors.toMap(CustomerMandate::getId, Function.identity()));
        // Each Request to Pay in its own transaction: one that cannot be sent fails its line, not the run.
        TransactionTemplate own = new TransactionTemplate(transactionManager);
        own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        List<CollectionLine> made = new ArrayList<>();
        for (CollectionRoutingService.Line p : plan.lines()) {
            CollectionLine l = new CollectionLine();
            l.setRunId(run.getId());
            l.setCorporateId(corporateId);
            l.setReceivableId(p.receivableId());
            l.setReceivableNumber(p.receivableNumber());
            l.setCustomerName(p.customerName());
            l.setMethod(p.method());
            l.setAmount(p.amount());
            l.setCurrency(p.currency());
            l.setReason(p.reason());
            switch (p.method()) {
                case DIRECT_DEBIT -> {
                    CustomerMandate m = used.get(p.mandateId());
                    l.setStatus(Status.SENT);
                    l.setMandateId(m.getId());
                    l.setMandateReference(m.getMandateReference());
                    l.setSchemeCode(m.getSchemeCode());
                    l.setSequenceType(p.sequenceType());
                    l.setCollectionDate(p.collectionDate());
                    l.setEndToEndId("DD-" + random(24));
                    l.setCreditorVaId(m.getCreditorVaId());
                    l.setDebtorName(m.getDebtorName());
                    l.setDebtorIban(m.getDebtorIban());
                    l.setDebtorBic(m.getDebtorBic());
                }
                case REQUEST_TO_PAY -> {
                    try {
                        own.executeWithoutResult(s -> requestToPay.sendRequestToPay(p.receivableId()));
                        l.setStatus(Status.RTP_SENT);
                    } catch (RuntimeException e) {
                        l.setStatus(Status.FAILED);
                        l.setReason("Request to Pay could not be sent: " + e.getMessage());
                    }
                }
                case EXCLUDED -> l.setStatus(Status.EXCLUDED);
            }
            made.add(l);
        }
        made = lines.saveAll(made);

        List<CollectionLine> debits = made.stream().filter(l -> l.getMethod() == Method.DIRECT_DEBIT).toList();
        if (!debits.isEmpty()) {
            Map<UUID, String> numbers = accounts.findAllById(debits.stream().map(CollectionLine::getCreditorVaId).collect(Collectors.toSet()))
                    .stream().collect(Collectors.toMap(VirtualAccount::getId, VirtualAccount::getVaNumber));
            Map<UUID, LocalDate> signed = new HashMap<>();
            used.values().forEach(m -> { if (m.getSignedDate() != null) signed.put(m.getId(), m.getSignedDate()); });
            run.setPain008Xml(Pain008.build(run.getRunRef(), corporate.getLegalName(), homeBank.getBic(), debits, numbers, signed));
        }
        return new RunDetail(summary(run, made), made);
    }

    public List<RunSummary> list(UUID corporateId) {
        List<CollectionRun> all = runs.findByCorporateIdOrderByCreatedAtDesc(corporateId);
        Map<UUID, List<CollectionLine>> byRun = all.isEmpty() ? Map.of()
                : lines.findByRunIdIn(all.stream().map(CollectionRun::getId).toList()).stream().collect(Collectors.groupingBy(CollectionLine::getRunId));
        return all.stream().map(r -> summary(r, byRun.getOrDefault(r.getId(), List.of()))).toList();
    }

    public RunDetail get(UUID runId) {
        CollectionRun run = find(runId);
        List<CollectionLine> l = lines.findByRunIdOrderByCreatedAtAsc(runId);
        return new RunDetail(summary(run, l), l);
    }

    public CollectionRun find(UUID runId) {
        return runs.findById(runId).orElseThrow(() -> new ResourceNotFoundException("Collection run not found: " + runId));
    }

    public int holdDays(UUID corporateId) {
        Integer days = corporates.findById(corporateId).orElseThrow(() -> new ResourceNotFoundException("Corporate not found: " + corporateId))
                .getCollectionHoldBusinessDays();
        return days != null ? days : DEFAULT_HOLD_BUSINESS_DAYS;
    }

    @Transactional
    public int setHoldDays(UUID corporateId, int days) {
        if (days < 0 || days > 60) throw new BusinessException("The hold must be between 0 and 60 business days");
        Corporate c = corporates.findById(corporateId).orElseThrow(() -> new ResourceNotFoundException("Corporate not found: " + corporateId));
        c.setCollectionHoldBusinessDays(days);
        return days;
    }

    private static RunSummary summary(CollectionRun run, List<CollectionLine> l) {
        Map<Status, Long> statuses = l.stream().collect(Collectors.groupingBy(CollectionLine::getStatus, () -> new EnumMap<>(Status.class), Collectors.counting()));
        List<CollectionRoutingService.Total> totals = CollectionRoutingService.totals(l.stream()
                .map(x -> new CollectionRoutingService.Line(x.getReceivableId(), null, null, x.getAmount(), x.getCurrency(), null,
                        x.getMethod(), null, null, null, null, null, null)).toList());
        return new RunSummary(run.getId(), run.getRunRef(), run.getCreatedAt(), run.getSource(), statuses, totals,
                run.getPain008Xml() != null, l.stream().noneMatch(x -> CollectionLine.IN_FLIGHT.contains(x.getStatus())));
    }

    private static String random(int n) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, n).toUpperCase(Locale.ROOT);
    }
}
