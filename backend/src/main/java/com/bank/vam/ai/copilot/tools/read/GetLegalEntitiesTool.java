package com.bank.vam.ai.copilot.tools.read;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.hierarchy.LegalEntity.EntityStatus;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Read tool: list legal entities in the corporate's hierarchy, or fetch one
 * with ancestors/descendants by id.
 *
 * <p>Rows include {@code bancsCustomerId} truthfully — masking that field is
 * exclusively the MCP pipeline's job ({@code McpMaskingPolicy}), not this
 * tool's, so the same tool called in-app (where masking doesn't apply) still
 * shows it to authorized bank staff.
 *
 * <p>Output shape (list): {@code { totalMatched, entities: [{ id, entityCode,
 * entityName, entityType, hierarchyPath, hierarchyLevel, parentEntityId,
 * countryCode, status, bancsCustomerId, ihbCurrentExposure, totalLentOut,
 * totalDeposited, netIhbPosition }] } }
 * <p>Output shape (detail, {@code id} given): adds {@code ancestors: [...]},
 * {@code descendants: [...]}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetLegalEntitiesTool implements CopilotTool {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private final LegalEntityRepository legalEntityRepository;

    @Override
    public String name() {
        return "get_legal_entities";
    }

    @Override
    public String description() {
        return "List legal entities in the corporate's hierarchy, or fetch one with ancestors/descendants by id.";
    }

    @Override
    public Map<String, Map<String, Object>> parameterSchema() {
        return Map.of(
                "id", Map.of("type", "string", "description", "Entity UUID or entityCode for single-entity detail"),
                "status", Map.of("type", "string", "description", "ACTIVE, INACTIVE, DISSOLVED"),
                "limit", Map.of("type", "integer", "description", "Max rows to return (default 50, cap 200)")
        );
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(ToolContext context, Map<String, Object> params) {
        try {
            String idParam = paramString(params, "id", null);
            if (idParam != null && !idParam.isBlank()) {
                return lookupById(idParam, context);
            }

            String statusParam = paramString(params, "status", null);
            int limit = Math.min(Math.max(1, paramInt(params, "limit", DEFAULT_LIMIT)), MAX_LIMIT);

            List<LegalEntity> entities;
            if (statusParam != null && context.hasCorporateScope()) {
                entities = legalEntityRepository.findByCorporateIdAndStatus(context.corporateId(), parseStatus(statusParam));
            } else if (context.hasCorporateScope()) {
                entities = legalEntityRepository.findByCorporateIdOrderByHierarchyPath(context.corporateId());
            } else {
                entities = legalEntityRepository.findAll();
            }

            int totalMatched = entities.size();
            List<Map<String, Object>> rows = entities.stream().limit(limit).map(this::toRow).collect(Collectors.toList());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalMatched", totalMatched);
            data.put("entities", rows);

            return ToolResult.ok(name(), totalMatched + " legal entity(ies) found", data);
        } catch (Exception e) {
            log.warn("get_legal_entities failed", e);
            return ToolResult.error(name(), "Failed to list legal entities: " + e.getMessage());
        }
    }

    private ToolResult lookupById(String idParam, ToolContext context) {
        Optional<LegalEntity> found = UUID_PATTERN.matcher(idParam).matches()
                ? legalEntityRepository.findById(UUID.fromString(idParam))
                : legalEntityRepository.findByEntityCode(idParam);

        if (found.isPresent() && context.hasCorporateScope()
                && !context.corporateId().equals(found.get().getCorporateId())) {
            found = Optional.empty();
        }

        if (found.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalMatched", 0);
            data.put("entities", List.of());
            return ToolResult.ok(name(), "No legal entity matched id '" + idParam + "'", data);
        }

        LegalEntity entity = found.get();
        Map<String, Object> row = toRow(entity);
        row.put("ancestors", legalEntityRepository.findAncestors(entity.getId()).stream().map(this::toRow).toList());
        row.put("descendants", entity.getHierarchyPath() != null
                ? legalEntityRepository.findDescendants(entity.getHierarchyPath()).stream().map(this::toRow).toList()
                : List.of());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalMatched", 1);
        data.put("entities", List.of(row));
        return ToolResult.ok(name(), "Legal entity " + entity.getEntityCode(), data);
    }

    private Map<String, Object> toRow(LegalEntity entity) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", entity.getId() != null ? entity.getId().toString() : null);
        row.put("entityCode", entity.getEntityCode());
        row.put("entityName", entity.getEntityName());
        row.put("entityType", entity.getEntityType() != null ? entity.getEntityType().name() : null);
        row.put("hierarchyPath", entity.getHierarchyPath());
        row.put("hierarchyLevel", entity.getHierarchyLevel());
        row.put("parentEntityId", entity.getParentEntityId() != null ? entity.getParentEntityId().toString() : null);
        row.put("countryCode", entity.getCountryCode());
        row.put("status", entity.getStatus() != null ? entity.getStatus().name() : null);
        row.put("bancsCustomerId", entity.getBancsCustomerId());
        row.put("ihbCurrentExposure", entity.getIhbCurrentExposure());
        row.put("totalLentOut", entity.getTotalLentOut());
        row.put("totalDeposited", entity.getTotalDeposited());
        row.put("netIhbPosition", entity.getNetIhbPosition());
        return row;
    }

    private EntityStatus parseStatus(String s) {
        try {
            return EntityStatus.valueOf(s.trim().toUpperCase());
        } catch (Exception e) {
            return EntityStatus.ACTIVE;
        }
    }
}
