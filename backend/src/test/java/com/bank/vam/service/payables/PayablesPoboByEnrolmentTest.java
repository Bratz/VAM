package com.bank.vam.service.payables;

import com.bank.vam.config.MarketProfileProperties;
import com.bank.vam.controller.intercompany.IntercompanyController;
import com.bank.vam.controller.payables.PayablesController;
import com.bank.vam.controller.pobo.PoboController;
import com.bank.vam.dto.TransactionDto;
import com.bank.vam.dto.payables.PayablesDto.CreatePayableRequest;
import com.bank.vam.dto.payables.PayablesDto.PaymentExecutionRequest;
import com.bank.vam.dto.payables.PayablesDto.PoboPreviewRequest;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.payables.Payable;
import com.bank.vam.entity.payables.Payable.PayableStatus;
import com.bank.vam.entity.pobo.PoboEnrolment;
import com.bank.vam.entity.treasury.PaymentRequest;
import com.bank.vam.repository.TransactionRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.party.PartyRepository;
import com.bank.vam.repository.payables.PayableRepository;
import com.bank.vam.repository.treasury.PaymentRequestRepository;
import com.bank.vam.service.TransactionService;
import com.bank.vam.service.pobo.PoboEnrolmentService;
import com.bank.vam.service.treasury.NettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * A payable of a subsidiary enrolled for POBO in its currency is paid on its behalf, decided by the enrolment and not
 * by a request (spec R4-R7, D3-D5, D10): payer from the enrolment, booked to the IHB current account, no fee; the
 * opt-out and other currencies pay directly with the reason; the old request / approve / execute endpoints are gone.
 */
class PayablesPoboByEnrolmentTest {

    private final PayableRepository payables = mock(PayableRepository.class);
    private final VirtualAccountRepository vas = mock(VirtualAccountRepository.class);
    private final PaymentRequestRepository paymentRequests = mock(PaymentRequestRepository.class);
    private final TransactionService transactions = mock(TransactionService.class);
    private final MarketProfileProperties marketProfile = mock(MarketProfileProperties.class);
    private final PoboEnrolmentService enrolments = mock(PoboEnrolmentService.class);

    private final com.bank.vam.repository.party.PartyBankAccountRepository partyBankAccounts =

        mock(com.bank.vam.repository.party.PartyBankAccountRepository.class);

    private final PayablesService service = new PayablesService(payables, mock(PartyRepository.class), vas,
        mock(TransactionRepository.class), paymentRequests, transactions, marketProfile, mock(NettingService.class),
        mock(LegalEntityRepository.class), enrolments, mock(com.bank.vam.fx.deal.FxPaymentService.class),
        mock(com.bank.vam.repository.receivables.ReceivableRepository.class), mock(com.bank.vam.service.treasury.FxRateService.class), partyBankAccounts);

    private final UUID corp = UUID.randomUUID();
    private LegalEntity subsidiary, treasury;
    private VirtualAccount ownAccount, ihbCurrent, paymentAccount;
    private PoboEnrolmentService.Route route;

