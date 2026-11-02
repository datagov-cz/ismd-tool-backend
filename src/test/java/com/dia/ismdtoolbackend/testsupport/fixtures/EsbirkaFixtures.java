package com.dia.ismdtoolbackend.testsupport.fixtures;

import com.dia.ismdtoolbackend.models.eli.FragmentModel;
import com.dia.ismdtoolbackend.models.eli.LawModel;
import com.dia.ismdtoolbackend.models.eli.LawVersionModel;

import java.time.LocalDate;

/** e-Sbírka ELI IRIs and model rows for tests. */
public final class EsbirkaFixtures {

    /** The canonical ELI base for Czech legislation. */
    public static final String ELI_CZ = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz";
    /** The canonical base of the Sbírka zákonů; a law IRI is {@code SB + rok + "/" + číslo}. */
    public static final String SB = ELI_CZ + "/sb/";
    /** The pre-.gov.cz host, still present in stored concept sources. */
    public static final String LEGACY_SB = "https://opendata.eselpoint.cz/esel-esb/eli/cz/sb/";

    /** Act 49/1997 Sb. (civil aviation). */
    public static final String LAW_49_1997 = lawIri("49", 1997);
    /** Act 187/2006 Sb. */
    public static final String LAW_187_2006 = lawIri("187", 2006);
    /** Act 361/2000 Sb. (road traffic). */
    public static final String LAW_361_2000 = lawIri("361", 2000);

    private EsbirkaFixtures() {
    }

    /** The canonical IRI of law {@code cislo/rok Sb.}. */
    public static String lawIri(String cislo, int rok) {
        return SB + rok + "/" + cislo;
    }

    /** The legacy-host IRI of law {@code cislo/rok Sb.}. */
    public static String legacyLawIri(String cislo, int rok) {
        return LEGACY_SB + rok + "/" + cislo;
    }

    /** Law {@code cislo/rok Sb.} at its canonical IRI. */
    public static LawModel law(String cislo, int rok) {
        return new LawModel(lawIri(cislo, rok), citace(cislo, rok), cislo, rok, "sb");
    }

    /** Law {@code cislo/rok Sb.} carrying a name, as name search returns it. */
    public static LawModel law(String cislo, int rok, String nazev) {
        return new LawModel(lawIri(cislo, rok), citace(cislo, rok), cislo, rok, "sb", nazev);
    }

    /** A consolidated znění valid from {@code ucinnostOd} with no end date. */
    public static LawVersionModel version(String versionIri, LocalDate ucinnostOd, boolean latest) {
        return new LawVersionModel(versionIri, ucinnostOd, null, "KONSOL", latest);
    }

    /** A single § 1 fragment under the version's norma root, with the given body. */
    public static FragmentModel fragment(String versionIri, String body) {
        return new FragmentModel(versionIri + "/dokument/norma/par_1",
                versionIri + "/dokument/norma", "§ 1", "par", "0001", body);
    }

    private static String citace(String cislo, int rok) {
        return cislo + "/" + rok + " Sb.";
    }
}
