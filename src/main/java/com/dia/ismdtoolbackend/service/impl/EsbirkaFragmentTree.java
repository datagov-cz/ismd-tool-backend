package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.FragmentDto;
import com.dia.ismdtoolbackend.models.eli.FragmentModel;
import com.dia.ismdtoolbackend.utility.eli.EsbirkaCzechCitationFormatter;
import com.dia.ismdtoolbackend.utility.eli.EsbirkaEliParser;
import com.dia.ismdtoolbackend.utility.eli.ParsedEli;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Assembles the flat fragment rows of one law version into its navigable tree. */
@Slf4j
final class EsbirkaFragmentTree {

    private EsbirkaFragmentTree() {
    }

    /**
     * Deepest tree e-Sbírka's structure yields: dokument, norma, část, hlava, díl, oddíl,
     * pododdíl, §, odstavec, písmeno, bod, text block.
     */
    static final int MAX_FRAGMENT_DEPTH = 12;
    static final int FRAGMENT_ROW_WARN_THRESHOLD = 5_000;

    /** Infix of a version's structural document containers, {@code <versionIri>/dokument/<container>}. */
    private static final String DOKUMENT_INFIX = "/dokument/";

    /** Kind of the document root, the single parentless node of a version's fragment tree. */
    private static final String DOKUMENT_KIND = "dokument";

    /** Kind of an unnumbered fragment: a text block when childless, a structural parent otherwise. */
    private static final String FRAG_KIND = "frag";

    /**
     * Tree assembly without a law citation, so the document root stays unlabelled. Used by
     * {@link EsbirkaSubtreeBodyCache}, which resolves no law metadata.
     */
    static List<FragmentDto> assembleTree(List<FragmentModel> rows, String versionIri) {
        return assembleTree(rows, versionIri, null);
    }

    /**
     * Tree assembly. A fragment is a <em>root</em> when its parent is a structural document
     * container — {@code <versionIri>/dokument/<container>} for any container (norma = the
     * body, poznamkypodcarou = footnotes, prilohy = annexes, …); roots are detected
     * structurally. Multi-root is supported.
     *
     * <p>{@code lawCitation}, when present, labels the otherwise-blank {@code dokument} root.
     */
    static List<FragmentDto> assembleTree(List<FragmentModel> rows, String versionIri, String lawCitation) {
        if (rows.isEmpty()) {
            return List.of();
        }
        String dokumentPrefix = versionIri + DOKUMENT_INFIX;

        Map<String, FragmentDto> nodes = new HashMap<>(rows.size());
        for (FragmentModel m : rows) {
            nodes.put(m.getIri(), toFragmentDto(m));
        }

        List<FragmentDto> roots = new ArrayList<>();
        int orphanCount = 0;
        String firstOrphanParent = null;
        for (FragmentModel m : rows) {
            FragmentDto self = nodes.get(m.getIri());
            if (m.getParentIri() == null) {
                roots.add(self);
                continue;
            }
            String anchor = resolveAnchor(m.getParentIri(), nodes, dokumentPrefix);
            if (anchor == null) {
                // Unresolvable parent: surface as a root so no text is lost, and count it
                // for the warn-log.
                roots.add(self);
                orphanCount++;
                if (firstOrphanParent == null) {
                    firstOrphanParent = m.getParentIri();
                }
            } else if (ROOT_ANCHOR.equals(anchor)) {
                roots.add(self);
            } else {
                nodes.get(anchor).getChildren().add(self);
            }
        }

        if (orphanCount > 0) {
            log.warn("Surfaced {} fragment row(s) as roots for version {} because their parent IRI "
                            + "resolved to neither an existing node nor a dokument container "
                            + "(sample parent IRI: {}).",
                    orphanCount, versionIri, firstOrphanParent);
        }

        labelDocumentRoots(roots, lawCitation);
        markNavigable(roots);
        capDepth(roots, 1, versionIri);
        return roots;
    }

    /**
     * Flag the nodes that carry no navigable label.
     *
     * <p>Resolved bottom-up: an unnumbered {@code frag} is non-navigable when it has no
     * navigable descendant either. Judging it on childlessness alone left wrappers whose every
     * child is itself non-navigable — they carry no citation and no body of their own, so they
     * render as a blank navigation row that expands to nothing.
     *
     * @return whether {@code nodes} contains anything navigable, directly or deeper
     */
    private static boolean markNavigable(List<FragmentDto> nodes) {
        boolean anyNavigable = false;
        for (FragmentDto n : nodes) {
            boolean navigableBelow = markNavigable(n.getChildren());
            boolean textOnly = FRAG_KIND.equals(n.getKind()) && !navigableBelow;
            n.setNavigable(!textOnly);
            if (textOnly) {
                n.setCitation(null);
            }
            anyNavigable |= !textOnly;
        }
        return anyNavigable;
    }

