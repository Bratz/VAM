package com.bank.vam.service.party;

import com.bank.vam.dto.party.PartyDto.CreatePartyRequest;
import com.bank.vam.dto.party.PartyDto.PartyListResponse;
import com.bank.vam.entity.party.Party;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.party.PartyBankAccountRepository;
import com.bank.vam.repository.party.PartyDocumentRepository;
import com.bank.vam.repository.party.PartyRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Parties come from the database or not at all: no demo parties, counts or corporates. */
class PartyServiceTest {

    private final PartyRepository partyRepository = mock(PartyRepository.class);
    private final PartyBankAccountRepository bankAccountRepository = mock(PartyBankAccountRepository.class);
    private final PartyDocumentRepository documentRepository = mock(PartyDocumentRepository.class);
    private final PartyService service = new PartyService(partyRepository, bankAccountRepository, documentRepository);

    private static final UUID CORPORATE_ID = UUID.randomUUID();

    @Test
    void noPartiesListsNoParties() {
        when(partyRepository.findByCorporateId(eq(CORPORATE_ID), any())).thenReturn(Page.empty());
        when(partyRepository.getExtendedStats(CORPORATE_ID)).thenReturn(List.<Object[]>of(new Object[]{0L}));

        PartyListResponse list = service.getAllParties(CORPORATE_ID, null);

        assertTrue(list.getParties().isEmpty());
        assertEquals(0L, list.getTotalCount());
        assertEquals(0L, list.getStats().getTotalParties());
        assertEquals(0L, list.getStats().getVendors());
    }

    @Test
    void emptyQueriesStayEmpty() {
        UUID partyId = UUID.randomUUID();
        assertTrue(service.getPoboEligibleVendors(CORPORATE_ID).isEmpty());
        assertTrue(service.getIntercompanyParties(CORPORATE_ID).isEmpty());
        assertTrue(service.getNettingEligibleParties(CORPORATE_ID).isEmpty());
        assertTrue(service.getPartyBankAccounts(partyId).isEmpty());
        assertTrue(service.getPartyDocuments(partyId).isEmpty());
    }

    @Test
    void anUnknownPartyIsNotFound() {
        UUID partyId = UUID.randomUUID();
        when(partyRepository.findById(partyId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getPartyDetail(partyId));
    }

    @Test
    void aPartyWithoutACorporateIsRefused() {
        CreatePartyRequest request = CreatePartyRequest.builder().partyType("COMPANY").legalName("Acme").build();

        BusinessException e = assertThrows(BusinessException.class, () -> service.createParty(null, request));
        assertEquals("corporateId is required", e.getMessage());
        verify(partyRepository, never()).save(any());
    }

    @Test
    void aNewPartyIsNotScreenedClearAndItsDetailInventsNothing() {
        when(partyRepository.findByPartyCode(any())).thenReturn(Optional.empty());
        when(partyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createParty(CORPORATE_ID, CreatePartyRequest.builder()
                .partyType("COMPANY").legalName("Acme").roles(Set.of("CUSTOMER")).build());

        ArgumentCaptor<Party> saved = ArgumentCaptor.forClass(Party.class);
        verify(partyRepository).save(saved.capture());
        Party party = saved.getValue();
        assertEquals(CORPORATE_ID, party.getCorporateId());
        assertNull(party.getSanctionsStatus());

        party.setId(UUID.randomUUID());
        when(partyRepository.findById(party.getId())).thenReturn(Optional.of(party));
        var detail = service.getPartyDetail(party.getId());
        assertNull(detail.getParty().getSanctionsStatus());
        assertNull(detail.getCompliance().getRiskScore());
        assertNull(detail.getCompliance().getVerificationProgress());
        assertTrue(detail.getCompliance().getRiskFactors().isEmpty());
        assertTrue(detail.getCompliance().getRequiredDocuments().isEmpty());
        assertTrue(detail.getActivityLog().isEmpty());
    }
}
