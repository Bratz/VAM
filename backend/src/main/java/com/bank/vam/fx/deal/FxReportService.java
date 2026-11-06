package com.bank.vam.fx.deal;

import com.bank.vam.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The corporate's FX reports, each one table for a period: deal history, settlements, the month-end MTM statement
 * and the confirmation archive. The same table feeds the screen, the CSV and the PDF, so they cannot disagree.
 * Corporate-facing: no bank mid, margin or floor in any of them (the MTM's market rate is the indicative rate the
 * deal list already shows).
 *
 * <p>ponytail: reads a corporate's deals and filters in memory; add dated queries when a corporate has thousands.
 */
@Service
@RequiredArgsConstructor
public class FxReportService {

    public enum Kind {
        DEALS("FX deal history"), SETTLEMENTS("FX maturities and settlements"), MTM("FX mark-to-market statement"),
        CONFIRMATIONS("FX confirmation archive");

        final String title;

        Kind(String title) {
            this.title = title;
        }

        static Kind of(String s) {
            try {
                return valueOf(s.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new BusinessException("FX_REPORT_UNKNOWN", "Unknown report '" + s + "'; use deals, settlements, mtm or confirmations");
            }
        }
    }

    /** {@code subtitle} says the period or the as-of date. */
    public record Table(String kind, String title, String subtitle, List<String> columns, List<List<String>> rows) {
    }

    private final FxDealRepository deals;
    private final FxSettlementRepository settlements;
    private final FxMtmRepository marks;
    private final FxMtmService mtm;
    private final FxDealService dealService;

    public Table table(String kind, UUID corporateId, LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new BusinessException("FX_REPORT_PERIOD", "Choose a period whose start is on or before its end");
        }
        Kind k = Kind.of(kind);
        List<FxDeal> all = deals.findByCorporateIdOrderByCreatedAtDesc(corporateId);
        String period = from + " to " + to;
        return switch (k) {
            case DEALS -> new Table(k.name(), k.title, "Traded " + period,
                    List.of("Deal", "Entity", "Trade date", "Instrument", "Pair", "You buy", "You sell", "Rate", "Value date", "Status", "Confirmation"),
                    all.stream().filter(d -> within(d.getTradeDate(), from, to))
                            .sorted(Comparator.comparing(FxDeal::getTradeDate).thenComparing(FxDeal::getDealRef))
                            .map(d -> List.of(d.getDealRef(), nz(d.getEntityName()), d.getTradeDate().toString(), name(d.getInstrument()),
                                    d.getPair(), d.getBuyCurrency() + " " + money(d.getBuyAmount()), d.getSellCurrency() + " " + money(d.getSellAmount()),
                                    d.getRate().stripTrailingZeros().toPlainString(), valueDates(d), name(d.getStatus()), name(d.getConfirmationStatus())))
                            .toList());
            case SETTLEMENTS -> settlementsTable(k, all, from, to, period);
            case MTM -> mtmTable(k, all, to);
            case CONFIRMATIONS -> new Table(k.name(), k.title, "Issued " + period,
                    List.of("Deal", "Entity", "Issued", "Status", "Confirmed by", "Confirmed at", "Dispute"),
                    confirmationsIn(all, from, to).stream()
                            .map(d -> List.of(d.getDealRef(), nz(d.getEntityName()), stamp(d.getConfirmationIssuedAt()), name(d.getConfirmationStatus()),
                                    nz(d.getConfirmedBy()), stamp(d.getConfirmedAt()), nz(d.getDisputeReason())))
                            .toList());
        };
    }

    public String csv(Table t) {
        StringBuilder sb = new StringBuilder();
        sb.append(csvLine(t.columns()));
        t.rows().forEach(r -> sb.append(csvLine(r)));
        return sb.toString();
    }

    /** The table as a landscape PDF; the confirmation archive is the confirmations themselves, one per page. */
    public byte[] pdf(Table t, UUID corporateId, LocalDate from, LocalDate to) {
        if (Kind.of(t.kind()) == Kind.CONFIRMATIONS) {
            List<FxDeal> issued = confirmationsIn(deals.findByCorporateIdOrderByCreatedAtDesc(corporateId), from, to);
            String text = issued.isEmpty() ? t.title() + "\n" + t.subtitle() + "\n\nNo confirmations were issued in this period."
                    : issued.stream().map(dealService::confirmation).collect(Collectors.joining("\f"));
            return ConfirmationPdf.render(text, false);
        }
        return ConfirmationPdf.render(textTable(t), true);
    }

