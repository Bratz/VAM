package com.bank.vam.repository.hierarchy;

import com.bank.vam.entity.hierarchy.HierarchyLevelConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for HierarchyLevelConfig entity.
 * Manages hierarchy level configuration per program.
 */
@Repository
public interface HierarchyLevelConfigRepository extends JpaRepository<HierarchyLevelConfig, UUID> {

    /**
     * Find all level configs for a program, ordered by level number.
     */
    List<HierarchyLevelConfig> findByProgramIdOrderByLevelNumberAsc(UUID programId);

    /**
     * Find specific level config for a program.
     */
    Optional<HierarchyLevelConfig> findByProgramIdAndLevelNumber(UUID programId, Integer levelNumber);

    /**
     * Find required levels for a program.
     */
    @Query("SELECT c FROM HierarchyLevelConfig c WHERE c.programId = :programId AND c.isRequired = true ORDER BY c.levelNumber")
    List<HierarchyLevelConfig> findRequiredLevels(@Param("programId") UUID programId);

    /**
     * Find levels by dimension type.
     */
    List<HierarchyLevelConfig> findByProgramIdAndDimensionType(UUID programId, String dimensionType);

    /**
     * Count levels for a program.
     */
    long countByProgramId(UUID programId);

    /**
     * Check if level exists for program.
     */
    boolean existsByProgramIdAndLevelNumber(UUID programId, Integer levelNumber);

    /**
     * Delete all configs for a program.
     */
    void deleteByProgramId(UUID programId);

    /**
     * Find max level number for a program.
     */
    @Query("SELECT MAX(c.levelNumber) FROM HierarchyLevelConfig c WHERE c.programId = :programId")
    Optional<Integer> findMaxLevelNumber(@Param("programId") UUID programId);

    /**
     * Find leaf level config (level 7 or max level).
     */
    @Query("SELECT c FROM HierarchyLevelConfig c WHERE c.programId = :programId AND c.levelNumber = " +
           "(SELECT MAX(c2.levelNumber) FROM HierarchyLevelConfig c2 WHERE c2.programId = :programId)")
    Optional<HierarchyLevelConfig> findLeafLevelConfig(@Param("programId") UUID programId);
}