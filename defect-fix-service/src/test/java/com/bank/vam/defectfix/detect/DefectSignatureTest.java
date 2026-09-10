package com.bank.vam.defectfix.detect;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefectSignatureTest {

    @Test
    void parseIsTheInverseOfTheEmbeddedForm() {
        DefectSignature original = new DefectSignature("frontend-lint", "no-unused-vars:src/Foo.tsx");

        DefectSignature parsed = DefectSignature.parse(original.source() + "|" + original.key());

        assertThat(parsed).isEqualTo(original);
    }

    @Test
    void rejectsTextWithNoSeparator() {
        assertThatThrownBy(() -> DefectSignature.parse("not-a-valid-signature"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
