package com.bank.vam.fx.deal;

import com.bank.vam.entity.Corporate;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import com.bank.vam.repository.CorporateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Books deals from accepted quotes and moves them through their states. Reads for the blotter live here too. */
@Service
@RequiredArgsConstructor
public class FxDealService {

    private final FxDealRepository deals;
    private final FxDealEventRepository events;
    private final FxEvents log;
    private final CorporateRepository corporates;

    /** Books the deal for an accepted quote, issues its confirmation, and leaves it ACTIVE (forward) or awaiting settlement (spot). */
    FxDeal book(FxQuoteRequest q, BigDecimal limitCharge, String limitCurrency) {
        boolean buyBase = q.getSide() == Side.BUY;
        FxDeal d = new FxDeal();
        d.setDealRef("FXD-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-"
                + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT));
        d.setCorporateId(q.getCorporateId());
        d.setQuoteRequestId(q.getId());
        d.setInstrument(q.getInstrument());
        d.setPair(q.getPair());
        d.setSide(q.getSide());
        d.setBuyCurrency(buyBase ? q.baseCurrency() : q.quoteCurrency());
        d.setBuyAmount(buyBase ? q.getAmount() : q.getCounterAmount());
        d.setSellCurrency(buyBase ? q.quoteCurrency() : q.baseCurrency());
        d.setSellAmount(buyBase ? q.getCounterAmount() : q.getAmount());
        d.setRate(q.getClientRate());
        d.setMidRate(q.getMidRate());
        d.setForwardPoints(q.getForwardPoints());
        d.setSpreadBps(q.getSpreadBps());
        d.setTradeDate(LocalDate.now());
        d.setValueDate(q.getValueDate());
        d.setWindowStart(q.getWindowStart());
        d.setLimitCharge(limitCharge);
        d.setLimitCurrency(limitCurrency);
        d.setPurpose(q.getPurpose());
        d.setExposureRef(q.getExposureRef());
        d = deals.save(d);

        log.deal(d, null, DealStatus.BOOKED, "Booked from quote at " + d.getRate());
        // The bank's back office sends the confirmation; the deal is live meanwhile, and cannot settle until the client affirms it.
        d.setConfirmationStatus(ConfirmationStatus.ISSUED);
        d.setConfirmationIssuedAt(LocalDateTime.now());
        log.note(d, "CONFIRMATION_SENT", "Back office issued the trade confirmation to the client, to check and confirm");
        transition(d, d.getInstrument() == Instrument.SPOT ? DealStatus.PENDING_SETTLEMENT : DealStatus.ACTIVE,
                d.getInstrument() == Instrument.SPOT ? "Awaiting settlement on " + d.getValueDate()
                        : "Live until " + d.getValueDate());
        return deals.save(d);
    }

    /** The only way a deal changes status, so an illegal move cannot happen by accident. */
    void transition(FxDeal d, DealStatus to, String detail) {
        DealStatus from = d.getStatus();
        if (!from.next().contains(to)) {
            throw new BusinessException("FX_DEAL_STATE_INVALID", "A deal cannot go from " + from + " to " + to);
        }
        d.setStatus(to);
        log.deal(d, from, to, detail);
    }

    public List<FxDeal> list(UUID corporateId) {
        return deals.findByCorporateIdOrderByCreatedAtDesc(corporateId);
    }

    public FxDeal get(UUID id) {
        return deals.findById(id).orElseThrow(() -> new ResourceNotFoundException("Deal not found: " + id));
    }

    public List<FxDealEvent> events(UUID entityId) {
        return events.findByEntityIdOrderByCreatedAtAsc(entityId);
    }

    /** Plain-text trade confirmation. Generated from the deal each time, so it can never disagree with it. */
    public String confirmation(FxDeal d) {
        String client = corporates.findById(d.getCorporateId()).map(Corporate::getLegalName).orElse("Client");
        String when = switch (d.getInstrument()) {
            case SPOT -> "Spot, value " + d.getValueDate();
            case FORWARD -> "Forward, value " + d.getValueDate();
            case WINDOW -> "Window forward, drawable " + d.getWindowStart() + " to " + d.getValueDate();
        };
        String points = d.getForwardPoints() != null && d.getForwardPoints().signum() != 0
                ? " plus forward points " + d.getForwardPoints().toPlainString() : "";
        return String.join("\n",
                "FX TRADE CONFIRMATION (SIMULATED - NOT A REAL TRADE)",
                "",
                "Deal reference : " + d.getDealRef(),
                "Client         : " + client,
                "Trade date     : " + d.getTradeDate(),
                "Instrument     : " + when,
                "You buy        : " + d.getBuyCurrency() + " " + money(d.getBuyAmount()),
                "You sell       : " + d.getSellCurrency() + " " + money(d.getSellAmount()),
                "Rate           : " + d.getRate().stripTrailingZeros().toPlainString() + " (" + d.getPair() + ")",
                "Price build-up : interbank mid " + d.getMidRate().stripTrailingZeros().toPlainString() + points
                        + ", bank margin " + d.getSpreadBps().toPlainString() + " bps",
                "Status         : " + d.getStatus(),
                "",
                "Please check these details against the trade you agreed and confirm or dispute",
                "the deal in the FX Workspace. A deal that is not confirmed cannot settle.",
                "",
                "Simulated desk and rates for demonstration. No settlement instructions are issued.");
    }

    public byte[] confirmationPdf(FxDeal d) {
        return ConfirmationPdf.render(confirmation(d));
    }

    private static String money(BigDecimal v) {
        return String.format(Locale.US, "%,.2f", v);
    }
}
