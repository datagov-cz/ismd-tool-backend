package com.dia.ismdtoolbackend.client.esbirka;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

/**
 * Classifies e-Sbírka REST error responses by the {@code chyby[].kod} of the API's {@code Chyby}
 * body, never by HTTP status alone: a missing document is HTTP 400 {@code DOKUMENT_NENALEZEN}.
 * A body that is not {@code Chyby} JSON (the edge's HTML 429/5xx pages) is {@link Kind#NOT_API_ERROR}.
 */
final class EsbirkaRestErrors {

    enum Kind {
        /** The requested document, version or fragment does not exist. */
        NOT_FOUND,
        /** The API rejected our request as malformed — a bug on our side. */
        INVALID_REQUEST,
        /** API key missing or rejected — configuration, not transient. */
        INVALID_API_KEY,
        /** Any other API error. */
        OTHER,
        /** No parseable {@code Chyby} body: an edge/proxy response, not the API's error model. */
        NOT_API_ERROR
    }

    record Classification(Kind kind, String kod, String popis, String correlationId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Chyby(List<Chyba> chyby) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Chyba(String kod, String popis, String correlationId) {
    }

    private static final Set<String> NOT_FOUND = Set.of(
            "DOKUMENT_NENALEZEN", "FRAGMENT_NENALEZEN", "NEPLATNE_STALE_URL", "ZAZNAM_NENALEZEN");
    private static final Set<String> INVALID_REQUEST = Set.of(
            "CHYBA_VSTUPU", "ZADAN_NEPLATNY_VSTUPNI_PARAMETR", "CHYBI_VSTUPNI_PARAMETR");
    private static final String INVALID_API_KEY = "NEPLATNY_API_KLIC";

    private EsbirkaRestErrors() {
    }

    static Classification classify(byte[] body, JsonMapper jsonMapper) {
        Chyba first = firstChyba(body, jsonMapper);
        if (first == null || first.kod() == null) {
            return new Classification(Kind.NOT_API_ERROR, null, null, null);
        }
        return new Classification(kindOf(first.kod()), first.kod(), first.popis(), first.correlationId());
    }

    private static Kind kindOf(String kod) {
        if (NOT_FOUND.contains(kod)) {
            return Kind.NOT_FOUND;
        }
        if (INVALID_REQUEST.contains(kod)) {
            return Kind.INVALID_REQUEST;
        }
        if (INVALID_API_KEY.equals(kod)) {
            return Kind.INVALID_API_KEY;
        }
        return Kind.OTHER;
    }

    private static Chyba firstChyba(byte[] body, JsonMapper jsonMapper) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            Chyby chyby = jsonMapper.readValue(body, Chyby.class);
            if (chyby == null || chyby.chyby() == null || chyby.chyby().isEmpty()) {
                return null;
            }
            return chyby.chyby().get(0);
        } catch (JacksonException e) {
            return null;
        }
    }
}
