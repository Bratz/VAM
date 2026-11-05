package com.bank.vam.fx.exposure;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExposureCsvParserTest {

    private static final UUID CORP = UUID.randomUUID();

    private static ExposureCsvParser.Result parse(String csv) throws IOException {
        return ExposureCsvParser.parse(new StringReader(csv), CORP);
    }

    @Test
    void readsExposuresAndHedgesWithOptionalColumnsAndQuotedCommas() throws IOException {
        ExposureCsvParser.Result r = parse("""
                kind,currency,amount,valueDate,reference,description
                EXPOSURE,eur,250000.5,2027-01-15,ERP-1,"Rotterdam, Q1 shipment"
                hedge,EUR,-100000,2027-01-15,FWD-9,
                """);

        assertThat(r.errors()).isEmpty();
        assertThat(r.entries()).hasSize(2);
        FxExposureEntry first = r.entries().get(0);
        assertThat(first.getKind()).isEqualTo(FxExposureEntry.Kind.EXPOSURE);
        assertThat(first.getCurrency()).isEqualTo("EUR");
        assertThat(first.getAmount()).isEqualByComparingTo("250000.50");
        assertThat(first.getDescription()).isEqualTo("Rotterdam, Q1 shipment");
        assertThat(first.getCorporateId()).isEqualTo(CORP);
        assertThat(r.entries().get(1).getKind()).isEqualTo(FxExposureEntry.Kind.HEDGE);
        assertThat(r.entries().get(1).getReference()).isEqualTo("FWD-9");
        assertThat(r.entries().get(1).getDescription()).isNull();
    }

    @Test
    void worksWithOnlyTheRequiredColumnsAndAByteOrderMark() throws IOException {
        ExposureCsvParser.Result r = parse("﻿Kind,Currency,Amount,ValueDate\nEXPOSURE,USD,1000,2027-02-01\n");

        assertThat(r.errors()).isEmpty();
        assertThat(r.entries()).hasSize(1);
    }

    @Test
    void reportsEveryBadRowWithItsLineNumberAndImportsNone() throws IOException {
        ExposureCsvParser.Result r = parse("""
                kind,currency,amount,valueDate
                EXPOSURE,EUR,100,2027-01-15
                FORWARD,EUR,100,2027-01-15
                EXPOSURE,EURO,100,2027-01-15
                EXPOSURE,EUR,abc,2027-01-15
                EXPOSURE,EUR,0,2027-01-15
                EXPOSURE,EUR,100,15/01/2027
                """);

        assertThat(r.errors()).hasSize(5);
        assertThat(r.errors().get(0)).startsWith("Line 3:").contains("kind");
        assertThat(r.errors().get(1)).startsWith("Line 4:").contains("3-letter");
        assertThat(r.errors().get(2)).startsWith("Line 5:").contains("not a number");
        assertThat(r.errors().get(3)).startsWith("Line 6:").contains("zero");
        assertThat(r.errors().get(4)).startsWith("Line 7:");
    }

    @Test
    void rejectsAnEmptyFileAMissingColumnAndAHeaderOnlyFile() throws IOException {
        assertThat(parse("").errors()).containsExactly("The file is empty");
        assertThat(parse("kind,currency,amount\nEXPOSURE,EUR,1\n").errors().get(0)).contains("valueDate");
        assertThat(parse("kind,currency,amount,valueDate\n").errors()).containsExactly("The file has a header but no rows");
    }
}
