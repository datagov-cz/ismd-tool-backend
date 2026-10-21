package com.dia.ismdtoolbackend.client.esbirka;

import com.dia.ismdtoolbackend.config.EsbirkaConfig;
import com.dia.ismdtoolbackend.controller.dto.LawDto;
import com.dia.ismdtoolbackend.controller.dto.LawSearchGroupDto;
import com.dia.ismdtoolbackend.controller.dto.LawSearchResultDto;
import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import com.dia.ismdtoolbackend.models.eli.FragmentModel;
import com.dia.ismdtoolbackend.models.eli.FragmentResolutionModel;
import com.dia.ismdtoolbackend.models.eli.LawModel;
import com.dia.ismdtoolbackend.models.eli.LawVersionModel;
import com.dia.ismdtoolbackend.service.EsbirkaService;
import com.dia.ismdtoolbackend.service.impl.EsbirkaServiceWiring;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

/** {@link EsbirkaRestClient} over WireMock serving responses recorded from the live API. */
class EsbirkaRestClientTest {

    private static final String B = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz";
    private static final String LAW_49 = B + "/sb/1997/49";
    private static final String V_49 = LAW_49 + "/2025-11-01";
    private static final String SEARCH = "/rozsirena-vyhledavani";
    private static final String SUGGEST = "/jednoducha-vyhledavani/nabidka";
    private static final String PAGES_49 = "/dokumenty-sbirky/%2Fsb%2F1997%2F49%2F2025-11-01/fragmenty?cisloStranky=";

    @RegisterExtension
    static final WireMockExtension WM = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private EsbirkaRestReader reader;
    private EsbirkaRestNameSearch nameSearch;
    private EsbirkaRestClient client;

    @BeforeEach
    void setUp() {
        EsbirkaConfig config = new EsbirkaConfig();
        config.getRest().setBaseUrl(WM.baseUrl());
        config.getRest().setApiKey("test-key");
        config.getRest().setMinRequestSpacingMs(1);
        config.getRest().getRetry().setInitialBackoffMs(5);
        EsbirkaRestTransport transport = new EsbirkaRestTransport(
                config,
                JsonMapper.builder().build(),
                new SimpleMeterRegistry()
        );
        reader = new EsbirkaRestReader(transport, config);
        nameSearch = new EsbirkaRestNameSearch(transport);
        client = new EsbirkaRestClient(transport, reader, nameSearch);
    }

    @AfterEach
    void tearDown() {
        reader.shutdown();
        nameSearch.shutdown();
        EsbirkaRequestDeadline.end();
    }

    // --- grouped search ------------------------------------------------------------------------

    @Test
    void shortPrefixKeepsTheExactGroupCompleteAndDropsThePartialLastGroup() {
        stubSearch("search-1");

        LawSearchResultDto result = service().searchLawsGrouped("1", 20);

        LawSearchGroupDto exact = result.getGroups().get(0);
        assertEquals("1", exact.getCislo());
        assertTrue(exact.isExactNumberMatch());
        assertEquals(125, exact.getCount(), "every act numbered 1");
        assertEquals("1/2026 Sb.", exact.getLaws().get(0).getCitace(), "newest act numbered 1 heads the group");
        assertTrue(result.isTruncated());
        assertTrue(result.getGroups().stream().noneMatch(g -> "102".equals(g.getCislo())),
                "last fetched group (102) may be partial and is dropped");
        assertEquals(20, result.getGroups().size());
        WM.verify(1, postRequestedFor(urlEqualTo(SEARCH)));
    }

    @Test
    void numberSearchFitsInOneCallAndIsComplete() {
        stubSearch("search-49");

        LawSearchResultDto result = service().searchLawsGrouped("49", 20);

        LawSearchGroupDto exact = result.getGroups().get(0);
        assertEquals("49", exact.getCislo());
        assertTrue(exact.getLaws().stream().map(LawDto::getIri).anyMatch(LAW_49::equals));
        tools.jackson.databind.JsonNode rows = EsbirkaRestFixtures.JSON.readTree(EsbirkaRestFixtures.searchResponse("search-49")).get("seznam");
        long numbered49 = 0;
        boolean otherCollection = false;
        for (tools.jackson.databind.JsonNode row : rows) {
            String[] segments = row.get("staleUrl").asString().split("/");
            if ("49".equals(segments[3])) {
                numbered49++;
                otherCollection |= !"sb".equals(segments[1]);
            }
        }
        assertEquals(numbered49, exact.getCount(), "count covers every collection, as SPARQL");
        assertTrue(otherCollection, "fixture holds acts numbered 49 outside Sb.");
        assertFalse(result.isTruncated());
        assertEquals(List.of("49", "490", "491"), result.getGroups().stream().limit(3).map(LawSearchGroupDto::getCislo).toList());
    }

