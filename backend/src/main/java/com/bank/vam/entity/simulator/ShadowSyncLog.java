package com.bank.vam.entity.simulator;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One external-feed sync event for a physical account. Append-only, immutable
 * — deliberately NOT a {@code BaseEntity} (updated_at/version are noise on a
 * high-volume event series). The Phase-4 Source-quality line aggregates
 * trailing-90d {@code miss_rate} + {@code avg_lag_hours} from these rows.
 *
 * @see com.bank.vam.service.simulator.SourceQualityService
 */
@Entity
@Table(name = "shadow_sync_log", indexes = {
        @Index(name = "idx_ssl_pa_expected",
                columnList = "physical_account_id, expected_sync_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShadowSyncLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "physical_account_id", nullable = false)
    private UUID physicalAccountId;

    @Column(name = "data_source", nullable = false, length = 30)
    private String dataSource;

    @Column(name = "expected_sync_at", nullable = false)
    private LocalDateTime expectedSyncAt;

    /** NULL = the expected sync did not arrive (missed). */
    @Column(name = "actual_sync_at")
    private LocalDateTime actualSyncAt;

    @Column(name = "lag_minutes")
    private Integer lagMinutes;

    /** SUCCESS | FAILED | MISSED — anything ≠ SUCCESS counts as a miss. */
    @Column(name = "sync_status", nullable = false, length = 20)
    private String syncStatus;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
