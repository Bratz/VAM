package com.bank.vam.service.intercompany;

import com.bank.vam.config.MarketProfileProperties;
import com.bank.vam.entity.VirtualAccount;
import com.bank.vam.entity.VirtualAccount.MirrorAccountType;
import com.bank.vam.entity.hierarchy.LegalEntity;
import com.bank.vam.entity.payables.Payable;
import com.bank.vam.entity.payables.Payable.PayableStatus;
import com.bank.vam.entity.pobo.IntercompanyRecharge;
import com.bank.vam.entity.receivables.Receivable;
import com.bank.vam.entity.receivables.Receivable.ReceivableStatus;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.repository.VirtualAccountRepository;
import com.bank.vam.repository.hierarchy.LegalEntityRepository;
import com.bank.vam.repository.payables.PayableRepository;
import com.bank.vam.repository.pobo.IntercompanyRechargeRepository;
import com.bank.vam.repository.receivables.ReceivableRepository;
import com.bank.vam.service.intercompany.IntercompanyTransactionService.SubsidiaryIntercompanyPosition;
import com.bank.vam.service.treasury.FxRateService;
import com.bank.vam.service.treasury.NettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * The group's who-owes-whom for one corporate (spec ihb-intercompany-pages D2, D9; R3, R4, R6). Per currency and pair
 * of entities, three kinds of line kept apart: the in-house bank position from the ledger (treasury's IC receivable /
 * payable mirrors), open intercompany invoices, and POBO/COBO recharges awaiting netting. Plus a zero-sum control per
 * currency over the in-house bank positions, and one total in the corporate's reporting currency at the stored rate,
 * returned with each rate and its date. Nothing is summed across currencies except in that labelled conversion.
 * Recharges still awaiting approval are a fourth kind, shown apart: not owed until approved, so they are in no total.
 */
@Service
@RequiredArgsConstructor
public class IntercompanyGroupPositionService {

    public static final String IHB_POSITION = "IHB_POSITION";
    public static final String RECEIVABLE = "RECEIVABLE";
    public static final String PAYABLE = "PAYABLE";
    public static final String RECHARGE = "RECHARGE";
    public static final String RECHARGE_PENDING = "RECHARGE_PENDING";

    /** No longer owed: paid, settled through netting, cancelled, written off, or not issued yet. */
    private static final Set<ReceivableStatus> CLOSED_RECEIVABLE = EnumSet.of(ReceivableStatus.DRAFT,
        ReceivableStatus.PAID, ReceivableStatus.CANCELLED, ReceivableStatus.WRITTEN_OFF, ReceivableStatus.NETTED);
    private static final Set<PayableStatus> CLOSED_PAYABLE = EnumSet.of(PayableStatus.DRAFT, PayableStatus.REJECTED,
        PayableStatus.CANCELLED, PayableStatus.PAID, PayableStatus.NETTED);

    private final IntercompanyTransactionService icTransactions;
    private final LegalEntityRepository legalEntities;
    private final VirtualAccountRepository accounts;
    private final PayableRepository payables;
    private final ReceivableRepository receivables;
    private final IntercompanyRechargeRepository recharges;
    private final FxRateService fxRates;
    private final MarketProfileProperties marketProfile;

    public record Party(UUID id, String code, String name) {}

    /** The debtor owes the creditor {@code amount}. sourceId is the invoice or recharge (null for an IHB position). */
    public record Line(String kind, UUID sourceId, String reference, Party debtor, Party creditor, BigDecimal amount,
                       String currency, String status, LocalDate dueDate) {}

    /** What one line kind nets to within a pair; debtor and creditor are null when it nets to zero. */
    public record Net(Party debtor, Party creditor, BigDecimal amount) {}

    public record LineGroup(List<Line> lines, Net net) {}

    public record PairPosition(Party entityA, Party entityB, LineGroup ihbPositions, LineGroup openInvoices,
                               LineGroup recharges, LineGroup rechargesAwaitingApproval) {}

    /** Across the group, what is owed to entities less what they owe, on the IHB positions; non-zero is a break. */
    public record Control(BigDecimal receivables, BigDecimal payables, BigDecimal difference, boolean balanced) {}

    /** outstanding: every line of the currency, each invoice once; recharges awaiting approval are not owed yet. */
    public record CurrencyPosition(String currency, BigDecimal outstanding, Control control, List<PairPosition> pairs) {}

    /** {@code amount} in {@code currency} at {@code rate} (1 currency = rate reporting currency) dated rateDate. */
    public record Conversion(String currency, BigDecimal amount, BigDecimal rate, LocalDate rateDate,
                             BigDecimal converted) {}

    /** The outstanding of every currency in the reporting currency; currencies without a rate are left out, named. */
    public record ReportingTotal(String currency, BigDecimal amount, List<Conversion> conversions,
                                 List<String> unconvertedCurrencies) {}

