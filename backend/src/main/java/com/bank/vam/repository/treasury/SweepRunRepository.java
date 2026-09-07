package com.bank.vam.repository.treasury;

import com.bank.vam.entity.treasury.SweepRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface SweepRunRepository extends JpaRepository<SweepRun, UUID> {
}
