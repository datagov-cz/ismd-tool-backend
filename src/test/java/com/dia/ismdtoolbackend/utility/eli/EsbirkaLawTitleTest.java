package com.dia.ismdtoolbackend.utility.eli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Title shapes seen in e-Sbírka search responses (recorded 2026-10-19). */
class EsbirkaLawTitleTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            Zákon o civilním letectví                                    | Zákon          | o civilním letectví
            Zákon České národní rady o daních z příjmů                   | Zákon          | o daních z příjmů
            Zákon občanský zákoník                                       | Zákon          | občanský zákoník
            Zákon o katastru nemovitostí (katastrální zákon)             | Zákon          | o katastru nemovitostí (katastrální zákon)
            Zákon, kterým se mění zákon č. 49/1997 Sb., o civilním letectví | Zákon       | kterým se mění zákon č. 49/1997 Sb., o civilním letectví
            Vyhláška Ministerstva dopravy a spojů, kterou se provádí zákon | Vyhláška     | kterou se provádí zákon
            Vyhláška ministra financí o poplatcích                       | Vyhláška       | o poplatcích
            Nařízení vlády, kterým se stanoví minimální mzda              | Nařízení vlády | kterým se stanoví minimální mzda
            Ústavní zákon České národní rady o Ústavě                    | Ústavní zákon  | o Ústavě
            Sdělení Ministerstva zahraničních věcí o přijetí Protokolu   | Sdělení        | o přijetí Protokolu
            Nález Ústavního soudu ze dne 1. října 2019 sp. zn. Pl. ÚS 5/19 | Nález        | ze dne 1. října 2019 sp. zn. Pl. ÚS 5/19
            Usnesení předsednictva České národní rady o vyhlášení voleb  | Usnesení       | o vyhlášení voleb
            """)
    void splitsActTypeIssuerAndSubject(String nazev, String actType, String subject) {
        EsbirkaLawTitle title = EsbirkaLawTitle.parse(nazev);

        assertThat(title.actType()).isEqualTo(actType);
        assertThat(title.subject()).isEqualTo(subject);
    }

    @Test
    void titleWithoutAKnownActTypeIsAllSubject() {
        EsbirkaLawTitle title = EsbirkaLawTitle.parse("Zákoník práce");

        assertThat(title.actType()).isNull();
        assertThat(title.subject()).isEqualTo("Zákoník práce");
    }

    @Test
    void displayNameIsTheCzechCitationForm() {
        assertThat(EsbirkaLawTitle.displayName("49/1997 Sb.", "Zákon o civilním letectví"))
                .isEqualTo("Zákon č. 49/1997 Sb., o civilním letectví");
        assertThat(EsbirkaLawTitle.displayName("586/1992 Sb.", "Zákon České národní rady o daních z příjmů"))
                .isEqualTo("Zákon č. 586/1992 Sb., o daních z příjmů");
        assertThat(EsbirkaLawTitle.displayName("89/2012 Sb.", "Zákon občanský zákoník"))
                .isEqualTo("Zákon č. 89/2012 Sb., občanský zákoník");
        assertThat(EsbirkaLawTitle.displayName("108/1997 Sb.",
                        "Vyhláška Ministerstva dopravy a spojů, kterou se provádí zákon"))
                .isEqualTo("Vyhláška č. 108/1997 Sb., kterou se provádí zákon");
    }

    @Test
    void displayNameWithoutActTypeLeadsWithTheCode() {
        assertThat(EsbirkaLawTitle.displayName("65/1965 Sb.", "Zákoník práce")).isEqualTo("65/1965 Sb., Zákoník práce");
    }

    @Test
    void displayNameWithoutTitleKeepsTodaysLabel() {
        // SPARQL rows carry no title.
        assertThat(EsbirkaLawTitle.displayName("49/1997 Sb.", null)).isEqualTo("Zákon č. 49/1997 Sb.");
        assertThat(EsbirkaLawTitle.displayName("49/1997 Sb.", " ")).isEqualTo("Zákon č. 49/1997 Sb.");
    }

    @Test
    void foldDropsDiacriticsCaseAndExtraSpaces() {
        assertThat(EsbirkaLawTitle.fold("  Zákoník   PRÁCE ")).isEqualTo("zakonik prace");
        assertThat(EsbirkaLawTitle.fold(null)).isEqualTo("");
    }
}