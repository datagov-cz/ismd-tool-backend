package com.dia.ismdtoolbackend.client.esbirka;

import com.dia.ismdtoolbackend.client.esbirka.EsbirkaRestFixtures.SparqlVersion;
import com.dia.ismdtoolbackend.models.eli.LawModel;
import com.dia.ismdtoolbackend.models.eli.LawVersionModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EsbirkaRestMapperVersionsTest {

    private static final String B = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz";

    @ParameterizedTest
    @ValueSource(strings = {"sb-1997-49", "sb-2005-410", "sb-2012-89"})
    void versionsMatchSparqlRows(String law) {
        List<LawVersionModel> versions = EsbirkaRestMapper.toVersions(EsbirkaRestFixtures.historie(law));
        Map<String, SparqlVersion> sparql = EsbirkaRestFixtures.sparqlVersions(law).stream()
                .collect(Collectors.toMap(SparqlVersion::iri, Function.identity()));

        assertEquals(sparql.keySet(), versions.stream().map(LawVersionModel::getIri).collect(Collectors.toSet()));
        for (LawVersionModel v : versions) {
            SparqlVersion s = sparql.get(v.getIri());
            assertEquals(s.ucinnostOd(), String.valueOf(v.getUcinnostOd()), "od of " + v.getIri());
            assertEquals(s.ucinnostDo(), v.getUcinnostDo() == null ? null : v.getUcinnostDo().toString(), "do of " + v.getIri());
            assertEquals(s.typ(), v.getVersionType(), "type of " + v.getIri());
        }
        for (int i = 1; i < versions.size(); i++) {
            assertTrue(!versions.get(i).getUcinnostOd().isAfter(versions.get(i - 1).getUcinnostOd()), "newest first");
        }
        assertEquals(1, versions.stream().filter(LawVersionModel::isLatest).count(), "exactly one latest");
    }

    @Test
    void latestIsTheVersionInForceLikeSparqlWhenNoFutureVersionExists() {
        assertEquals(sparqlLatest("sb-1997-49"), restLatest("sb-1997-49"));
        assertEquals(B + "/sb/1997/49/2025-11-01", restLatest("sb-1997-49"));
    }

    @Test
    void latestSkipsAPublishedFutureVersion() {
        assertEquals(B + "/sb/2012/89/2027-01-01", sparqlLatest("sb-2012-89"), "SPARQL picks the future version");
        assertEquals(B + "/sb/2012/89/2026-01-01", restLatest("sb-2012-89"), "REST picks the one in force");
    }

    @Test
    void latestFallsBackToNewestWhenNoneIsInForce() {
        assertEquals(B + "/sb/2005/410/2022-10-11", restLatest("sb-2005-410"));
        assertEquals(sparqlLatest("sb-2005-410"), restLatest("sb-2005-410"));
    }

    @Test
    void lawHeaderFromDetail() {
        LawModel law = EsbirkaRestMapper.toLaw(EsbirkaRestFixtures.detail("sb-1997-49"));

        assertEquals(new LawModel(B + "/sb/1997/49", "49/1997 Sb.", "49", 1997, "sb"), law);
    }

    @Test
    void lawHeaderFromVersionDetail() {
        LawModel law = EsbirkaRestMapper.toLaw(EsbirkaRestFixtures.detail("sb-2012-89-2014"));

        assertEquals(new LawModel(B + "/sb/2012/89", "89/2012 Sb.", "89", 2012, "sb"), law);
        assertEquals(LocalDate.of(2014, 1, 1), EsbirkaRestFixtures.detail("sb-2012-89-2014").datumUcinnostiZneniOd());
    }

    private static String restLatest(String law) {
        return EsbirkaRestMapper.toVersions(EsbirkaRestFixtures.historie(law)).stream()
                .filter(LawVersionModel::isLatest).map(LawVersionModel::getIri).findFirst().orElseThrow();
    }

    private static String sparqlLatest(String law) {
        return EsbirkaRestFixtures.sparqlVersions(law).stream()
                .filter(v -> "1".equals(v.isLatest()) || "true".equals(v.isLatest()))
                .map(SparqlVersion::iri).findFirst().orElseThrow();
    }
}