    public record GroupPosition(UUID corporateId, boolean allSettled, List<CurrencyPosition> currencies,
                                ReportingTotal reportingTotal) {}

    @Transactional(readOnly = true)
    public GroupPosition getGroupPosition(UUID corporateId) {
        List<LegalEntity> group = legalEntities.findByCorporateIdOrderByHierarchyPath(corporateId);
        Map<UUID, LegalEntity> byId = new HashMap<>();
        group.forEach(e -> byId.put(e.getId(), e));

        List<Line> lines = new ArrayList<>();
        lines.addAll(ihbLines(corporateId, byId));
        lines.addAll(invoiceLines(corporateId, byId));
        lines.addAll(rechargeLines(byId));
        Map<String, Control> controls = controls(corporateId, byId);

        Set<String> currencies = new TreeSet<>(controls.keySet());
        lines.forEach(l -> currencies.add(l.currency()));
        List<CurrencyPosition> positions = new ArrayList<>();
        for (String ccy : currencies) {
            List<Line> ofCcy = lines.stream().filter(l -> ccy.equals(l.currency())).toList();
            Control control = controls.getOrDefault(ccy, new Control(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, true));
            if (ofCcy.isEmpty() && control.balanced()) continue;
            BigDecimal outstanding = sum(ofCcy.stream().filter(l -> !RECHARGE_PENDING.equals(l.kind())).toList());
            positions.add(new CurrencyPosition(ccy, outstanding, control, pairs(ofCcy)));
        }
        return new GroupPosition(corporateId, lines.isEmpty(), positions, reportingTotal(group, positions));
    }

    /**
     * The double-count rule. One intercompany invoice can exist twice: as the creditor's receivable and the debtor's
     * payable, linked by Payable.counterpartyReceivableId or Receivable.counterpartyPayableId. A payable linked either
     * way to one of the group's IC receivables is that invoice's other side: it is counted, and netted, as the
     * receivable (the creditor's claim), whatever either side's status. Unlinked payables count on their own.
     * ponytail: no matching by amount/reference; documents not linked are two invoices. O(payables x receivables).
     */
    public static boolean countedAsReceivable(Payable payable, Collection<Receivable> icReceivables) {
        return icReceivables.stream().anyMatch(r -> r.getId().equals(payable.getCounterpartyReceivableId())
            || payable.getId().equals(r.getCounterpartyPayableId()));
    }

    // ------------------------------------------------------------------------------------------------------------
    // The three line kinds
    // ------------------------------------------------------------------------------------------------------------

    /** Who owes whom on the in-house bank, from treasury's IC receivable / payable mirrors (/intercompany/positions). */
    private List<Line> ihbLines(UUID corporateId, Map<UUID, LegalEntity> byId) {
        List<Line> lines = new ArrayList<>();
        for (SubsidiaryIntercompanyPosition p : icTransactions.getIntercompanyPositions(corporateId)) {
            BigDecimal net = p.getNetPosition(); // positive: the subsidiary owes treasury
            // Both sides must be entities of this group: a position with an unknown or outside side is no pair in it.
            if (net.signum() == 0 || !between(byId, p.getSubsidiaryEntityId(), p.getTreasuryEntityId())) continue;
            Party subsidiary = party(byId, p.getSubsidiaryEntityId());
            Party treasury = party(byId, p.getTreasuryEntityId());
            lines.add(new Line(IHB_POSITION, null, null, net.signum() > 0 ? subsidiary : treasury,
                net.signum() > 0 ? treasury : subsidiary, net.abs(), p.getCurrencyCode(), null, null));
        }
        return lines;
    }

    /** Open intercompany invoices between two of the group's entities, each invoice once (see countedAsReceivable). */
    private List<Line> invoiceLines(UUID corporateId, Map<UUID, LegalEntity> byId) {
        List<Line> lines = new ArrayList<>();
        List<Receivable> icReceivables = receivables.findIntercompanyReceivablesByCorporate(corporateId);
        for (Receivable r : icReceivables) {
            if (r.getStatus() == null || CLOSED_RECEIVABLE.contains(r.getStatus())) continue;
            if (!between(byId, r.getIntercompanyEntityId(), r.getOwningEntityId())) continue;
            BigDecimal owed = owed(r.getOutstandingAmount(), r.getNetAmount());
            if (owed.signum() <= 0) continue;
            lines.add(new Line(RECEIVABLE, r.getId(), r.getReceivableNumber(), party(byId, r.getIntercompanyEntityId()),
                party(byId, r.getOwningEntityId()), owed, r.getCurrencyCode(), r.getStatus().name(), r.getDueDate()));
        }
        for (Payable p : payables.findByCorporateIdAndIsIntercompanyTrue(corporateId)) {
            if (p.getStatus() == null || CLOSED_PAYABLE.contains(p.getStatus())) continue;
            if (!between(byId, p.getOwningEntityId(), p.getCounterpartyEntityId())) continue;
            if (countedAsReceivable(p, icReceivables)) continue;
            BigDecimal owed = owed(p.getOutstandingAmount(), p.getNetAmount());
            if (owed.signum() <= 0) continue;
            lines.add(new Line(PAYABLE, p.getId(), p.getPayableNumber(), party(byId, p.getOwningEntityId()),
                party(byId, p.getCounterpartyEntityId()), owed, p.getCurrencyCode(), p.getStatus().name(), p.getDueDate()));
        }
        return lines;
    }

