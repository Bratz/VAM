package com.bank.vam.fileingest.signature;

import com.bank.vam.fileingest.agent.FileStructureProfile;
import com.bank.vam.fileingest.entity.IngestDomain;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FormatSignatureServiceTest {

    private final FormatSignatureService service = new FormatSignatureService(null);

    @Test
    void sameShapeHashesTheSameRegardlessOfColumnCase() {
        FileStructureProfile lower = new FileStructureProfile(List.of("amount", "currency"), ",", "amount", null);
        FileStructureProfile upper = new FileStructureProfile(List.of("AMOUNT", "CURRENCY"), ",", "AMOUNT", null);

        assertThat(service.hash("cust-1", IngestDomain.RECEIVABLES, lower))
                .isEqualTo(service.hash("cust-1", IngestDomain.RECEIVABLES, upper));
    }

    @Test
    void differentCustomersHashDifferently() {
        FileStructureProfile profile = new FileStructureProfile(List.of("amount", "currency"), ",", "amount", null);

        assertThat(service.hash("cust-1", IngestDomain.RECEIVABLES, profile))
                .isNotEqualTo(service.hash("cust-2", IngestDomain.RECEIVABLES, profile));
    }

    @Test
    void differentDelimiterHashesDifferently() {
        FileStructureProfile comma = new FileStructureProfile(List.of("amount"), ",", "amount", null);
        FileStructureProfile semicolon = new FileStructureProfile(List.of("amount"), ";", "amount", null);

        assertThat(service.hash("cust-1", IngestDomain.RECEIVABLES, comma))
                .isNotEqualTo(service.hash("cust-1", IngestDomain.RECEIVABLES, semicolon));
    }
}