    @Test
    void yearPrefixBecomesARange() {
        stubSearch("search-49-199");

        LawSearchResultDto result = service().searchLawsGrouped("49/199", 20);

        assertTrue(result.getGroups().stream().flatMap(g -> g.getLaws().stream())
                .allMatch(l -> l.getRok() >= 1990 && l.getRok() <= 1999));
        assertTrue(result.getGroups().get(0).getLaws().stream().map(LawDto::getIri).anyMatch(LAW_49::equals));
    }

    @Test
    void lettersMatchNothingWithoutCallingUpstream() {
        assertTrue(client.searchLawsByNumber("abc", null, 20).groups().isEmpty());
        assertTrue(client.searchLawsByNumber("49", "19x", 20).groups().isEmpty());
        WM.verify(0, anyRequestedFor(anyUrl()));
    }

    // --- law, versions, content ----------------------------------------------------------------

    @Test
    void findLawReadsTheCollectionsDetail() {
        WM.stubFor(get(urlEqualTo("/dokumenty-sbirky/%2Fsb%2F1997%2F49"))
                .willReturn(json(EsbirkaRestFixtures.text("sb-1997-49/detail.json"))));

        assertEquals(Optional.of(new LawModel(LAW_49, "49/1997 Sb.", "49", 1997, "sb")), client.findLaw("sb", "49", 1997));
    }