    @BeforeEach
    void setUp() {
        subsidiary = entity("SUB-X");
        treasury = entity("TC-1");
        ownAccount = account("OWN-AED");
        ihbCurrent = account("IHB-SUBX-AED");
        ihbCurrent.setIhbParticipant(true);
        ihbCurrent.setOwningEntityCode("SUB-X");
        ihbCurrent.setCurrentBalance(new BigDecimal("-200"));
        ihbCurrent.setEffectiveCreditLimit(new BigDecimal("1000"));
        paymentAccount = account("TC-PAY-AED");
        PoboEnrolment enrolment = PoboEnrolment.builder().corporateId(corp).subsidiaryEntityId(subsidiary.getId())
            .currency("AED").payerEntityId(treasury.getId()).paymentVaId(paymentAccount.getId()).effectiveFrom(LocalDate.now()).build();
        route = new PoboEnrolmentService.Route(enrolment, subsidiary, treasury, paymentAccount, ihbCurrent);

        when(enrolments.route(subsidiary.getId(), "AED", LocalDate.now())).thenReturn(Optional.of(route));
        when(payables.save(any())).thenAnswer(i -> i.getArgument(0));
        when(paymentRequests.save(any())).thenAnswer(i -> i.getArgument(0));
        when(marketProfile.getActiveHomeBankBic()).thenReturn("HBUKGB4BXXX");
        Transaction posted = Transaction.builder().referenceNumber("IHBPOBO-1").build();
        posted.setId(UUID.randomUUID());
        when(transactions.makeIhb6LegPoboPayment(any())).thenReturn(posted);
        when(transactions.makePayment(any())).thenReturn(posted);
        when(enrolments.painDebtor(route)).thenReturn(new PoboEnrolmentService.PainDebtor(
            "TC-1 LLC", "TC-1", "AE070331234567890123456", "EBILAEADXXX", "SUB-X LLC"));
        when(enrolments.debtorIban(ownAccount)).thenReturn("AE440331234567890000001");
    }

    private LegalEntity entity(String code) {
        LegalEntity e = new LegalEntity();
        e.setId(UUID.randomUUID());
        e.setEntityCode(code);
        e.setEntityName(code + " LLC");
        e.setCorporateId(corp);
        return e;
    }

    private VirtualAccount account(String number) {
        VirtualAccount va = VirtualAccount.builder().vaNumber(number).currencyCode("AED")
            .currentBalance(new BigDecimal("5000")).availableBalance(new BigDecimal("5000")).heldBalance(BigDecimal.ZERO).build();
        va.setId(UUID.randomUUID());
        when(vas.findById(va.getId())).thenReturn(Optional.of(va));
        return va;
    }

    private CreatePayableRequest.CreatePayableRequestBuilder request(String currency) {
        return CreatePayableRequest.builder()
            .corporateId(corp).status("PENDING_APPROVAL")
            .invoiceNumber("INV-1").grossAmount(new BigDecimal("500")).currencyCode(currency)
            .dueDate(LocalDate.now().plusDays(30)).vendorName("Supplier Ltd")
            .virtualAccountId(ownAccount.getId())
            .owningEntityId(subsidiary.getId()).owningEntityCode("SUB-X").owningEntityName("SUB-X LLC");
    }

    private Payable created(CreatePayableRequest request) {
        service.createPayable(request);
        ArgumentCaptor<Payable> saved = ArgumentCaptor.forClass(Payable.class);
        verify(payables).save(saved.capture());
        return saved.getValue();
    }

    private Payable approved(boolean payDirectly) {
        Payable p = Payable.builder().payableNumber("PAY-1").invoiceNumber("INV-1").status(PayableStatus.APPROVED)
            .currencyCode("AED").netAmount(new BigDecimal("500")).paidAmount(BigDecimal.ZERO).vendorName("Supplier Ltd")
            .vendorAccount("GB29NWBK60161331926819")
            .owningEntityId(subsidiary.getId()).owningEntityName("SUB-X LLC").virtualAccountId(ownAccount.getId())
            .payDirectly(payDirectly).build();
        p.setId(UUID.randomUUID());
        when(payables.findById(p.getId())).thenReturn(Optional.of(p));
        when(payables.findAllById(List.of(p.getId()))).thenReturn(List.of(p));
        return p;
    }

    @Test
    void createOfAnEnrolledSubsidiarysPayableIsPoboFromTheEnrolment() {
        Payable p = created(request("AED").build());

        assertThat(p.getIsPobo()).isTrue();
        assertThat(p.getPaymentRoute()).isEqualTo(Payable.PaymentRoute.POBO);
        assertThat(p.getPaymentViaEntityId()).isEqualTo(treasury.getId());
        assertThat(p.getPaymentViaEntityCode()).isEqualTo("TC-1");
        assertThat(p.getBehalfOfEntity()).isEqualTo("SUB-X LLC");
        assertThat(p.getBehalfOfVaId()).isEqualTo(ihbCurrent.getId());
        assertThat(p.getVirtualAccountId()).as("the enrolment decides the account").isEqualTo(ihbCurrent.getId());
        assertThat(p.getPaymentRouteNote()).isNull();
    }

