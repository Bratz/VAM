package com.bank.vam.iso20022.service;

import com.bank.vam.dto.TransactionDto;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.pobo.PoboEnrolment;
import com.bank.vam.fx.deal.FxPaymentService;
import com.bank.vam.iso20022.dto.Iso20022PaymentDto.OutwardPaymentRequest;
import com.bank.vam.iso20022.util.Iso20022XmlBuilder;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.service.PaymentRoutingService;
import com.bank.vam.service.TransactionService;
import com.bank.vam.service.pobo.PoboEnrolmentService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** An ISO outward payment by an entity enrolled for POBO in its currency runs through the enrolment (spec R4). */
class IsoOutwardPoboByEnrolmentTest {

    private final TransactionService transactions = mock(TransactionService.class);
    private final VirtualAccountRepository vas = mock(VirtualAccountRepository.class);
    private final FxPaymentService fx = mock(FxPaymentService.class);
    private final PoboEnrolmentService enrolments = mock(PoboEnrolmentService.class);
    private final Iso20022OutwardPaymentService service = new Iso20022OutwardPaymentService(transactions,
        mock(PaymentRoutingService.class), vas, mock(Iso20022XmlBuilder.class), fx, mock(PlatformTransactionManager.class), enrolments);

    private VirtualAccount account(UUID owner) {
        VirtualAccount va = VirtualAccount.builder().vaNumber("VA-" + UUID.randomUUID()).currencyCode("AED")
            .owningEntityId(owner).status(VirtualAccount.VaStatus.ACTIVE).build();
        va.setId(UUID.randomUUID());
        when(vas.findById(va.getId())).thenReturn(Optional.of(va));
        return va;
    }

    @Test
    void aPaymentFromAnEnrolledSubsidiarysAccountIsBookedToItsIhbCurrentAndPaidByTheEnrolmentsAccount() {
        LegalEntity sub = new LegalEntity();
        sub.setId(UUID.randomUUID());
        LegalEntity treasury = new LegalEntity();
        treasury.setId(UUID.randomUUID());
        VirtualAccount own = account(sub.getId());
        VirtualAccount ihbCurrent = account(sub.getId());
        VirtualAccount payment = account(treasury.getId());
        when(enrolments.route(sub.getId(), "AED", LocalDate.now())).thenReturn(Optional.of(new PoboEnrolmentService.Route(
            new PoboEnrolment(), sub, treasury, payment, ihbCurrent)));
        Transaction posted = Transaction.builder().referenceNumber("IHBPOBO-1").build();
        posted.setId(UUID.randomUUID());
        when(transactions.makeIhb6LegPoboPayment(any())).thenReturn(posted);

        var response = service.processOutwardPayment(OutwardPaymentRequest.builder()
            .sourceVaId(own.getId()).amount(new BigDecimal("250")).currency("AED")
            .creditorName("Supplier Ltd").creditorAccount("GB00TEST").build());

        assertThat(response.isSuccess()).isTrue();
        ArgumentCaptor<TransactionDto.PoboPaymentRequest> req = ArgumentCaptor.forClass(TransactionDto.PoboPaymentRequest.class);
        verify(transactions).makeIhb6LegPoboPayment(req.capture());
        assertThat(req.getValue().getOwnerVaId()).isEqualTo(ihbCurrent.getId());
        assertThat(req.getValue().getPayerVaId()).isEqualTo(payment.getId());
        verify(transactions, never()).makePayment(any());
        verifyNoInteractions(fx);   // POBO pays in the enrolment's currency: no conversion
    }