    // ---- the four tables ----

    private Table settlementsTable(Kind k, List<FxDeal> all, LocalDate from, LocalDate to, String period) {
        Map<UUID, FxDeal> byId = all.stream().collect(Collectors.toMap(FxDeal::getId, Function.identity()));
        List<List<String>> rows = new ArrayList<>();
        all.stream().flatMap(d -> settlements.findByDealIdOrderByCreatedAtAsc(d.getId()).stream())
                .filter(s -> within(s.getValueDate(), from, to))
                .sorted(Comparator.comparing(FxSettlement::getValueDate))
                .forEach(s -> {
                    FxDeal d = byId.get(s.getDealId());
                    rows.add(List.of(d.getDealRef(), nz(d.getEntityName()), s.getValueDate().toString(), name(s.getKind()),
                            s.getBuyAmount().signum() > 0 ? s.getBuyCurrency() + " " + money(s.getBuyAmount()) : "",
                            s.getSellAmount().signum() > 0 ? s.getSellCurrency() + " " + money(s.getSellAmount()) : "",
                            nz(s.getCreditAccountLabel()), nz(s.getDebitAccountLabel()), name(s.getStatus()), nz(s.getReason())));
                });
        // Open deals maturing in the period that have not settled yet: what is coming.
        all.stream().filter(d -> DealStatus.OPEN.contains(d.getStatus()) && d.remainingBase().signum() > 0 && within(d.getValueDate(), from, to))
                .sorted(Comparator.comparing(FxDeal::getValueDate))
                .forEach(d -> rows.add(List.of(d.getDealRef(), nz(d.getEntityName()), d.getValueDate().toString(), "Maturity (due)",
                        d.getBuyCurrency() + " " + money(share(d.getBuyAmount(), d)), d.getSellCurrency() + " " + money(share(d.getSellAmount(), d)),
                        nz(d.getBuyAccountLabel()), nz(d.getSellAccountLabel()), "Due", "")));
        return new Table(k.name(), k.title, "Value dates " + period,
                List.of("Deal", "Entity", "Value date", "Kind", "Credited", "Debited", "Credit account", "Debit account", "Status", "Reason"), rows);
    }

    /** Deals open at the end of {@code asOf}, each worth what the market says: live today, else the last mark on or before. */
    private Table mtmTable(Kind k, List<FxDeal> all, LocalDate asOf) {
        boolean live = !asOf.isBefore(LocalDate.now());
        List<List<String>> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        String home = null;
        boolean allMarked = true;
        for (FxDeal d : all.stream().filter(d -> openAt(d, asOf)).sorted(Comparator.comparing(FxDeal::getValueDate)).toList()) {
            BigDecimal rateUsed = null, worthQuote = null, worthHome = null;
            String homeCcy = null;
            if (live) {
                FxMtmService.Mark m = mtm.mark(d).orElse(null);
                if (m != null) {
                    rateUsed = m.rateUsed();
                    worthQuote = m.mtmQuote();
                    worthHome = m.mtmHome();
                    homeCcy = m.homeCurrency();
                }
            } else {
                FxMtm m = marks.findByDealIdOrderByAsOfAsc(d.getId()).stream().filter(x -> !x.getAsOf().isAfter(asOf))
                        .reduce((a, b) -> b).orElse(null);
                if (m != null) {
                    rateUsed = m.getRateUsed();
                    worthQuote = m.getMtmQuote();
                    worthHome = m.getMtmHome();
                    homeCcy = m.getHomeCurrency();
                }
            }
            if (worthHome != null) {
                total = total.add(worthHome);
                home = homeCcy;
            } else {
                allMarked = false;
            }
            rows.add(List.of(d.getDealRef(), nz(d.getEntityName()), d.getPair(), name(d.getSide()), money(d.remainingBase()) + " " + d.baseCurrency(),
                    d.getRate().stripTrailingZeros().toPlainString(), rateUsed == null ? "not marked" : rateUsed.stripTrailingZeros().toPlainString(),
                    d.getValueDate().toString(), worthQuote == null ? "" : signed(worthQuote) + " " + d.quoteCurrency(),
                    worthHome == null ? "" : signed(worthHome) + " " + homeCcy));
        }
        if (!rows.isEmpty()) {
            rows.add(List.of("Total", "", "", "", "", "", "", "", "", home == null ? "" : signed(total) + " " + home + (allMarked ? "" : " (marked deals only)")));
        }
        return new Table(k.name(), k.title, (live ? "As of today, at live indicative rates" : "As of " + asOf + ", from the end-of-day marks")
                + "; positive is in your favour",
                List.of("Deal", "Entity", "Pair", "Side", "Still to deliver", "Deal rate", "Market rate", "Value date", "Worth (quote ccy)", "Worth (home ccy)"),
                rows);
    }