    @Test
    void payDirectlyInsteadKeepsTheOwnAccountAndSaysWhy() {
        Payable p = created(request("AED").payDirectly(true).build());

        assertThat(p.getIsPobo()).isFalse();
        assertThat(p.getPaymentRoute()).isEqualTo(Payable.PaymentRoute.DIRECT);
        assertThat(p.getVirtualAccountId()).isEqualTo(ownAccount.getId());
        assertThat(p.getPaymentRouteNote()).isEqualTo("Paid directly instead of through TC-1 (chosen on the payable)");
    }

    @Test
    void aCurrencyTheSubsidiaryIsNotEnrolledForIsPaidDirectlyWithTheReason() {
        when(enrolments.notEnrolledReason(subsidiary.getId(), "USD", LocalDate.now()))
            .thenReturn("Paid directly: SUB-X is not enrolled for USD (only AED)");

        Payable p = created(request("USD").build());

        assertThat(p.getIsPobo()).isFalse();
        assertThat(p.getVirtualAccountId()).isEqualTo(ownAccount.getId());
        assertThat(p.getPaymentRouteNote()).isEqualTo("Paid directly: SUB-X is not enrolled for USD (only AED)");
    }

    @Test
    void payNowOfAnEnrolledSubsidiaryRunsTheInHousePostingWithThePayerFromTheEnrolmentAndNoFee() {
        Payable p = approved(false);

        service.executePayment(PaymentExecutionRequest.builder().payableId(p.getId()).executedBy("tester").build());

        ArgumentCaptor<TransactionDto.PoboPaymentRequest> posted = ArgumentCaptor.forClass(TransactionDto.PoboPaymentRequest.class);
        verify(transactions).makeIhb6LegPoboPayment(posted.capture());
        assertThat(posted.getValue().getOwnerVaId()).isEqualTo(ihbCurrent.getId());
        assertThat(posted.getValue().getPayerVaId()).as("not the account's treasuryPoolVaId").isEqualTo(paymentAccount.getId());
        assertThat(posted.getValue().getAmount()).isEqualByComparingTo("500");
        verify(transactions, never()).makePayment(any());
        verify(transactions, never()).makePoboPayment(any());
        assertThat(p.getPaymentRoute()).isEqualTo(Payable.PaymentRoute.POBO);
        assertThat(p.getStatus()).isEqualTo(PayableStatus.PAID);
        ArgumentCaptor<PaymentRequest> request = ArgumentCaptor.forClass(PaymentRequest.class);
        verify(paymentRequests, atLeastOnce()).save(request.capture());
        assertThat(request.getValue().getSourceVaId()).as("the account the money leaves").isEqualTo(paymentAccount.getId());
    }

    @Test
    void payNowOfAnOptedOutPayablePaysDirectlyFromItsOwnAccount() {
        Payable p = approved(true);

        service.executePayment(PaymentExecutionRequest.builder().payableId(p.getId()).executedBy("tester").build());

        ArgumentCaptor<TransactionDto.PaymentRequest> paid = ArgumentCaptor.forClass(TransactionDto.PaymentRequest.class);
        verify(transactions).makePayment(paid.capture());
        assertThat(paid.getValue().getFromVaId()).isEqualTo(ownAccount.getId());
        verify(transactions, never()).makeIhb6LegPoboPayment(any());
        assertThat(p.getIsPobo()).isFalse();
    }

