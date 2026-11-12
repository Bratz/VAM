package com.bank.vam.ai.copilot.tools.read;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.dto.treasury.NotionalPoolDto;
import com.bank.vam.entity.credit.InterestConfiguration;
import com.bank.vam.entity.treasury.NotionalPool;
import com.bank.vam.entity.treasury.NotionalPool.PoolStatus;
import com.bank.vam.entity.treasury.PoolMember;
import com.bank.vam.repository.credit.InterestConfigurationRepository;
import com.bank.vam.repository.treasury.NotionalPoolRepository;
import com.bank.vam.service.treasury.PoolRunService;
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
 * <p>Pool interest is calculated by daily runs, stored per pool and value date
 * ({@code pool_interest_calculations} + lines, {@code PoolRunService}); this tool
 * reports the figures built from them (last run, month-to-date pooled interest
 * and benefit, year-to-date benefit; the benefit is pooled minus standalone, the
 * value of pooling), not the retired stored counters. Members are the active
 * ones; members who left keep their row but are not counted or listed.
 *
 * <p>Output shape (list): {@code { totalMatched, pools: [{ id, poolReference,
 * poolName, poolCurrency, status, memberCount, headerAccountId,
 * headerAccountNumber, legalEntityId, rates: { creditRate, debitRate,
 * dayCount }, poolFloor, figures: { asOf, lastRunDate, lastRunNet,
 * lastRunPooledInterest, mtdPooledInterest, mtdBenefit, ytdBenefit } }] }}
 * <p>Output shape (detail, {@code id} given): adds {@code members: [{
 * accountId, accountNumber, entityCode, entityName, debitLimit, joinedDate,
 * header }]}
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
    private final InterestConfigurationRepository interestConfigurationRepository;
    private final PoolRunService runService;

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
            List<NotionalPool> page = scoped.stream().limit(limit).toList();
            Map<UUID, NotionalPoolDto.Figures> figures = runService.figures(page.stream().map(NotionalPool::getId).toList());
            List<Map<String, Object>> rows = page.stream()
                    .map(p -> toListRow(p, figures.get(p.getId())))
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
        Map<String, Object> row = toListRow(pool, runService.figures(List.of(pool.getId())).get(pool.getId()));
        List<PoolMember> active = activeMembers(pool);
        row.put("members", active.stream().map(m -> toMemberRow(m, pool)).toList());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalMatched", 1);
        data.put("pools", List.of(row));
        return ToolResult.ok(name(), "Pool " + pool.getPoolReference() + " has " + active.size() + " active member(s)", data);
    }

    /** Members who left keep their row (dated history); the pool is its ACTIVE members. */
    static List<PoolMember> activeMembers(NotionalPool pool) {
        return pool.getMembers() == null ? List.of() : pool.getMembers().stream()
                .filter(m -> m.getStatus() == PoolMember.MemberStatus.ACTIVE).toList();
    }

    private Map<String, Object> toListRow(NotionalPool pool, NotionalPoolDto.Figures figures) {
        List<PoolMember> active = activeMembers(pool);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", pool.getId() != null ? pool.getId().toString() : null);
        row.put("poolReference", pool.getPoolReference());
        row.put("poolName", pool.getPoolName());
        row.put("poolCurrency", pool.getPoolCurrency());
        row.put("status", pool.getStatus() != null ? pool.getStatus().name() : null);
        row.put("memberCount", active.size());
        row.put("headerAccountId", pool.getHeaderAccountId() != null ? pool.getHeaderAccountId().toString() : null);
        row.put("headerAccountNumber", active.stream().filter(m -> m.getAccountId().equals(pool.getHeaderAccountId()))
                .map(PoolMember::getAccountNumber).findFirst().orElse(null));
        row.put("legalEntityId", pool.getLegalEntityId() != null ? pool.getLegalEntityId().toString() : null);
        InterestConfiguration config = pool.getInterestConfigurationId() == null ? null
                : interestConfigurationRepository.findById(pool.getInterestConfigurationId()).orElse(null);
        if (config != null) {
            Map<String, Object> rates = new LinkedHashMap<>();
            rates.put("creditRate", config.getEffectiveCreditRate());
            rates.put("debitRate", config.getEffectiveDebitRate());
            rates.put("dayCount", config.getDayCountConvention());
            row.put("rates", rates);
        }
        row.put("poolFloor", pool.getPoolFloor());
        row.put("figures", figures);
        return row;
    }

    private Map<String, Object> toMemberRow(PoolMember member, NotionalPool pool) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("accountId", member.getAccountId() != null ? member.getAccountId().toString() : null);
        row.put("accountNumber", member.getAccountNumber());
        row.put("entityCode", member.getEntityCode());
        row.put("entityName", member.getEntityName());
        row.put("debitLimit", member.getDebitLimit());
        row.put("joinedDate", member.getJoinedDate());
        row.put("header", member.getAccountId() != null && member.getAccountId().equals(pool.getHeaderAccountId()));
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
