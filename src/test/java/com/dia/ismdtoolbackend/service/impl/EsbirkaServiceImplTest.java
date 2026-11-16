package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.EsbirkaClient;
import com.dia.ismdtoolbackend.controller.dto.FragmentDto;
import com.dia.ismdtoolbackend.controller.dto.LawContentDto;
import com.dia.ismdtoolbackend.controller.dto.LawDto;
import com.dia.ismdtoolbackend.controller.dto.LawVersionDto;
import com.dia.ismdtoolbackend.models.eli.FragmentModel;
import com.dia.ismdtoolbackend.models.eli.LawNumberGroupModel;
import com.dia.ismdtoolbackend.models.eli.LawNumberSearchModel;
import com.dia.ismdtoolbackend.models.eli.LawVersionModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import static com.dia.ismdtoolbackend.testsupport.fixtures.EsbirkaFixtures.LAW_187_2006;
import static com.dia.ismdtoolbackend.testsupport.fixtures.EsbirkaFixtures.LAW_49_1997;
import static com.dia.ismdtoolbackend.testsupport.fixtures.EsbirkaFixtures.LEGACY_SB;
import static com.dia.ismdtoolbackend.testsupport.fixtures.EsbirkaFixtures.fragment;
import static com.dia.ismdtoolbackend.testsupport.fixtures.EsbirkaFixtures.law;
import static com.dia.ismdtoolbackend.testsupport.fixtures.EsbirkaFixtures.lawIri;
import static com.dia.ismdtoolbackend.testsupport.fixtures.EsbirkaFixtures.legacyLawIri;
import static com.dia.ismdtoolbackend.testsupport.fixtures.EsbirkaFixtures.version;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EsbirkaServiceImplTest {

    /** Act 187/2006 and one of its znění; the grouped-search, versions and fragment-tree tests use it. */
    private static final String VERSION_IRI = LAW_187_2006 + "/2026-04-01";
    private static final String NORMA_ROOT = VERSION_IRI + "/dokument/norma";

    /** Act 49/1997 and two of its znění; the law-content tests use it. */
    private static final String V_2026 = LAW_49_1997 + "/2026-04-01";
    private static final String V_2020 = LAW_49_1997 + "/2020-01-01";
    private static final String NORMA_2026 = V_2026 + "/dokument/norma";
    private static final LawVersionModel LATEST = version(V_2026, LocalDate.of(2026, 4, 1), true);
    private static final LawVersionModel OLDER = version(V_2020, LocalDate.of(2020, 1, 1), false);

    @Mock
    private EsbirkaClient client;

    private EsbirkaServiceImpl service;

    @BeforeEach
    void setUp() {
        service = EsbirkaServiceWiring.build(client, null, null);
    }

    /** Law 49/1997 resolves by číslo/rok and has the given znění. */
    private void givenLaw49(LawVersionModel... versions) {
        when(client.findLaw("sb", "49", 1997)).thenReturn(Optional.of(law("49", 1997)));
        when(client.fetchVersions(LAW_49_1997)).thenReturn(List.of(versions));
    }

    private static List<FragmentDto> assemble(FragmentModel... rows) {
        return EsbirkaFragmentTree.assembleTree(List.of(rows), VERSION_IRI);
    }

    private static FragmentModel row(String iri, String parent, String citation, String kind, String order) {
        return new FragmentModel(iri, parent, citation, kind, order);
    }

    @Nested
    class GroupedSearch {

        @Test
        void mapsModelToDtoWithDisplayNameAndEliPath() {
            when(client.searchLawsByNumber("187", null, 20)).thenReturn(new LawNumberSearchModel(
                    List.of(new LawNumberGroupModel("187", 1)), List.of(law("187", 2006)), false));

            LawDto dto = service.searchLawsGrouped("187", 20).getGroups().get(0).getLaws().get(0);

            assertThat(dto.getIri()).isEqualTo(LAW_187_2006);
            assertThat(dto.getEliPath()).isEqualTo("/eli/cz/sb/2006/187");
            assertThat(dto.getDomain()).isEqualTo("https://opendata.eselpoint.gov.cz");
            assertThat(dto.getDisplayName()).isEqualTo("Zákon č. 187/2006 Sb.");
            assertThat(dto.getCitace()).isEqualTo("187/2006 Sb.");
            assertThat(dto.getCislo()).isEqualTo("187");
            assertThat(dto.getRok()).isEqualTo(2006);
            assertThat(dto.getSbirka()).isEqualTo("sb");
        }
    }

    @Nested
    class Versions {

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = "https://example.org/foo")
        void rejectsAMissingOrNonEsbirkaIri(String iri) {
            assertThatThrownBy(() -> service.getVersions(iri))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Neplatný identifikátor právního aktu.");
        }

        /** The bare host carries no /esel-esb/ segment; canonicalization inserts it. */
        @ParameterizedTest
        @ValueSource(strings = {
                "https://opendata.eselpoint.cz/esel-esb/eli/cz/sb/2006/187",
                "https://eselpoint.cz/eli/cz/sb/2006/187"})
        void acceptsLegacyHostsAndQueriesTheCanonicalIri(String legacy) {
            when(client.fetchVersions(LAW_187_2006)).thenReturn(List.of(
                    version(VERSION_IRI, LocalDate.of(2026, 4, 1), true)));

            assertThat(service.getVersions(legacy)).singleElement()
                    .extracting(LawVersionDto::getIri).isEqualTo(VERSION_IRI);
            // e-Sbírka knows only the canonical host and would return nothing for the legacy string.
            verify(client).fetchVersions(LAW_187_2006);
        }

        @Test
        void stripsATrailingSlashBeforeQuerying() {
            when(client.fetchVersions(LAW_187_2006)).thenReturn(List.of(
                    version(VERSION_IRI, LocalDate.of(2026, 4, 1), true)));

            assertThat(service.getVersions(LAW_187_2006 + "/")).hasSize(1);

            verify(client).fetchVersions(LAW_187_2006);
        }

        @Test
        void mapsModelToDtoWithEliPathAndLatest() {
            when(client.fetchVersions(LAW_187_2006)).thenReturn(List.of(new LawVersionModel(
                    VERSION_IRI, LocalDate.of(2026, 4, 1), null, "https://t/jednorazové", true)));

            assertThat(service.getVersions(LAW_187_2006)).singleElement().satisfies(dto -> {
                assertThat(dto.getIri()).isEqualTo(VERSION_IRI);
                assertThat(dto.getEliPath()).isEqualTo("/eli/cz/sb/2006/187/2026-04-01");
                assertThat(dto.getUcinnostOd()).isEqualTo(LocalDate.of(2026, 4, 1));
                assertThat(dto.getUcinnostDo()).isNull();
                assertThat(dto.getVersionType()).isEqualTo("https://t/jednorazové");
                assertThat(dto.isLatest()).isTrue();
            });
        }
    }

    /** Tree shape: which fragments become roots, where orphans attach, and the depth cap. */
    @Nested
    class FragmentTreeShape {

        @Test
        void singleRootBuildsLinearChain() {
            String par = VERSION_IRI + "/par_1";
            String odst = par + "/odst_1";

            List<FragmentDto> out = assemble(
                    row(par, NORMA_ROOT, "§ 1", "par", "0001"),
                    row(odst, par, "§ 1 odst. 1", "odst", "0002"));

            assertThat(out).singleElement().satisfies(root -> {
                assertThat(root.getIri()).isEqualTo(par);
                assertThat(root.getEliPath()).isEqualTo("/eli/cz/sb/2006/187/2026-04-01/par_1");
                assertThat(root.getKind()).isEqualTo("par");
                assertThat(root.getChildren()).extracting(FragmentDto::getIri).containsExactly(odst);
            });
        }

        @Test
        void multiRootIsSupported() {
            String par1 = VERSION_IRI + "/par_1";
            String par2 = VERSION_IRI + "/par_2";

            List<FragmentDto> out = assemble(
                    row(par1, NORMA_ROOT, "§ 1", "par", "0001"),
                    row(par2, NORMA_ROOT, "§ 2", "par", "0002"));

            assertThat(out).extracting(FragmentDto::getIri).containsExactly(par1, par2);
        }

        /** Footnotes hang off {@code /dokument/poznamkypodcarou}, a second root beside the norma. */
        @Test
        void poznamkyPodcarouFragmentsBecomeRoots() {
            String poznamkyRoot = VERSION_IRI + "/dokument/poznamkypodcarou";
            String par = VERSION_IRI + "/par_1";
            String fn1 = poznamkyRoot + "/frag_1";
            String fn2 = poznamkyRoot + "/frag_2";

            List<FragmentDto> out = assemble(
                    row(par, NORMA_ROOT, "§ 1", "par", "0001"),
                    row(fn1, poznamkyRoot, "1)", "frag", "9001"),
                    row(fn2, poznamkyRoot, "2)", "frag", "9002"));

            assertThat(out).extracting(FragmentDto::getIri).containsExactly(par, fn1, fn2);
        }

        /**
         * e-Sbírka nests text under {@code frag_*} groupers that are never returned. Walking the IRI path up
         * one segment from the missing grouper lands on par_1, which the text attaches to.
         */
        @Test
        void fragUnderParWhoseGrouperIsMissing_reparentsToNearestExistingAncestor() {
            String par = NORMA_ROOT + "/par_1";
            String missingGrouper = par + "/frag_6660499";
            String text = missingGrouper + "/text_1";

            List<FragmentDto> out = assemble(
                    row(par, NORMA_ROOT, "§ 1", "par", "0001"),
                    row(text, missingGrouper, "§ 1 text", "text", "0002"));

            assertThat(out).singleElement().satisfies(root -> {
                assertThat(root.getIri()).isEqualTo(par);
                assertThat(root.getChildren()).extracting(FragmentDto::getIri).containsExactly(text);
            });
        }

        /** Annexes live under {@code /dokument/prilohy}; one whose grouper is missing becomes a root. */
        @Test
        void prilohyDokumentContainerIsAThirdRoot() {
            String par = NORMA_ROOT + "/par_1";
            String prilohyRoot = VERSION_IRI + "/dokument/prilohy";
            String priloha = prilohyRoot + "/priloha_0";

            List<FragmentDto> out = assemble(
                    row(par, NORMA_ROOT, "§ 1", "par", "0001"),
                    row(priloha, prilohyRoot + "/frag_6668611", "Příloha", "priloha", "9999"));

            assertThat(out).extracting(FragmentDto::getIri).containsExactly(par, priloha);
        }

        /**
         * The shape of labour law 262/2006: text under a missing grouper, a footnote root and an annex whose
         * grouper is also missing. Every input row appears in the tree exactly once.
         */
        @Test
        void realisticMixedShape_losesNoFragment() {
            String cast = NORMA_ROOT + "/cast_1";
            String par = cast + "/par_1";
            String missingGrouper = par + "/frag_100";
            String text = missingGrouper + "/text_1";
            String footnote = VERSION_IRI + "/dokument/poznamkypodcarou/frag_9";
            String annexGrouper = VERSION_IRI + "/dokument/prilohy/frag_50";
            String annex = VERSION_IRI + "/dokument/prilohy/priloha_0";

            List<FragmentDto> out = assemble(
                    row(cast, NORMA_ROOT, "Část 1", "cast", "0001"),
                    row(par, cast, "§ 1", "par", "0002"),
                    row(text, missingGrouper, "§ 1 text", "text", "0003"),
                    row(footnote, VERSION_IRI + "/dokument/poznamkypodcarou", "1)", "frag", "0004"),
                    row(annex, annexGrouper, "Příloha 1", "priloha", "0005"));

            assertThat(allIris(out)).as("every input fragment appears exactly once")
                    .containsExactlyInAnyOrder(cast, par, text, footnote, annex);
            assertThat(out).as("roots: cast, footnote, annex").hasSize(3);
        }

        /** A parent that resolves to nothing is a data anomaly; the fragment surfaces as a root. */
        @Test
        void unresolvableParentIsSurfacedAsRootNotDropped() {
            String par = NORMA_ROOT + "/par_1";
            String ghost = VERSION_IRI + "/par_99/odst_5";

            List<FragmentDto> out = assemble(
                    row(par, NORMA_ROOT, "§ 1", "par", "0001"),
                    row(ghost, ghost + "-no-such", "ghost", "odst", "0003"));

            assertThat(out).extracting(FragmentDto::getIri).containsExactly(par, ghost);
        }

        /** Levels 1..MAX+1 survive; the children of level MAX+1 are cleared. */
        @Test
        void depthCapTrimsCyclicSubtreesAt10() {
            List<FragmentModel> rows = new ArrayList<>();
            String parent = NORMA_ROOT;
            for (int i = 1; i <= EsbirkaFragmentTree.MAX_FRAGMENT_DEPTH + 2; i++) {
                String iri = VERSION_IRI + "/level_" + i;
                rows.add(row(iri, parent, "L" + i, "frag", String.format("%04d", i)));
                parent = iri;
            }

            List<FragmentDto> out = EsbirkaFragmentTree.assembleTree(rows, VERSION_IRI);

            assertThat(out).hasSize(1);
            FragmentDto cursor = out.get(0);
            for (int hop = 1; hop <= EsbirkaFragmentTree.MAX_FRAGMENT_DEPTH; hop++) {
                assertThat(cursor.getChildren()).as("one child at hop %d", hop).hasSize(1);
                cursor = cursor.getChildren().get(0);
            }
            assertThat(cursor.getChildren()).as("children cleared at the depth cap").isEmpty();
        }

        @Test
        void largeTreeAssemblesFully() {
            List<FragmentModel> rows = new ArrayList<>(5_001);
            for (int i = 0; i < 5_001; i++) {
                rows.add(row(VERSION_IRI + "/par_" + i, NORMA_ROOT, "§ " + i, "par", String.format("%05d", i)));
            }

            assertThat(EsbirkaFragmentTree.assembleTree(rows, VERSION_IRI)).hasSize(5_001);
        }

        private static List<String> allIris(List<FragmentDto> roots) {
            List<String> seen = new ArrayList<>();
            Deque<FragmentDto> stack = new ArrayDeque<>(roots);
            while (!stack.isEmpty()) {
                FragmentDto n = stack.pop();
                seen.add(n.getIri());
                stack.addAll(n.getChildren());
            }
            return seen;
        }
    }

    /** Navigation labels: structural containers, the document root and unnumbered text blocks. */
    @Nested
    class NavigationLabels {

        private static final String DOKUMENT = VERSION_IRI + "/dokument";

        /** Upstream has no citace for containers above the first citable unit. */
        @Test
        void structuralContainersGetDisplayLabels() {
            List<FragmentDto> out = assemble(
                    row(DOKUMENT, null, null, "dokument", "0001"),
                    row(DOKUMENT + "/norma", DOKUMENT, null, "norma", "0002"),
                    row(DOKUMENT + "/prilohy", DOKUMENT, null, "prilohy", "0003"));

            assertThat(out.get(0).getChildren()).extracting(FragmentDto::getCitation)
                    .containsExactly("Text předpisu", "Přílohy");
        }

        /** Real IRIs include {@code prilohy:2}; the suffix arrives as part of the kind. */
        @Test
        void repeatedContainerSiblingsAreDistinguishedByOrdinal() {
            List<FragmentDto> out = assemble(
                    row(DOKUMENT, null, null, "dokument", "0001"),
                    row(DOKUMENT + "/prilohy", DOKUMENT, null, "prilohy", "0002"),
                    row(DOKUMENT + "/prilohy:2", DOKUMENT, null, "prilohy:2", "0003"));

            assertThat(out.get(0).getChildren()).extracting(FragmentDto::getCitation)
                    .containsExactly("Přílohy", "Přílohy (2)");
        }

        @Test
        void documentRootIsLabelledWithLawCitationOnlyWhenKnown() {
            List<FragmentModel> rows = List.of(row(DOKUMENT, null, null, "dokument", "0001"));

            assertThat(EsbirkaFragmentTree.assembleTree(rows, VERSION_IRI).get(0).getCitation())
                    .as("without law metadata (the subtree body cache)").isNull();
            assertThat(EsbirkaFragmentTree.assembleTree(rows, VERSION_IRI, "49/1997 Sb.").get(0).getCitation())
                    .isEqualTo("Zákon č. 49/1997 Sb.");
        }

        @Test
        void upstreamCitationWinsOverContainerLabel() {
            List<FragmentDto> out = assemble(
                    row(DOKUMENT, null, null, "dokument", "0001"),
                    row(DOKUMENT + "/norma", DOKUMENT, "Vlastní název", "norma", "0002"));

            assertThat(out.get(0).getChildren().get(0).getCitation()).isEqualTo("Vlastní název");
        }

        /** A {@code frag_*} leaf is an unnumbered text block; its trailing number is an internal id. */
        @Test
        void childlessFragIsNonNavigableAndCarriesNoSyntheticCitation() {
            String par = VERSION_IRI + "/par_1";

            FragmentDto leaf = assemble(
                    row(par, NORMA_ROOT, "§ 1", "par", "0001"),
                    row(par + "/frag_6619443", par, null, "frag", "0002"))
                    .get(0).getChildren().get(0);

            assertThat(leaf.getCitation()).isNull();
            assertThat(leaf.isNavigable()).isFalse();
        }

        /** The 49/1997 shape {@code par_105/frag_1234029045}: a frag whose children are all text blocks. */
        @Test
        void fragWrappingOnlyTextBlocksIsNonNavigable() {
            String par = VERSION_IRI + "/par_105";
            String wrapper = par + "/frag_1234029045";

            FragmentDto node = assemble(
                    row(par, NORMA_ROOT, "§ 105", "par", "0001"),
                    row(wrapper, par, null, "frag", "0002"),
                    row(wrapper + "/frag_2", wrapper, null, "frag", "0003"))
                    .get(0).getChildren().get(0);

            assertThat(node.isNavigable()).as("a frag wrapping only text blocks is not a nav target").isFalse();
            assertThat(node.getCitation()).isNull();
            assertThat(node.getChildren().get(0).isNavigable())
                    .as("hiding it strands nothing visible").isFalse();
        }

        /** One citable unit anywhere below keeps the whole chain of wrappers reachable. */
        @Test
        void deeplyNestedNavigableDescendantKeepsAncestorsNavigable() {
            String par = VERSION_IRI + "/par_9";
            String outer = par + "/frag_1";
            String inner = outer + "/frag_2";

            FragmentDto o = assemble(
                    row(par, NORMA_ROOT, "§ 9", "par", "0001"),
                    row(outer, par, null, "frag", "0002"),
                    row(inner, outer, null, "frag", "0003"),
                    row(inner + "/pism_b", inner, "§ 9 písm. b)", "pism", "0004"))
                    .get(0).getChildren().get(0);

            assertThat(o.isNavigable()).as("outer wrapper").isTrue();
            FragmentDto i = o.getChildren().get(0);
            assertThat(i.isNavigable()).as("inner wrapper").isTrue();
            assertThat(i.getChildren().get(0).isNavigable()).isTrue();
        }

        /** Many {@code frag_*} nodes parent real §/písm./bod subtrees; hiding them would orphan those. */
        @Test
        void fragWithChildrenStaysNavigable() {
            String par = VERSION_IRI + "/par_3";
            String grouper = par + "/frag_6619455";

            FragmentDto node = assemble(
                    row(par, NORMA_ROOT, "§ 3", "par", "0001"),
                    row(grouper, par, null, "frag", "0002"),
                    row(par + "/pism_a", grouper, "§ 3 písm. a)", "pism", "0003"))
                    .get(0).getChildren().get(0);

            assertThat(node.isNavigable()).isTrue();
            assertThat(node.getChildren()).singleElement()
                    .satisfies(child -> assertThat(child.isNavigable()).isTrue());
        }

        /**
         * With every upstream citace absent (as in the 2026-08-24 outage), § and Část labels come from IRI
         * segments and containers from their own labels.
         */
        @Test
        void navigationSurvivesUpstreamCitationOutage() {
            String norma = DOKUMENT + "/norma";
            String cast = norma + "/cast_1";
            String par = cast + "/par_1";
            String odst = par + "/odst_2";

            List<FragmentDto> out = EsbirkaFragmentTree.assembleTree(List.of(
                    row(DOKUMENT, null, null, "dokument", "0001"),
                    row(norma, DOKUMENT, null, "norma", "0002"),
                    row(cast, norma, null, "cast", "0003"),
                    row(par, cast, null, "par", "0004"),
                    row(odst, par, null, "odst", "0005"),
                    row(odst + "/pism_a", odst, null, "pism", "0006")), VERSION_IRI, "49/1997 Sb.");

            FragmentDto root = out.get(0);
            FragmentDto normaDto = root.getChildren().get(0);
            FragmentDto castDto = normaDto.getChildren().get(0);
            FragmentDto parDto = castDto.getChildren().get(0);
            FragmentDto odstDto = parDto.getChildren().get(0);
            assertThat(root.getCitation()).isEqualTo("Zákon č. 49/1997 Sb.");
            assertThat(normaDto.getCitation()).isEqualTo("Text předpisu");
            assertThat(castDto.getCitation()).isEqualTo("Část 1");
            assertThat(parDto.getCitation()).as("structural ancestors drop once a § is present").isEqualTo("§ 1");
            assertThat(odstDto.getCitation()).isEqualTo("§ 1 odst. 2");
            assertThat(odstDto.getChildren().get(0).getCitation()).isEqualTo("§ 1 odst. 2 písm. a)");
        }
    }

    /** {@code getLawContent} addressed by "číslo/rok". */
    @Nested
    class LawContentByNumber {

        @Test
        void parsesNumberYearAndResolvesLatestVersion() {
            givenLaw49(LATEST, OLDER);
            when(client.fetchVersionContent(V_2026)).thenReturn(List.of(
                    new FragmentModel(NORMA_2026 + "/par_1", NORMA_2026, "§ 1", "par", "0001", "<var>§ 1</var>")));

            LawContentDto out = service.getLawContent("49/1997");

            assertThat(out.getLawIri()).isEqualTo(LAW_49_1997);
            assertThat(out.getCitace()).isEqualTo("49/1997 Sb.");
            assertThat(out.getVersionIri()).isEqualTo(V_2026);
            assertThat(out.getVersionDate()).isEqualTo(LocalDate.of(2026, 4, 1));
            assertThat(out.getVersions()).hasSize(2);
            assertThat(out.getFragments()).singleElement()
                    .extracting(FragmentDto::getBodyHtml).isEqualTo("<var>§ 1</var>");
        }

        @Test
        void picksLatestEvenWhenNotFirstRow() {
            givenLaw49(OLDER, LATEST);
            when(client.fetchVersionContent(V_2026)).thenReturn(List.of());

            assertThat(service.getLawContent("49/1997").getVersionIri()).isEqualTo(V_2026);
        }

        @Test
        void trimsAndStripsSbSuffix() {
            givenLaw49(LATEST);
            when(client.fetchVersionContent(V_2026)).thenReturn(List.of());

            assertThat(service.getLawContent("  49/1997 Sb. ").getLawIri()).isEqualTo(LAW_49_1997);
        }

        /** The navigation tree reaches the /law/content response fully labelled. */
        @Test
        void contentNavigationTreeIsFullyLabelled() {
            givenLaw49(LATEST);
            String dokument = V_2026 + "/dokument";
            String norma = dokument + "/norma";
            String cast = norma + "/cast_1";
            String par = cast + "/par_1";
            when(client.fetchVersionContent(V_2026)).thenReturn(List.of(
                    new FragmentModel(dokument, null, null, "dokument", "0001", null),
                    new FragmentModel(norma, dokument, null, "norma", "0002", null),
                    new FragmentModel(dokument + "/prefix", dokument, null, "prefix", "0003", null),
                    new FragmentModel(cast, norma, "Část 1", "cast", "0004", null),
                    new FragmentModel(par, cast, "§ 1", "par", "0005", null),
                    new FragmentModel(par + "/frag_3304837", par, null, "frag", "0006", "<p>text</p>")));

            LawContentDto out = service.getLawContent("49/1997");

            FragmentDto root = out.getFragments().get(0);
            assertThat(root.getCitation()).isEqualTo("Zákon č. 49/1997 Sb.");
            assertThat(root.getChildren()).extracting(FragmentDto::getCitation)
                    .containsExactly("Text předpisu", "Úvodní ustanovení");
            FragmentDto textBlock = root.getChildren().get(0).getChildren().get(0)
                    .getChildren().get(0).getChildren().get(0);
            assertThat(textBlock.getCitation()).isNull();
            assertThat(textBlock.isNavigable()).isFalse();
            assertThat(out.getBodyHtml()).as("a non-navigable node still contributes its text")
                    .contains("<p>text</p>");
        }

        /** bodyHtml nests one {@code <section>} per fragment, in document order. */
        @Test
        void assemblesNestedBodyHtmlInDocumentOrder() {
            givenLaw49(LATEST);
            String cast = NORMA_2026 + "/cast_1";
            String par1 = cast + "/par_1";
            when(client.fetchVersionContent(V_2026)).thenReturn(List.of(
                    new FragmentModel(cast, NORMA_2026, "Část 1", "cast", "0001", null),
                    new FragmentModel(par1, cast, "§ 1", "par", "0002", "<p>§ 1</p>"),
                    new FragmentModel(par1 + "/odst_1", par1, "§ 1 odst. 1", "odst", "0003", "<p>(1)</p>"),
                    new FragmentModel(NORMA_2026 + "/par_2", NORMA_2026, "§ 2", "par", "0004", "<p>§ 2</p>")));

            LawContentDto out = service.getLawContent("49/1997");

            String body = out.getBodyHtml();
            assertThat(body).as("raw, unescaped bodies in document order")
                    .containsSubsequence("<p>§ 1</p>", "<p>(1)</p>", "<p>§ 2</p>");
            assertThat(body).as("a structural node is still wrapped")
                    .contains("data-kind=\"cast\"", "data-kind=\"odst\"");
            assertThat(body).as("data-iri carries the full IRI, data-eli the domain-stripped path")
                    .contains("data-iri=\"" + par1 + "\"",
                            "data-eli=\"/eli/cz/sb/1997/49/2026-04-01/dokument/norma/cast_1/par_1\"");
            assertThat(occurrences(body.substring(body.indexOf("<p>§ 1</p>"), body.indexOf("<p>(1)</p>")),
                    "</section>")).as("odst nests inside par_1").isZero();
            assertThat(occurrences(body, "<section ")).as("one wrapper per fragment").isEqualTo(4);
            assertThat(occurrences(body, "</section>")).as("balanced wrappers").isEqualTo(4);
            assertThat(out.getFragments()).as("the tree is returned alongside the body").hasSize(2);
        }

        @Test
        void emptyContentYieldsEmptyBodyHtml() {
            givenLaw49(LATEST);
            when(client.fetchVersionContent(V_2026)).thenReturn(List.of());

            LawContentDto out = service.getLawContent("49/1997");

            assertThat(out.getBodyHtml()).isEmpty();
            assertThat(out.getFragments()).isEmpty();
        }

        @Test
        void unknownLawThrows() {
            when(client.findLaw("sb", "999", 1997)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getLawContent("999/1997"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Právní akt č. 999/1997 nebyl nalezen.");
        }

        /** A bare number is the FE's cue to use /law/search/grouped instead. */
        @Test
        void rejectsPartialInput() {
            assertThatThrownBy(() -> service.getLawContent("49"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Referenci zadejte ve tvaru číslo/rok (např. 49/1997).");
        }

        @ParameterizedTest
        @ValueSource(strings = {"49/abc", "abc/1997", "  "})
        void rejectsMalformedNumberYear(String lawRef) {
            assertThatThrownBy(() -> service.getLawContent(lawRef)).isInstanceOf(IllegalArgumentException.class);
        }

        private static int occurrences(String haystack, String needle) {
            int n = 0;
            for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
                n++;
            }
            return n;
        }
    }

    /** {@code getLawContent} addressed by a law, version or fragment IRI. */
    @Nested
    class LawContentByIri {

        /** The FE holds version IRIs and no číslo/rok; one alone renders that znění, not the latest. */
        @Test
        void acceptsAVersionIriAsTheLawRefAndSelectsThatVersion() {
            givenLaw49(LATEST, OLDER);
            when(client.fetchVersionContent(V_2020)).thenReturn(List.of());

            LawContentDto out = service.getLawContent(V_2020, null);

            assertThat(out.getLawIri()).isEqualTo(LAW_49_1997);
            assertThat(out.getVersionIri()).isEqualTo(V_2020);
            assertThat(out.getVersionDate()).isEqualTo(LocalDate.of(2020, 1, 1));
            assertThat(out.isVersionLatest()).isFalse();
            assertThat(out.getVersions()).as("the switcher still lists every znění").hasSize(2);
            verify(client, never()).fetchVersionContent(V_2026);
        }

        @Test
        void acceptsALawLevelIriAndFallsBackToLatest() {
            givenLaw49(LATEST, OLDER);
            when(client.fetchVersionContent(V_2026)).thenReturn(List.of());

            LawContentDto out = service.getLawContent(LAW_49_1997, null);

            assertThat(out.getVersionIri()).isEqualTo(V_2026);
            assertThat(out.isVersionLatest()).isTrue();
        }

        /** Content is whole-version; the fragment is addressable inside the returned tree. */
        @Test
        void acceptsAFragmentIriAndRendersItsParentVersion() {
            givenLaw49(LATEST, OLDER);
            when(client.fetchVersionContent(V_2020)).thenReturn(List.of());

            assertThat(service.getLawContent(V_2020 + "/dokument/norma/par_1", null).getVersionIri())
                    .isEqualTo(V_2020);
        }

        @Test
        void explicitVersionIriWinsOverTheOneCarriedByTheLawIri() {
            givenLaw49(LATEST, OLDER);
            when(client.fetchVersionContent(V_2026)).thenReturn(List.of());

            assertThat(service.getLawContent(V_2020, V_2026).getVersionIri()).isEqualTo(V_2026);
        }

        /** Canonicalized on the way in, so membership in the canonical version list holds. */
        @Test
        void acceptsALegacyHostVersionIri() {
            givenLaw49(LATEST, OLDER);
            when(client.fetchVersionContent(V_2020)).thenReturn(List.of());

            assertThat(service.getLawContent(legacyLawIri("49", 1997) + "/2020-01-01", null).getVersionIri())
                    .isEqualTo(V_2020);
        }

        @Test
        void aVersionIriFromAnotherLawIsStillRejected() {
            givenLaw49(LATEST, OLDER);

            assertThatThrownBy(() -> service.getLawContent(LAW_49_1997, VERSION_IRI))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /** Falls through to the číslo/rok parse rather than claiming a bad IRI. */
        @Test
        void malformedIriShapedRefReportsTheNumberYearMessage() {
            assertThatThrownBy(() -> service.getLawContent("https://example.com/eli/cz/sb/nope", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** {@code getLawContent} with a caller-selected znění. */
    @Nested
    class SelectedVersion {

        @Test
        void rendersSelectedVersionNotLatest() {
            givenLaw49(LATEST, OLDER);
            when(client.fetchVersionContent(V_2020)).thenReturn(List.of(fragment(V_2020, "<p>staré znění</p>")));

            LawContentDto out = service.getLawContent("49/1997", V_2020);

            assertThat(out.getVersionIri()).isEqualTo(V_2020);
            assertThat(out.getVersionDate()).isEqualTo(LocalDate.of(2020, 1, 1));
            assertThat(out.getVersionEliPath()).isEqualTo("/eli/cz/sb/1997/49/2020-01-01");
            assertThat(out.isVersionLatest()).as("an older znění is not flagged latest").isFalse();
            assertThat(out.getBodyHtml()).contains("<p>staré znění</p>");
            assertThat(out.getVersions()).as("the switcher still lists every znění").hasSize(2);
        }

        @Test
        void nullVersionIriFallsBackToLatest() {
            givenLaw49(OLDER, LATEST);
            when(client.fetchVersionContent(V_2026)).thenReturn(List.of());

            LawContentDto out = service.getLawContent("49/1997", null);

            assertThat(out.getVersionIri()).isEqualTo(V_2026);
            assertThat(out.isVersionLatest()).isTrue();
        }

        @Test
        void blankVersionIriFallsBackToLatest() {
            givenLaw49(LATEST);
            when(client.fetchVersionContent(V_2026)).thenReturn(List.of());

            assertThat(service.getLawContent("49/1997", "   ").getVersionIri()).isEqualTo(V_2026);
        }

        /** Host-shape validation passes this IRI; only membership in this law's versions rejects it. */
        @Test
        void rejectsVersionIriOfAnotherLaw() {
            givenLaw49(LATEST);

            assertThatThrownBy(() -> service.getLawContent("49/1997", lawIri("262", 2006) + "/2026-04-01"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("nepatří k právnímu aktu");
            verify(client, never()).fetchVersionContent(anyString());
        }

        /**
         * Membership compares against the canonical IRIs e-Sbírka returns, so a legacy-host znění is
         * canonicalized first instead of being told it belongs to another act.
         */
        @Test
        void acceptsALegacyHostVersionIriAsAMember() {
            givenLaw49(LATEST);
            when(client.fetchVersionContent(V_2026)).thenReturn(List.of());

            assertThat(service.getLawContent("49/1997", LEGACY_SB + "1997/49/2026-04-01").getVersionIri())
                    .isEqualTo(V_2026);
            verify(client).fetchVersionContent(V_2026);
        }

        @Test
        void rejectsNonEsbirkaVersionIri() {
            givenLaw49(LATEST);

            assertThatThrownBy(() -> service.getLawContent("49/1997", "https://example.org/foo"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Neplatný identifikátor znění právního aktu.");
        }
    }

    /** The {@code @Cacheable} key of {@code getLawContent}: law half and version half. */
    @Nested
    class CacheKey {

        private EsbirkaLawContentReader contentReader;

        @BeforeEach
        void buildReader() {
            contentReader = new EsbirkaLawContentReader(client, new EsbirkaLawVersionsReader(client));
        }

        /** Equivalent refs collapse onto one key, or each stores a separate ~2 MB entry. */
        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
                "49/1997|49/1997",
                "'  49/1997  '|49/1997",
                "49/1997 Sb.|49/1997",
                "'  49/1997 Sb. '|49/1997",
                "262/2006 Sb.|262/2006"})
        void normalizeLawRefCollapsesEquivalentRefsToOneKey(String lawRef, String key) {
            assertThat(contentReader.normalizeLawRef(lawRef)).isEqualTo(key);
        }

        /** Same parser as getLawContent, so junk is rejected rather than producing a bogus key. */
        @ParameterizedTest
        @ValueSource(strings = {"49", "abc/1997", "  "})
        void normalizeLawRefRejectsPartialInputLikeGetLawContent(String lawRef) {
            assertThatThrownBy(() -> contentReader.normalizeLawRef(lawRef))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void anIriAndItsNumberYearShareTheLawHalfOfTheCacheKey() {
            assertThat(contentReader.normalizeLawRef(V_2020)).isEqualTo("49/1997");
            assertThat(contentReader.normalizeLawRef(LAW_49_1997)).isEqualTo("49/1997");
        }

        /** Without a version half per IRI, two znění of one act would serve each other's payload. */
        @Test
        void versionIriCarriedByTheLawRefKeysItsOwnCacheEntry() {
            assertThat(contentReader.resolveContentVersionIri(V_2020, null)).isEqualTo(V_2020);
            assertThat(contentReader.resolveContentVersionIri(V_2026, null)).isEqualTo(V_2026);
            assertThat(contentReader.resolveContentVersionIri(LAW_49_1997, null)).isEmpty();
            assertThat(contentReader.resolveContentVersionIri("49/1997", null)).isEmpty();
            assertThat(contentReader.resolveContentVersionIri(V_2020, V_2026))
                    .as("the explicit parameter wins").isEqualTo(V_2026);
            assertThat(contentReader.resolveContentVersionIri("49/1997", legacyLawIri("49", 1997) + "/2020-01-01"))
                    .as("a legacy host collapses onto the canonical entry").isEqualTo(V_2020);
        }
    }
}
