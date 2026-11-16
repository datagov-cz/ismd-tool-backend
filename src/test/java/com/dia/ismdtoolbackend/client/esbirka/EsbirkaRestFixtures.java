package com.dia.ismdtoolbackend.client.esbirka;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Recorded e-Sbírka responses under {@code src/test/resources/esbirka-rest/}, with the SPARQL rows
 * of the same fragments and versions recorded alongside as ground truth (2026-10-17). Large laws
 * hold ancestor-closed slices: whole subtrees are dropped, so depth-stack parents are unaffected.
 * Files over 64 KB are gzipped ({@code .json.gz}).
 */
final class EsbirkaRestFixtures {

    static final JsonMapper JSON = JsonMapper.builder().build();

    /** One row of today's SPARQL version-content query. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SparqlFragment(String iri, String parent, String citace, String order, String obsah) {
    }

    /** One row of today's SPARQL version-list query; {@code isLatest} is "1"/"0". */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SparqlVersion(String iri, String ucinnostOd, String ucinnostDo, String typ, String isLatest) {
    }

    private EsbirkaRestFixtures() {
    }

    static EsbirkaRestDtos.DocumentDetail detail(String law) {
        return read(law + "/detail.json", new TypeReference<>() { });
    }

    static EsbirkaRestDtos.Historie historie(String law) {
        return read(law + "/historie.json", new TypeReference<>() { });
    }

    static List<EsbirkaRestDtos.FragmentPage> pages(String law) {
        List<EsbirkaRestDtos.FragmentPage> pages = new ArrayList<>();
        for (int n = 0; exists(law + "/fragmenty-" + n + ".json"); n++) {
            pages.add(read(law + "/fragmenty-" + n + ".json", new TypeReference<>() { }));
        }
        if (pages.isEmpty()) {
            throw new IllegalStateException("no fragment pages for " + law);
        }
        return pages;
    }

    static List<SparqlFragment> sparqlContent(String law) {
        return read(law + "/sparql-content.json", new TypeReference<>() { });
    }

    static List<SparqlVersion> sparqlVersions(String law) {
        return read(law + "/sparql-versions.json", new TypeReference<>() { });
    }

    /** The request body sent when {@code search/<name>} was recorded, as JSON. */
    static String searchRequest(String name) {
        return read("search/" + name + ".json", new TypeReference<tools.jackson.databind.JsonNode>() { })
                .get("request").toString();
    }

    /** The response recorded for {@code search/<name>}, as JSON. */
    static String searchResponse(String name) {
        return read("search/" + name + ".json", new TypeReference<tools.jackson.databind.JsonNode>() { })
                .get("response").toString();
    }

    /** A recorded file as raw JSON text (gunzipped when stored as {@code .json.gz}). */
    static String text(String path) {
        try (InputStream in = open(path)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static <T> T read(String path, TypeReference<T> type) {
        try (InputStream in = open(path)) {
            return JSON.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean exists(String path) {
        return resource(path) != null || resource(path + ".gz") != null;
    }

    private static InputStream open(String path) throws IOException {
        InputStream plain = resource(path);
        if (plain != null) {
            return plain;
        }
        InputStream gz = resource(path + ".gz");
        if (gz == null) {
            throw new IllegalStateException("missing fixture esbirka-rest/" + path);
        }
        return new GZIPInputStream(gz);
    }

    private static InputStream resource(String path) {
        return EsbirkaRestFixtures.class.getResourceAsStream("/esbirka-rest/" + path);
    }
}
