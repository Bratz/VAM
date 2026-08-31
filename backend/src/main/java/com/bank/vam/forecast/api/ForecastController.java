package com.bank.vam.forecast.api;

import com.bank.vam.dto.ApiResponse;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.forecast.api.dto.ForecastLineDto;
import com.bank.vam.forecast.api.dto.ForecastRunDto;
import com.bank.vam.forecast.api.dto.ForecastSummaryDto;
import com.bank.vam.forecast.api.dto.ForecastWeeklyBucketDto;
import com.bank.vam.forecast.domain.ForecastLine;
import com.bank.vam.forecast.domain.ForecastRun;
import com.bank.vam.forecast.domain.enums.ForecastDirection;
import com.bank.vam.forecast.domain.enums.RunStatus;
import com.bank.vam.forecast.orchestration.ForecastScheduler;
import com.bank.vam.forecast.repository.ForecastLineRepository;
import com.bank.vam.forecast.repository.ForecastRunRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * REST surface for the Cash Forecasting module — Sprint 1 / T9.
 *
 * <p>Four endpoints back the cockpit's Forecasting page:
 * <ul>
 *   <li>{@code GET  /api/v1/forecasts/latest}        — headline summary + weekly buckets</li>
 *   <li>{@code GET  /api/v1/forecasts/{runId}/lines} — drill-down to atomic lines</li>
 *   <li>{@code GET  /api/v1/forecasts/{runId}}       — run metadata</li>
 *   <li>{@code POST /api/v1/forecasts/run}           — fire an on-demand run</li>
 * </ul>
 *
 * <p>Corporate scope is resolved from the {@code X-Corporate-Id} header,
 * which is auto-injected by the frontend axios layer. Absent / unparsable
 * headers return HTTP 400 (Spring's default behaviour for {@code @RequestHeader}
 * binding failure).
 *
 * <p>Sprint-1 deferrals (documented inline rather than overstated):
 * <ul>
 *   <li>{@code openingBalance} is anchored at {@code 0} — Sprint 2 wires
 *       {@code BalanceAggregationService}.</li>
 *   <li>{@code groupBy} on {@code /lines} accepts the query param but is
 *       ignored — Sprint 1 always returns raw lines. Aggregation lands in
 *       Sprint 2 alongside the variance heat-map.</li>
 *   <li>No paging on {@code /lines}; a 91-day horizon at demo scale is
 *       hundreds of rows. Add pagination in Sprint 2 when a real corporate's
 *       AR/AP volume hits the page.</li>
 *   <li>RBAC (e.g. {@code forecast:read} / {@code forecast:run}) is not
 *       enforced — matches the rest of the module's posture in dev. Production
 *       must add this before exposing the endpoints outside the VPN.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/forecasts")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "Forecasts", description = "Cash forecasting module — runs, lines, summary")
public class ForecastController {

    private final ForecastScheduler scheduler;
    private final ForecastRunRepository runRepository;
    private final ForecastLineRepository lineRepository;

    /** Default 13-week horizon when the caller doesn't supply one. */
    private static final int DEFAULT_HORIZON_DAYS = 91;

    // ------------------------------------------------------------------------
    // GET /latest — headline summary + weekly buckets
    // ------------------------------------------------------------------------

    @GetMapping("/latest")
    @Transactional(readOnly = true)
    @Operation(
        summary = "Latest forecast summary",
        description = "Most-recent COMPLETED forecast for the corporate, aggregated into weekly buckets " +
                      "with a running closing balance. Used by the Forecasting page headline strip + chart."
    )
    public ResponseEntity<ApiResponse<ForecastSummaryDto>> getLatest(
            @Parameter(description = "Corporate scope — auto-injected by the frontend axios layer.")
            @RequestHeader("X-Corporate-Id") UUID corporateId,
            @Parameter(description = "Filter lines to a single ISO 4217 currency. If omitted, all currencies are summed (engines emit in source currency so this is meaningful only with one currency in scope).")
            @RequestParam(required = false) String currency,
            @Parameter(description = "Forecast horizon in days from today. Defaults to 91 (≈13 weeks).")
            @RequestParam(required = false, defaultValue = "" + DEFAULT_HORIZON_DAYS) int horizonDays
    ) {
        ForecastRun run = runRepository
                .findFirstByCorporateIdAndStatusOrderByRunAtDesc(corporateId, RunStatus.COMPLETED)
                .orElseThrow(() -> new ResourceNotFoundException(
                    "No COMPLETED forecast run exists for corporate " + corporateId
                    + ". Fire POST /v1/forecasts/run first."));

        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(horizonDays);

        List<Object[]> rows = lineRepository.aggregateWeeklyByCurrencyDirection(
                run.getId(), from, to);

        ForecastSummaryDto summary = buildSummary(run, rows, currency);
        return ResponseEntity.ok(ApiResponse.success(summary));
    }

