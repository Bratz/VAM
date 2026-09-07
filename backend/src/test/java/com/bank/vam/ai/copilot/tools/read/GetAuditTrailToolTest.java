package com.bank.vam.ai.copilot.tools.read;

import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.entity.audit.AuditLog;
import com.bank.vam.repository.audit.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression guard: {@code get_audit_trail} previously called {@code findAll()}
 * unconditionally, returning every corporate's governance events (pool
 * created/updated/member added...) to any caller. {@code AuditLog} gained a
 * nullable {@code corporateId} column for exactly this — a scoped caller must
 * use the corporate-scoped finder, never the unscoped one.
 */
class GetAuditTrailToolTest {

    @Test
    void scopedCaller_usesCorporateScopedFinder() {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        UUID corporateId = UUID.randomUUID();
        Page<AuditLog> page = new PageImpl<>(List.of());
        when(repository.findByCorporateIdOrderByCreatedAtDesc(eq(corporateId), any(Pageable.class)))
                .thenReturn(page);

        GetAuditTrailTool tool = new GetAuditTrailTool(repository);
        ToolContext scoped = new ToolContext(corporateId, "user", "USD", "US", null);

        ToolResult result = tool.execute(scoped, Map.of());

        assertThat(result.ok()).isTrue();
        verify(repository).findByCorporateIdOrderByCreatedAtDesc(eq(corporateId), any(Pageable.class));
    }

    @Test
    void unscopedCaller_usesUnscopedFinder_inAppCopilotBehaviourUnchanged() {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        Page<AuditLog> page = new PageImpl<>(List.of());
        when(repository.findAll(any(Pageable.class))).thenReturn(page);

        GetAuditTrailTool tool = new GetAuditTrailTool(repository);
        ToolContext unscoped = new ToolContext(null, "user", "USD", "US", null);

        ToolResult result = tool.execute(unscoped, Map.of());

        assertThat(result.ok()).isTrue();
        verify(repository).findAll(any(Pageable.class));
    }
}
