package com.bank.vam.service.receivables;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.entity.VirtualAccount.MirrorAccountType;
import com.bank.vam.entity.VirtualAccount.VaStatus;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.receivables.CoboEnrolment;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.receivables.CoboEnrolmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** COBO enrolment validation (spec R1-R3) and the active-enrolment lookup invoices use. */
class CoboEnrolmentServiceTest {

    private static final UUID CORPORATE = UUID.randomUUID();

    private final CoboEnrolmentRepository repo = mock(CoboEnrolmentRepository.class);
    private final LegalEntityRepository entityRepo = mock(LegalEntityRepository.class);
    private final VirtualAccountRepository vaRepo = mock(VirtualAccountRepository.class);
    private final CoboEnrolmentService service = new CoboEnrolmentService(repo, entityRepo, vaRepo);

    private final Map<UUID, LegalEntity> entities = new HashMap<>();
    private final Map<UUID, VirtualAccount> vas = new HashMap<>();
    private final List<CoboEnrolment> saved = new ArrayList<>();

    private LegalEntity treasury;
    private LegalEntity subsidiary;
    private VirtualAccount collection;
    private VirtualAccount ihbCurrent;

    @BeforeEach
    void setUp() {
        treasury = entity("TC-1", true);
        subsidiary = entity("SUB-X", false);
        collection = va(treasury, null);
        VirtualAccount icPayable = va(treasury, MirrorAccountType.IC_PAYABLE);
        ihbCurrent = va(subsidiary, MirrorAccountType.IHB_CURRENT);
        ihbCurrent.setIcPayableVaId(icPayable.getId());

        when(entityRepo.findById(any())).thenAnswer(i -> Optional.ofNullable(entities.get(i.<UUID>getArgument(0))));
        when(vaRepo.findById(any())).thenAnswer(i -> Optional.ofNullable(vas.get(i.<UUID>getArgument(0))));
        when(vaRepo.findByOwningEntityIdAndStatus(any(), any())).thenAnswer(i -> vas.values().stream()
            .filter(v -> v.getOwningEntityId().equals(i.getArgument(0)) && v.getStatus() == i.getArgument(1)).toList());
        when(repo.save(any())).thenAnswer(i -> {
            CoboEnrolment e = i.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
                saved.add(e);
            }
            return e;
        });
        when(repo.findBySubsidiaryEntityIdAndCurrency(any(), any())).thenAnswer(i -> saved.stream()
            .filter(e -> e.getSubsidiaryEntityId().equals(i.getArgument(0)) && e.getCurrency().equals(i.getArgument(1))).toList());
        when(repo.findById(any())).thenAnswer(i -> saved.stream().filter(e -> e.getId().equals(i.getArgument(0))).findFirst());
    }

    private LegalEntity entity(String code, boolean treasuryCentre) {
        LegalEntity e = new LegalEntity();
        e.setId(UUID.randomUUID());
        e.setEntityCode(code);
        e.setEntityName(code + " LLC");
        e.setCorporateId(CORPORATE);
        e.setIsTreasuryCenter(treasuryCentre);
        entities.put(e.getId(), e);
        return e;
    }

    private VirtualAccount va(LegalEntity owner, MirrorAccountType mirror) {
        VirtualAccount v = new VirtualAccount();
        v.setId(UUID.randomUUID());
        v.setVaNumber("VA-" + vas.size());
        v.setOwningEntityId(owner.getId());
        v.setMirrorAccountType(mirror);
        v.setCurrencyCode("AED");
        v.setStatus(VaStatus.ACTIVE);
        v.setAccountCategory(AccountCategory.COLLECTION);
        v.setProgramId(UUID.randomUUID());
        vas.put(v.getId(), v);
        return v;
    }

    private CoboEnrolmentService.EnrolmentView enrol(LegalEntity collector, VirtualAccount account) {
        return service.create(CORPORATE, subsidiary.getId(), "aed", collector.getId(), account.getId(), null);
    }

    @Test
    void aValidEnrolmentIsSavedAndIsTheActiveOneForTheSubsidiaryAndCurrency() {
        var view = enrol(treasury, collection);

        assertThat(view.currency()).isEqualTo("AED");
        assertThat(view.collectorName()).isEqualTo("TC-1 LLC");
        assertThat(view.collectionVaId()).isEqualTo(collection.getId());
        assertThat(view.active()).isTrue();
        assertThat(service.active(subsidiary.getId(), "AED", LocalDate.now())).map(CoboEnrolmentService.EnrolmentView::id)
            .contains(view.id());
        assertThat(service.active(subsidiary.getId(), "USD", LocalDate.now())).isEmpty();
    }

    @Test
    void aCollectorThatIsNotTheTreasuryCentreIsRefused() {
        LegalEntity holding = entity("HOLD", false);
        VirtualAccount holdingAccount = va(holding, null);

        assertThatThrownBy(() -> enrol(holding, holdingAccount))
            .isInstanceOf(BusinessException.class).hasMessageContaining("HOLD is not a treasury centre");
        assertThat(saved).isEmpty();
    }

    @Test
    void aSubsidiaryWithoutAnIhbCurrentAccountIsRefused() {
        vas.remove(ihbCurrent.getId());

        assertThatThrownBy(() -> enrol(treasury, collection))
            .isInstanceOf(BusinessException.class).hasMessageContaining("SUB-X has no active AED IHB current account");
        assertThat(saved).isEmpty();
    }

    @Test
    void anIhbCurrentAccountWithoutAnIcPayableIsRefused() {
        ihbCurrent.setIcPayableVaId(null);

        assertThatThrownBy(() -> enrol(treasury, collection))
            .isInstanceOf(BusinessException.class).hasMessageContaining("no active IC payable");
    }

    @Test
    void aSecondOpenEnrolmentForTheSameSubsidiaryAndCurrencyIsRefusedUntilTheFirstEnds() {
        var first = enrol(treasury, collection);

        assertThatThrownBy(() -> enrol(treasury, collection))
            .isInstanceOf(BusinessException.class).hasMessageContaining("already enrolled for AED");

        service.end(first.id());
        assertThat(service.active(subsidiary.getId(), "AED", LocalDate.now())).isEmpty();
        assertThat(enrol(treasury, collection).active()).isTrue();
    }

    @Test
    void aCollectionAccountOfAnotherEntityOrCurrencyOrKindIsRefused() {
        VirtualAccount subsidiarysOwn = va(subsidiary, null);
        assertThatThrownBy(() -> enrol(treasury, subsidiarysOwn))
            .isInstanceOf(BusinessException.class).hasMessageContaining("is not the treasury centre's");

        VirtualAccount usd = va(treasury, null);
        usd.setCurrencyCode("USD");
        assertThatThrownBy(() -> enrol(treasury, usd))
            .isInstanceOf(BusinessException.class).hasMessageContaining("is in USD, not AED");

        VirtualAccount settlement = va(treasury, null);
        settlement.setSettlementMark(true);
        assertThatThrownBy(() -> enrol(treasury, settlement))
            .isInstanceOf(BusinessException.class).hasMessageContaining("settlement account");

        VirtualAccount mirror = va(treasury, MirrorAccountType.IC_PAYABLE);
        assertThatThrownBy(() -> enrol(treasury, mirror))
            .isInstanceOf(BusinessException.class).hasMessageContaining("mirror account");

        assertThat(saved).isEmpty();
        assertThat(service.collectionAccounts(treasury.getId(), "AED")).extracting(CoboEnrolmentService.CollectionAccount::id)
            .containsExactly(collection.getId());
    }
}
