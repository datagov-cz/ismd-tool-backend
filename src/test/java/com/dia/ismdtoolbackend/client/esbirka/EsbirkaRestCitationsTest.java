package com.dia.ismdtoolbackend.client.esbirka;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rules the recorded laws cannot tell apart: there, every container cites the document itself, which
 * stripping the act reference already empties. These synthetic inputs pin each rule on its own.
 */
class EsbirkaRestCitationsTest {

    private static final String ELI = "/eli/cz/sb/1997/49/2025-11-01/dokument";
    private final EsbirkaRestCitations zakon = EsbirkaRestCitations.forDocument("Zákon č. 49/1997 Sb.");

    @Test
    void virtualContainersCarryNoCitationWhateverTheyCite() {
        assertThat(zakon.ownCitation("Virtual_Prilohy", ELI + "/prilohy", "Přílohy zákona č. 49/1997 Sb.")).isNull();
        assertThat(zakon.ownCitation("Priloha", ELI + "/prilohy/priloha_1", "Přílohy zákona č. 49/1997 Sb."))
                .isEqualTo("Přílohy");
    }

    @Test
    void documentCitationWithoutNumberIsDroppedByEquality() {
        EsbirkaRestCitations unnumbered = EsbirkaRestCitations.forDocument("Ústavní listina");

        assertThat(unnumbered.ownCitation("Prefix", ELI + "/prefix/frag_1", "Ústavní listina")).isNull();
        assertThat(unnumbered.ownCitation("Clanek", ELI + "/norma/cl_1", "Čl. 1 Ústavní listina"))
                .isEqualTo("Čl. 1 Ústavní listina");
    }

    @Test
    void citationOutsideTheDocumentPatternIsKept() {
        assertThat(zakon.ownCitation("Poznamka", ELI + "/prilohy/priloha_1/frag_9", "pozn. 1")).isEqualTo("pozn. 1");
    }

    @Test
    void onlyFragGroupersRepeatingTheirParentAreDropped() {
        assertThat(EsbirkaRestCitations.repeatsParent(ELI + "/norma/par_1/frag_2", "§ 1 zákona č. 49/1997 Sb.", "§ 1 zákona č. 49/1997 Sb."))
                .isTrue();
        assertThat(EsbirkaRestCitations.repeatsParent(ELI + "/norma/par_1/odst_1", "§ 1 zákona č. 49/1997 Sb.", "§ 1 zákona č. 49/1997 Sb."))
                .isFalse();
        assertThat(EsbirkaRestCitations.repeatsParent(ELI + "/norma/par_1/frag_2", "pozn. 1", "§ 1 zákona č. 49/1997 Sb."))
                .isFalse();
    }
}
