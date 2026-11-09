package com.bank.vam.collections;

import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.party.Party;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.party.PartyBankAccountRepository;
import com.bank.vam.repository.party.PartyRepository;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MandateServiceTest {

    private final CustomerMandateRepository mandates = mock(CustomerMandateRepository.class);
    private final PartyRepository parties = mock(PartyRepository.class);
    private final VirtualAccountRepository accounts = mock(VirtualAccountRepository.class);
    private final MandateService service = new MandateService(mandates, parties, mock(PartyBankAccountRepository.class), accounts);

    private static final UUID CORP = UUID.randomUUID();
    private static final String HEADER = "mandateReference,customer,creditorAccount,schemeCode,validFrom,debtorIban,maxPerCollection,frequency,type\n";

    private void knownCustomerAndAccount() {
        Party p = new Party();
        p.setId(UUID.randomUUID());
        p.setCorporateId(CORP);
        p.setLegalName("Acme Trading LLC");
        when(parties.findByPartyCode(any())).thenReturn(Optional.empty());
        Party twinA = new Party();
        twinA.setCorporateId(CORP);
        twinA.setLegalName("Twin Co");
        Party twinB = new Party();
        twinB.setCorporateId(CORP);
        twinB.setLegalName("TWIN CO");
        when(parties.findAllByCorporateIdAndLegalNameIgnoreCase(any(), any())).thenReturn(List.of());
        when(parties.findAllByCorporateIdAndLegalNameIgnoreCase(CORP, "Acme Trading LLC")).thenReturn(List.of(p));
        when(parties.findAllByCorporateIdAndLegalNameIgnoreCase(CORP, "Twin Co")).thenReturn(List.of(twinA, twinB));
        VirtualAccount va = VirtualAccount.builder().vaNumber("VA-AED-1").currencyCode("AED").status(VirtualAccount.VaStatus.ACTIVE).build();
        va.setId(UUID.randomUUID());
        va.setCorporateId(CORP);
        when(accounts.findByVaNumber("VA-AED-1")).thenReturn(Optional.of(va));
        when(mandates.saveAll(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void ibanCheckDigitsAreVerified() {
        assertEquals("GB33BUKB20201555555555", MandateService.iban("GB33 BUKB 2020 1555 5555 55"));
        assertThrows(BusinessException.class, () -> MandateService.iban("GB34BUKB20201555555555"));
        assertThrows(BusinessException.class, () -> MandateService.iban("not an iban"));
    }

    @Test
    void fileImportsEveryRowWithItsLimits() throws Exception {
        knownCustomerAndAccount();
        String csv = HEADER
                + "MND-1,Acme Trading LLC,VA-AED-1,uae_dds,2026-11-01,AE070331234567890123456,\"5,000\",1/MONTH,RECURRING\n"
                + "MND-2,Acme Trading LLC,VA-AED-1,UAE_DDS,2026-11-01,AE070331234567890123456,,,one-off\n";

        MandateService.ImportResult result = service.importFile(CORP, new StringReader(csv));

        assertEquals(2, result.imported());
        MandateService.MandateView first = result.mandates().get(0);
        assertEquals("UAE_DDS", first.schemeCode());
        assertEquals("AED", first.currency()); // from the receiving account when the file gives none
        assertEquals(new BigDecimal("5000"), first.maxPerCollection());
        assertEquals(1, first.frequencyCount());
        assertEquals(CustomerMandate.Period.MONTH, first.frequencyPeriod());
        assertEquals(CustomerMandate.Source.FILE, first.source());
        assertEquals(CustomerMandate.Type.ONE_OFF, result.mandates().get(1).mandateType());
    }

    @Test
    void oneBadRowRefusesTheWholeFile() {
        knownCustomerAndAccount();
        String csv = HEADER
                + "MND-1,Acme Trading LLC,VA-AED-1,UAE_DDS,2026-11-01,AE070331234567890123456,,,\n"
                + "MND-1,Acme Trading LLC,VA-AED-1,UAE_DDS,2026-11-01,AE070331234567890123456,,,\n"
                + "MND-3,Nobody Ltd,VA-AED-1,UAE_DDS,2026-11-01,AE070331234567890123456,,,\n"
                + "MND-4,Twin Co,VA-AED-1,UAE_DDS,2026-11-01,AE070331234567890123456,,,\n";

        BusinessException e = assertThrows(BusinessException.class, () -> service.importFile(CORP, new StringReader(csv)));

        assertTrue(e.getMessage().contains("Line 3: Mandate reference MND-1 already exists"), e.getMessage());
        assertTrue(e.getMessage().contains("Line 4: No customer with code or name Nobody Ltd"), e.getMessage());
        assertTrue(e.getMessage().contains("Line 5: 2 customers are called Twin Co; use the party code"), e.getMessage());
        verify(mandates, never()).saveAll(any());
    }
}
