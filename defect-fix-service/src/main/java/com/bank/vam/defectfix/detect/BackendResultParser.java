package com.bank.vam.defectfix.detect;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses the JUnit surefire XML reports uploaded by
 * .github/workflows/backend-ci.yml (target/surefire-reports/TEST-*.xml).
 */
@Component
public class BackendResultParser {

    /** @param reportsByFileName filename -> XML content, one entry per TEST-*.xml file */
    public List<DetectedDefect> parseSurefireReports(Map<String, String> reportsByFileName) throws Exception {
        List<DetectedDefect> defects = new ArrayList<>();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); // no external entities
        DocumentBuilder builder = factory.newDocumentBuilder();

        for (String xml : reportsByFileName.values()) {
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList testcases = doc.getElementsByTagName("testcase");
            for (int i = 0; i < testcases.getLength(); i++) {
                Element testcase = (Element) testcases.item(i);
                Element problem = firstChild(testcase, "failure");
                if (problem == null) {
                    problem = firstChild(testcase, "error");
                }
                if (problem == null) {
                    continue;
                }
                String className = testcase.getAttribute("classname");
                String testName = testcase.getAttribute("name");
                String message = problem.getAttribute("message");
                String key = className + "#" + testName;
                defects.add(new DetectedDefect(
                        new DefectSignature("backend-test", key),
                        "[test] " + key + " failed",
                        (message == null || message.isBlank() ? problem.getTextContent() : message)));
            }
        }
        return defects;
    }

    private Element firstChild(Element parent, String tagName) {
        NodeList children = parent.getElementsByTagName(tagName);
        return children.getLength() > 0 ? (Element) children.item(0) : null;
    }
}
