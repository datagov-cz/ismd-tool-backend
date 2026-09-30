package com.dia.ismdtoolbackend.utility.codelist;

import com.dia.ismdtoolbackend.models.concept.ConceptValidationUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Runs the reader against a real local HTTP server; payloads are trimmed from live
 * distributions (2026-09-30).
 */
class CodelistDistributionReaderTest {

    private static final String RPP_151 = """
            {
              "@context" : "https://ofn.gov.cz/číselníky/2022-02-08/kontexty/číselník.jsonld",
              "iri" : "https://rpp-opendata.egon.gov.cz/odrpp/zdroj/číselníky/151/2025-01-01",
              "typ" : "Číselník",
              "název" : { "cs" : "Pohlaví", "en" : "Codelist of sex" },
              "položky" : [ { "typ" : "Položka", "iri" : "https://x/položka/1" } ]
            }""";

    private static final String MFF = """
            {
                "@context": "https://ofn.gov.cz/číselníky/2022-02-08/kontexty/číselník.jsonld",
                "iri": "https://data.mff.cuni.cz/zdroj/číselníky/klasifikace-výzkumných-oborů",
                "typ": "Číselník"
            }""";

    /** {@code @context} is an object and the only identifier is a relative {@code id}. */
    private static final String MPSV = """
            {
              "@context": {
                "id": "@id", "type": "@type", "@base": "https://data.mpsv.cz/zdroj/",
                "polozky": { "@reverse": "skos:inScheme", "@container": "@set" }
              },
              "id": "ciselnik/FormySocSluzby",
              "type": "ciselnik",
              "polozky": [ { "id": "polozka/1", "iri": "https://not-top-level/iri" } ]
            }""";

    private static final String MSMT = """
            { "@context" : "https://ofn.gov.cz/číselníky/2022-02-08/kontexty/číselník.jsonld",
              "typ":"Číselník","název":{"cs":"Jazyk"},"kód":"AACJ" }""";

    private static final String RPP_CSV = "číselník,číselník_kód,číselník_název_cs,číselník_definice_en\n"
            + "https://rpp-opendata.egon.gov.cz/odrpp/zdroj/číselníky/151/2025-01-01,151,Pohlaví,"
            + "\"The SEX code list is used to fill in the value of the \"\"SEX\"\" data.\"\n"
            + "https://rpp-opendata.egon.gov.cz/odrpp/zdroj/číselníky/151/2025-01-01,151,Pohlaví,x\n";

    private HttpServer server;
    private String base;
    private final List<String> requestedPaths = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private CodelistDistributionReader reader(long maxBytes, Duration timeout) {
        return new CodelistDistributionReader(
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(), maxBytes, timeout);
    }

    private Optional<String> read(String url) {
        return reader(2 * 1024 * 1024, Duration.ofSeconds(5)).readCodeListIri(url);
    }

    private void serve(String path, int status, String contentType, byte[] body, boolean gzip) {
        server.createContext(path, exchange -> {
            requestedPaths.add(exchange.getRequestURI().getRawPath());
            byte[] payload = gzip ? gzip(body) : body;
            if (contentType != null) {
                exchange.getResponseHeaders().add("Content-Type", contentType);
            }
            if (gzip) {
                exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            }
            respond(exchange, status, payload);
        });
    }

    private void serve(String path, String contentType, String body) {
        serve(path, 200, contentType, body.getBytes(StandardCharsets.UTF_8), false);
    }

