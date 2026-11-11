package com.bank.vam.fx.deal;

import com.bank.vam.exception.BusinessException;
import com.bank.vam.fx.ForwardPricer;
import com.bank.vam.fx.HomeCurrency;
import com.bank.vam.service.treasury.FxRateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What a deal is worth against today's market: the gain or loss if it were closed at the mid (no bank
 * margin) today. Undiscounted, which is fine for a demo. Marks are computed live for the blotter and
 * snapshotted once a day so the value can be followed over time.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FxMtmService {

    /**
     * {@code mtmHome} is null when there is no stored platform rate from the quote currency to home. {@code source}
     * is the market the rate came from ("SIMULATED").
     */
    public record Mark(BigDecimal rateUsed, BigDecimal mtmQuote, String quoteCurrency, BigDecimal mtmHome, String homeCurrency,
                       String source) {
    }

    private final ForwardPricer forwards;
    private final FxRateService rates;
    private final HomeCurrency homeCurrency;
    private final FxMtmRepository mtms;
    private final FxDealRepository deals;
    private final FxLimitService limits;

    /** Positive is in the corporate's favour. A buyer gains when the market is above its rate. */
    static BigDecimal mtmQuote(Side side, BigDecimal remainingBase, BigDecimal contractedRate, BigDecimal currentMid) {
        BigDecimal gain = remainingBase.multiply(currentMid.subtract(contractedRate));
        return (side == Side.BUY ? gain : gain.negate()).setScale(2, RoundingMode.HALF_UP);
    }

    /** Empty when the market cannot price the pair or date, so one odd deal never breaks a blotter. */
    public Optional<Mark> mark(FxDeal d) {
        try {
            LocalDate spot = forwards.spotDate();
            LocalDate target = d.getValueDate().isAfter(spot) ? d.getValueDate() : spot;
            com.bank.vam.fx.ForwardQuote fwd = forwards.forward(d.getPair(), target);
            BigDecimal current = fwd.forwardMid();
            BigDecimal quoteAmount = mtmQuote(d.getSide(), d.remainingBase(), d.getRate(), current);
            String home = homeCurrency.of(d.getCorporateId());
            BigDecimal inHome = null;
            try {
                inHome = d.quoteCurrency().equals(home) ? quoteAmount
                        : quoteAmount.multiply(rates.getRate(d.quoteCurrency(), home)).setScale(2, RoundingMode.HALF_UP);
            } catch (BusinessException e) {
                log.debug("No platform rate {}/{} for the home-currency mark", d.quoteCurrency(), home);
            }
            return Optional.of(new Mark(current, quoteAmount, d.quoteCurrency(), inHome, home, fwd.source()));
        } catch (BusinessException e) {
            log.warn("Could not mark {}: {}", d.getDealRef(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Stores today's mark for every open deal (one row per deal per day, replaced on a rerun) and refreshes
     * each deal's credit-limit charge, which shrinks as the deal gets closer to maturity.
     *
     * @param corporateId one corporate, or null for all
     * @return how many deals were marked
     */
    @Transactional
    public int snapshot(LocalDate asOf, UUID corporateId) {
        List<FxDeal> open = corporateId == null ? deals.findByStatusIn(DealStatus.OPEN)
                : deals.findByCorporateIdAndStatusIn(corporateId, DealStatus.OPEN);
        int marked = 0;
        for (FxDeal d : open) {
            Optional<Mark> mark = mark(d);
            if (mark.isEmpty()) {
                continue;
            }
            FxMtm row = mtms.findByDealIdAndAsOf(d.getId(), asOf).orElseGet(FxMtm::new);
            row.setDealId(d.getId());
            row.setCorporateId(d.getCorporateId());
            row.setAsOf(asOf);
            row.setRateUsed(mark.get().rateUsed());
            row.setContractedRate(d.getRate());
            row.setMtmQuote(mark.get().mtmQuote());
            row.setQuoteCurrency(mark.get().quoteCurrency());
            row.setMtmHome(mark.get().mtmHome());
            row.setHomeCurrency(mark.get().homeCurrency());
            mtms.save(row);
            refreshLimitCharge(d, asOf);
            marked++;
        }
        // The charges shrink as deals near maturity: write the new usage to the FX line and sub-limits.
        open.stream().map(FxDeal::getCorporateId).distinct().forEach(limits::refreshUsage);
        return marked;
    }

    public List<FxMtm> history(UUID dealId) {
        return mtms.findByDealIdOrderByAsOfAsc(dealId);
    }

    private void refreshLimitCharge(FxDeal d, LocalDate asOf) {
        try {
            d.setLimitCharge(limits.charge(d.getInstrument(), d.baseCurrency(), d.remainingBase(), asOf, d.getValueDate(), d.getLimitCurrency()));
        } catch (BusinessException e) {
            log.debug("Left {} limit charge unchanged: {}", d.getDealRef(), e.getMessage());
        }
    }
}
