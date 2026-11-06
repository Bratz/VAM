package com.bank.vam.service.notification;

import com.bank.vam.entity.notification.Notification;
import com.bank.vam.entity.notification.Notification.DeliveryStatus;
import com.bank.vam.entity.notification.Notification.NotificationCategory;
import com.bank.vam.entity.notification.Notification.NotificationType;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.notification.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** The in-app notifications behind the header bell: written by the features that raise them, read per corporate. */
@Service
@RequiredArgsConstructor
public class NotificationService {

    /** What the bell shows: the latest notifications and how many are unread. */
    public record Inbox(long unread, List<View> items) {
    }

    public record View(UUID id, String type, String category, String title, String message, String referenceNumber,
                       String actionUrl, String actionLabel, LocalDateTime createdAt, boolean read) {
        static View of(Notification n) {
            return new View(n.getId(), n.getNotificationType().name(), n.getCategory().name(), n.getTitle(), n.getMessage(),
                    n.getReferenceNumber(), n.getActionUrl(), n.getActionLabel(), n.getCreatedAt(), n.getReadAt() != null);
        }
    }

    private final NotificationRepository notifications;

    /**
     * Raises a notification for a corporate, once per type and reference: a second call for the same thing (say, the
     * same deal's maturity) does nothing. Returns false when it was already raised.
     *
     * @param actionUrl the app page to open, e.g. "fx-deals"
     */
    @Transactional
    public boolean notifyOnce(UUID corporateId, NotificationType type, NotificationCategory category, String title,
                              String message, String referenceType, UUID referenceId, String referenceNumber,
                              String actionUrl, String actionLabel) {
        if (referenceId != null && notifications.existsByNotificationTypeAndReferenceId(type, referenceId)) {
            return false;
        }
        Notification n = new Notification();
        n.setCorporateId(corporateId);
        n.setNotificationType(type);
        n.setCategory(category);
        n.setTitle(title);
        n.setMessage(message);
        n.setReferenceType(referenceType);
        n.setReferenceId(referenceId);
        n.setReferenceNumber(referenceNumber);
        n.setActionUrl(actionUrl);
        n.setActionLabel(actionLabel);
        n.setDeliveryStatus(DeliveryStatus.DELIVERED);
        n.setSentAt(LocalDateTime.now());
        n.setDeliveredAt(LocalDateTime.now());
        notifications.save(n);
        return true;
    }

    /** @param corporateId one corporate, or null for all (the bank's view) */
    public Inbox inbox(UUID corporateId) {
        List<Notification> latest = corporateId == null ? notifications.findTop30ByOrderByCreatedAtDesc()
                : notifications.findTop30ByCorporateIdOrderByCreatedAtDesc(corporateId);
        long unread = corporateId == null ? notifications.countByReadAtIsNull() : notifications.countByCorporateIdAndReadAtIsNull(corporateId);
        return new Inbox(unread, latest.stream().map(View::of).toList());
    }

    @Transactional
    public void markRead(UUID id) {
        Notification n = notifications.findById(id).orElseThrow(() -> new ResourceNotFoundException("Notification not found: " + id));
        read(n);
    }

    @Transactional
    public int markAllRead(UUID corporateId) {
        List<Notification> unread = corporateId == null ? notifications.findByReadAtIsNull()
                : notifications.findByCorporateIdAndReadAtIsNull(corporateId);
        unread.forEach(this::read);
        return unread.size();
    }

    private void read(Notification n) {
        if (n.getReadAt() == null) {
            n.setReadAt(LocalDateTime.now());
            n.setDeliveryStatus(DeliveryStatus.READ);
            notifications.save(n);
        }
    }
}
