package com.bank.vam.fx.deal;

import com.bank.vam.entity.Corporate;
import com.bank.vam.repository.CorporateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** What about FX deals needs the treasurer's attention today, for the dashboard's action queue. */
@Service
@RequiredArgsConstructor
public class FxDealAttentionService {

    public enum Type { CONFIRMATION_PENDING, CONFIRMATION_DISPUTED, SETTLEMENT_FAILED, FUNDING_NEEDED, LIMIT_WARNING }

    public record Item(Type type, String severity, UUID corporateId, String corporateName, UUID dealId,
                       String dealRef, String headline, String detail, LocalDate dueDate) {
    }

    private final FxDealRepository deals;
    private final CorporateRepository corporates;
    private final FxConfirmationService confirmations;
    private final HeldCash cash;
    private final FxLimitService limits;
    private final FxSettlementRepository settlements;

    /** @param corporateId one corporate, or null for all */
    public List<Item> items(UUID corporateId) {
        List<FxDeal> open = corporateId == null ? deals.findByStatusIn(DealStatus.OPEN)
                : deals.findByCorporateIdAndStatusIn(corporateId, DealStatus.OPEN);
        Map<UUID, String> names = corporates.findAllById(open.stream().map(FxDeal::getCorporateId).distinct().toList()).stream()
                .collect(Collectors.toMap(Corporate::getId, Corporate::getLegalName));
        Function<FxDeal, String> name = d -> names.getOrDefault(d.getCorporateId(), "Corporate");

        LocalDate today = LocalDate.now();
        List<Item> out = new ArrayList<>();
        for (FxDeal d : open) {
            if (d.getConfirmationStatus() == ConfirmationStatus.ISSUED) {
                boolean late = confirmations.overdue(d);
                out.add(new Item(Type.CONFIRMATION_PENDING, late ? "high" : "medium", d.getCorporateId(), name.apply(d),
                        d.getId(), d.getDealRef(), "Confirm FX deal " + d.getDealRef() + " · " + name.apply(d),
                        (late ? "Overdue: " : "") + "the bank's confirmation is waiting for you to check and confirm. It cannot settle until you do.",
                        d.getValueDate()));
            } else if (d.getConfirmationStatus() == ConfirmationStatus.DISPUTED) {
                out.add(new Item(Type.CONFIRMATION_DISPUTED, "medium", d.getCorporateId(), name.apply(d), d.getId(), d.getDealRef(),
                        "FX confirmation disputed · " + d.getDealRef(),
                        "Waiting for the bank to resolve: " + d.getDisputeReason(), d.getValueDate()));
            }
            if (d.getStatus() == DealStatus.SETTLEMENT_FAILED) {
                out.add(new Item(Type.SETTLEMENT_FAILED, "high", d.getCorporateId(), name.apply(d), d.getId(), d.getDealRef(),
                        "FX settlement failed · " + d.getDealRef() + " · " + name.apply(d),
                        "Settlement could not complete on " + d.getValueDate() + ". Fund the " + d.getSellCurrency()
                                + " account, or roll or close out the deal.", d.getValueDate()));
            } else if (!d.getValueDate().isAfter(today.plusDays(2)) && d.remainingBase().signum() > 0) {
                BigDecimal need = d.getSellAmount().multiply(d.remainingBase()).divide(d.baseAmount(), 2, java.math.RoundingMode.HALF_UP);
                BigDecimal held = cash.of(d.getCorporateId(), d.getSellCurrency());
                if (held.compareTo(need) < 0) {
                    out.add(new Item(Type.FUNDING_NEEDED, d.getValueDate().isAfter(today) ? "medium" : "high", d.getCorporateId(),
                            name.apply(d), d.getId(), d.getDealRef(), "Fund " + d.getSellCurrency() + " for " + d.getDealRef() + " · " + name.apply(d),
                            "Settles " + d.getValueDate() + " and needs " + d.getSellCurrency() + " " + need.toPlainString()
                                    + "; the corporate holds " + held.toPlainString() + ".", d.getValueDate()));
                }
            }
        }
        // FX lines and entity sub-limits past their warning (80%) or critical (95%) threshold. dealId carries the
        // limit's id so each alert is distinct; there is no deal behind it.
        List<Corporate> scope = corporateId == null ? corporates.findAll() : corporates.findAllById(List.of(corporateId));
        for (Corporate c : scope) {
            // Settlement debits an account even when it is short; each account left below zero needs funding.
            java.util.Set<UUID> seen = new java.util.HashSet<>();
            for (Object[] r : settlements.overdrawn(c.getId())) {
                FxSettlement s = (FxSettlement) r[0];
                if (seen.add(s.getDebitAccountId())) {
                    out.add(new Item(Type.FUNDING_NEEDED, "high", c.getId(), c.getLegalName(), s.getDealId(), (String) r[2],
                            "Account overdrawn by FX settlement: " + s.getDebitAccountLabel(),
                            "The " + s.getSellCurrency() + " account stands at " + ((BigDecimal) r[1]).toPlainString() + " after "
                                    + r[2] + " settled. Pay in to bring it back above zero.", today));
                }
            }
            for (FxLimitService.Alert a : limits.alerts(c.getId())) {
                out.add(new Item(Type.LIMIT_WARNING, a.critical() ? "high" : "medium", c.getId(), c.getLegalName(),
                        a.limitId(), null, a.what() + " is " + a.percentUsed().stripTrailingZeros().toPlainString() + "% used",
                        a.usage().used().toPlainString() + " of " + a.usage().limit().toPlainString() + " " + a.currency()
                                + " used, " + a.usage().headroom().max(BigDecimal.ZERO).toPlainString() + " left. Deals that do not fit are declined.",
                        today));
            }
        }
        return out;
    }
}
