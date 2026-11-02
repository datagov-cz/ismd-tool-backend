package com.dia.ismdtoolbackend.client.esbirka;

import com.dia.ismdtoolbackend.client.esbirka.EsbirkaRestFixtures.SparqlFragment;
import com.dia.ismdtoolbackend.models.eli.FragmentModel;
import com.dia.ismdtoolbackend.utility.eli.EsbirkaFragmentKind;
import com.dia.ismdtoolbackend.utility.eli.EsbirkaHtmlText;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EsbirkaRestMapper#toFragments} against the SPARQL rows recorded for the same fragments:
 * every IRI, parent, order and citation must match, and the sanitized bodies must agree.
 */
class EsbirkaRestMapperFragmentsTest {

    private static final String B = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz";
    private static final String V49 = B + "/sb/1997/49/2025-11-01/dokument";

    @ParameterizedTest
    @ValueSource(strings = {"sb-1997-49", "sb-2005-410", "sb-1993-1", "sb-1992-586", "sb-2007-361",
            "sb-2012-89-2014", "sb-2015-294"})
    void matchesSparqlFragmentForFragment(String law) {
        List<FragmentModel> mapped = EsbirkaRestMapper.toFragments(EsbirkaRestFixtures.pages(law));
        List<SparqlFragment> sparql = EsbirkaRestFixtures.sparqlContent(law).stream()
                .sorted(Comparator.comparing(SparqlFragment::order)).toList();

        assertEquals(sparql.stream().map(SparqlFragment::iri).toList(),
                mapped.stream().map(FragmentModel::getIri).toList(), law + ": fragment set and order");
        for (int i = 0; i < mapped.size(); i++) {
            FragmentModel m = mapped.get(i);
            SparqlFragment s = sparql.get(i);
            assertEquals(s.parent(), m.getParentIri(), "parent of " + m.getIri());
            assertEquals(s.citace(), m.getCitation(), "citation of " + m.getIri());
            assertEquals(EsbirkaFragmentKind.fromIri(s.iri()), m.getKind(), "kind of " + m.getIri());
            assertEquals(EsbirkaHtmlSanitizer.sanitize(s.obsah()), m.getBodyHtml(), "body of " + m.getIri());
            assertEquals(EsbirkaHtmlText.toPlainText(s.obsah()), EsbirkaHtmlText.toPlainText(m.getBodyHtml()),
                    "text of " + m.getIri());
            if (i > 0) {
                assertTrue(mapped.get(i - 1).getOrder().compareTo(m.getOrder()) < 0, "order must be lex-increasing");
            }
        }
    }

    @Test
    void parentStackCarriesAcrossPageBoundaries() {
        List<EsbirkaRestDtos.FragmentPage> pages = EsbirkaRestFixtures.pages("sb-1997-49");
        int page0 = pages.get(0).seznam().size();
        List<FragmentModel> mapped = EsbirkaRestMapper.toFragments(pages);

        FragmentModel firstOfPage1 = mapped.get(page0);
        assertNotNull(firstOfPage1.getParentIri());
        assertTrue(mapped.subList(0, page0).stream().anyMatch(f -> f.getIri().equals(firstOfPage1.getParentIri())),
                "first fragment of page 1 has its parent on page 0");
    }

    @Test
    void groupersWithoutIriPrefixHangUnderTheirGrouper() {
        Map<String, FragmentModel> byIri = byIri("sb-1997-49");
        String grouper = V49 + "/norma/cast_3/hlava_2/par_22b/frag_3305333";

        assertEquals(V49 + "/norma/cast_3/hlava_2/par_22b", byIri.get(grouper).getParentIri());
        assertNull(byIri.get(grouper).getCitation(), "grouper repeating its paragraph carries no citation");
        assertEquals(grouper, byIri.get(V49 + "/norma/cast_3/hlava_2/par_22b/pism_a").getParentIri());
        assertEquals("§ 22b písm. a)", byIri.get(V49 + "/norma/cast_3/hlava_2/par_22b/pism_a").getCitation());
    }

    @Test
    void containersAndDocumentRootCarryNoCitation() {
        Map<String, FragmentModel> byIri = byIri("sb-1997-49");

        assertNull(byIri.get(V49).getParentIri());
        assertNull(byIri.get(V49).getCitation());
        assertNull(byIri.get(V49 + "/norma").getCitation());
        assertEquals("Část 1", byIri.get(V49 + "/norma/cast_1").getCitation());
    }

    @Test
    void stripsMultiWordActTypes() {
        assertTrue(citations("sb-1992-586").stream().anyMatch("§ 1"::equals), "Zákon České národní rady (4 words)");
        assertTrue(citations("sb-1993-1").stream().anyMatch("Čl. 1"::equals), "Ústavní zákon České národní rady (5 words)");
        assertTrue(citations("sb-1993-1").stream().noneMatch(c -> c.contains(" č. ") || c.contains("rady")));
    }

    @Test
    void rebuildsUpperCaseHeadingsFromTheIri() {
        List<String> citations = citations("sb-2012-89-2014");

        assertTrue(citations.contains("Část 1 Hlava 1 Díl 1"));
        assertTrue(citations.stream().anyMatch(c -> c.contains("Pododdíl ")), "pododdíl label");
        assertTrue(citations.stream().noneMatch(c -> c.contains("ČÁST") || c.contains("HLAVA")));
    }

    @Test
    void keepsAnnexFootnoteCitations() {
        assertTrue(citations("sb-2007-361").stream().anyMatch(c -> c.startsWith("pozn.")), "annex footnotes keep theirs");
    }

    private static Map<String, FragmentModel> byIri(String law) {
        return EsbirkaRestMapper.toFragments(EsbirkaRestFixtures.pages(law)).stream()
                .collect(Collectors.toMap(FragmentModel::getIri, Function.identity()));
    }

    private static List<String> citations(String law) {
        return EsbirkaRestMapper.toFragments(EsbirkaRestFixtures.pages(law)).stream()
                .map(FragmentModel::getCitation).filter(c -> c != null).toList();
    }
}
