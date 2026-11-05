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

    public enum Type { CONFIRMATION_PENDING, CONFIRMATION_DISPUTED, SETTLEMENT_FAILED, FUNDING_NEEDED }

    public record Item(Type type, String severity, UUID corporateId, String corporateName, UUID dealId,
                       String dealRef, String headline, String detail, LocalDate dueDate) {
    }

    private final FxDealRepository deals;
    private final CorporateRepository corporates;
    private final FxConfirmationService confirmations;
    private final HeldCash cash;

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
        return out;
    }
}