    /**
     * Recharges a netting cycle would take (NettingService.awaitingNetting), and those of the same kinds still
     * awaiting approval (PENDING, line kind RECHARGE_PENDING), which the page approves or rejects. A POBO recharge:
     * the entity paid for owes the payer; a COBO collection: the collector owes the entity it collected for.
     * ponytail: every recharge row is read and filtered to the group; a query by entity ids if the table grows.
     */
    private List<Line> rechargeLines(Map<UUID, LegalEntity> byId) {
        List<Line> lines = new ArrayList<>();
        for (IntercompanyRecharge r : recharges.findAll()) {
            boolean pending = r.getStatus() == IntercompanyRecharge.RechargeStatus.PENDING && r.getSettlementReference() == null
                && (r.getRechargeType() == null || r.getRechargeType() == IntercompanyRecharge.RechargeType.COBO_COLLECTION);
            if (!(pending || NettingService.awaitingNetting(r)) || !between(byId, r.getPayerEntityId(), r.getBehalfEntityId())) continue;
            boolean cobo = r.getRechargeType() == IntercompanyRecharge.RechargeType.COBO_COLLECTION;
            UUID debtor = cobo ? r.getPayerEntityId() : r.getBehalfEntityId();
            UUID creditor = cobo ? r.getBehalfEntityId() : r.getPayerEntityId();
            lines.add(new Line(pending ? RECHARGE_PENDING : RECHARGE, r.getId(), r.getRechargeReference(), party(byId, debtor), party(byId, creditor),
                r.getTotalRecharge(), r.getCurrencyCode(), r.getStatus().name(), r.getSettlementDate()));
        }
        return lines;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Zero-sum control
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Per currency, both sides of every in-house bank position: treasury's IC receivable and payable mirrors, and the
     * participants' IHB current accounts (a credit balance is owed to the entity, a debit balance owed by it). The
     * group nets to zero when the mirrors follow the accounts; any difference is a break, e.g. a mirror that drifted or
     * an IHB current account with no mirrors at all. The treasury centre's own IHB account is not a position with it.
     */
    private Map<String, Control> controls(UUID corporateId, Map<UUID, LegalEntity> byId) {
        Map<String, BigDecimal[]> sides = new TreeMap<>(); // currency -> [owed to entities, owed by entities]
        for (VirtualAccount va : accounts.findByCorporateId(corporateId)) {
            BigDecimal balance = va.getCurrentBalance() != null ? va.getCurrentBalance() : BigDecimal.ZERO;
            MirrorAccountType mirror = va.getMirrorAccountType();
            boolean treasuryMirror = va.getMirrorsVaId() != null
                && (mirror == MirrorAccountType.IC_RECEIVABLE || mirror == MirrorAccountType.IC_PAYABLE);
            LegalEntity owner = va.getOwningEntityId() != null ? byId.get(va.getOwningEntityId()) : null;
            boolean participant = !treasuryMirror && va.isIhbParticipant()
                && !(owner != null && Boolean.TRUE.equals(owner.getIsTreasuryCenter()));
            if (!treasuryMirror && !participant) continue;
            BigDecimal[] side = sides.computeIfAbsent(va.getCurrencyCode(), c -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            if (mirror == MirrorAccountType.IC_RECEIVABLE && treasuryMirror) side[0] = side[0].add(balance);
            else if (treasuryMirror) side[1] = side[1].add(balance);
            else if (balance.signum() > 0) side[0] = side[0].add(balance);
            else side[1] = side[1].add(balance.negate());
        }
        Map<String, Control> controls = new TreeMap<>();
        sides.forEach((ccy, s) -> {
            BigDecimal difference = s[0].subtract(s[1]);
            controls.put(ccy, new Control(s[0], s[1], difference, difference.signum() == 0));
        });
        return controls;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Reporting-currency total
    // ------------------------------------------------------------------------------------------------------------

    /**
     * The outstanding of each currency at the rate the dashboard's consolidated position uses (FxRateService.getRate,
     * the stored rate, its inverse or a hop), with that rate and its date. Currencies without a rate are left out and
     * named, never counted 1:1.
     */
    private ReportingTotal reportingTotal(List<LegalEntity> group, List<CurrencyPosition> positions) {
        String reporting = reportingCurrency(group);
        BigDecimal total = BigDecimal.ZERO;
        List<Conversion> conversions = new ArrayList<>();
        List<String> unconverted = new ArrayList<>();
        for (CurrencyPosition p : positions) {
            if (p.outstanding().signum() == 0) continue;
            if (p.currency().equals(reporting)) {
                total = total.add(p.outstanding());
                continue;
            }
            try {
                BigDecimal rate = fxRates.getRate(p.currency(), reporting);
                BigDecimal converted = p.outstanding().multiply(rate).setScale(2, RoundingMode.HALF_UP);
                conversions.add(new Conversion(p.currency(), p.outstanding(), rate,
                    fxRates.getRateDate(p.currency(), reporting).orElse(null), converted));
                total = total.add(converted);
            } catch (BusinessException noRate) {
                unconverted.add(p.currency());
            }
        }
        return new ReportingTotal(reporting, total, conversions, unconverted);
    }

    /**
     * ponytail: a corporate has no reporting currency of its own. The group parent's (the root legal entity's
     * reporting currency, else its functional currency) stands in, and the market profile's default when the
     * corporate has no entities. Add corporates.reporting_currency if a group ever reports in another currency.
     */
    private String reportingCurrency(List<LegalEntity> group) {
        return group.stream().filter(LegalEntity::isRoot)
            .min(Comparator.comparing(e -> e.getHierarchyLevel() != null ? e.getHierarchyLevel() : 0))
            .map(e -> e.getReportingCurrency() != null ? e.getReportingCurrency() : e.getFunctionalCurrency())
            .orElse(marketProfile.getDefaultCurrency());
    }

    // ------------------------------------------------------------------------------------------------------------
    // Pairs
    // ------------------------------------------------------------------------------------------------------------

    private static List<PairPosition> pairs(List<Line> lines) {
        Map<Set<UUID>, List<Line>> byPair = new LinkedHashMap<>();
        lines.forEach(l -> byPair.computeIfAbsent(Set.of(l.debtor().id(), l.creditor().id()), k -> new ArrayList<>()).add(l));
        List<PairPosition> pairs = new ArrayList<>();
        byPair.values().stream()
            .sorted(Comparator.comparing((List<Line> ls) -> sum(ls)).reversed())
            .forEach(ls -> {
                Party x = ls.get(0).debtor();
                Party y = ls.get(0).creditor();
                boolean xFirst = String.valueOf(x.code()).compareTo(String.valueOf(y.code())) <= 0;
                Party a = xFirst ? x : y;
                Party b = xFirst ? y : x;
                pairs.add(new PairPosition(a, b, group(ls, IHB_POSITION, a, b), group(ls, null, a, b),
                    group(ls, RECHARGE, a, b), group(ls, RECHARGE_PENDING, a, b)));
            });
        return pairs;
    }

    /** The lines of one kind (null: the invoices, receivables and payables together) and what they net to. */
    private static LineGroup group(List<Line> pairLines, String kind, Party a, Party b) {
        List<Line> ls = pairLines.stream()
            .filter(l -> kind != null ? kind.equals(l.kind()) : RECEIVABLE.equals(l.kind()) || PAYABLE.equals(l.kind()))
            .toList();
        BigDecimal aOwesB = BigDecimal.ZERO;
        for (Line l : ls) aOwesB = l.debtor().id().equals(a.id()) ? aOwesB.add(l.amount()) : aOwesB.subtract(l.amount());
        Net net = aOwesB.signum() == 0 ? new Net(null, null, BigDecimal.ZERO)
            : aOwesB.signum() > 0 ? new Net(a, b, aOwesB) : new Net(b, a, aOwesB.negate());
        return new LineGroup(ls, net);
    }

    // ------------------------------------------------------------------------------------------------------------

    private static Party party(Map<UUID, LegalEntity> byId, UUID id) {
        LegalEntity e = id != null ? byId.get(id) : null;
        return new Party(id, e != null ? e.getEntityCode() : null, e != null ? e.getEntityName() : null);
    }

    /** Both are two different entities of the group. */
    private static boolean between(Map<UUID, LegalEntity> byId, UUID a, UUID b) {
        return a != null && b != null && !a.equals(b) && byId.containsKey(a) && byId.containsKey(b);
    }

    private static BigDecimal owed(BigDecimal outstanding, BigDecimal net) {
        return outstanding != null ? outstanding : net != null ? net : BigDecimal.ZERO;
    }

    private static BigDecimal sum(List<Line> lines) {
        return lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
