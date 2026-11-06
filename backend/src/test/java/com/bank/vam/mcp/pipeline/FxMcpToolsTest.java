package com.bank.vam.mcp.pipeline;

import com.bank.vam.ai.copilot.tools.ToolContext;
import com.bank.vam.ai.copilot.tools.ToolContext.CallerContext;
import com.bank.vam.ai.copilot.tools.ToolRegistry;
import com.bank.vam.ai.copilot.tools.ToolResult;
import com.bank.vam.ai.copilot.tools.read.GetFxDealsTool;
import com.bank.vam.ai.copilot.tools.read.GetFxExposureTool;
import com.bank.vam.ai.copilot.tools.read.GetFxRatesTool;
import com.bank.vam.ai.copilot.tools.read.GetFxRfqLogTool;
import com.bank.vam.config.FxMarketProperties;
import com.bank.vam.fx.ForwardPricer;
import com.bank.vam.fx.RateProvider;
import com.bank.vam.fx.SpotTick;
import com.bank.vam.fx.deal.DealStatus;
import com.bank.vam.fx.deal.DealViewAssembler;
import com.bank.vam.fx.deal.FxDeal;
import com.bank.vam.fx.deal.FxDealAttentionService;
import com.bank.vam.fx.deal.FxDealModels.RfqLog;
import com.bank.vam.fx.deal.FxDealModels.RfqSummary;
import com.bank.vam.fx.deal.FxDealService;
import com.bank.vam.fx.deal.FxLimitService;
import com.bank.vam.fx.deal.FxQuoteService;
import com.bank.vam.fx.deal.QuoteStatus;
import com.bank.vam.fx.exposure.ExposureBucket;
import com.bank.vam.fx.exposure.FxExposureModels.Bucket;
import com.bank.vam.fx.exposure.FxExposureModels.CurrencyRow;
import com.bank.vam.fx.exposure.FxExposureModels.HedgeStatus;
import com.bank.vam.fx.exposure.FxExposureModels.Ladder;
import com.bank.vam.fx.exposure.FxExposureService;
import com.bank.vam.mcp.McpAuditService;
import com.bank.vam.mcp.McpException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The four FX tools exposed through MCP: what each returns, that the three corporate-scoped ones refuse to run
 * without a scope and read only the scope in the context, and that the real pipeline stages keep a caller to
 * their own corporate when these tools run behind them.
 */
class FxMcpToolsTest {

    private static final UUID CORP = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final ToolContext UNSCOPED = new ToolContext(null, "mcp-unauthenticated", "GBP", "UK", null);
    private static final ToolContext SCOPED = new ToolContext(CORP, "u", "GBP", "UK", null);

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    // ---- get_fx_rates --------------------------------------------------------------------------------------

    private GetFxRatesTool ratesTool() {
        RateProvider market = new RateProvider() {
            public List<SpotTick> spots() {
                return List.of(tick("EUR/USD", "1.10000"), tick("USD/JPY", "150.000"));
            }

            public Optional<SpotTick> spot(String pair) {
                return spots().stream().filter(t -> t.pair().equals(pair)).findFirst();
            }

            private SpotTick tick(String pair, String mid) {
                return new SpotTick(pair, new BigDecimal(mid), BigDecimal.ZERO, Instant.EPOCH, "TEST");
            }
        };
        Clock thursday = Clock.fixed(Instant.parse("2026-11-05T09:00:00Z"), ZoneOffset.UTC);
        return new GetFxRatesTool(market, new ForwardPricer(new FxMarketProperties(), market, thursday), json);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResult r) {
        return r.data();
    }

    @Test
    void ratesNeedNoCorporateScopeAndCoverSpotCurveAndSingleForward() {
        GetFxRatesTool tool = ratesTool();

        ToolResult all = tool.execute(UNSCOPED, Map.of());
        assertThat(all.ok()).isTrue();
        assertThat((List<?>) data(all).get("spots")).hasSize(2);
        assertThat(data(all).get("source")).isEqualTo("SIMULATED");

        ToolResult curve = tool.execute(UNSCOPED, Map.of("pair", "eur/usd"));
        assertThat((List<?>) data(curve).get("curve")).isNotEmpty();
        assertThat(data(curve)).doesNotContainKey("forward");

        ToolResult tenor = tool.execute(UNSCOPED, Map.of("pair", "EUR/USD", "tenor", "3M"));
        assertThat(data(tenor)).containsKey("forward");
        assertThat((List<?>) data(tenor).get("curve")).isEmpty();

        ToolResult date = tool.execute(UNSCOPED, Map.of("pair", "EUR/USD", "valueDate", "2027-02-09"));
        assertThat(date.ok()).isTrue();
        assertThat(data(date)).containsKey("forward");
    }

