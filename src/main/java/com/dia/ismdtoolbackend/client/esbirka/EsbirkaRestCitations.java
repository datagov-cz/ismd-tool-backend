package com.dia.ismdtoolbackend.client.esbirka;

import com.dia.ismdtoolbackend.utility.eli.EsbirkaCzechCitationFormatter;
import com.dia.ismdtoolbackend.utility.eli.ParsedEli;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Derives today's SPARQL fragment citation ({@code "§ 22b písm. a)"}) from the REST
 * {@code zkracenaCitace} ({@code "§ 22b písm. a) zákona č. 49/1997 Sb."}). Rules, in order:
 * <ol>
 *   <li>{@code Virtual_*} containers carry no citation.</li>
 *   <li>A citation equal to the document's own carries none.</li>
 *   <li>Strip {@code " č. <kód>"} with the act-type words before it: as many words as the act type
 *       in the document's own citation has ("Zákon" 1, "Zákon České národní rady" 4).</li>
 *   <li>Structural headings in upper-case ordinal form ({@code "ČÁST PRVNÍ HLAVA I"}, older
 *       versions) are rebuilt from the IRI ({@code "Část 1 Hlava 1"}).</li>
 *   <li>A {@code frag_*} grouper citing the same as its parent carries none.</li>
 * </ol>
 * Built per version from its document root; stateless otherwise.
 */
final class EsbirkaRestCitations {

    private static final String NUMBER_MARKER = " č. ";
    private static final Set<String> HEADING_TYPES = Set.of("Cast", "Hlava", "Dil", "Oddil", "Pododdil");
    private static final Set<String> HEADING_KINDS = Set.of("cast", "hlava", "dil", "oddil", "pododdil");
    private static final Pattern UPPER_CASE_HEADING =
            Pattern.compile("(^|\\s)(ČÁST|HLAVA|DÍL|ODDÍL|PODODDÍL)\\s");

    private final String documentCitation;
    private final String suffix;
    private final int actTypeWords;

    private EsbirkaRestCitations(String documentCitation, String suffix, int actTypeWords) {
        this.documentCitation = documentCitation;
        this.suffix = suffix;
        this.actTypeWords = actTypeWords;
    }

    /** @param documentCitation the document root's {@code zkracenaCitace}, e.g. {@code "Zákon č. 49/1997 Sb."} */
    static EsbirkaRestCitations forDocument(String documentCitation) {
        int marker = documentCitation == null ? -1 : documentCitation.indexOf(NUMBER_MARKER);
        if (marker < 0) {
            return new EsbirkaRestCitations(documentCitation, null, 0);
        }
        String actType = documentCitation.substring(0, marker).trim();
        return new EsbirkaRestCitations(documentCitation,
                documentCitation.substring(marker), actType.split("\\s+").length);
    }

    /**
     * Citation before rule 5, which needs the parent: rules 1–4.
     *
     * @param eli the fragment's ELI path ({@code /eli/cz/sb/...})
     */
    String ownCitation(String kodTypuFragmentu, String eli, String zkracenaCitace) {
        if (zkracenaCitace == null || kodTypuFragmentu == null || kodTypuFragmentu.startsWith("Virtual_")
                || zkracenaCitace.equals(documentCitation)) {
            return null;
        }
        String citation = stripActReference(zkracenaCitace);
        if (citation != null && HEADING_TYPES.contains(kodTypuFragmentu)
                && UPPER_CASE_HEADING.matcher(citation).find()) {
            citation = headingFromIri(eli);
        }
        return citation;
    }

    /** Rule 5: a {@code frag_*} grouper whose raw citation repeats its parent's carries none. */
    static boolean repeatsParent(String eli, String zkracenaCitace, String parentZkracenaCitace) {
        return eli.substring(eli.lastIndexOf('/') + 1).startsWith("frag_")
                && zkracenaCitace != null && zkracenaCitace.equals(parentZkracenaCitace);
    }

    private String stripActReference(String zkracenaCitace) {
        if (suffix == null || !zkracenaCitace.endsWith(suffix)) {
            return zkracenaCitace;
        }
        String[] words = zkracenaCitace.substring(0, zkracenaCitace.length() - suffix.length()).split(" ");
        String citation = String.join(" ", List.of(words).subList(0, Math.max(0, words.length - actTypeWords))).trim();
        return citation.isEmpty() ? null : citation;
    }

    private static String headingFromIri(String eli) {
        List<ParsedEli.FragmentSegment> headings = new ArrayList<>();
        for (String segment : eli.split("/")) {
            int underscore = segment.indexOf('_');
            if (underscore > 0 && HEADING_KINDS.contains(segment.substring(0, underscore))) {
                headings.add(new ParsedEli.FragmentSegment(segment.substring(0, underscore), segment.substring(underscore + 1)));
            }
        }
        return EsbirkaCzechCitationFormatter.buildFragmentCitationFromSegments(headings);
    }
}
