package com.bank.vam.ai.copilot.tools.read;

import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.treasury.SweepInstruction;
import com.bank.vam.entity.treasury.SweepInstruction.InstructionStatus;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.treasury.SweepInstructionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression guard: {@code SweepInstruction} carries no {@code corporateId} of
 * its own — it belongs to a corporate only via its source/target VA. Before
 * this fix, {@code get_sweep_instructions} returned every corporate's in-flight
 * sweeps to any caller, and a direct id/idempotency-key/external-reference
 * lookup would return another corporate's instruction verbatim.
 */
class GetSweepInstructionsToolTest {

    private VirtualAccount va(UUID id, UUID corporateId) {
        VirtualAccount va = new VirtualAccount();
        va.setId(id);
        va.setCorporateId(corporateId);
        return va;
    }

    @Test
    void listing_excludesOtherCorporatesInstructions() {
        SweepInstructionRepository instructionRepository = mock(SweepInstructionRepository.class);
        VirtualAccountRepository vaRepository = mock(VirtualAccountRepository.class);

        UUID ourCorporate = UUID.randomUUID();
        UUID theirCorporate = UUID.randomUUID();
        UUID ourVaId = UUID.randomUUID();
        UUID theirVaId = UUID.randomUUID();

        SweepInstruction ours = SweepInstruction.builder()
                .sourceShadowVaId(ourVaId).targetVaId(UUID.randomUUID())
                .status(InstructionStatus.INSTRUCTED).currencyCode("USD").amount(java.math.BigDecimal.TEN)
                .build();
        ours.setId(UUID.randomUUID());
        SweepInstruction theirs = SweepInstruction.builder()
                .sourceShadowVaId(theirVaId).targetVaId(UUID.randomUUID())
                .status(InstructionStatus.INSTRUCTED).currencyCode("USD").amount(java.math.BigDecimal.ONE)
                .build();
        theirs.setId(UUID.randomUUID());

        when(instructionRepository.findByStatusInOrderByInstructedAtDesc(any()))
                .thenReturn(List.of(ours, theirs));
        when(vaRepository.findAllById(any())).thenAnswer(inv -> {
            // Only VAs actually referenced get resolved — mirrors real batch-fetch behaviour.
            Iterable<UUID> ids = inv.getArgument(0);
            List<VirtualAccount> all = List.of(va(ourVaId, ourCorporate), va(theirVaId, theirCorporate));
            return all.stream().filter(v -> {
                for (UUID id : ids) if (id.equals(v.getId())) return true;
                return false;
            }).toList();
        });

        GetSweepInstructionsTool tool = new GetSweepInstructionsTool(instructionRepository, vaRepository);
        ToolContext scoped = new ToolContext(ourCorporate, "user", "USD", "US", null);

        ToolResult result = tool.execute(scoped, Map.of());

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("totalMatched")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.data().get("instructions");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("id")).isEqualTo(ours.getId().toString());
    }

    @Test
    void directLookup_reportsNotFound_whenInstructionBelongsToAnotherCorporate() {
        SweepInstructionRepository instructionRepository = mock(SweepInstructionRepository.class);
        VirtualAccountRepository vaRepository = mock(VirtualAccountRepository.class);

        UUID ourCorporate = UUID.randomUUID();
        UUID theirCorporate = UUID.randomUUID();
        UUID theirVaId = UUID.randomUUID();
        UUID instructionId = UUID.randomUUID();

        SweepInstruction theirs = SweepInstruction.builder()
                .sourceShadowVaId(theirVaId).targetVaId(UUID.randomUUID())
                .status(InstructionStatus.SETTLED).currencyCode("USD").amount(java.math.BigDecimal.ONE)
                .build();
        theirs.setId(instructionId);

        when(instructionRepository.findById(instructionId)).thenReturn(java.util.Optional.of(theirs));
        when(vaRepository.findAllById(any())).thenReturn(List.of(va(theirVaId, theirCorporate)));

        GetSweepInstructionsTool tool = new GetSweepInstructionsTool(instructionRepository, vaRepository);
        ToolContext scoped = new ToolContext(ourCorporate, "user", "USD", "US", null);

        ToolResult result = tool.execute(scoped, Map.of("id", instructionId.toString()));

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("totalMatched")).isEqualTo(0);
        assertThat(result.summary()).contains("No instruction matched");
    }
}
