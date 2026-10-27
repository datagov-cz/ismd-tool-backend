package com.dia.ismdtoolbackend.client.esbirka;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * Allow-list sanitizer for e-Sbírka fragment bodies. The FE renders them as raw HTML, so each body
 * is cleaned once, before it is cached. The allow-list covers the markup e-Sbírka uses (text
 * markup, tables, links, images, MathML); anything else is unwrapped, keeping its text — which
 * also drops the {@code <czechvoc-termin>} wrappers the SPARQL bodies never had.
 */
final class EsbirkaHtmlSanitizer {

    /** Resolves relative links for the protocol check only; they stay relative in the output. */
    private static final String BASE_URI = "https://www.e-sbirka.cz/";
    private static final Set<String> STYLE_PROPERTIES = Set.of("text-align", "width", "vertical-align");
    private static final Pattern SAFE_STYLE_VALUE = Pattern.compile("[-#%.\\w\\s]+");

    private static final Cleaner CLEANER = new Cleaner(new Safelist()
            .addTags("var", "b", "i", "sup", "sub", "br", "table", "tbody", "tr", "td", "th", "a", "img",
                    "math", "mi", "mo", "mn", "mrow", "msub", "msup", "mfrac", "mfenced", "mtext")
            .addAttributes("td", "class", "colspan", "rowspan", "style")
            .addAttributes("th", "class", "colspan", "rowspan", "style")
            .addAttributes("tr", "align", "style")
            .addAttributes("table", "class", "style", "cellpadding", "cellspacing")
            .addAttributes("a", "class", "href")
            .addAttributes("img", "src", "alt", "width", "height")
            .addAttributes("math", "xmlns")
            .addAttributes("mi", "mathvariant")
            .addAttributes("mo", "stretchy")
            .addAttributes("mfenced", "separators")
            .addProtocols("a", "href", "http", "https", "#")
            .addProtocols("img", "src", "http", "https")
            .preserveRelativeLinks(true));

    private EsbirkaHtmlSanitizer() {
    }

    /** @return the cleaned body, or {@code null} for a {@code null} body */
    static String sanitize(String html) {
        if (html == null) {
            return null;
        }
        Document clean = CLEANER.clean(Jsoup.parseBodyFragment(html, BASE_URI));
        for (Element styled : clean.select("[style]")) {
            String style = filterStyle(styled.attr("style"));
            if (style.isEmpty()) {
                styled.removeAttr("style");
            } else {
                styled.attr("style", style);
            }
        }
        clean.outputSettings()
                .prettyPrint(false)
                .escapeMode(Entities.EscapeMode.xhtml)
                .charset("UTF-8");
        return clean.body().html();
    }

    /** Keeps only the allowed CSS properties with plain values (no {@code url(…)}, no expressions). */
    private static String filterStyle(String style) {
        StringJoiner kept = new StringJoiner("; ");
        for (String declaration : style.split(";")) {
            int colon = declaration.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String property = declaration.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = declaration.substring(colon + 1).trim();
            if (STYLE_PROPERTIES.contains(property) && SAFE_STYLE_VALUE.matcher(value).matches()) {
                kept.add(property + ": " + value);
            }
        }
        return kept.toString();
    }
}
