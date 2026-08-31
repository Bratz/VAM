package com.bank.vam.controller.simulator;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.dto.simulator.SimulatorScenarioDto;
import com.bank.vam.service.simulator.ScenarioSnapshotService;
import com.bank.vam.service.simulator.SimulatorActivationService;
import com.bank.vam.service.simulator.SimulatorScenarioService;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Cash Concentration Simulator — scenario CRUD (Phase 1) + score persistence
 * (Phase 2).
 *
 * <p>Sandbox surface. No live operational tables are written by any endpoint
 * here; the only path from sandbox to operations is the Phase 3 activation
 * flow (separate controller), which routes through the existing approval
 * pipeline. The Phase-2 score is computed client-side and merely frozen onto
 * the scenario row by {@code POST /{id}/score}.
 */
@RestController
@RequestMapping("/api/v1/simulator/scenarios")
@RequiredArgsConstructor
@Tag(name = "Cash Concentration Simulator", description = "Sandbox scenario CRUD (Phase 1)")
public class SimulatorController {

    private final SimulatorScenarioService scenarioService;
    private final ScenarioSnapshotService scenarioSnapshotService;
    private final SimulatorActivationService activationService;

    @GetMapping
    @Operation(summary = "List scenarios for a corporate (most recently updated first)")
    public ResponseEntity<ApiResponse<List<SimulatorScenarioDto.Response>>> list(
            @RequestParam UUID corporateId) {
        return ResponseEntity.ok(ApiResponse.success(scenarioService.listScenarios(corporateId)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a scenario by id")
    public ResponseEntity<ApiResponse<SimulatorScenarioDto.Response>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(scenarioService.getScenario(id)));
    }

    @PostMapping
    @Operation(summary = "Create a scenario")
    public ResponseEntity<ApiResponse<SimulatorScenarioDto.Response>> create(
            @RequestBody SimulatorScenarioDto.CreateRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success(scenarioService.createScenario(request), "Scenario created"));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a scenario")
    public ResponseEntity<ApiResponse<SimulatorScenarioDto.Response>> update(
            @PathVariable UUID id, @RequestBody SimulatorScenarioDto.UpdateRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success(scenarioService.updateScenario(id, request), "Scenario updated"));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Soft-delete (archive) a scenario")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        scenarioService.deleteScenario(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Scenario archived"));
    }

    @PostMapping("/{id}/score")
    @Operation(summary = "Persist the latest computed Optimisation Score (Phase 2)")
    public ResponseEntity<ApiResponse<SimulatorScenarioDto.Response>> saveScore(
            @PathVariable UUID id, @RequestBody JsonNode score) {
        return ResponseEntity.ok(
                ApiResponse.success(scenarioService.saveScore(id, score), "Score saved"));
    }

    @PostMapping("/{id}/fork")
    @Operation(summary = "Fork a scenario into a sibling A/B/C set (Phase 4)")
    public ResponseEntity<ApiResponse<SimulatorScenarioDto.Response>> fork(
            @PathVariable UUID id) {
        return ResponseEntity.ok(
                ApiResponse.success(scenarioService.fork(id), "Scenario forked"));
    }

    @GetMapping("/{id}/forks")
    @Operation(summary = "Root + all siblings of a scenario's comparison set (Phase 4)")
    public ResponseEntity<ApiResponse<List<SimulatorScenarioDto.Response>>> forks(
            @PathVariable UUID id) {
        return ResponseEntity.ok(
                ApiResponse.success(scenarioService.listForkSet(id)));
    }

    @PostMapping("/{id}/snapshot")
    @Operation(summary = "Capture/refresh the live-config diff baseline (Phase 3; sandbox-only write)")
    public ResponseEntity<ApiResponse<SimulatorScenarioDto.Response>> snapshot(
            @PathVariable UUID id,
            @RequestParam(required = false, defaultValue = "false") boolean force) {
        scenarioSnapshotService.captureSnapshot(id, force);
        return ResponseEntity.ok(
                ApiResponse.success(scenarioService.getScenario(id), "Snapshot captured"));
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Single-user activate: write the proposed structure "
            + "as live sweep rules (Phase 3; gated by simulator.v3.activation)")
    public ResponseEntity<ApiResponse<SimulatorScenarioDto.Response>> activate(
            @PathVariable UUID id,
            @RequestBody(required = false) JsonNode body) {
        String notes = body != null && body.hasNonNull("notes")
                ? body.get("notes").asText() : null;
        String activatedBy = body != null && body.hasNonNull("activatedBy")
                ? body.get("activatedBy").asText() : "treasurer";
        LocalDateTime scheduledAt = null;
        if (body != null && body.hasNonNull("scheduledAt")) {
            try {
                scheduledAt = LocalDateTime.parse(
                        body.get("scheduledAt").asText());
            } catch (Exception ignored) {
                // best-effort; default = now
            }
        }
        try {
            activationService.activate(id, notes, scheduledAt, activatedBy);
            return ResponseEntity.ok(ApiResponse.success(
                    scenarioService.getScenario(id), "Scenario activated"));
        } catch (RuntimeException ex) {
            // Main tx rolled back (no partial live writes); persist the
            // audited reason in a fresh tx and surface it.
            activationService.recordActivationFailure(id, ex.getMessage());
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(ex.getMessage()));
        }
    }
}