    @Test
    void missingDocumentsAreEmptyResults() {
        WM.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                .withBody(EsbirkaRestFixtures.text("errors/chyby-dokument-nenalezen.json"))));

        assertEquals(Optional.empty(), client.findLaw("sb", "99999", 1997));
        assertEquals(List.of(), client.fetchVersions(B + "/sb/1997/99999"));
        assertEquals(List.of(), client.fetchVersionContent(B + "/sb/1997/99999/2025-11-01"));
        assertEquals(Optional.empty(), client.resolveFragment(B + "/sb/1997/99999/2025-11-01/dokument/norma/par_1",
                B + "/sb/1997/99999/2025-11-01", B + "/sb/1997/99999"));
    }

    @Test
    void versionsComeFromHistorie() {
        stubHistorie();

        List<LawVersionModel> versions = client.fetchVersions(LAW_49);

        assertEquals(35, versions.size());
        assertEquals(V_49, versions.get(0).getIri());
        assertTrue(versions.get(0).isLatest());
    }

    @Test
    void versionContentReadsEveryPage() {
        stubPages();

        List<FragmentModel> content = client.fetchVersionContent(V_49);

        assertEquals(2508, content.size());
        for (int n = 0; n < 3; n++) {
            WM.verify(1, getRequestedFor(urlEqualTo(PAGES_49 + n)));
        }
        assertEquals(EsbirkaRestMapper.toFragments(EsbirkaRestFixtures.pages("sb-1997-49")), content);
    }

    @Test
    void resolveReadsTheFragmentFromItsVersion() {
        stubPages();
        stubHistorie();

        Optional<FragmentResolutionModel> resolved = client.resolveFragment(
                V_49 + "/dokument/norma/cast_3/hlava_2/par_22b/pism_a", V_49, LAW_49);

        assertTrue(resolved.isPresent());
        assertEquals("§ 22b písm. a)", resolved.get().citation());
        assertTrue(resolved.get().isLatest());
        assertNull(resolved.get().versionValidUntil());
        assertTrue(resolved.get().bodyHtml().contains("zajistit dodržování limitů"));
        assertEquals(Optional.empty(), client.resolveFragment(V_49 + "/dokument/norma/par_99999", V_49, LAW_49));
    }

    @Test
    void anUndatedVersionIsAMissingVersionNotAnError() {
        String undated = LAW_49 + "/aktualni";

        assertEquals(List.of(), client.fetchVersionContent(undated));
        assertEquals(Optional.empty(), client.resolveFragment(undated + "/dokument/norma/par_1", undated, LAW_49));
        WM.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void callsDrawFromTheRequestDeadline() {
        stubHistorie();
        EsbirkaRequestDeadline.begin(EsbirkaDeadline.after(Duration.ZERO));

        assertThrows(SparqlEndpointUnavailableException.class, () -> client.fetchVersions(LAW_49));
        WM.verify(0, anyRequestedFor(anyUrl()));
    }

    // --- helpers -------------------------------------------------------------------------------

    // --- name search ---------------------------------------------------------------------------

    @Test
    void nameSearchAsksSuggesterAndTitleSearchThenRanks() {
        stubNameSearch("civilním letectví");

        LawSearchResultDto result = service().searchLawsGrouped("civilním letectví", 20);

        LawDto top = result.getGroups().get(0).getLaws().get(0);
        assertEquals(LAW_49, top.getIri());
        assertEquals("Zákon č. 49/1997 Sb., o civilním letectví", top.getDisplayName());
        WM.verify(1, getRequestedFor(urlPathEqualTo(SUGGEST))
                .withQueryParam("text", equalTo("civilním letectví"))
                .withQueryParam("maxPocet", equalTo("100")));
        WM.verify(1, postRequestedFor(urlEqualTo(SEARCH)).withRequestBody(equalToJson("""
                {"start":0,"pocet":100,"rozsahVyhledavani":["PLATNE"],"dalsiPodminky":[
                 {"polozka":"NAZEV_PRAVNIHO_AKTU","operace":"OBSAHUJE","hodnotaText":"civilním letectví",
                  "logickaOperace":"AND"}]}""")));
    }

    @Test
    void accentlessZakonOIsFoundThroughTheStrippedQuery() {
        stubNameSearch("zakon o civilnim letectvi");
        stubNameSearch("o civilnim letectvi");

        LawSearchResultDto result = service().searchLawsGrouped("zakon o civilnim letectvi", 20);

        assertEquals(LAW_49, result.getGroups().get(0).getLaws().get(0).getIri());
        WM.verify(2, getRequestedFor(urlPathEqualTo(SUGGEST)));
        WM.verify(2, postRequestedFor(urlEqualTo(SEARCH)));
    }

    @Test
    void numberSearchLabelsActsWithTheirTitle() {
        stubSearch("search-49");

        LawDto law = service().searchLawsGrouped("49", 20).getGroups().get(0).getLaws().stream()
                .filter(l -> LAW_49.equals(l.getIri())).findFirst().orElseThrow();

        assertTrue(law.getDisplayName().startsWith("Zákon č. 49/1997 Sb., o civilním letectví"), law.getDisplayName());
    }

    private EsbirkaService service() {
        return EsbirkaServiceWiring.forClient(client);
    }

    /**
     * Serves the recorded response only for the exact recorded request; any other body gets the
     * unsorted response, so a request that loses its sort order no longer finds the exact group.
     */
    private void stubSearch(String name) {
        WM.stubFor(post(urlEqualTo(SEARCH)).atPriority(10)
                .willReturn(json(EsbirkaRestFixtures.searchResponse("search-1-unsorted"))));
        WM.stubFor(post(urlEqualTo(SEARCH)).atPriority(1)
                .withRequestBody(equalToJson(EsbirkaRestFixtures.searchRequest(name)))
                .willReturn(json(EsbirkaRestFixtures.searchResponse(name))));
    }

    /** Serves the name-search responses recorded for {@code q} (fixture slug = folded query). */
    private void stubNameSearch(String q) {
        String slug = String.join("-", com.dia.ismdtoolbackend.utility.eli.EsbirkaLawTitle.fold(q).split(" "));
        WM.stubFor(get(urlPathEqualTo(SUGGEST)).withQueryParam("text", equalTo(q))
                .willReturn(json(EsbirkaRestFixtures.text("name-search/" + slug + ".nabidka.json"))));
        WM.stubFor(post(urlEqualTo(SEARCH)).withRequestBody(matchingJsonPath("$.dalsiPodminky[0].hodnotaText", equalTo(q)))
                .willReturn(json(EsbirkaRestFixtures.text("name-search/" + slug + ".nazev.json"))));
    }

    private void stubHistorie() {
        WM.stubFor(get(urlEqualTo("/dokumenty-sbirky/%2Fsb%2F1997%2F49/historie"))
                .willReturn(json(EsbirkaRestFixtures.text("sb-1997-49/historie.json"))));
    }

    private void stubPages() {
        for (int n = 0; n < 3; n++) {
            WM.stubFor(get(urlEqualTo(PAGES_49 + n))
                    .willReturn(json(EsbirkaRestFixtures.text("sb-1997-49/fragmenty-" + n + ".json"))));
        }
    }

    private static ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }
}
