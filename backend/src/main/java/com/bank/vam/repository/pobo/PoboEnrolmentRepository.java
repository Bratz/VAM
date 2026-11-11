package com.bank.vam.repository.pobo;

import com.bank.vam.entity.pobo.PoboEnrolment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PoboEnrolmentRepository extends JpaRepository<PoboEnrolment, UUID> {

    List<PoboEnrolment> findByCorporateIdOrderByCreatedAtDesc(UUID corporateId);

    List<PoboEnrolment> findBySubsidiaryEntityId(UUID subsidiaryEntityId);
}
