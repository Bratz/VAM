package com.bank.vam.forecast;

import com.bank.vam.entity.Corporate;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.payables.Payable;
import com.bank.vam.entity.payables.Payable.PayableStatus;
import com.bank.vam.entity.payables.Payable.PayableType;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.entity.receivables.Receivable.ReceivableStatus;
import com.bank.vam.entity.receivables.Receivable.ReceivableType;
import com.bank.vam.forecast.domain.ForecastAdjustment;
import com.bank.vam.forecast.domain.ForecastCategory;
import com.bank.vam.forecast.domain.ForecastLine;
import com.bank.vam.forecast.domain.ForecastRun;
import com.bank.vam.forecast.domain.enums.ForecastDirection;
import com.bank.vam.forecast.domain.enums.ForecastSource;
import com.bank.vam.forecast.domain.enums.RunStatus;
import com.bank.vam.forecast.engine.ForecastContext;
import com.bank.vam.forecast.engine.ForecastEngine;
import com.bank.vam.forecast.orchestration.ForecastOrchestrator;
import com.bank.vam.forecast.repository.ForecastAdjustmentRepository;
import com.bank.vam.forecast.repository.ForecastCategoryRepository;
import com.bank.vam.forecast.repository.ForecastLineRepository;
import com.bank.vam.forecast.repository.ForecastRunRepository;
import com.bank.vam.repository.CorporateRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.payables.PayableRepository;
import com.bank.vam.repository.receivables.ReceivableRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T7 DoD — integration test for {@link ForecastOrchestrator}.
 *
 * <p>Three scenarios, all against a real Postgres via Testcontainers:
 * <ol>
 *   <li>{@link #seededPayrollAndAr_produceCompletedRunWithExpectedLines()} —
 *       happy path. One payroll Payable + one open Receivable → asserts
 *       COMPLETED run, ≥1 PATTERN/PAYROLL line, ≥1 AGING/AR_COLLECTIONS line,
 *       generation time &lt; 5 seconds.</li>
 *   <li>{@link #brokenEngine_doesNotSinkRun()} — a deliberately broken
 *       engine (the static {@link BrokenEngine} nested class, picked up as a
 *       {@code @Component} from the test classpath) throws in
 *       {@code generate()}. The orchestrator must catch + log + continue;
 *       the other engines' output stays on the run.</li>
 *   <li>{@link #carryForwardAdjustment_emittedByManualOverlayOnSubsequentRun()} —
 *       a {@code ForecastAdjustment} saved against the first run is re-emitted
 *       as a MANUAL line on a second run.</li>
 * </ol>
 *
 * <p>Schema is created by Hibernate {@code ddl-auto=create-drop} per
 * {@code application-test.yml} — Flyway stays OFF in the test profile (same
 * as prod-dev) so the V13 category seed does NOT run automatically;
 * categories are seeded explicitly in {@link #seedCategories()}.
 *
 * <p>{@link BrokenEngine} is always present in the Spring context (it's a
 * test-classpath {@code @Component}). The happy-path and carry-forward
 * tests don't care about its presence — they assert only the lines they
 * expect — and the broken-engine test exercises it directly.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class ForecastOrchestratorIT {

    @Container
    @SuppressWarnings("resource") // managed by Testcontainers
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("vam_test")
        .withUsername("vam_test")
        .withPassword("vam_test");

    @DynamicPropertySource
    static void wireDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
    }

    /**
     * Deliberately broken engine. Claims the DRIVER source so it cannot
     * collide with the real PATTERN / AGING / MANUAL engines, and always
     * throws — which the orchestrator must catch + log + continue.
     */
    @Component
    static class BrokenEngine implements ForecastEngine {
        final AtomicInteger callCount = new AtomicInteger(0);

        @Override
        public ForecastSource type() {
            return ForecastSource.DRIVER;
        }

        @Override
        public boolean supports(ForecastCategory category) {
            return category != null && category.getDefaultEngine() == ForecastSource.DRIVER;
        }

        @Override
        public List<ForecastLine> generate(ForecastContext ctx) {
            callCount.incrementAndGet();
            throw new RuntimeException("BrokenEngine: deliberate failure for the IT");
        }
    }

    @Autowired ForecastOrchestrator orchestrator;
    @Autowired ForecastRunRepository runRepository;
    @Autowired ForecastLineRepository lineRepository;
    @Autowired ForecastCategoryRepository categoryRepository;
    @Autowired ForecastAdjustmentRepository adjustmentRepository;
    @Autowired CorporateRepository corporateRepository;
    @Autowired LegalEntityRepository legalEntityRepository;
    @Autowired PayableRepository payableRepository;
    @Autowired ReceivableRepository receivableRepository;
    @Autowired BrokenEngine brokenEngine;

    private UUID corporateId;
    private UUID entityId;
    private UUID capexCategoryId;

    @BeforeEach
    void seed() {
        brokenEngine.callCount.set(0);

        Corporate corporate = Corporate.builder()
            .corporateId("ACME-IT-" + UUID.randomUUID().toString().substring(0, 8))
            .legalName("Acme Test MNC")
            .build();
        corporate = corporateRepository.save(corporate);
        corporateId = corporate.getId();

        LegalEntity entity = LegalEntity.createHolding(
            corporateId, "ACME-HOLD-" + UUID.randomUUID().toString().substring(0, 6),
            "Acme Holding", "AED", false, null);
        entity = legalEntityRepository.save(entity);
        entityId = entity.getId();

        seedCategories();

        Payable payroll = Payable.builder()
            .payableNumber("PAY-IT-" + UUID.randomUUID().toString().substring(0, 8))
            .payableType(PayableType.SALARY)
            .status(PayableStatus.SCHEDULED)
            .corporateId(corporateId)
            .owningEntityId(entityId)
            .currencyCode("AED")
            .grossAmount(new BigDecimal("50000.00"))
            .netAmount(new BigDecimal("50000.00"))
            .outstandingAmount(new BigDecimal("50000.00"))
            .dueDate(LocalDate.now().plusDays(14))
            .build();
        payableRepository.save(payroll);

        Receivable invoice = Receivable.builder()
            .receivableNumber("REC-IT-" + UUID.randomUUID().toString().substring(0, 8))
            .receivableType(ReceivableType.INVOICE)
            .status(ReceivableStatus.OPEN)
            .corporateId(corporateId)
            .owningEntityId(entityId)
            .currencyCode("AED")
            .netAmount(new BigDecimal("75000.00"))
            .outstandingAmount(new BigDecimal("75000.00"))
            .dueDate(LocalDate.now().plusDays(30))
            .build();
        receivableRepository.save(invoice);
    }

    /**
     * Categories the engines need:
     * <ul>
     *   <li>PAYROLL  (PATTERN, OUT) — for the payroll payable</li>
     *   <li>AR_COLLECTIONS (AGING, IN) — for the receivable</li>
     *   <li>CAPEX (MANUAL, OUT) — for the carry-forward test</li>
     *   <li>FX_CONVERSION (DRIVER, IN) — gives BrokenEngine a category to
     *       claim so it actually gets invoked (otherwise supports() returns
     *       false for every category and the orchestrator skips the engine)</li>
     * </ul>
     */
    private void seedCategories() {
        categoryRepository.save(ForecastCategory.builder()
            .code("PAYROLL").label("Payroll").direction(ForecastDirection.OUT)
            .defaultEngine(ForecastSource.PATTERN).color("#6A1B9A").build());
        categoryRepository.save(ForecastCategory.builder()
            .code("AR_COLLECTIONS").label("AR Collections").direction(ForecastDirection.IN)
            .defaultEngine(ForecastSource.AGING).color("#2E7D32").build());
        ForecastCategory capex = categoryRepository.save(ForecastCategory.builder()
            .code("CAPEX").label("Capital Expenditure").direction(ForecastDirection.OUT)
            .defaultEngine(ForecastSource.MANUAL).color("#5D4037").build());
        categoryRepository.save(ForecastCategory.builder()
            .code("FX_CONVERSION").label("FX Conversion").direction(ForecastDirection.IN)
            .defaultEngine(ForecastSource.DRIVER).color("#0277BD").build());
        capexCategoryId = capex.getId();
    }

    @AfterEach
    void cleanup() {
        // Order matters: child rows before parents. ForecastLine has a
        // @ManyToOne ForecastRun and @ManyToOne ForecastCategory; the
        // adjustments reference both via bare UUID FK.
        adjustmentRepository.deleteAll();
        lineRepository.deleteAll();
        runRepository.deleteAll();
        categoryRepository.deleteAll();
        payableRepository.deleteAll();
        receivableRepository.deleteAll();
        legalEntityRepository.deleteAll();
        corporateRepository.deleteAll();
    }

    // ==================================================================
    // Test 1 — Happy path: COMPLETED run with payroll + AR lines.
    // ==================================================================

    @Test
    void seededPayrollAndAr_produceCompletedRunWithExpectedLines() {
        long t0 = System.currentTimeMillis();
        ForecastRun run = orchestrator.run(corporateId, 91);
        long elapsed = System.currentTimeMillis() - t0;

        assertThat(run).isNotNull();
        assertThat(run.getId()).isNotNull();
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.getHorizonEnd()).isEqualTo(LocalDate.now().plusDays(91));
        assertThat(run.getCreatedBy()).isEqualTo("system");
        assertThat(run.getGenerationMs()).isNotNull().isLessThan(5_000);
        assertThat(elapsed).as("end-to-end orchestrator round-trip").isLessThan(5_000);

        List<ForecastLine> lines = lineRepository.findByRunIdAndValueDateBetween(
            run.getId(), LocalDate.now().minusDays(1), LocalDate.now().plusDays(91));

        assertThat(lines).isNotEmpty();

        assertThat(lines)
            .as("≥1 PATTERN line for the seeded payroll payable")
            .anySatisfy(l -> {
                assertThat(l.getSource()).isEqualTo(ForecastSource.PATTERN);
                assertThat(l.getCategory().getCode()).isEqualTo("PAYROLL");
                assertThat(l.getAmountMid()).isEqualByComparingTo(new BigDecimal("50000.00"));
                assertThat(l.getCurrency()).isEqualTo("AED");
                assertThat(l.getEntityId()).isEqualTo(entityId);
                assertThat(l.getSourceRef()).startsWith("payable:");
            });

        assertThat(lines)
            .as("≥1 AGING line for the seeded open receivable")
            .anySatisfy(l -> {
                assertThat(l.getSource()).isEqualTo(ForecastSource.AGING);
                assertThat(l.getCategory().getCode()).isEqualTo("AR_COLLECTIONS");
                assertThat(l.getAmountMid()).isEqualByComparingTo(new BigDecimal("75000.00"));
                assertThat(l.getCurrency()).isEqualTo("AED");
                assertThat(l.getEntityId()).isEqualTo(entityId);
                assertThat(l.getSourceRef()).startsWith("receivable:");
            });
    }

    // ==================================================================
    // Test 2 — Broken engine is isolated; the run still completes.
    // ==================================================================

    @Test
    void brokenEngine_doesNotSinkRun() {
        ForecastRun run = orchestrator.run(corporateId, 91);

        assertThat(run.getStatus())
            .as("the run completes even though the DRIVER engine threw")
            .isEqualTo(RunStatus.COMPLETED);
        assertThat(brokenEngine.callCount.get())
            .as("the broken engine was actually invoked (and threw)")
            .isEqualTo(1);

        List<ForecastLine> lines = lineRepository.findByRunIdAndValueDateBetween(
            run.getId(), LocalDate.now().minusDays(1), LocalDate.now().plusDays(91));
        assertThat(lines).isNotEmpty();
        assertThat(lines)
            .as("PATTERN/PAYROLL line still present alongside the failed DRIVER engine")
            .anyMatch(l -> l.getSource() == ForecastSource.PATTERN
                && "PAYROLL".equals(l.getCategory().getCode()));
        assertThat(lines)
            .as("AGING/AR_COLLECTIONS line still present")
            .anyMatch(l -> l.getSource() == ForecastSource.AGING
                && "AR_COLLECTIONS".equals(l.getCategory().getCode()));
        assertThat(lines)
            .as("no DRIVER lines (the engine threw before emitting any)")
            .noneMatch(l -> l.getSource() == ForecastSource.DRIVER);
    }

    // ==================================================================
    // Test 3 — Carry-forward adjustment surfaces as a MANUAL line on the
    // next run.
    // ==================================================================

    @Test
    void carryForwardAdjustment_emittedByManualOverlayOnSubsequentRun() {
        ForecastRun firstRun = orchestrator.run(corporateId, 91);
        assertThat(firstRun.getStatus()).isEqualTo(RunStatus.COMPLETED);

        LocalDate adjustmentDate = LocalDate.now().plusDays(45);
        ForecastAdjustment adj = ForecastAdjustment.builder()
            .runId(firstRun.getId())
            .valueDate(adjustmentDate)
            .entityId(entityId)
            .currency("AED")
            .categoryId(capexCategoryId)
            .deltaAmount(new BigDecimal("12000.00"))
            .reasonCode("CAPEX_HARDWARE")
            .note("Server refresh — manual overlay")
            .createdBy("treasurer.test")
            .build();
        adj = adjustmentRepository.save(adj);
        UUID adjId = adj.getId();

        ForecastRun secondRun = orchestrator.run(corporateId, 91);
        assertThat(secondRun.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(secondRun.getId()).isNotEqualTo(firstRun.getId());

        List<ForecastLine> secondRunLines = lineRepository.findByRunIdAndValueDateBetween(
            secondRun.getId(), LocalDate.now().minusDays(1), LocalDate.now().plusDays(91));

        assertThat(secondRunLines)
            .as("the carry-forward adjustment is re-emitted as a MANUAL line on the second run")
            .anySatisfy(l -> {
                assertThat(l.getSource()).isEqualTo(ForecastSource.MANUAL);
                assertThat(l.getCategory().getCode()).isEqualTo("CAPEX");
                assertThat(l.getValueDate()).isEqualTo(adjustmentDate);
                assertThat(l.getAmountMid()).isEqualByComparingTo(new BigDecimal("12000.00"));
                assertThat(l.getSourceRef()).isEqualTo("adjustment:" + adjId);
            });
    }
}
