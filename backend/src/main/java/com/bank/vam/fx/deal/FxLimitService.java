package com.bank.vam.fx.deal;

import com.bank.vam.config.FxDealProperties;
import com.bank.vam.config.FxDealProperties.PfeBand;
import com.bank.vam.config.MarketProfileProperties;
import com.bank.vam.entity.credit.CreditAgreement;
import com.bank.vam.entity.credit.CreditFacility;
import com.bank.vam.entity.credit.CreditFacility.FacilityStatus;
import com.bank.vam.entity.credit.CreditFacility.FacilityType;
import com.bank.vam.entity.credit.CreditLimit;
import com.bank.vam.entity.credit.CreditLimit.LimitStatus;
import com.bank.vam.entity.credit.CreditLimit.LimitType;
import com.bank.vam.entity.credit.CreditLimit.TargetType;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.fx.HomeCurrency;
import com.bank.vam.repository.credit.CreditAgreementRepository;
import com.bank.vam.repository.credit.CreditFacilityRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.service.treasury.FxRateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The corporate's FX line, inside the app's credit model. The bank sanctions an FX_TREASURY_LINE facility under its
 * credit agreement with the corporate; that facility's external limit (target type FX_LINE) is the group FX line, and
 * the corporate's treasury splits it into internal sub-limits per legal entity (FX_ENTITY, parent the line). A deal is
 * booked for one entity and must fit both its entity's sub-limit and the group line. Over either, the desk declines.
 *
 * <p>A deal uses the line by its charge: spot at full notional (it settles in two days), a forward or window at
 * notional times a factor that grows with days to maturity (a stand-in for potential future exposure). Usage is always
 * derived from the open deals, the source of truth, and written back to the limits' utilised amounts and the
 * facility's outstanding on every change ({@link #refreshUsage}), so the Credit Limits and Credit Facilities pages show
 * the same figure as FX Deals.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FxLimitService {

    /** Usage of one limit, in the line's currency. */
    public record Usage(BigDecimal limit, BigDecimal used, BigDecimal headroom) {
    }

    /**
     * The group line. {@code usable} is false when there is no FX facility, or it or its limit is not active or
     * not in date; {@code reason} then says which, in words for the corporate.
     */
    public record Line(UUID corporateId, UUID limitId, UUID facilityId, String facilityName, String currency,
                       LocalDate expiry, boolean usable, String reason, Usage usage) {
    }

    /** One entity's FX sub-limit. Not usable when none is allocated, or it is not active or not in date. */
    public record Allocation(UUID entityId, String entityName, UUID limitId, String currency, boolean usable,
                             String reason, Usage usage) {
    }

    private static final String SOURCE = "FX";

    private final FxCreditLineRepository lines;
    private final CreditFacilityRepository facilities;
    private final CreditAgreementRepository agreements;
    private final LegalEntityRepository entities;
    private final FxDealRepository deals;
    private final HomeCurrency homeCurrency;
    private final FxRateService rates;
    private final FxDealProperties props;
    private final MarketProfileProperties marketProfile;

    // ---- reading --------------------------------------------------------------------------------------------

    public Line line(UUID corporateId) {
        Optional<CreditLimit> found = lines.findLines(corporateId).stream().findFirst();
        if (found.isEmpty()) {
            return new Line(corporateId, null, null, null, homeCurrency.of(corporateId), null, false,
                    "No FX line: the bank has not set up an FX facility for this corporate", new Usage(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        }
        CreditLimit l = found.get();
        CreditFacility f = l.getCreditFacilityId() == null ? null : facilities.findById(l.getCreditFacilityId()).orElse(null);
        BigDecimal used = deals.sumLimitCharge(corporateId, l.getLimitCurrency(), DealStatus.OPEN);
        LocalDate expiry = f != null && f.getExpiryDate() != null ? f.getExpiryDate() : l.getEffectiveTo();
        return new Line(corporateId, l.getId(), f == null ? null : f.getId(), f == null ? l.getLimitName() : f.getFacilityName(),
                l.getLimitCurrency(), expiry, lineProblem(f, l) == null, lineProblem(f, l), usage(l.getLimitAmount(), used));
    }

    /** Every active entity of the corporate, with its FX sub-limit or the fact that it has none. */
    public List<Allocation> allocations(UUID corporateId) {
        Line line = line(corporateId);
        Map<UUID, CreditLimit> byEntity = line.limitId() == null ? Map.of()
                : lines.findAllocations(line.limitId()).stream().collect(Collectors.toMap(CreditLimit::getTargetId, l -> l, (a, b) -> a));
        return entities.findByCorporateIdAndStatus(corporateId, LegalEntity.EntityStatus.ACTIVE).stream()
                .sorted(Comparator.comparing(LegalEntity::getEntityName))
                .map(e -> allocation(corporateId, line, e, byEntity.get(e.getId())))
                .toList();
    }

    public Allocation allocation(UUID corporateId, UUID entityId) {
        LegalEntity e = entityOf(corporateId, entityId);
        Line line = line(corporateId);
        CreditLimit a = line.limitId() == null ? null : lines.findAllocation(line.limitId(), entityId).orElse(null);
        return allocation(corporateId, line, e, a);
    }

    private Allocation allocation(UUID corporateId, Line line, LegalEntity e, CreditLimit a) {
        if (a == null) {
            return new Allocation(e.getId(), e.getEntityName(), null, line.currency(), false,
                    "No FX sub-limit is allocated to " + e.getEntityName() + "; your treasury allocates one from the FX line",
                    new Usage(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        }
        BigDecimal used = deals.sumLimitChargeForEntity(corporateId, e.getId(), a.getLimitCurrency(), DealStatus.OPEN);
        String problem = limitProblem(a, "The FX sub-limit of " + e.getEntityName());
        return new Allocation(e.getId(), e.getEntityName(), a.getId(), a.getLimitCurrency(), problem == null, problem,
                usage(a.getLimitAmount(), used));
    }

    /** A line or sub-limit at or past its warning threshold; {@code critical} past its critical one. */
    public record Alert(UUID corporateId, UUID limitId, String what, boolean critical, BigDecimal percentUsed,
                        String currency, Usage usage) {
    }

    /**
     * The corporate's FX line and entity sub-limits that are at or past their warning threshold (80% unless the bank
     * set another), critical past the critical one (95%): the same thresholds the other credit limits use.
     */
    public List<Alert> alerts(UUID corporateId) {
        Optional<CreditLimit> found = lines.findLines(corporateId).stream().findFirst();
        if (found.isEmpty() || found.get().getStatus() != LimitStatus.ACTIVE) {
            return List.of();
        }
        CreditLimit line = found.get();
        java.util.ArrayList<Alert> out = new java.util.ArrayList<>();
        BigDecimal used = deals.sumLimitCharge(corporateId, line.getLimitCurrency(), DealStatus.OPEN);
        alert(corporateId, line, "The FX line", used).ifPresent(out::add);
        Map<UUID, String> names = entities.findByCorporateIdAndStatus(corporateId, LegalEntity.EntityStatus.ACTIVE).stream()
                .collect(Collectors.toMap(LegalEntity::getId, LegalEntity::getEntityName));
        for (CreditLimit a : lines.findAllocations(line.getId())) {
            if (a.getStatus() == LimitStatus.ACTIVE) {
                BigDecimal entityUsed = deals.sumLimitChargeForEntity(corporateId, a.getTargetId(), a.getLimitCurrency(), DealStatus.OPEN);
                alert(corporateId, a, "The FX sub-limit of " + names.getOrDefault(a.getTargetId(), "an entity"), entityUsed).ifPresent(out::add);
            }
        }
        return out;
    }

    private static Optional<Alert> alert(UUID corporateId, CreditLimit l, String what, BigDecimal used) {
        if (l.getLimitAmount() == null || l.getLimitAmount().signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal pct = used.multiply(BigDecimal.valueOf(100)).divide(l.getLimitAmount(), 1, RoundingMode.HALF_UP);
        BigDecimal warn = l.getWarningThresholdPercent() == null ? new BigDecimal("80") : l.getWarningThresholdPercent();
        BigDecimal crit = l.getCriticalThresholdPercent() == null ? new BigDecimal("95") : l.getCriticalThresholdPercent();
        if (pct.compareTo(warn) < 0) {
            return Optional.empty();
        }
        return Optional.of(new Alert(corporateId, l.getId(), what, pct.compareTo(crit) >= 0, pct, l.getLimitCurrency(),
                usage(l.getLimitAmount(), used)));
    }

    // ---- what a deal uses -----------------------------------------------------------------------------------

    /**
     * What a deal adds to the line's use, in the line's currency: spot at full notional, a forward or window at
     * notional times the factor for its days to maturity.
     */
    public BigDecimal charge(Instrument instrument, String currency, BigDecimal amount, LocalDate tradeDate,
                             LocalDate maturity, String limitCurrency) {
        BigDecimal notional = currency.equals(limitCurrency) ? amount : amount.multiply(rates.getRate(currency, limitCurrency));
        if (instrument == Instrument.SPOT) {
            return notional.setScale(2, RoundingMode.HALF_UP);
        }
        int days = (int) Math.max(0, ChronoUnit.DAYS.between(tradeDate, maturity));
        return notional.multiply(factor(props.getPfeBands(), days)).setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal factor(List<PfeBand> bands, int days) {
        for (PfeBand b : bands) {
            if (days <= b.getMaxDays()) {
                return b.getFactor();
            }
        }
        return bands.get(bands.size() - 1).getFactor();
    }

    /** Why a deal with this charge cannot be booked for the entity now, or null when it fits. Reads without locking. */
    public String refusal(UUID corporateId, UUID entityId, BigDecimal charge) {
        Line line = line(corporateId);
        if (!line.usable()) {
            return line.reason();
        }
        if (entityId == null) {
            return "Choose the legal entity the deal is for.";
        }
        Allocation a = allocation(corporateId, entityId);
        if (!a.usable()) {
            return a.reason();
        }
        if (charge.compareTo(a.usage().headroom()) > 0) {
            return String.format("The FX sub-limit of %s would be exceeded (needs %s %s, %s left)", a.entityName(),
                    money(charge), line.currency(), money(a.usage().headroom().max(BigDecimal.ZERO)));
        }
        if (charge.compareTo(line.usage().headroom()) > 0) {
            return String.format("The corporate's FX line would be exceeded (needs %s %s, %s left)",
                    money(charge), line.currency(), money(line.usage().headroom().max(BigDecimal.ZERO)));
        }
        return null;
    }

    /**
     * Locks the group line and the entity's sub-limit (always in that order, so two bookings cannot deadlock), then
     * throws when the charge no longer fits. Called when a price is accepted: another deal may have used the headroom
     * since the price was given.
     */
    public void requireLocked(UUID corporateId, UUID entityId, BigDecimal charge) {
        Line line = line(corporateId);
        if (line.limitId() != null) {
            lines.lockById(line.limitId());
            if (entityId != null) {
                lines.findAllocation(line.limitId(), entityId).ifPresent(a -> lines.lockById(a.getId()));
            }
        }
        String why = refusal(corporateId, entityId, charge);
        if (why != null) {
            throw new BusinessException("FX_LIMIT_EXCEEDED", "Cannot book. " + why);
        }
    }

    /**
     * Writes the usage derived from open deals to the line's and every sub-limit's utilised amount, the line's
     * allocated-to-children, and the facility's outstanding. Called whenever a deal changes status and after the
     * daily mark refreshes charges.
     */
    @Transactional
    public void refreshUsage(UUID corporateId) {
        Optional<CreditLimit> found = lines.findLines(corporateId).stream().findFirst();
        if (found.isEmpty()) {
            return;
        }
        CreditLimit line = found.get();
        BigDecimal used = deals.sumLimitCharge(corporateId, line.getLimitCurrency(), DealStatus.OPEN);
        BigDecimal allocated = BigDecimal.ZERO;
        for (CreditLimit a : lines.findAllocations(line.getId())) {
            a.setUtilizedAmount(deals.sumLimitChargeForEntity(corporateId, a.getTargetId(), a.getLimitCurrency(), DealStatus.OPEN));
            lines.save(a);
            if (a.getStatus() == LimitStatus.ACTIVE) {
                allocated = allocated.add(a.getLimitAmount());
            }
        }
        line.setUtilizedAmount(used);
        line.setAllocatedToChildren(allocated);
        lines.save(line);
        if (line.getCreditFacilityId() != null) {
            facilities.updateOutstanding(line.getCreditFacilityId(), used);
        }
    }

    // ---- allocating -----------------------------------------------------------------------------------------

    /**
     * Sets an entity's FX sub-limit. All sub-limits together may not exceed the group line, and a sub-limit may not be
     * set below what the entity's open deals already use.
     */
    @Transactional
    public Allocation allocate(UUID corporateId, UUID entityId, BigDecimal amount) {
        if (amount == null || amount.signum() < 0) {
            throw new BusinessException("FX_ALLOCATION_INVALID", "The FX sub-limit must be zero or more");
        }
        LegalEntity e = entityOf(corporateId, entityId);
        Optional<CreditLimit> found = lines.findLines(corporateId).stream().findFirst();
        if (found.isEmpty()) {
            throw new BusinessException("FX_NO_LINE", "No FX line: the bank has not set up an FX facility for this corporate");
        }
        CreditLimit line = lines.lockById(found.get().getId()).orElseThrow();
        List<CreditLimit> all = lines.findAllocations(line.getId());
        BigDecimal others = all.stream()
                .filter(a -> !a.getTargetId().equals(entityId) && a.getStatus() == LimitStatus.ACTIVE)
                .map(CreditLimit::getLimitAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (others.add(amount).compareTo(line.getLimitAmount()) > 0) {
            throw new BusinessException("FX_ALLOCATION_EXCEEDS_LINE", String.format(
                    "Sub-limits would total %s %s, more than the FX line of %s %s (%s is allocated to other entities)",
                    money(others.add(amount)), line.getLimitCurrency(), money(line.getLimitAmount()), line.getLimitCurrency(), money(others)));
        }
        BigDecimal inUse = deals.sumLimitChargeForEntity(corporateId, entityId, line.getLimitCurrency(), DealStatus.OPEN);
        if (amount.compareTo(inUse) < 0) {
            throw new BusinessException("FX_ALLOCATION_BELOW_USE", String.format(
                    "%s's open deals already use %s %s; the sub-limit cannot be set below that", e.getEntityName(),
                    money(inUse), line.getLimitCurrency()));
        }
        CreditLimit a = all.stream().filter(x -> x.getTargetId().equals(entityId)).findFirst().orElseGet(() -> CreditLimit.builder()
                .corporateId(corporateId)
                .limitType(LimitType.INTERNAL)
                .targetType(TargetType.FX_ENTITY)
                .targetId(entityId)
                .parentLimitId(line.getId())
                .limitCurrency(line.getLimitCurrency())
                .sourceSystem(SOURCE)
                .build());
        a.setLimitName("FX sub-limit - " + e.getEntityName());
        a.setLimitAmount(amount);
        a.setStatus(LimitStatus.ACTIVE);
        a.setIsHardLimit(true);
        a.setEffectiveTo(line.getEffectiveTo());
        lines.save(a);
        refreshUsage(corporateId);
        return allocation(corporateId, entityId);
    }

    // ---- seeding (demo) -------------------------------------------------------------------------------------

    /**
     * Gives a corporate with no FX line one for the demo: an FX_TREASURY_LINE facility of the default amount under
     * its agreement with the home bank (an agreement is created where there is none), the line's external limit, and
     * the whole line allocated to the corporate's treasury centre, else its holding or top entity, so it can deal at
     * once. Other entities need an allocation. In production the facility and line come from core banking.
     *
     * @return true when a line was created
     */
    @Transactional
    public boolean ensureLine(UUID corporateId) {
        if (!lines.findLines(corporateId).isEmpty()) {
            return false;
        }
        String bic = marketProfile.getActiveHomeBankBic();
        String bank = Optional.ofNullable(marketProfile.getActiveHomeBankName()).orElse("Home bank");
        String home = homeCurrency.of(corporateId);
        LocalDate today = LocalDate.now();
        BigDecimal amount = props.getDefaultLimit();

        CreditAgreement agreement = agreements.findByCorporateIdOrderByAgreementName(corporateId).stream()
                .filter(a -> bic != null && bic.equalsIgnoreCase(a.getBankCode()))
                .findFirst()
                .orElseGet(() -> agreements.save(CreditAgreement.builder()
                        .agreementName(bank + " credit agreement")
                        .corporateId(corporateId)
                        .bankCode(bic)
                        .bankName(bank)
                        .totalLimit(amount)
                        .limitCurrency(home)
                        .effectiveDate(today)
                        .expiryDate(today.plusYears(1))
                        .sourceSystem(SOURCE)
                        .build()));
        String currency = agreement.getLimitCurrency();
        CreditFacility facility = facilities.save(CreditFacility.builder()
                .facilityName("FX treasury line")
                .creditAgreementId(agreement.getId())
                .corporateId(corporateId)
                .facilityType(FacilityType.FX_TREASURY_LINE)
                .sanctionedLimit(amount)
                .facilityCurrency(currency)
                .sanctionDate(today)
                .effectiveDate(today)
                .expiryDate(today.plusYears(1))
                .status(FacilityStatus.ACTIVE)
                .sourceSystem(SOURCE)
                .build());
        CreditLimit line = CreditLimit.createExternalLimit(corporateId, corporateId, TargetType.FX_LINE, facility.getId(), amount, currency);
        line.setLimitName("FX line - " + bank);
        line.setStatus(LimitStatus.ACTIVE);
        line.setIsHardLimit(true);
        line.setEffectiveFrom(today);
        line.setEffectiveTo(facility.getExpiryDate());
        line.setSourceSystem(SOURCE);
        lines.save(line);

        entities.findByCorporateIdAndStatus(corporateId, LegalEntity.EntityStatus.ACTIVE).stream()
                .min(Comparator.comparingInt((LegalEntity e) -> e.isTreasury() ? 0 : e.isHolding() ? 1 : e.isRoot() ? 2 : 3)
                        .thenComparing(LegalEntity::getEntityName))
                .ifPresent(e -> allocate(corporateId, e.getId(), amount));
        return true;
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private LegalEntity entityOf(UUID corporateId, UUID entityId) {
        LegalEntity e = entities.findById(entityId).orElseThrow(() -> new ResourceNotFoundException("Legal entity not found: " + entityId));
        if (!corporateId.equals(e.getCorporateId())) {
            throw new BusinessException("FX_ENTITY_INVALID", e.getEntityName() + " is not an entity of this corporate");
        }
        if (e.getStatus() != LegalEntity.EntityStatus.ACTIVE) {
            throw new BusinessException("FX_ENTITY_INVALID", e.getEntityName() + " is not active");
        }
        return e;
    }

    private static String lineProblem(CreditFacility f, CreditLimit l) {
        LocalDate today = LocalDate.now();
        if (f != null) {
            if (f.getStatus() != FacilityStatus.ACTIVE) {
                return "The FX facility is " + f.getStatus().name().toLowerCase(Locale.ROOT).replace('_', ' ');
            }
            if (f.getExpiryDate() != null && f.getExpiryDate().isBefore(today)) {
                return "The FX facility expired on " + f.getExpiryDate();
            }
            if (f.getEffectiveDate() != null && f.getEffectiveDate().isAfter(today)) {
                return "The FX facility starts on " + f.getEffectiveDate();
            }
        }
        return limitProblem(l, "The FX line");
    }

    private static String limitProblem(CreditLimit l, String what) {
        LocalDate today = LocalDate.now();
        if (l.getStatus() != LimitStatus.ACTIVE) {
            return what + " is " + l.getStatus().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
        if (l.getEffectiveTo() != null && l.getEffectiveTo().isBefore(today)) {
            return what + " expired on " + l.getEffectiveTo();
        }
        if (l.getEffectiveFrom() != null && l.getEffectiveFrom().isAfter(today)) {
            return what + " starts on " + l.getEffectiveFrom();
        }
        return null;
    }

    private static Usage usage(BigDecimal limit, BigDecimal used) {
        return new Usage(limit, used, limit.subtract(used));
    }

    private static String money(BigDecimal v) {
        return String.format(Locale.US, "%,.2f", v);
    }
}
