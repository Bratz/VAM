package com.bank.vam.entity.notification;

import com.bank.vam.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Notification Entity - Event-driven notification records.
 * 
 * Supports multiple notification types:
 * - Payment notifications (received, sent)
 * - Invoice notifications (due, overdue)
 * - Approval notifications
 * - Balance alerts
 * - System alerts
 * 
 * Delivery channels:
 * - IN_APP: In-application notification
 * - EMAIL: Email delivery
 * - SMS: SMS delivery
 * - PUSH: Push notification
 * - WEBHOOK: Webhook callback
 */
@Entity
@Table(name = "notifications", indexes = {
    @Index(name = "idx_notif_corporate", columnList = "corporate_id"),
    @Index(name = "idx_notif_user", columnList = "user_id"),
    @Index(name = "idx_notif_type", columnList = "notification_type"),
    @Index(name = "idx_notif_status", columnList = "delivery_status"),
    @Index(name = "idx_notif_reference", columnList = "reference_type, reference_id"),
    @Index(name = "idx_notif_created", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification extends BaseEntity {

    // ========================================================================
    // TARGET
    // ========================================================================

    @Column(name = "corporate_id")
    private UUID corporateId;

    @Column(name = "user_id", length = 100)
    private String userId;

    @Column(name = "recipient_email", length = 200)
    private String recipientEmail;

    @Column(name = "recipient_mobile", length = 30)
    private String recipientMobile;

    // ========================================================================
    // NOTIFICATION DETAILS
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 50)
    private NotificationType notificationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 30)
    @Builder.Default
    private NotificationCategory category = NotificationCategory.INFO;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", length = 20)
    @Builder.Default
    private NotificationPriority priority = NotificationPriority.NORMAL;

    // ========================================================================
    // CONTENT
    // ========================================================================

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "summary", length = 500)
    private String summary;

    // ========================================================================
    // REFERENCE
    // ========================================================================

    @Column(name = "reference_type", length = 50)
    private String referenceType;

    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(name = "reference_number", length = 100)
    private String referenceNumber;

    // ========================================================================
    // DELIVERY
    // ========================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    @Builder.Default
    private DeliveryChannel channel = DeliveryChannel.IN_APP;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", length = 20)
    @Builder.Default
    private DeliveryStatus deliveryStatus = DeliveryStatus.PENDING;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    // ========================================================================
    // RETRY
    // ========================================================================

    @Column(name = "retry_count")
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "last_retry_at")
    private LocalDateTime lastRetryAt;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    // ========================================================================
    // EXPIRY
    // ========================================================================

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    // ========================================================================
    // METADATA
    // ========================================================================

    @Column(name = "metadata", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String metadata;

    @Column(name = "action_url", length = 500)
    private String actionUrl;

    @Column(name = "action_label", length = 100)
    private String actionLabel;

    // ========================================================================
    // ENUMS
    // ========================================================================

    public enum NotificationType {
        // Payment notifications
        PAYMENT_RECEIVED,
        PAYMENT_SENT,
        PAYMENT_FAILED,
        PAYMENT_REVERSED,
        
        // Invoice/Receivable notifications
        INVOICE_CREATED,
        INVOICE_DUE_SOON,
        INVOICE_DUE,
        INVOICE_OVERDUE,
        INVOICE_PAID,
        
        // Payable notifications
        PAYABLE_CREATED,
        PAYABLE_DUE_SOON,
        PAYABLE_DUE,
        PAYABLE_OVERDUE,
        PAYABLE_PAID,
        
        // Approval notifications
        APPROVAL_REQUIRED,
        APPROVAL_GRANTED,
        APPROVAL_REJECTED,
        
        // Batch notifications
        BATCH_CREATED,
        BATCH_APPROVED,
        BATCH_COMPLETED,
        BATCH_FAILED,
        
        // Balance notifications
        BALANCE_LOW,
        BALANCE_HIGH,
        BALANCE_THRESHOLD,
        
        // Limit notifications
        LIMIT_BREACH,
        LIMIT_WARNING,
        
        // Reconciliation notifications
        RECONCILIATION_SUCCESS,
        RECONCILIATION_FAILED,
        UNMATCHED_PAYMENT,
        
        // VIBAN notifications
        VIBAN_CREATED,
        VIBAN_EXPIRED,
        VIBAN_PAYMENT_RECEIVED,
        
        // System notifications
        SYSTEM_ALERT,
        SYSTEM_MAINTENANCE,
        SYSTEM_ERROR
    }

    public enum NotificationCategory {
        INFO,       // Informational
        WARNING,    // Warning
        ERROR,      // Error
        SUCCESS,    // Success
        URGENT      // Urgent action required
    }

    public enum NotificationPriority {
        LOW,        // Low priority
        NORMAL,     // Normal priority
        HIGH,       // High priority
        URGENT      // Urgent
    }

    public enum DeliveryChannel {
        IN_APP,     // In-application notification
        EMAIL,      // Email
        SMS,        // SMS
        PUSH,       // Push notification
        WEBHOOK     // Webhook callback
    }

    public enum DeliveryStatus {
        PENDING,    // Awaiting delivery
        SENT,       // Sent
        DELIVERED,  // Delivered
        FAILED,     // Delivery failed
        READ        // Read by user
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    /**
     * Check if notification is pending.
     */
    public boolean isPending() {
        return deliveryStatus == DeliveryStatus.PENDING;
    }

    /**
     * Check if notification was delivered.
     */
    public boolean isDelivered() {
        return deliveryStatus == DeliveryStatus.DELIVERED || deliveryStatus == DeliveryStatus.READ;
    }

    /**
     * Check if notification was read.
     */
    public boolean isRead() {
        return deliveryStatus == DeliveryStatus.READ;
    }

    /**
     * Check if notification failed.
     */
    public boolean isFailed() {
        return deliveryStatus == DeliveryStatus.FAILED;
    }

    /**
     * Check if notification is expired.
     */
    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }

    /**
     * Check if notification can be retried.
     */
    public boolean canRetry() {
        return isFailed() && retryCount < 3;
    }

    /**
     * Mark as sent.
     */
    public void markSent() {
        this.deliveryStatus = DeliveryStatus.SENT;
        this.sentAt = LocalDateTime.now();
    }

    /**
     * Mark as delivered.
     */
    public void markDelivered() {
        this.deliveryStatus = DeliveryStatus.DELIVERED;
        this.deliveredAt = LocalDateTime.now();
    }

    /**
     * Mark as read.
     */
    public void markRead() {
        this.deliveryStatus = DeliveryStatus.READ;
        this.readAt = LocalDateTime.now();
    }

    /**
     * Mark as failed.
     */
    public void markFailed(String errorMessage) {
        this.deliveryStatus = DeliveryStatus.FAILED;
        this.errorMessage = errorMessage;
    }

    /**
     * Retry delivery.
     */
    public void retry() {
        this.retryCount = (retryCount != null ? retryCount : 0) + 1;
        this.lastRetryAt = LocalDateTime.now();
        this.deliveryStatus = DeliveryStatus.PENDING;
    }

    // ========================================================================
    // FACTORY METHODS
    // ========================================================================

    /**
     * Create payment received notification.
     */
    public static Notification paymentReceived(UUID corporateId, String userId,
                                                UUID transactionId, String amount,
                                                String senderName) {
        return Notification.builder()
            .corporateId(corporateId)
            .userId(userId)
            .notificationType(NotificationType.PAYMENT_RECEIVED)
            .category(NotificationCategory.SUCCESS)
            .priority(NotificationPriority.NORMAL)
            .title("Payment Received")
            .message("Payment of " + amount + " received from " + senderName)
            .referenceType("TRANSACTION")
            .referenceId(transactionId)
            .channel(DeliveryChannel.IN_APP)
            .build();
    }

    /**
     * Create approval required notification.
     */
    public static Notification approvalRequired(UUID corporateId, String approverId,
                                                 String referenceType, UUID referenceId,
                                                 String referenceNumber, String description) {
        return Notification.builder()
            .corporateId(corporateId)
            .userId(approverId)
            .notificationType(NotificationType.APPROVAL_REQUIRED)
            .category(NotificationCategory.WARNING)
            .priority(NotificationPriority.HIGH)
            .title("Approval Required")
            .message("Your approval is required for " + description)
            .referenceType(referenceType)
            .referenceId(referenceId)
            .referenceNumber(referenceNumber)
            .channel(DeliveryChannel.IN_APP)
            .actionLabel("Review & Approve")
            .build();
    }

    /**
     * Create invoice overdue notification.
     */
    public static Notification invoiceOverdue(UUID corporateId, String userId,
                                               UUID invoiceId, String invoiceNumber,
                                               String customerName, String amount,
                                               int daysOverdue) {
        return Notification.builder()
            .corporateId(corporateId)
            .userId(userId)
            .notificationType(NotificationType.INVOICE_OVERDUE)
            .category(NotificationCategory.WARNING)
            .priority(NotificationPriority.HIGH)
            .title("Invoice Overdue")
            .message("Invoice " + invoiceNumber + " for " + customerName + 
                    " (" + amount + ") is " + daysOverdue + " days overdue")
            .referenceType("RECEIVABLE")
            .referenceId(invoiceId)
            .referenceNumber(invoiceNumber)
            .channel(DeliveryChannel.IN_APP)
            .build();
    }

    /**
     * Create balance low notification.
     */
    public static Notification balanceLow(UUID corporateId, String userId,
                                           UUID accountId, String accountNumber,
                                           String currentBalance, String threshold) {
        return Notification.builder()
            .corporateId(corporateId)
            .userId(userId)
            .notificationType(NotificationType.BALANCE_LOW)
            .category(NotificationCategory.WARNING)
            .priority(NotificationPriority.HIGH)
            .title("Low Balance Alert")
            .message("Account " + accountNumber + " balance (" + currentBalance + 
                    ") is below threshold (" + threshold + ")")
            .referenceType("VIRTUAL_ACCOUNT")
            .referenceId(accountId)
            .referenceNumber(accountNumber)
            .channel(DeliveryChannel.IN_APP)
            .build();
    }

    /**
     * Create batch completed notification.
     */
    public static Notification batchCompleted(UUID corporateId, String userId,
                                               UUID batchId, String batchReference,
                                               int totalCount, int successCount,
                                               int failedCount) {
        NotificationCategory category = failedCount > 0 
            ? NotificationCategory.WARNING 
            : NotificationCategory.SUCCESS;
        
        return Notification.builder()
            .corporateId(corporateId)
            .userId(userId)
            .notificationType(NotificationType.BATCH_COMPLETED)
            .category(category)
            .priority(NotificationPriority.NORMAL)
            .title("Batch Payment Completed")
            .message("Batch " + batchReference + " completed: " + successCount + 
                    "/" + totalCount + " successful" + 
                    (failedCount > 0 ? ", " + failedCount + " failed" : ""))
            .referenceType("PAYMENT_BATCH")
            .referenceId(batchId)
            .referenceNumber(batchReference)
            .channel(DeliveryChannel.IN_APP)
            .build();
    }
}