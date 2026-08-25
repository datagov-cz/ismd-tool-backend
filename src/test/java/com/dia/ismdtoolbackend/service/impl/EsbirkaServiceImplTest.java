package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.EsbirkaSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.FragmentDto;
import com.dia.ismdtoolbackend.controller.dto.LawDto;
import com.dia.ismdtoolbackend.controller.dto.LawVersionDto;
import com.dia.ismdtoolbackend.models.eli.FragmentModel;
import com.dia.ismdtoolbackend.models.eli.LawModel;
import com.dia.ismdtoolbackend.models.eli.LawVersionModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EsbirkaServiceImplTest {

    private static final String LAW_IRI = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187";
    private static final String VERSION_IRI = LAW_IRI + "/2026-04-01";
    private static final String NORMA_ROOT = VERSION_IRI + "/dokument/norma";

    @Mock
    private EsbirkaSparqlClient client;

    private EsbirkaServiceImpl service;

    @org.junit.jupiter.api.BeforeEach
    void buildService() {
        // `self` is the @Cacheable proxy in production; a plain self-reference here exercises
        // the one-arg delegation without the caching layer.
        service = new EsbirkaServiceImpl(client, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    // -------- searchLaws --------

    @Test
    void searchLawsMapsModelToDtoWithDisplayNameAndEliPath() {
        when(client.searchLaws("187", 20)).thenReturn(List.of(
                new LawModel(LAW_IRI, "187/2006 Sb.", "187", 2006, "sb")));
        List<LawDto> out = service.searchLaws("187", 20);
        assertEquals(1, out.size());
        LawDto dto = out.get(0);
        assertEquals(LAW_IRI, dto.getIri());
        assertEquals("/eli/cz/sb/2006/187", dto.getEliPath());
        assertEquals("https://opendata.eselpoint.gov.cz", dto.getDomain());
        assertEquals("Zákon č. 187/2006 Sb.", dto.getDisplayName());
        assertEquals("187/2006 Sb.", dto.getCitace());
        assertEquals("187", dto.getCislo());
        assertEquals(2006, dto.getRok());
        assertEquals("sb", dto.getSbirka());
    }

    @Test
    void searchLawsEmptyReturnsEmpty() {
        when(client.searchLaws(null, 20)).thenReturn(List.of());
        assertEquals(List.of(), service.searchLaws(null, 20));
    }

    // -------- getVersions --------

    @Test
    void getVersionsRejectsNonEsbirkaIri() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.getVersions("https://example.org/foo"));
        assertEquals("Neplatný identifikátor právního aktu.", ex.getMessage());
    }

    @Test
    void getVersionsRejectsNullIri() {
        assertThrows(IllegalArgumentException.class, () -> service.getVersions(null));
    }

    @Test
    void getVersionsMapsModelToDtoWithEliPathAndLatest() {
        when(client.fetchVersions(LAW_IRI)).thenReturn(List.of(
                new LawVersionModel(VERSION_IRI,
                        LocalDate.of(2026, 4, 1), null, "https://t/jednorazové", true)));
        List<LawVersionDto> out = service.getVersions(LAW_IRI);
        assertEquals(1, out.size());
        LawVersionDto dto = out.get(0);
        assertEquals(VERSION_IRI, dto.getIri());
        assertEquals("/eli/cz/sb/2006/187/2026-04-01", dto.getEliPath());
        assertEquals(LocalDate.of(2026, 4, 1), dto.getUcinnostOd());
        assertNull(dto.getUcinnostDo());
        assertEquals("https://t/jednorazové", dto.getVersionType());
        assertTrue(dto.isLatest());
    }

    // -------- getFragments: validation + plumbing --------

    @Test
    void getFragmentsRejectsNonEsbirkaIri() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.getFragments("javascript:alert(1)"));
        assertEquals("Neplatný identifikátor znění právního aktu.", ex.getMessage());
    }

    @Test
    void getFragmentsEmptyReturnsEmpty() {
        when(client.fetchFragments(VERSION_IRI)).thenReturn(List.of());
        assertEquals(List.of(), service.getFragments(VERSION_IRI));
    }

    // -------- getFragments: tree assembly (G7) --------

    @Test
    void singleRootBuildsLinearChain() {
        // norma -> par_1 -> odst_1
        String par = VERSION_IRI + "/par_1";
        String odst = par + "/odst_1";
        when(client.fetchFragments(VERSION_IRI)).thenReturn(List.of(
                new FragmentModel(par, NORMA_ROOT, "§ 1", "par", "0001"),
                new FragmentModel(odst, par, "§ 1 odst. 1", "odst", "0002")));
        List<FragmentDto> out = service.getFragments(VERSION_IRI);
        assertEquals(1, out.size());
        FragmentDto root = out.get(0);
        assertEquals(par, root.getIri());
        assertEquals("/eli/cz/sb/2006/187/2026-04-01/par_1", root.getEliPath());
        assertEquals("par", root.getKind());
        assertEquals(1, root.getChildren().size());
        assertEquals(odst, root.getChildren().get(0).getIri());
    }

    @Test
    void poznamkyPodcarouFragmentsBecomeRoots() {
        // Footnotes hang off <versionIri>/dokument/poznamkypodcarou, a second structural
        // root alongside /dokument/norma. They carry real text and must not be dropped.
        // (Live: labour law 262/2006 has 123 such footnote fragments, all text-bearing.)
        String poznamkyRoot = VERSION_IRI + "/dokument/poznamkypodcarou";
        String par = VERSION_IRI + "/par_1";
        String fn1 = poznamkyRoot + "/frag_1";
        String fn2 = poznamkyRoot + "/frag_2";
        when(client.fetchFragments(VERSION_IRI)).thenReturn(List.of(
                new FragmentModel(par, NORMA_ROOT, "§ 1", "par", "0001"),
                new FragmentModel(fn1, poznamkyRoot, "1)", "frag", "9001"),
                new FragmentModel(fn2, poznamkyRoot, "2)", "frag", "9002")));
        List<FragmentDto> out = service.getFragments(VERSION_IRI);
        assertEquals(3, out.size());
        assertEquals(par, out.get(0).getIri());
        assertEquals(fn1, out.get(1).getIri());
        assertEquals(fn2, out.get(2).getIri());
    }

    @Test
    void multiRootIsSupported() {
        String par1 = VERSION_IRI + "/par_1";
        String par2 = VERSION_IRI + "/par_2";
        when(client.fetchFragments(VERSION_IRI)).thenReturn(List.of(
                new FragmentModel(par1, NORMA_ROOT, "§ 1", "par", "0001"),
                new FragmentModel(par2, NORMA_ROOT, "§ 2", "par", "0002")));
        List<FragmentDto> out = service.getFragments(VERSION_IRI);
        assertEquals(2, out.size());
        assertEquals(par1, out.get(0).getIri());
        assertEquals(par2, out.get(1).getIri());
    }

    @Test
    void fragUnderParWhoseGrouperIsMissing_reparentsToNearestExistingAncestor() {
        // e-Sbírka nests text fragments under intermediate frag_* grouping nodes that
        // má-fragment-znění never returns. The child's parent IRI (.../par_1/frag_X) is not
        // in the set; walking the IRI path up one segment lands on par_1, which IS. The text
        // fragment must attach to par_1, not be dropped. (Live: 151/152 such rows on 262/2006.)
        String par = NORMA_ROOT + "/par_1";
        String missingGrouper = par + "/frag_6660499";
        String text = missingGrouper + "/text_1";
        when(client.fetchFragments(VERSION_IRI)).thenReturn(List.of(
                new FragmentModel(par, NORMA_ROOT, "§ 1", "par", "0001"),
                new FragmentModel(text, missingGrouper, "§ 1 text", "text", "0002")));
        List<FragmentDto> out = service.getFragments(VERSION_IRI);
        assertEquals(1, out.size());
        assertEquals(par, out.get(0).getIri());
        assertEquals(1, out.get(0).getChildren().size());
        assertEquals(text, out.get(0).getChildren().get(0).getIri());
    }

    @Test
    void prilohyDokumentContainerIsAThirdRoot() {
        // Besides norma and poznamkypodcarou, annexes live under <V>/dokument/prilohy.
        // A priloha fragment whose grouper parent is missing must walk up to the prilohy
        // container and become a root. (Live: priloha_0 on 262/2006.)
        String par = NORMA_ROOT + "/par_1";
        String prilohyRoot = VERSION_IRI + "/dokument/prilohy";
        String priloha = prilohyRoot + "/priloha_0";
        when(client.fetchFragments(VERSION_IRI)).thenReturn(List.of(
                new FragmentModel(par, NORMA_ROOT, "§ 1", "par", "0001"),
                new FragmentModel(priloha, prilohyRoot + "/frag_6668611", "Příloha", "priloha", "9999")));
        List<FragmentDto> out = service.getFragments(VERSION_IRI);
        assertEquals(2, out.size());
        assertEquals(par, out.get(0).getIri());
        assertEquals(priloha, out.get(1).getIri());
    }

    @Test
    void realisticMixedShape_losesNoFragment() {
        // End-to-end regression mirroring labour law 262/2006's structure: a norma subtree
        // with a text fragment nested under a MISSING frag_* grouper, a footnote root, and an
        // annex whose grouper is also missing. Every input row must appear in the tree exactly
        // once. (Pre-fix, ~11% of rows like these were dropped.)
        String cast = NORMA_ROOT + "/cast_1";
        String par = cast + "/par_1";
        String missingGrouper = par + "/frag_100";
        String text = missingGrouper + "/text_1";
        String footnote = VERSION_IRI + "/dokument/poznamkypodcarou/frag_9";
        String annexGrouper = VERSION_IRI + "/dokument/prilohy/frag_50";
        String annex = VERSION_IRI + "/dokument/prilohy/priloha_0";

        List<FragmentModel> rows = List.of(
                new FragmentModel(cast, NORMA_ROOT, "Část 1", "cast", "0001"),
                new FragmentModel(par, cast, "§ 1", "par", "0002"),
                new FragmentModel(text, missingGrouper, "§ 1 text", "text", "0003"),
                new FragmentModel(footnote, VERSION_IRI + "/dokument/poznamkypodcarou", "1)", "frag", "0004"),
                new FragmentModel(annex, annexGrouper, "Příloha 1", "priloha", "0005"));
        when(client.fetchFragments(VERSION_IRI)).thenReturn(rows);

        List<FragmentDto> out = service.getFragments(VERSION_IRI);

        // Walk the assembled tree and collect every IRI; must equal the 5 input IRIs.
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.Deque<FragmentDto> stack = new java.util.ArrayDeque<>(out);
        while (!stack.isEmpty()) {
            FragmentDto n = stack.pop();
            assertTrue(seen.add(n.getIri()), "fragment appeared twice: " + n.getIri());
            stack.addAll(n.getChildren());
        }
        assertEquals(java.util.Set.of(cast, par, text, footnote, annex), seen,
                "every input fragment must appear exactly once — no text lost");

        // Roots: cast (norma child), footnote, annex. text/par are nested, not roots.
        assertEquals(3, out.size());
    }

    @Test
    void unresolvableParentIsSurfacedAsRootNotDropped() {
        // A parent that resolves to neither an existing node nor a dokument container is a
        // genuine data anomaly. We surface the fragment as a root (its text is never lost)
        // and warn, rather than silently dropping it.
        String par = NORMA_ROOT + "/par_1";
        String ghost = VERSION_IRI + "/par_99/odst_5";
        when(client.fetchFragments(VERSION_IRI)).thenReturn(List.of(
                new FragmentModel(par, NORMA_ROOT, "§ 1", "par", "0001"),
                new FragmentModel(ghost, ghost + "-no-such", "ghost", "odst", "0003")));
        List<FragmentDto> out = service.getFragments(VERSION_IRI);
        assertEquals(2, out.size());
        assertEquals(par, out.get(0).getIri());
        assertEquals(ghost, out.get(1).getIri());
    }

    @Test
    void depthCapTrimsCyclicSubtreesAt10() {
        // Build a chain of depth 12 below the norma root; depth 11+ subtrees must be cleared.
        List<FragmentModel> rows = new ArrayList<>();
        String parent = NORMA_ROOT;
        String tip = null;
        for (int i = 1; i <= 12; i++) {
            String iri = VERSION_IRI + "/level_" + i;
            rows.add(new FragmentModel(iri, parent, "L" + i, "frag", String.format("%04d", i)));
            parent = iri;
            tip = iri;
        }
        when(client.fetchFragments(VERSION_IRI)).thenReturn(rows);
        List<FragmentDto> out = service.getFragments(VERSION_IRI);
        assertEquals(1, out.size());

        // capDepth fires when depth > MAX_FRAGMENT_DEPTH, clearing children of depth-MAX
        // nodes; so levels 1..MAX survive in the tree, level MAX+1 is trimmed.
        // Walking from level 1 to level MAX is MAX-1 hops; level MAX+1 has empty children.
        FragmentDto cursor = out.get(0);
        for (int hop = 1; hop <= EsbirkaServiceImpl.MAX_FRAGMENT_DEPTH; hop++) {
            assertEquals(1, cursor.getChildren().size(),
                    "expected one child at hop " + hop);
            cursor = cursor.getChildren().get(0);
        }
        // We're now at level MAX_FRAGMENT_DEPTH+1; its children were trimmed.
        assertTrue(cursor.getChildren().isEmpty(),
                "expected children cleared at depth cap, got: " + cursor.getChildren().size());
        assertNotNull(tip); // sanity: rows were built
    }

    @Test
    void over5000RowsTriggersWarnLogButReturnsTree() {
        List<FragmentModel> rows = new ArrayList<>(5_001);
        for (int i = 0; i < 5_001; i++) {
            String iri = VERSION_IRI + "/par_" + i;
            rows.add(new FragmentModel(iri, NORMA_ROOT, "§ " + i, "par", String.format("%05d", i)));
        }
        when(client.fetchFragments(VERSION_IRI)).thenReturn(rows);
        List<FragmentDto> out = service.getFragments(VERSION_IRI);
        // We don't assert the warn-log message here (covered by absence of failure);
        // but the tree itself still assembles fully.
        assertEquals(5_001, out.size());
    }

    // -------- getLawContent: number/year resolution --------

    @Test
    void getLawContentParsesNumberYearAndResolvesLatestVersion() {
        when(client.findLawByNumberYear("49", 1997)).thenReturn(java.util.Optional.of(
                new LawModel(LAW_IRI, "49/1997 Sb.", "49", 1997, "sb")));
        String olderIri = LAW_IRI + "/2020-01-01";
        when(client.fetchVersions(LAW_IRI)).thenReturn(List.of(
                new LawVersionModel(VERSION_IRI, LocalDate.of(2026, 4, 1), null, "t", true),
                new LawVersionModel(olderIri, LocalDate.of(2020, 1, 1), null, "t", false)));
        String par = VERSION_IRI + "/par_1";
        when(client.fetchVersionContent(VERSION_IRI)).thenReturn(List.of(
                new FragmentModel(par, NORMA_ROOT, "§ 1", "par", "0001", "<var>§ 1</var>")));

        com.dia.ismdtoolbackend.controller.dto.LawContentDto out = service.getLawContent("49/1997");

        assertEquals(LAW_IRI, out.getLawIri());
        assertEquals("49/1997 Sb.", out.getCitace());
        assertEquals(VERSION_IRI, out.getVersionIri());
        assertEquals(LocalDate.of(2026, 4, 1), out.getVersionDate());
        assertEquals(2, out.getVersions().size());
        assertEquals(1, out.getFragments().size());
        assertEquals("<var>§ 1</var>", out.getFragments().get(0).getBodyHtml());
    }

    @Test
    void getLawContentAssemblesNestedBodyHtmlInDocumentOrder() {
        // Server-side bodyHtml: a document-ordered, nested tree of <section> wrappers, each
        // carrying its fragment's body (null for structural nodes) followed by its children.
        when(client.findLawByNumberYear("49", 1997)).thenReturn(java.util.Optional.of(
                new LawModel(LAW_IRI, "49/1997 Sb.", "49", 1997, "sb")));
        when(client.fetchVersions(LAW_IRI)).thenReturn(List.of(
                new LawVersionModel(VERSION_IRI, LocalDate.of(2026, 4, 1), null, "t", true)));

        // cast_1 (structural, no body) -> par_1 (body) -> odst_1 (body), then par_2 (body).
        String cast = NORMA_ROOT + "/cast_1";
        String par1 = cast + "/par_1";
        String odst = par1 + "/odst_1";
        String par2 = NORMA_ROOT + "/par_2";
        when(client.fetchVersionContent(VERSION_IRI)).thenReturn(List.of(
                new FragmentModel(cast, NORMA_ROOT, "Část 1", "cast", "0001", null),
                new FragmentModel(par1, cast, "§ 1", "par", "0002", "<p>§ 1</p>"),
                new FragmentModel(odst, par1, "§ 1 odst. 1", "odst", "0003", "<p>(1)</p>"),
                new FragmentModel(par2, NORMA_ROOT, "§ 2", "par", "0004", "<p>§ 2</p>")));

        com.dia.ismdtoolbackend.controller.dto.LawContentDto out = service.getLawContent("49/1997");

        String body = out.getBodyHtml();
        // Document order: §1 before its odst, both before §2.
        assertTrue(body.indexOf("<p>§ 1</p>") < body.indexOf("<p>(1)</p>"), "§ 1 must precede its odst");
        assertTrue(body.indexOf("<p>(1)</p>") < body.indexOf("<p>§ 2</p>"), "odst must precede § 2");
        // Structural cast_1 contributes a wrapper but no body literal of its own.
        assertTrue(body.contains("data-kind=\"cast\""), "structural node still wrapped");
        assertTrue(body.contains("data-kind=\"odst\""));
        // Each section also carries the full fragment IRI in data-iri (FE navigation hook)
        // alongside the domain-stripped data-eli path.
        assertTrue(body.contains("data-iri=\"" + par1 + "\""),
                "section must expose the full fragment IRI in data-iri");
        assertTrue(body.contains("data-eli=\"/eli/cz/sb/2006/187/2026-04-01/dokument/norma/cast_1/par_1\""),
                "data-eli still carries the domain-stripped path");
        // Nesting: odst_1's section is INSIDE par_1's section (no closing </section> between
        // the §1 body and the (1) body — the odst opens before §1's section closes).
        int par1Body = body.indexOf("<p>§ 1</p>");
        int odstBody = body.indexOf("<p>(1)</p>");
        assertEquals(0, countCloseSections(body, par1Body, odstBody),
                "odst must nest inside par_1 — no </section> between their bodies");
        // Balanced wrappers: one <section> open and close per fragment (4 fragments).
        assertEquals(4, countOccurrences(body, "<section "), "one wrapper per fragment");
        assertEquals(4, countOccurrences(body, "</section>"), "wrappers must be balanced");
        // bodyHtml is emitted as-is (not escaped) — raw <p> tags survive.
        assertTrue(body.contains("<p>§ 1</p>"));
        // The tree is still present alongside the assembled body.
        assertEquals(2, out.getFragments().size());
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    private static int countCloseSections(String s, int from, int to) {
        return countOccurrences(s.substring(from, to), "</section>");
    }

    @Test
    void getLawContentEmptyContentYieldsEmptyBodyHtml() {
        when(client.findLawByNumberYear("49", 1997)).thenReturn(java.util.Optional.of(
                new LawModel(LAW_IRI, "49/1997 Sb.", "49", 1997, "sb")));
        when(client.fetchVersions(LAW_IRI)).thenReturn(List.of(
                new LawVersionModel(VERSION_IRI, LocalDate.of(2026, 4, 1), null, "t", true)));
        when(client.fetchVersionContent(VERSION_IRI)).thenReturn(List.of());

        com.dia.ismdtoolbackend.controller.dto.LawContentDto out = service.getLawContent("49/1997");
        assertEquals("", out.getBodyHtml());
        assertTrue(out.getFragments().isEmpty());
    }

    @Test
    void getLawContentPicksLatestEvenWhenNotFirstRow() {
        when(client.findLawByNumberYear("49", 1997)).thenReturn(java.util.Optional.of(
                new LawModel(LAW_IRI, "49/1997 Sb.", "49", 1997, "sb")));
        String latestIri = LAW_IRI + "/2026-04-01";
        when(client.fetchVersions(LAW_IRI)).thenReturn(List.of(
                new LawVersionModel(LAW_IRI + "/2020-01-01", LocalDate.of(2020, 1, 1), null, "t", false),
                new LawVersionModel(latestIri, LocalDate.of(2026, 4, 1), null, "t", true)));
        when(client.fetchVersionContent(latestIri)).thenReturn(List.of());

        com.dia.ismdtoolbackend.controller.dto.LawContentDto out = service.getLawContent("49/1997");
        assertEquals(latestIri, out.getVersionIri());
    }

    @Test
    void getLawContentTrimsAndStripsSbSuffix() {
        when(client.findLawByNumberYear("49", 1997)).thenReturn(java.util.Optional.of(
                new LawModel(LAW_IRI, "49/1997 Sb.", "49", 1997, "sb")));
        when(client.fetchVersions(LAW_IRI)).thenReturn(List.of(
                new LawVersionModel(VERSION_IRI, LocalDate.of(2026, 4, 1), null, "t", true)));
        when(client.fetchVersionContent(VERSION_IRI)).thenReturn(List.of());

        com.dia.ismdtoolbackend.controller.dto.LawContentDto out = service.getLawContent("  49/1997 Sb. ");
        assertEquals(LAW_IRI, out.getLawIri());
    }

    @Test
    void getLawContentUnknownLawThrows() {
        when(client.findLawByNumberYear("999", 1997)).thenReturn(java.util.Optional.empty());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.getLawContent("999/1997"));
        assertEquals("Právní akt č. 999/1997 nebyl nalezen.", ex.getMessage());
    }

    @Test
    void getLawContentRejectsPartialInput() {
        // bare number is the FE's cue to use /law/search instead
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.getLawContent("49"));
        assertEquals("Referenci zadejte ve tvaru číslo/rok (např. 49/1997).", ex.getMessage());
    }

    @Test
    void getLawContentRejectsNonNumericYear() {
        assertThrows(IllegalArgumentException.class, () -> service.getLawContent("49/abc"));
    }

    @Test
    void getLawContentRejectsNonNumericNumber() {
        assertThrows(IllegalArgumentException.class, () -> service.getLawContent("abc/1997"));
    }

    @Test
    void getLawContentRejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> service.getLawContent("  "));
    }

    // -------- getLawContent: caller-selected znění --------

    @Test
    void getLawContentRendersSelectedVersionNotLatest() {
        String olderIri = LAW_IRI + "/2020-01-01";
        when(client.findLawByNumberYear("49", 1997)).thenReturn(java.util.Optional.of(
                new LawModel(LAW_IRI, "49/1997 Sb.", "49", 1997, "sb")));
        when(client.fetchVersions(LAW_IRI)).thenReturn(List.of(
                new LawVersionModel(VERSION_IRI, LocalDate.of(2026, 4, 1), null, "t", true),
                new LawVersionModel(olderIri, LocalDate.of(2020, 1, 1), null, "t", false)));
        when(client.fetchVersionContent(olderIri)).thenReturn(List.of(
                new FragmentModel(olderIri + "/dokument/norma/par_1", olderIri + "/dokument/norma",
                        "§ 1", "par", "0001", "<p>staré znění</p>")));

        com.dia.ismdtoolbackend.controller.dto.LawContentDto out =
                service.getLawContent("49/1997", olderIri);

        // The whole header must describe the SELECTED version, not the latest one.
        assertEquals(olderIri, out.getVersionIri());
        assertEquals(LocalDate.of(2020, 1, 1), out.getVersionDate());
        assertEquals("/eli/cz/sb/2006/187/2020-01-01", out.getVersionEliPath());
        assertFalse(out.isVersionLatest(), "selected an older znění — must not be flagged latest");
        assertTrue(out.getBodyHtml().contains("<p>staré znění</p>"));
        // The switcher list still carries every version.
        assertEquals(2, out.getVersions().size());
    }

    @Test
    void getLawContentNullVersionIriFallsBackToLatest() {
        when(client.findLawByNumberYear("49", 1997)).thenReturn(java.util.Optional.of(
                new LawModel(LAW_IRI, "49/1997 Sb.", "49", 1997, "sb")));
        when(client.fetchVersions(LAW_IRI)).thenReturn(List.of(
                new LawVersionModel(LAW_IRI + "/2020-01-01", LocalDate.of(2020, 1, 1), null, "t", false),
                new LawVersionModel(VERSION_IRI, LocalDate.of(2026, 4, 1), null, "t", true)));
        when(client.fetchVersionContent(VERSION_IRI)).thenReturn(List.of());

        com.dia.ismdtoolbackend.controller.dto.LawContentDto out =
                service.getLawContent("49/1997", null);
        assertEquals(VERSION_IRI, out.getVersionIri());
        assertTrue(out.isVersionLatest());
    }

    @Test
    void getLawContentBlankVersionIriFallsBackToLatest() {
        when(client.findLawByNumberYear("49", 1997)).thenReturn(java.util.Optional.of(
                new LawModel(LAW_IRI, "49/1997 Sb.", "49", 1997, "sb")));
        when(client.fetchVersions(LAW_IRI)).thenReturn(List.of(
                new LawVersionModel(VERSION_IRI, LocalDate.of(2026, 4, 1), null, "t", true)));
        when(client.fetchVersionContent(VERSION_IRI)).thenReturn(List.of());

        assertEquals(VERSION_IRI, service.getLawContent("49/1997", "   ").getVersionIri());
    }

    @Test
    void getLawContentRejectsVersionIriOfAnotherLaw() {
        // Host-shape validation alone passes this IRI; only membership in THIS law's version
        // list can reject it.
        String foreignVersion =
                "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/262/2026-04-01";
        when(client.findLawByNumberYear("49", 1997)).thenReturn(java.util.Optional.of(
                new LawModel(LAW_IRI, "49/1997 Sb.", "49", 1997, "sb")));
        when(client.fetchVersions(LAW_IRI)).thenReturn(List.of(
                new LawVersionModel(VERSION_IRI, LocalDate.of(2026, 4, 1), null, "t", true)));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.getLawContent("49/1997", foreignVersion));
        assertTrue(ex.getMessage().contains("nepatří k právnímu aktu"), ex.getMessage());
        // Must fail before spending a ~2 MB content fetch.
        verify(client, never()).fetchVersionContent(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void getLawContentRejectsNonEsbirkaVersionIri() {
        when(client.findLawByNumberYear("49", 1997)).thenReturn(java.util.Optional.of(
                new LawModel(LAW_IRI, "49/1997 Sb.", "49", 1997, "sb")));
        when(client.fetchVersions(LAW_IRI)).thenReturn(List.of(
                new LawVersionModel(VERSION_IRI, LocalDate.of(2026, 4, 1), null, "t", true)));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.getLawContent("49/1997", "https://example.org/foo"));
        assertEquals("Neplatný identifikátor znění právního aktu.", ex.getMessage());
    }

    @Test
    void getLawContentSingleArgOverloadStillRendersLatest() {
        // The pre-existing one-arg contract must be unchanged for existing callers.
        when(client.findLawByNumberYear("49", 1997)).thenReturn(java.util.Optional.of(
                new LawModel(LAW_IRI, "49/1997 Sb.", "49", 1997, "sb")));
        when(client.fetchVersions(LAW_IRI)).thenReturn(List.of(
                new LawVersionModel(LAW_IRI + "/2020-01-01", LocalDate.of(2020, 1, 1), null, "t", false),
                new LawVersionModel(VERSION_IRI, LocalDate.of(2026, 4, 1), null, "t", true)));
        when(client.fetchVersionContent(VERSION_IRI)).thenReturn(List.of());

        assertEquals(VERSION_IRI, service.getLawContent("49/1997").getVersionIri());
    }

    // -------- normalizeLawRef: @Cacheable key generator --------

    @Test
    void normalizeLawRefCollapsesEquivalentRefsToOneKey() {
        // normalizeLawRef is the SpEL key expression for @Cacheable on getLawContent
        // (#root.target.normalizeLawRef(#lawRef)). It MUST collapse trim/Sb.-suffix
        // variants to a single key, or equivalent refs each store a separate ~2 MB entry.
        assertEquals("49/1997", service.normalizeLawRef("49/1997"));
        assertEquals("49/1997", service.normalizeLawRef("  49/1997  "));
        assertEquals("49/1997", service.normalizeLawRef("49/1997 Sb."));
        assertEquals("49/1997", service.normalizeLawRef("  49/1997 Sb. "));
        // Leading-zero year is normalized via Integer parse (1997, not "1997 ").
        assertEquals("262/2006", service.normalizeLawRef("262/2006 Sb."));
    }

    @Test
    void normalizeLawRefRejectsPartialInputLikeGetLawContent() {
        // Same parser as getLawContent, so the cache-key path rejects junk identically
        // rather than producing a bogus key.
        assertThrows(IllegalArgumentException.class, () -> service.normalizeLawRef("49"));
        assertThrows(IllegalArgumentException.class, () -> service.normalizeLawRef("abc/1997"));
        assertThrows(IllegalArgumentException.class, () -> service.normalizeLawRef("  "));
    }

    // -------- DTO mapping edge cases --------

    @Test
    void searchLawsHandlesNullCislo() {
        when(client.searchLaws(null, 20)).thenReturn(List.of(
                new LawModel(LAW_IRI, "187/2006 Sb.", null, 2006, "sb")));
        List<LawDto> out = service.searchLaws(null, 20);
        assertNull(out.get(0).getCislo());
        assertEquals("Zákon č. 187/2006 Sb.", out.get(0).getDisplayName());
    }
}
