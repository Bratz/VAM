package com.bank.vam.service.treasury;

import com.bank.vam.entity.PhysicalAccount;
import com.bank.vam.entity.Program;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.PhysicalAccountRepository;
import com.bank.vam.repository.ProgramRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Home-bank accounts get a shadow as soon as they exist, before any program does, so the
 * shadow starts outside every hierarchy and joins a program only when that program picks it.
 */
class HomeBankShadowTest {

    private final VirtualAccountRepository vas = mock(VirtualAccountRepository.class);
    private final PhysicalAccountRepository pas = mock(PhysicalAccountRepository.class);
    private final ShadowAccountService service = new ShadowAccountService(
        vas, pas, mock(ProgramRepository.class), mock(LegalEntityRepository.class), mock(HierarchyVaService.class), homeBank());

    private static com.bank.vam.config.HomeBankProperties homeBank() {
        var hb = new com.bank.vam.config.HomeBankProperties();
        hb.setBic("HOMEBANKXXX");
        return hb;
    }

    @Test
    void homeBankIsTheConfiguredBicNotTheAccountFlag() {
        PhysicalAccount elsewhere = homeAccount();          // flagged INTERNAL, as older rows are
        elsewhere.setBankCode("CITIUS33XXX");
        PhysicalAccount home = homeAccount();
        home.setBankCode("homebankxxx");
        assertThat(service.isAtHomeBank(elsewhere)).isFalse();
        assertThat(service.isAtHomeBank(home)).isTrue();
    }

    private final UUID corporateId = UUID.randomUUID();

    private PhysicalAccount homeAccount() {
        PhysicalAccount pa = new PhysicalAccount();
        pa.setId(UUID.randomUUID());
        pa.setAccountNumber("PA-AED-009");
        pa.setCorporateId(corporateId);
        pa.setCurrencyCode("AED");
        pa.setBankRelationship(PhysicalAccount.BankRelationship.INTERNAL);
        return pa;
    }

    private VirtualAccount shadow(UUID programId, String currency) {
        return shadowAt("HOMEBANKXXX", programId, currency);
    }

    private VirtualAccount shadowAt(String bic, UUID programId, String currency) {
        VirtualAccount s = VirtualAccount.builder()
            .corporateId(corporateId).programId(programId).currencyCode(currency).bankSwift(bic)
            .linkedPhysicalAccountId(UUID.randomUUID())
            .accountCategory(VirtualAccount.AccountCategory.PHYSICAL_MIRROR).bankAccountNumber("PA-X").build();
        s.setId(UUID.randomUUID());
        when(vas.findById(s.getId())).thenReturn(Optional.of(s));
        return s;
    }

    private Program program(String currency) {
        Program p = Program.builder().corporateId(corporateId).currencyCode(currency).programCode("P1").build();
        p.setId(UUID.randomUUID());
        return p;
    }

    @Test
    void newHomeBankAccountGetsAnUnassignedShadow() {
        PhysicalAccount pa = homeAccount();
        when(vas.findByLinkedPhysicalAccountId(pa.getId())).thenReturn(Optional.empty());
        when(vas.save(any())).thenAnswer(i -> { VirtualAccount v = i.getArgument(0); v.setId(UUID.randomUUID()); return v; });

        VirtualAccount s = service.ensureHomeBankShadow(pa);

        assertThat(s.getProgramId()).isNull();
        assertThat(s.getParentAccountId()).isNull();
        // Not level 0: corporate "ROOT-only" totals read level 0 as a program root.
        assertThat(s.getHierarchyLevel()).isEqualTo(ShadowAccountService.UNASSIGNED_LEVEL).isNotZero();
        assertThat(s.getLinkedPhysicalAccountId()).isEqualTo(pa.getId());
        assertThat(pa.getShadowVaId()).isEqualTo(s.getId());
    }

    @Test
    void existingShadowIsReusedNotDuplicated() {
        PhysicalAccount pa = homeAccount();
        VirtualAccount existing = shadow(null, "AED");
        when(vas.findByLinkedPhysicalAccountId(pa.getId())).thenReturn(Optional.of(existing));

        assertThat(service.ensureHomeBankShadow(pa)).isSameAs(existing);
        verify(vas, never()).save(any());
    }

    @Test
    void shadowInAnotherProgramCannotBePicked() {
        Program p = program("AED");
        VirtualAccount taken = shadow(UUID.randomUUID(), "AED");
        when(vas.findByProgramIdAndAccountCategory(p.getId(), VirtualAccount.AccountCategory.PHYSICAL_MIRROR)).thenReturn(List.of());

        assertThatThrownBy(() -> service.setProgramShadows(p, List.of(taken.getId())))
            .isInstanceOf(BusinessException.class).hasMessageContaining("another program");
    }

