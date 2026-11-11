package com.bank.vam.entity;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public abstract class BaseEntity {

    /**
     * {@code createdBy} of a record the application made up at startup (demo terms, limits or balances) rather than
     * one the bank agreed or entered. Screens show such a record as a default.
     */
    public static final String SYSTEM_DEFAULT = "system-default";

    /**
     * True for a record the application seeded with made-up values (see {@link #SYSTEM_DEFAULT}) that nobody has
     * changed since: an edit that records who made it ({@code updatedBy}) turns it into the bank's own setting.
     */
    public boolean isSystemDefault() {
        return SYSTEM_DEFAULT.equals(createdBy) && updatedBy == null;
    }

    /** Marks {@code e} as made up at startup (see {@link #SYSTEM_DEFAULT}) and returns it, for use around a builder. */
    public static <T extends BaseEntity> T systemDefault(T e) {
        e.setCreatedBy(SYSTEM_DEFAULT);
        return e;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "updated_by")
    private String updatedBy;

    // @Version
    @Column(name = "version")
    private Long version = 0L;  // Initialize to 0 to avoid NPE on first save
}