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

import static org.assertj.core.api.Assertions.assertThat;

class EsbirkaRestMapperVersionsTest {

    private static final String B = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz";

    @ParameterizedTest
    @ValueSource(strings = {"sb-1997-49", "sb-2005-410", "sb-2012-89"})
    void versionsMatchSparqlRows(String law) {
        List<LawVersionModel> versions = EsbirkaRestMapper.toVersions(EsbirkaRestFixtures.historie(law));
        Map<String, SparqlVersion> sparql = EsbirkaRestFixtures.sparqlVersions(law).stream()
                .collect(Collectors.toMap(SparqlVersion::iri, Function.identity()));

        assertThat(versions.stream().map(LawVersionModel::getIri).collect(Collectors.toSet()))
                .isEqualTo(sparql.keySet());
        for (LawVersionModel v : versions) {
            SparqlVersion s = sparql.get(v.getIri());
            assertThat(String.valueOf(v.getUcinnostOd())).as("od of " + v.getIri()).isEqualTo(s.ucinnostOd());
            assertThat(v.getUcinnostDo() == null ? null : v.getUcinnostDo().toString())
                    .as("do of " + v.getIri())
                    .isEqualTo(s.ucinnostDo());
            assertThat(v.getVersionType()).as("type of " + v.getIri()).isEqualTo(s.typ());
        }
        for (int i = 1; i < versions.size(); i++) {
            assertThat(versions.get(i).getUcinnostOd().isAfter(versions.get(i - 1).getUcinnostOd()))
                    .as("newest first")
                    .isFalse();
        }
        assertThat(versions.stream().filter(LawVersionModel::isLatest).count()).as("exactly one latest").isEqualTo(1);
    }

    @Test
    void latestIsTheVersionInForceLikeSparqlWhenNoFutureVersionExists() {
        assertThat(restLatest("sb-1997-49")).isEqualTo(sparqlLatest("sb-1997-49"));
        assertThat(restLatest("sb-1997-49")).isEqualTo(B + "/sb/1997/49/2025-11-01");
    }

    @Test
    void latestSkipsAPublishedFutureVersion() {
        assertThat(sparqlLatest("sb-2012-89"))
                .as("SPARQL picks the future version")
                .isEqualTo(B + "/sb/2012/89/2027-01-01");
        assertThat(restLatest("sb-2012-89")).as("REST picks the one in force").isEqualTo(B + "/sb/2012/89/2026-01-01");
    }

    @Test
    void latestFallsBackToNewestWhenNoneIsInForce() {
        assertThat(restLatest("sb-2005-410")).isEqualTo(B + "/sb/2005/410/2022-10-11");
        assertThat(restLatest("sb-2005-410")).isEqualTo(sparqlLatest("sb-2005-410"));
    }

    @Test
    void lawHeaderFromDetail() {
        LawModel law = EsbirkaRestMapper.toLaw(EsbirkaRestFixtures.detail("sb-1997-49"));

        assertThat(law).isEqualTo(new LawModel(B + "/sb/1997/49", "49/1997 Sb.", "49", 1997, "sb"));
    }

    @Test
    void lawHeaderFromVersionDetail() {
        LawModel law = EsbirkaRestMapper.toLaw(EsbirkaRestFixtures.detail("sb-2012-89-2014"));

        assertThat(law).isEqualTo(new LawModel(B + "/sb/2012/89", "89/2012 Sb.", "89", 2012, "sb"));
        assertThat(EsbirkaRestFixtures.detail("sb-2012-89-2014").datumUcinnostiZneniOd())
                .isEqualTo(LocalDate.of(2014, 1, 1));
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
