package com.bank.vam.defectfixsmoketest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SmokeTestGreeterTest {

    private final SmokeTestGreeter greeter = new SmokeTestGreeter();

    @Test
    void greetIncludesTheGivenName() {
        assertThat(greeter.greet("Alex")).isEqualTo("Hello, Alex!");
    }
}
