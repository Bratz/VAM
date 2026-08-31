package com.bank.vam.repository.ai;

import com.bank.vam.entity.ai.BalanceAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BalanceAlertRepository extends JpaRepository<BalanceAlert, UUID> {

    List<BalanceAlert> findByActiveTrue();

    List<BalanceAlert> findByVaIdAndActiveTrue(UUID vaId);
}
