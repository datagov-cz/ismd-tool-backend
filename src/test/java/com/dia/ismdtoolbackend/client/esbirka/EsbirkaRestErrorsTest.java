package com.dia.ismdtoolbackend.client.esbirka;

import com.dia.ismdtoolbackend.client.esbirka.EsbirkaRestErrors.Kind;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(c.kind()).isEqualTo(expected);
        assertThat(c.kod()).isEqualTo(kod);
    }

    @Test
    void carriesPopisAndCorrelationIdAndIgnoresUnknownFields() {
        EsbirkaRestErrors.Classification c = classify("""
                {"chyby":[{"kod":"DOKUMENT_NENALEZEN","popis":"Dokument nenalezen.",
                  "datumCasChyby":"2026-10-17T10:00:00Z","correlationId":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                  "neznamePole":1}],"dalsi":true}""");
        assertThat(c.kind()).isEqualTo(Kind.NOT_FOUND);
        assertThat(c.popis()).isEqualTo("Dokument nenalezen.");
        assertThat(c.correlationId()).isEqualTo("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    }

    @Test
    void edgeHtmlIsNotAnApiError() {
        EsbirkaRestErrors.Classification c = classify("<html><body><h1>429 Too Many Requests</h1></body></html>");
        assertThat(c.kind()).isEqualTo(Kind.NOT_API_ERROR);
        assertThat(c.kod()).isNull();
    }

    @Test
    void emptyOrKodlessBodiesAreNotApiErrors() {
        assertThat(classify("").kind()).isEqualTo(Kind.NOT_API_ERROR);
        assertThat(classify("{\"chyby\":[]}").kind()).isEqualTo(Kind.NOT_API_ERROR);
        assertThat(classify("{\"chyby\":[{\"popis\":\"x\"}]}").kind()).isEqualTo(Kind.NOT_API_ERROR);
        assertThat(classify("[1,2]").kind()).isEqualTo(Kind.NOT_API_ERROR);
    }

    private EsbirkaRestErrors.Classification classify(String body) {
        return EsbirkaRestErrors.classify(body.getBytes(StandardCharsets.UTF_8), jsonMapper);
    }

    private static String chyby(String kod) {
        return "{\"chyby\":[{\"kod\":\"" + kod + "\",\"popis\":\"p\"}]}";
    }
}
