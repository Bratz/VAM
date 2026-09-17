package com.bank.vam.service.fileingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReceivablesCsvTransformTest {

    private final ReceivablesCsvTransform transform = new ReceivablesCsvTransform();

    @TempDir
    Path tempDir;

    @Test
    void parsesRowsAndSumsTheControlTotal() throws Exception {
        Path file = tempDir.resolve("known.csv");
        Files.writeString(file, """
                amount,currency,viban,debtorName,debtorAccount,remittanceInformation,endToEndId
                100.00,AED,VIBAN001,John Doe,ACC1,Invoice 1,REF-1
                50.50,AED,VIBAN002,Jane Smith,ACC2,Invoice 2,REF-2
                """);

        TransformOutput output = transform.transform(file);

        assertThat(output.sourceRowCount()).isEqualTo(2);
        assertThat(output.sourceControlTotal()).isEqualByComparingTo("150.50");
        assertThat(output.rows()).hasSize(2);
        assertThat(output.rows().get(0).amount()).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void skipsBlankLines() throws Exception {
        Path file = tempDir.resolve("blanks.csv");
        Files.writeString(file, """
                amount,currency,viban,debtorName,debtorAccount,remittanceInformation,endToEndId
                100.00,AED,VIBAN001,John Doe,ACC1,Invoice 1,REF-1

                50.50,AED,VIBAN002,Jane Smith,ACC2,Invoice 2,REF-2
                """);

        TransformOutput output = transform.transform(file);

        assertThat(output.sourceRowCount()).isEqualTo(2);
    }

    @Test
    void rejectsAnUnrecognizedHeader() throws Exception {
        Path file = tempDir.resolve("unrecognized.csv");
        Files.writeString(file, "InvoiceAmount;Curr;AccountRef\n100.00;AED;VIBAN001\n");

        assertThatThrownBy(() -> transform.transform(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unrecognized format");
    }

    /**
     * Regression test for a real production bug: uploading a JPEG to Receivables threw
     * UncheckedIOException (MalformedInputException from Files.readAllLines's UTF-8 decoding),
     * which IngestOrchestrator.runJob's catch(IllegalArgumentException) doesn't catch -- so it
     * skipped the escalate-to-agent path entirely and dead-ended the job at BLOCKED with a raw
     * file-path error and no ticket ever filed. A non-text file is just another unrecognized
     * shape and must throw the same exception type as a bad header does.
     */
    @Test
    void unreadableBinaryContentIsTreatedAsAnUnrecognizedFormat() throws Exception {
        Path file = tempDir.resolve("photo.jpeg");
        // Invalid UTF-8 byte sequences (a lone continuation byte, an overlong encoding) --
        // Files.readAllLines(path, UTF_8) throws MalformedInputException on these.
        Files.write(file, new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, (byte) 0x80, (byte) 0x80 });

        assertThatThrownBy(() -> transform.transform(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unrecognized format");
    }

    @Test
    void emptyFileProducesEmptyOutput() throws Exception {
        Path file = tempDir.resolve("empty.csv");
        Files.writeString(file, "");

        TransformOutput output = transform.transform(file);

        assertThat(output.sourceRowCount()).isZero();
        assertThat(output.rows()).isEmpty();
    }
}
