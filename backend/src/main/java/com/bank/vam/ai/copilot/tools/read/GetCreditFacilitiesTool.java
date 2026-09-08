package com.bank.vam.ai.copilot.tools.read;

import com.bank.vam.ai.copilot.tools.CopilotTool;
import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.entity.credit.CreditFacility;
import com.bank.vam.entity.credit.CreditFacility.FacilityStatus;
import com.bank.vam.repository.credit.CreditFacilityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read tool: credit facilities for the corporate — overdrafts, loans,
 * guarantees.
 *
 * <p>{@code expiringWithinDays} is applied as an in-memory filter over the
 * already corporate-scoped list, deliberately NOT via {@code
 * CreditFacilityRepository.findExpiringSoon(date)} — that method has no
 * {@code corporateId} parameter and would leak other corporates' facilities
 * to a scoped caller.
 *
 * <p>Output shape: {@code { totalMatched, facilities: [{ id, facilityName,
 * facilityType, sanctionedLimit, currentOutstanding, availableLimit,
 * facilityCurrency, expiryDate, status }] } }
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetCreditFacilitiesTool implements CopilotTool {

    private final CreditFacilityRepository creditFacilityRepository;

    @Override
    public String name() {
        return "get_credit_facilities";
    }

    @Override
    public String description() {
        return "Credit facilities for the corporate — overdrafts, loans, guarantees.";
    }

    @Override
    public Map<String, Map<String, Object>> parameterSchema() {
        return Map.of(
                "status", Map.of("type", "string", "description", "ACTIVE, SUSPENDED, CLOSED, EXPIRED"),
                "highUtilizationOnly", Map.of("type", "boolean", "description", "Only facilities with high utilization"),
                "expiringWithinDays", Map.of("type", "integer", "description", "Only facilities expiring within N days")
        );
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(ToolContext context, Map<String, Object> params) {
        try {
            boolean highUtilizationOnly = paramBoolean(params, "highUtilizationOnly", false);
            String statusParam = paramString(params, "status", null);
            Integer expiringWithinDays = params != null && params.get("expiringWithinDays") != null
                    ? paramInt(params, "expiringWithinDays", 0) : null;

            List<CreditFacility> facilities;
            if (highUtilizationOnly && context.hasCorporateScope()) {
                facilities = creditFacilityRepository.findHighUtilizationFacilities(context.corporateId());
            } else if (statusParam != null && context.hasCorporateScope()) {
                facilities = creditFacilityRepository.findByCorporateIdAndStatus(context.corporateId(), parseStatus(statusParam));
            } else if (context.hasCorporateScope()) {
                facilities = creditFacilityRepository.findByCorporateIdOrderByFacilityName(context.corporateId());
            } else if (statusParam != null) {
                FacilityStatus status = parseStatus(statusParam);
                facilities = creditFacilityRepository.findAll().stream()
                        .filter(f -> f.getStatus() == status).toList();
            } else {
                facilities = creditFacilityRepository.findAll();
            }

            if (expiringWithinDays != null) {
                LocalDate cutoff = LocalDate.now().plusDays(expiringWithinDays);
                facilities = facilities.stream()
                        .filter(f -> f.getExpiryDate() != null && !f.getExpiryDate().isAfter(cutoff))
                        .toList();
            }

            List<Map<String, Object>> rows = facilities.stream().map(this::toRow).collect(Collectors.toList());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalMatched", rows.size());
            data.put("facilities", rows);

            return ToolResult.ok(name(), rows.size() + " credit facility(ies) found", data);
        } catch (Exception e) {
            log.warn("get_credit_facilities failed", e);
            return ToolResult.error(name(), "Failed to list credit facilities: " + e.getMessage());
        }
    }

    private Map<String, Object> toRow(CreditFacility facility) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", facility.getId() != null ? facility.getId().toString() : null);
        row.put("facilityName", facility.getFacilityName());
        row.put("facilityType", facility.getFacilityType() != null ? facility.getFacilityType().name() : null);
        row.put("sanctionedLimit", facility.getSanctionedLimit());
        row.put("currentOutstanding", facility.getCurrentOutstanding());
        row.put("availableLimit", facility.getAvailableLimit());
        row.put("facilityCurrency", facility.getFacilityCurrency());
        row.put("expiryDate", facility.getExpiryDate());
        row.put("status", facility.getStatus() != null ? facility.getStatus().name() : null);
        return row;
    }

    private FacilityStatus parseStatus(String s) {
        try {
            return FacilityStatus.valueOf(s.trim().toUpperCase());
        } catch (Exception e) {
            return FacilityStatus.ACTIVE;
        }
    }
}