    /**
     * Label the {@code dokument} root with the law citation. Upstream carries no citation for
     * it and it is not a fragment, so it would otherwise head the navigation tree blank.
     * Only unlabelled document roots are touched.
     */
    private static void labelDocumentRoots(List<FragmentDto> roots, String lawCitation) {
        if (lawCitation == null || lawCitation.isBlank()) {
            return;
        }
        for (FragmentDto root : roots) {
            if (DOKUMENT_KIND.equals(root.getKind()) && root.getCitation() == null) {
                root.setCitation("Zákon č. " + lawCitation);
            }
        }
    }

    /** Sentinel returned by {@link #resolveAnchor} when a fragment resolves to a tree root. */
    private static final String ROOT_ANCHOR = "ROOT";

    /**
     * Resolve where a fragment with the given parent IRI should attach, walking the IRI up by
     * path segments: the IRI of an existing node (attach as its child), {@link #ROOT_ANCHOR}
     * (a {@code dokument/<container>} segment, so a tree root), or {@code null} (unresolvable).
     */
    private static String resolveAnchor(String parentIri, Map<String, FragmentDto> nodes, String dokumentPrefix) {
        if (parentIri == null) {
            return null;
        }
        String cur = parentIri;
        while (true) {
            if (nodes.containsKey(cur)) {
                return cur;
            }
            if (isDokumentRoot(cur, dokumentPrefix)) {
                return ROOT_ANCHOR;
            }
            int slash = cur.lastIndexOf('/');
            if (slash <= 0) {
                return null;
            }
            cur = cur.substring(0, slash);
        }
    }

    /** True when {@code iri} is exactly {@code <versionIri>/dokument/<single-segment>}. */
    private static boolean isDokumentRoot(String iri, String dokumentPrefix) {
        if (!iri.startsWith(dokumentPrefix)) {
            return false;
        }
        String rest = iri.substring(dokumentPrefix.length());
        return !rest.isEmpty() && rest.indexOf('/') < 0;
    }

    private static void capDepth(List<FragmentDto> nodes, int depth, String versionIri) {
        if (depth > MAX_FRAGMENT_DEPTH) {
            int trimmed = nodes.size();
            for (FragmentDto n : nodes) {
                n.getChildren().clear();
            }
            log.warn("Fragment tree depth cap reached for version {} at depth {} ({} subtree(s) trimmed).",
                    versionIri, MAX_FRAGMENT_DEPTH, trimmed);
            return;
        }
        for (FragmentDto n : nodes) {
            if (!n.getChildren().isEmpty()) {
                capDepth(n.getChildren(), depth + 1, versionIri);
            }
        }
    }

    private static FragmentDto toFragmentDto(FragmentModel m) {
        FragmentDto dto = new FragmentDto();
        dto.setIri(m.getIri());
        dto.setEliPath(SparqlIriValidator.extractEsbirkaEliPath(m.getIri()));
        dto.setKind(m.getKind());
        dto.setCitation(citationOrSegmentFallback(m));
        dto.setOrder(m.getOrder());
        dto.setBodyHtml(m.getBodyHtml());
        return dto;
    }

    /**
     * Fragment citation, in order of preference: the upstream
     * citace-označení-fragmentu-znění-právního-aktu, a structural-container label, then the
     * IRI path segments. Null when no source yields a label.
     *
     * <p>The segment fallback is load-bearing, not decorative: e-Sbírka deleted
     * citace-označení-fragmentu dataset-wide on 2026-08-24 and restored it later, serving
     * HTTP 200 with the predicate simply absent throughout. Upstream citations are therefore
     * the preferred source, never a guaranteed one — if the predicate disappears again,
     * §/Část labels degrade to segment-derived text instead of blanking the navigation.
     *
     * <p>Containers are resolved before the fragment check because they are not fragments in
     * the ELI sense — {@link EsbirkaEliParser} rejects them, and they make up the whole of the
     * navigation tree above the first citable unit. Their labels are independent of upstream
     * data, so they survive such an outage unchanged.
     */
    private static String citationOrSegmentFallback(FragmentModel m) {
        String citation = m.getCitation();
        if (citation != null && !citation.isBlank()) {
            return citation;
        }
        String containerLabel = containerLabel(m.getKind());
        if (containerLabel != null) {
            return containerLabel;
        }
        ParsedEli parsed = EsbirkaEliParser.parse(m.getIri());
        if (!parsed.isFragment()) {
            return null;
        }
        String derived = EsbirkaCzechCitationFormatter
                .buildFragmentCitationFromSegments(parsed.fragmentSegments());
        return derived.isBlank() ? null : derived;
    }

    /**
     * Label for a structural container kind, or null when the kind is not a container.
     * A {@code :N} suffix marks a repeated sibling (a second Přílohy block, say) and is
     * rendered as an ordinal so the siblings stay distinguishable in the navigation.
     */
    private static String containerLabel(String kind) {
        if (kind == null || kind.isBlank()) {
            return null;
        }
        int colon = kind.indexOf(':');
        String base = colon > 0 ? kind.substring(0, colon) : kind;
        String label = EsbirkaCzechCitationFormatter.containerLabel(base);
        if (label == null) {
            return null;
        }
        return colon > 0 ? label + " (" + kind.substring(colon + 1) + ")" : label;
    }
}
