package com.dia.ismdtoolbackend.client.esbirka;

import com.dia.ismdtoolbackend.utility.eli.EsbirkaHtmlText;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class EsbirkaHtmlSanitizerTest {

    private static final List<String> LAWS = List.of("sb-1997-49", "sb-2005-410", "sb-1993-1", "sb-1992-586",
            "sb-2007-361", "sb-2012-89-2014", "sb-2015-294");
    private static final List<String> ALLOWED_TAGS = List.of("var", "b", "i", "sup", "sub", "br", "table", "tbody",
            "tr", "td", "th", "a", "img", "math", "mi", "mo", "mn", "mrow", "msub", "msup", "mfrac", "mfenced", "mtext");

    @Test
    void realBodiesKeepTextAndElements() {
        int bodies = 0;
        for (String law : LAWS) {
            for (EsbirkaRestDtos.FragmentPage page : EsbirkaRestFixtures.pages(law)) {
                for (EsbirkaRestDtos.Fragment f : page.seznam()) {
                    if (f.xhtml() == null) {
                        continue;
                    }
                    bodies++;
                    String clean = EsbirkaHtmlSanitizer.sanitize(f.xhtml());
                    assertThat(EsbirkaHtmlText.toPlainText(clean))
                            .as(f.eli())
                            .isEqualTo(EsbirkaHtmlText.toPlainText(f.xhtml()));
                    Document before = Jsoup.parseBodyFragment(f.xhtml());
                    Document after = Jsoup.parseBodyFragment(clean);
                    for (String tag : ALLOWED_TAGS) {
                        assertThat(after.select(tag)).as(tag + " in " + f.eli()).hasSize(before.select(tag).size());
                    }
                    assertThat(after.select("czechvoc-termin")).as(f.eli()).isEmpty();
                }
            }
        }
        assertThat(bodies).as("fixture coverage: " + bodies).isGreaterThan(3_000);
    }

    @Test
    void keepsRelativeImagesAnchorsAndAllowedStyles() {
        String clean = EsbirkaHtmlSanitizer.sanitize(
                "<table class=\"t\" style=\"width:100%\"><tbody><tr><td style=\"text-align:center;color:red\" rowspan=\"2\">"
                        + "<a class=\"odkaz\" href=\"#par_3\" data-odkaz-id=\"9\">§ 3</a>"
                        + "<img src=\"/souborove-dokumenty/8292/NAHLED\" alt=\"obr\" width=\"10\" data-mime-type=\"image/png\">"
                        + "</td></tr></tbody></table>");
        Document doc = Jsoup.parseBodyFragment(clean);

        assertThat(doc.selectFirst("a").attr("href")).isEqualTo("#par_3");
        assertThat(doc.selectFirst("a").attr("class")).isEqualTo("odkaz");
        assertThat(doc.selectFirst("a").hasAttr("data-odkaz-id")).isFalse();
        assertThat(doc.selectFirst("img").attr("src")).isEqualTo("/souborove-dokumenty/8292/NAHLED");
        assertThat(doc.selectFirst("td").attr("style")).isEqualTo("text-align: center");
        assertThat(doc.selectFirst("td").attr("rowspan")).isEqualTo("2");
        assertThat(doc.selectFirst("table").attr("style")).isEqualTo("width: 100%");
    }

    @Test
    void hostileMarkupComesOutInert() {
        String clean = EsbirkaHtmlSanitizer.sanitize("""
                <script>alert(1)</script><b onclick="alert(2)">tučně</b>
                <img src="x" onerror="alert(3)"><img src="javascript:alert(4)">
                <a href="javascript:alert(5)">odkaz</a><a href="data:text/html,<script>alert(6)</script>">data</a>
                <svg onload="alert(7)"><circle/></svg><iframe src="https://evil.example"></iframe>
                <td style="width: expression(alert(8)); background: url(https://evil.example/x.png)">bunka</td>
                <czechvoc-termin koncept-id="1">pojem</czechvoc-termin>""");
        String lower = clean.toLowerCase();

        for (String bad : List.of("<script", "onclick", "onerror", "onload", "javascript:", "data:", "<svg",
                "<iframe", "expression", "url(", "czechvoc")) {
            assertThat(lower).as(bad + " survived: " + clean).doesNotContain(bad);
        }
        assertThat(clean.contains("tučně") && clean.contains("odkaz") && clean.contains("pojem")).as(clean).isTrue();
    }

    @Test
    void nullStaysNull() {
        assertThat(EsbirkaHtmlSanitizer.sanitize(null)).isNull();
        assertThat(Objects.equals("", EsbirkaHtmlSanitizer.sanitize(""))).isTrue();
    }
}