    @Test
    void thePain001OfAPaymentOnBehalfHasTheTreasuryAsDebtorWithItsIbanAndTheSubsidiaryAsUltimateDebtor() {
        Payable p = approved(false);

        String xml = service.executePayment(PaymentExecutionRequest.builder().payableId(p.getId()).executedBy("tester").build())
            .getPain001Xml();

        assertThat(xml).contains("<Dbtr>\n        <Nm>TC-1 LLC</Nm>", "<IBAN>AE070331234567890123456</IBAN>",
            "<BICFI>EBILAEADXXX</BICFI>", "<UltmtDbtr>\n        <Nm>SUB-X LLC</Nm>", "<Ustrd>Invoice: INV-1</Ustrd>",
            "<InitgPty>\n        <Nm>TC-1 LLC</Nm>");
        assertThat(xml.indexOf("<UltmtDbtr>")).as("PmtInf level, after DbtrAgt, before the transaction")
            .isGreaterThan(xml.indexOf("</DbtrAgt>")).isLessThan(xml.indexOf("<CdtTrfTxInf>"));
        assertThat(xml).doesNotContain(ihbCurrent.getId().toString(), paymentAccount.getId().toString(), "<Othr>\n            <Id>");
    }

    @Test
    void thePain001OfADirectPaymentHasTheOwnersNameAndItsAccountsIbanNotAVaId() {
        Payable p = approved(true);

        String xml = service.executePayment(PaymentExecutionRequest.builder().payableId(p.getId()).executedBy("tester").build())
            .getPain001Xml();

        assertThat(xml).contains("<Dbtr>\n        <Nm>SUB-X LLC</Nm>", "<IBAN>AE440331234567890000001</IBAN>",
            "<BICFI>HBUKGB4BXXX</BICFI>", "<Ustrd>Invoice: INV-1</Ustrd>");
        assertThat(xml).doesNotContain("UltmtDbtr", ownAccount.getId().toString());
    }

