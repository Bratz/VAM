package com.bank.vam.fx.exposure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A forecast line that projects an open invoice is not exposure on top of that invoice: the invoice is read live.
 * The pattern engine projects open salary/tax/utility payables ("payable:{id}", like the aging engine), so leaving
 * out only AGING lines counted those payables twice.
 */
class ExposureSourcesTest {

    @Test
    void linesThatProjectAnInvoiceAreLeftOutWhicheverEngineMadeThem() {
        assertThat(ExposureSources.projectsInvoice("payable:3f2c1a9e-0000-0000-0000-000000000001")).isTrue();    // aging or pattern
        assertThat(ExposureSources.projectsInvoice("receivable:3f2c1a9e-0000-0000-0000-000000000002")).isTrue(); // aging
    }

    /** The queries read the shared "not open" lists (hedge links use them too) as the literals they always had. */
    @Test
    void notOpenStatusesAreTheQueriesLiterals() {
        assertThat(ExposureSources.literals(ExposureSources.PAYABLE_NOT_OPEN))
                .isEqualTo("('PAID','CANCELLED','NETTED','REJECTED','DRAFT')");
        assertThat(ExposureSources.literals(ExposureSources.RECEIVABLE_NOT_OPEN))
                .isEqualTo("('PAID','CANCELLED','NETTED','WRITTEN_OFF','DRAFT','COBO_REJECTED')");
    }

    @Test
    void linesNoInvoiceCarriesStay() {
        assertThat(ExposureSources.projectsInvoice("adjustment:3f2c1a9e-0000-0000-0000-000000000003")).isFalse(); // manual
        assertThat(ExposureSources.projectsInvoice(null)).isFalse();                                              // model / driver
        assertThat(ExposureSources.projectsInvoice("")).isFalse();
    }
}
