package com.dia.ismdtoolbackend.utility.codelist;

import com.dia.ismdtoolbackend.config.NkodConfig;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Reads the codelist IRI out of an NKOD codelist distribution file: the top-level {@code iri} of
 * a JSON-LD file, or cell A2 of a CSV file.
 *
 * <p>Streams the body and stops at the value, under a byte cap and a deadline, so a large or
 * slow file costs no more than a small one. Never throws: every failure is an empty result.
 * Stateless and thread-safe.
 */
@Component
@Slf4j
public class CodelistDistributionReader {

    /** Same rule as {@code ConceptValidationUtil.ABSOLUTE_IRI_PATTERN}. */
    private static final Pattern ABSOLUTE_IRI = Pattern.compile("^https?://\\S+$");

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private enum Format { JSON_LD, CSV }

    private final HttpClient httpClient;
    private final long maxBytes;
    private final Duration timeout;
    private final JsonFactory jsonFactory = new JsonFactory();

    @Autowired
    public CodelistDistributionReader(NkodConfig config) {
        this(HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                config.getCodelist().getMaxDistributionBytes(),
                Duration.ofMillis(config.getCodelist().getDistributionTimeoutMs()));
    }

    CodelistDistributionReader(HttpClient httpClient, long maxBytes, Duration timeout) {
        this.httpClient = httpClient;
        this.maxBytes = maxBytes;
        this.timeout = timeout;
    }

    /** The absolute codelist IRI the file declares, or empty when it can't be read or has none. */
    public Optional<String> readCodeListIri(String downloadUrl) {
        URI uri = toAsciiUri(downloadUrl);
        if (uri == null) {
            log.warn("Skipping codelist distribution with an invalid URL: {}", downloadUrl);
            return Optional.empty();
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(timeout)
                .header("Accept", "application/ld+json, application/json;q=0.9, text/csv;q=0.8, */*;q=0.1")
                .header("Accept-Encoding", "gzip")
                .build();
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream raw = response.body()) {
                if (response.statusCode() / 100 != 2) {
                    log.warn("Codelist distribution {} returned HTTP {}", downloadUrl, response.statusCode());
                    return Optional.empty();
                }
                Format format = format(uri, response.headers().firstValue("Content-Type").orElse(""));
                if (format == null) {
                    log.warn("Codelist distribution {} is neither JSON-LD nor CSV", downloadUrl);
                    return Optional.empty();
                }
                String encoding = response.headers().firstValue("Content-Encoding").orElse("");
                try (InputStream body = new CappedInputStream(decode(raw, encoding), maxBytes, deadline)) {
                    String value = format == Format.JSON_LD ? readJsonIri(body) : readCsvA2(body);
                    return absoluteIri(value, downloadUrl);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted reading codelist distribution {}", downloadUrl);
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read codelist distribution {}: {}", downloadUrl, e.toString());
            return Optional.empty();
        }
    }

    private static Optional<String> absoluteIri(String value, String downloadUrl) {
        if (value == null || value.isBlank()) {
            log.warn("Codelist distribution {} declares no codelist IRI", downloadUrl);
            return Optional.empty();
        }
        String iri = value.strip();
        if (!ABSOLUTE_IRI.matcher(iri).matches()) {
            log.warn("Codelist distribution {} declares a non-absolute codelist IRI: {}", downloadUrl, iri);
            return Optional.empty();
        }
        return Optional.of(iri);
    }

    /**
     * An ASCII-only URI for the request. Catalogue download URLs carry raw Czech
     * ({@code …/soubory/číselníky/…}); existing percent-escapes are kept as they are.
     */
    static URI toAsciiUri(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException e) {
            try {
                URL parsed = new URL(url.strip());
                uri = new URI(parsed.getProtocol(), parsed.getUserInfo(), parsed.getHost(), parsed.getPort(),
                        parsed.getPath(), parsed.getQuery(), parsed.getRef());
            } catch (MalformedURLException | URISyntaxException ex) {
                return null;
            }
        }
        String scheme = uri.getScheme();
        if (uri.getHost() == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            return null;
        }
        return URI.create(uri.toASCIIString());
    }

    private static Format format(URI uri, String contentType) {
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        if (path.endsWith(".jsonld") || path.endsWith(".json")) {
            return Format.JSON_LD;
        }
        if (path.endsWith(".csv")) {
            return Format.CSV;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        if (type.contains("json")) {
            return Format.JSON_LD;
        }
        if (type.contains("csv")) {
            return Format.CSV;
        }
        return null;
    }

    private static InputStream decode(InputStream raw, String contentEncoding) throws IOException {
        String encoding = contentEncoding.strip().toLowerCase(Locale.ROOT);
        return switch (encoding) {
            case "gzip", "x-gzip" -> new GZIPInputStream(raw);
            case "deflate" -> new InflaterInputStream(raw);
            default -> raw;
        };
    }

    /** The top-level {@code iri} string; skips every other member without materialising it. */
    private String readJsonIri(InputStream body) throws IOException {
        try (JsonParser parser = jsonFactory.createParser(body)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return null;
            }
            while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                String name = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("iri".equals(name)) {
                    return value == JsonToken.VALUE_STRING ? parser.getString() : null;
                }
                parser.skipChildren();
            }
            return null;
        }
    }

    /** The first field of the second CSV record; quoted fields may hold commas, quotes and newlines. */
    static String readCsvA2(InputStream body) throws IOException {
        Reader reader = new InputStreamReader(body, StandardCharsets.UTF_8);
        boolean inQuotes = false;
        int c;
        while ((c = reader.read()) != -1) {
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == '\n' && !inQuotes) {
                break;
            }
        }
        if (c == -1) {
            return null;
        }

        StringBuilder field = new StringBuilder();
        c = reader.read();
        if (c == '"') {
            while (true) {
                c = reader.read();
                if (c == -1) {
                    return null;
                }
                if (c == '"') {
                    int next = reader.read();
                    if (next != '"') {
                        break;
                    }
                }
                field.append((char) c);
            }
        } else {
            while (c != -1 && c != ',' && c != '\n' && c != '\r') {
                field.append((char) c);
                c = reader.read();
            }
        }
        return field.isEmpty() ? null : field.toString();
    }

    /**
     * Fails the read once more than {@code maxBytes} are consumed or the deadline passes. Counts
     * bytes the parser pulls, so the granularity is its read buffer (~8 KB), not the value's offset.
     */
    private static final class CappedInputStream extends FilterInputStream {

        private final long maxBytes;
        private final long deadlineNanos;
        private long count;

        CappedInputStream(InputStream in, long maxBytes, long deadlineNanos) {
            super(in);
            this.maxBytes = maxBytes;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public int read() throws IOException {
            check();
            int b = super.read();
            if (b != -1) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            check();
            int n = super.read(buf, off, len);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            check();
            long skipped = super.skip(n);
            count(skipped);
            return skipped;
        }

        private void check() throws IOException {
            if (System.nanoTime() > deadlineNanos) {
                throw new IOException("distribution read timed out");
            }
        }

        private void count(long n) throws IOException {
            count += n;
            if (count > maxBytes) {
                throw new IOException("distribution exceeds " + maxBytes + " bytes");
            }
        }
    }
}
