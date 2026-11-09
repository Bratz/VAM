package com.bank.vam.collections;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/** One submission of invoices for collection. Its direct debits go out as one pain.008, kept here. */
@Entity
@Table(name = "collection_runs", indexes = @Index(name = "idx_collection_run_corp", columnList = "corporate_id"))
@Getter
@Setter
public class CollectionRun extends BaseEntity {

    @Column(name = "corporate_id", nullable = false)
    private UUID corporateId;

    @Column(name = "run_ref", nullable = false, unique = true, length = 35)
    private String runRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 10)
    private Source source = Source.INVOICES;

    /** Null when the run has no direct debits. */
    @Column(name = "pain008_xml", columnDefinition = "text")
    private String pain008Xml;

    public enum Source { INVOICES, FILE, API }
}
