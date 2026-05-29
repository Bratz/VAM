package com.bank.vam.dto.simulator;

import com.bank.vam.entity.simulator.SimulatorScenario.ScenarioStatus;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Wire shapes for the Simulator scenario API. Entities never cross the
 * controller boundary.
 *
 * <p>Payload fields are {@link JsonNode} so the frontend POSTs real JSON
 * objects ({@code { shadows:[...], rules:[...] }}) rather than stringified
 * JSON; the service serialises to / from the entity's JSONB columns.
 */
public final class SimulatorScenarioDto {

    private SimulatorScenarioDto() {
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private UUID id;
        private UUID corporateId;
        private String scenarioName;
        private String scenarioReference;
        private String description;
        private String baseCurrency;
        private ScenarioStatus status;
        private UUID parentScenarioId;
        private String forkLabel;
        private LocalDateTime snapshotTakenAt;
        private JsonNode snapshotPayload;
        private JsonNode proposedPayload;
        private JsonNode scorePayload;
        private String notes;
        private LocalDateTime createdAt;
        private String createdBy;
        private LocalDateTime updatedAt;
        private String updatedBy;
        private Long version;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        private UUID corporateId;
        private String scenarioName;
        private String baseCurrency;
        private String description;
        /** Optional; defaults to an empty {@code {shadows:[],rules:[]}} structure. */
        private JsonNode proposedPayload;
        /** Forking (Phase 4) — accepted by the API in V1, surfaced in the UI later. */
        private UUID parentScenarioId;
        private String forkLabel;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        private String scenarioName;
        private String description;
        private String baseCurrency;
        private ScenarioStatus status;
        private JsonNode proposedPayload;
        private JsonNode scorePayload;
        private JsonNode snapshotPayload;
        private LocalDateTime snapshotTakenAt;
        private String forkLabel;
        private String notes;
    }
}
