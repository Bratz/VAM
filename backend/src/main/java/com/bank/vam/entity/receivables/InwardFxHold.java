package com.bank.vam.entity.receivables;

import com.bank.vam.entity.BaseEntity;
import com.bank.vam.iso20022.dto.Iso20022PaymentDto.InwardPaymentRequest;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An inward credit to a COBO VIBAN that needs a negotiated rate (docs/specs/ihb-fx.md R6b, D8): accepted (PDNG to the
 * sender), nothing posted, until treasury accepts a firm quote and it is posted through the normal inward path. Keeps
 * the inward request as received so it can be replayed. {@code createdAt} is when it arrived.
 */
@Entity
@Table(name = "inward_fx_hold", indexes = {
    @Index(name = "idx_inward_fx_hold_corporate", columnList = "corporate_id, status"),
    @Index(name = "idx_inward_fx_hold_e2e", columnList = "virtual_account_id, end_to_end_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InwardFxHold extends BaseEntity {

    public static final String AWAITING_FX = "AWAITING_FX";
    public static final String POSTED = "POSTED";
    public static final String RETURNED = "RETURNED";

    @Column(name = "corporate_id")
    private UUID corporateId;

    /** The account the credit lands on (treasury's collection account) and its owner: the entity that sells the currency. */
    @Column(name = "virtual_account_id", nullable = false)
    private UUID virtualAccountId;

    @Column(name = "va_number", length = 50)
    private String vaNumber;

    @Column(name = "collector_entity_id")
    private UUID collectorEntityId;

    @Column(name = "settle_currency", length = 3)
    private String settleCurrency;

    @Column(name = "viban_id")
    private UUID vibanId;

    @Column(name = "viban", length = 34)
    private String viban;

    @Column(name = "receivable_id")
    private UUID receivableId;

    @Column(name = "receivable_number", length = 50)
    private String receivableNumber;

    @Column(name = "subsidiary_code", length = 20)
    private String subsidiaryCode;

    // ---- the inward request as received ----
    @Column(name = "message_id", length = 50)
    private String messageId;

    @Column(name = "instruction_id", length = 50)
    private String instructionId;

    @Column(name = "end_to_end_id", length = 50)
    private String endToEndId;

    @Column(name = "amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "creditor_account", length = 50)
    private String creditorAccount;

    @Column(name = "debtor_name", length = 200)
    private String debtorName;

    @Column(name = "debtor_account", length = 50)
    private String debtorAccount;

    @Column(name = "debtor_bic", length = 15)
    private String debtorBic;

    @Column(name = "remittance_info", length = 500)
    private String remittanceInfo;

    @Column(name = "structured_ref", length = 50)
    private String structuredRef;

    @Column(name = "channel", length = 30)
    private String channel;

    // ---- what became of it ----
    /** {@link #AWAITING_FX}, {@link #POSTED} or {@link #RETURNED} (treasury will not convert it). */
    @Column(name = "status", length = 20, nullable = false)
    private String status;

    /** RETURNED: why, by whom, when. */
    @Column(name = "return_reason", length = 500)
    private String returnReason;

    @Column(name = "returned_by", length = 100)
    private String returnedBy;

    @Column(name = "returned_at")
    private LocalDateTime returnedAt;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "transaction_ref", length = 50)
    private String transactionRef;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    public static InwardFxHold of(InwardPaymentRequest r) {
        return InwardFxHold.builder().messageId(r.getMessageId()).instructionId(r.getInstructionId())
            .endToEndId(r.getEndToEndId()).amount(r.getAmount()).currency(r.getCurrency())
            .creditorAccount(r.getCreditorAccount()).debtorName(r.getDebtorName()).debtorAccount(r.getDebtorAccount())
            .debtorBic(r.getDebtorBic()).remittanceInfo(r.getRemittanceInfo()).structuredRef(r.getStructuredRef())
            .channel(r.getChannel()).status(AWAITING_FX).build();
    }

    /** The request again, to post it through the normal inward path. */
    public InwardPaymentRequest toRequest() {
        return InwardPaymentRequest.builder().messageId(messageId).instructionId(instructionId).endToEndId(endToEndId)
            .amount(amount).currency(currency).creditorAccount(creditorAccount).debtorName(debtorName)
            .debtorAccount(debtorAccount).debtorBic(debtorBic).remittanceInfo(remittanceInfo).structuredRef(structuredRef)
            .channel(channel).build();
    }
}
