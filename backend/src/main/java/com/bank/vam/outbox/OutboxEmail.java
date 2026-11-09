package com.bank.vam.outbox;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * An email the platform would send. There is no mail server: emails are kept here and shown in the portal's
 * Outbox (a simulated send), with the link they carry so the portal can show its QR code.
 */
@Entity
@Table(name = "outbox_emails", indexes = {
        @Index(name = "idx_outbox_corporate", columnList = "corporate_id"),
        @Index(name = "idx_outbox_related", columnList = "related_id")
})
@Getter
@Setter
public class OutboxEmail extends BaseEntity {

    @Column(name = "corporate_id")
    private UUID corporateId;

    /** Null when the recipient has no email address; then {@code status} is NO_ADDRESS. */
    @Column(name = "to_address", length = 200)
    private String toAddress;

    @Column(name = "to_name", length = 200)
    private String toName;

    @Column(name = "subject", nullable = false, length = 300)
    private String subject;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    /** What the email is about, e.g. REQUEST_TO_PAY, and that record's id (an invoice). */
    @Column(name = "related_type", length = 30)
    private String relatedType;

    @Column(name = "related_id")
    private UUID relatedId;

    @Column(name = "related_ref", length = 60)
    private String relatedRef;

    /** The link the email carries (the invoice's pay page); the portal renders its QR code. */
    @Column(name = "link", length = 300)
    private String link;

    /** SENT (simulated: kept in the outbox) or NO_ADDRESS (nothing to send to). Plain text, no enum CHECK to outgrow. */
    @Column(name = "status", nullable = false, length = 20)
    private String status;
}
