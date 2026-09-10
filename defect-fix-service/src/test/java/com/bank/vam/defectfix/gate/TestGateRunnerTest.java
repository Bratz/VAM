package com.bank.vam.defectfix.gate;

import com.bank.vam.defectfix.detect.BackendResultParser;
import com.bank.vam.defectfix.detect.DefectDetectionService.Stack;
import com.bank.vam.defectfix.detect.DefectSignature;
import com.bank.vam.defectfix.detect.DetectedDefect;
import com.bank.vam.defectfix.detect.FrontendResultParser;
import com.bank.vam.defectfix.gate.TestGateRunner.GateResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestGateRunnerTest {

    private final TestGateRunner gateRunner = new TestGateRunner(new FrontendResultParser(), new BackendResultParser());

    private static final DefectSignature TARGET = new DefectSignature("frontend-lint", "no-unused-vars:a.tsx");
    private static final DefectSignature OTHER = new DefectSignature("frontend-lint", "no-console:b.tsx");

    @Test
    void passesWhenTargetDefectIsGoneEvenWithUnrelatedDefectsStillPresent() {
        // This is the exact bug this class fixes: the project has plenty of pre-existing,
        // unrelated defects — the gate must not fail because of THOSE.
        List<DetectedDefect> remaining = List.of(new DetectedDefect(OTHER, "unrelated", "unrelated"));

        GateResult result = gateRunner.decide(Stack.FRONTEND, remaining, TARGET);

        assertThat(result.passed()).isTrue();
    }

    @Test
    void failsWhenTargetDefectIsStillPresent() {
        List<DetectedDefect> remaining = List.of(new DetectedDefect(TARGET, "still there", "still there"));

        GateResult result = gateRunner.decide(Stack.FRONTEND, remaining, TARGET);

        assertThat(result.passed()).isFalse();
    }

    @Test
    void failsClosedOnACompletelyEmptyFrontendResultInsteadOfTreatingItAsClean() {
        // This codebase has hundreds of pre-existing frontend warnings — a truly empty result
        // means the tooling itself didn't run (crash, npm ci never finished), not that
        // everything is suddenly clean.
        GateResult result = gateRunner.decide(Stack.FRONTEND, List.of(), TARGET);

        assertThat(result.passed()).isFalse();
    }

    @Test
    void emptyBackendResultIsATruePassUnlikeFrontend() {
        // Backend doesn't carry the same pile of pre-existing warnings, so "no failing tests" is
        // the normal, expected clean state, not suspicious.
        GateResult result = gateRunner.decide(Stack.BACKEND, List.of(), new DefectSignature("backend-test", "Foo#bar"));

        assertThat(result.passed()).isTrue();
    }
}
