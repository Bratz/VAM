package com.bank.vam.service.audit;

import com.bank.vam.entity.audit.AuditLog;
import com.bank.vam.repository.audit.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves the hash chain actually links rows together, not just hashes each
 * row's own content in isolation.
 */
class AuditLogServiceTest {

    private final AuditLogRepository repository = mock(AuditLogRepository.class);
    private final AuditLogService service = new AuditLogService(repository, new ObjectMapper());

    @Test
    void firstEverRow_chainsFromGenesis() {
        when(repository.findTopByOrderByCreatedAtDesc()).thenReturn(Optional.empty());

        service.record("POOL_CREATED", "NotionalPool", null, "created pool", Map.of("k", "v"));

        AuditLog saved = captureSaved();
        assertThat(saved.getPreviousHash()).isEqualTo("GENESIS");
        assertThat(saved.getEntryHash()).isNotBlank();
    }

    @Test
    void secondRow_chainsFromFirstRowsEntryHash() {
        AuditLog firstRow = AuditLog.builder().entryHash("abc123").build();
        when(repository.findTopByOrderByCreatedAtDesc()).thenReturn(Optional.of(firstRow));

        service.record("POOL_UPDATED", "NotionalPool", null, "updated pool", Map.of("k", "v2"));

        AuditLog saved = captureSaved();
        assertThat(saved.getPreviousHash()).isEqualTo("abc123");
    }

    @Test
    void identicalArguments_produceDifferentEntryHash_becausePreviousHashDiffers() {
        when(repository.findTopByOrderByCreatedAtDesc())
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(AuditLog.builder().entryHash("first-hash").build()));

        service.record("POOL_CREATED", "NotionalPool", null, "same summary", Map.of("k", "v"));
        service.record("POOL_CREATED", "NotionalPool", null, "same summary", Map.of("k", "v"));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository, times(2)).save(captor.capture());
        String firstEntryHash = captor.getAllValues().get(0).getEntryHash();
        String secondEntryHash = captor.getAllValues().get(1).getEntryHash();
        assertThat(secondEntryHash).isNotEqualTo(firstEntryHash);
    }

    private AuditLog captureSaved() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
