package com.bank.vam.service.treasury;

import com.bank.vam.config.HomeBankProperties;
import com.bank.vam.config.MultiBankProperties;
import com.bank.vam.dto.treasury.MultiBankLiquidityDto.*;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.AccountCategory;
import com.bank.vam.entity.VirtualAccount.BalanceRefreshStatus;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.treasury.ShadowBalanceSnapshot;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.treasury.ShadowBalanceSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Read-model assembler for the Multi-Bank Liquidity dashboard.
 *
 * Aggregates PHYSICAL_MIRROR shadow VAs by (bank BIC, currency) and tags
 * home-bank-held buckets distinctly from external ones. Computes staleness
 * relative to the configured freshness threshold.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiBankLiquidityViewService {

    private final VirtualAccountRepository vaRepository;
    private final HomeBankProperties homeBankProperties;
    private final MultiBankProperties multiBankProperties;
    private final LegalEntityRepository legalEntityRepository;
    private final ShadowBalanceSnapshotRepository snapshotRepository;

    @Transactional(readOnly = true)
    public LiquiditySummary getSummary(UUID corporateId) {
        List<VirtualAccount> shadows = corporateId != null
                ? vaRepository.findByCorporateIdAndAccountCategory(corporateId, AccountCategory.PHYSICAL_MIRROR)
                : vaRepository.findByAccountCategory(AccountCategory.PHYSICAL_MIRROR);

        // Bulk-resolve owning entities once (not per-shadow) — the real FK is
        // owningEntityId; owningEntityCode below is a denormalized display
        // label only. Shadows with no owning entity (nullable, no DB
        // constraint) fall back to "Unassigned" in toShadowSummary().
        Set<UUID> entityIds = shadows.stream()
                .map(VirtualAccount::getOwningEntityId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, LegalEntity> entitiesById = entityIds.isEmpty()
                ? Collections.emptyMap()
                : legalEntityRepository.findAllById(entityIds).stream()
                        .collect(Collectors.toMap(LegalEntity::getId, e -> e));

        String homeBic = homeBankProperties.getBic();
        // Home-bank display name resolved from the actual account data by BIC
        // (scope-independent) — never a static config string, so a BIC-only
        // per-geography override (e.g. VAM_HOME_BANK_BIC=LOYDGB2LXXX) can't
        // render a stale wrong name. Falls back to the BIC itself when no
        // account carries that bank.
        String homeBankDisplayName = homeBic == null ? null
                : vaRepository.findBankNameByBic(homeBic).orElse(homeBic);
        int homeShadowCount = 0;
        int externalShadowCount = 0;
        int staleCount = 0;
        int neverCount = 0;

        // Group: bankBic → currency → list of shadows
        Map<String, Map<String, List<ShadowSummary>>> grouped = new LinkedHashMap<>();

        for (VirtualAccount s : shadows) {
            ShadowSummary summary = toShadowSummary(s, entitiesById);
            if (summary.isHomeBankHeld()) homeShadowCount++;
            else externalShadowCount++;
            if (summary.isStale()) staleCount++;
            if (s.getLastBalanceRefreshStatus() == null
                    || s.getLastBalanceRefreshStatus() == BalanceRefreshStatus.NEVER) {
                neverCount++;
            }

            String bic = summary.getBankBic() != null ? summary.getBankBic() : "UNKNOWN";
            grouped.computeIfAbsent(bic, k -> new LinkedHashMap<>())
                   .computeIfAbsent(summary.getCurrencyCode() != null ? summary.getCurrencyCode() : "UNKNOWN",
                                    k -> new ArrayList<>())
                   .add(summary);
        }

        List<BankBucket> banks = grouped.entrySet().stream()
                .map(bankEntry -> {
                    String bic = bankEntry.getKey();
                    boolean isHome = homeBic != null && homeBic.equalsIgnoreCase(bic);
                    List<CurrencyBucket> currencyBuckets = bankEntry.getValue().entrySet().stream()
                            .map(cEntry -> buildCurrencyBucket(cEntry.getKey(), cEntry.getValue()))
                            .sorted(Comparator.comparing(CurrencyBucket::getCurrencyCode))
                            .collect(Collectors.toList());
                    int shadowCount = currencyBuckets.stream().mapToInt(CurrencyBucket::getShadowCount).sum();
                    String bankName = bankEntry.getValue().values().stream()
                            .flatMap(List::stream)
                            .map(ShadowSummary::getBankName)
                            .filter(Objects::nonNull)
                            .findFirst()
                            .orElse(isHome ? homeBankDisplayName : null);
                    return BankBucket.builder()
                            .bankBic(bic)
                            .bankName(bankName)
                            .homeBank(isHome)
                            .shadowCount(shadowCount)
                            .currencies(currencyBuckets)
                            .build();
                })
                .sorted(Comparator
                        .comparing(BankBucket::isHomeBank).reversed()  // home bank first
                        .thenComparing(BankBucket::getBankBic))
                .collect(Collectors.toList());

        return LiquiditySummary.builder()
                .homeBankBic(homeBic)
                .homeBankName(homeBankDisplayName)
                .totalShadows(shadows.size())
                .homeBankShadows(homeShadowCount)
                .externalShadows(externalShadowCount)
                .staleCount(staleCount)
                .neverRefreshedCount(neverCount)
                .banks(banks)
                .build();
    }

    private CurrencyBucket buildCurrencyBucket(String currency, List<ShadowSummary> shadows) {
        BigDecimal totalBank = BigDecimal.ZERO;
        BigDecimal totalCommitted = BigDecimal.ZERO;
        BigDecimal totalEffective = BigDecimal.ZERO;
        for (ShadowSummary s : shadows) {
            totalBank = totalBank.add(nz(s.getBankBalance()));
            totalCommitted = totalCommitted.add(nz(s.getBankBalanceCommitted()));
            totalEffective = totalEffective.add(nz(s.getBankBalanceEffective()));
        }
        return CurrencyBucket.builder()
                .currencyCode(currency)
                .shadowCount(shadows.size())
                .totalBankBalance(totalBank)
                .totalCommitted(totalCommitted)
                .totalEffective(totalEffective)
                .shadows(shadows)
                .build();
    }

    private ShadowSummary toShadowSummary(VirtualAccount s, Map<UUID, LegalEntity> entitiesById) {
        String homeBic = homeBankProperties.getBic();
        boolean isHome = s.isHomeBankHeld(homeBic);
        LegalEntity entity = s.getOwningEntityId() != null ? entitiesById.get(s.getOwningEntityId()) : null;
        return ShadowSummary.builder()
                .vaId(s.getId() != null ? s.getId().toString() : null)
                .vaNumber(s.getVaNumber())
                .vaName(s.getVaName())
                .currencyCode(s.getCurrencyCode())
                .bankBic(s.getBankSwift())
                .bankName(s.getBankName())
                .bankAccountNumber(s.getBankAccountNumber())
                .bankIban(s.getBankIban())
                .homeBankHeld(isHome)
                .owningEntityCode(s.getOwningEntityCode())
                .owningEntityId(s.getOwningEntityId() != null ? s.getOwningEntityId().toString() : null)
                .owningEntityName(entity != null ? entity.getEntityName() : "Unassigned")
                .owningEntityCountry(entity != null ? entity.getCountryCode() : null)
                .owningEntityJurisdiction(entity != null ? entity.getJurisdiction() : null)
                .bankBalance(s.getBankBalance())
                .bankAvailableBalance(s.getBankAvailableBalance())
                .bankBalanceCommitted(s.getBankBalanceCommitted())
                .bankBalanceEffective(s.getBankBalanceEffective())
                .balanceDataSource(s.getBalanceDataSource() != null ? s.getBalanceDataSource().name() : null)
                .lastBalanceRefreshAt(s.getLastBalanceRefreshAt())
                .lastBalanceRefreshStatus(s.getLastBalanceRefreshStatus() != null
                        ? s.getLastBalanceRefreshStatus().name() : BalanceRefreshStatus.NEVER.name())
                .stale(isStale(s))
                .build();
    }

    /**
     * Daily bank-balance trend per currency, from {@link ShadowBalanceSnapshot}
     * rows captured by {@code BalanceRefreshService} on every successful
     * refresh (plus the demo history seeded by V18). Per-currency, not
     * FX-converted — same honesty rule as the rest of this view.
     */
    @Transactional(readOnly = true)
    public List<TrendPoint> getTrend(UUID corporateId, int days) {
        LocalDate from = LocalDate.now().minusDays(Math.max(days, 1) - 1L);
        return snapshotRepository.findTrend(corporateId, from).stream()
                .map(r -> TrendPoint.builder()
                        .asOf(r.getAsOf())
                        .currencyCode(r.getCurrencyCode())
                        .totalBankBalance(nz(r.getTotalBankBalance()))
                        .totalEffective(nz(r.getTotalEffective()))
                        .build())
                .collect(Collectors.toList());
    }

    private boolean isStale(VirtualAccount s) {
        if (s.getLastBalanceRefreshAt() == null) return true;
        int threshold = s.getFreshnessThresholdMinutes() != null
                ? s.getFreshnessThresholdMinutes()
                : multiBankProperties.getDefaultFreshnessThresholdMinutes();
        return Duration.between(s.getLastBalanceRefreshAt(), LocalDateTime.now()).toMinutes() >= threshold;
    }

    private BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
