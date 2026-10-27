package com.dia.ismdtoolbackend.ofn;

/**
 * RDF IRIs of the OFN slovníky terms the tool writes and reads, and the legacy (pre-OFN) IRIs that
 * readers still accept until the data migration has run. {@code OfnIrisTest} checks every OFN IRI
 * against the bundled context.
 */
public final class OfnIris {

    // namespaces (as in the context)
    public static final String SLOVNIKY = "https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/";
    public static final String L111_2009 = "https://slovník.gov.cz/legislativní/sbírka/111/2009/pojem/";
    public static final String A104 = "https://slovník.gov.cz/agendový/104/pojem/";
    public static final String CAS = "https://slovník.gov.cz/generický/čas/pojem/";
    public static final String VSGOV = "https://slovník.gov.cz/veřejný-sektor/pojem/";
    public static final String DCTERMS = "http://purl.org/dc/terms/";
    public static final String SKOS = "http://www.w3.org/2004/02/skos/core#";
    public static final String OWL = "http://www.w3.org/2002/07/owl#";
    public static final String RDFS = "http://www.w3.org/2000/01/rdf-schema#";
    public static final String XSD = "http://www.w3.org/2001/XMLSchema#";

    // vocabulary
    public static final String SLOVNIK = SLOVNIKY + "slovník";
    public static final String CONCEPT_SCHEME = SKOS + "ConceptScheme";
    public static final String ONTOLOGY = OWL + "Ontology";
    public static final String OKAMZIK_VYTVORENI = SLOVNIKY + "okamžik-vytvoření";
    public static final String OKAMZIK_POSLEDNI_ZMENY = SLOVNIKY + "okamžik-poslední-změny";
    public static final String CASOVY_OKAMZIK = CAS + "časový-okamžik";
    public static final String DATUM_A_CAS = CAS + "datum-a-čas";
    public static final String DATUM = CAS + "datum";

    // concept types
    public static final String POJEM = SLOVNIKY + "pojem";
    public static final String KONCEPT = SKOS + "Concept";
    public static final String OWL_CLASS = OWL + "Class";
    public static final String OWL_DATATYPE_PROPERTY = OWL + "DatatypeProperty";
    public static final String OWL_OBJECT_PROPERTY = OWL + "ObjectProperty";
    public static final String TYP_SUBJEKTU_PRAVA = VSGOV + "typ-subjektu-práva";
    public static final String TYP_OBJEKTU_PRAVA = VSGOV + "typ-objektu-práva";
    public static final String VEREJNY_UDAJ = L111_2009 + "veřejný-údaj";
    public static final String NEVEREJNY_UDAJ = L111_2009 + "neveřejný-údaj";

    // concept properties
    public static final String DEFINUJICI_USTANOVENI = SLOVNIKY + "definující-ustanovení";
    public static final String SOUVISEJICI_USTANOVENI = SLOVNIKY + "související-ustanovení";
    public static final String DEFINUJICI_NELEGISLATIVNI_ZDROJ = SLOVNIKY + "definující-nelegislativní-zdroj";
    public static final String SOUVISEJICI_NELEGISLATIVNI_ZDROJ = SLOVNIKY + "související-nelegislativní-zdroj";
    public static final String DIGITALNI_OBJEKT = "https://slovník.gov.cz/generický/digitální-objekty/pojem/digitální-objekt";
    public static final String SCHEMA_URL = "http://schema.org/url";
    public static final String JE_SDILEN_V_PPDF = A104 + "je-sdílen-v-propojeném-datovém-fondu";
    public static final String USTANOVENI_NEVEREJNOST = L111_2009 + "je-vymezen-ustanovením-stanovujícím-jeho-neveřejnost";
    public static final String MA_INSTANCE_CISELNIKEM = SLOVNIKY + "má-instance-definované-číselníkem";
    public static final String CISELNIK = L111_2009 + "číselník";

    // governance codelist namespaces (the context prefixes)
    public static final String ZPUSOBY_ZISKANI = "https://data.dia.gov.cz/zdroj/číselníky/způsoby-získání-údajů/položky/";
    public static final String ZPUSOBY_SDILENI = "https://data.dia.gov.cz/zdroj/číselníky/způsoby-sdílení-údajů/položky/";
    public static final String TYPY_OBSAHU = "https://data.dia.gov.cz/zdroj/číselníky/typy-obsahu-údajů/položky/";

    // legacy shapes, read until the migration has run, never written
    public static final String LEGACY_TRIDA = SLOVNIKY + "třída";
    public static final String LEGACY_VLASTNOST = SLOVNIKY + "vlastnost";
    public static final String LEGACY_VZTAH = SLOVNIKY + "vztah";
    public static final String LEGACY_TYP_SUBJEKTU_PRAVA = SLOVNIKY + "typ-subjektu-práva";
    public static final String LEGACY_TYP_OBJEKTU_PRAVA = SLOVNIKY + "typ-objektu-práva";
    /** Local name of the pre-OFN provision predicate (any namespace). */
    public static final String LEGACY_USTANOVENI_LOCAL_NAME = "ustanovení-dokládající-neveřejnost-údaje";
    /** Local name of the vocabulary-namespace hierarchy duplicate (any namespace but rdfs). */
    public static final String LEGACY_NADRAZENA_TRIDA_LOCAL_NAME = "nadřazená-třída";
    /** Local name of the internal thesaurus flag (any namespace); the flag lives in PostgreSQL only. */
    public static final String LEGACY_IN_TEZAURUS_LOCAL_NAME = "inTezaurus";
    /** The junk class the common library's base model added to every created vocabulary. */
    public static final String LEGACY_JUNK_POJEM = "https://slovník.gov.cz/pojem";
    public static final String DCT_CONFORMS_TO = DCTERMS + "conformsTo";

    private OfnIris() {
    }

    /** The part of an IRI after its last {@code /} or {@code #}. */
    public static String localName(String iri) {
        if (iri == null) {
            return "";
        }
        int cut = Math.max(iri.lastIndexOf('/'), iri.lastIndexOf('#'));
        return cut < 0 ? iri : iri.substring(cut + 1);
    }

    /** True when the concept IRI lies in the vocabulary's namespace ({@code <vocabulary>/…}). */
    public static boolean isOwnedBy(String conceptIri, String vocabularyIri) {
        return conceptIri != null && vocabularyIri != null && conceptIri.startsWith(vocabularyIri + "/");
    }
}
