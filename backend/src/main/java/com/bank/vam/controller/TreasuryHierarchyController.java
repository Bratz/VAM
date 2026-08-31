package com.bank.vam.controller;

import com.bank.vam.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/treasury/hierarchy")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class TreasuryHierarchyController {

    private final com.bank.vam.config.MarketProfileProperties marketProfile;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> getHierarchy(
            @RequestParam(required = false) UUID corporateId) {
        
        Map<String, Object> hierarchy = new LinkedHashMap<>();
        hierarchy.put("id", UUID.randomUUID());
        hierarchy.put("name", "Global Treasury");
        hierarchy.put("type", "ROOT");
        hierarchy.put("totalBalance", BigDecimal.valueOf(125000000));
        hierarchy.put("children", Arrays.asList(
            createNode("EMEA Region", "REGION", BigDecimal.valueOf(45000000), Arrays.asList(
                createNode("UK Operations", "ENTITY", BigDecimal.valueOf(20000000), Arrays.asList(
                    createNode("London Office", "ACCOUNT", BigDecimal.valueOf(12000000), null),
                    createNode("Manchester Office", "ACCOUNT", BigDecimal.valueOf(8000000), null)
                )),
                createNode("UAE Operations", "ENTITY", BigDecimal.valueOf(25000000), Arrays.asList(
                    createNode("Dubai HQ", "ACCOUNT", BigDecimal.valueOf(18000000), null),
                    createNode("Abu Dhabi Branch", "ACCOUNT", BigDecimal.valueOf(7000000), null)
                ))
            )),
            createNode("APAC Region", "REGION", BigDecimal.valueOf(50000000), Arrays.asList(
                createNode("Singapore Hub", "ENTITY", BigDecimal.valueOf(30000000), null),
                createNode("Hong Kong Office", "ENTITY", BigDecimal.valueOf(20000000), null)
            )),
            createNode("Americas Region", "REGION", BigDecimal.valueOf(30000000), Arrays.asList(
                createNode("US Operations", "ENTITY", BigDecimal.valueOf(30000000), null)
            ))
        ));
        
        return ResponseEntity.ok(ApiResponse.success(hierarchy));
    }

    @GetMapping("/nodes/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getNodeDetails(@PathVariable UUID id) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("name", "UAE Operations");
        node.put("type", "ENTITY");
        node.put("parentId", UUID.randomUUID());
        node.put("parentName", "EMEA Region");
        node.put("balance", BigDecimal.valueOf(25000000));
        node.put("currencyCode", marketProfile.getDefaultCurrency());
        node.put("accountCount", 5);
        node.put("childCount", 2);
        node.put("manager", "Treasury Manager UAE");
        node.put("costCenter", "CC-UAE-001");
        node.put("createdAt", LocalDateTime.now().minusYears(2));
        
        return ResponseEntity.ok(ApiResponse.success(node));
    }

    @PostMapping("/nodes")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createNode(@RequestBody CreateNodeRequest request) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", UUID.randomUUID());
        node.put("name", request.name());
        node.put("type", request.type());
        node.put("parentId", request.parentId());
        node.put("createdAt", LocalDateTime.now());
        
        log.info("Created hierarchy node: {} under {}", request.name(), request.parentId());
        return ResponseEntity.ok(ApiResponse.success(node));
    }

    @PutMapping("/nodes/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateNode(
            @PathVariable UUID id,
            @RequestBody UpdateNodeRequest request) {
        
        Map<String, Object> node = new HashMap<>();
        node.put("id", id);
        node.put("name", request.name());
        node.put("updatedAt", LocalDateTime.now());
        
        return ResponseEntity.ok(ApiResponse.success(node));
    }

    @DeleteMapping("/nodes/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteNode(@PathVariable UUID id) {
        log.info("Deleted hierarchy node: {}", id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/nodes/{id}/move")
    public ResponseEntity<ApiResponse<Map<String, Object>>> moveNode(
            @PathVariable UUID id,
            @RequestBody MoveNodeRequest request) {
        
        Map<String, Object> result = new HashMap<>();
        result.put("nodeId", id);
        result.put("newParentId", request.newParentId());
        result.put("movedAt", LocalDateTime.now());
        
        log.info("Moved node {} to parent {}", id, request.newParentId());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getHierarchySummary() {
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalNodes", 24);
        summary.put("regions", 3);
        summary.put("entities", 8);
        summary.put("accounts", 13);
        summary.put("totalBalance", BigDecimal.valueOf(125000000));
        summary.put("currencies", marketProfile.getActiveSuggestedCurrencies());
        
        return ResponseEntity.ok(ApiResponse.success(summary));
    }

    private Map<String, Object> createNode(String name, String type, BigDecimal balance, List<Map<String, Object>> children) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", UUID.randomUUID());
        node.put("name", name);
        node.put("type", type);
        node.put("balance", balance);
        if (children != null) {
            node.put("children", children);
        }
        return node;
    }

    public record CreateNodeRequest(String name, String type, UUID parentId, String currencyCode) {}
    public record UpdateNodeRequest(String name, String manager, String costCenter) {}
    public record MoveNodeRequest(UUID newParentId) {}
}