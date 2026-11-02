package com.dia.ismdtoolbackend.client.esbirka;

import com.dia.ismdtoolbackend.client.esbirka.EsbirkaRestFixtures.SparqlFragment;
import com.dia.ismdtoolbackend.client.esbirka.EsbirkaRestFixtures.SparqlVersion;
import com.dia.ismdtoolbackend.models.eli.FragmentModel;
import com.dia.ismdtoolbackend.models.eli.LawVersionModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every IRI the REST path emits keeps today's exact form — canonical host + ELI path — because
 * {@code ismd-ai} accepts only {@code https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/...} and
 * stored legal sources hold these IRIs.
 */
class EsbirkaRestIriContractTest {

    private static final String PREFIX = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/";
    private static final String LAW = "sb-1997-49";

    @Test
    void lawIri() {
        String iri = EsbirkaRestMapper.toLaw(EsbirkaRestFixtures.detail(LAW)).getIri();

        assertEquals(PREFIX + "sb/1997/49", iri);
        assertTrue(EsbirkaRestFixtures.sparqlVersions(LAW).stream().allMatch(v -> v.iri().startsWith(iri + "/")));
    }

    @Test
    void versionIrisEqualSparql() {
        List<LawVersionModel> versions = EsbirkaRestMapper.toVersions(EsbirkaRestFixtures.historie(LAW));

        assertTrue(versions.stream().allMatch(v -> v.getIri().startsWith(PREFIX)));
        assertEquals(EsbirkaRestFixtures.sparqlVersions(LAW).stream().map(SparqlVersion::iri).collect(Collectors.toSet()),
                versions.stream().map(LawVersionModel::getIri).collect(Collectors.toSet()));
    }

    @Test
    void fragmentAndParentIrisEqualSparql() {
        List<FragmentModel> fragments = EsbirkaRestMapper.toFragments(EsbirkaRestFixtures.pages(LAW));
        Set<String> sparql = EsbirkaRestFixtures.sparqlContent(LAW).stream().map(SparqlFragment::iri).collect(Collectors.toSet());

        assertTrue(fragments.stream().allMatch(f -> f.getIri().startsWith(PREFIX)));
        assertEquals(sparql, fragments.stream().map(FragmentModel::getIri).collect(Collectors.toSet()));
        assertTrue(fragments.stream().map(FragmentModel::getParentIri).filter(p -> p != null).allMatch(sparql::contains));
    }
}
