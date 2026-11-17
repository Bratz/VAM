package com.bank.vam.controller.intercompany;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec acceptance 4 and 6, structurally: the only intercompany settle action is a netting cycle's (no
 * {@code /settle-bilateral}, no recharge "settle via netting"), and nothing reads or writes {@code
 * intercompany_transactions}, which V64 drops in both migration folders.
 * ponytail: scans controllers and source text; a test per flow is in NettingSettlementTest / PoboLegsBalanceTest.
 */
class IntercompanySettlementSurfaceTest {

    private static final Pattern INTERCOMPANY = Pattern.compile("intercompany|pobo|cobo|recharge|netting");

    @Test
    void theOnlyIntercompanySettleActionIsANettingCycles() throws Exception {
        ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> settles = new ArrayList<>();
        List<String> all = new ArrayList<>();
        for (var bean : scan.findCandidateComponents("com.bank.vam")) {
            Class<?> controller = Class.forName(bean.getBeanClassName());
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            String[] prefixes = base != null && base.path().length > 0 ? base.path() : new String[] {""};
            for (Method m : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (mapping == null) continue;
                for (String prefix : prefixes) {
                    for (String path : mapping.path().length > 0 ? mapping.path() : new String[] {""}) {
                        String full = (prefix + path).toLowerCase(Locale.ROOT);
                        all.add(full);
                        boolean writes = mapping.method().length == 0
                            || !Arrays.asList(mapping.method()).equals(List.of(RequestMethod.GET));
                        if (writes && full.contains("settle") && INTERCOMPANY.matcher(full).find()) settles.add(full);
                    }
                }
            }
        }
        assertThat(all).as("controllers found").hasSizeGreaterThan(100);
        assertThat(all).noneMatch(p -> p.contains("settle-bilateral"));
        assertThat(settles).containsExactly("/api/v1/netting/cycles/{id}/settle");
    }

    @Test
    void noCodeReadsOrWritesTheLegacyTableAndV64DropsItInBothFolders() throws IOException {
        Pattern inStringLiteral = Pattern.compile("\"[^\"\\n]*intercompany_transactions");
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            List<Path> users = sources.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try {
                    return inStringLiteral.matcher(Files.readString(p)).find();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
            assertThat(users).isEmpty();
        }
        String v64 = "V64__drop_intercompany_transactions.sql";
        String runs = Files.readString(Path.of("src/main/resources/db/migration", v64));
        assertThat(runs).contains("DROP TABLE IF EXISTS intercompany_transactions;");
        assertThat(Files.readString(Path.of("../database/migrations", v64))).isEqualTo(runs);
    }
}
