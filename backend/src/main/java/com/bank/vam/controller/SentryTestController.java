package com.bank.vam.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Throwaway endpoint to confirm the backend Sentry SDK is actually delivering events. Safe to
 * delete once verified in the Sentry dashboard — see tasks/defect-autofix-pipeline-design.md.
 */
@RestController
@RequestMapping("/api/v1")
public class SentryTestController {

    @GetMapping("/sentry-test")
    public void test() {
        throw new RuntimeException("Sentry backend connectivity test");
    }
}
