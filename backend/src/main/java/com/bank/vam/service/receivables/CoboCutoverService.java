package com.bank.vam.service.receivables;

import com.bank.vam.config.CurrentUser;
import com.bank.vam.dto.viban.VibanDto.VibanCreateRequest;
import com.bank.vam.dto.viban.VibanDto.VibanResponse;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.receivables.CoboEnrolment;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.entity.receivables.Receivable.CoboRequestStatus;
import com.bank.vam.entity.receivables.Receivable.CollectionRoute;
import com.bank.vam.entity.receivables.Receivable.ReceivableStatus;
import com.bank.vam.entity.viban.Viban;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.receivables.CoboEnrolmentRepository;
import com.bank.vam.repository.receivables.ReceivableRepository;
import com.bank.vam.service.receivables.CoboEnrolmentService.EnrolmentView;
import com.bank.vam.service.viban.VibanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cut-over of COBO invoices from the old per-invoice request/approval flow to enrolment (spec D7, acceptance test 9).
 * Run per corporate after enrolling, preview first: a pending request becomes direct collection; an approved,
 * not collected one of an enrolled subsidiary moves to the cash-driven flow with a new VIBAN on the treasury's
 * collection account, otherwise it too is collected directly. Idempotent: a moved invoice carries its enrolment id.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoboCutoverService {

    private final ReceivableRepository receivables;
    private final CoboEnrolmentRepository enrolments;
    private final CoboEnrolmentService coboEnrolments;
    private final VirtualAccountRepository accounts;
    private final VibanService vibanService;

    /** One invoice and what happens (dry run) or happened to it, and why. */
    public record Item(UUID invoiceId, String invoiceNumber, String owningEntity, String currency,
                       BigDecimal outstanding, String detail) {
    }

    public record Report(UUID corporateId, boolean dryRun, List<Item> toDirect, List<Item> toNewFlow, List<Item> skipped) {
    }

    @Transactional
    public Report cutover(UUID corporateId, boolean dryRun) {
        if (corporateId == null) throw new BusinessException("Choose the corporate");
        Set<UUID> enrolmentIds = enrolments.findByCorporateIdOrderByCreatedAtDesc(corporateId).stream()
            .map(CoboEnrolment::getId).collect(Collectors.toSet());
        LocalDate today = LocalDate.now();
        List<Item> toDirect = new ArrayList<>();
        List<Item> toNewFlow = new ArrayList<>();
        List<Item> skipped = new ArrayList<>();

        for (Receivable r : receivables.findCoboByCorporateList(corporateId)) {
            if (r.getCoboRequestId() != null && enrolmentIds.contains(r.getCoboRequestId())) continue; // already new flow
            BigDecimal outstanding = outstanding(r);
            CoboRequestStatus req = r.getCoboRequestStatus();

            if (outstanding.signum() <= 0 || !r.canAcceptPayment()) {
                skipped.add(item(r, outstanding, "Not owed (" + r.getStatus() + "); left as it is"));
            } else if (req == CoboRequestStatus.PENDING_TREASURY_APPROVAL || req == CoboRequestStatus.PENDING_ENTITY_APPROVAL) {
                toDirect.add(item(r, outstanding, "COBO request was pending approval; collected directly" + payDetailsKept(r)));
                if (!dryRun) direct(r);
            } else if (req == CoboRequestStatus.APPROVED) {
                EnrolmentView e = coboEnrolments.active(r.getOwningEntityId(), r.getCurrencyCode(), today).orElse(null);
                if (e == null) {
                    toDirect.add(item(r, outstanding, "Approved, but " + entityName(r) + " is not enrolled for "
                        + r.getCurrencyCode() + "; collected directly" + payDetailsKept(r)));
                    if (!dryRun) direct(r);
                } else {
                    String name = e.collectorName() + " re: " + (r.getOwningEntityName() != null ? r.getOwningEntityName() : e.subsidiaryName());
                    toNewFlow.add(item(r, outstanding, dryRun
                        ? "Collected by " + e.collectorName() + " into " + e.collectionVaNumber() + ": new VIBAN named \""
                            + name + "\"" + (r.getPrimaryVibanId() != null ? "; old VIBAN " + r.getViban() + " will be cancelled" : "")
                        : newFlow(r, e, outstanding)));
                }
            } else {
                skipped.add(item(r, outstanding, "COBO " + req + "; left as it is"));
            }
        }
        if (!dryRun) {
            log.info("COBO cut-over for corporate {}: {} to direct, {} to the new flow, {} skipped",
                corporateId, toDirect.size(), toNewFlow.size(), skipped.size());
        }
        return new Report(corporateId, dryRun, toDirect, toNewFlow, skipped);
    }

    /** Back to direct collection on the invoice's own account and VIBAN. */
    private void direct(Receivable r) {
        r.setIsCobo(false);
        r.setCollectionRoute(CollectionRoute.DIRECT);
        r.setCoboCollectorEntityId(null);
        r.setCoboCollectorEntityCode(null);
        r.setCoboCollectorEntityName(null);
        r.setCoboRequestId(null);
        r.setCoboRequestStatus(CoboRequestStatus.NOT_REQUESTED);
        markActioned(r);
        receivables.save(r);
    }

    /** Onto the enrolment: the treasury's account, a new VIBAN named "T re: X"; the old VIBAN is cancelled. */
    private String newFlow(Receivable r, EnrolmentView e, BigDecimal outstanding) {
        VirtualAccount va = accounts.findById(e.collectionVaId())
            .orElseThrow(() -> new BusinessException("Collection account of the " + e.subsidiaryCode() + " " + e.currency()
                + " enrolment not found"));
        if (r.getOwningEntityName() == null) r.setOwningEntityName(e.subsidiaryName());
        if (r.getOwningEntityCode() == null) r.setOwningEntityCode(e.subsidiaryCode());
        r.setIsCobo(true);
        r.setCollectionRoute(CollectionRoute.COBO);
        r.setCoboCollectorEntityId(e.collectorEntityId());
        r.setCoboCollectorEntityCode(e.collectorCode());
        r.setCoboCollectorEntityName(e.collectorName());
        r.setCoboRequestId(e.id());
        r.setCoboRequestStatus(CoboRequestStatus.APPROVED);
        r.setVirtualAccountId(va.getId());
        if (va.getHierarchyNodeId() != null) r.setHierarchyNodeId(va.getHierarchyNodeId());
        if (r.getPaymentLinkToken() == null) r.setPaymentLinkToken(UUID.randomUUID().toString());
        markActioned(r);

        // As ReceivablesService.createInvoice issues a COBO invoice's VIBAN; an overdue invoice gets 30 days.
        LocalDate due = r.getDueDate();
        VibanResponse created = vibanService.createViban(va.getProgramId(), VibanCreateRequest.builder()
            .virtualAccountId(va.getId())
            .vibanType(Viban.VibanType.INVOICE)
            .referenceType(Viban.REF_TYPE_INVOICE)
            .referenceId(r.getId().toString())
            .expectedAmount(outstanding)
            .currencyCode(r.getCurrencyCode())
            .validUntil(due != null && due.isAfter(LocalDate.now()) ? due.atStartOfDay() : LocalDateTime.now().plusDays(30))
            .singleUse(false)
            .customerName(r.vibanName())
            .build());

        String old = "";
        if (r.getPrimaryVibanId() != null) {
            vibanService.updateStatus(r.getPrimaryVibanId(), Viban.STATUS_CANCELLED);
            old = "; old VIBAN " + r.getViban() + " cancelled";
        } else if (r.getViban() != null) {
            old = "; old VIBAN " + r.getViban() + " has no record to cancel, left as it is";
        }
        r.setViban(created.getViban());
        r.setPrimaryVibanId(created.getId());
        receivables.save(r);
        return "Collected by " + e.collectorName() + " into " + va.getVaNumber() + ": new VIBAN " + created.getViban()
            + " named \"" + r.vibanName() + "\"" + old;
    }

    private static void markActioned(Receivable r) {
        if (r.getStatus() == ReceivableStatus.PENDING_COBO || r.getStatus() == ReceivableStatus.COBO_APPROVED
            || r.getStatus() == ReceivableStatus.COBO_REJECTED) {
            r.setStatus(r.getPaidAmount() != null && r.getPaidAmount().signum() > 0 ? ReceivableStatus.PARTIAL : ReceivableStatus.OPEN);
        }
        r.setCoboActionedAt(LocalDateTime.now());
        r.setCoboActionedBy("cut-over by " + CurrentUser.name());
    }

    private static String payDetailsKept(Receivable r) {
        return r.getViban() != null ? "; keeps VIBAN " + r.getViban() : "";
    }

    private static BigDecimal outstanding(Receivable r) {
        if (r.getOutstandingAmount() != null) return r.getOutstandingAmount();
        BigDecimal net = r.getNetAmount() != null ? r.getNetAmount() : BigDecimal.ZERO;
        return net.subtract(r.getPaidAmount() != null ? r.getPaidAmount() : BigDecimal.ZERO);
    }

    private static String entityName(Receivable r) {
        return r.getOwningEntityName() != null ? r.getOwningEntityName()
            : r.getOwningEntityCode() != null ? r.getOwningEntityCode() : "its owning entity";
    }

    private static Item item(Receivable r, BigDecimal outstanding, String detail) {
        return new Item(r.getId(), r.getReceivableNumber(), entityName(r), r.getCurrencyCode(), outstanding, detail);
    }
}