    @Test
    void itsPain001HasTheTreasuryAsDebtorWithItsIbanAndTheSubsidiaryAsUltimateDebtor() {
        com.bank.vam.config.MarketProfileProperties market = mock(com.bank.vam.config.MarketProfileProperties.class);
        when(market.getActiveHomeBankBic()).thenReturn("HBUKGB4BXXX");
        Iso20022OutwardPaymentService real = new Iso20022OutwardPaymentService(transactions, mock(PaymentRoutingService.class),
            vas, new Iso20022XmlBuilder(market), fx, mock(PlatformTransactionManager.class), enrolments);
        LegalEntity sub = new LegalEntity();
        sub.setId(UUID.randomUUID());
        LegalEntity treasury = new LegalEntity();
        treasury.setId(UUID.randomUUID());
        VirtualAccount own = account(sub.getId());
        own.setVaName("SUB-X operating");
        PoboEnrolmentService.Route route = new PoboEnrolmentService.Route(new PoboEnrolment(), sub, treasury,
            account(treasury.getId()), account(sub.getId()));
        when(enrolments.route(sub.getId(), "AED", LocalDate.now())).thenReturn(Optional.of(route));
        when(enrolments.painDebtor(route)).thenReturn(new PoboEnrolmentService.PainDebtor(
            "TC-1 LLC", "TC-1", "AE070331234567890123456", null, "SUB-X LLC"));
        Transaction posted = Transaction.builder().referenceNumber("IHBPOBO-2").build();
        posted.setId(UUID.randomUUID());
        when(transactions.makeIhb6LegPoboPayment(any())).thenReturn(posted);

        var response = real.processOutwardPayment(OutwardPaymentRequest.builder()
            .sourceVaId(own.getId()).amount(new BigDecimal("250")).currency("AED").debtorName("SUB-X LLC")
            .creditorName("Supplier Ltd").creditorAccount("GB00TEST").remittanceInfo("INV-77").build());

        String xml = response.getPain001Xml();
        assertThat(xml).contains("<Dbtr>\n        <Nm>TC-1 LLC</Nm>", "<DbtrAcct>\n        <Id>\n          <IBAN>AE070331234567890123456</IBAN>",
            "<BICFI>HBUKGB4BXXX</BICFI>", "<UltmtDbtr>\n        <Nm>SUB-X LLC</Nm>", "<Ustrd>INV-77</Ustrd>");
        assertThat(xml.indexOf("<UltmtDbtr>")).isGreaterThan(xml.indexOf("</DbtrAgt>")).isLessThan(xml.indexOf("<CdtTrfTxInf>"));
    }

    @Test
    void aBulkPain001PutsRowsPaidOnBehalfInTheirOwnPmtInfAndLeavesTheOthersAsBefore() {
        com.bank.vam.config.MarketProfileProperties market = mock(com.bank.vam.config.MarketProfileProperties.class);
        when(market.getActiveHomeBankBic()).thenReturn("HBUKGB4BXXX");
        VirtualAccount own = account(UUID.randomUUID());
        own.setVaName("SUB-X operating");
        own.setViban("AE000000000000000000077");
        var pobo = new PoboEnrolmentService.PainDebtor("TC-1 LLC", "TC-1", "AE070331234567890123456", "EBILAEADXXX", "SUB-X LLC");
        var direct = com.bank.vam.iso20022.dto.Iso20022PaymentDto.PaymentInstruction.builder().instructionId("I-1")
            .amount(new BigDecimal("10")).currency("USD").creditorName("A").creditorAccount("GB01").build();
        var onBehalf = com.bank.vam.iso20022.dto.Iso20022PaymentDto.PaymentInstruction.builder().instructionId("I-2")
            .amount(new BigDecimal("20")).currency("AED").creditorName("B").creditorAccount("GB02").remittanceInfo("INV-9").build();
        Transaction t1 = Transaction.builder().referenceNumber("T1").amount(new BigDecimal("10")).build();
        Transaction t2 = Transaction.builder().referenceNumber("T2").amount(new BigDecimal("20")).build();

        String xml = new Iso20022XmlBuilder(market).buildBulkPain001(
            com.bank.vam.iso20022.dto.Iso20022PaymentDto.BulkPaymentRequest.builder().messageId("B-1").build(), own,
            java.util.List.of(new Iso20022XmlBuilder.BulkLine(t1, direct, null), new Iso20022XmlBuilder.BulkLine(t2, onBehalf, pobo)));

        String[] pmtInfs = xml.split("<PmtInf>");
        assertThat(pmtInfs).hasSize(3);
        assertThat(pmtInfs[1]).contains("<PmtInfId>B-1</PmtInfId>", "<Nm>SUB-X operating</Nm>",
            "<IBAN>AE000000000000000000077</IBAN>", "<BICFI>HBUKGB4BXXX</BICFI>", "<InstrId>I-1</InstrId>")
            .doesNotContain("UltmtDbtr", "I-2");
        assertThat(pmtInfs[2]).contains("<PmtInfId>B-1-2</PmtInfId>", "<NbOfTxs>1</NbOfTxs>", "<CtrlSum>20</CtrlSum>",
            "<Nm>TC-1 LLC</Nm>", "<IBAN>AE070331234567890123456</IBAN>", "<BICFI>EBILAEADXXX</BICFI>",
            "<UltmtDbtr>\n        <Nm>SUB-X LLC</Nm>", "<InstrId>I-2</InstrId>", "<Ustrd>INV-9</Ustrd>")
            .doesNotContain("I-1");
        assertThat(xml).contains("<GrpHdr>").contains("<NbOfTxs>2</NbOfTxs>\n      <CtrlSum>30</CtrlSum>");
    }
}
