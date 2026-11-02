package com.dia.ismdtoolbackend.client;

import com.dia.ismdtoolbackend.models.nkod.NkodCodelist;
import com.dia.ismdtoolbackend.models.nkod.NkodCodelistEntry;
import com.dia.ismdtoolbackend.testsupport.sparql.SparqlJson;
import com.dia.ismdtoolbackend.testsupport.sparql.SparqlJson.Binding;
import org.apache.jena.query.ResultSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.dia.ismdtoolbackend.testsupport.sparql.SparqlJson.literal;
import static com.dia.ismdtoolbackend.testsupport.sparql.SparqlJson.typed;
import static com.dia.ismdtoolbackend.testsupport.sparql.SparqlJson.uri;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the codelist mappers over real Jena {@link ResultSet}s built from SPARQL JSON. Rows are
 * trimmed from the prod catalogue (data.gov.cz, 2026-09-30).
 */
class NkodSparqlClientCodelistTest {

    private static final String DIA = "https://rpp-opendata.egon.gov.cz/odrpp/zdroj/orgán-veřejné-moci/17651921";
    private static final String MPSV = "https://rpp-opendata.egon.gov.cz/odrpp/zdroj/orgán-veřejné-moci/00551023";
    private static final String UK = "https://rpp-opendata.egon.gov.cz/odrpp/zdroj/orgán-veřejné-moci/00216208";
    private static final String TACR = "https://rpp-opendata.egon.gov.cz/odrpp/zdroj/orgán-veřejné-moci/72050365";

    private static final String POHLAVI_151 = "https://data.gov.cz/zdroj/datové-sady/17651921/5ccc4289ba0bafb05f078bf1157922b0";
    private static final String POHLAVI_46 = "https://data.gov.cz/zdroj/datové-sady/17651921/658544064a5eb18b5357ebe5b40e032b";
    private static final String FORMY = "https://data.gov.cz/zdroj/datové-sady/00551023/454f8a1fdeb7c48d4ce8c3b6fad1d62c";
    private static final String ISVAV = "https://data.gov.cz/zdroj/datové-sady/00216208/102d4d07b434cad29a95f561e3a88f8e";
    private static final String ROLE = "https://data.gov.cz/zdroj/datové-sady/72050365/1072138515";

    private static final Map<String, String> NAMES = Map.of(
            DIA, "Digitální a informační agentura",
            MPSV, "Ministerstvo práce a sociálních věcí",
            UK, "Univerzita Karlova");

    private static final List<Binding[]> ROWS = List.of(
            row(POHLAVI_151, "Pohlaví",
                    "https://rpp-opendata.egon.gov.cz/odrpp/datovasada/ciselnikyVdf/ciselnik_151_20250101.jsonld",
                    DIA, "Datová sada POHLAVI slouží pro vyplnění hodnoty údaje „Pohlaví“.",
                    "https://rpp-ais.egon.gov.cz/AISP/rest/verejne/ddciselniky/mdzastresujicids/151", "2025-01-01"),
            row(POHLAVI_46, "Pohlaví",
                    "https://rpp-opendata.egon.gov.cz/odrpp/datovasada/ciselnikyVdf/ciselnik_46_19000101.jsonld",
                    DIA, "Pohlaví, kód ČSÚ: 102",
                    "https://rpp-ais.egon.gov.cz/AISP/rest/verejne/ddciselniky/mdzastresujicids/46", "1900-01-01"),
            row(FORMY, "Formy sociálních služeb",
                    "https://data.mpsv.cz/od/soubory/ciselniky/formy-soc-sluzby.jsonld",
                    MPSV, "Číselník forem sociálních služeb", null, null),
            row(FORMY, "Formy sociálních služeb",
                    "https://data.mpsv.cz/od/soubory/ciselniky/formy-soc-sluzby-ofn.jsonld",
                    MPSV, "Číselník forem sociálních služeb", null, null),
            row(ISVAV, "Klasifikace oborů ISVaV",
                    "https://data.mff.cuni.cz/soubory/číselníky/klasifikace-oborů-isvav.jsonld",
                    UK, "Číselník Klasifikace oborů ISVaV", null, null),
            row(ROLE, "Role účastníka v projektu", null,
                    TACR, "Tento číselník obsahuje seznam rolí účastníka (subjektu) v projektu.", null, null));

    private static List<NkodCodelistEntry> map(List<Binding[]> rows, Map<String, String> names) {
        SparqlJson result = SparqlJson.select("dataset", "title", "downloadUrl", "publisher", "description",
                "rppIdentifier", "validFrom");
        rows.forEach(result::row);
        return NkodSparqlClient.groupByDataset(NkodSparqlClient.mapCodelistRows(result.toResultSet()), names);
    }

    private static Binding[] row(String dataset, String title, String url, String publisher, String description,
                                 String rppId, String validFrom) {
        return new Binding[]{
                uri("dataset", dataset),
                literal("title", title, "cs"),
                uri("downloadUrl", url),
                uri("publisher", publisher),
                literal("description", description, "cs"),
                typed("rppIdentifier", rppId, "xsd:anyURI"),
                typed("validFrom", validFrom, "xsd:date")};
    }

