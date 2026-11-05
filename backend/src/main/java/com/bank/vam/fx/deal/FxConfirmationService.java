package com.bank.vam.fx.deal;

import com.bank.vam.config.FxDealProperties;
import com.bank.vam.exception.BusinessException;
import com.bank.vam.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The corporate's side of trade confirmation. The back office issues a confirmation when a deal is booked
 * (see {@link FxDealService#book}); the corporate checks it against the trade it agreed and affirms or
 * disputes it. A dispute goes back to the bank, which either re-issues a corrected confirmation or cancels
 * the deal. Settlement waits for an affirmed confirmation.
 */
@Service
@RequiredArgsConstructor
public class FxConfirmationService {

    /** What the bank decides after a dispute. */
    public enum Resolution { REISSUE, CANCEL }

    private final FxDealRepository deals;
    private final FxDealService dealService;
    private final FxEvents log;
    private final FxDealProperties props;

    /** Affirming twice is harmless: the second call returns the deal as it is. */
    @Transactional
    public FxDeal affirm(UUID dealId) {
        FxDeal d = find(dealId);
        if (d.getConfirmationStatus() == ConfirmationStatus.AFFIRMED) {
            return d;
        }
        requireStatus(d, ConfirmationStatus.ISSUED, "affirmed");
        d.setConfirmationStatus(ConfirmationStatus.AFFIRMED);
        d.setConfirmedAt(LocalDateTime.now());
        d.setConfirmedBy(FxEvents.actor());
        log.note(d, "CONFIRMED_BY_CLIENT", "Client confirmed the trade as issued");
        return d;
    }

    @Transactional
    public FxDeal dispute(UUID dealId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("FX_DISPUTE_INVALID", "Say what does not match the trade you agreed");
        }
        if (reason.length() > 300) {
            throw new BusinessException("FX_DISPUTE_INVALID", "Keep the reason to 300 characters");
        }
        FxDeal d = find(dealId);
        requireStatus(d, ConfirmationStatus.ISSUED, "disputed");
        d.setConfirmationStatus(ConfirmationStatus.DISPUTED);
        d.setDisputeReason(reason.trim());
        log.note(d, "DISPUTED_BY_CLIENT", "Client disputed the confirmation: " + reason.trim());
        return d;
    }

    /** The bank's answer to a dispute. There is no screen for this yet; it is an API for the back office. */
    @Transactional
    public FxDeal resolve(UUID dealId, Resolution resolution, String note) {
        FxDeal d = find(dealId);
        requireStatus(d, ConfirmationStatus.DISPUTED, "resolved");
        String detail = note == null || note.isBlank() ? "" : ": " + note.trim();
        if (resolution == Resolution.CANCEL) {
            dealService.transition(d, DealStatus.CANCELLED, "Cancelled by the bank after a dispute" + detail);
        } else {
            d.setConfirmationStatus(ConfirmationStatus.ISSUED);
            d.setConfirmationIssuedAt(LocalDateTime.now());
            d.setDisputeReason(null);
            log.note(d, "REISSUED_BY_BANK", "Back office re-issued the confirmation after the dispute" + detail);
        }
        return d;
    }

    /** Issued more than the allowed hours ago and still unanswered. */
    public boolean overdue(FxDeal d) {
        return d.getConfirmationStatus() == ConfirmationStatus.ISSUED && d.getConfirmationIssuedAt() != null
                && d.getConfirmationIssuedAt().plusHours(props.getConfirmationDueHours()).isBefore(LocalDateTime.now());
    }

    private FxDeal find(UUID id) {
        return deals.findById(id).orElseThrow(() -> new ResourceNotFoundException("Deal not found: " + id));
    }

    private static void requireStatus(FxDeal d, ConfirmationStatus expected, String verb) {
        if (d.getConfirmationStatus() != expected) {
            throw new BusinessException("FX_CONFIRMATION_STATE",
                    "This confirmation cannot be " + verb + ": it is " + d.getConfirmationStatus());
        }
    }
}
