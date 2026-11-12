package com.bank.vam.statement.parse;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One statement line. {@code amount} is positive; {@code creditDebit} is the effect on the account. A reversal (MT RC/RD,
 * camt RvslInd) keeps its effect direction (RC = reversal of a credit = D) with {@code reversal} set.
 */
public record ParsedLine(
        LocalDate valueDate,
        LocalDate bookingDate,       // MT :61: entry date (year inferred), camt BookgDt; may be null
        BigDecimal amount,
        CreditDebit creditDebit,
        boolean reversal,
        String bankReference,        // MT :61: after "//", camt AcctSvcrRef
        String endToEndId,           // camt Refs/EndToEndId, MT :86: EREF+ or /EREF/
        String customerReference,    // MT :61: before "//" (NONREF = none) else :86: KREF+, camt Refs/InstrId else PmtInfId
        String remittanceText,
        String transactionCode) {    // MT :61: type (e.g. NTRF), camt BkTxCd Domn/Fmly/SubFmly (e.g. PMNT/RCDT/ESCT) else Prtry

    public enum CreditDebit { C, D }
}