    @Test
    void ratesRejectBadCombinationsWithAMessageInsteadOfThrowing() {
        GetFxRatesTool tool = ratesTool();

        assertThat(tool.execute(UNSCOPED, Map.of("tenor", "3M")).ok()).isFalse();
        assertThat(tool.execute(UNSCOPED, Map.of("pair", "EUR/USD", "tenor", "3M", "valueDate", "2027-02-09")).ok()).isFalse();
        assertThat(tool.execute(UNSCOPED, Map.of("pair", "EURUSD")).errorMessage()).contains("EUR/USD");
        assertThat(tool.execute(UNSCOPED, Map.of("pair", "ABC/XYZ")).errorMessage()).contains("No market");
        assertThat(tool.execute(UNSCOPED, Map.of("pair", "EUR/USD", "valueDate", "tomorrow")).errorMessage()).contains("YYYY-MM-DD");
    }

    // ---- scoped tools --------------------------------------------------------------------------------------

    private static Ladder ladder() {
        BigDecimal ten = new BigDecimal("1000000");
        Bucket breached = new Bucket(ExposureBucket.D0_30, "0-30 days", ten, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                ten, BigDecimal.ZERO, ten, BigDecimal.ZERO, new BigDecimal("0.75"), BigDecimal.ONE,
                HedgeStatus.UNDER_HEDGED, new BigDecimal("-750000"));
        Bucket fine = new Bucket(ExposureBucket.D31_60, "31-60 days", ten, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                ten, new BigDecimal("-600000"), new BigDecimal("400000"), new BigDecimal("0.60"), new BigDecimal("0.50"),
                new BigDecimal("0.80"), HedgeStatus.WITHIN_POLICY, null);
        Bucket empty = new Bucket(ExposureBucket.D61_90, "61-90 days", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null, null,
                HedgeStatus.WITHIN_POLICY, null);
        return new Ladder(CORP, "Acme Ltd", "GBP", LocalDate.of(2026, 11, 5),
                List.of(new CurrencyRow("EUR", new BigDecimal("0.86"), List.of(breached, fine, empty))), null, false);
    }

