package com.dia.ismdtoolbackend.client.esbirka;

import com.dia.ismdtoolbackend.client.esbirka.EsbirkaRestDtos.DocumentDetail;
import com.dia.ismdtoolbackend.client.esbirka.EsbirkaRestDtos.Fragment;
import com.dia.ismdtoolbackend.client.esbirka.EsbirkaRestDtos.FragmentPage;
import com.dia.ismdtoolbackend.client.esbirka.EsbirkaRestDtos.Historie;
import com.dia.ismdtoolbackend.client.esbirka.EsbirkaRestDtos.HistorieEntry;
import com.dia.ismdtoolbackend.models.eli.FragmentModel;
import com.dia.ismdtoolbackend.models.eli.LawModel;
import com.dia.ismdtoolbackend.models.eli.LawVersionModel;
import com.dia.ismdtoolbackend.utility.eli.EsbirkaFragmentKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Maps e-Sbírka REST payloads to the models the SPARQL client produces, so tree assembly,
 * {@code navigable}, citation fallback and body rendering downstream stay unchanged. Every IRI is
 * {@link StaleUrls#IRI_BASE} + ELI path — the exact form the SPARQL dataset uses, which callers
 * such as {@code ismd-ai} depend on.
 */
final class EsbirkaRestMapper {

    private static final String VERSION_TYPE_BASE = StaleUrls.IRI_BASE + "/cis-esb-typ-znění/položka/";
    private static final String VERSION_TYPE_PROMULGATED = VERSION_TYPE_BASE + "VYHLZNE";
    private static final String VERSION_TYPE_CONSOLIDATED = VERSION_TYPE_BASE + "KONSOL";
    /** Versions in force today. {@code BUDOUCI} (published, not yet in force) is not "latest". */
    private static final Set<String> IN_FORCE_TYPES =
            Set.of("AKTUALNI", "AKTUALNI_NEUCINNE", "AKTUALNI_CASTECNE_UCINNE");

    private EsbirkaRestMapper() {
    }

    /** Law header from a law or version detail; číslo/rok/sbírka come from the staleUrl. */
    static LawModel toLaw(DocumentDetail detail) {
        String lawStaleUrl = StaleUrls.toLawStaleUrl(detail.staleUrl());
        String[] segments = lawStaleUrl.substring(1).split("/");
        return new LawModel(StaleUrls.toIri(lawStaleUrl), detail.kodDokumentuSbirky(),
                segments[2], Integer.valueOf(segments[1]), segments[0]);
    }

    /**
     * All versions, newest first by účinnost od. {@code latest} marks the version in force today;
     * when none is (a repealed law), the newest one.
     */
    static List<LawVersionModel> toVersions(Historie historie) {
        List<HistorieEntry> entries = new ArrayList<>(historie.historie() == null ? List.of() : historie.historie());
        entries.sort(Comparator.comparing(HistorieEntry::datumUcinnostiZneniOd,
                Comparator.nullsLast(Comparator.reverseOrder())));
        int latest = -1;
        for (int i = 0; i < entries.size() && latest < 0; i++) {
            if (IN_FORCE_TYPES.contains(entries.get(i).typZneni())) {
                latest = i;
            }
        }
        if (latest < 0 && !entries.isEmpty()) {
            latest = 0;
        }
        List<LawVersionModel> versions = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            HistorieEntry e = entries.get(i);
            String type = "VYHLASENE".equals(e.typZneni()) ? VERSION_TYPE_PROMULGATED : VERSION_TYPE_CONSOLIDATED;
            versions.add(new LawVersionModel(StaleUrls.toIri(e.staleUrl()),
                    e.datumUcinnostiZneniOd(), e.datumUcinnostiZneniDo(), type, i == latest));
        }
        return versions;
    }

    /**
     * Fragments of one version, from all of its pages in page order. The REST list is flat in
     * document order; parents come from a depth stack over {@code hloubka}, carried across pages.
     * Bodies are sanitized here, before anything caches them.
     */
    static List<FragmentModel> toFragments(List<FragmentPage> pages) {
        List<Fragment> flat = new ArrayList<>();
        for (FragmentPage page : pages) {
            if (page.seznam() != null) {
                flat.addAll(page.seznam());
            }
        }
        EsbirkaRestCitations citations = EsbirkaRestCitations.forDocument(documentCitation(flat));
        int width = Math.max(6, String.valueOf(flat.size()).length());
        List<FragmentModel> out = new ArrayList<>(flat.size());
        List<Integer> stack = new ArrayList<>();
        for (int i = 0; i < flat.size(); i++) {
            Fragment f = flat.get(i);
            int depth = Math.max(0, f.hloubka());
            while (stack.size() > depth) {
                stack.remove(stack.size() - 1);
            }
            Fragment parent = stack.isEmpty() ? null : flat.get(stack.get(stack.size() - 1));
            String iri = StaleUrls.fromEli(f.eli());
            String citation = parent != null && EsbirkaRestCitations.repeatsParent(f.eli(), f.zkracenaCitace(), parent.zkracenaCitace())
                    ? null
                    : citations.ownCitation(f.kodTypuFragmentu(), f.eli(), f.zkracenaCitace());
            out.add(new FragmentModel(iri,
                    parent == null ? null : StaleUrls.fromEli(parent.eli()),
                    citation,
                    EsbirkaFragmentKind.fromIri(iri),
                    String.format("%0" + width + "d", i),
                    EsbirkaHtmlSanitizer.sanitize(f.xhtml())));
            stack.add(i);
        }
        return out;
    }

    /** The document root's citation ({@code "Zákon č. 49/1997 Sb."}). */
    private static String documentCitation(List<Fragment> flat) {
        for (Fragment f : flat) {
            if (f.hloubka() == 0) {
                return f.zkracenaCitace();
            }
        }
        return null;
    }
}
