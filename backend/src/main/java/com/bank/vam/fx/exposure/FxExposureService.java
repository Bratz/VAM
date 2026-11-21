package com.bank.vam.fx.exposure;

import com.bank.vam.entity.Corporate;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.fx.HomeCurrency;
import com.bank.vam.fx.deal.DealStatus;
import com.bank.vam.fx.deal.FxDeal;
import com.bank.vam.fx.deal.FxDealRepository;
import com.bank.vam.fx.exposure.FxExposureModels.Breach;
import com.bank.vam.fx.exposure.FxExposureModels.Bucket;
import com.bank.vam.fx.exposure.FxExposureModels.BucketItems;
import com.bank.vam.fx.exposure.FxExposureModels.CurrencyRow;
import com.bank.vam.fx.exposure.FxExposureModels.EntryView;
import com.bank.vam.fx.exposure.FxExposureModels.HedgeStatus;
import com.bank.vam.fx.exposure.FxExposureModels.Item;
import com.bank.vam.fx.exposure.FxExposureModels.ItemKind;
import com.bank.vam.fx.exposure.FxExposureModels.Ladder;
import com.bank.vam.fx.exposure.FxExposureModels.UploadResult;
import com.bank.vam.repository.CorporateRepository;
import com.bank.vam.service.treasury.FxRateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The corporate's foreign-currency exposure ladder and where it breaks hedge policy. Hedges are the
 * ones the corporate declared by upload plus its open deals with the bank.
 *
 * <p>ponytail: base-equivalents use the stored platform rates, not the simulated market.
 */
@Slf4j
@Service
public class FxExposureService {

    private final CorporateRepository corporates;
    private final HomeCurrency homeCurrency;
    private final ExposureSources sources;
    private final FxExposureEntryRepository entries;
    private final HedgePolicyService policy;
    private final FxRateService rates;
    private final FxDealRepository deals;
    private final BigDecimal materialityBase;
    private final int forecastStaleDays;

    public FxExposureService(CorporateRepository corporates, HomeCurrency homeCurrency, ExposureSources sources,
                             FxExposureEntryRepository entries, HedgePolicyService policy, FxRateService rates,
                             FxDealRepository deals,
                             @Value("${vam.fx.exposure.materiality-base:25000}") BigDecimal materialityBase,
                             @Value("${vam.fx.exposure.forecast-stale-days:7}") int forecastStaleDays) {
        this.corporates = corporates;
        this.homeCurrency = homeCurrency;
        this.sources = sources;
        this.entries = entries;
        this.policy = policy;
        this.rates = rates;
        this.deals = deals;
        this.materialityBase = materialityBase;
        this.forecastStaleDays = forecastStaleDays;
    }

    public Ladder ladder(UUID corporateId) {
        Corporate corporate = corporates.findById(corporateId)
                .orElseThrow(() -> new ResourceNotFoundException("Corporate not found: " + corporateId));
        LocalDate today = LocalDate.now();
        Inputs in = inputs(corporateId, today);
        String base = in.base();
        List<FxPolicyBand> bands = policy.bandsFor(corporateId);
        List<CurrencyRow> rows = ExposureCalculator.build(base, today, in.payables(), in.receivables(),
                in.forecastFlows(), ExposureSources.balances(in.accounts()), in.entries(),
                bands, ccy -> rateToBase(ccy, base), materialityBase);
        java.time.OffsetDateTime runAt = in.forecast().map(ExposureSources.ForecastFlows::runAt).orElse(null);
        boolean stale = runAt != null && runAt.toLocalDate().isBefore(today.minusDays(forecastStaleDays));
        return new Ladder(corporateId, corporate.getLegalName(), base, today, rows, runAt, stale,
                bands.stream().anyMatch(FxPolicyBand::isSystemDefault));
    }

    /** Policy breaches for one corporate, or for all of them when {@code corporateId} is null. */
    public List<Breach> breaches(UUID corporateId) {
        List<Corporate> scope = corporateId == null ? corporates.findAll()
                : List.of(corporates.findById(corporateId)
                        .orElseThrow(() -> new ResourceNotFoundException("Corporate not found: " + corporateId)));
        List<Breach> out = new ArrayList<>();
        for (Corporate c : scope) {
            try {
                Ladder ladder = ladder(c.getId());
                for (CurrencyRow row : ladder.currencies()) {
                    for (Bucket b : row.buckets()) {
                        if (b.status() == HedgeStatus.UNDER_HEDGED || b.status() == HedgeStatus.OVER_HEDGED) {
                            out.add(breach(ladder, row, b));
                        }
                    }
                }
            } catch (RuntimeException e) {
                // One corporate's bad data must not blank the whole attention feed.
                log.warn("FX breach scan skipped corporate {}: {}", c.getId(), e.toString());
            }
        }
        return out;
    }

