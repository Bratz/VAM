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
    private final FxLimitService limits;
    private final FxSettlementAccounts settlementAccounts;

    /** Books the deal for an accepted quote, issues its confirmation, and leaves it ACTIVE (forward) or awaiting settlement (spot). */
    FxDeal book(FxQuoteRequest q, BigDecimal limitCharge, String limitCurrency) {
        boolean buyBase = q.getSide() == Side.BUY;
        FxDeal d = new FxDeal();
        d.setDealRef("FXD-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-"
                + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT));
        d.setCorporateId(q.getCorporateId());
        d.setEntityId(q.getEntityId());
        d.setEntityName(q.getEntityName());
        d.setBuyAccountId(q.getBuyAccountId());
        d.setBuyAccountLabel(q.getBuyAccountLabel());
        d.setSellAccountId(q.getSellAccountId());
        d.setSellAccountLabel(q.getSellAccountLabel());
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
        // The FX line's and sub-limits' utilised amounts follow the open deals; keep them current for the credit pages.
        limits.refreshUsage(d.getCorporateId());
    }

    /**
     * Changes the accounts a deal settles through. Allowed until the day before value date, or later while a failed
     * settlement waits to be tried again. Each account must be one of the entity's home-bank accounts in that currency.
     */
    @org.springframework.transaction.annotation.Transactional
    public FxDeal changeAccounts(UUID dealId, UUID buyAccountId, UUID sellAccountId) {
        FxDeal d = get(dealId);
        boolean open = DealStatus.OPEN.contains(d.getStatus());
        boolean beforeValueDate = LocalDate.now().isBefore(d.getValueDate());
        if (!open || !(beforeValueDate || d.getStatus() == DealStatus.SETTLEMENT_FAILED)) {
            throw new BusinessException("FX_SETTLEMENT_LOCKED", "The settlement accounts of " + d.getDealRef()
                    + " can no longer be changed: they can be until the day before value date (" + d.getValueDate() + ")");
        }
        if (d.getEntityId() == null) {
            throw new BusinessException("FX_SETTLEMENT_ACCOUNT_INVALID", d.getDealRef() + " is not booked for an entity");
        }
        StringBuilder what = new StringBuilder();
        if (buyAccountId != null && !buyAccountId.equals(d.getBuyAccountId())) {
            FxSettlementAccounts.Account a = settlementAccounts.require(d.getCorporateId(), d.getEntityId(), d.getBuyCurrency(), buyAccountId);
            what.append(d.getBuyCurrency()).append(" credit account ").append(d.getBuyAccountLabel()).append(" to ").append(a.label()).append(". ");
            d.setBuyAccountId(a.id());
            d.setBuyAccountLabel(a.label());
        }
        if (sellAccountId != null && !sellAccountId.equals(d.getSellAccountId())) {
            FxSettlementAccounts.Account a = settlementAccounts.require(d.getCorporateId(), d.getEntityId(), d.getSellCurrency(), sellAccountId);
            what.append(d.getSellCurrency()).append(" debit account ").append(d.getSellAccountLabel()).append(" to ").append(a.label()).append(". ");
            d.setSellAccountId(a.id());
            d.setSellAccountLabel(a.label());
        }
        if (what.length() > 0) {
            log.note(d, "SETTLE_ACCOUNTS", "Settlement accounts changed: " + what.toString().trim());
        }
        return deals.save(d);
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
        return String.join("\n",
                "FX TRADE CONFIRMATION (SIMULATED - NOT A REAL TRADE)",
                "",
                "Deal reference : " + d.getDealRef(),
                "Client         : " + client,
                "Entity         : " + (d.getEntityName() == null ? client : d.getEntityName()),
                "Trade date     : " + d.getTradeDate(),
                "Instrument     : " + when,
                "You buy        : " + d.getBuyCurrency() + " " + money(d.getBuyAmount()),
                "You sell       : " + d.getSellCurrency() + " " + money(d.getSellAmount()),
                "Credit account : " + (d.getBuyAccountLabel() == null ? "to be advised" : d.getBuyAccountLabel()),
                "Debit account  : " + (d.getSellAccountLabel() == null ? "to be advised" : d.getSellAccountLabel()),
                buildUp(d) + "Rate           : " + d.getRate().stripTrailingZeros().toPlainString() + " (" + d.getPair() + ")",
                "Status         : " + d.getStatus(),
                "",
                "Please check these details against the trade you agreed and confirm or dispute",
                "the deal in FX Deals. A deal that is not confirmed cannot settle.",
                "",
                "Simulated desk and rates for demonstration. No settlement instructions are issued.");
    }

    /** For a forward, the spot rate and forward points that make up the all-in rate, as a portal shows them; none for spot. */
    private static String buildUp(FxDeal d) {
        FxDealModels.ClientPrice p = FxDealModels.ClientPrice.of(d.getInstrument(), d.getSide(), d.quoteCurrency(),
                d.getRate(), d.getMidRate(), d.getSpreadBps());
        if (p.spotRate() == null) {
            return "";
        }
        return "Spot rate      : " + p.spotRate().toPlainString() + "\n"
                + "Forward points : " + (p.forwardPoints().signum() > 0 ? "+" : "") + p.forwardPoints().toPlainString() + "\n";
    }

    public byte[] confirmationPdf(FxDeal d) {
        return ConfirmationPdf.render(confirmation(d));
    }

    private static String money(BigDecimal v) {
        return String.format(Locale.US, "%,.2f", v);
    }
}
