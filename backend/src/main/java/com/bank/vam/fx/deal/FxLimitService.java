package com.bank.vam.fx.deal;

import com.bank.vam.config.FxDealProperties;
import com.bank.vam.config.FxDealProperties.PfeBand;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.fx.HomeCurrency;
import com.bank.vam.service.treasury.FxRateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Credit exposure per corporate. Each open deal is charged its notional (in the limit's currency,
 * at the stored platform rate) times a factor that grows with days to maturity, a simple stand-in
 * for the potential future exposure of a forward. A corporate with no limit gets the default on first use.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FxLimitService {

    public record Position(UUID corporateId, String currency, BigDecimal limit, BigDecimal used, BigDecimal headroom) {
    }

    private final FxCreditLimitRepository limits;
    private final FxDealRepository deals;
    private final HomeCurrency homeCurrency;
    private final FxRateService rates;
    private final FxDealProperties props;

    /** @return true when a limit was created */
    @Transactional
    public boolean ensureDefault(UUID corporateId) {
        if (limits.findByCorporateId(corporateId).isPresent()) {
            return false;
        }
        FxCreditLimit l = new FxCreditLimit();
        l.setCorporateId(corporateId);
        l.setCurrency(homeCurrency.of(corporateId));
        l.setLimitAmount(props.getDefaultLimit());
        try {
            limits.saveAndFlush(l);
            return true;
        } catch (DataIntegrityViolationException e) {
            log.debug("FX limit for {} created concurrently", corporateId);
            return false;
        }
    }

    public FxCreditLimit limitFor(UUID corporateId) {
        ensureDefault(corporateId);
        return limits.findByCorporateId(corporateId).orElseThrow();
    }

    /** Same as {@link #limitFor} but holds the row until the transaction ends, to serialise bookings. */
    public FxCreditLimit lockLimitFor(UUID corporateId) {
        ensureDefault(corporateId);
        return limits.lockByCorporateId(corporateId).orElseThrow();
    }

    public Position position(FxCreditLimit limit) {
        BigDecimal used = deals.sumLimitCharge(limit.getCorporateId(), limit.getCurrency(), DealStatus.OPEN);
        return new Position(limit.getCorporateId(), limit.getCurrency(), limit.getLimitAmount(), used,
                limit.getLimitAmount().subtract(used));
    }

    public Position position(UUID corporateId) {
        return position(limitFor(corporateId));
    }

    /** What a deal of {@code amount} in {@code currency}, maturing on {@code maturity}, adds to the limit's use. */
    public BigDecimal charge(String currency, BigDecimal amount, LocalDate tradeDate, LocalDate maturity, String limitCurrency) {
        BigDecimal notional = currency.equals(limitCurrency) ? amount : amount.multiply(rate(currency, limitCurrency));
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

    /** Throws when {@code charge} does not fit in what is left. */
    public void require(Position p, BigDecimal charge) {
        if (charge.compareTo(p.headroom()) > 0) {
            throw new BusinessException("FX_LIMIT_EXCEEDED", String.format(
                    "FX credit limit exceeded: this deal needs %s %s of limit, %s %s is left (limit %s, in use %s)",
                    money(charge), p.currency(), money(p.headroom().max(BigDecimal.ZERO)), p.currency(),
                    money(p.limit()), money(p.used())));
        }
    }

    private BigDecimal rate(String from, String to) {
        return rates.getRate(from, to);
    }

    private static String money(BigDecimal v) {
        return String.format(java.util.Locale.US, "%,.2f", v);
    }
}
