package com.dia.ismdtoolbackend.utility.eli;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Builds Czech display labels for {@link ParsedEli} values.
 *
 * <p>Three layers:
 * <ul>
 *   <li>{@link #buildDisplayLabel(ParsedEli, String)} — full label, optionally
 *       overriding the fragment portion with an authoritative SPARQL-fetched
 *       citation (e.g. {@code "§ 119 odst. 2 písm. a)"}).</li>
 *   <li>{@link #buildFragmentCitationFromSegments(List)} — fallback citation
 *       built purely from IRI path segments. Drops structural ancestors
 *       (Část/Hlava/Díl/Oddíl) when a {@code par_} segment is present, matching
 *       e-Sbírka's own citation style.</li>
 *   <li>{@link #formatCzechDate(java.time.LocalDate)} — {@code "d. M. yyyy"}
 *       (no leading zeroes, Czech short-date convention).</li>
 * </ul>
 *
 * <p>Static utility — never throws, never allocates Spring beans.
 */
public final class EsbirkaCzechCitationFormatter {

    private static final DateTimeFormatter CZECH_DATE = DateTimeFormatter.ofPattern("d. M. yyyy");

    /**
     * Display labels for the structural containers that sit between the document root and the
     * first citable unit. Upstream carries no citace-označení-fragmentu for these, so without
     * this map a container-root reference renders with no fragment part at all.
     *
     * <p>Keyed by bare kind — callers strip any {@code :N} sibling suffix first.
     */
    private static final Map<String, String> CONTAINER_LABELS = Map.of(
            "prefix", "Úvodní ustanovení",
            "norma", "Text předpisu",
            "novela", "Novelizační ustanovení",
            "prilohy", "Přílohy",
            "poznamkypodcarou", "Poznámky pod čarou",
            "postfix", "Závěrečná ustanovení",
            "zaver", "Závěr");

    /** Label for a structural container kind ({@code poznamkypodcarou}), or null when not a container. */
    public static String containerLabel(String bareKind) {
        return bareKind == null ? null : CONTAINER_LABELS.get(bareKind);
    }

    private EsbirkaCzechCitationFormatter() {
    }

    /**
     * Full Czech label: {@code "Zákon č. N/YYYY Sb.[, <fragment>] [(znění od D. M. YYYY)]"}.
     * {@code sparqlCitation} overrides the fragment portion when present and non-blank.
     */
    public static String buildDisplayLabel(ParsedEli p, String sparqlCitation) {
        if (p == null || !p.isValid()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("Zákon č. ").append(p.lawNumber()).append("/").append(p.lawYear()).append(" Sb.");
        if (p.isFragment()) {
            String fragmentPart = sparqlCitation != null && !sparqlCitation.isBlank()
                    ? sparqlCitation
                    : fallbackFragmentPart(p);
            if (!fragmentPart.isBlank()) {
                sb.append(", ").append(fragmentPart);
            }
        }
        if (p.versionDate() != null) {
            sb.append(" (znění od ").append(formatCzechDate(p.versionDate())).append(")");
        }
        return sb.toString();
    }

    /**
     * Fragment portion when no SPARQL citation is available: the container's own label for a
     * container root ({@code "Poznámky pod čarou"}), otherwise a citation built from the segments.
     */
    private static String fallbackFragmentPart(ParsedEli p) {
        if (p.isContainerRoot()) {
            String label = containerLabel(p.container());
            return label == null ? "" : label;
        }
        return buildFragmentCitationFromSegments(p.fragmentSegments());
    }

    /**
     * Fallback fragment citation built purely from path segments. Used when
     * SPARQL is unavailable, the fragment isn't in the dataset, or the
     * extractor is rendering a PENDING DTO.
     *
     * <p>If a {@code par_} segment is present, structural ancestors are
     * omitted — that matches e-Sbírka's own citation format (e.g. e-Sbírka
     * cites {@code "§ 119 odst. 2 písm. a)"}, not
     * {@code "Část 1 Hlava 4 § 119 odst. 2 písm. a)"}).
     */
    public static String buildFragmentCitationFromSegments(List<ParsedEli.FragmentSegment> segments) {
        if (segments == null || segments.isEmpty()) return "";
        boolean hasPar = segments.stream().anyMatch(s -> "par".equals(s.kind()));
        StringBuilder sb = new StringBuilder();
        for (ParsedEli.FragmentSegment s : segments) {
            if (hasPar && isStructuralAncestor(s.kind())) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(formatSegment(s));
        }
        return sb.toString();
    }

    public static String formatCzechDate(java.time.LocalDate date) {
        return date == null ? null : date.format(CZECH_DATE);
    }

    private static boolean isStructuralAncestor(String kind) {
        return "cast".equals(kind) || "hlava".equals(kind) || "dil".equals(kind) || "oddil".equals(kind);
    }

    private static String formatSegment(ParsedEli.FragmentSegment s) {
        return switch (s.kind()) {
            case "cast" -> "Část " + s.number();
            case "hlava" -> "Hlava " + s.number();
            case "dil" -> "Díl " + s.number();
            case "oddil" -> "Oddíl " + s.number();
            case "par" -> "§ " + s.number();
            case "odst" -> "odst. " + s.number();
            case "pism" -> "písm. " + s.number() + ")";
            case "bod" -> "bod " + s.number();
            case "ppc" -> "ppc " + s.number();
            case "frag" -> "frag " + s.number();
            default -> s.kind() + " " + s.number();
        };
    }
}