    private static List<FxDeal> confirmationsIn(List<FxDeal> all, LocalDate from, LocalDate to) {
        return all.stream().filter(d -> d.getConfirmationIssuedAt() != null && within(d.getConfirmationIssuedAt().toLocalDate(), from, to))
                .sorted(Comparator.comparing(FxDeal::getConfirmationIssuedAt)).toList();
    }

    static boolean openAt(FxDeal d, LocalDate asOf) {
        if (d.getTradeDate().isAfter(asOf) || d.getStatus() == DealStatus.CANCELLED) {
            return false;
        }
        LocalDateTime ended = d.getSettledAt() != null ? d.getSettledAt() : d.getClosedAt();
        return DealStatus.OPEN.contains(d.getStatus()) ? true : ended != null && ended.toLocalDate().isAfter(asOf);
    }

    // ---- formatting ----

    private static String textTable(Table t) {
        int n = t.columns().size();
        int[] w = new int[n];
        for (int i = 0; i < n; i++) {
            w[i] = t.columns().get(i).length();
            for (List<String> r : t.rows()) {
                w[i] = Math.max(w[i], Math.min(r.get(i).length(), 40));
            }
        }
        StringBuilder sb = new StringBuilder(t.title()).append('\n').append(t.subtitle()).append("\nPrinted ")
                .append(LocalDate.now()).append("\n\n");
        sb.append(row(t.columns(), w)).append('\n');
        sb.append("-".repeat(java.util.Arrays.stream(w).sum() + 2 * (n - 1))).append('\n');
        if (t.rows().isEmpty()) {
            sb.append("Nothing in this period.\n");
        }
        t.rows().forEach(r -> sb.append(row(r, w)).append('\n'));
        return sb.toString();
    }

    private static String row(List<String> cells, int[] w) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            String c = cells.get(i).length() > 40 ? cells.get(i).substring(0, 39) + "~" : cells.get(i);
            sb.append(String.format("%-" + w[i] + "s", c));
            if (i < cells.size() - 1) {
                sb.append("  ");
            }
        }
        return sb.toString().stripTrailing();
    }

    private static String csvLine(List<String> cells) {
        return cells.stream().map(c -> c.contains(",") || c.contains("\"") || c.contains("\n")
                ? "\"" + c.replace("\"", "\"\"") + "\"" : c).collect(Collectors.joining(",")) + "\r\n";
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean within(LocalDate d, LocalDate from, LocalDate to) {
        return d != null && !d.isBefore(from) && !d.isAfter(to);
    }

    private static BigDecimal share(BigDecimal leg, FxDeal d) {
        return leg.multiply(d.remainingBase()).divide(d.baseAmount(), 2, RoundingMode.HALF_UP);
    }

    private static String valueDates(FxDeal d) {
        return d.getWindowStart() != null ? d.getWindowStart() + " to " + d.getValueDate() : d.getValueDate().toString();
    }

    private static String money(BigDecimal v) {
        return String.format(java.util.Locale.ROOT, "%,.2f", v);
    }

    private static String signed(BigDecimal v) {
        return (v.signum() > 0 ? "+" : "") + money(v);
    }

    private static String stamp(LocalDateTime t) {
        return t == null ? "" : t.withNano(0).toString().replace('T', ' ');
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String name(Enum<?> e) {
        if (e == null) {
            return "";
        }
        String s = e.name().replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
