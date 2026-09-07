package com.bank.vam.repository.audit;

import com.bank.vam.entity.audit.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
    Page<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, UUID entityId, Pageable pageable);
    Page<AuditLog> findByEventTypeOrderByCreatedAtDesc(String eventType, Pageable pageable);

    // Corporate-scoped variants — used by get_audit_trail when the caller has a
    // corporate scope, so one corporate's governance events are never returned
    // to a caller entitled to another. Rows with corporateId == null (existing
    // call sites that don't resolve one yet) are excluded under scope, by design.
    Page<AuditLog> findByCorporateIdOrderByCreatedAtDesc(UUID corporateId, Pageable pageable);
    Page<AuditLog> findByCorporateIdAndEntityTypeAndEntityIdOrderByCreatedAtDesc(
            UUID corporateId, String entityType, UUID entityId, Pageable pageable);
    Page<AuditLog> findByCorporateIdAndEventTypeOrderByCreatedAtDesc(
            UUID corporateId, String eventType, Pageable pageable);
}
