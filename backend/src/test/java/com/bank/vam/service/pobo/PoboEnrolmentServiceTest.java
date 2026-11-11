package com.bank.vam.service.pobo;

import com.bank.vam.entity.PhysicalAccount;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.entity.VirtualAccount.MirrorAccountType;
import com.bank.vam.entity.VirtualAccount.VaStatus;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.pobo.PoboEnrolment;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.PhysicalAccountRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.pobo.PoboEnrolmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** POBO enrolment validation (spec R1-R2, D2), the route payments take from it, and the D7 headroom rule. */
class PoboEnrolmentServiceTest {

    private static final UUID CORPORATE = UUID.randomUUID();

    private final PoboEnrolmentRepository repo = mock(PoboEnrolmentRepository.class);
    private final LegalEntityRepository entityRepo = mock(LegalEntityRepository.class);
    private final VirtualAccountRepository vaRepo = mock(VirtualAccountRepository.class);
    private final PhysicalAccountRepository paRepo = mock(PhysicalAccountRepository.class);
    private final PoboEnrolmentService service = new PoboEnrolmentService(repo, entityRepo, vaRepo, paRepo);

    private final Map<UUID, LegalEntity> entities = new HashMap<>();
    private final Map<UUID, VirtualAccount> vas = new HashMap<>();
    private final Map<UUID, PhysicalAccount> banks = new HashMap<>();
    private final List<PoboEnrolment> saved = new ArrayList<>();

    private LegalEntity treasury;
    private LegalEntity subsidiary;
    private VirtualAccount payment;
    private VirtualAccount ihbCurrent;