    @Test
    void exposureReadsOnlyTheContextCorporateAndFiltersToBreaches() {
        FxExposureService exposure = mock(FxExposureService.class);
        when(exposure.ladder(CORP)).thenReturn(ladder());
        when(exposure.breaches(CORP)).thenReturn(List.of());
        GetFxExposureTool tool = new GetFxExposureTool(exposure, json);

        // Even if a caller supplies another corporate's id, only the context's scope is read.
        ToolResult everything = tool.execute(SCOPED, Map.of("corporateId", OTHER.toString()));
        ToolResult breachesOnly = tool.execute(SCOPED, Map.of("breachesOnly", true));
        ToolResult noSuchCurrency = tool.execute(SCOPED, Map.of("currency", "JPY"));

        verify(exposure, org.mockito.Mockito.never()).ladder(OTHER);
        assertThat(buckets(everything)).hasSize(2); // the all-zero bucket says nothing and is dropped
        assertThat(buckets(breachesOnly)).hasSize(1);
        assertThat(buckets(breachesOnly).get(0)).containsEntry("status", "UNDER_HEDGED");
        assertThat((List<?>) data(noSuchCurrency).get("currencies")).isEmpty();
        assertThat(data(everything)).containsEntry("corporateName", "Acme Ltd").containsEntry("baseCurrency", "GBP");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> buckets(ToolResult r) {
        List<Map<String, Object>> currencies = (List<Map<String, Object>>) data(r).get("currencies");
        return (List<Map<String, Object>>) currencies.get(0).get("buckets");
    }

    private static FxDeal deal(DealStatus status) {
        FxDeal d = new FxDeal();
        d.setId(UUID.randomUUID());
        d.setStatus(status);
        return d;
    }

    private GetFxDealsTool dealsTool(FxDealService deals, FxLimitService limits) {
        FxDealAttentionService attention = mock(FxDealAttentionService.class);
        when(attention.items(any())).thenReturn(List.of());
        when(limits.line(any(UUID.class))).thenAnswer(i -> new FxLimitService.Line(i.getArgument(0), UUID.randomUUID(),
                UUID.randomUUID(), "FX treasury line", "GBP", null, true, null,
                new FxLimitService.Usage(new BigDecimal("20000000"), new BigDecimal("1500000"),
                        new BigDecimal("18500000"))));
        return new GetFxDealsTool(deals, mock(DealViewAssembler.class), limits, attention, json);
    }

    @Test
    void dealsDefaultToOpenOnesAndReportTheCreditLimit() {
        FxDealService deals = mock(FxDealService.class);
        when(deals.list(CORP)).thenReturn(List.of(deal(DealStatus.ACTIVE), deal(DealStatus.SETTLED), deal(DealStatus.CANCELLED)));
        GetFxDealsTool tool = dealsTool(deals, mock(FxLimitService.class));

        ToolResult open = tool.execute(SCOPED, Map.of());
        ToolResult all = tool.execute(SCOPED, Map.of("status", "all"));
        ToolResult settled = tool.execute(SCOPED, Map.of("status", "settled"));
        ToolResult capped = tool.execute(SCOPED, Map.of("status", "all", "limit", 2));
        ToolResult bad = tool.execute(SCOPED, Map.of("status", "pending"));

        assertThat(data(open)).containsEntry("total", 1);
        assertThat(data(all)).containsEntry("total", 3);
        assertThat(data(settled)).containsEntry("total", 1);
        assertThat(data(capped)).containsEntry("total", 3).containsEntry("shown", 2);
        assertThat(bad.ok()).isFalse();
        assertThat(bad.errorMessage()).contains("Unknown status");
        @SuppressWarnings("unchecked")
        Map<String, Object> limit = (Map<String, Object>) data(open).get("limit");
        assertThat(limit).containsEntry("currency", "GBP");
        assertThat((BigDecimal) limit.get("headroom")).isEqualByComparingTo("18500000");
    }

    @Test
    void rfqLogPassesTheFiltersThroughAndRejectsAnUnknownStatus() {
        FxQuoteService quotes = mock(FxQuoteService.class);
        when(quotes.rfqLog(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new RfqLog(new RfqSummary(0, 0, 0, 0, 0, 0, 0, null, null), List.of()));
        GetFxRfqLogTool tool = new GetFxRfqLogTool(quotes, json);

        ToolResult ok = tool.execute(SCOPED, Map.of("status", "rejected", "pair", "eur/usd", "days", 7, "limit", 9999));
        ToolResult bad = tool.execute(SCOPED, Map.of("status", "nope"));

        assertThat(ok.ok()).isTrue();
        verify(quotes).rfqLog(CORP, QuoteStatus.REJECTED, "EUR/USD", 7, 200);
        assertThat(bad.ok()).isFalse();
        assertThat(bad.errorMessage()).contains("Unknown status");
    }

    @Test
    void theScopedToolsRefuseWithoutACorporateScopeAndTouchNothing() {
        FxExposureService exposure = mock(FxExposureService.class);
        FxDealService deals = mock(FxDealService.class);
        FxLimitService limits = mock(FxLimitService.class);
        FxQuoteService quotes = mock(FxQuoteService.class);

        List<ToolResult> results = List.of(
                new GetFxExposureTool(exposure, json).execute(UNSCOPED, Map.of()),
                dealsTool(deals, limits).execute(UNSCOPED, Map.of()),
                new GetFxRfqLogTool(quotes, json).execute(UNSCOPED, Map.of()));

        assertThat(results).allSatisfy(r -> {
            assertThat(r.ok()).isFalse();
            assertThat(r.errorMessage()).contains("requires a corporate scope");
        });
        verifyNoInteractions(exposure, deals, limits, quotes);
    }

    // ---- behind the real MCP pipeline ----------------------------------------------------------------------

    @Test
    void behindThePipelineACallerIsKeptToTheirOwnCorporate() {
        FxExposureService exposure = mock(FxExposureService.class);
        when(exposure.ladder(CORP)).thenReturn(ladder());
        when(exposure.breaches(CORP)).thenReturn(List.of());
        GetFxExposureTool tool = new GetFxExposureTool(exposure, json);
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.find("get_fx_exposure")).thenReturn(Optional.of(tool));
        McpToolPipeline pipeline = new McpToolPipeline(registry, new McpArgumentStripper(), new McpSchemaValidator(),
                new McpEntitlementGuard(), new McpRateLimiter(null, null) {
                    @Override
                    public void acquire(String toolName) {
                    }
                }, new McpMaskingPolicy(), new McpResponseEnveloper(json), mock(McpAuditService.class));
        CallerContext caller = new CallerContext("alice", Set.of(CORP), "VIEW", List.of(), "jti");

        // A scope-shaped argument for someone else's corporate is dropped; the signed scope is what is read.
        Map<String, Object> envelope = pipeline.call("get_fx_exposure", Map.of("corporateId", OTHER.toString()),
                new ToolContext(CORP, "alice", "GBP", "UK", null, caller));

        assertThat(envelope.get("isError")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) envelope.get("structuredContent");
        assertThat(structured).containsKey("currencies");
        verify(exposure, org.mockito.Mockito.never()).ladder(OTHER);

        // A context scoped to a corporate outside the caller's entitlement never reaches the tool.
        assertThatThrownBy(() -> pipeline.call("get_fx_exposure", Map.of(),
                new ToolContext(OTHER, "alice", "GBP", "UK", null, caller))).isInstanceOf(McpException.class);
        verify(exposure, org.mockito.Mockito.never()).ladder(OTHER);
    }
}
