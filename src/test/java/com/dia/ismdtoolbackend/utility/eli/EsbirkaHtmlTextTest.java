package com.dia.ismdtoolbackend.utility.eli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EsbirkaHtmlTextTest {

    @Test
    void toPlainText_nullStaysNull() {
        assertThat(EsbirkaHtmlText.toPlainText(null)).isNull();
    }

    @Test
    void toPlainText_stripsTags() {
        assertThat(EsbirkaHtmlText.toPlainText("<p>Toto je <strong>text</strong>.</p>")).isEqualTo("Toto je text.");
    }

    @Test
    void toPlainText_decodesEntitiesAndNormalizesNbsp() {
        // &nbsp; (U+00A0) must come back as a regular space, not an invisible char.
        String out = EsbirkaHtmlText.toPlainText("&sect;&nbsp;2 a &sect;&nbsp;3");
        assertThat(out).isEqualTo("§ 2 a § 3");
        assertThat(out).as("NBSP should be normalized away").doesNotContain("\u00A0");
    }

    @Test
    void toPlainText_preservesBlockLineBreaks() {
        assertThat(EsbirkaHtmlText.toPlainText("<p>První odstavec.</p><p>Druhý odstavec.</p>"))
                .isEqualTo("První odstavec.\nDruhý odstavec.");
    }

    @Test
    void toPlainText_blankHtmlBecomesEmpty() {
        assertThat(EsbirkaHtmlText.toPlainText("   ")).isEqualTo("");
    }
}