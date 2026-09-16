package com.bank.vam.service.fileingest;

import java.math.BigDecimal;

/**
 * One row's worth of fields in the shape the target processor needs, using ISO 20022 vocabulary
 * (debtor/creditor/remittanceInformation/endToEndId) for the party fields rather than an ad-hoc
 * naming — this mirrors InwardPaymentRequest's fields (the semantic content ISO 20022 CAMT.054
 * carries) without round-tripping through XML. {@code viban} is always this app's own Virtual
 * Account in the transaction — its ISO 20022 role depends on direction, not the field itself:
 * the creditor for Receivables (money arriving), the debtor for Payables/Payments (money leaving).
 * Only the pair matching the actual direction is populated per row; the other stays null — see
 * PayablesProcessor/PaymentsProcessor/ReceivablesProcessor's own doc comments. (Previously
 * debtorName/debtorAccount were overloaded to carry the creditor's data for outward flows — a
 * real, not just cosmetic, naming bug; fixed by giving creditor its own fields instead.)
 */
public record TransformedRow(
        int sourceRowNumber,
        BigDecimal amount,
        String currency,
        String viban,
        String debtorName,
        String debtorAccount,
        String creditorName,
        String creditorAccount,
        String remittanceInformation,
        String endToEndId
) {
}
