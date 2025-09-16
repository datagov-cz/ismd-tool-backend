package com.dia.ismdtoolbackend.constants;

public class OFNJsonConstants {
    // Namespaces
    public static final String SLOVNIKY_NS = "https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/";
    public static final String OFN_NAMESPACE = "https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/";
    public static final String DEFAULT_NS = "https://slovník.gov.cz/";
    public static final String DCT_NS = "http://purl.org/dc/terms/";
    public static final String SKOS_NS = "http://www.w3.org/2004/02/skos/core#";
    public static final String CAS_NS = "https://slovník.gov.cz/generický/čas/pojem/";
    public static final String XSD = "http://www.w3.org/2001/XMLSchema#";
    public static final String CONTEXT = "https://ofn.gov.cz/slovník/kontexty/";

    // Property names
    public static final String NAZEV = "název";
    public static final String POPIS = "popis";
    public static final String DEFINICE = "definice";
    public static final String IDENTIFIKATOR = "identifikátor";
    public static final String ALTERNATIVNI_NAZEV = "alternativní název";
    public static final String EKVIVALENTNI_POJEM = "ekvivalentní pojem";
    public static final String DEFINICNI_OBOR = "definiční-obor";
    public static final String OBOR_HODNOT = "obor-hodnot";
    public static final String NADRAZENA_TRIDA = "nadřazená-třída";
    public static final String OKAMZIK_VYTVORENI = "okamžik-vytvoření";
    public static final String OKAMZIK_POSLEDNI_ZMENY = "okamžik-poslední-změny";
    public static final String DATUM_A_CAS = "datum-a-čas";
    public static final String DATUM = "datum";
    public static final String LOKALNI_KATALOG = "lokální-katalog";

    // Governance properties
    public static final String ZPUSOB_SDILENI = "způsob-sdílení-údajů";
    public static final String ZPUSOB_ZISKANI = "způsob-získání-údajů";
    public static final String TYP_OBSAHU = "typ-obsahu-údajů";
    public static final String USTANOVENI_NEVEREJNOST = "ustanovení-neveřejnost";
    public static final String JE_PPDF = "je-ppdf";
    public static final String AIS = "ais";
    public static final String AGENDA = "agenda";

    // Source properties
    public static final String DEFINUJICI_USTANOVENI = "definující-ustanovení";
    public static final String SOUVISEJICI_USTANOVENI = "související-ustanovení";
    public static final String DEFINUJICI_NELEGISLATIVNI_ZDROJ = "definující-nelegislativní-zdroj";
    public static final String SOUVISEJICI_NELEGISLATIVNI_ZDROJ = "související-nelegislativní-zdroj";

    // OFN concept types
    public static final String POJEM = "pojem";
    public static final String TRIDA = "třída";
    public static final String VZTAH = "vztah";
    public static final String VLASTNOST = "vlastnost";
    public static final String TSP = "typ-subjektu-práva";
    public static final String TOP = "typ-objektu-práva";
    public static final String VEREJNY_UDAJ = "veřejný-údaj";
    public static final String NEVEREJNY_UDAJ = "neveřejný-údaj";

    // JSON-LD type mappings
    public static final String POJEM_JSON_LD = "pojem";
    public static final String TRIDA_JSON_LD = "třída";
    public static final String VZTAH_JSON_LD = "vztah";
    public static final String VLASTNOST_JSON_LD = "vlastnost";
    public static final String TSP_JSON_LD = "typ-subjektu-práva";
    public static final String TOP_JSON_LD = "typ-objektu-práva";
    public static final String VEREJNY_UDAJ_JSON_LD = "veřejný-údaj";
    public static final String NEVEREJNY_UDAJ_JSON_LD = "neveřejný-údaj";

    // JSON structure constants
    public static final String JSON_CONTEXT = "@context";
    public static final String JSON_IRI = "iri";
    public static final String JSON_TYP = "typ";
    public static final String JSON_POJMY = "pojmy";
    public static final String TYPE_SLOVNIK = "slovník";
    public static final String TYPE_TEZAURUS = "tezaurus";
    public static final String TYPE_KM = "konceptuální model";

    // Field ordering for JSON output
    public static final String[] CONCEPT_FIELD_ORDER = {
            "iri", "typ", "název", "alternativní název", "identifikátor", "popis", "definice",
            "ekvivalentní pojem", DEFINUJICI_USTANOVENI, SOUVISEJICI_USTANOVENI,
            DEFINUJICI_NELEGISLATIVNI_ZDROJ, SOUVISEJICI_NELEGISLATIVNI_ZDROJ,
            "definiční-obor", "obor-hodnot", "nadřazený-vztah", "nadřazená-vlastnost",
            "nadřazená-třída", "způsob-sdílení-údajů", "způsob-získání-údajů", "typ-obsahu-údajů"
    };

    private OFNJsonConstants() {}
}
