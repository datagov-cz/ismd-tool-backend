package com.dia.ismdtoolbackend.client.esbirka;

import com.dia.ismdtoolbackend.client.esbirka.EsbirkaRestErrors.Kind;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EsbirkaRestErrorsTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @ParameterizedTest
    @CsvSource({
            "DOKUMENT_NENALEZEN, NOT_FOUND",
            "FRAGMENT_NENALEZEN, NOT_FOUND",
            "NEPLATNE_STALE_URL, NOT_FOUND",
            "ZAZNAM_NENALEZEN, NOT_FOUND",
            "CHYBA_VSTUPU, INVALID_REQUEST",
            "ZADAN_NEPLATNY_VSTUPNI_PARAMETR, INVALID_REQUEST",
            "CHYBI_VSTUPNI_PARAMETR, INVALID_REQUEST",
            "NEPLATNY_API_KLIC, INVALID_API_KEY",
            "NECO_JINEHO, OTHER"
    })
    void classifiesByKod(String kod, Kind expected) {
        EsbirkaRestErrors.Classification c = classify(chyby(kod));
        assertEquals(expected, c.kind());
        assertEquals(kod, c.kod());
    }

    @Test
    void carriesPopisAndCorrelationIdAndIgnoresUnknownFields() {
        EsbirkaRestErrors.Classification c = classify("""
                {"chyby":[{"kod":"DOKUMENT_NENALEZEN","popis":"Dokument nenalezen.",
                  "datumCasChyby":"2026-10-17T10:00:00Z","correlationId":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                  "neznamePole":1}],"dalsi":true}""");
        assertEquals(Kind.NOT_FOUND, c.kind());
        assertEquals("Dokument nenalezen.", c.popis());
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", c.correlationId());
    }

    @Test
    void edgeHtmlIsNotAnApiError() {
        EsbirkaRestErrors.Classification c = classify("<html><body><h1>429 Too Many Requests</h1></body></html>");
        assertEquals(Kind.NOT_API_ERROR, c.kind());
        assertNull(c.kod());
    }

    @Test
    void emptyOrKodlessBodiesAreNotApiErrors() {
        assertEquals(Kind.NOT_API_ERROR, classify("").kind());
        assertEquals(Kind.NOT_API_ERROR, classify("{\"chyby\":[]}").kind());
        assertEquals(Kind.NOT_API_ERROR, classify("{\"chyby\":[{\"popis\":\"x\"}]}").kind());
        assertEquals(Kind.NOT_API_ERROR, classify("[1,2]").kind());
    }

    private EsbirkaRestErrors.Classification classify(String body) {
        return EsbirkaRestErrors.classify(body.getBytes(StandardCharsets.UTF_8), jsonMapper);
    }

    private static String chyby(String kod) {
        return "{\"chyby\":[{\"kod\":\"" + kod + "\",\"popis\":\"p\"}]}";
    }
}
