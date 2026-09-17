package com.bank.vam.service.fileingest;

import com.bank.vam.dto.party.PartyDto.CreatePartyRequest;
import com.bank.vam.dto.party.PartyDto.PartyResponse;
import com.bank.vam.dto.receivables.ReceivablesDto.CreateInvoiceRequest;
import com.bank.vam.dto.receivables.ReceivablesDto.InvoiceResponse;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.fileingest.IngestJob;
import com.bank.vam.entity.fileingest.RowStatus;
import com.bank.vam.entity.fileingest.StagedTransaction;
import com.bank.vam.entity.party.Party;
import com.bank.vam.repository.fileingest.IngestJobRepository;
import com.bank.vam.repository.fileingest.StagedTransactionRepository;
import com.bank.vam.repository.party.PartyRepository;
import com.bank.vam.service.VirtualAccountService;
import com.bank.vam.service.party.PartyService;
import com.bank.vam.service.receivables.ReceivablesService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Live-processing stage for RECEIVABLES_INVOICE: the opposite direction from
 * {@link ReceivablesProcessor} -- raises a NEW Receivable (DRAFT -> OPEN, awaiting payment) per
 * row instead of posting a payment that already happened. Each row's {@code viban} must resolve
 * to a real, already-provisioned VA (same requirement ReceivablesProcessor/PayablesProcessor
 * already have) -- ReceivablesService.createInvoice()'s own "auto-generate a VIBAN" option
 * fabricates a string that's never saved to the real Viban repository, so it could never actually
 * be matched by a later real collection; deliberately not used here.
 *
 * <p>Each row's debtor is resolved against the corporate's existing customer/Party registry by
 * exact legal name, or auto-onboarded (see {@link #resolveOrCreateParty}) -- otherwise a
 * bulk-raised invoice would be linked to no real customer record at all.
 *
 * <p>ponytail: dueDate defaults to +30 days, same as PayablesProcessor -- TransformedRow has no
 * date field of its own, and nothing downstream gates on it.
 */
@Component
public class ReceivableInvoiceProcessor implements DomainProcessor {

    private final StagedTransactionRepository repository;
    private final IngestJobRepository ingestJobRepository;
    private final VirtualAccountService virtualAccountService;
    private final ReceivablesService receivablesService;
    private final PartyRepository partyRepository;
    private final PartyService partyService;

    public ReceivableInvoiceProcessor(StagedTransactionRepository repository, IngestJobRepository ingestJobRepository,
                                       VirtualAccountService virtualAccountService, ReceivablesService receivablesService,
                                       PartyRepository partyRepository, PartyService partyService) {
        this.repository = repository;
        this.ingestJobRepository = ingestJobRepository;
        this.virtualAccountService = virtualAccountService;
        this.receivablesService = receivablesService;
        this.partyRepository = partyRepository;
        this.partyService = partyService;
    }

    @Override
    public void process(UUID ingestJobId, List<TransformedRow> rows) {
        UUID corporateId = resolveCorporateId(ingestJobId);
        Map<Integer, TransformedRow> byRowNumber = rows.stream()
                .collect(Collectors.toMap(TransformedRow::sourceRowNumber, Function.identity()));

        List<StagedTransaction> readyRows = repository.findByIngestJobIdAndStatus(ingestJobId, RowStatus.READY);
        for (StagedTransaction staged : readyRows) {
            TransformedRow row = byRowNumber.get(staged.getSourceRowNumber());
            try {
                VirtualAccount va = virtualAccountService.getByViban(row.viban());
                UUID partyId = resolveOrCreateParty(corporateId, row.debtorName());

                CreateInvoiceRequest request = CreateInvoiceRequest.builder()
                        .customerId(partyId)
                        .customerName(row.debtorName())
                        .customerVaNumber(row.debtorAccount())
                        .targetVaId(va.getId())
                        .amount(row.amount())
                        .currencyCode(row.currency())
                        .dueDate(LocalDate.now().plusDays(30))
                        .description(row.remittanceInformation())
                        .build();
                InvoiceResponse created = receivablesService.createInvoice(corporateId, request);

                staged.setStatus(RowStatus.PROCESSED);
                staged.setProcessedEntityId(created.getId());
                staged.setReason(null);
            } catch (Exception e) {
                staged.setStatus(RowStatus.FAILED);
                staged.setReason(e.getMessage());
            }
            repository.save(staged);
        }
    }

    /** Look up the debtor as an existing customer Party under this corporate; if none exists,
     * auto-onboard one -- this IS "KYCC details created automatically": createParty() already
     * defaults a new Party to kycStatus=PENDING, this app's real (if informally named) KYCC
     * model. Exact-match on legalName so a re-uploaded file for the same customer reliably finds
     * the SAME Party rather than creating a duplicate on every run. */
    private UUID resolveOrCreateParty(UUID corporateId, String debtorName) {
        return partyRepository.findByCorporateIdAndLegalNameIgnoreCase(corporateId, debtorName)
                .map(Party::getId)
                .orElseGet(() -> {
                    PartyResponse created = partyService.createParty(corporateId, CreatePartyRequest.builder()
                            .partyType("COMPANY") // TransformedRow has no signal to distinguish
                                                   // INDIVIDUAL vs COMPANY; documented default.
                            .legalName(debtorName)
                            .roles(Set.of("CUSTOMER"))
                            .build());
                    return created.getId();
                });
    }

    private UUID resolveCorporateId(UUID ingestJobId) {
        IngestJob job = ingestJobRepository.findById(ingestJobId).orElse(null);
        if (job == null || job.getCustomerId() == null) {
            return null;
        }
        try {
            return UUID.fromString(job.getCustomerId());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
