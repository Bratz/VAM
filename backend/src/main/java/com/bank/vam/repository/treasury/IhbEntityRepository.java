package com.bank.vam.repository.treasury;

import com.bank.vam.entity.treasury.IhbEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IhbEntityRepository extends JpaRepository<IhbEntity, UUID> {
    Optional<IhbEntity> findByEntityCode(String entityCode);
    List<IhbEntity> findByStatus(IhbEntity.EntityStatus status);
    List<IhbEntity> findByEntityType(IhbEntity.EntityType entityType);
}
