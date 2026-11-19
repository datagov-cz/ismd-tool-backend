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

import static org.assertj.core.api.Assertions.assertThat;

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

        assertThat(mapped.stream().map(FragmentModel::getIri).toList())
                .as(law + ": fragment set and order")
                .isEqualTo(sparql.stream().map(SparqlFragment::iri).toList());
        for (int i = 0; i < mapped.size(); i++) {
            FragmentModel m = mapped.get(i);
            SparqlFragment s = sparql.get(i);
            assertThat(m.getParentIri()).as("parent of " + m.getIri()).isEqualTo(s.parent());
            assertThat(m.getCitation()).as("citation of " + m.getIri()).isEqualTo(s.citace());
            assertThat(m.getKind()).as("kind of " + m.getIri()).isEqualTo(EsbirkaFragmentKind.fromIri(s.iri()));
            assertThat(m.getBodyHtml()).as("body of " + m.getIri()).isEqualTo(EsbirkaHtmlSanitizer.sanitize(s.obsah()));
            assertThat(EsbirkaHtmlText.toPlainText(m.getBodyHtml()))
                    .as("text of " + m.getIri())
                    .isEqualTo(EsbirkaHtmlText.toPlainText(s.obsah()));
            if (i > 0) {
                assertThat(mapped.get(i - 1).getOrder().compareTo(m.getOrder()))
                        .as("order must be lex-increasing")
                        .isLessThan(0);
            }
        }
    }

    @Test
    void parentStackCarriesAcrossPageBoundaries() {
        List<EsbirkaRestDtos.FragmentPage> pages = EsbirkaRestFixtures.pages("sb-1997-49");
        int page0 = pages.get(0).seznam().size();
        List<FragmentModel> mapped = EsbirkaRestMapper.toFragments(pages);

        FragmentModel firstOfPage1 = mapped.get(page0);
        assertThat(firstOfPage1.getParentIri()).isNotNull();
        assertThat(mapped.subList(0, page0).stream().anyMatch(f -> f.getIri().equals(firstOfPage1.getParentIri())))
                .as("first fragment of page 1 has its parent on page 0")
                .isTrue();
    }

    @Test
    void groupersWithoutIriPrefixHangUnderTheirGrouper() {
        Map<String, FragmentModel> byIri = byIri("sb-1997-49");
        String grouper = V49 + "/norma/cast_3/hlava_2/par_22b/frag_3305333";

        assertThat(byIri.get(grouper).getParentIri()).isEqualTo(V49 + "/norma/cast_3/hlava_2/par_22b");
        assertThat(byIri.get(grouper).getCitation()).as("grouper repeating its paragraph carries no citation").isNull();
        assertThat(byIri.get(V49 + "/norma/cast_3/hlava_2/par_22b/pism_a").getParentIri()).isEqualTo(grouper);
        assertThat(byIri.get(V49 + "/norma/cast_3/hlava_2/par_22b/pism_a").getCitation()).isEqualTo("§ 22b písm. a)");
    }

    @Test
    void containersAndDocumentRootCarryNoCitation() {
        Map<String, FragmentModel> byIri = byIri("sb-1997-49");

        assertThat(byIri.get(V49).getParentIri()).isNull();
        assertThat(byIri.get(V49).getCitation()).isNull();
        assertThat(byIri.get(V49 + "/norma").getCitation()).isNull();
        assertThat(byIri.get(V49 + "/norma/cast_1").getCitation()).isEqualTo("Část 1");
    }

    @Test
    void stripsMultiWordActTypes() {
        assertThat(citations("sb-1992-586").stream().anyMatch("§ 1"::equals))
                .as("Zákon České národní rady (4 words)")
                .isTrue();
        assertThat(citations("sb-1993-1").stream().anyMatch("Čl. 1"::equals))
                .as("Ústavní zákon České národní rady (5 words)")
                .isTrue();
        assertThat(citations("sb-1993-1").stream().noneMatch(c -> c.contains(" č. ") || c.contains("rady"))).isTrue();
    }

    @Test
    void rebuildsUpperCaseHeadingsFromTheIri() {
        List<String> citations = citations("sb-2012-89-2014");

        assertThat(citations).contains("Část 1 Hlava 1 Díl 1");
        assertThat(citations.stream().anyMatch(c -> c.contains("Pododdíl "))).as("pododdíl label").isTrue();
        assertThat(citations.stream().noneMatch(c -> c.contains("ČÁST") || c.contains("HLAVA"))).isTrue();
    }

    @Test
    void keepsAnnexFootnoteCitations() {
        assertThat(citations("sb-2007-361").stream().anyMatch(c -> c.startsWith("pozn.")))
                .as("annex footnotes keep theirs")
                .isTrue();
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
