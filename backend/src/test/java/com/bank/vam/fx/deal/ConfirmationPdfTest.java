package com.bank.vam.fx.deal;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ConfirmationPdfTest {

    private static String asText(byte[] pdf) {
        return new String(pdf, StandardCharsets.ISO_8859_1);
    }

    @Test
    void producesAWellFormedPdfWhoseCrossReferenceTablePointsAtEveryObject() {
        String pdf = asText(ConfirmationPdf.render("FX TRADE CONFIRMATION\nYou buy : EUR 100,000.00"));

        assertThat(pdf).startsWith("%PDF-1.4").endsWith("%%EOF\n");
        int startxref = Integer.parseInt(pdf.substring(pdf.lastIndexOf("startxref") + 10, pdf.lastIndexOf("%%EOF")).trim());
        assertThat(pdf.substring(startxref)).startsWith("xref");

        // Each of the five objects must start exactly where the table says.
        String[] entries = pdf.substring(startxref).split("\n");
        for (int obj = 1; obj <= 5; obj++) {
            int offset = Integer.parseInt(entries[2 + obj].substring(0, 10));
            assertThat(pdf.substring(offset)).startsWith(obj + " 0 obj");
        }
    }

    @Test
    void carriesTheTextAndEscapesTheCharactersPdfTreatsAsSyntax() {
        String pdf = asText(ConfirmationPdf.render("Rate : 1.10 (EUR/USD) \\ done"));

        assertThat(pdf).contains("(Rate : 1.10 \\(EUR/USD\\) \\\\ done) Tj");
    }

    @Test
    void replacesCharactersOutsideLatin1AndWrapsLongLines() {
        String pdf = asText(ConfirmationPdf.render("Name: \u20AC\u4E2D\n" + "x".repeat(200)));

        assertThat(pdf).contains("(Name: ??) Tj");
        assertThat(pdf).contains("(" + "x".repeat(88) + ") Tj");
        assertThat(pdf).doesNotContain("x".repeat(89));
    }

    @Test
    void longTextRunsOnToMorePagesOf68LinesAndNothingIsLost() {
        String pdf = asText(ConfirmationPdf.render("line\n".repeat(500)));

        // 500 lines plus the empty one after the last newline: 501 over pages of 68.
        assertThat(pdf).contains("/Count 8");
        assertThat(pdf.split("Tj", -1).length - 1).isEqualTo(501);
    }

    @Test
    void aFormFeedStartsANewPageAndLandscapeIsWider() {
        String pdf = asText(ConfirmationPdf.render("first\fsecond", true));

        assertThat(pdf).contains("/Count 2").contains("/MediaBox [0 0 842 595]");
        assertThat(pdf).contains("(first) Tj").contains("(second) Tj");
    }
}
