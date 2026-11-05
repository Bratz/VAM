package com.bank.vam.fx.deal;

import com.bank.vam.fx.deal.FxDealModels.DealView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Builds the blotter view of a deal: its stored fields plus today's mark and whether its confirmation is overdue. */
@Component
@RequiredArgsConstructor
public class DealViewAssembler {

    private final FxMtmService mtm;
    private final FxConfirmationService confirmations;

    public DealView view(FxDeal d) {
        // Only deals still in force have a mark worth showing.
        Optional<FxMtmService.Mark> mark = DealStatus.OPEN.contains(d.getStatus()) ? mtm.mark(d) : Optional.empty();
        return DealView.of(d, mark, confirmations.overdue(d));
    }
}
