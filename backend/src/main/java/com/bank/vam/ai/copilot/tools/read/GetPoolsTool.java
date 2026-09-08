package com.bank.vam.ai.copilot.tools.read;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.entity.treasury.NotionalPool;
import com.bank.vam.entity.treasury.NotionalPool.PoolStatus;
import com.bank.vam.entity.treasury.PoolMember;
import com.bank.vam.repository.treasury.NotionalPoolRepository;
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
 * Read tool: list notional pools, or fetch one pool with its members by id.
 *
 * <p>No persisted interest-calculation history exists in this codebase
 * ({@code PoolInterestCalculation} has no repository/persistence anywhere) —
 * this tool only reports the pool's own current {@code interestRate}/
 * {@code interestSavingsYtd}/{@code lastCalculationDate} fields, not a
 * calculation history.
 *
 * <p>Output shape (list): {@code { totalMatched, pools: [{ id, poolReference,
 * poolName, poolCurrency, status, memberCount, totalBalance, interestRate,
 * interestSavingsYtd, lastCalculationDate }] }}
 * <p>Output shape (detail, {@code id} given): adds {@code members: [{
 * accountId, accountNumber, entityCode, entityName, currentBalance,
 * contributionPercent, weight, status }]}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetPoolsTool implements CopilotTool {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    static final int DEFAULT_LIMIT = 25;
    static final int MAX_LIMIT = 200;

    private final NotionalPoolRepository poolRepository;

    @Override
    public String name() {
        return "get_pools";
    }

    @Override
    public String description() {
        return "List notional pools, or fetch one pool with its members by id.";
    }

    @Override
    public Map<String, Map<String, Object>> parameterSchema() {
        return Map.of(
                "id", Map.of("type", "string", "description", "Pool UUID or poolReference for single-pool detail"),
                "status", Map.of("type", "string", "description", "Filter by status: ACTIVE, SUSPENDED, PENDING, CLOSED"),
                "limit", Map.of("type", "integer", "description", "Max rows to return (default 25, cap 200)")
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

            List<NotionalPool> candidate = statusParam != null
                    ? poolRepository.findByStatus(parseStatus(statusParam))
                    : poolRepository.findAll();

            List<NotionalPool> scoped = context.hasCorporateScope()
                    ? candidate.stream().filter(p -> context.corporateId().equals(p.getCorporateId())).toList()
                    : candidate;

            int totalMatched = scoped.size();
            List<Map<String, Object>> rows = scoped.stream()
                    .limit(limit)
                    .map(this::toListRow)
                    .collect(Collectors.toList());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalMatched", totalMatched);
            data.put("pools", rows);

            String summary = String.format("%d pool(s)%s", totalMatched,
                    statusParam != null ? " with status " + statusParam : "");
            return ToolResult.ok(name(), summary, data);
        } catch (Exception e) {
            log.warn("get_pools failed", e);
            return ToolResult.error(name(), "Failed to list pools: " + e.getMessage());
        }
    }

    private ToolResult lookupById(String idParam, ToolContext context) {
        Optional<NotionalPool> found = UUID_PATTERN.matcher(idParam).matches()
                ? poolRepository.findByIdWithMembers(UUID.fromString(idParam))
                : poolRepository.findByPoolReference(idParam)
                        .flatMap(p -> poolRepository.findByIdWithMembers(p.getId()));

        // A direct-id lookup must not leak a row outside the caller's corporate —
        // report it as "not found", not a 403, same convention as GetSweepInstructionsTool.
        if (found.isPresent() && context.hasCorporateScope()
                && !context.corporateId().equals(found.get().getCorporateId())) {
            found = Optional.empty();
        }

        if (found.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalMatched", 0);
            data.put("pools", List.of());
            return ToolResult.ok(name(), "No pool matched id '" + idParam + "'", data);
        }

        NotionalPool pool = found.get();
        Map<String, Object> row = toListRow(pool);
        row.put("members", pool.getMembers().stream().map(this::toMemberRow).toList());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalMatched", 1);
        data.put("pools", List.of(row));
        return ToolResult.ok(name(), "Pool " + pool.getPoolReference() + " has " + pool.getMembers().size() + " member(s)", data);
    }

    private Map<String, Object> toListRow(NotionalPool pool) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", pool.getId() != null ? pool.getId().toString() : null);
        row.put("poolReference", pool.getPoolReference());
        row.put("poolName", pool.getPoolName());
        row.put("poolCurrency", pool.getPoolCurrency());
        row.put("status", pool.getStatus() != null ? pool.getStatus().name() : null);
        row.put("memberCount", pool.getMemberCount());
        row.put("totalBalance", pool.getTotalBalance());
        row.put("interestRate", pool.getInterestRate());
        row.put("interestSavingsYtd", pool.getInterestSavingsYtd());
        row.put("lastCalculationDate", pool.getLastCalculationDate());
        return row;
    }

    private Map<String, Object> toMemberRow(PoolMember member) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("accountId", member.getAccountId() != null ? member.getAccountId().toString() : null);
        row.put("accountNumber", member.getAccountNumber());
        row.put("entityCode", member.getEntityCode());
        row.put("entityName", member.getEntityName());
        row.put("currentBalance", member.getCurrentBalance());
        row.put("contributionPercent", member.getContributionPercent());
        row.put("weight", member.getWeight());
        row.put("status", member.getStatus() != null ? member.getStatus().name() : null);
        return row;
    }

    private PoolStatus parseStatus(String s) {
        try {
            return PoolStatus.valueOf(s.trim().toUpperCase());
        } catch (Exception e) {
            return PoolStatus.ACTIVE;
        }
    }
}
