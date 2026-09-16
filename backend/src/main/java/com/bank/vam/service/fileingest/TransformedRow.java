package com.bank.vam.service.fileingest;

import java.math.BigDecimal;

/**
 * One row's worth of fields in the shape the target processor needs — for
 * Receivables this mirrors InwardPaymentRequest's fields (the semantic
 * content ISO 20022 CAMT.054 carries) without round-tripping through XML.
 * Reused as-is (field names kept generic) for Payables/Payments, each
 * reinterpreting the fields for an outbound flow — see PayablesProcessor/
 * PaymentsProcessor's own doc comments.
 */
public record TransformedRow(
        int sourceRowNumber,
        BigDecimal amount,
        String currency,
        String viban,
        String debtorName,
        String debtorAccount,
        String remittanceInfo,
        String reference
) {
}