    @Test
    void shadowInAnotherCurrencyCannotBePicked() {
        Program p = program("AED");
        VirtualAccount gbp = shadow(null, "GBP");
        when(vas.findByProgramIdAndAccountCategory(p.getId(), VirtualAccount.AccountCategory.PHYSICAL_MIRROR)).thenReturn(List.of());

        assertThatThrownBy(() -> service.setProgramShadows(p, List.of(gbp.getId())))
            .isInstanceOf(BusinessException.class).hasMessageContaining("home-bank accounts in AED");
    }

    @Test
    void savingSetupLeavesAccountsItDoesNotShowAlone() {
        // A multi-bank program: one home-bank account setup shows, and accounts at other banks
        // and in other currencies that setup never lists.
        Program p = program("AED");
        VirtualAccount home = shadowAt("HOMEBANKXXX", p.getId(), "AED");
        VirtualAccount otherBank = shadowAt("CITIUS33XXX", p.getId(), "AED");
        VirtualAccount otherCurrency = shadowAt("HOMEBANKXXX", p.getId(), "USD");
        p.setPhysicalAccountId(otherBank.getLinkedPhysicalAccountId());
        when(vas.findByLinkedPhysicalAccountId(otherBank.getLinkedPhysicalAccountId())).thenReturn(Optional.of(otherBank));
        when(vas.findByProgramIdAndAccountCategory(p.getId(), VirtualAccount.AccountCategory.PHYSICAL_MIRROR))
            .thenReturn(List.of(home, otherBank, otherCurrency));

        // e.g. a rename with the one listed account still ticked
        UUID backing = service.setProgramShadows(p, List.of(home.getId()));

        assertThat(otherBank.getProgramId()).isEqualTo(p.getId());
        assertThat(otherCurrency.getProgramId()).isEqualTo(p.getId());
        assertThat(backing).isEqualTo(otherBank.getLinkedPhysicalAccountId());   // backing unchanged
    }

    @Test
    void droppedShadowReturnsToUnassigned() {
        Program p = program("AED");
        VirtualAccount mine = shadow(p.getId(), "AED");
        mine.setParentAccountId(UUID.randomUUID());
        when(vas.findByProgramIdAndAccountCategory(p.getId(), VirtualAccount.AccountCategory.PHYSICAL_MIRROR)).thenReturn(List.of(mine));
        when(vas.findByParentAccountId(mine.getId())).thenReturn(List.of());

        assertThat(service.setProgramShadows(p, List.of())).isNull();
        assertThat(mine.getProgramId()).isNull();
        assertThat(mine.getParentAccountId()).isNull();
    }

    private VirtualAccount booked(Program p, VirtualAccount.AccountCategory category, UUID bankAccountId) {
        VirtualAccount va = VirtualAccount.builder().programId(p.getId()).accountCategory(category).physicalAccountId(bankAccountId).build();
        va.setId(UUID.randomUUID());
        return va;
    }

    @Test
    void customerAccountsOnTheBankAccountBlockItsRemoval() {
        Program p = program("AED");
        VirtualAccount mine = shadow(p.getId(), "AED");
        mine.setLinkedPhysicalAccountId(UUID.randomUUID());
        when(vas.findByProgramIdAndAccountCategory(p.getId(), VirtualAccount.AccountCategory.PHYSICAL_MIRROR)).thenReturn(List.of(mine));
        when(vas.findByProgramIdAndPhysicalAccountId(p.getId(), mine.getLinkedPhysicalAccountId()))
            .thenReturn(List.of(booked(p, VirtualAccount.AccountCategory.COLLECTION, mine.getLinkedPhysicalAccountId())));

        assertThatThrownBy(() -> service.setProgramShadows(p, List.of()))
            .isInstanceOf(BusinessException.class).hasMessageContaining("booked on bank account");
        assertThat(mine.getProgramId()).isEqualTo(p.getId());
    }

    @Test
    void programScaffoldingFollowsTheNewBackingAccount() {
        Program p = program("AED");
        VirtualAccount old = shadow(p.getId(), "AED");
        old.setLinkedPhysicalAccountId(UUID.randomUUID());
        VirtualAccount root = booked(p, VirtualAccount.AccountCategory.ROOT, old.getLinkedPhysicalAccountId());
        when(vas.findByProgramIdAndAccountCategory(p.getId(), VirtualAccount.AccountCategory.PHYSICAL_MIRROR)).thenReturn(List.of(old));
        when(vas.findByProgramIdAndPhysicalAccountId(p.getId(), old.getLinkedPhysicalAccountId())).thenReturn(List.of(root));

        service.setProgramShadows(p, List.of());

        assertThat(root.getPhysicalAccountId()).isNull();
        assertThat(old.getProgramId()).isNull();
    }
}
