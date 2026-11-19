package com.dia.ismdtoolbackend.ofn;

/**
 * Names of the OFN slovníky context terms the document builder writes.
 * <p>
 * {@code OfnTermsTest} checks every constant against the bundled context, so a renamed or removed
 * term fails the build instead of silently producing data the gate would drop.
 */
public final class OfnTerms {

    // keys
    public static final String IRI = "iri";
    public static final String TYP = "typ";
    public static final String NAZEV = "název";
    public static final String POPIS = "popis";
    public static final String VYTVORENO = "vytvořeno";
    public static final String AKTUALIZOVANO = "aktualizováno";
    public static final String DATUM_A_CAS = "datum_a_čas";
    public static final String DATUM = "datum";
    public static final String ALTERNATIVNI_NAZEV = "alternativní-název";
    public static final String DEFINICE = "definice";
    public static final String DEFINUJICI_USTANOVENI = "definující-ustanovení-právního-předpisu";
    public static final String SOUVISEJICI_USTANOVENI = "související-ustanovení-právního-předpisu";
    public static final String DEFINUJICI_NELEGISLATIVNI_ZDROJ = "definující-nelegislativní-zdroj";
    public static final String SOUVISEJICI_NELEGISLATIVNI_ZDROJ = "související-nelegislativní-zdroj";
    public static final String URL = "url";
    public static final String EKVIVALENTNI_POJEM = "ekvivalentní-pojem";
    public static final String NADRAZENY_POJEM = "nadřazený-pojem";
    public static final String JE_SDILEN_V_PPDF = "je-sdílen-v-ppdf";
    public static final String ZPUSOB_ZISKANI_UDAJE = "způsob-získání-údaje";
    public static final String TYP_OBSAHU_UDAJE = "typ-obsahu-údaje";
    public static final String ZPUSOBY_SDILENI_UDAJE = "způsoby-sdílení-údaje";
    public static final String AGENDA = "agenda";
    public static final String AGENDOVY_INFORMACNI_SYSTEM = "agendový-informační-systém";
    public static final String USTANOVENI_NEVEREJNOST = "ustanovení-dokládající-neveřejnost-údaje";
    public static final String OBOR_HODNOT = "obor-hodnot";
    public static final String DEFINICNI_OBOR = "definiční-obor";
    public static final String NADRAZENA_VLASTNOST = "nadřazená-vlastnost";
    public static final String NADRAZENY_VZTAH = "nadřazený-vztah";
    public static final String NADRAZENA_TRIDA = "nadřazená-třída";
    public static final String INSTANCE_DEFINOVANY_CISELNIKEM = "instance-definovány-číselníkem";
    public static final String DATOVA_SADA_V_NKOD = "datová-sada-v-nkod";

    // types
    public static final String SLOVNIK = "Slovník";
    public static final String TEZAURUS = "Tezaurus";
    public static final String KONCEPTUALNI_MODEL = "Konceptuální model";
    public static final String CASOVY_OKAMZIK = "Časový okamžik";
    public static final String POJEM = "Pojem";
    public static final String KONCEPT = "Koncept";
    public static final String TRIDA = "Třída";
    public static final String VLASTNOST = "Vlastnost";
    public static final String VZTAH = "Vztah";
    public static final String TYP_SUBJEKTU_PRAVA = "Typ subjektu práva";
    public static final String TYP_OBJEKTU_PRAVA = "Typ objektu práva";
    public static final String VEREJNY_UDAJ = "Veřejný údaj";
    public static final String NEVEREJNY_UDAJ = "Neveřejný údaj";
    public static final String DIGITALNI_OBJEKT = "Digitální objekt";
    public static final String CISELNIK = "Číselník";

    private OfnTerms() {
    }
}
