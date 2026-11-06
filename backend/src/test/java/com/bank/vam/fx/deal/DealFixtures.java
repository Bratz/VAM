package com.bank.vam.fx.deal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** Deals for the lifecycle tests: EUR 100,000 against USD 110,000 at 1.10, affirmed unless a test says otherwise. */
final class DealFixtures {

    static final UUID CORP = UUID.randomUUID();
    static final UUID ENTITY = UUID.randomUUID();
    static final UUID EUR_ACCOUNT = UUID.randomUUID();
    static final UUID USD_ACCOUNT = UUID.randomUUID();

    private DealFixtures() {
    }

    static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    static FxDeal deal(Side side, Instrument instrument, DealStatus status, LocalDate valueDate) {
        FxDeal d = new FxDeal();
        d.setId(UUID.randomUUID());
        d.setDealRef("FXD-" + d.getId().toString().substring(0, 6).toUpperCase());
        d.setCorporateId(CORP);
        d.setQuoteRequestId(UUID.randomUUID());
        d.setInstrument(instrument);
        d.setPair("EUR/USD");
        d.setSide(side);
        boolean buy = side == Side.BUY;
        d.setBuyCurrency(buy ? "EUR" : "USD");
        d.setBuyAmount(buy ? bd("100000.00") : bd("110000.00"));
        d.setSellCurrency(buy ? "USD" : "EUR");
        d.setSellAmount(buy ? bd("110000.00") : bd("100000.00"));
        d.setEntityId(ENTITY);
        d.setBuyAccountId(buy ? EUR_ACCOUNT : USD_ACCOUNT);
        d.setBuyAccountLabel(d.getBuyCurrency() + " account");
        d.setSellAccountId(buy ? USD_ACCOUNT : EUR_ACCOUNT);
        d.setSellAccountLabel(d.getSellCurrency() + " account");
        d.setRate(bd("1.10"));
        d.setMidRate(bd("1.0997"));
        d.setSpreadBps(bd("3.00"));
        d.setTradeDate(LocalDate.of(2026, 11, 5));
        d.setValueDate(valueDate);
        d.setWindowStart(instrument == Instrument.WINDOW ? valueDate.minusDays(30) : null);
        d.setStatus(status);
        d.setLimitCharge(bd("1000.00"));
        d.setLimitCurrency("AED");
        d.setConfirmationStatus(ConfirmationStatus.AFFIRMED);
        d.setConfirmationIssuedAt(LocalDateTime.now().minusHours(1));
        d.setCreatedAt(LocalDateTime.now());
        return d;
    }
}
