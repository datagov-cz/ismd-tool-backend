package com.dia.ismdtoolbackend.ofn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OfnDocumentOrderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OfnDocumentOrder order = new OfnDocumentOrder(OfnSpecification.bundled().contextIndex());

    @Test
    void keysFollowTheContext_aliasesFirst_unknownLast_atEveryLevel() throws Exception {
        ObjectNode document = (ObjectNode) mapper.readTree("""
                {"pojmy": [{"obor-hodnot": "xsd:string", "neznámé": 1, "název": {"cs": "a"}, "typ": ["Vlastnost"],
                            "iri": "https://x/pojem/a",
                            "definující-nelegislativní-zdroj": [{"url": "https://u", "typ": "Digitální objekt", "název": {"cs": "d"}}]}],
                 "název": {"cs": "V"}, "typ": ["Slovník"], "iri": "https://x", "@context": "c",
                 "vytvořeno": {"datum": "2026-01-01", "typ": "Časový okamžik"}}""");

        ObjectNode ordered = order.apply(document);

        assertThat(keys(ordered)).startsWith("@context", "iri", "typ", "název");
        assertThat(keys(ordered.get("vytvořeno"))).containsExactly("typ", "datum");
        JsonNode concept = ordered.get("pojmy").get(0);
        assertThat(keys(concept)).startsWith("iri", "typ", "název").endsWith("neznámé");
        assertThat(keys(concept).indexOf("definující-nelegislativní-zdroj")).isLessThan(keys(concept).indexOf("obor-hodnot"));
        assertThat(keys(concept.get("definující-nelegislativní-zdroj").get(0))).containsExactly("typ", "název", "url");
        assertThat(ordered).isEqualTo(document);
    }

    private static List<String> keys(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }
}
