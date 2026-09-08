package com.bank.vam.ai.copilot.tools.read;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.iso20022.dto.Iso20022StatementDto.Camt053GenerateRequest;
import com.bank.vam.iso20022.dto.Iso20022StatementDto.Camt053Response;
import com.bank.vam.iso20022.dto.Iso20022StatementDto.OutputFormat;
import com.bank.vam.iso20022.service.Iso20022StatementService;
import com.bank.vam.repository.VirtualAccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Read tool: generate a camt.053 bank-to-customer statement for a VA over a
 * date range. Only wraps {@link Iso20022StatementService#generateCamt053}
 * (genuinely recomputes from the DB each call) — {@code getStatementById}/
 * {@code getStatementHistory} are deliberately out of scope, since their
 * backing store is an in-memory {@code ConcurrentHashMap} that doesn't
 * survive an app restart, so wrapping them as an MCP tool would silently
 * misrepresent a demo-only limitation as durable history.
 *
 * <p>The service itself has no corporate scoping — this tool resolves and
 * verifies the VA's corporate ownership itself, before calling the service,
 * same not-found-on-mismatch convention as {@code get_account_detail}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetIso20022StatementTool implements CopilotTool {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final VirtualAccountRepository accountRepository;
    private final Iso20022StatementService iso20022StatementService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "get_iso20022_statement";
    }

    @Override
    public String description() {
        return "Generate a camt.053 bank-to-customer statement for a VA over a date range.";
    }

    @Override
    public Map<String, Map<String, Object>> parameterSchema() {
        return Map.of(
                "id", Map.of("type", "string", "description", "Account UUID or vaNumber", "required", true),
                "fromDate", Map.of("type", "string", "description", "Statement period start (ISO date)", "required", true),
                "toDate", Map.of("type", "string", "description", "Statement period end (ISO date)", "required", true),
                "includeChildren", Map.of("type", "boolean", "description", "Include child/subsidiary accounts (default false)")
        );
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(ToolContext context, Map<String, Object> params) {
        try {
            String idParam = paramString(params, "id", null);
            String fromDateParam = paramString(params, "fromDate", null);
            String toDateParam = paramString(params, "toDate", null);
            if (idParam == null || idParam.isBlank() || fromDateParam == null || toDateParam == null) {
                return ToolResult.error(name(), "Missing required parameter: id, fromDate, and toDate are all required");
            }
            boolean includeChildren = paramBoolean(params, "includeChildren", false);

            Optional<VirtualAccount> found = UUID_PATTERN.matcher(idParam).matches()
                    ? accountRepository.findById(UUID.fromString(idParam))
                    : accountRepository.findByVaNumber(idParam);

            // Not-found on a corporate-scope mismatch, not 403 — same convention as
            // get_account_detail; the service itself has no scoping of its own.
            if (found.isPresent() && context.hasCorporateScope()
                    && !context.corporateId().equals(found.get().getCorporateId())) {
                found = Optional.empty();
            }

            if (found.isEmpty()) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("totalMatched", 0);
                return ToolResult.ok(name(), "No account matched id '" + idParam + "'", data);
            }

            VirtualAccount va = found.get();
            LocalDate fromDate = LocalDate.parse(fromDateParam);
            LocalDate toDate = LocalDate.parse(toDateParam);

            Camt053GenerateRequest request = Camt053GenerateRequest.builder()
                    .vaId(va.getId())
                    .fromDate(fromDate)
                    .toDate(toDate)
                    .format(OutputFormat.JSON) // never XML — the MCP caller doesn't need the raw XML string
                    .includeChildren(includeChildren)
                    .build();

            Camt053Response response = iso20022StatementService.generateCamt053(request);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.convertValue(response, Map.class);
            // Large and redundant with the flattened entries — a payload-size decision,
            // not a masking one, so it's dropped here rather than in McpMaskingPolicy.
            data.remove("camt053Xml");

            String summary = String.format("camt.053 statement for %s, %s to %s", va.getVaNumber(), fromDate, toDate);
            return ToolResult.ok(name(), summary, data);
        } catch (Exception e) {
            log.warn("get_iso20022_statement failed", e);
            return ToolResult.error(name(), "Failed to generate ISO 20022 statement: " + e.getMessage());
        }
    }
}