    private static NkodCodelistEntry entry(List<NkodCodelistEntry> entries, String datasetIri) {
        return entries.stream().filter(e -> e.datasetIri().equals(datasetIri)).findFirst().orElseThrow();
    }

    @Test
    void oneEntryPerDataset_inQueryOrder() {
        List<NkodCodelistEntry> entries = map(ROWS, NAMES);

        assertThat(entries).extracting(NkodCodelistEntry::datasetIri)
                .containsExactly(POHLAVI_151, POHLAVI_46, FORMY, ISVAV, ROLE);
    }

    @Test
    void seriesRow_mapsEveryField() {
        NkodCodelist c = entry(map(ROWS, NAMES), POHLAVI_151).codelist();

        assertThat(c.getTitle()).isEqualTo("Pohlaví");
        assertThat(c.getPublisher()).isEqualTo("Digitální a informační agentura");
        assertThat(c.getDescription()).startsWith("Datová sada POHLAVI");
        assertThat(c.getCodeListNumber()).isEqualTo("151");
        assertThat(c.getValidFrom()).isEqualTo("2025-01-01");
    }

    @Test
    void identicalTitles_areSeparatedByCodeListNumber() {
        List<NkodCodelistEntry> entries = map(ROWS, NAMES);

        assertThat(entry(entries, POHLAVI_151).codelist().getCodeListNumber()).isEqualTo("151");
        assertThat(entry(entries, POHLAVI_46).codelist().getCodeListNumber()).isEqualTo("46");
        assertThat(entry(entries, POHLAVI_46).codelist().getValidFrom()).isEqualTo("1900-01-01");
    }

    @Test
    void datasetWithTwoDistributions_keepsBothUrlsSorted() {
        NkodCodelistEntry e = entry(map(ROWS, NAMES), FORMY);

        assertThat(e.downloadUrls()).containsExactly(
                "https://data.mpsv.cz/od/soubory/ciselniky/formy-soc-sluzby-ofn.jsonld",
                "https://data.mpsv.cz/od/soubory/ciselniky/formy-soc-sluzby.jsonld");
    }

    @Test
    void datasetWithoutDistribution_isKeptWithNoUrls() {
        NkodCodelistEntry e = entry(map(ROWS, NAMES), ROLE);

        assertThat(e.downloadUrls()).isEmpty();
        assertThat(e.codelist().getTitle()).isEqualTo("Role účastníka v projektu");
    }

    @Test
    void standaloneDataset_hasNoSeriesFieldsAndKeepsRawCzechUrl() {
        NkodCodelistEntry e = entry(map(ROWS, NAMES), ISVAV);

        assertThat(e.codelist().getCodeListNumber()).isNull();
        assertThat(e.codelist().getValidFrom()).isNull();
        assertThat(e.downloadUrls())
                .containsExactly("https://data.mff.cuni.cz/soubory/číselníky/klasifikace-oborů-isvav.jsonld");
    }

    @Test
    void publisherWithoutName_fallsBackToItsIri() {
        assertThat(entry(map(ROWS, NAMES), ROLE).codelist().getPublisher()).isEqualTo(TACR);
    }

    @Test
    void rowWithoutTitle_isSkipped() {
        List<Binding[]> rows = new ArrayList<>();
        rows.add(row("https://data.gov.cz/zdroj/datové-sady/x/untitled", null,
                "https://x/a.jsonld", DIA, "d", null, null));
        rows.addAll(ROWS);

        assertThat(map(rows, NAMES)).extracting(NkodCodelistEntry::datasetIri)
                .doesNotContain("https://data.gov.cz/zdroj/datové-sady/x/untitled")
                .hasSize(5);
    }

    @Test
    void everyEntryStartsUnresolved() {
        assertThat(map(ROWS, NAMES)).noneMatch(NkodCodelistEntry::isResolved);
    }

    @Test
    void publisherNames_mapsEachPublisher() {
        ResultSet publishers = SparqlJson.select("publisher", "name")
                .row(uri("publisher", DIA), literal("name", "Digitální a informační agentura", "cs"))
                .row(uri("publisher", UK), literal("name", "Univerzita Karlova", "cs"))
                .toResultSet();

        Map<String, String> names = NkodSparqlClient.mapPublisherNames(publishers);

        assertThat(names).containsOnly(
                Map.entry(DIA, "Digitální a informační agentura"),
                Map.entry(UK, "Univerzita Karlova"));
    }

    @Test
    void lastPathSegment() {
        assertThat(NkodSparqlClient.lastPathSegment(
                "https://rpp-ais.egon.gov.cz/AISP/rest/verejne/ddciselniky/mdzastresujicids/151")).isEqualTo("151");
        assertThat(NkodSparqlClient.lastPathSegment("https://x/mdzastresujicids/151/")).isEqualTo("151");
        assertThat(NkodSparqlClient.lastPathSegment(null)).isNull();
    }
}
