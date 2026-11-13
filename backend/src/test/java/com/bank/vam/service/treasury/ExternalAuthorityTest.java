package com.bank.vam.service.treasury;

import com.bank.vam.config.HomeBankProperties;
import com.bank.vam.dto.treasury.SweepRuleDto;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.treasury.ExternalMandate;
import com.bank.vam.entity.treasury.ExternalMandate.InstructionFormat;
import com.bank.vam.entity.treasury.ExternalMandate.MandateStatus;
import com.bank.vam.entity.treasury.SweepRule;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.treasury.ExternalMandateRepository;
import com.bank.vam.repository.treasury.SweepExecutionRepository;
import com.bank.vam.repository.treasury.SweepRuleRepository;
import com.bank.vam.repository.treasury.SweepRuleSourceRepository;
import com.bank.vam.service.TransactionService;
import com.bank.vam.service.audit.AuditLogService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Authorities for the home bank to instruct an account at another bank (spec external-bank-sweeps R1–R3): the one
 * check, the bank-operations lifecycle, and rule creation going through the check.
 */
class ExternalAuthorityTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);

    private final ExternalMandateRepository mandates = mock(ExternalMandateRepository.class);
    private final VirtualAccountRepository vas = mock(VirtualAccountRepository.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final HomeBankProperties homeBank = new HomeBankProperties();
    private final ExternalAuthorityService service = new ExternalAuthorityService(mandates, vas, homeBank, audit);

    {
        homeBank.setBic("HBUKGB4BXXX");
        when(mandates.save(any())).thenAnswer(i -> {
            ExternalMandate m = i.getArgument(0);
            if (m.getId() == null) m.setId(UUID.randomUUID());
            return m;
        });
    }

    private static final UUID ENTITY = UUID.randomUUID();

    private VirtualAccount shadow(VirtualAccount.AccountCategory category, String bic) {
        VirtualAccount va = VirtualAccount.builder().vaNumber("SH-" + bic).currencyCode("GBP").bankSwift(bic)
                .corporateId(UUID.randomUUID()).accountCategory(category)
                .owningEntityId(ENTITY).owningEntityCode("ALBN-EN").build();
        va.setId(UUID.randomUUID());
        when(vas.findById(va.getId())).thenReturn(Optional.of(va));
        return va;
    }

    private static ExternalMandate authority(MandateStatus status) {
        ExternalMandate m = ExternalMandate.builder().mandateReference("AUTH-1").shadowVaId(UUID.randomUUID())
                .status(status).build();
        m.setId(UUID.randomUUID());
        return m;
    }

    private ExternalMandate stored(MandateStatus status) {
        ExternalMandate m = authority(status);
        when(mandates.findById(m.getId())).thenReturn(Optional.of(m));
        return m;
    }

    private static ExternalAuthorityService.Registration registration(UUID shadowId) {
        return new ExternalAuthorityService.Registration(shadowId, " LLOY-101 ", null, TODAY, TODAY.plusYears(1),
                new BigDecimal("50000"), new BigDecimal("100000"), "DOC-7");
    }

    // ---- the check -------------------------------------------------------------------------------------------

    @Test
    void onlyAnActiveAuthorityInsideItsValidityPasses() {
        ExternalMandate m = authority(MandateStatus.ACTIVE);
        m.setValidFrom(TODAY);
        m.setValidTo(TODAY.plusDays(30));
        assertThat(m.refusal(null, TODAY, BigDecimal.ZERO)).isNull();
        assertThat(m.refusal(null, TODAY.minusDays(1), BigDecimal.ZERO)).contains("valid from 2026-11-12");
        assertThat(m.refusal(null, TODAY.plusDays(31), BigDecimal.ZERO)).contains("expired on 2026-12-12");
        for (MandateStatus s : List.of(MandateStatus.PENDING_ACTIVATION, MandateStatus.SUSPENDED,
                MandateStatus.EXPIRED, MandateStatus.REVOKED)) {
            m.setStatus(s);
            assertThat(m.refusal(null, TODAY, BigDecimal.ZERO)).isEqualTo("authority AUTH-1 is " + s);
        }
    }

    @Test
    void amountIsCheckedAgainstThePerTransactionAndDailyLimits() {
        ExternalMandate m = authority(MandateStatus.ACTIVE);
        assertThat(m.refusal(new BigDecimal("1e9"), TODAY, new BigDecimal("1e9"))).as("no limits").isNull();

        m.setMaxPerTransaction(new BigDecimal("1000.0000"));
        m.setMaxDaily(new BigDecimal("2500.0000"));
        assertThat(m.refusal(new BigDecimal("1000"), TODAY, BigDecimal.ZERO)).isNull();
        assertThat(m.refusal(new BigDecimal("1000.01"), TODAY, BigDecimal.ZERO))
                .isEqualTo("authority AUTH-1: 1000.01 is over the limit per transaction of 1000");
        assertThat(m.refusal(new BigDecimal("1000"), TODAY, new BigDecimal("1500"))).as("exactly the daily limit").isNull();
        assertThat(m.refusal(new BigDecimal("1000"), TODAY, new BigDecimal("1500.01")))
                .contains("1500.01 already instructed on 2026-11-12 is over the daily limit of 2500");
    }

    @Test
    void aMissingFormatReadsAsMt101() {
        assertThat(authority(MandateStatus.ACTIVE).getInstructionFormat()).isEqualTo(InstructionFormat.MT101);
    }

    // ---- registration ----------------------------------------------------------------------------------------

    @Test
    void registersAnExternalShadowPendingActivationAndAudits() {
        VirtualAccount lloyds = shadow(VirtualAccount.AccountCategory.PHYSICAL_MIRROR, "LOYDGB2L");

        ExternalMandate m = service.register(registration(lloyds.getId()));

        assertThat(m.getStatus()).isEqualTo(MandateStatus.PENDING_ACTIVATION);
        assertThat(m.getMandateReference()).isEqualTo("LLOY-101");
        assertThat(m.getInstructionFormat()).isEqualTo(InstructionFormat.MT101);
        assertThat(m.getDebitorBankBic()).isEqualTo("LOYDGB2L");
        assertThat(m.getCreditorBankBic()).isEqualTo("HBUKGB4BXXX");
        verify(audit).record(eq("EXTERNAL_AUTHORITY_REGISTERED"), eq("ExternalMandate"), eq(m.getId()),
                eq(lloyds.getCorporateId()), anyString(), any());
    }

    @Test
    void anExternalMirrorTakesAPain001Authority() {
        VirtualAccount de = shadow(VirtualAccount.AccountCategory.EXTERNAL_MIRROR, "DEUTDEFFXXX");
        ExternalAuthorityService.Registration r = registration(de.getId());
        ExternalMandate m = service.register(new ExternalAuthorityService.Registration(r.shadowVaId(), "DE-1",
                InstructionFormat.PAIN_001, null, null, null, null, null));
        assertThat(m.getInstructionFormat()).isEqualTo(InstructionFormat.PAIN_001);
    }

    @Test
    void refusesHomeBankAndNonShadowAccounts() {
        // 8-character home BIC is the same bank as the configured 11-character ...XXX form
        VirtualAccount home8 = shadow(VirtualAccount.AccountCategory.PHYSICAL_MIRROR, "HBUKGB4B");
        VirtualAccount operating = shadow(VirtualAccount.AccountCategory.TRANSACTION, "LOYDGB2L");
        for (VirtualAccount va : List.of(home8, operating)) {
            assertThatThrownBy(() -> service.register(registration(va.getId())))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("not an account at another bank");
        }
        verify(mandates, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    void refusesADuplicateReferenceAndBadValidityOrLimits() {
        VirtualAccount lloyds = shadow(VirtualAccount.AccountCategory.PHYSICAL_MIRROR, "LOYDGB2L");
        UUID id = lloyds.getId();
        when(mandates.findByMandateReference("TAKEN")).thenReturn(Optional.of(authority(MandateStatus.ACTIVE)));
        List<ExternalAuthorityService.Registration> bad = List.of(
                new ExternalAuthorityService.Registration(id, "TAKEN", null, null, null, null, null, null),
                new ExternalAuthorityService.Registration(id, " ", null, null, null, null, null, null),
                new ExternalAuthorityService.Registration(id, "A", null, TODAY, TODAY.minusDays(1), null, null, null),
                new ExternalAuthorityService.Registration(id, "B", null, null, null, BigDecimal.ZERO, null, null),
                new ExternalAuthorityService.Registration(id, "C", null, null, null, null, new BigDecimal("-1"), null));
        for (ExternalAuthorityService.Registration r : bad) {
            assertThatThrownBy(() -> service.register(r)).isInstanceOf(BusinessException.class);
        }
        verify(mandates, never()).save(any());
    }

    // ---- lifecycle -------------------------------------------------------------------------------------------

    @Test
    void lifecycleActivateSuspendReactivateRevoke() {
        ExternalMandate m = stored(MandateStatus.PENDING_ACTIVATION);
        assertThat(service.activate(m.getId()).getStatus()).isEqualTo(MandateStatus.ACTIVE);
        assertThat(service.suspend(m.getId()).getStatus()).isEqualTo(MandateStatus.SUSPENDED);
        assertThat(service.activate(m.getId()).getStatus()).isEqualTo(MandateStatus.ACTIVE);
        ExternalMandate revoked = service.revoke(m.getId(), " Corporate withdrew it ");
        assertThat(revoked.getStatus()).isEqualTo(MandateStatus.REVOKED);
        assertThat(revoked.getRevocationReason()).isEqualTo("Corporate withdrew it");
        assertThat(revoked.getRevokedAt()).isNotNull();
        verify(audit, times(4)).record(eq("EXTERNAL_AUTHORITY_STATUS_CHANGED"), eq("ExternalMandate"), eq(m.getId()),
                any(), anyString(), any());
    }

    @Test
    void illegalMovesAreRefusedAndRevokedIsFinal() {
        ExternalMandate pending = stored(MandateStatus.PENDING_ACTIVATION);
        assertThatThrownBy(() -> service.suspend(pending.getId()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("is PENDING_ACTIVATION; it cannot become SUSPENDED");
        ExternalMandate active = stored(MandateStatus.ACTIVE);
        assertThatThrownBy(() -> service.activate(active.getId())).isInstanceOf(BusinessException.class);
        for (MandateStatus end : List.of(MandateStatus.REVOKED, MandateStatus.EXPIRED)) {
            ExternalMandate m = stored(end);
            assertThatThrownBy(() -> service.activate(m.getId())).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.suspend(m.getId())).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.revoke(m.getId(), "again")).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> service.revoke(active.getId(), "  "))
                .isInstanceOf(BusinessException.class).hasMessageContaining("reason is required");
        assertThat(active.getStatus()).isEqualTo(MandateStatus.ACTIVE);
        verify(mandates, never()).save(any());
        verifyNoInteractions(audit);
    }

    // ---- rule creation goes through the check ----------------------------------------------------------------

    private final SweepRuleRepository rules = mock(SweepRuleRepository.class);
    private final SweepService sweeps = new SweepService(rules, mock(SweepExecutionRepository.class),
            mock(SweepRuleSourceRepository.class), vas, mock(TransactionRepository.class), mock(LegalEntityRepository.class),
            mock(FeePostingService.class), mock(IhbFxService.class), mock(com.bank.vam.config.MarketProfileProperties.class),
            mock(PlatformTransactionManager.class), mock(SweepRunBootstrap.class),
            mock(com.bank.vam.repository.treasury.SweepRunRepository.class), homeBank, mandates,
            mock(com.bank.vam.repository.PhysicalAccountRepository.class), mock(TransactionService.class),
            mock(ExternalSweepService.class));

    private SweepRuleDto.Response createRule(VirtualAccount source, VirtualAccount target) {
        when(rules.save(any())).thenAnswer(i -> i.getArgument(0));
        when(vas.findAllById(List.of(source.getId()))).thenReturn(List.of(source));
        SweepRuleDto.SourceAccountRequest s = new SweepRuleDto.SourceAccountRequest();
        s.setAccountId(source.getId());   // by id only, as the API allows: messages name the account from the account
        SweepRuleDto.CreateRequest request = new SweepRuleDto.CreateRequest();
        request.setRuleName("Lloyds -> home");
        request.setSweepType(SweepRule.SweepType.ZERO_BALANCE);
        request.setTargetAccountId(target.getId());
        request.setSourceAccounts(List.of(s));
        return sweeps.createRule(request);
    }

    @Test
    void ruleCreationNeedsAnAuthorityThatPassesTheCheck() {
        VirtualAccount lloyds = shadow(VirtualAccount.AccountCategory.PHYSICAL_MIRROR, "LOYDGB2LXXX");
        VirtualAccount home = shadow(VirtualAccount.AccountCategory.PHYSICAL_MIRROR, "HBUKGB4B");

        assertThatThrownBy(() -> createRule(lloyds, home))
                .isInstanceOf(BusinessException.class).hasMessageContaining("none is registered")
                .hasMessageStartingWith("Account " + lloyds.getVaNumber() + " is at another bank");

        ExternalMandate suspended = authority(MandateStatus.SUSPENDED);
        when(mandates.findByShadowVaIdOrderByCreatedAtDesc(lloyds.getId())).thenReturn(List.of(suspended));
        assertThatThrownBy(() -> createRule(lloyds, home))
                .isInstanceOf(BusinessException.class).hasMessageContaining("authority AUTH-1 is SUSPENDED");

        // An ACTIVE one passes, whatever its rail scope (ignored for these, spec §4).
        ExternalMandate active = authority(MandateStatus.ACTIVE);
        active.setRailScope(SweepRule.Rail.SEPA_SCT);
        when(mandates.findByShadowVaIdOrderByCreatedAtDesc(lloyds.getId())).thenReturn(List.of(suspended, active));
        assertThat(createRule(lloyds, home).getTargetAccountId()).isEqualTo(home.getId());
        // the home-bank shadow (8-character BIC = configured ...XXX) needs no authority
        verify(mandates, never()).findByShadowVaIdOrderByCreatedAtDesc(home.getId());
    }
    // ---- which rules (spec R4–R6, D1, D10) ---------------------------------------------------------------------

    private VirtualAccount authorisedLloyds() {
        VirtualAccount lloyds = shadow(VirtualAccount.AccountCategory.PHYSICAL_MIRROR, "LOYDGB2LXXX");
        when(mandates.findByShadowVaIdOrderByCreatedAtDesc(lloyds.getId())).thenReturn(List.of(authority(MandateStatus.ACTIVE)));
        return lloyds;
    }

    @Test
    void anAccountAtAnotherBankIsNeverATarget() {
        VirtualAccount home = shadow(VirtualAccount.AccountCategory.PHYSICAL_MIRROR, "HBUKGB4BXXX");
        // home -> other bank, authorised or not, and other bank -> other bank
        assertThatThrownBy(() -> createRule(home, authorisedLloyds()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("external sweeps concentrate into the home bank");
        VirtualAccount de = shadow(VirtualAccount.AccountCategory.EXTERNAL_MIRROR, "DEUTDEFFXXX");
        assertThatThrownBy(() -> createRule(authorisedLloyds(), de))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("external sweeps concentrate into the home bank");
        verify(rules, never()).save(any());
    }

    @Test
    void anExternalSourceSweepsOnlyIntoItsOwnEntitysHomeBankAccountInItsCurrency() {
        VirtualAccount lloyds = authorisedLloyds();

        VirtualAccount otherEntity = shadow(VirtualAccount.AccountCategory.PHYSICAL_MIRROR, "HBUKGB4BXXX");
        otherEntity.setOwningEntityId(UUID.randomUUID());
        otherEntity.setOwningEntityCode("ALBN-GB");
        assertThatThrownBy(() -> createRule(lloyds, otherEntity))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("belongs to ALBN-GB").hasMessageContaining("goes through the in-house bank");

        VirtualAccount euros = shadow(VirtualAccount.AccountCategory.PHYSICAL_MIRROR, "HBUKGB4BXXX");
        euros.setCurrencyCode("EUR");
        assertThatThrownBy(() -> createRule(lloyds, euros))
                .isInstanceOf(BusinessException.class).hasMessageContaining("does not convert currency");

        VirtualAccount ledgerOnly = shadow(VirtualAccount.AccountCategory.TRANSACTION, null);
        assertThatThrownBy(() -> createRule(lloyds, ledgerOnly))
                .isInstanceOf(BusinessException.class).hasMessageContaining("is not an account held at the home bank");

        verify(rules, never()).save(any());
        VirtualAccount own = shadow(VirtualAccount.AccountCategory.PHYSICAL_MIRROR, "HBUKGB4BXXX");
        assertThat(createRule(lloyds, own).getTargetAccountId()).isEqualTo(own.getId());
    }

    @Test
    void homeBankRulesAreUnchanged() {
        // Across entities and without any authority, as before: the external checks apply to other-bank legs only.
        VirtualAccount source = shadow(VirtualAccount.AccountCategory.PHYSICAL_MIRROR, "HBUKGB4BXXX");
        VirtualAccount target = shadow(VirtualAccount.AccountCategory.PHYSICAL_MIRROR, "HBUKGB4B");
        target.setOwningEntityId(UUID.randomUUID());
        target.setCurrencyCode("EUR");
        assertThat(createRule(source, target).getTargetAccountId()).isEqualTo(target.getId());
        verify(mandates, never()).findByShadowVaIdOrderByCreatedAtDesc(any());
    }

    @Test
    void entityCodesComeFromTheAccountsNotTheFormsAccountNames() {
        // The form sends each account's name as its entity code; over 20 characters that was a 500 on the column.
        VirtualAccount source = shadow(VirtualAccount.AccountCategory.PHYSICAL_MIRROR, "HBUKGB4BXXX");
        VirtualAccount target = shadow(VirtualAccount.AccountCategory.PHYSICAL_MIRROR, "HBUKGB4B");
        target.setOwningEntityCode("HEL-US");
        when(rules.save(any())).thenAnswer(i -> i.getArgument(0));
        when(vas.findAllById(List.of(source.getId()))).thenReturn(List.of(source));
        SweepRuleDto.SourceAccountRequest s = new SweepRuleDto.SourceAccountRequest();
        s.setAccountId(source.getId());
        s.setEntityCode("Shadow · Bank of America USD");
        SweepRuleDto.CreateRequest request = new SweepRuleDto.CreateRequest();
        request.setRuleName("names");
        request.setSweepType(SweepRule.SweepType.ZERO_BALANCE);
        request.setTargetAccountId(target.getId());
        request.setTargetEntityCode("Shadow: Helios USD settlement account");
        request.setSourceAccounts(List.of(s));

        SweepRuleDto.Response rule = sweeps.createRule(request);

        assertThat(rule.getTargetEntityCode()).isEqualTo("HEL-US");
        assertThat(rule.getSourceAccounts().get(0).getEntityCode()).isEqualTo("ALBN-EN");
    }
}
