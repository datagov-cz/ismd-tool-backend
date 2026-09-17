package com.dia.ismdtoolbackend.utility.eli;

import com.dia.ismdtoolbackend.controller.dto.FragmentDto;
import org.springframework.web.util.HtmlUtils;

import java.util.List;

/**
 * Renders an assembled fragment tree to HTML.
 *
 * <p>Each fragment is wrapped in a {@code <section>} carrying its ELI path, full IRI and kind
 * as data attributes ({@code data-eli} path + {@code data-iri} full IRI — FE hooks for
 * deep-linking / navigation / styling); the fragment's own {@code bodyHtml} (null for
 * structural fragments) precedes its children, so the output is a nested, document-ordered
 * tree. Order is the tree's order, preserved from the server-side {@code ORDER BY ?order}.
 *
 * <p>Shared by the whole-version content endpoint and the {@code /resolve} body fallback, so
 * both emit byte-identical markup for the same subtree.
 */
public final class EsbirkaFragmentHtml {

    private EsbirkaFragmentHtml() {
    }

    /** Renders a forest of roots in order. */
    public static String render(List<FragmentDto> roots) {
        StringBuilder sb = new StringBuilder();
        for (FragmentDto root : roots) {
            append(sb, root);
        }
        return sb.toString();
    }

    /** Renders a single node and its descendants. */
    public static String renderNode(FragmentDto node) {
        StringBuilder sb = new StringBuilder();
        append(sb, node);
        return sb.toString();
    }

    private static void append(StringBuilder sb, FragmentDto node) {
        sb.append("<section data-eli=\"")
                .append(HtmlUtils.htmlEscape(nullToEmpty(node.getEliPath())))
                .append("\" data-iri=\"")
                .append(HtmlUtils.htmlEscape(nullToEmpty(node.getIri())))
                .append("\" data-kind=\"")
                .append(HtmlUtils.htmlEscape(nullToEmpty(node.getKind())))
                .append("\">");
        if (node.getBodyHtml() != null) {
            sb.append(node.getBodyHtml());
        }
        for (FragmentDto child : node.getChildren()) {
            append(sb, child);
        }
        sb.append("</section>");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}