package com.bank.vam.service.simulator;

import com.bank.vam.dto.simulator.SimulatorScenarioDto;
import com.bank.vam.entity.simulator.SimulatorScenario;
import com.bank.vam.entity.simulator.SimulatorScenario.ScenarioStatus;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.simulator.SimulatorScenarioRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Scenario CRUD for the Cash Concentration Simulator (Phase 1).
 *
 * <p><b>Sandbox isolation:</b> this service touches only
 * {@code simulator_scenarios}. It never reads or writes
 * {@code virtual_accounts}, {@code sweep_rules}, or {@code physical_accounts}.
 * The composite inventory read (Physical Accounts + existing Shadow VAs) is
 * served by the frontend {@code simulatorApi} composing the existing
 * {@code physicalAccountsApi} / {@code shadowAccountApi} — not from here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimulatorScenarioService {

    private final SimulatorScenarioRepository scenarioRepository;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter REF_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String EMPTY_PAYLOAD = "{\"shadows\":[],\"rules\":[]}";

    @Transactional(readOnly = true)
    public List<SimulatorScenarioDto.Response> listScenarios(UUID corporateId) {
        return scenarioRepository.findByCorporateIdOrderByUpdatedAtDesc(corporateId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public SimulatorScenarioDto.Response getScenario(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public SimulatorScenarioDto.Response createScenario(SimulatorScenarioDto.CreateRequest req) {
        if (req.getCorporateId() == null) {
            throw new IllegalArgumentException("corporateId is required");
        }
        if (!StringUtils.hasText(req.getScenarioName())) {
            throw new IllegalArgumentException("scenarioName is required");
        }
        SimulatorScenario scenario = SimulatorScenario.builder()
                .corporateId(req.getCorporateId())
                .scenarioName(req.getScenarioName().trim())
                .scenarioReference(nextReference())
                .description(req.getDescription())
                .baseCurrency(req.getBaseCurrency())
                .status(ScenarioStatus.DRAFT)
                .parentScenarioId(req.getParentScenarioId())
                .forkLabel(req.getForkLabel())
                .proposedPayload(writeJson(req.getProposedPayload(), EMPTY_PAYLOAD))
                .build();
        SimulatorScenario saved = scenarioRepository.save(scenario);
        log.info("Simulator scenario created: {} ({}) for corporate {}",
                saved.getId(), saved.getScenarioReference(), saved.getCorporateId());
        return toResponse(saved);
    }

    /**
     * Phase-4 fork — a sibling sharing one root. The root is implicitly 'A';
     * the new fork gets the next free letter among the set. Copies the
     * proposed structure; status DRAFT; independently editable. Sandbox-only.
     */
    @Transactional
    public SimulatorScenarioDto.Response fork(UUID id) {
        SimulatorScenario parent = scenarioRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Simulator scenario not found: " + id));
        UUID rootId = parent.getParentScenarioId() != null
                ? parent.getParentScenarioId() : parent.getId();
        SimulatorScenario root = parent.getParentScenarioId() != null
                ? scenarioRepository.findById(rootId).orElse(parent)
                : parent;

        List<SimulatorScenario> set = new ArrayList<>();
        set.add(root);
        set.addAll(scenarioRepository.findByParentScenarioId(rootId));

        if (root.getForkLabel() == null || root.getForkLabel().isBlank()) {
            root.setForkLabel("A");
            scenarioRepository.save(root);
        }
        Set<String> used = set.stream()
                .map(SimulatorScenario::getForkLabel)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        String label = nextForkLabel(used);

        SimulatorScenario fork = SimulatorScenario.builder()
                .corporateId(parent.getCorporateId())
                .scenarioName(stripForkSuffix(parent.getScenarioName())
                        + " (" + label + ")")
                .scenarioReference(nextReference())
                .description(parent.getDescription())
                .baseCurrency(parent.getBaseCurrency())
                .status(ScenarioStatus.DRAFT)
                .parentScenarioId(rootId)
                .forkLabel(label)
                .proposedPayload(parent.getProposedPayload())
                .build();
        SimulatorScenario saved = scenarioRepository.save(fork);
        log.info("Simulator scenario {} forked → {} (label {})",
                id, saved.getId(), label);
        return toResponse(saved);
    }

    /** Root + all siblings (non-archived), resolved from any set member. */
    @Transactional(readOnly = true)
    public List<SimulatorScenarioDto.Response> listForkSet(UUID id) {
        SimulatorScenario s = scenarioRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Simulator scenario not found: " + id));
        UUID rootId = s.getParentScenarioId() != null
                ? s.getParentScenarioId() : s.getId();
        List<SimulatorScenario> out = new ArrayList<>();
        scenarioRepository.findById(rootId).ifPresent(out::add);
        out.addAll(scenarioRepository.findByParentScenarioId(rootId));
        return out.stream()
                .filter(x -> x.getStatus() != ScenarioStatus.ARCHIVED)
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private String nextForkLabel(Set<String> used) {
        for (char ch = 'A'; ch <= 'Z'; ch++) {
            String s = String.valueOf(ch);
            if (!used.contains(s)) return s;
        }
        return "X";
    }

    private String stripForkSuffix(String name) {
        return name == null ? "Scenario" : name.replaceAll("\\s*\\([A-Z]\\)\\s*$", "");
    }

    @Transactional
    public SimulatorScenarioDto.Response updateScenario(UUID id, SimulatorScenarioDto.UpdateRequest req) {
        SimulatorScenario scenario = findOrThrow(id);
        if (StringUtils.hasText(req.getScenarioName())) {
            scenario.setScenarioName(req.getScenarioName().trim());
        }
        if (req.getDescription() != null) {
            scenario.setDescription(req.getDescription());
        }
        if (req.getBaseCurrency() != null) {
            scenario.setBaseCurrency(req.getBaseCurrency());
        }
        if (req.getStatus() != null) {
            scenario.setStatus(req.getStatus());
        }
        if (req.getProposedPayload() != null) {
            scenario.setProposedPayload(writeJson(req.getProposedPayload(), EMPTY_PAYLOAD));
        }
        if (req.getScorePayload() != null) {
            scenario.setScorePayload(writeJson(req.getScorePayload(), null));
        }
        if (req.getSnapshotPayload() != null) {
            scenario.setSnapshotPayload(writeJson(req.getSnapshotPayload(), null));
        }
        if (req.getSnapshotTakenAt() != null) {
            scenario.setSnapshotTakenAt(req.getSnapshotTakenAt());
        }
        if (req.getForkLabel() != null) {
            scenario.setForkLabel(req.getForkLabel());
        }
        if (req.getNotes() != null) {
            scenario.setNotes(req.getNotes());
        }
        SimulatorScenario saved = scenarioRepository.save(scenario);
        log.debug("Simulator scenario updated: {}", saved.getId());
        return toResponse(saved);
    }

    /**
     * Persist the latest computed Optimisation Score (Phase 2). The score is
     * computed client-side (deterministic, inputs in memory); this only
     * freezes the payload onto the scenario row. Sandbox-only — touches no
     * live operational table.
     */
    @Transactional
    public SimulatorScenarioDto.Response saveScore(UUID id, JsonNode score) {
        SimulatorScenario scenario = findOrThrow(id);
        scenario.setScorePayload(writeJson(score, null));
        SimulatorScenario saved = scenarioRepository.save(scenario);
        log.debug("Simulator scenario score persisted: {}", saved.getId());
        return toResponse(saved);
    }

    /** Soft delete — flips status to ARCHIVED; the row is retained for audit. */
    @Transactional
    public void deleteScenario(UUID id) {
        SimulatorScenario scenario = findOrThrow(id);
        scenario.setStatus(ScenarioStatus.ARCHIVED);
        scenarioRepository.save(scenario);
        log.info("Simulator scenario archived (soft delete): {}", id);
    }

    // ------------------------------------------------------------------ helpers

    private SimulatorScenario findOrThrow(UUID id) {
        return scenarioRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Simulator scenario not found: " + id));
    }

    /**
     * {@code SCN-yyyyMMdd-NNN}, sequence reset daily. {@code synchronized}
     * serialises the count+1 within an instance; the write volume of a
     * sandbox tool makes the cross-instance race acceptable for V1.
     */
    private synchronized String nextReference() {
        String prefix = "SCN-" + LocalDate.now().format(REF_DATE) + "-";
        long todayCount = scenarioRepository.countByScenarioReferenceStartingWith(prefix);
        return String.format("%s%03d", prefix, todayCount + 1);
    }

    private String writeJson(JsonNode node, String fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            log.warn("Failed to serialise scenario payload, using fallback: {}", e.getMessage());
            return fallback;
        }
    }

    private JsonNode readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            log.warn("Failed to parse stored scenario payload: {}", e.getMessage());
            return null;
        }
    }

    private SimulatorScenarioDto.Response toResponse(SimulatorScenario s) {
        return SimulatorScenarioDto.Response.builder()
                .id(s.getId())
                .corporateId(s.getCorporateId())
                .scenarioName(s.getScenarioName())
                .scenarioReference(s.getScenarioReference())
                .description(s.getDescription())
                .baseCurrency(s.getBaseCurrency())
                .status(s.getStatus())
                .parentScenarioId(s.getParentScenarioId())
                .forkLabel(s.getForkLabel())
                .snapshotTakenAt(s.getSnapshotTakenAt())
                .snapshotPayload(readJson(s.getSnapshotPayload()))
                .proposedPayload(readJson(s.getProposedPayload()))
                .scorePayload(readJson(s.getScorePayload()))
                .notes(s.getNotes())
                .createdAt(s.getCreatedAt())
                .createdBy(s.getCreatedBy())
                .updatedAt(s.getUpdatedAt())
                .updatedBy(s.getUpdatedBy())
                .version(s.getVersion())
                .build();
    }
}
