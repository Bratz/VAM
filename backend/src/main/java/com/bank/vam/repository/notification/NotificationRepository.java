package com.bank.vam.repository.notification;

import com.bank.vam.entity.notification.Notification;
import com.bank.vam.entity.notification.Notification.DeliveryChannel;
import com.bank.vam.entity.notification.Notification.DeliveryStatus;
import com.bank.vam.entity.notification.Notification.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Repository for Notification entity.
 */
@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    // ========================================================================
    // USER QUERIES
    // ========================================================================

    Page<Notification> findByUserId(String userId, Pageable pageable);
    
    List<Notification> findByUserIdAndDeliveryStatus(String userId, DeliveryStatus status);
    
    @Query("SELECT n FROM Notification n WHERE n.userId = :userId AND n.deliveryStatus != 'READ' ORDER BY n.createdAt DESC")
    List<Notification> findUnreadByUser(@Param("userId") String userId);
    
    long countByUserIdAndDeliveryStatus(String userId, DeliveryStatus status);

    // ========================================================================
    // CORPORATE QUERIES
    // ========================================================================

    Page<Notification> findByCorporateId(UUID corporateId, Pageable pageable);
    
    List<Notification> findByCorporateIdAndDeliveryStatus(UUID corporateId, DeliveryStatus status);
    
    long countByCorporateIdAndDeliveryStatus(UUID corporateId, DeliveryStatus status);

    // ========================================================================
    // TYPE QUERIES
    // ========================================================================

    List<Notification> findByNotificationType(NotificationType type);
    
    List<Notification> findByNotificationTypeAndDeliveryStatus(NotificationType type, DeliveryStatus status);

    // ========================================================================
    // CHANNEL QUERIES
    // ========================================================================

    List<Notification> findByChannel(DeliveryChannel channel);
    
    @Query("SELECT n FROM Notification n WHERE n.channel = :channel AND n.deliveryStatus = 'PENDING' ORDER BY n.priority DESC, n.createdAt ASC")
    List<Notification> findPendingByChannel(@Param("channel") DeliveryChannel channel);

    // ========================================================================
    // STATUS QUERIES
    // ========================================================================

    List<Notification> findByDeliveryStatus(DeliveryStatus status);
    
    @Query("SELECT n FROM Notification n WHERE n.deliveryStatus = 'PENDING' ORDER BY n.priority DESC, n.createdAt ASC")
    List<Notification> findAllPending();
    
    @Query("SELECT n FROM Notification n WHERE n.deliveryStatus = 'FAILED' AND n.retryCount < 3 ORDER BY n.lastRetryAt ASC NULLS FIRST")
    List<Notification> findRetryable();

    // ========================================================================
    // REFERENCE QUERIES
    // ========================================================================

    List<Notification> findByReferenceTypeAndReferenceId(String referenceType, UUID referenceId);
    
    @Query("SELECT n FROM Notification n WHERE n.referenceType = :type AND n.referenceId = :id ORDER BY n.createdAt DESC")
    List<Notification> findByReference(@Param("type") String referenceType, @Param("id") UUID referenceId);

    // ========================================================================
    // DATE QUERIES
    // ========================================================================

    @Query("SELECT n FROM Notification n WHERE n.createdAt BETWEEN :startDate AND :endDate ORDER BY n.createdAt DESC")
    List<Notification> findByCreatedAtBetween(@Param("startDate") LocalDateTime startDate, 
                                               @Param("endDate") LocalDateTime endDate);

    @Query("SELECT n FROM Notification n WHERE n.expiresAt IS NOT NULL AND n.expiresAt < CURRENT_TIMESTAMP AND n.deliveryStatus = 'PENDING'")
    List<Notification> findExpired();

    // ========================================================================
    // UPDATE QUERIES
    // ========================================================================

    @Modifying
    @Query("UPDATE Notification n SET n.deliveryStatus = :status WHERE n.id = :id")
    void updateStatus(@Param("id") UUID id, @Param("status") DeliveryStatus status);

    @Modifying
    @Query("UPDATE Notification n SET n.deliveryStatus = 'SENT', n.sentAt = CURRENT_TIMESTAMP WHERE n.id = :id")
    void markSent(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE Notification n SET n.deliveryStatus = 'DELIVERED', n.deliveredAt = CURRENT_TIMESTAMP WHERE n.id = :id")
    void markDelivered(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE Notification n SET n.deliveryStatus = 'READ', n.readAt = CURRENT_TIMESTAMP WHERE n.id = :id")
    void markRead(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE Notification n SET n.deliveryStatus = 'READ', n.readAt = CURRENT_TIMESTAMP WHERE n.userId = :userId AND n.deliveryStatus != 'READ'")
    void markAllReadByUser(@Param("userId") String userId);

    @Modifying
    @Query("UPDATE Notification n SET n.retryCount = n.retryCount + 1, n.lastRetryAt = CURRENT_TIMESTAMP, n.deliveryStatus = 'PENDING' WHERE n.id = :id")
    void retry(@Param("id") UUID id);

    // ========================================================================
    // DELETE QUERIES
    // ========================================================================

    @Modifying
    @Query("DELETE FROM Notification n WHERE n.createdAt < :before AND n.deliveryStatus IN ('READ', 'FAILED')")
    int deleteOldNotifications(@Param("before") LocalDateTime before);

    // ========================================================================
    // STATISTICS
    // ========================================================================

    @Query("SELECT n.notificationType, COUNT(n) FROM Notification n WHERE n.userId = :userId GROUP BY n.notificationType")
    List<Object[]> countByTypeForUser(@Param("userId") String userId);

    @Query("SELECT n.deliveryStatus, COUNT(n) FROM Notification n WHERE n.userId = :userId GROUP BY n.deliveryStatus")
    List<Object[]> countByStatusForUser(@Param("userId") String userId);

    @Query("SELECT COUNT(n) FROM Notification n WHERE n.userId = :userId AND n.deliveryStatus NOT IN ('READ', 'FAILED')")
    long countUnreadByUser(@Param("userId") String userId);
}