    private static void respond(HttpExchange exchange, int status, byte[] payload) throws IOException {
        exchange.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        } catch (IOException ignored) {
            // the reader may stop reading early and close the connection
        }
    }

    private static byte[] gzip(byte[] body) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            gz.write(body);
        }
        return bytes.toByteArray();
    }

    /** A resolved value must always pass the concept write-path validator unchanged. */
    private static void assertPassesConceptValidation(Optional<String> iri) {
        assertThat(iri).isPresent();
        assertThatCode(() -> ConceptValidationUtil.validateCodeListIri(iri.get())).doesNotThrowAnyException();
    }

    @Test
    void rppSeriesJsonLd_gzipped() {
        serve("/ciselnikyVdf/ciselnik_151_20250101.jsonld", 200, "application/ld+json; charset=utf-8",
                RPP_151.getBytes(StandardCharsets.UTF_8), true);

        Optional<String> iri = read(base + "/ciselnikyVdf/ciselnik_151_20250101.jsonld");

        assertThat(iri).contains("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/číselníky/151/2025-01-01");
        assertPassesConceptValidation(iri);
    }

    @Test
    void rawCzechDownloadUrl_isPercentEncodedOnTheWire() {
        serve("/soubory/", "application/ld+json", MFF);

        Optional<String> iri = read(base + "/soubory/číselníky/klasifikace-oborů-isvav.jsonld");

        assertThat(iri).contains("https://data.mff.cuni.cz/zdroj/číselníky/klasifikace-výzkumných-oborů");
        assertThat(requestedPaths)
                .containsExactly("/soubory/%C4%8D%C3%ADseln%C3%ADky/klasifikace-obor%C5%AF-isvav.jsonld");
    }

    @Test
    void alreadyEncodedDownloadUrl_isNotDoubleEncoded() {
        serve("/soubory/", "application/ld+json", MFF);

        read(base + "/soubory/%C4%8D%C3%ADseln%C3%ADky/x.jsonld");

        assertThat(requestedPaths).containsExactly("/soubory/%C4%8D%C3%ADseln%C3%ADky/x.jsonld");
    }

    @Test
    void mpsvRelativeId_isNotAccepted() {
        serve("/formy-soc-sluzby.jsonld", "application/ld+json", MPSV);

        assertThat(read(base + "/formy-soc-sluzby.jsonld")).isEmpty();
    }

    @Test
    void msmtWithoutIri_isEmpty() {
        serve("/AACJ.jsonld", "application/ld+json", MSMT);

        assertThat(read(base + "/AACJ.jsonld")).isEmpty();
    }

    @Test
    void relativeIri_isRejected() {
        serve("/rel.jsonld", "application/ld+json", "{\"iri\": \"ciselnik/x\"}");

        assertThat(read(base + "/rel.jsonld")).isEmpty();
    }

    @Test
    void csv_readsCellA2PastQuotedHeaderAndCells() {
        String csv = "\"a,b\",\"multi\nline\",c\n" + RPP_CSV.substring(RPP_CSV.indexOf('\n') + 1);
        serve("/ciselnik_151_20250101.csv", 200, "text/csv", csv.getBytes(StandardCharsets.UTF_8), true);

        Optional<String> iri = read(base + "/ciselnik_151_20250101.csv");

        assertThat(iri).contains("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/číselníky/151/2025-01-01");
        assertPassesConceptValidation(iri);
    }

    @Test
    void csv_quotedA2AndCrlf() {
        serve("/q.csv", "text/csv", "h1,h2\r\n\"https://x/číselník/1\",b\r\n");

        assertThat(read(base + "/q.csv")).contains("https://x/číselník/1");
    }

    @Test
    void csv_headerOnly_isEmpty() {
        serve("/h.csv", "text/csv", "h1,h2\n");

        assertThat(read(base + "/h.csv")).isEmpty();
    }

    @Test
    void unknownSuffix_fallsBackToContentType() {
        serve("/download", "application/ld+json", MFF);

        assertThat(read(base + "/download")).isPresent();
    }

    @Test
    void unsupportedFormat_isEmpty() {
        serve("/file.xml", "application/xml", "<x/>");

        assertThat(read(base + "/file.xml")).isEmpty();
    }

    @Test
    void notFound_isEmpty() {
        serve("/gone.jsonld", 404, "text/html", "not found".getBytes(StandardCharsets.UTF_8), false);

        assertThat(read(base + "/gone.jsonld")).isEmpty();
    }

    @Test
    void redirect_isFollowed() {
        server.createContext("/old.jsonld", exchange -> {
            exchange.getResponseHeaders().add("Location", base + "/new.jsonld");
            respond(exchange, 302, new byte[0]);
        });
        serve("/new.jsonld", "application/ld+json", MFF);

        assertThat(read(base + "/old.jsonld")).isPresent();
    }

    @Test
    void unparseableJson_isEmpty() {
        serve("/broken.jsonld", "application/ld+json", "{\"@context\": ");

        assertThat(read(base + "/broken.jsonld")).isEmpty();
    }

    /**
     * The iri is near the start, so a file far over the cap still resolves — the reader streams.
     * The cap is well above the parser's ~8 KB read buffer, which is the cap's granularity.
     */
    @Test
    void stopsReadingAtTheIri_soAFileOverTheCapStillResolves() {
        String big = RPP_151.replace("\"položky\" : [", "\"položky\" : [" + "{\"k\":\"v\"},".repeat(20_000));
        AtomicLong served = new AtomicLong();
        server.createContext("/big.jsonld", exchange -> {
            byte[] payload = big.getBytes(StandardCharsets.UTF_8);
            served.set(payload.length);
            exchange.getResponseHeaders().add("Content-Type", "application/ld+json");
            respond(exchange, 200, payload);
        });

        Optional<String> iri = reader(16 * 1024, Duration.ofSeconds(5)).readCodeListIri(base + "/big.jsonld");

        assertThat(served.get()).isGreaterThan(10 * 16 * 1024);
        assertThat(iri).contains("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/číselníky/151/2025-01-01");
    }

    @Test
    void iriBeyondTheCap_isEmpty() {
        String late = "{\"@context\": {\"pad\": \"" + "x".repeat(4096) + "\"}, "
                + "\"iri\": \"https://x/číselník/1\"}";
        serve("/late.jsonld", "application/ld+json", late);

        assertThat(reader(1024, Duration.ofSeconds(5)).readCodeListIri(base + "/late.jsonld")).isEmpty();
    }

    @Test
    void gzipBombIsCappedOnTheDecompressedSize() {
        String late = "{\"@context\": {\"pad\": \"" + "x".repeat(200_000) + "\"}, "
                + "\"iri\": \"https://x/číselník/1\"}";
        serve("/bomb.jsonld", 200, "application/ld+json", late.getBytes(StandardCharsets.UTF_8), true);

        assertThat(reader(64 * 1024, Duration.ofSeconds(5)).readCodeListIri(base + "/bomb.jsonld")).isEmpty();
    }

    @Test
    void slowResponse_timesOut() {
        server.createContext("/slow.jsonld", exchange -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, MFF.getBytes(StandardCharsets.UTF_8));
        });

        long start = System.nanoTime();
        Optional<String> iri = reader(1024, Duration.ofMillis(300)).readCodeListIri(base + "/slow.jsonld");

        assertThat(iri).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1500));
    }

    @Test
    void invalidOrUnreachableUrls_neverThrow() {
        assertThat(read(null)).isEmpty();
        assertThat(read("")).isEmpty();
        assertThat(read("ftp://x/a.jsonld")).isEmpty();
        assertThat(read("not a url")).isEmpty();
        assertThat(read("http://127.0.0.1:1/a.jsonld")).isEmpty();
    }

    @Test
    void toAsciiUri_encodesOnlyNonAscii() {
        assertThat(CodelistDistributionReader.toAsciiUri("https://h/soubory/číselníky/a b.jsonld"))
                .hasToString("https://h/soubory/%C4%8D%C3%ADseln%C3%ADky/a%20b.jsonld");
        assertThat(CodelistDistributionReader.toAsciiUri("https://h/a%20b.jsonld"))
                .hasToString("https://h/a%20b.jsonld");
    }
}
