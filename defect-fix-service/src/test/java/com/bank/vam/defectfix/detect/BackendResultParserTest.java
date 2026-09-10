package com.bank.vam.defectfix.detect;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BackendResultParserTest {

    private final BackendResultParser parser = new BackendResultParser();

    @Test
    void parsesOnlyFailingTestcases() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.bank.vam.service.audit.AuditLogServiceTest" tests="2" failures="1" errors="0">
                  <testcase classname="com.bank.vam.service.audit.AuditLogServiceTest" name="testLogsAudit" time="0.01"/>
                  <testcase classname="com.bank.vam.service.audit.AuditLogServiceTest" name="testRejectsNull" time="0.01">
                    <failure message="expected true but was false">stack trace here</failure>
                  </testcase>
                </testsuite>
                """;

        List<DetectedDefect> defects = parser.parseSurefireReports(Map.of("TEST-AuditLogServiceTest.xml", xml));

        assertThat(defects).hasSize(1);
        DetectedDefect defect = defects.get(0);
        assertThat(defect.signature().source()).isEqualTo("backend-test");
        assertThat(defect.signature().key())
                .isEqualTo("com.bank.vam.service.audit.AuditLogServiceTest#testRejectsNull");
        assertThat(defect.details()).contains("expected true but was false");
    }

    @Test
    void allTestsPassingYieldsNoDefects() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.bank.vam.FooTest" tests="1" failures="0" errors="0">
                  <testcase classname="com.bank.vam.FooTest" name="testOk" time="0.01"/>
                </testsuite>
                """;

        assertThat(parser.parseSurefireReports(Map.of("TEST-FooTest.xml", xml))).isEmpty();
    }
}
