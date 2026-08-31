package com.bank.vam.service.hierarchy;

import com.bank.vam.entity.Program;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.hierarchy.HierarchyNode;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.ProgramRepository;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.HierarchyNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Service for aggregating balances across the hierarchy.
 * 
 * Tiered aggregation strategy:
 * - L7 (VA) to L5: Real-time propagation on each transaction
 * - L4 to L1: Scheduled aggregation every 5 minutes
 * 
 * This balances accuracy with performance - lower levels get
 * instant updates while higher levels aggregate in batches.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BalanceAggregationService {

    private final HierarchyNodeRepository nodeRepository;
    private final VirtualAccountRepository virtualAccountRepository;
    private final ProgramRepository programRepository;

    private static final int REALTIME_PROPAGATION_MAX_LEVEL = 5;

    // ========================================================================
    // Real-time propagation (L7 to L5)
    // ========================================================================

    /**
     * Propagate balance change up the hierarchy in real-time.
     * Called immediately after a transaction on a VA.
     * 
     * @param virtualAccountId The VA that had a balance change
     * @param balanceDelta The amount of change (positive for credit, negative for debit)
     */
    @Transactional
    public void propagateBalanceChange(UUID virtualAccountId, BigDecimal balanceDelta) {
        if (balanceDelta == null || balanceDelta.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }

        // Find the hierarchy node for this VA
        HierarchyNode leafNode = nodeRepository.findByVirtualAccountId(virtualAccountId).orElse(null);
        if (leafNode == null) {
            // VA not in hierarchy, skip propagation
            return;
        }

        // Propagate up to level 5 (real-time)
        propagateToAncestors(leafNode, balanceDelta, REALTIME_PROPAGATION_MAX_LEVEL);
    }

    /**
     * Propagate balance change to ancestors up to specified level.
     */
    private void propagateToAncestors(HierarchyNode startNode, BigDecimal delta, int maxLevel) {
        LocalDateTime now = LocalDateTime.now();
        
        // Get all ancestors
        List<HierarchyNode> ancestors = nodeRepository.findAncestors(
            startNode.getProgramId(), 
            startNode.getMaterializedPath()
        );

        // Update each ancestor's balance
        for (HierarchyNode ancestor : ancestors) {
            if (ancestor.getLevelNumber() < maxLevel) {
                // This is above the real-time threshold, will be updated by scheduled job
                continue;
            }
            
            nodeRepository.incrementBalance(ancestor.getId(), delta, now);
            log.debug("Propagated {} to node {} (L{})", delta, ancestor.getNodeCode(), ancestor.getLevelNumber());
        }

        // Also update the leaf node itself
        nodeRepository.incrementBalance(startNode.getId(), delta, now);
    }

    // ========================================================================
    // Scheduled aggregation (L4 to L1)
    // ========================================================================

    /**
     * Scheduled job to aggregate balances for upper hierarchy levels.
     * Runs every 5 minutes by default.
     *
     * @deprecated This service is for HierarchyNode-based hierarchy.
     * The system now uses VirtualAccount.parentAccountId for hierarchy.
     * Use BalanceAggregationServiceEnhanced.scheduledAggregation() instead.
     * Scheduled annotation removed to prevent conflict.
     */
    // @Scheduled(fixedRateString = "${vam.hierarchy.aggregation-interval-ms:300000}")
    @Transactional
    @Deprecated
    public void scheduledAggregation() {
        log.info("Starting scheduled balance aggregation...");
        
        List<Program> hierarchyPrograms = programRepository.findAll().stream()
            .filter(p -> Boolean.TRUE.equals(p.getHierarchyEnabled()))
            .toList();

        for (Program program : hierarchyPrograms) {
            try {
                aggregateForProgram(program.getId());
            } catch (Exception e) {
                log.error("Error aggregating balances for program {}: {}", program.getProgramCode(), e.getMessage());
            }
        }
        
        log.info("Completed scheduled balance aggregation for {} programs", hierarchyPrograms.size());
    }

    /**
     * Aggregate balances for a specific program.
     */
    @Transactional
    public void aggregateForProgram(UUID programId) {
        log.debug("Aggregating balances for program: {}", programId);
        
        // Get all nodes ordered by level descending (leaf to root)
        List<HierarchyNode> nodes = nodeRepository.findByProgramIdOrderByMaterializedPathAsc(programId);
        
        // Sort by level descending for bottom-up aggregation
        nodes.sort((a, b) -> b.getLevelNumber().compareTo(a.getLevelNumber()));

        // Map to hold calculated balances
        Map<UUID, BigDecimal> calculatedBalances = new HashMap<>();
        Map<UUID, BigDecimal> calculatedAvailable = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();

        for (HierarchyNode node : nodes) {
            BigDecimal balance;
            BigDecimal available;

            if (node.getIsLeaf() && node.getVirtualAccountId() != null) {
                // Leaf node - get balance from VA
                VirtualAccount va = virtualAccountRepository.findById(node.getVirtualAccountId()).orElse(null);
                if (va != null) {
                    balance = va.getCurrentBalance() != null ? va.getCurrentBalance() : BigDecimal.ZERO;
                    available = va.getAvailableBalance() != null ? va.getAvailableBalance() : BigDecimal.ZERO;
                } else {
                    balance = BigDecimal.ZERO;
                    available = BigDecimal.ZERO;
                }
            } else {
                // Non-leaf node - sum children's balances
                balance = BigDecimal.ZERO;
                available = BigDecimal.ZERO;
                
                List<HierarchyNode> children = nodeRepository.findByParentIdOrderByDisplayOrderAsc(node.getId());
                for (HierarchyNode child : children) {
                    BigDecimal childBalance = calculatedBalances.getOrDefault(child.getId(), child.getAggregatedBalance());
                    BigDecimal childAvailable = calculatedAvailable.getOrDefault(child.getId(), child.getAvailableBalance());
                    
                    if (childBalance != null) balance = balance.add(childBalance);
                    if (childAvailable != null) available = available.add(childAvailable);
                }
            }

            calculatedBalances.put(node.getId(), balance);
            calculatedAvailable.put(node.getId(), available);

            // Only update nodes at level 4 and above (scheduled aggregation)
            if (node.getLevelNumber() < REALTIME_PROPAGATION_MAX_LEVEL) {
                nodeRepository.updateBalance(node.getId(), balance, available, now);
            }
        }

        log.debug("Aggregated {} nodes for program {}", nodes.size(), programId);
    }

    // ========================================================================
    // On-demand refresh
    // ========================================================================

    /**
     * Force refresh balance for a specific node and its ancestors.
     */
    @Transactional
    public void refreshNodeBalance(UUID nodeId) {
        HierarchyNode node = nodeRepository.findById(nodeId)
            .orElseThrow(() -> new ResourceNotFoundException("Node not found: " + nodeId));

        BigDecimal balance = calculateNodeBalance(node);
        BigDecimal available = balance; // Simplified - could subtract holds

        nodeRepository.updateBalance(nodeId, balance, available, LocalDateTime.now());

        // Refresh ancestors
        List<HierarchyNode> ancestors = nodeRepository.findAncestors(node.getProgramId(), node.getMaterializedPath());
        for (HierarchyNode ancestor : ancestors) {
            BigDecimal ancestorBalance = calculateNodeBalance(ancestor);
            nodeRepository.updateBalance(ancestor.getId(), ancestorBalance, ancestorBalance, LocalDateTime.now());
        }
    }

    /**
     * Calculate balance for a node (sum of children or VA balance).
     */
    private BigDecimal calculateNodeBalance(HierarchyNode node) {
        if (node.getIsLeaf() && node.getVirtualAccountId() != null) {
            VirtualAccount va = virtualAccountRepository.findById(node.getVirtualAccountId()).orElse(null);
            return va != null ? va.getCurrentBalance() : BigDecimal.ZERO;
        }

        // Sum children
        List<HierarchyNode> children = nodeRepository.findByParentIdOrderByDisplayOrderAsc(node.getId());
        return children.stream()
            .map(HierarchyNode::getAggregatedBalance)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Full refresh for entire program (use sparingly).
     */
    @Async
    @Transactional
    public void fullRefresh(UUID programId) {
        log.info("Starting full balance refresh for program: {}", programId);
        aggregateForProgram(programId);
        log.info("Completed full balance refresh for program: {}", programId);
    }

    // ========================================================================
    // Balance queries
    // ========================================================================

    /**
     * Get balance summary by level for a program.
     */
    @Transactional(readOnly = true)
    public Map<Integer, BigDecimal> getBalanceByLevel(UUID programId) {
        List<Object[]> results = nodeRepository.getBalanceByLevel(programId);
        Map<Integer, BigDecimal> balanceByLevel = new HashMap<>();
        
        for (Object[] row : results) {
            Integer level = (Integer) row[0];
            BigDecimal balance = (BigDecimal) row[1];
            balanceByLevel.put(level, balance);
        }
        
        return balanceByLevel;
    }

    /**
     * Get total balance for a program (from L1 nodes).
     */
    @Transactional(readOnly = true)
    public BigDecimal getTotalBalance(UUID programId) {
        return nodeRepository.getTotalBalance(programId);
    }

    /**
     * Get balance for a specific node.
     */
    @Transactional(readOnly = true)
    public BigDecimal getNodeBalance(UUID nodeId) {
        HierarchyNode node = nodeRepository.findById(nodeId)
            .orElseThrow(() -> new ResourceNotFoundException("Node not found: " + nodeId));
        return node.getAggregatedBalance();
    }

    /**
     * Get subtree balance (sum of all leaf nodes under a node).
     */
    @Transactional(readOnly = true)
    public BigDecimal getSubtreeBalance(UUID nodeId) {
        HierarchyNode node = nodeRepository.findById(nodeId)
            .orElseThrow(() -> new ResourceNotFoundException("Node not found: " + nodeId));

        List<HierarchyNode> leafNodes = nodeRepository.findLeafNodesUnder(
            node.getProgramId(), 
            node.getMaterializedPath()
        );

        BigDecimal total = BigDecimal.ZERO;
        for (HierarchyNode leaf : leafNodes) {
            if (leaf.getVirtualAccountId() != null) {
                VirtualAccount va = virtualAccountRepository.findById(leaf.getVirtualAccountId()).orElse(null);
                if (va != null && va.getCurrentBalance() != null) {
                    total = total.add(va.getCurrentBalance());
                }
            }
        }

        return total;
    }
}