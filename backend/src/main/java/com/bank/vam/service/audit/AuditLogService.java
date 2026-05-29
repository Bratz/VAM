package com.bank.vam.service.audit;

import com.bank.vam.entity.audit.AuditLog;
import com.bank.vam.repository.audit.AuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Records lightweight audit events. Stateless, non-blocking-callers safe:
 * an audit failure is logged and swallowed — the calling business operation
 * must not fail because the audit write failed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void record(String eventType, String entityType, UUID entityId, String summary, Map<String, Object> payload) {
        try {
            AuditLog log = AuditLog.builder()
                    .eventType(eventType)
                    .entityType(entityType)
                    .entityId(entityId)
                    .actor(currentActor())
                    .summary(summary)
                    .payload(payload != null ? toJson(payload) : null)
                    .build();
            repository.save(log);
        } catch (Exception e) {
            // Never let an audit failure break a business transaction.
            log.warn("Audit write failed for event {}: {}", eventType, e.getMessage());
        }
    }

    private String currentActor() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            return auth != null && auth.isAuthenticated() ? auth.getName() : "SYSTEM";
        } catch (Exception e) {
            return "SYSTEM";
        }
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{\"_serializationError\":\"" + e.getMessage() + "\"}";
        }
    }
}
