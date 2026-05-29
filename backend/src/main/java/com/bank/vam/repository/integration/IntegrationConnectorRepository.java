package com.bank.vam.repository.integration;

import com.bank.vam.entity.integration.IntegrationConnector;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Integration Connectors
 */
@Repository
public interface IntegrationConnectorRepository extends JpaRepository<IntegrationConnector, UUID> {

    /**
     * Find connector by unique code
     */
    Optional<IntegrationConnector> findByConnectorCode(String connectorCode);

    /**
     * Find connectors by category
     */
    List<IntegrationConnector> findByCategory(IntegrationConnector.ConnectorCategory category);

    /**
     * Find connectors by category ordered by sort order
     */
    List<IntegrationConnector> findByCategoryOrderBySortOrderAsc(IntegrationConnector.ConnectorCategory category);

    /**
     * Find connectors by status
     */
    List<IntegrationConnector> findByStatus(IntegrationConnector.ConnectorStatus status);

    /**
     * Find available connectors (not deprecated or coming soon)
     */
    @Query("SELECT c FROM IntegrationConnector c WHERE c.status IN ('AVAILABLE', 'BETA') ORDER BY c.sortOrder ASC")
    List<IntegrationConnector> findAllAvailable();

    /**
     * Find connectors by region
     */
    List<IntegrationConnector> findByRegion(String region);

    /**
     * Find connectors by category and status
     */
    List<IntegrationConnector> findByCategoryAndStatus(
            IntegrationConnector.ConnectorCategory category,
            IntegrationConnector.ConnectorStatus status);

    /**
     * Search connectors by name or description
     */
    @Query("SELECT c FROM IntegrationConnector c WHERE " +
           "LOWER(c.connectorName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(c.description) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(c.connectorCode) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "ORDER BY c.sortOrder ASC")
    List<IntegrationConnector> searchConnectors(@Param("search") String search);

    /**
     * Find Open Banking connectors
     */
    @Query("SELECT c FROM IntegrationConnector c WHERE c.category = 'OPEN_BANKING' AND c.status != 'DEPRECATED' ORDER BY c.sortOrder ASC")
    List<IntegrationConnector> findOpenBankingConnectors();

    /**
     * Find Payment connectors
     */
    @Query("SELECT c FROM IntegrationConnector c WHERE c.category = 'PAYMENTS' AND c.status != 'DEPRECATED' ORDER BY c.sortOrder ASC")
    List<IntegrationConnector> findPaymentConnectors();

    /**
     * Count connectors by category
     */
    long countByCategory(IntegrationConnector.ConnectorCategory category);

    /**
     * Count available connectors
     */
    @Query("SELECT COUNT(c) FROM IntegrationConnector c WHERE c.status IN ('AVAILABLE', 'BETA')")
    long countAvailable();

    /**
     * Find connectors with specific auth type
     */
    List<IntegrationConnector> findByAuthType(IntegrationConnector.AuthType authType);

    /**
     * Complex filter query
     */
    @Query("SELECT c FROM IntegrationConnector c WHERE " +
           "(:category IS NULL OR c.category = :category) AND " +
           "(:status IS NULL OR c.status = :status) AND " +
           "(:region IS NULL OR c.region = :region) AND " +
           "(:authType IS NULL OR c.authType = :authType) AND " +
           "(:search IS NULL OR LOWER(c.connectorName) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(c.description) LIKE LOWER(CONCAT('%', :search, '%'))) " +
           "ORDER BY c.sortOrder ASC")
    List<IntegrationConnector> findWithFilters(
            @Param("category") IntegrationConnector.ConnectorCategory category,
            @Param("status") IntegrationConnector.ConnectorStatus status,
            @Param("region") String region,
            @Param("authType") IntegrationConnector.AuthType authType,
            @Param("search") String search);
}
