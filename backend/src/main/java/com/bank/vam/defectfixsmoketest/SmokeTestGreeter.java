package com.bank.vam.defectfixsmoketest;

/**
 * Throwaway class for testing the automated defect-fix pipeline's backend path
 * (see tasks/defect-autofix-pipeline-design.md). Safe to delete once the
 * pipeline has picked up and fixed the deliberate bug below.
 */
public class SmokeTestGreeter {

    // Deliberately wrong: ignores the name argument entirely.
    public String greet(String name) {
        return "Hello, World!";
    }
}
