package com.bank.vam.ai.copilot.streaming;

import com.bank.vam.ai.copilot.CopilotProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a fully-composed response into word-sized chunks and emits them on a
 * {@link Flux} with a configurable inter-chunk delay, creating the visual
 * illusion of live LLM streaming.
 *
 * <p>The stub knows the entire answer up front; this class just paces the
 * delivery so the UX matches what users expect from a chat interface.
 *
 * <p>Tokenisation strategy: split on word boundaries, keeping any trailing
 * whitespace (incl. newlines) attached to the preceding word. A 100-word reply
 * becomes ~100 chunks; at the default 30ms delay, that's ~3s total — close to
 * real LLM throughput.
 */
@Component
@RequiredArgsConstructor
public class TokenStreamer {

    /**
     * Greedy match: a run of non-whitespace plus any trailing whitespace.
     * E.g. {@code "Hello,  world\nfoo"} → {@code ["Hello,  ", "world\n", "foo"]}.
     */
    private static final Pattern TOKEN = Pattern.compile("\\S+\\s*|\\s+");

    private final CopilotProperties properties;

    /**
     * Stream {@code text} as paced chunks. Returns an empty Flux for null/empty
     * input. The first token is delayed by {@code stream-token-delay-ms} just
     * like the rest — the small initial delay is harmless and keeps the
     * frontend's "thinking" indicator visible for at least one frame.
     */
    public Flux<String> stream(String text) {
        if (text == null || text.isEmpty()) {
            return Flux.empty();
        }
        List<String> tokens = tokenise(text);
        Duration delay = Duration.ofMillis(Math.max(0, properties.getStreamTokenDelayMs()));
        if (delay.isZero()) {
            return Flux.fromIterable(tokens);
        }
        return Flux.fromIterable(tokens).delayElements(delay);
    }

    /** Visible for testing. */
    static List<String> tokenise(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }
}
