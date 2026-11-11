package com.bank.vam.repository.receivables;

import com.bank.vam.entity.receivables.CoboEnrolment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CoboEnrolmentRepository extends JpaRepository<CoboEnrolment, UUID> {

    List<CoboEnrolment> findByCorporateIdOrderByCreatedAtDesc(UUID corporateId);

    List<CoboEnrolment> findBySubsidiaryEntityIdAndCurrency(UUID subsidiaryEntityId, String currency);
}
