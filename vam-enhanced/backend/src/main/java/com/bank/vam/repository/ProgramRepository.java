package com.bank.vam.repository;

import com.bank.vam.entity.Program;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProgramRepository extends JpaRepository<Program, UUID> {

    Optional<Program> findByProgramCode(String programCode);

    List<Program> findByCorporateId(UUID corporateId);

    Page<Program> findByCorporateId(UUID corporateId, Pageable pageable);

    List<Program> findByCorporateIdAndStatus(UUID corporateId, Program.ProgramStatus status);

    List<Program> findByProgramType(Program.ProgramType programType);

    boolean existsByProgramCode(String programCode);
}
