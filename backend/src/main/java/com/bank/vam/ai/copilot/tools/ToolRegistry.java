package com.bank.vam.ai.copilot.tools;

import com.bank.vam.config.MarketProfileProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Discovers all {@link CopilotTool} beans on the classpath and exposes them
 * by name. Constructor-injected list ⇒ Spring auto-collects every {@code
 * CopilotTool} implementation, so adding a tool is just dropping in a new
 * {@code @Component}.
 *
 * <p>Also exposes the helper {@link #buildContext} so callers don't need to
 * touch {@link MarketProfileProperties} themselves.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRegistry {

    private final List<CopilotTool> tools;
    private final MarketProfileProperties marketProfile;

    private Map<String, CopilotTool> byName;

    /** Lazy-init the lookup map after Spring finishes wiring all tools. */
    private Map<String, CopilotTool> index() {
        if (byName == null) {
            byName = tools.stream().collect(Collectors.toUnmodifiableMap(
                    CopilotTool::name,
                    t -> t,
                    (a, b) -> {
                        throw new IllegalStateException(
                                "Duplicate Copilot tool name: " + a.name());
                    }
            ));
            log.info("ToolRegistry initialised with {} tool(s): {}",
                    byName.size(), byName.keySet());
        }
        return byName;
    }

    public Optional<CopilotTool> find(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(index().get(name));
    }

    public List<CopilotTool> all() {
        return List.copyOf(tools);
    }

    /**
     * Build a {@link ToolContext} from auth + market state. {@code corporateId}
     * is nullable in the prototype (no auth); the active market profile is
     * always known. {@code conversationId} is null for out-of-band invocations
     * (e.g. the {@code /tools/{name}/execute} diagnostic endpoint).
     */
    public ToolContext buildContext(UUID corporateId, String userId, UUID conversationId) {
        return new ToolContext(
                corporateId,
                userId,
                marketProfile.getDefaultCurrency(),
                marketProfile.getActiveProfileCode(),
                conversationId
        );
    }

    /** Backwards-compatible overload for callers without a conversation scope. */
    public ToolContext buildContext(UUID corporateId, String userId) {
        return buildContext(corporateId, userId, null);
    }
}
