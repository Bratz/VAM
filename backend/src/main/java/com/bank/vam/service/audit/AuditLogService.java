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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.UUID;

/**
 * Records lightweight audit events. Stateless, non-blocking-callers safe:
 * an audit failure is logged and swallowed — the calling business operation
 * must not fail because the audit write failed.
 *
 * <p>Every row is hash-chained: {@code entryHash = SHA256(previousHash +
 * canonical(entry))}, where {@code previousHash} is the prior row's
 * {@code entryHash} (or {@code "GENESIS"} for the first row ever). Altering
 * or deleting a past row breaks the chain for everything after it, making
 * tampering detectable by recomputing hashes and comparing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private static final String GENESIS_HASH = "GENESIS";

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void record(String eventType, String entityType, UUID entityId, String summary, Map<String, Object> payload) {
        record(eventType, entityType, entityId, null, summary, payload);
    }

    /**
     * Same as {@link #record(String, String, UUID, String, Map)}, plus the owning
     * corporate — pass it whenever it's available so {@code get_audit_trail} can
     * scope by corporate instead of leaking every corporate's events.
     */
    @Transactional
    public void record(String eventType, String entityType, UUID entityId, UUID corporateId,
                        String summary, Map<String, Object> payload) {
        try {
            String payloadJson = payload != null ? toJson(payload) : null;
            String actor = currentActor();

            // ponytail: a process-local lock, not a DB-level SELECT...FOR UPDATE or a
            // distributed lock. Two concurrent writers both reading the same "most
            // recent row" and chaining from the same previousHash would corrupt the
            // chain (two rows claiming the same previousHash). This is a single-instance
            // prototype deployment (ddl-auto: update, no Flyway, permitAll dev auth) —
            // a JVM-wide lock makes the read-then-write atomic for the only writer that
            // exists today. Upgrade path if this ever runs multi-instance: SELECT ...
            // FOR UPDATE on the latest row (or a dedicated sequence/lock table).
            synchronized (AuditLogService.class) {
                String previousHash = repository.findTopByOrderByCreatedAtDesc()
                        .map(AuditLog::getEntryHash)
                        .filter(h -> h != null && !h.isBlank())
                        .orElse(GENESIS_HASH);

                String canonical = canonicalize(eventType, entityType, entityId, corporateId, actor, summary, payloadJson);
                String entryHash = sha256Hex(previousHash + canonical);

                AuditLog log = AuditLog.builder()
                        .eventType(eventType)
                        .entityType(entityType)
                        .entityId(entityId)
                        .corporateId(corporateId)
                        .actor(actor)
                        .summary(summary)
                        .payload(payloadJson)
                        .previousHash(previousHash)
                        .entryHash(entryHash)
                        .build();
                repository.save(log);
            }
        } catch (Exception e) {
            // Never let an audit failure break a business transaction.
            log.warn("Audit write failed for event {}: {}", eventType, e.getMessage());
        }
    }

    /**
     * Records what changed on one record, field by field ("credit limit: 1000000 → 500000"), from two snapshots taken
     * before and after with the same keys; numbers compare by value (1.50 = 1.5). Nothing is written when nothing
     * changed. Payload: {@code {subject, ...context, changes: [{field, from, to}]}}; context (nullable) says what the
     * values need to be read, e.g. their currency.
     */
    public void recordChanges(String eventType, String entityType, UUID entityId, UUID corporateId, String subject,
                              Map<String, Object> context, Map<String, Object> before, Map<String, Object> after) {
        java.util.List<Map<String, Object>> changes = new java.util.ArrayList<>();
        StringBuilder summary = new StringBuilder(subject).append(':');
        for (Map.Entry<String, Object> e : after.entrySet()) {
            Object from = before.get(e.getKey());
            Object to = e.getValue();
            if (sameValue(from, to)) continue;
            Map<String, Object> change = new java.util.LinkedHashMap<>();
            change.put("field", e.getKey());
            change.put("from", from);
            change.put("to", to);
            changes.add(change);
            summary.append(' ').append(e.getKey()).append(' ').append(from).append(" → ").append(to).append(';');
        }
        if (changes.isEmpty()) return;
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("subject", subject);
        if (context != null) payload.putAll(context);
        payload.put("changes", changes);
        String text = summary.substring(0, summary.length() - 1);
        record(eventType, entityType, entityId, corporateId, text.length() > 500 ? text.substring(0, 500) : text, payload);
    }

    private static boolean sameValue(Object a, Object b) {
        if (a instanceof java.math.BigDecimal x && b instanceof java.math.BigDecimal y) return x.compareTo(y) == 0;
        return java.util.Objects.equals(a, b);
    }

    /**
     * Fixed-order, control-character-delimited string of the entry's semantic
     * fields — not JSON. A hash only needs *a* deterministic byte sequence, not
     * valid JSON, and a fixed field order needs no key-sorting configuration on
     * the shared {@link ObjectMapper} (which is used elsewhere for arbitrary
     * DTOs and shouldn't be reconfigured for this one purpose).
     */
    private String canonicalize(String eventType, String entityType, UUID entityId, UUID corporateId,
                                 String actor, String summary, String payloadJson) {
        return String.join("\u0001",
                nullToEmpty(eventType),
                nullToEmpty(entityType),
                entityId == null ? "" : entityId.toString(),
                corporateId == null ? "" : corporateId.toString(),
                nullToEmpty(actor),
                nullToEmpty(summary),
                nullToEmpty(payloadJson));
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JCA algorithm on every JVM — unreachable.
            throw new IllegalStateException(e);
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
