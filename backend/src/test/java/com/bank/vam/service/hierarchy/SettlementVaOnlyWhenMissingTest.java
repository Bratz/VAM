package com.bank.vam.service.hierarchy;

import com.bank.vam.entity.Program;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.hierarchy.HierarchyNode;
import com.bank.vam.repository.ProgramRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.HierarchyLevelConfigRepository;
import com.bank.vam.repository.hierarchy.HierarchyNodeRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** A settlement VA is created only when one is missing -- on every path, including this one. */
class SettlementVaOnlyWhenMissingTest {

    private final HierarchyNodeRepository nodes = mock(HierarchyNodeRepository.class);
    private final ProgramRepository programs = mock(ProgramRepository.class);
    private final VirtualAccountRepository vas = mock(VirtualAccountRepository.class);
    private final HierarchyService service = new HierarchyService(mock(HierarchyLevelConfigRepository.class), nodes, programs, vas);

    @Test
    void hierarchyNodePathReturnsTheExistingSettlementVa() {
        UUID programId = UUID.randomUUID();
        Program program = new Program();
        program.setId(programId);
        program.setProgramCode("PRG");
        when(programs.findById(programId)).thenReturn(Optional.of(program));

        HierarchyNode parent = new HierarchyNode();
        parent.setId(UUID.randomUUID());
        parent.setLevelNumber(2);
        parent.setVirtualAccountId(UUID.randomUUID());
        when(nodes.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(nodes.findByParentIdAndNodeCode(parent.getId(), "SETTLEMENT-EUR")).thenReturn(Optional.empty());

        // one already sits beside the node's own account (e.g. made when a transaction VA was created)
        VirtualAccount existing = new VirtualAccount();
        existing.setId(UUID.randomUUID());
        existing.setCurrencyCode("EUR");
        existing.setStatus(VirtualAccount.VaStatus.ACTIVE);
        when(vas.findByParentAccountIdAndAccountCategory(parent.getVirtualAccountId(), VirtualAccount.AccountCategory.SETTLEMENT))
            .thenReturn(List.of(existing));

        assertThat(service.createSettlementVa(programId, parent.getId(), "EUR", null)).isSameAs(existing);
        verify(vas, never()).save(any());
        verify(nodes, never()).save(any());
    }

    @Test
    void aNewOneIsLinkedUnderTheNodesOwnAccount() {
        UUID programId = UUID.randomUUID();
        Program program = new Program();
        program.setId(programId);
        program.setProgramCode("PRG");
        when(programs.findById(programId)).thenReturn(Optional.of(program));

        VirtualAccount parentVa = new VirtualAccount();
        parentVa.setId(UUID.randomUUID());
        parentVa.setHierarchyLevel(2);
        parentVa.setHierarchyPathVa("/ROOT/EMEA");
        parentVa.setOwningEntityCode("ACME-UK");
        HierarchyNode parent = new HierarchyNode();
        parent.setId(UUID.randomUUID());
        parent.setLevelNumber(2);
        parent.setVirtualAccountId(parentVa.getId());
        when(nodes.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(nodes.findByParentIdAndNodeCode(any(), any())).thenReturn(Optional.empty());
        when(nodes.save(any())).thenAnswer(i -> i.getArgument(0));
        when(vas.findById(parentVa.getId())).thenReturn(Optional.of(parentVa));
        when(vas.findByParentAccountIdAndAccountCategory(any(), any())).thenReturn(List.of());
        when(vas.save(any())).thenAnswer(i -> i.getArgument(0));

        VirtualAccount created = service.createSettlementVa(programId, parent.getId(), "EUR", null);

        assertThat(created.getParentAccountId()).isEqualTo(parentVa.getId());
        assertThat(created.getHierarchyLevel()).isEqualTo(3);
        assertThat(created.getHierarchyPathVa()).startsWith("/ROOT/EMEA/SETTLEMENT-EUR-");
        assertThat(created.getOwningEntityCode()).isEqualTo("ACME-UK");
        assertThat(created.getAccountCategory()).isEqualTo(VirtualAccount.AccountCategory.SETTLEMENT);
    }

    @Test
    void systemCategoriesAreNotForTheGenericCreate() {
        assertThat(VirtualAccount.AccountCategory.SETTLEMENT.isSystemCreated()).isTrue();
        assertThat(VirtualAccount.AccountCategory.EXCEPTION.isSystemCreated()).isTrue();
        assertThat(VirtualAccount.AccountCategory.TRANSACTION.isSystemCreated()).isFalse();
    }
}
