package com.bank.vam.ai.copilot.tools.read;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.entity.audit.AuditLog;
import com.bank.vam.repository.audit.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Read tool: recent audit-log events.
 *
 * <p>Mapped intents (P3): {@code RECENT_ACTIVITY}.
 *
 * <p>Parameters:
 * <ul>
 *   <li>{@code eventType} — filter by event type (e.g. {@code POOL_CREATED})</li>
 *   <li>{@code entityType} + {@code entityId} — combo filter for a specific entity</li>
 *   <li>{@code limit} — max rows (default 25, cap 200)</li>
 * </ul>
 *
 * <p>Output shape:
 * <pre>
 *   {
 *     totalMatched, returned,
 *     filter: { eventType, entityType, entityId },
 *     events: [
 *       { id, eventType, entityType, entityId, actor, summary, createdAt }
 *     ]
 *   }
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetAuditTrailTool implements CopilotTool {

    static final int DEFAULT_LIMIT = 25;
    static final int MAX_LIMIT = 200;

    private final AuditLogRepository auditLogRepository;

    @Override
    public String name() {
        return "get_audit_trail";
    }

    @Override
    public String description() {
        return "List recent audit events, optionally filtered by event type or entity.";
    }

    @Override
    public Map<String, Map<String, Object>> parameterSchema() {
        return Map.of(
                "eventType", Map.of("type", "string",
                        "description", "Event type to filter on (e.g. POOL_CREATED)"),
                "entityType", Map.of("type", "string",
                        "description", "Entity type (e.g. NotionalPool) — pair with entityId"),
                "entityId", Map.of("type", "string",
                        "description", "Target entity UUID — pair with entityType"),
                "limit", Map.of("type", "integer",
                        "description", "Max rows to return (default 25, cap 200)")
        );
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(ToolContext context, Map<String, Object> params) {
        try {
            String eventType = paramString(params, "eventType", null);
            String entityType = paramString(params, "entityType", null);
            String entityIdParam = paramString(params, "entityId", null);
            int limit = Math.min(Math.max(1, paramInt(params, "limit", DEFAULT_LIMIT)), MAX_LIMIT);

            UUID entityId = parseUuid(entityIdParam);

            // Pick the most specific finder available. Corporate-scoped whenever the
            // caller has a scope — a scoped caller must never see another corporate's
            // governance events, including rows with no corporateId recorded at all.
            Page<AuditLog> page;
            PageRequest pageable = PageRequest.of(0, limit,
                    Sort.by(Sort.Direction.DESC, "createdAt"));

            if (context.hasCorporateScope()) {
                UUID corporateId = context.corporateId();
                if (entityType != null && entityId != null) {
                    page = auditLogRepository.findByCorporateIdAndEntityTypeAndEntityIdOrderByCreatedAtDesc(
                            corporateId, entityType, entityId, pageable);
                } else if (eventType != null) {
                    page = auditLogRepository.findByCorporateIdAndEventTypeOrderByCreatedAtDesc(
                            corporateId, eventType, pageable);
                } else {
                    page = auditLogRepository.findByCorporateIdOrderByCreatedAtDesc(corporateId, pageable);
                }
            } else if (entityType != null && entityId != null) {
                page = auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
                        entityType, entityId, pageable);
            } else if (eventType != null) {
                page = auditLogRepository.findByEventTypeOrderByCreatedAtDesc(eventType, pageable);
            } else {
                page = auditLogRepository.findAll(pageable);
            }

            List<Map<String, Object>> rows = page.getContent().stream()
                    .map(this::toRow)
                    .collect(Collectors.toList());

            String summary = String.format("%d audit event%s%s%s",
                    rows.size(), rows.size() == 1 ? "" : "s",
                    eventType != null ? " of type " + eventType : "",
                    (entityType != null && entityId != null)
                            ? " for " + entityType + " " + entityId : "");

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalMatched", page.getTotalElements());
            data.put("returned", rows.size());
            Map<String, Object> filter = new LinkedHashMap<>();
            filter.put("eventType", eventType);
            filter.put("entityType", entityType);
            filter.put("entityId", entityId == null ? null : entityId.toString());
            data.put("filter", filter);
            data.put("events", rows);

            return ToolResult.ok(name(), summary, data);
        } catch (Exception e) {
            log.warn("get_audit_trail failed", e);
            return ToolResult.error(name(), "Failed to fetch audit trail: " + e.getMessage());
        }
    }

    private UUID parseUuid(String s) {
        if (s == null) return null;
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Map<String, Object> toRow(AuditLog a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId() == null ? null : a.getId().toString());
        m.put("eventType", a.getEventType());
        m.put("entityType", a.getEntityType());
        m.put("entityId", a.getEntityId() == null ? null : a.getEntityId().toString());
        m.put("actor", a.getActor());
        m.put("summary", a.getSummary());
        m.put("createdAt", a.getCreatedAt() == null ? null : a.getCreatedAt().toString());
        return m;
    }
}