    @Test
    void aDirectPaymentFromAnAccountWithNoIbanIsRefusedBeforeMoneyMoves() {
        Payable p = approved(true);
        when(enrolments.debtorIban(ownAccount)).thenThrow(new com.bank.vam.exception.BusinessException(
            "Not available: no IBAN for the debtor account OWN-AED"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.executePayment(
                PaymentExecutionRequest.builder().payableId(p.getId()).executedBy("tester").build()))
            .hasMessage("Not available: no IBAN for the debtor account OWN-AED");
        verify(transactions, never()).makePayment(any());
        assertThat(p.getStatus()).isEqualTo(PayableStatus.APPROVED);
    }

    @Test
    void anUnenrolledPaymentFromAnIhbCurrentAccountHasTheTreasuryAsDebtorWithThePayoutIbanAndTheSubsidiaryAsUltimateDebtor() {
        VirtualAccount treasuryLink = account("TC-SETTLE-AED");
        VirtualAccount shadow = account("SHADOW-AED");
        VirtualAccount otherIhb = account("IHB-SUBY-AED");
        otherIhb.setIhbParticipant(true);
        otherIhb.setTreasuryPoolVaId(treasuryLink.getId());
        Payable p = approved(false);
        p.setOwningEntityId(UUID.randomUUID()); // not enrolled
        p.setOwningEntityName("SUB-Y LLC");
        p.setVirtualAccountId(otherIhb.getId());
        when(transactions.payoutShadow(treasuryLink)).thenReturn(shadow);
        when(enrolments.inHouseDebtor(otherIhb, treasuryLink, shadow)).thenReturn(new PoboEnrolmentService.PainDebtor(
            "TC-1 LLC", "TC-1", "AE550331234567890000555", null, "SUB-Y LLC"));

        String xml = service.executePayment(PaymentExecutionRequest.builder().payableId(p.getId()).executedBy("tester").build())
            .getPain001Xml();

        ArgumentCaptor<TransactionDto.PoboPaymentRequest> posted = ArgumentCaptor.forClass(TransactionDto.PoboPaymentRequest.class);
        verify(transactions).makeIhb6LegPoboPayment(posted.capture());
        assertThat(posted.getValue().getPayerVaId()).isEqualTo(treasuryLink.getId());
        assertThat(xml).contains("<Dbtr>\n        <Nm>TC-1 LLC</Nm>", "<IBAN>AE550331234567890000555</IBAN>",
            "<BICFI>HBUKGB4BXXX</BICFI>", "<UltmtDbtr>\n        <Nm>SUB-Y LLC</Nm>");
        verify(enrolments, never()).debtorIban(any());
    }

    @Test
    void aPayableWithoutASupplierBankAccountIsRefusedBeforeMoneyMoves() {
        Payable p = approved(true);
        p.setVendorAccount(null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.executePayment(
                PaymentExecutionRequest.builder().payableId(p.getId()).executedBy("tester").build()))
            .hasMessage("Not available: PAY-1 has no supplier bank account");
        verify(transactions, never()).makePayment(any());
        assertThat(p.getStatus()).isEqualTo(PayableStatus.APPROVED);
    }

    @Test
    void thePain001CreditorIsTheSuppliersChosenBankAccountWithNoPlaceholdersAndSepaOnlyForEuro() {
        Payable p = approved(true);
        p.setVendorAccount(null);
        p.setPartyBankAccountId(UUID.randomUUID());
        com.bank.vam.entity.party.PartyBankAccount bank = new com.bank.vam.entity.party.PartyBankAccount();
        bank.setIban("GB29NWBK60161331926819");
        bank.setBankCode("NWBKGB2LXXX");
        bank.setBankName("NatWest");
        when(partyBankAccounts.findById(p.getPartyBankAccountId())).thenReturn(Optional.of(bank));

        String xml = service.executePayment(PaymentExecutionRequest.builder().payableId(p.getId()).executedBy("tester").build())
            .getPain001Xml();

        assertThat(xml).contains("<BICFI>NWBKGB2LXXX</BICFI>", "<Cdtr>\n          <Nm>Supplier Ltd</Nm>",
            "<IBAN>GB29NWBK60161331926819</IBAN>");
        assertThat(xml).doesNotContain("UNKNOWN", "Unknown Bank", ">Vendor<", "SEPA");
    }

    @Test
    void thePreviewShowsThePayerTheHeadroomAndTheRefusalWhenOver() {
        Payable p = approved(false);
        p.setNetAmount(new BigDecimal("900"));

        var preview = service.getPoboPreview(PoboPreviewRequest.builder().payableIds(List.of(p.getId())).build()).get(0);

        assertThat(preview.getRoute()).isEqualTo("POBO");
        assertThat(preview.getPayerEntityCode()).isEqualTo("TC-1");
        assertThat(preview.getPaymentVaNumber()).isEqualTo("TC-PAY-AED");
        assertThat(preview.getIhbCurrentVaNumber()).isEqualTo("IHB-SUBX-AED");
        assertThat(preview.getHeadroom()).isEqualByComparingTo("800");
        assertThat(preview.getRefusal()).contains("headroom of 800");
    }

    @Test
    void theRequestApproveAndExecuteEndpointsOfTheOldPoboWorkflowAreGone() {
        List<String> payablesPaths = paths(PayablesController.class);
        assertThat(payablesPaths).contains("/pobo/preview", "/pobo/history")
            .doesNotContain("/pobo/request", "/pobo/approve", "/pobo/batch-approve", "/pobo/execute",
                "/pobo/pending-approval", "/pobo/eligible");
        assertThat(paths(PoboController.class)).noneMatch(p -> p.startsWith("/execute") || p.startsWith("/preview")
            || p.startsWith("/validate") || p.startsWith("/authorizations"));
        assertThat(paths(IntercompanyController.class)).doesNotContain("/pobo/preview", "/pobo/execute");
    }

    private static List<String> paths(Class<?> controller) {
        List<String> out = new ArrayList<>();
        for (Method m : controller.getDeclaredMethods()) {
            Stream.concat(
                Optional.ofNullable(m.getAnnotation(PostMapping.class)).stream().flatMap(a -> Stream.of(a.value())),
                Optional.ofNullable(m.getAnnotation(GetMapping.class)).stream().flatMap(a -> Stream.of(a.value()))
            ).forEach(out::add);
        }
        return out;
    }
}
