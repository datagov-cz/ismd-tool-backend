package com.dia.ismdtoolbackend.utility.eli;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeVisitor;

/**
 * Strips e-Sbírka rendered legal-text HTML ({@code obsah}) to plain text:
 * decodes entities (e.g. {@code &nbsp;}, {@code &sect;}) and drops markup,
 * emitting a line break for each block element so paragraph structure survives.
 * Non-breaking spaces are normalized to regular spaces so the raw body carries
 * no invisible characters.
 */
public final class EsbirkaHtmlText {

    /** Non-breaking space (U+00A0); e-Sbírka uses it heavily around § and numbers. */
    private static final char NBSP = '\u00A0';

    private EsbirkaHtmlText() {
    }

    /**
     * @param html rendered HTML body, may be {@code null}
     * @return plain-text rendering, or {@code null} when {@code html} is null
     *         (mirrors the nullable {@code bodyHtml} contract)
     */
    public static String toPlainText(String html) {
        if (html == null) {
            return null;
        }
        Document doc = Jsoup.parse(html);
        StringBuilder sb = new StringBuilder();
        doc.body().traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode text) {
                    sb.append(text.getWholeText());
                }
            }

            @Override
            public void tail(Node node, int depth) {
                // jsoup's wholeText() concatenates block elements with no separator;
                // append a newline as each block closes to preserve paragraph breaks.
                if (node instanceof Element el && el.tag().isBlock()) {
                    sb.append('\n');
                }
            }
        });
        return sb.toString()
                .replace(NBSP, ' ')
                .replaceAll("[ \\t]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{2,}", "\n")
                .strip();
    }
}