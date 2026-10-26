package com.dia.ismdtoolbackend.client;

import com.dia.ismdtoolbackend.config.EsbirkaConfig;
import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import com.dia.ismdtoolbackend.models.eli.FragmentModel;
import com.dia.ismdtoolbackend.models.eli.FragmentResolutionModel;
import com.dia.ismdtoolbackend.models.eli.LawModel;
import com.dia.ismdtoolbackend.models.eli.LawVersionModel;
import com.dia.ismdtoolbackend.testsupport.sparql.SparqlJson;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.http.HttpClient;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.dia.ismdtoolbackend.testsupport.sparql.SparqlJson.literal;
import static com.dia.ismdtoolbackend.testsupport.sparql.SparqlJson.typed;
import static com.dia.ismdtoolbackend.testsupport.sparql.SparqlJson.uri;
import static com.dia.ismdtoolbackend.testsupport.sparql.SparqlJson.xsdBoolean;
import static com.dia.ismdtoolbackend.testsupport.sparql.SparqlStubs.serverError;
import static com.dia.ismdtoolbackend.testsupport.sparql.SparqlStubs.sparqlJson;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class EsbirkaSparqlClientTest {

    private static final String LAW_IRI =
            "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187";
    private static final String VERSION_IRI =
            "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187/2026-04-01";

    private static final int BREAKER_THRESHOLD = 3;

    private static final String[] LAW_VARS = {"akt", "citace", "cislo", "rok", "sbirka"};
    private static final String[] FRAGMENT_VARS = {"fragment", "parent", "citace", "order"};
    private static final String[] CONTENT_VARS = {"fragment", "parent", "citace", "order", "obsah"};
    private static final String[] RESOLUTION_VARS = {"citace", "ucinnostDo", "isLatest"};

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @RegisterExtension
    static final WireMockExtension WM = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private EsbirkaSparqlClient client;

    @BeforeEach
    void setUp() {
        client = clientFor(WM.baseUrl() + "/sparql");
    }

    private static EsbirkaSparqlClient clientFor(String endpoint) {
        return clientFor(endpoint, 2000);
    }

    private static EsbirkaSparqlClient clientFor(String endpoint, int timeoutMs) {
        EsbirkaConfig config = new EsbirkaConfig();
        config.getSparql().setEndpoint(endpoint);
        config.getSparql().setTimeout(timeoutMs);
        config.getSparql().getCircuitBreaker().setFailureThreshold(BREAKER_THRESHOLD);
        return new EsbirkaSparqlClient(config, HTTP);
    }

    // --- law row mapping (grouped search, law lookup) -----------------------

    @Test
    void lawRows_skipRowMissingCitace() {
        stubSparql(SparqlJson.select(LAW_VARS)
                .row(lawRow(LAW_IRI, null, "187", typed("rok", "2006", "xsd:gYear")))
                .row(lawRow(LAW_IRI + "/2025", "99/2025 Sb.", "99", typed("rok", "2025", "xsd:gYear"))));
        Optional<LawModel> out = client.findLawByNumberYear("99", 2025);
        assertThat(out).isPresent();
        assertThat(out.get().getCitace()).isEqualTo("99/2025 Sb.");
    }

    @Test
    void lawRows_unparseableYearBecomesNull() {
        stubSparql(SparqlJson.select(LAW_VARS)
                .row(lawRow(LAW_IRI, "99/9999 Sb.", "99", literal("rok", "not-a-number"))));
        Optional<LawModel> out = client.findLawByNumberYear("99", 9999);
        assertThat(out).isPresent();
        assertThat(out.get().getRok()).isNull();
    }

    // --- fetchVersions ------------------------------------------------------

    @Test
    void fetchVersions_mapsAllOptionalFieldsAndLatestFlag() {
        stubSparql(SparqlJson.select("zneni", "ucinnostOd", "ucinnostDo", "typ", "isLatest")
                .row(uri("zneni", VERSION_IRI),
                        typed("ucinnostOd", "2026-04-01", "xsd:date"),
                        typed("ucinnostDo", "2026-06-30", "xsd:date"),
                        uri("typ", "https://example.org/typ/KONSOL"),
                        xsdBoolean("isLatest", true))
                .row(uri("zneni", VERSION_IRI + "-old"),
                        typed("ucinnostOd", "2025-01-01", "xsd:date"),
                        xsdBoolean("isLatest", false)));
        List<LawVersionModel> out = client.fetchVersions(LAW_IRI);
        assertThat(out).hasSize(2);

        LawVersionModel latest = out.get(0);
        assertThat(latest.getIri()).isEqualTo(VERSION_IRI);
        assertThat(latest.getUcinnostOd()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(latest.getUcinnostDo()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(latest.getVersionType()).isEqualTo("https://example.org/typ/KONSOL");
        assertThat(latest.isLatest()).isTrue();

        LawVersionModel old = out.get(1);
        assertThat(old.getUcinnostOd()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(old.getUcinnostDo()).isNull();
        assertThat(old.getVersionType()).isNull();
        assertThat(old.isLatest()).isFalse();
    }

    @Test
    void fetchVersions_unparseableDateBecomesNull() {
        stubSparql(SparqlJson.select("zneni", "ucinnostOd", "isLatest")
                .row(uri("zneni", VERSION_IRI), literal("ucinnostOd", "not-a-date"), xsdBoolean("isLatest", true)));
        List<LawVersionModel> out = client.fetchVersions(LAW_IRI);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getUcinnostOd()).isNull();
    }

    // --- fragment kind from IRI -----------------------------------------------

    @Test
    void fragmentKind_parsesAllKnownKindsFromIri() {
        String basePath = VERSION_IRI + "/dokument/norma";
        stubSparql(SparqlJson.select(FRAGMENT_VARS)
                .row(fragmentRow(basePath + "/cast_5",                      basePath,                "Část 5",          "6AC0"))
                .row(fragmentRow(basePath + "/cast_5/hlava_4",              basePath + "/cast_5",    "Hlava 4",         "6ADA"))
                .row(fragmentRow(basePath + "/cast_5/hlava_4/dil_3",        basePath + "/cast_5/hlava_4", "Díl 3",      "6ADAD0"))
                .row(fragmentRow(basePath + "/cast_5/oddil_2",              basePath + "/cast_5",    "Oddíl 2",         "6ADB"))
                .row(fragmentRow(basePath + "/par_122",                     basePath,                "§ 122",           "6ADE"))
                .row(fragmentRow(basePath + "/par_122/odst_4",              basePath + "/par_122",   "§ 122 odst. 4",   "6ADED5"))
                .row(fragmentRow(basePath + "/par_122/odst_4/pism_g",       basePath + "/par_122/odst_4", "§ 122 odst. 4 písm. g)", "6ADED580"))
                .row(fragmentRow(basePath + "/par_122/odst_4/pism_g/bod_1", basePath + "/par_122/odst_4/pism_g", "bod 1", "6ADED5C0"))
                .row(fragmentRow(basePath + "/ppc_1",                       basePath,                "PPC 1",           "6AEE"))
                .row(fragmentRow(basePath + "/frag_1",                      basePath,                "Frag 1",          "6AEF")));
        List<FragmentModel> out = client.fetchVersionContent(VERSION_IRI);
        assertThat(out).hasSize(10);

        // Order preserved (server-side ORDER BY ?order); kind parsed from last segment.
        assertThat(out.get(0).getKind()).isEqualTo("cast");
        assertThat(out.get(1).getKind()).isEqualTo("hlava");
        assertThat(out.get(2).getKind()).isEqualTo("dil");
        assertThat(out.get(3).getKind()).isEqualTo("oddil");
        assertThat(out.get(4).getKind()).isEqualTo("par");
        assertThat(out.get(5).getKind()).isEqualTo("odst");
        assertThat(out.get(6).getKind()).isEqualTo("pism");
        assertThat(out.get(7).getKind()).isEqualTo("bod");
        assertThat(out.get(8).getKind()).isEqualTo("ppc");
        assertThat(out.get(9).getKind()).isEqualTo("frag");

        // Citation preserved verbatim
        assertThat(out.get(6).getCitation()).isEqualTo("§ 122 odst. 4 písm. g)");
        // Order key preserved (string, lex-sortable)
        assertThat(out.get(6).getOrder()).isEqualTo("6ADED580");
        // Parent edge preserved
        assertThat(out.get(6).getParentIri()).isEqualTo(basePath + "/par_122/odst_4");
    }

    @Test
    void fragmentKind_lastSegmentWithoutUnderscore_usesSegmentAsKind() {
        // parseKindFromIri returns the whole last segment when it contains no underscore
        // (e.g. a "poznamkypodcarou" container or a bare "frag" leaf).
        String basePath = VERSION_IRI + "/dokument/norma";
        stubSparql(SparqlJson.select(FRAGMENT_VARS)
                .row(fragmentRow(basePath + "/poznamkypodcarou", basePath, "Poznámky", "FFFF"))
                .row(fragmentRow(basePath + "/frag",             basePath, "Frag",     "FFFE")));
        List<FragmentModel> out = client.fetchVersionContent(VERSION_IRI);
        assertThat(out).hasSize(2);
        assertThat(out.get(0).getKind()).isEqualTo("poznamkypodcarou");
        assertThat(out.get(1).getKind()).isEqualTo("frag");
    }

    @Test
    void fragmentKind_iriEndingInSlash_kindIsUnknown() {
        // Defensive branch: an IRI whose last char is '/' has no parseable last segment.
        String basePath = VERSION_IRI + "/dokument/norma";
        stubSparql(SparqlJson.select(FRAGMENT_VARS)
                .row(fragmentRow(basePath + "/trailing/", basePath, "Trailing", "FFFD")));
        List<FragmentModel> out = client.fetchVersionContent(VERSION_IRI);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getKind()).isEqualTo("unknown");
    }

    // --- fetchVersionContent (obsah body mapping) ---------------------------

    @Test
    void fetchVersionContent_mapsBodyHtmlAndNullForStructuralFragments() {
        // A textual fragment (par) carries an obsah HTML body; a structural fragment
        // (cast) carries none — the OPTIONAL obsah join leaves it unbound, and the
        // mapper must surface that as a null bodyHtml rather than dropping the row.
        String basePath = VERSION_IRI + "/dokument/norma";
        stubSparql(SparqlJson.select(CONTENT_VARS)
                .row(contentRow(basePath + "/cast_1", basePath, "Část 1", "6AC0", null))
                .row(contentRow(basePath + "/cast_1/par_1", basePath + "/cast_1", "§ 1", "6AC1",
                        "<var>§ 1</var> Tělo paragrafu.")));

        List<FragmentModel> out = client.fetchVersionContent(VERSION_IRI);
        assertThat(out).hasSize(2);

        FragmentModel structural = out.get(0);
        assertThat(structural.getKind()).isEqualTo("cast");
        assertThat(structural.getBodyHtml()).as("structural fragment must have null bodyHtml").isNull();

        FragmentModel textual = out.get(1);
        assertThat(textual.getKind()).isEqualTo("par");
        assertThat(textual.getBodyHtml()).isEqualTo("<var>§ 1</var> Tělo paragrafu.");
        assertThat(textual.getParentIri()).isEqualTo(basePath + "/cast_1");
        assertThat(textual.getOrder()).isEqualTo("6AC1");
    }

    @Test
    void fetchVersionContent_keepsRowMissingParent() {
        // A row without a parent edge is kept, with its obsah body.
        String basePath = VERSION_IRI + "/dokument/norma";
        stubSparql(SparqlJson.select(CONTENT_VARS)
                .row(contentRow(basePath + "/par_1", null, "§ 1", "6AC0", "<var>orphan</var>"))
                .row(contentRow(basePath + "/par_2", basePath, "§ 2", "6AC1", "<var>§ 2</var>")));

        List<FragmentModel> out = client.fetchVersionContent(VERSION_IRI);
        // A missing parent must NOT drop the row — its obsah body would be lost with it.
        assertThat(out).hasSize(2);
        assertThat(out.get(0).getParentIri()).isNull();
        assertThat(out.get(0).getBodyHtml()).isEqualTo("<var>orphan</var>");
        assertThat(out.get(1).getCitation()).isEqualTo("§ 2");
        assertThat(out.get(1).getBodyHtml()).isEqualTo("<var>§ 2</var>");
    }

    @Test
    void fetchVersionContent_emptyResultSet() {
        stubSparql(SparqlJson.select(CONTENT_VARS));
        assertThat(client.fetchVersionContent(VERSION_IRI)).isEmpty();
    }

    // --- findLawByNumberYear ------------------------------------------------

    @Test
    void findLawByNumberYear_happyPath_mapsFirstRow() {
        stubSparql(SparqlJson.select(LAW_VARS)
                .row(lawRow(LAW_IRI, "49/1997 Sb.", "49", typed("rok", "1997", "xsd:gYear"))));

        Optional<LawModel> out = client.findLawByNumberYear("49", 1997);
        assertThat(out).isPresent();
        assertThat(out.get().getIri()).isEqualTo(LAW_IRI);
        assertThat(out.get().getCitace()).isEqualTo("49/1997 Sb.");
        assertThat(out.get().getCislo()).isEqualTo("49");
        assertThat(out.get().getRok()).isEqualTo(Integer.valueOf(1997));
    }

    @Test
    void findLawByNumberYear_noMatch_returnsEmptyOptional() {
        stubSparql(SparqlJson.select(LAW_VARS));
        assertThat(client.findLawByNumberYear("999", 1997)).isEmpty();
    }

    // --- resolveFragment ----------------------------------------------------

    @Test
    void resolveFragment_happyPath_returnsCitationAndMetadata() {
        String fragmentIri = VERSION_IRI + "/dokument/norma/par_2/pism_d";
        stubSparql(SparqlJson.select(RESOLUTION_VARS)
                .row(literal("citace", "§ 2 písm. d)"),
                        typed("ucinnostDo", "2024-12-31", "xsd:date"),
                        xsdBoolean("isLatest", true)));

        Optional<FragmentResolutionModel> out = client.resolveFragment(fragmentIri, VERSION_IRI, LAW_IRI);

        assertThat(out).isPresent();
        assertThat(out.get().citation()).isEqualTo("§ 2 písm. d)");
        assertThat(out.get().versionValidUntil()).isEqualTo(LocalDate.of(2024, 12, 31));
        assertThat(out.get().isLatest()).isTrue();
    }

    @Test
    void resolveFragment_emptyResult_returnsEmptyOptional() {
        String fragmentIri = VERSION_IRI + "/dokument/norma/par_2/pism_d";
        stubSparql(SparqlJson.select(RESOLUTION_VARS));

        Optional<FragmentResolutionModel> out = client.resolveFragment(fragmentIri, VERSION_IRI, LAW_IRI);

        assertThat(out).isEmpty();
    }

    @Test
    void resolveFragment_noUcinnostDo_returnsNullValidUntil() {
        String fragmentIri = VERSION_IRI + "/dokument/norma/par_2/pism_d";
        stubSparql(SparqlJson.select(RESOLUTION_VARS)
                .row(literal("citace", "§ 2 písm. d)"), xsdBoolean("isLatest", false)));

        Optional<FragmentResolutionModel> out = client.resolveFragment(fragmentIri, VERSION_IRI, LAW_IRI);

        assertThat(out).isPresent();
        assertThat(out.get().citation()).isEqualTo("§ 2 písm. d)");
        assertThat(out.get().versionValidUntil()).isNull();
        assertThat(out.get().isLatest()).isFalse();
    }

    @Test
    void resolveFragment_endpointDown_throwsSparqlEndpointUnavailable() {
        String fragmentIri = VERSION_IRI + "/dokument/norma/par_2/pism_d";
        WM.stubFor(any(anyUrl()).willReturn(serverError()));

        assertThatThrownBy(() -> client.resolveFragment(fragmentIri, VERSION_IRI, LAW_IRI))
                .isInstanceOf(SparqlEndpointUnavailableException.class);
    }

    @Test
    void resolveFragment_malformedBody_throwsSparqlEndpointUnavailable() {
        String fragmentIri = VERSION_IRI + "/dokument/norma/par_2/pism_d";
        stubSparql("{ malformed");

        assertThatThrownBy(() -> client.resolveFragment(fragmentIri, VERSION_IRI, LAW_IRI))
                .isInstanceOf(SparqlEndpointUnavailableException.class);
    }

    // --- G12: failure modes -------------------------------------------------

    @Test
    void upstream500ThrowsEsbirkaUnavailable() {
        WM.stubFor(any(anyUrl()).willReturn(serverError()));
        assertThatThrownBy(() -> client.findLawByNumberYear("49", 1997))
                .isInstanceOf(SparqlEndpointUnavailableException.class);
    }

    @Test
    void upstreamTimeoutThrowsEsbirkaUnavailable() {
        EsbirkaSparqlClient impatient = clientFor(WM.baseUrl() + "/sparql", 250);
        WM.stubFor(any(anyUrl()).willReturn(sparqlJson(SparqlJson.select(LAW_VARS)).withFixedDelay(1_500)));
        SparqlEndpointUnavailableException ex = assertThatExceptionOfType(SparqlEndpointUnavailableException.class)
                .isThrownBy(() -> impatient.findLawByNumberYear("49", 1997))
                .actual();
        assertThat(ex.getMessage()).isNotNull();
    }

    @Test
    void malformedBodyThrowsEsbirkaUnavailable() {
        stubSparql("{ this is not valid sparql json");
        assertThatThrownBy(() -> client.findLawByNumberYear("49", 1997))
                .isInstanceOf(SparqlEndpointUnavailableException.class);
    }

    @Test
    void connectionRefusedThrowsEsbirkaUnavailable() {
        // Point at a port that's not listening — a stopped wiremock instance equivalent.
        EsbirkaSparqlClient down = clientFor("http://127.0.0.1:1/sparql");
        assertThatThrownBy(() -> down.findLawByNumberYear("49", 1997))
                .isInstanceOf(SparqlEndpointUnavailableException.class);
    }

    @Test
    void unconfiguredEndpointThrowsEsbirkaUnavailable() {
        EsbirkaSparqlClient unconfigured = clientFor("");
        SparqlEndpointUnavailableException ex = assertThatExceptionOfType(SparqlEndpointUnavailableException.class)
                .isThrownBy(() -> unconfigured.findLawByNumberYear("49", 1997))
                .actual();
        assertThat(ex).hasMessageContaining("not configured");
    }

    @Test
    void breakerOpensAfterConsecutiveFailuresAndStopsCallingUpstream() {
        WM.stubFor(any(anyUrl()).willReturn(serverError()));
        for (int i = 0; i < BREAKER_THRESHOLD; i++) {
            assertThatThrownBy(() -> client.findLawByNumberYear("49", 1997))
                    .isInstanceOf(SparqlEndpointUnavailableException.class);
        }
        int upstreamCalls = WM.getAllServeEvents().size();

        // Every operation shares the breaker, not just the one that failed.
        SparqlEndpointUnavailableException ex = assertThatExceptionOfType(SparqlEndpointUnavailableException.class)
                .isThrownBy(() -> client.fetchVersions(LAW_IRI))
                .actual();

        assertThat(ex).hasMessageContaining("circuit breaker open");
        assertThat(WM.getAllServeEvents()).as("an open breaker must not reach the endpoint").hasSize(upstreamCalls);
    }

    // --- helpers ------------------------------------------------------------

    private static SparqlJson.Binding[] lawRow(String akt, String citace, String cislo, SparqlJson.Binding rok) {
        return new SparqlJson.Binding[] {
                uri("akt", akt), literal("citace", citace), literal("cislo", cislo), rok, literal("sbirka", "sb")};
    }

    private static SparqlJson.Binding[] fragmentRow(String fragmentIri, String parentIri, String citace, String order) {
        return new SparqlJson.Binding[] {
                uri("fragment", fragmentIri), uri("parent", parentIri),
                literal("citace", citace), literal("order", order)};
    }

    /**
     * A whole-version content row. A null {@code obsah} or {@code parentIri} leaves the variable
     * unbound, as an unmatched OPTIONAL does.
     */
    private static SparqlJson.Binding[] contentRow(String fragmentIri, String parentIri, String citace,
                                                   String order, String obsah) {
        return new SparqlJson.Binding[] {
                uri("fragment", fragmentIri), uri("parent", parentIri),
                literal("citace", citace), literal("order", order), literal("obsah", obsah)};
    }

    private static void stubSparql(String body) {
        WM.stubFor(any(anyUrl()).willReturn(sparqlJson(body)));
    }

    private static void stubSparql(SparqlJson result) {
        stubSparql(result.toJson());
    }
}
