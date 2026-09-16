package com.bank.vam.fileingest.transform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransformTestGateRunnerTest {

    private final TransformTestGateRunner gateRunner = new TransformTestGateRunner();

    @Test
    void passesOnExitCodeZero() {
        assertThat(gateRunner.decide(0, "BUILD SUCCESS").passed()).isTrue();
    }

    @Test
    void failsOnNonZeroExitCode() {
        assertThat(gateRunner.decide(1, "BUILD FAILURE").passed()).isFalse();
    }
}
