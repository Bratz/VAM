package com.bank.vam.ai.copilot.tools.read;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.treasury.SweepInstruction;
import com.bank.vam.entity.treasury.SweepInstruction.InstructionStatus;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.treasury.SweepInstructionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Read tool: query sweep instructions by id / status / time window.
 *
 * <p>Mapped intents (P3): {@code FAILED_SWEEPS}, {@code EXPLAIN_REJECTION}.
 *
 * <p>Parameters:
 * <ul>
 *   <li>{@code id} — UUID, idempotency key, or external reference (single-row lookup)</li>
 *   <li>{@code status} — {@link InstructionStatus} (filter)</li>
 *   <li>{@code since} — relative duration ("24h", "7d", "30m"); resolved against now</li>
 *   <li>{@code limit} — max rows (default 25, cap 200)</li>
 * </ul>
 *
 * <p>{@code id} short-circuits the other filters. When {@code id} is absent and
 * {@code status} is absent, defaults to "all non-terminal" so the demo question
 * "what's in flight?" works without parameters.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetSweepInstructionsTool implements CopilotTool {

    static final int DEFAULT_LIMIT = 25;
    static final int MAX_LIMIT = 200;

    private static final Pattern DURATION_PATTERN = Pattern.compile("^\\s*(\\d+)\\s*([smhd])\\s*$");
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final SweepInstructionRepository instructionRepository;
    private final VirtualAccountRepository virtualAccountRepository;

    @Override
    public String name() {
        return "get_sweep_instructions";
    }

    @Override
    public String description() {
        return "Lookup sweep instructions by id, or list by status / time window.";
    }

    @Override
    public Map<String, Map<String, Object>> parameterSchema() {
        return Map.of(
                "id", Map.of("type", "string",
                        "description", "UUID, idempotency key, or external reference for single-row lookup"),
                "status", Map.of("type", "string",
                        "description", "PREPARED, INSTRUCTED, ACK_RECEIVED, SETTLED, REJECTED, NACKED, EXPIRED"),
                "since", Map.of("type", "string",
                        "description", "Relative time window like '24h', '7d', '30m'"),
                "limit", Map.of("type", "integer",
                        "description", "Max rows to return (default 25, cap 200)")
        );
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(ToolContext context, Map<String, Object> params) {
        try {
            String idParam = paramString(params, "id", null);
            if (idParam != null) {
                return lookupById(idParam, context);
            }

            String statusParam = paramString(params, "status", null);
            String sinceParam = paramString(params, "since", null);
            int limit = Math.min(Math.max(1, paramInt(params, "limit", DEFAULT_LIMIT)), MAX_LIMIT);

            InstructionStatus statusFilter = parseStatus(statusParam);
            LocalDateTime sinceCutoff = parseSince(sinceParam);

            List<SweepInstruction> candidate;
            if (statusFilter != null) {
                candidate = instructionRepository.findByStatus(statusFilter);
            } else {
                // Default — all non-terminal (in-flight). Keeps the "what's in flight?"
                // intent useful even with no parameters.
                candidate = instructionRepository.findByStatusInOrderByInstructedAtDesc(
                        List.of(InstructionStatus.PREPARED,
                                InstructionStatus.INSTRUCTED,
                                InstructionStatus.ACK_RECEIVED));
            }

            // Corporate scope BEFORE limiting — SweepInstruction has no corporateId of
            // its own, so resolve it via the source/target VA (batch-fetched once, not
            // per-row) and drop anything outside the caller's corporate first, otherwise
            // a scoped caller could see fewer than `limit` rows even when more of theirs
            // exist further down the unfiltered list.
            List<SweepInstruction> corporateScoped = context.hasCorporateScope()
                    ? filterByCorporate(candidate, context.corporateId())
                    : candidate;

            List<SweepInstruction> filtered = corporateScoped.stream()
                    .filter(i -> sinceCutoff == null
                            || (i.getCreatedAt() != null && i.getCreatedAt().isAfter(sinceCutoff)))
                    .sorted(Comparator.comparing(
                            (SweepInstruction i) -> i.getCreatedAt() == null
                                    ? LocalDateTime.MIN : i.getCreatedAt())
                            .reversed())
                    .limit(limit)
                    .toList();

            List<Map<String, Object>> rows = filtered.stream()
                    .map(this::toRow)
                    .collect(Collectors.toList());

            String summary = String.format("%d instruction%s%s%s",
                    filtered.size(), filtered.size() == 1 ? "" : "s",
                    statusFilter != null ? " in " + statusFilter : " in-flight",
                    sinceCutoff != null ? " since " + sinceParam : "");

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalMatched", filtered.size());
            Map<String, Object> filter = new LinkedHashMap<>();
            filter.put("status", statusFilter == null ? null : statusFilter.name());
            filter.put("since", sinceParam);
            data.put("filter", filter);
            data.put("instructions", rows);

            return ToolResult.ok(name(), summary, data);
        } catch (Exception e) {
            log.warn("get_sweep_instructions failed", e);
            return ToolResult.error(name(), "Failed to query sweep instructions: " + e.getMessage());
        }
    }

    private ToolResult lookupById(String idParam, ToolContext context) {
        Optional<SweepInstruction> found = Optional.empty();
        if (UUID_PATTERN.matcher(idParam).matches()) {
            try {
                found = instructionRepository.findById(UUID.fromString(idParam));
            } catch (IllegalArgumentException ignored) {
                // fall through to other lookup strategies
            }
        }
        if (found.isEmpty()) {
            found = instructionRepository.findByIdempotencyKey(idParam);
        }
        if (found.isEmpty()) {
            found = instructionRepository.findByExternalReference(idParam);
        }
        // A direct-id lookup must not leak a row outside the caller's corporate —
        // report it as "not found" exactly like a genuine miss, not a 403, so a
        // scoped caller can't use this to probe which ids exist elsewhere.
        if (found.isPresent() && context.hasCorporateScope()
                && filterByCorporate(List.of(found.get()), context.corporateId()).isEmpty()) {
            found = Optional.empty();
        }
        if (found.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalMatched", 0);
            Map<String, Object> filter = new LinkedHashMap<>();
            filter.put("id", idParam);
            data.put("filter", filter);
            data.put("instructions", List.of());
            return ToolResult.ok(name(), "No instruction matched id '" + idParam + "'", data);
        }
        SweepInstruction i = found.get();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalMatched", 1);
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("id", idParam);
        data.put("filter", filter);
        data.put("instructions", List.of(toRow(i)));
        String summary = String.format("Instruction %s is %s%s",
                i.getIdempotencyKey() != null ? i.getIdempotencyKey() : i.getId(),
                i.getStatus(),
                i.getRejectionCode() != null ? " (rejection " + i.getRejectionCode() + ")" : "");
        return ToolResult.ok(name(), summary, data);
    }

    /**
     * SweepInstruction carries no corporateId of its own — it belongs to a corporate
     * only via its source/target VA. Batch-resolves every distinct VA id referenced
     * by {@code instructions} in one query (not per-row) and keeps only rows whose
     * source or target VA belongs to {@code corporateId}.
     */
    private List<SweepInstruction> filterByCorporate(List<SweepInstruction> instructions, UUID corporateId) {
        Set<UUID> vaIds = new HashSet<>();
        for (SweepInstruction i : instructions) {
            if (i.getSourceShadowVaId() != null) vaIds.add(i.getSourceShadowVaId());
            if (i.getTargetVaId() != null) vaIds.add(i.getTargetVaId());
        }
        if (vaIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, UUID> vaIdToCorporateId = virtualAccountRepository.findAllById(vaIds).stream()
                .collect(Collectors.toMap(VirtualAccount::getId, VirtualAccount::getCorporateId));
        return instructions.stream()
                .filter(i -> corporateId.equals(vaIdToCorporateId.get(i.getSourceShadowVaId()))
                        || corporateId.equals(vaIdToCorporateId.get(i.getTargetVaId())))
                .toList();
    }

    private InstructionStatus parseStatus(String s) {
        if (s == null) return null;
        try {
            return InstructionStatus.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.debug("get_sweep_instructions: ignoring unknown status '{}'", s);
            return null;
        }
    }

    /** Parses "24h", "7d", "30m", "60s" → cutoff timestamp. */
    private LocalDateTime parseSince(String s) {
        if (s == null) return null;
        Matcher m = DURATION_PATTERN.matcher(s);
        if (!m.matches()) return null;
        long n = Long.parseLong(m.group(1));
        Duration d = switch (m.group(2)) {
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            case "h" -> Duration.ofHours(n);
            case "d" -> Duration.ofDays(n);
            default -> null;
        };
        return d == null ? null : LocalDateTime.now().minus(d);
    }

    private Map<String, Object> toRow(SweepInstruction i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getId() == null ? null : i.getId().toString());
        m.put("idempotencyKey", i.getIdempotencyKey());
        m.put("status", i.getStatus() == null ? null : i.getStatus().name());
        m.put("amount", i.getAmount());
        m.put("currency", i.getCurrencyCode());
        m.put("rail", i.getRail() == null ? null : i.getRail().name());
        m.put("sourceShadowVaId", i.getSourceShadowVaId() == null ? null : i.getSourceShadowVaId().toString());
        m.put("targetVaId", i.getTargetVaId() == null ? null : i.getTargetVaId().toString());
        m.put("externalReference", i.getExternalReference());
        m.put("clearingReference", i.getClearingReference());
        m.put("rejectionCode", i.getRejectionCode());
        m.put("rejectionReason", i.getRejectionReason());
        m.put("rejectionCategory", i.getRejectionCategory() == null ? null : i.getRejectionCategory().name());
        m.put("retryCount", i.getRetryCount());
        m.put("preparedAt", i.getPreparedAt() == null ? null : i.getPreparedAt().toString());
        m.put("instructedAt", i.getInstructedAt() == null ? null : i.getInstructedAt().toString());
        m.put("ackAt", i.getAckAt() == null ? null : i.getAckAt().toString());
        m.put("settledAt", i.getSettledAt() == null ? null : i.getSettledAt().toString());
        m.put("rejectedAt", i.getRejectedAt() == null ? null : i.getRejectedAt().toString());
        m.put("createdAt", i.getCreatedAt() == null ? null : i.getCreatedAt().toString());
        return m;
    }
}
