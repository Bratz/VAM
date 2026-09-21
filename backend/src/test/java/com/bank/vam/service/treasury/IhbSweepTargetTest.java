package com.bank.vam.service.treasury;

import com.bank.vam.dto.treasury.IhbDto;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.repository.PhysicalAccountRepository;
import com.bank.vam.repository.ProgramRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.HierarchyNodeRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Enabling IHB links the entity to a treasury center for sweeping. That center must belong to
 * the same corporate -- picking any lending entity moved cash across corporates.
 */
class IhbSweepTargetTest {

    private final LegalEntityRepository entities = mock(LegalEntityRepository.class);
    private final VirtualAccountRepository vas = mock(VirtualAccountRepository.class);
    private final IhbUnifiedService service = new IhbUnifiedService(entities, vas,
        mock(FeePostingService.class), mock(com.bank.vam.repository.treasury.SweepRuleRepository.class),
        mock(com.bank.vam.repository.credit.InterestConfigurationRepository.class), mock(com.bank.vam.service.tax.TaxService.class),
        new com.bank.vam.config.MarketProfileProperties(), mock(HierarchyNodeRepository.class),
        mock(ProgramRepository.class), mock(PhysicalAccountRepository.class));

    @Test
    void treasuryCenterIsLookedUpInTheEntitysOwnCorporateOnly() {
        UUID corporateB = UUID.randomUUID();
        LegalEntity entity = new LegalEntity();
        entity.setId(UUID.randomUUID());
        entity.setCorporateId(corporateB);
        entity.setEntityCode("SUB-B");
        when(entities.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(entities.save(any())).thenAnswer(i -> i.getArgument(0));
        when(entities.findByCorporateIdAndCanLendTrue(corporateB)).thenReturn(List.of(entity)); // only itself

        IhbDto.EnableIhbRequest req = new IhbDto.EnableIhbRequest();
        req.setAutoEnrollAccounts(false);
        service.enableIhb(entity.getId(), req);

        verify(entities).findByCorporateIdAndCanLendTrue(corporateB);
        verify(entities, never()).findByCanLendTrue();
        verify(vas, never()).findByOwningEntityId(any());   // itself is no sweep target, so no rule
    }
}
