package com.bank.vam.repository.escrow;

import com.bank.vam.entity.escrow.EscrowContract;
import com.bank.vam.entity.escrow.EscrowContract.EscrowStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EscrowContractRepository extends JpaRepository<EscrowContract, UUID> {

    Optional<EscrowContract> findByEscrowReference(String escrowReference);

    List<EscrowContract> findByStatus(EscrowStatus status);

    List<EscrowContract> findByCorporateId(UUID corporateId);

    List<EscrowContract> findByBuyerId(UUID buyerId);

    List<EscrowContract> findBySellerId(UUID sellerId);

    List<EscrowContract> findByEscrowVaId(UUID escrowVaId);

    @Query("SELECT e FROM EscrowContract e WHERE e.expiryDate <= :date AND e.status NOT IN ('RELEASED', 'CANCELLED', 'EXPIRED')")
    List<EscrowContract> findExpiring(@Param("date") LocalDate date);

    @Query("SELECT e FROM EscrowContract e WHERE e.corporateId = :corporateId AND e.status = :status")
    List<EscrowContract> findByCorporateIdAndStatus(@Param("corporateId") UUID corporateId, 
                                                     @Param("status") EscrowStatus status);

    @Query("SELECT COUNT(e) FROM EscrowContract e WHERE e.corporateId = :corporateId AND e.status = 'FUNDED'")
    long countActiveByCorporateId(@Param("corporateId") UUID corporateId);

    @Query("SELECT SUM(e.currentBalance) FROM EscrowContract e WHERE e.corporateId = :corporateId AND e.status IN ('FUNDED', 'PARTIALLY_RELEASED')")
    java.math.BigDecimal sumActiveBalanceByCorporateId(@Param("corporateId") UUID corporateId);
}