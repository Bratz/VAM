package com.bank.vam.service.receivables;

import com.bank.vam.dto.viban.VibanDto.VibanCreateRequest;
import com.bank.vam.dto.viban.VibanDto.VibanResponse;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.receivables.CoboEnrolment;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.entity.receivables.Receivable.CoboRequestStatus;
import com.bank.vam.entity.receivables.Receivable.CollectionRoute;
import com.bank.vam.entity.receivables.Receivable.ReceivableStatus;
import com.bank.vam.entity.viban.Viban;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.receivables.CoboEnrolmentRepository;
import com.bank.vam.repository.receivables.ReceivableRepository;
import com.bank.vam.service.viban.VibanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** COBO cut-over (spec D7, acceptance test 9): pending -> direct, approved -> enrolment or direct, the rest untouched. */
class CoboCutoverServiceTest {

    private static final UUID CORPORATE = UUID.randomUUID();

    private final ReceivableRepository receivableRepo = mock(ReceivableRepository.class);
    private final CoboEnrolmentRepository enrolmentRepo = mock(CoboEnrolmentRepository.class);
    private final LegalEntityRepository entityRepo = mock(LegalEntityRepository.class);
    private final VirtualAccountRepository vaRepo = mock(VirtualAccountRepository.class);
    private final VibanService vibanService = mock(VibanService.class);
    private final CoboCutoverService service = new CoboCutoverService(receivableRepo, enrolmentRepo,
        new CoboEnrolmentService(enrolmentRepo, entityRepo, vaRepo), vaRepo, vibanService);

    private final Map<UUID, LegalEntity> entities = new HashMap<>();
    private final List<CoboEnrolment> enrolments = new ArrayList<>();
    private final List<Receivable> invoices = new ArrayList<>();

    private LegalEntity treasury;
    private LegalEntity enrolled;
    private LegalEntity notEnrolled;
    private VirtualAccount collection;
    private CoboEnrolment enrolment;

    private Receivable pending;
    private Receivable approvedEnrolled;
    private Receivable approvedNotEnrolled;
    private Receivable paid;
    private Receivable collected;
    private Receivable alreadyNew;

