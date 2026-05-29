package com.bank.vam.repository.integration;

import com.bank.vam.entity.integration.IntegrationConnection;
import com.bank.vam.entity.integration.IntegrationConnector;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Integration Connections
 */
@Repository
public interface IntegrationConnectionRepository extends JpaRepository<IntegrationConnection, UUID> {

    /**
     * Find connection with connector eagerly loaded
     */
    @Query("SELECT c FROM IntegrationConnection c JOIN FETCH c.connector WHERE c.id = :id")
    Optional<IntegrationConnection> findByIdWithConnector(@Param("id") UUID id);

    /**
     * Find all connections with connectors eagerly loaded
     */
    @Query("SELECT DISTINCT c FROM IntegrationConnection c JOIN FETCH c.connector ORDER BY c.createdAt DESC")
    List<IntegrationConnection> findAllWithConnector();

    /**
     * Find connections by connector
     */
    List<IntegrationConnection> findByConnector(IntegrationConnector connector);

    /**
     * Find connections by connector ID
     */
    List<IntegrationConnection> findByConnectorId(UUID connectorId);

    /**
     * Find connections by status
     */
    List<IntegrationConnection> findByStatus(IntegrationConnection.ConnectionStatus status);

    /**
     * Find connections by environment
     */
    List<IntegrationConnection> findByEnvironment(IntegrationConnection.Environment environment);

    /**
     * Find connections by connector category
     */
    @Query("SELECT c FROM IntegrationConnection c JOIN FETCH c.connector con WHERE con.category = :category ORDER BY c.createdAt DESC")
    List<IntegrationConnection> findByConnectorCategory(@Param("category") IntegrationConnector.ConnectorCategory category);

    /**
     * Find connections by category and status
     */
    @Query("SELECT c FROM IntegrationConnection c JOIN FETCH c.connector con " +
           "WHERE con.category = :category AND c.status = :status ORDER BY c.createdAt DESC")
    List<IntegrationConnection> findByCategoryAndStatus(
            @Param("category") IntegrationConnector.ConnectorCategory category,
            @Param("status") IntegrationConnection.ConnectionStatus status);

    /**
     * Find Open Banking connections
     */
    @Query("SELECT c FROM IntegrationConnection c JOIN FETCH c.connector con WHERE con.category = 'OPEN_BANKING' ORDER BY c.createdAt DESC")
    List<IntegrationConnection> findOpenBankingConnections();

    /**
     * Count connected connections
     */
    @Query("SELECT COUNT(c) FROM IntegrationConnection c WHERE c.status = 'CONNECTED'")
    long countConnected();

    /**
     * Count error connections
     */
    @Query("SELECT COUNT(c) FROM IntegrationConnection c WHERE c.status = 'ERROR'")
    long countErrors();

    /**
     * Count syncing connections
     */
    @Query("SELECT COUNT(c) FROM IntegrationConnection c WHERE c.status = 'SYNCING'")
    long countSyncing();

    /**
     * Count connections by category
     */
    @Query("SELECT COUNT(c) FROM IntegrationConnection c JOIN c.connector con WHERE con.category = :category")
    long countByCategory(@Param("category") IntegrationConnector.ConnectorCategory category);

    /**
     * Find connections due for sync
     */
    @Query("SELECT c FROM IntegrationConnection c WHERE c.status = 'CONNECTED' AND " +
           "(c.nextSyncAt IS NULL OR c.nextSyncAt <= :now) ORDER BY c.nextSyncAt ASC")
    List<IntegrationConnection> findDueForSync(@Param("now") LocalDateTime now);

    /**
     * Find connections with expiring consents (for Open Banking)
     */
    @Query("SELECT c FROM IntegrationConnection c JOIN c.connector con " +
           "WHERE con.category = 'OPEN_BANKING' AND c.status = 'CONNECTED' " +
           "ORDER BY c.createdAt DESC")
    List<IntegrationConnection> findOpenBankingConnectionsWithExpiringConsents();

    /**
     * Complex filter query
     */
    @Query("SELECT c FROM IntegrationConnection c JOIN FETCH c.connector con WHERE " +
           "(:category IS NULL OR con.category = :category) AND " +
           "(:status IS NULL OR c.status = :status) AND " +
           "(:environment IS NULL OR c.environment = :environment) " +
           "ORDER BY c.createdAt DESC")
    List<IntegrationConnection> findWithFilters(
            @Param("category") IntegrationConnector.ConnectorCategory category,
            @Param("status") IntegrationConnection.ConnectionStatus status,
            @Param("environment") IntegrationConnection.Environment environment);

    /**
     * Check if connector has active connections
     */
    @Query("SELECT COUNT(c) > 0 FROM IntegrationConnection c WHERE c.connector.id = :connectorId AND c.status = 'CONNECTED'")
    boolean hasActiveConnections(@Param("connectorId") UUID connectorId);

    /**
     * Count connections by connector
     */
    @Query("SELECT COUNT(c) FROM IntegrationConnection c WHERE c.connector.id = :connectorId")
    long countByConnectorId(@Param("connectorId") UUID connectorId);
}
