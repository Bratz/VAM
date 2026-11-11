package com.bank.vam.controller;

import com.bank.vam.config.MarketProfileProperties;
import com.bank.vam.entity.Transaction;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.service.TransactionService;
import com.bank.vam.service.pobo.PoboEnrolmentService;
import com.bank.vam.service.pobo.PoboEnrolmentService.PainDebtor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * The transaction screen's pain.001 resolves the debtor as the payment paths do (POBO spec D6): real IBAN, home-bank BIC,
 * treasury + UltmtDbtr for a payment through the in-house posting; never the VA number or an invented BIC.
 */
class TransactionControllerPain001Test {

    private final TransactionService transactions = mock(TransactionService.class);
    private final VirtualAccountRepository vas = mock(VirtualAccountRepository.class);
    private final PoboEnrolmentService enrolments = mock(PoboEnrolmentService.class);
    private final MarketProfileProperties market = mock(MarketProfileProperties.class);
    private final TransactionController controller = new TransactionController(transactions, vas, enrolments, market,
        mock(LegalEntityRepository.class));

    private VirtualAccount va(String number) {
        VirtualAccount va = VirtualAccount.builder().vaNumber(number).vaName(number + " name").currencyCode("AED").build();
        va.setId(UUID.randomUUID());
        when(vas.findById(va.getId())).thenReturn(Optional.of(va));
        return va;
    }

    private Transaction txn(Transaction.MovementType type, VirtualAccount on) {
        Transaction t = Transaction.builder().movementType(type).vaId(on.getId()).amount(new BigDecimal("700"))
            .currencyCode("AED").referenceNumber("TXN-1").correlationId("IHBPOBO-1")
            .transactionDate(LocalDateTime.now()).valueDate(LocalDate.now()).beneficiaryName("Supplier").build();
        t.setId(UUID.randomUUID());
        when(transactions.getById(t.getId())).thenReturn(t);
        return t;
    }

    private String xml(Transaction t) {
        return controller.getIsoMessage(t.getId()).getBody().getData().getXml();
    }

    @Test
    void aDirectPaymentHasTheAccountsIbanAndTheHomeBankBic() {
        VirtualAccount own = va("OWN-AED");
        Transaction debit = txn(Transaction.MovementType.DEBIT, own);
        when(enrolments.debtorIban(own)).thenReturn("AE440331234567890000001");
        when(market.getActiveHomeBankBic()).thenReturn("HBUKGB4BXXX");

        String xml = xml(debit);

        assertThat(xml).contains("<IBAN>AE440331234567890000001</IBAN>", "<BIC>HBUKGB4BXXX</BIC>");
        assertThat(xml).doesNotContain("TEABORGG", "OWN-AED</Id>", "UltmtDbtr");
    }

    @Test
    void anInHousePaymentHasTheTreasuryAsDebtorWithThePayoutIbanAndTheSubsidiaryAsUltimateDebtor() {
        VirtualAccount ihbCurrent = va("IHB-SUB-AED");
        VirtualAccount payer = va("TC-SETTLE-AED");
        VirtualAccount shadow = va("SHADOW-AED");
        Transaction leg1 = txn(Transaction.MovementType.POBO_DEBIT, ihbCurrent);
        Transaction payout = Transaction.builder().movementType(Transaction.MovementType.POBO_DEBIT).vaId(shadow.getId())
            .physicalAccountId(UUID.randomUUID()).counterpartyVaId(payer.getId()).behalfOfVaId(ihbCurrent.getId())
            .correlationId("IHBPOBO-1").build();
        when(transactions.getByCorrelationId("IHBPOBO-1")).thenReturn(List.of(leg1, payout));
        when(enrolments.inHouseDebtor(ihbCurrent, payer, shadow))
            .thenReturn(new PainDebtor("TC-1 LLC", "TC-1", "AE550331234567890000555", null, "SUB-X LLC"));
        when(market.getActiveHomeBankBic()).thenReturn("HBUKGB4BXXX");

        String xml = xml(leg1);

        assertThat(xml).contains("<Nm>TC-1 LLC</Nm>", "<IBAN>AE550331234567890000555</IBAN>", "<BIC>HBUKGB4BXXX</BIC>",
            "<UltmtDbtr>\n        <Nm>SUB-X LLC</Nm>");
        assertThat(xml).doesNotContain("TEABORGG", "IHB-SUB-AED");
    }

    @Test
    void noIbanMeansNotAvailable() {
        VirtualAccount own = va("OWN-AED");
        Transaction debit = txn(Transaction.MovementType.DEBIT, own);
        when(enrolments.debtorIban(own)).thenThrow(new BusinessException("Not available: no IBAN for the debtor account OWN-AED"));

        assertThatThrownBy(() -> xml(debit)).hasMessageStartingWith("Not available");
    }
}