    @BeforeEach
    void setUp() {
        treasury = entity("TC");
        enrolled = entity("SUB-X");
        notEnrolled = entity("SUB-Y");
        collection = new VirtualAccount();
        collection.setId(UUID.randomUUID());
        collection.setVaNumber("VA-TC-AED");
        collection.setProgramId(UUID.randomUUID());
        collection.setHierarchyNodeId(UUID.randomUUID());

        enrolment = CoboEnrolment.builder().corporateId(CORPORATE).subsidiaryEntityId(enrolled.getId()).currency("AED")
            .collectorEntityId(treasury.getId()).collectionVaId(collection.getId())
            .effectiveFrom(LocalDate.now().minusDays(1)).build();
        enrolment.setId(UUID.randomUUID());
        enrolments.add(enrolment);

        when(entityRepo.findById(any())).thenAnswer(i -> Optional.ofNullable(entities.get(i.<UUID>getArgument(0))));
        when(vaRepo.findById(collection.getId())).thenReturn(Optional.of(collection));
        when(enrolmentRepo.findByCorporateIdOrderByCreatedAtDesc(CORPORATE)).thenReturn(enrolments);
        when(enrolmentRepo.findBySubsidiaryEntityIdAndCurrency(any(), any())).thenAnswer(i -> enrolments.stream()
            .filter(e -> e.getSubsidiaryEntityId().equals(i.getArgument(0)) && e.getCurrency().equals(i.getArgument(1))).toList());
        // As the real query: isCobo = true
        when(receivableRepo.findCoboByCorporateList(CORPORATE)).thenAnswer(i -> invoices.stream()
            .filter(r -> Boolean.TRUE.equals(r.getIsCobo())).toList());
        when(receivableRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(vibanService.createViban(any(), any())).thenAnswer(i -> VibanResponse.builder()
            .id(UUID.randomUUID()).viban("AE-NEW-" + UUID.randomUUID().toString().substring(0, 6)).build());

        pending = legacy("INV-P", enrolled, CoboRequestStatus.PENDING_TREASURY_APPROVAL, ReceivableStatus.PENDING_COBO);
        approvedEnrolled = legacy("INV-A", enrolled, CoboRequestStatus.APPROVED, ReceivableStatus.COBO_APPROVED);
        approvedEnrolled.setOwningEntityName(null); // old-flow invoices may lack it
        approvedNotEnrolled = legacy("INV-N", notEnrolled, CoboRequestStatus.APPROVED, ReceivableStatus.COBO_APPROVED);
        paid = legacy("INV-PAID", enrolled, CoboRequestStatus.APPROVED, ReceivableStatus.PAID);
        paid.setPaidAmount(paid.getNetAmount());
        paid.setOutstandingAmount(BigDecimal.ZERO);
        collected = legacy("INV-C", enrolled, CoboRequestStatus.COLLECTED, ReceivableStatus.PAID);
        collected.setOutstandingAmount(BigDecimal.ZERO);
        alreadyNew = legacy("INV-NEW", enrolled, CoboRequestStatus.APPROVED, ReceivableStatus.OPEN);
        alreadyNew.setCoboRequestId(enrolment.getId());
    }

    private LegalEntity entity(String code) {
        LegalEntity e = new LegalEntity();
        e.setId(UUID.randomUUID());
        e.setEntityCode(code);
        e.setEntityName(code + " LLC");
        e.setCorporateId(CORPORATE);
        entities.put(e.getId(), e);
        return e;
    }

    private Receivable legacy(String number, LegalEntity owner, CoboRequestStatus req, ReceivableStatus status) {
        Receivable r = Receivable.builder()
            .receivableNumber(number).corporateId(CORPORATE)
            .owningEntityId(owner.getId()).owningEntityCode(owner.getEntityCode()).owningEntityName(owner.getEntityName())
            .customerName("Acme").currencyCode("AED")
            .grossAmount(new BigDecimal("100")).netAmount(new BigDecimal("100"))
            .paidAmount(BigDecimal.ZERO).outstandingAmount(new BigDecimal("100"))
            .virtualAccountId(UUID.randomUUID()).primaryVibanId(UUID.randomUUID()).viban("AE-OLD-" + number)
            .paymentLinkToken("tok-" + number)
            .status(status).collectionRoute(CollectionRoute.COBO)
            .isCobo(true).coboCollectorEntityId(treasury.getId()).coboCollectorEntityCode("TC").coboCollectorEntityName("TC LLC")
            .coboRequestId(UUID.randomUUID()).coboRequestStatus(req)
            .build();
        r.setId(UUID.randomUUID());
        invoices.add(r);
        return r;
    }

    private static List<String> numbers(List<CoboCutoverService.Item> items) {
        return items.stream().map(CoboCutoverService.Item::invoiceNumber).sorted().toList();
    }

    @Test
    void dryRunReportsEachOutcomeAndChangesNothing() {
        UUID oldVa = approvedEnrolled.getVirtualAccountId();

        CoboCutoverService.Report report = service.cutover(CORPORATE, true);

        assertThat(numbers(report.toDirect())).containsExactly("INV-N", "INV-P");
        assertThat(numbers(report.toNewFlow())).containsExactly("INV-A");
        assertThat(numbers(report.skipped())).containsExactly("INV-C", "INV-PAID");
        assertThat(report.toNewFlow().get(0).detail()).contains("VA-TC-AED").contains("TC LLC re: SUB-X LLC");
        verify(receivableRepo, never()).save(any());
        verifyNoInteractions(vibanService);
        assertThat(pending.getIsCobo()).isTrue();
        assertThat(approvedEnrolled.getVirtualAccountId()).isEqualTo(oldVa);
        assertThat(approvedEnrolled.getOwningEntityName()).isNull();
    }

    @Test
    void realRunMovesEachInvoiceAndASecondRunChangesNothing() {
        UUID oldVibanOfPending = pending.getPrimaryVibanId();
        UUID oldVibanOfApproved = approvedEnrolled.getPrimaryVibanId();

        service.cutover(CORPORATE, false);

        // pending -> direct, own account and VIBAN kept
        assertThat(pending.getIsCobo()).isFalse();
        assertThat(pending.getCollectionRoute()).isEqualTo(CollectionRoute.DIRECT);
        assertThat(pending.getCoboCollectorEntityId()).isNull();
        assertThat(pending.getCoboRequestStatus()).isEqualTo(CoboRequestStatus.NOT_REQUESTED);
        assertThat(pending.getStatus()).isEqualTo(ReceivableStatus.OPEN);
        assertThat(pending.getPrimaryVibanId()).isEqualTo(oldVibanOfPending);

        // approved + enrolled -> new flow on the treasury's account with a new VIBAN named "T re: X"
        ArgumentCaptor<VibanCreateRequest> req = ArgumentCaptor.forClass(VibanCreateRequest.class);
        verify(vibanService).createViban(eq(collection.getProgramId()), req.capture());
        assertThat(req.getValue().getVirtualAccountId()).isEqualTo(collection.getId());
        assertThat(req.getValue().getCustomerName()).isEqualTo("TC LLC re: SUB-X LLC");
        assertThat(req.getValue().getReferenceId()).isEqualTo(approvedEnrolled.getId().toString());
        assertThat(approvedEnrolled.getCoboRequestId()).isEqualTo(enrolment.getId());
        assertThat(approvedEnrolled.getVirtualAccountId()).isEqualTo(collection.getId());
        assertThat(approvedEnrolled.getOwningEntityName()).isEqualTo("SUB-X LLC");
        assertThat(approvedEnrolled.getViban()).startsWith("AE-NEW-");
        assertThat(approvedEnrolled.getPrimaryVibanId()).isNotEqualTo(oldVibanOfApproved);
        assertThat(approvedEnrolled.getPaymentLinkToken()).isEqualTo("tok-INV-A");
        assertThat(approvedEnrolled.getStatus()).isEqualTo(ReceivableStatus.OPEN);
        verify(vibanService).updateStatus(oldVibanOfApproved, Viban.STATUS_CANCELLED);

        // approved + not enrolled -> direct
        assertThat(approvedNotEnrolled.getIsCobo()).isFalse();
        assertThat(approvedNotEnrolled.getCollectionRoute()).isEqualTo(CollectionRoute.DIRECT);

        // paid / collected untouched
        assertThat(paid.getIsCobo()).isTrue();
        assertThat(collected.getCoboRequestStatus()).isEqualTo(CoboRequestStatus.COLLECTED);
        assertThat(alreadyNew.getViban()).isEqualTo("AE-OLD-INV-NEW");

        clearInvocations(receivableRepo, vibanService);
        CoboCutoverService.Report again = service.cutover(CORPORATE, false);
        assertThat(again.toDirect()).isEmpty();
        assertThat(again.toNewFlow()).isEmpty();
        verify(receivableRepo, never()).save(any());
        verifyNoInteractions(vibanService);
    }
}
