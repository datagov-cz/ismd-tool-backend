package com.dia.ismdtoolbackend.constants;

/**
 * Consolidated format and tool-specific constants.
 * Merges constants from: ConverterControllerConstants, EnterpriseArchitectConstants, ExcelConstants.
 */
public class FormatConstants {

    private FormatConstants() {
    }

    /**
     * Excel import/export specific constants.
     * Czech column headers used in Excel templates.
     */
    public static final class Excel {
        // =============== CORE VOCABULARY TYPES ===============
        public static final String SLOVNIK = "Slovník";
        public static final String SUBJEKTY_OBJEKTY_PRAVA = "Subjekty a objekty práva";
        public static final String SUBJEKT_OBJEKT_PRAVA = "Subjekt nebo objekt práva";
        public static final String VLASTNOSTI = "Vlastnosti";
        public static final String VZTAHY = "Vztahy";

        // =============== CORE VOCABULARY PROPERTIES ===============
        public static final String NAZEV = "Název";
        public static final String TYP = "Typ";
        public static final String POPIS = "Popis";
        public static final String DEFINICE = "Definice";
        public static final String ZDROJ = "Zdroj";
        public static final String SOUVISEJICI_ZDROJ = "Související zdroj";
        public static final String NADRAZENY_POJEM = "Nadřazený pojem";
        public static final String EKVIVALENTNI_POJEM = "Ekvivalentní pojem";
        public static final String ALT_NAZEV = "Alternativní název";
        public static final String IDENTIFIKATOR = "Identifikátor";
        public static final String AGENDA = "Agenda";
        public static final String AIS = "Agendový informační systém";
        public static final String DATOVY_TYP = "Datový typ";
        public static final String JE_PPDF = "Je pojem sdílen v PPDF?";
        public static final String JE_VEREJNY = "Je pojem veřejný?";
        public static final String USTANOVENI_DOKLADAJICI_NEVEREJNOST = "Ustanovení dokládající neveřejnost pojmu";
        public static final String ZPUSOB_SDILENI_UDEJE = "Způsob sdílení údaje";
        public static final String ZPUSOB_ZISKANI_UDEJE = "Způsob získání údaje";
        public static final String TYP_OBSAHU_UDAJE = "Typ obsahu údaje";

        private Excel() {
        }
    }

    /**
     * Enterprise Architect (EA) tool specific constants.
     * XMI export/import constants for EA tool integration.
     */
    public static final class EnterpriseArchitect {
        // =============== STEREOTYPE CONSTANTS ===============
        public static final String STEREOTYPE_SLOVNIKY_PACKAGE = "slovnikyPackage";
        public static final String STEREOTYPE_TYP_OBJEKTU = "typObjektu";
        public static final String STEREOTYPE_TYP_SUBJEKTU = "typSubjektu";
        public static final String STEREOTYPE_TYP_VLASTNOSTI = "typVlastnosti";
        public static final String STEREOTYPE_TYP_VZTAHU = "typVztahu";

        // =============== XMI CONSTANTS ===============
        public static final String XMI_ID = "xmi:id";
        public static final String XMI_IDREF = "xmi:idref";
        public static final String XMI_TYPE = "xmi:type";
        public static final String UML_PACKAGE = "uml:Package";
        public static final String UML_CLASS = "uml:Class";
        public static final String PACKAGED_ELEMENT = "packagedElement";
        public static final String SOURCE = "source";
        public static final String TARGET = "target";

        // =============== ENCODING CONSTANTS ===============
        public static final String WINDOWS_1252 = "windows-1252";
        public static final String ISO_8859_2 = "ISO-8859-2";

        // =============== FIELD MAPPING CONSTANTS ===============
        public static final String TAG_POPIS = "popis";
        public static final String TAG_DEFINICE = "definice";
        public static final String TAG_ZDROJ = "zdroj";
        public static final String TAG_IDENTIFIKATOR = "identifikátor";
        public static final String TAG_ALTERNATIVNI_NAZEV = "alternativní název";
        public static final String TAG_EKVIVALENTNI_POJEM = "ekvivalentní pojem";
        public static final String TAG_SOUVISEJICI_ZDROJ = "související zdroj";
        public static final String TAG_DATOVY_TYP = "datový typ";
        public static final String TAG_JE_POJEM_SDILEN_V_PPDF = "je pojem sdílen v PPDF?";
        public static final String TAG_JE_POJEM_VEREJNY = "je pojem veřejný?";
        public static final String TAG_USTANOVENI_DOKLADAJICI_NEVEREJNOST = "ustanovení dokládající neveřejnost pojmu";
        public static final String TAG_ZPUSOB_SDILENI_UDAJE = "způsob sdílení údaje";
        public static final String TAG_ZPUSOB_ZISKANI_UDAJE = "způsob získání údaje";
        public static final String TAG_TYP_OBSAHU_UDAJE = "typ obsahu údaje";
        public static final String TAG_AGENDA = "agenda";
        public static final String TAG_AGENDOVY_INFORMACNI_SYSTEM = "agendový informační systém";

        private EnterpriseArchitect() {
        }
    }

    /**
     * ArchiMate and XMI converter specific constants.
     * Header URIs and logging constants for format conversion.
     */
    public static final class Converter {
        // =============== FORMAT HEADERS ===============
        public static final String ARCHI_3_HEADER = "http://www.opengroup.org/xsd/archimate/3.0/";
        public static final String ARCHIMATE_HEADER = "http://www.opengroup.org/xsd/archimate";
        public static final String XMI_HEADER = "http://schema.omg.org/spec/XMI/2.1";

        // =============== LOGGING CONSTANTS ===============
        public static final String LOG_REQUEST_ID = "requestId";

        private Converter() {
        }
    }
}