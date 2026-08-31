package com.bank.vam.ai.copilot.streaming;

import com.bank.vam.ai.copilot.CopilotProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TokenStreamer}.
 *
 * <p>Two things we care about:
 * <ol>
 *   <li>Tokenisation preserves every character — concatenating tokens
 *       reconstructs the input exactly (so the frontend can render naively
 *       with no whitespace-fixup).</li>
 *   <li>The Flux emits the right number of elements, paced at the configured
 *       delay, and completes.</li>
 * </ol>
 *
 * <p>Uses only {@code reactor-core} (already on the classpath via WebFlux),
 * not {@code reactor-test} — no extra test dependency.
 */
class TokenStreamerTest {

    @Test
    void tokeniseReconstructsInputExactly() {
        String input = "Hello, world!\n\nSecond line   with  weird spaces.\nLine three.";
        List<String> tokens = TokenStreamer.tokenise(input);
        assertThat(String.join("", tokens)).isEqualTo(input);
    }

    @Test
    void tokeniseEmitsOneTokenPerWord() {
        // 4 words → expected: ["Hello, ", "this ", "is ", "treasury."]
        List<String> tokens = TokenStreamer.tokenise("Hello, this is treasury.");
        assertThat(tokens).hasSize(4);
        assertThat(tokens.get(0)).endsWith(" ");
        assertThat(tokens.get(3)).isEqualTo("treasury.");
    }

    @Test
    void tokeniseHandlesEmptyAndWhitespace() {
        assertThat(TokenStreamer.tokenise("")).isEmpty();
        // Pure whitespace is one whitespace-run token, not zero.
        assertThat(TokenStreamer.tokenise("   ")).containsExactly("   ");
    }

    @Test
    void streamEmitsAllTokensInOrderAndCompletes() {
        TokenStreamer streamer = new TokenStreamer(propsWithDelay(0));
        List<String> emitted = streamer.stream("one two three").collectList().block();
        assertThat(emitted).containsExactly("one ", "two ", "three");
    }

    @Test
    void streamReturnsEmptyForNullOrBlank() {
        TokenStreamer streamer = new TokenStreamer(propsWithDelay(0));
        assertThat(streamer.stream(null).collectList().block()).isEmpty();
        assertThat(streamer.stream("").collectList().block()).isEmpty();
    }

    @Test
    void streamPacesElementsAtConfiguredDelay() {
        // 3 tokens × 50ms = ~150ms minimum end-to-end.
        TokenStreamer streamer = new TokenStreamer(propsWithDelay(50));
        long started = System.nanoTime();
        List<String> emitted = streamer.stream("a b c").collectList().block();
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(emitted).containsExactly("a ", "b ", "c");
        // Allow generous lower bound (scheduler granularity). The point is that
        // it is not instantaneous — we want >= 2×delay between first and last.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(100);
    }

    private static CopilotProperties propsWithDelay(int ms) {
        CopilotProperties p = new CopilotProperties();
        p.setStreamTokenDelayMs(ms);
        return p;
    }
}
