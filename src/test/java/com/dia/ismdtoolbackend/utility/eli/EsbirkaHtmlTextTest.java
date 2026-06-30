package com.dia.ismdtoolbackend.utility.eli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class EsbirkaHtmlTextTest {

    @Test
    void toPlainText_nullStaysNull() {
        assertNull(EsbirkaHtmlText.toPlainText(null));
    }

    @Test
    void toPlainText_stripsTags() {
        assertEquals("Toto je text.",
                EsbirkaHtmlText.toPlainText("<p>Toto je <strong>text</strong>.</p>"));
    }

    @Test
    void toPlainText_decodesEntitiesAndNormalizesNbsp() {
        // &nbsp; (U+00A0) must come back as a regular space, not an invisible char.
        String out = EsbirkaHtmlText.toPlainText("&sect;&nbsp;2 a &sect;&nbsp;3");
        assertEquals("§ 2 a § 3", out);
        assertFalse(out.contains("\u00A0"), "NBSP should be normalized away");
    }

    @Test
    void toPlainText_preservesBlockLineBreaks() {
        assertEquals("První odstavec.\nDruhý odstavec.",
                EsbirkaHtmlText.toPlainText("<p>První odstavec.</p><p>Druhý odstavec.</p>"));
    }

    @Test
    void toPlainText_blankHtmlBecomesEmpty() {
        assertEquals("", EsbirkaHtmlText.toPlainText("   "));
    }
}