    // ------------------------------------------------------------------------
    // GET /{runId}/lines — atomic line drill-down
    // ------------------------------------------------------------------------

    @GetMapping("/{runId}/lines")
    @Transactional(readOnly = true)
    @Operation(
        summary = "Forecast lines for a run",
        description = "Atomic forecast lines for the given run, optionally clipped to a date window. " +
                      "Each line carries a `sourceRef` that traces back to the originating invoice / pattern / adjustment."
    )
    public ResponseEntity<ApiResponse<List<ForecastLineDto>>> getLines(
            @PathVariable UUID runId,
            @Parameter(description = "Inclusive lower bound on value_date. Defaults to today.")
            @RequestParam(required = false) LocalDate fromDate,
            @Parameter(description = "Inclusive upper bound on value_date. Defaults to today + 91d.")
            @RequestParam(required = false) LocalDate toDate,
            @Parameter(description = "Sprint 1: accepted but ignored — raw lines are returned regardless. " +
                                     "Aggregation by CATEGORY / ENTITY / COUNTERPARTY arrives in Sprint 2.")
            @RequestParam(required = false) String groupBy
    ) {
        // Reject unknown runIds explicitly — keeps the error message helpful.
        runRepository.findById(runId).orElseThrow(() ->
                new ResourceNotFoundException("No forecast run exists with id " + runId));

        LocalDate from = fromDate != null ? fromDate : LocalDate.now();
        LocalDate to = toDate != null ? toDate : from.plusDays(DEFAULT_HORIZON_DAYS);

        if (groupBy != null && !"NONE".equalsIgnoreCase(groupBy)) {
            log.debug("groupBy={} requested on /{}/lines but Sprint 1 only supports raw lines — ignoring",
                    groupBy, runId);
        }

        List<ForecastLine> lines = lineRepository.findByRunIdAndValueDateBetween(runId, from, to);
        List<ForecastLineDto> dtos = lines.stream().map(ForecastLineDto::from).toList();
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    // ------------------------------------------------------------------------
    // GET /{runId} — run metadata
    // ------------------------------------------------------------------------

    @GetMapping("/{runId}")
    @Operation(
        summary = "Forecast run metadata",
        description = "Header view of a single forecast run — status, run_at, horizon, generation latency."
    )
    public ResponseEntity<ApiResponse<ForecastRunDto>> getRun(@PathVariable UUID runId) {
        ForecastRun run = runRepository.findById(runId).orElseThrow(() ->
                new ResourceNotFoundException("No forecast run exists with id " + runId));
        return ResponseEntity.ok(ApiResponse.success(ForecastRunDto.from(run)));
    }

    // ------------------------------------------------------------------------
    // POST /run — fire on-demand
    // ------------------------------------------------------------------------

    @PostMapping("/run")
    @Operation(
        summary = "Trigger an on-demand forecast run",
        description = "Synchronously kicks off the orchestrator. Returns the persisted ForecastRun once " +
                      "engines complete (typically &lt; 5s on Sprint 1 seed). " +
                      "Responds 409 if a run is already in progress for the same corporate."
    )
    public ResponseEntity<ApiResponse<ForecastRunDto>> triggerRun(
            @Parameter(description = "Corporate scope — auto-injected by the frontend axios layer.")
            @RequestHeader("X-Corporate-Id") UUID corporateId
    ) {
        // Route through the T8 scheduler so the corporate-scoped mutex is
        // honoured — two simultaneous POSTs collide on the lock and the
        // second one gets 409 instead of both firing the orchestrator.
        // The scheduler uses its own DEFAULT horizon constant; callers wanting
        // a non-default horizon should go through a future API once Sprint-2
        // adds it (out of scope for Sprint 1).
        log.info("POST /v1/forecasts/run requested for corporate {}", corporateId);
        ForecastRun run = scheduler.triggerOnDemand(corporateId);
        return ResponseEntity.ok(ApiResponse.success(ForecastRunDto.from(run)));
    }

    // ------------------------------------------------------------------------
    // Internal — summary assembly
    // ------------------------------------------------------------------------

    /**
     * Roll up the native aggregate ({@code [week_start, currency, direction, sum_amount_mid]})
     * into a {@link ForecastSummaryDto}. Anchored at opening balance = 0 — see
     * the class javadoc for the Sprint 2 follow-up.
     */
    private ForecastSummaryDto buildSummary(ForecastRun run, List<Object[]> rows, String currencyFilter) {
        // Bucket by week (ISO Monday). The native query returns one row per
        // (week_start, currency, direction) so a single calendar week can
        // contribute up to 2 rows (IN + OUT) per currency.
        java.util.Map<LocalDate, BigDecimal> netByWeek = new java.util.TreeMap<>();
        String resolvedCurrency = currencyFilter;

        for (Object[] row : rows) {
            LocalDate weekStart = toLocalDate(row[0]);
            String currency = (String) row[1];
            String directionRaw = (String) row[2];
            BigDecimal sum = (BigDecimal) row[3];

            if (currencyFilter != null && !currencyFilter.equalsIgnoreCase(currency)) {
                continue;
            }
            if (resolvedCurrency == null) {
                resolvedCurrency = currency; // First seen — used for summary label only.
            }

            ForecastDirection direction = ForecastDirection.valueOf(directionRaw);
            BigDecimal signed = direction == ForecastDirection.IN ? sum : sum.negate();
            netByWeek.merge(weekStart, signed, BigDecimal::add);
        }

        BigDecimal openingBalance = BigDecimal.ZERO; // TODO Sprint 2: BalanceAggregationService.
        BigDecimal running = openingBalance;
        List<ForecastWeeklyBucketDto> buckets = new ArrayList<>(netByWeek.size());

        for (var entry : netByWeek.entrySet()) {
            LocalDate weekStart = entry.getKey().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            LocalDate weekEnd = weekStart.plusDays(6);
            BigDecimal net = entry.getValue();
            running = running.add(net);
            buckets.add(new ForecastWeeklyBucketDto(weekStart, weekEnd, net, running));
        }

        BigDecimal closingBalance = running;
        ForecastWeeklyBucketDto trough = buckets.stream()
                .min(Comparator.comparing(ForecastWeeklyBucketDto::closingBalance))
                .orElse(null);

        return new ForecastSummaryDto(
                run.getId(),
                run.getRunAt(),
                run.getHorizonEnd(),
                resolvedCurrency,
                openingBalance,
                closingBalance,
                trough,
                buckets
        );
    }

    /**
     * The native aggregate query returns {@code week_start} as the JDBC
     * driver's native temporal type — most commonly {@code java.sql.Date} or
     * {@code java.sql.Timestamp} depending on PG version + Hibernate flavour.
     * Normalise to {@link LocalDate} here so the summary layer above doesn't
     * have to care.
     */
    private static LocalDate toLocalDate(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof LocalDate ld) {
            return ld;
        }
        if (raw instanceof Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        if (raw instanceof Timestamp ts) {
            return ts.toLocalDateTime().toLocalDate();
        }
        if (raw instanceof java.time.LocalDateTime ldt) {
            return ldt.toLocalDate();
        }
        if (raw instanceof java.time.OffsetDateTime odt) {
            return odt.toLocalDate();
        }
        if (raw instanceof java.time.Instant inst) {
            // Hibernate 6 + Postgres JDBC will surface a TIMESTAMPTZ aggregate
            // (e.g. date_trunc without a ::date cast) as Instant. Anchor at UTC
            // since value_date is a zoneless DATE — the conversion can't drift.
            return inst.atZone(java.time.ZoneOffset.UTC).toLocalDate();
        }
        throw new IllegalStateException("Unexpected week_start type " + raw.getClass() + " from native query");
    }
}