    /** What the ladder is built from, read once so the ladder and a bucket's drill-down see the same items. */
    private record Inputs(String base, List<ExposureCalculator.Flow> payables, List<ExposureCalculator.Flow> receivables,
                          java.util.Optional<ExposureSources.ForecastFlows> forecast, List<ExposureCalculator.Flow> accounts,
                          List<FxExposureEntry> entries) {
        List<ExposureCalculator.Flow> forecastFlows() {
            return forecast.map(ExposureSources.ForecastFlows::flows).orElse(List.of());
        }
    }

    private Inputs inputs(UUID corporateId, LocalDate today) {
        String base = homeCurrency.of(corporateId);
        return new Inputs(base, sources.payables(corporateId, base), sources.receivables(corporateId, base),
                sources.forecast(corporateId, base, today), sources.accounts(corporateId, base), declaredAndDealt(corporateId));
    }

    /**
     * The invoices, forecast lines, cash, entries and hedges behind one currency in one bucket of the ladder; their
     * exposure items add up to the bucket's exposure and the hedges to its hedged amount.
     */
    public BucketItems items(UUID corporateId, String currency, ExposureBucket bucket) {
        if (!corporates.existsById(corporateId)) {
            throw new ResourceNotFoundException("Corporate not found: " + corporateId);
        }
        LocalDate today = LocalDate.now();
        Inputs in = inputs(corporateId, today);
        String ccy = currency.trim().toUpperCase();
        List<Item> items = ExposureCalculator.items(in.base(), today, ccy, bucket, in.payables(), in.receivables(),
                in.forecastFlows(), in.accounts(), in.entries());
        BigDecimal exposure = items.stream().filter(i -> i.kind() != ItemKind.HEDGE).map(Item::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal hedged = items.stream().filter(i -> i.kind() == ItemKind.HEDGE).map(Item::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new BucketItems(ccy, bucket, bucket.label(), exposure, hedged, items);
    }

    /** Hedges the corporate declared by upload, plus the open deals it has done with the bank. */
    private List<FxExposureEntry> declaredAndDealt(UUID corporateId) {
        List<FxExposureEntry> all = new ArrayList<>(entries.findByCorporateIdOrderByValueDateAsc(corporateId));
        for (FxDeal d : deals.findByCorporateIdAndStatusIn(corporateId, DealStatus.OPEN)) {
            all.addAll(d.hedgePositions());
        }
        return all;
    }

    public List<EntryView> entriesFor(UUID corporateId) {
        return entries.findByCorporateIdOrderByValueDateAsc(corporateId).stream().map(EntryView::of).toList();
    }

    /** Replaces the corporate's earlier uploads when {@code replace} is true; otherwise appends. */
    @Transactional
    public UploadResult importEntries(UUID corporateId, List<FxExposureEntry> parsed, boolean replace) {
        if (!corporates.existsById(corporateId)) {
            throw new ResourceNotFoundException("Corporate not found: " + corporateId);
        }
        int replaced = 0;
        if (replace) {
            replaced = (int) entries.findByCorporateIdOrderByValueDateAsc(corporateId).stream()
                    .filter(e -> FxExposureEntry.SOURCE_UPLOAD.equals(e.getSource())).count();
            entries.deleteByCorporateIdAndSource(corporateId, FxExposureEntry.SOURCE_UPLOAD);
        }
        entries.saveAll(parsed);
        return new UploadResult(parsed.size(), replaced);
    }

    private static Breach breach(Ladder ladder, CurrencyRow row, Bucket b) {
        boolean urgentUnder = b.status() == HedgeStatus.UNDER_HEDGED && b.bucket() == ExposureBucket.D0_30;
        return new Breach(ladder.corporateId(), ladder.corporateName(), ladder.baseCurrency(), row.currency(),
                b.bucket(), b.label(), b.bucket().horizonDays(), b.exposure(), b.hedged(), b.hedgeRatio(),
                b.minRatio(), b.maxRatio(), b.status(), b.adjustToBand(), row.rateToBase(),
                urgentUnder ? "high" : "medium");
    }

    private BigDecimal rateToBase(String currency, String base) {
        try {
            return rates.getRate(currency, base);
        } catch (BusinessException e) {
            return null;
        }
    }
}