    @BeforeEach
    void setUp() {
        treasury = entity("TC-1", true);
        subsidiary = entity("SUB-X", false);
        payment = bankBacked(va(treasury, null), "AE070331234567890123456");
        VirtualAccount ihbSettlement = va(subsidiary, MirrorAccountType.IHB_SETTLEMENT);
        VirtualAccount icReceivable = va(treasury, MirrorAccountType.IC_RECEIVABLE);
        ihbCurrent = va(subsidiary, MirrorAccountType.IHB_CURRENT);
        ihbCurrent.setIhbParticipant(true);
        ihbCurrent.setIhbSettlementVaId(ihbSettlement.getId());
        ihbCurrent.setIcReceivableVaId(icReceivable.getId());
        ihbCurrent.setOwningEntityCode("SUB-X");
        ihbCurrent.setCurrentBalance(new BigDecimal("-200"));
        ihbCurrent.setEffectiveCreditLimit(new BigDecimal("1000"));

        when(entityRepo.findById(any())).thenAnswer(i -> Optional.ofNullable(entities.get(i.<UUID>getArgument(0))));
        when(vaRepo.findById(any())).thenAnswer(i -> Optional.ofNullable(vas.get(i.<UUID>getArgument(0))));
        when(paRepo.findById(any())).thenAnswer(i -> Optional.ofNullable(banks.get(i.<UUID>getArgument(0))));
        when(vaRepo.findByOwningEntityIdAndStatus(any(), any())).thenAnswer(i -> vas.values().stream()
            .filter(v -> i.getArgument(0).equals(v.getOwningEntityId()) && v.getStatus() == i.getArgument(1)).toList());
        when(vaRepo.findByLinkedPhysicalAccountIdAndAccountCategory(any(), eq(AccountCategory.PHYSICAL_MIRROR))).thenAnswer(i ->
            vas.values().stream().filter(v -> v.getAccountCategory() == AccountCategory.PHYSICAL_MIRROR
                && i.getArgument(0).equals(v.getLinkedPhysicalAccountId())).findFirst());
        when(repo.save(any())).thenAnswer(i -> {
            PoboEnrolment e = i.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
                saved.add(e);
            }
            return e;
        });
        when(repo.findBySubsidiaryEntityId(any())).thenAnswer(i -> saved.stream()
            .filter(e -> e.getSubsidiaryEntityId().equals(i.getArgument(0))).toList());
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
        v.setAccountCategory(AccountCategory.TRANSACTION);
        v.setProgramId(UUID.randomUUID());
        vas.put(v.getId(), v);
        return v;
    }

    /** {@code v} on a bank account with {@code iban}, whose shadow is in v's program. */
    private VirtualAccount bankBacked(VirtualAccount v, String iban) {
        PhysicalAccount bank = new PhysicalAccount();
        bank.setId(UUID.randomUUID());
        bank.setIban(iban);
        bank.setStatus(PhysicalAccount.AccountStatus.ACTIVE);
        banks.put(bank.getId(), bank);
        v.setPhysicalAccountId(bank.getId());
        VirtualAccount shadow = new VirtualAccount();
        shadow.setId(UUID.randomUUID());
        shadow.setAccountCategory(AccountCategory.PHYSICAL_MIRROR);
        shadow.setLinkedPhysicalAccountId(bank.getId());
        shadow.setProgramId(v.getProgramId());
        shadow.setCurrencyCode(v.getCurrencyCode());
        shadow.setStatus(VaStatus.ACTIVE);
        vas.put(shadow.getId(), shadow);
        return v;
    }

    private PoboEnrolmentService.EnrolmentView enrol(LegalEntity payer, VirtualAccount account) {
        return service.create(CORPORATE, subsidiary.getId(), "aed", payer.getId(), account.getId(), null);
    }

    @Test
    void aValidEnrolmentIsSavedAndRoutesTheSubsidiarysPaymentsInThatCurrency() {
        var view = enrol(treasury, payment);

        assertThat(view.currency()).isEqualTo("AED");
        assertThat(view.payerName()).isEqualTo("TC-1 LLC");
        assertThat(view.paymentIban()).isEqualTo("AE070331234567890123456");
        assertThat(view.ihbCurrentVaId()).isEqualTo(ihbCurrent.getId());
        assertThat(view.headroom()).isEqualByComparingTo("800");
        assertThat(view.active()).isTrue();

        var route = service.route(subsidiary.getId(), "AED", LocalDate.now()).orElseThrow();
        assertThat(route.payer().getId()).isEqualTo(treasury.getId());
        assertThat(route.paymentVa().getId()).isEqualTo(payment.getId());
        assertThat(route.ihbCurrent().getId()).isEqualTo(ihbCurrent.getId());
        assertThat(service.route(subsidiary.getId(), "USD", LocalDate.now())).isEmpty();
        assertThat(service.notEnrolledReason(subsidiary.getId(), "USD", LocalDate.now()))
            .isEqualTo("Paid directly: SUB-X is not enrolled for USD (only AED)");
        assertThat(service.notEnrolledReason(treasury.getId(), "USD", LocalDate.now())).isNull();
    }

    @Test
    void aPayerThatIsNotTheTreasuryCentreIsRefused() {
        LegalEntity holding = entity("HOLD", false);
        VirtualAccount holdingAccount = bankBacked(va(holding, null), "AE000000000000000000001");

        assertThatThrownBy(() -> enrol(holding, holdingAccount))
            .isInstanceOf(BusinessException.class).hasMessageContaining("HOLD is not a treasury centre");
        assertThat(saved).isEmpty();
    }

    @Test
    void theTreasuryCentreCannotBeEnrolledForItself() {
        assertThatThrownBy(() -> service.create(CORPORATE, treasury.getId(), "AED", treasury.getId(), payment.getId(), null))
            .isInstanceOf(BusinessException.class).hasMessageContaining("cannot pay on its own behalf");
    }

    @Test
    void aSubsidiaryWithoutAnIhbCurrentAccountOrItsInHouseLinksIsRefused() {
        ihbCurrent.setIcReceivableVaId(null);
        assertThatThrownBy(() -> enrol(treasury, payment))
            .isInstanceOf(BusinessException.class).hasMessageContaining("no active IC receivable");

        ihbCurrent.setIhbSettlementVaId(null);
        assertThatThrownBy(() -> enrol(treasury, payment))
            .isInstanceOf(BusinessException.class).hasMessageContaining("no active IHB settlement account");

        vas.remove(ihbCurrent.getId());
        assertThatThrownBy(() -> enrol(treasury, payment))
            .isInstanceOf(BusinessException.class).hasMessageContaining("SUB-X has no active AED IHB current account");
        assertThat(saved).isEmpty();
    }

    @Test
    void aSecondOpenEnrolmentIsRefusedUntilTheFirstEnds() {
        var first = enrol(treasury, payment);

        assertThatThrownBy(() -> enrol(treasury, payment))
            .isInstanceOf(BusinessException.class).hasMessageContaining("already enrolled for AED");

        service.end(first.id());
        assertThat(service.route(subsidiary.getId(), "AED", LocalDate.now())).isEmpty();
        assertThat(enrol(treasury, payment).active()).isTrue();
    }

    @Test
    void aPaymentAccountMustBeTheTreasurysBankBackedAedAccount() {
        VirtualAccount subsidiarysOwn = bankBacked(va(subsidiary, null), "AE000000000000000000002");
        assertThatThrownBy(() -> enrol(treasury, subsidiarysOwn)).hasMessageContaining("is not the treasury centre's");

        VirtualAccount usd = bankBacked(va(treasury, null), "AE000000000000000000003");
        usd.setCurrencyCode("USD");
        assertThatThrownBy(() -> enrol(treasury, usd)).hasMessageContaining("is in USD, not AED");

        VirtualAccount settlement = bankBacked(va(treasury, null), "AE000000000000000000004");
        settlement.setSettlementMark(true);
        assertThatThrownBy(() -> enrol(treasury, settlement)).hasMessageContaining("settlement account");

        VirtualAccount mirror = bankBacked(va(treasury, MirrorAccountType.IC_RECEIVABLE), "AE000000000000000000005");
        assertThatThrownBy(() -> enrol(treasury, mirror)).hasMessageContaining("in-house account");

        VirtualAccount noIban = bankBacked(va(treasury, null), "");
        assertThatThrownBy(() -> enrol(treasury, noIban)).hasMessageContaining("no bank account with an IBAN");

        VirtualAccount noShadow = va(treasury, null);
        PhysicalAccount bank = new PhysicalAccount();
        bank.setId(UUID.randomUUID());
        bank.setIban("AE000000000000000000006");
        bank.setStatus(PhysicalAccount.AccountStatus.ACTIVE);
        banks.put(bank.getId(), bank);
        noShadow.setPhysicalAccountId(bank.getId());
        assertThatThrownBy(() -> enrol(treasury, noShadow)).hasMessageContaining("no active shadow");

        assertThat(saved).isEmpty();
        assertThat(service.paymentAccounts(treasury.getId(), "AED")).extracting(PoboEnrolmentService.PaymentAccount::id)
            .containsExactly(payment.getId());
    }

    @Test
    void theHeadroomIsTheBalancePlusTheCreditLimitAndAnythingOverItIsRefused() {
        assertThat(PoboEnrolmentService.headroomRefusal(ihbCurrent, new BigDecimal("800"))).isNull();
        assertThat(PoboEnrolmentService.headroomRefusal(ihbCurrent, new BigDecimal("800.01")))
            .contains("over SUB-X's IHB credit headroom of 800");
    }

    @Test
    void thePain001DebtorOnBehalfIsTheTreasuryWithItsPaymentAccountsIbanAndBankAndTheSubsidiaryAsUltimateDebtor() {
        banks.get(payment.getPhysicalAccountId()).setBankCode("EBILAEADXXX");
        var route = new PoboEnrolmentService.Route(new PoboEnrolment(), subsidiary, treasury, payment, ihbCurrent);

        assertThat(service.painDebtor(route)).isEqualTo(new PoboEnrolmentService.PainDebtor(
            "TC-1 LLC", "TC-1", "AE070331234567890123456", "EBILAEADXXX", "SUB-X LLC"));

        banks.get(payment.getPhysicalAccountId()).setBankCode(null);
        assertThat(service.painDebtor(route).bic()).as("no BIC held: the home bank's").isNull();

        banks.get(payment.getPhysicalAccountId()).setIban("");
        assertThatThrownBy(() -> service.painDebtor(route)).hasMessageContaining("Not available: no IBAN for the debtor account");
    }

    @Test
    void anUnenrolledInHousePaymentsDebtorIsTheTreasuryWithThePayoutBankAccountsIbanAndTheSubsidiaryAsUltimateDebtor() {
        // The IHB current account has no IBAN of its own; the posting pays out through treasury's account's bank account.
        VirtualAccount treasuryLink = bankBacked(va(treasury, MirrorAccountType.TREASURY_SETTLEMENT), "AE550331234567890000555");
        VirtualAccount shadow = vas.values().stream()
            .filter(v -> treasuryLink.getPhysicalAccountId().equals(v.getLinkedPhysicalAccountId())).findFirst().orElseThrow();

        assertThat(service.inHouseDebtor(ihbCurrent, treasuryLink, shadow)).isEqualTo(new PoboEnrolmentService.PainDebtor(
            "TC-1 LLC", "TC-1", "AE550331234567890000555", null, "SUB-X LLC"));

        banks.get(treasuryLink.getPhysicalAccountId()).setIban(null);
        assertThatThrownBy(() -> service.inHouseDebtor(ihbCurrent, treasuryLink, shadow))
            .hasMessageContaining("Not available: no IBAN for the bank account");
    }

    @Test
    void aDirectDebtorAccountIsTheVibanElseTheBankIbanElseTheBankAccountsIbanNeverTheVaId() {
        VirtualAccount own = bankBacked(va(subsidiary, null), "AE000000000000000000099");
        assertThat(service.debtorIban(own)).isEqualTo("AE000000000000000000099");
        own.setBankIban("AE000000000000000000088");
        assertThat(service.debtorIban(own)).isEqualTo("AE000000000000000000088");
        own.setViban("AE000000000000000000077");
        assertThat(service.debtorIban(own)).isEqualTo("AE000000000000000000077");

        VirtualAccount none = va(subsidiary, null);
        assertThatThrownBy(() -> service.debtorIban(none)).hasMessage("Not available: no IBAN for the debtor account " + none.getVaNumber());
    }
}
