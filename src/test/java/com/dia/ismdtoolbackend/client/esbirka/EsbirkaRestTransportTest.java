package com.dia.ismdtoolbackend.client.esbirka;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.dia.ismdtoolbackend.config.EsbirkaConfig;
import com.dia.ismdtoolbackend.exception.EsbirkaDocumentNotFoundException;
import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EsbirkaRestTransportTest {

    private static final String API_KEY = "test-key-7f3e9c1a-SECRET";
    private static final String HISTORIE_PATH = "/dokumenty-sbirky/%2Fsb%2F1997%2F49/historie";
    private static final String HISTORIE_JSON = "{\"zaznamy\":[{\"staleUrl\":\"/sb/1997/49/2025-11-01\"}]}";
    /** Recorded from the live edge: HTML, no Retry-After, Connection: close. */
    private static final String HTML_429 = EsbirkaRestFixtures.text("errors/nginx-429.html");
    private static final int BREAKER_THRESHOLD = 2;

    record Historie(List<Map<String, Object>> zaznamy) {
    }

    record SearchResult(int pocetCelkem) {
    }

    @RegisterExtension
    static final WireMockExtension WM = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private SimpleMeterRegistry meters;
    private ListAppender<ILoggingEvent> logs;
    private Logger transportLogger;
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        logs = new ListAppender<>();
        logs.start();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(logs);
        transportLogger = (Logger) LoggerFactory.getLogger(EsbirkaRestTransport.class);
        previousLevel = transportLogger.getLevel();
        transportLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
        transportLogger.setLevel(previousLevel);
        assertKeyNeverLogged();
    }

    // --- wire format ---------------------------------------------------------------------------

    @Test
    void sendsEncodedStaleUrlAsOneSegmentWithKeyAndGzip() {
        WM.stubFor(get(urlEqualTo(HISTORIE_PATH)).willReturn(json(200, HISTORIE_JSON)));
        EsbirkaRestTransport transport = transport(c -> { });

        Historie h = transport.get("historie", historieUri(transport), Historie.class, transport.newDeadline());

        assertEquals("/sb/1997/49/2025-11-01", h.zaznamy().get(0).get("staleUrl"));
        WM.verify(getRequestedFor(urlEqualTo(HISTORIE_PATH))
                .withHeader(EsbirkaRestTransport.API_KEY_HEADER, equalTo(API_KEY))
                .withHeader("Accept-Encoding", containing("gzip")));
    }

    @Test
    void encodesFragmentHashIntoThePathSegment() {
        String path = "/dokumenty-sbirky/%2Fsb%2F2012%2F89%2F2026-01-01%23par_3000";
        WM.stubFor(get(urlEqualTo(path)).willReturn(json(200, "{}")));
        EsbirkaRestTransport transport = transport(c -> { });

        transport.get("detail", transport.uri("/dokumenty-sbirky/"
                + StaleUrls.encodePathSegment("/sb/2012/89/2026-01-01#par_3000")), Map.class, transport.newDeadline());

        WM.verify(1, getRequestedFor(urlEqualTo(path)));
    }

    @Test
    void decodesGzippedBodies() {
        String big = "{\"zaznamy\":[" + "{\"staleUrl\":\"/sb/1997/49/2025-11-01\"},".repeat(2000)
                + "{\"staleUrl\":\"/sb/1997/49/0000-00-00\"}]}";
        WM.stubFor(get(urlEqualTo(HISTORIE_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withHeader("Content-Encoding", "gzip")
                .withBody(gzip(big))));
        EsbirkaRestTransport transport = transport(c -> { });

        Historie h = transport.get("historie", historieUri(transport), Historie.class, transport.newDeadline());

        assertEquals(2001, h.zaznamy().size());
    }

    @Test
    void postsJsonBody() {
        WM.stubFor(post(urlEqualTo("/rozsirena-vyhledavani")).willReturn(json(200, "{\"pocetCelkem\":1}")));
        EsbirkaRestTransport transport = transport(c -> { });

        SearchResult result = transport.post(transport.uri("/rozsirena-vyhledavani"),
                Map.of("pocet", 600), SearchResult.class, transport.newDeadline());

        assertEquals(1, result.pocetCelkem());
        WM.verify(postRequestedFor(urlEqualTo("/rozsirena-vyhledavani"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(equalToJson("{\"pocet\":600}")));
    }

    // --- retries -------------------------------------------------------------------------------

    @Test
    void retriesEdge429ThenSucceeds() {
        WM.stubFor(get(urlEqualTo(HISTORIE_PATH)).inScenario("429").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(html(429)).willSetStateTo("ok"));
        WM.stubFor(get(urlEqualTo(HISTORIE_PATH)).inScenario("429").whenScenarioStateIs("ok")
                .willReturn(json(200, HISTORIE_JSON)));
        EsbirkaRestTransport transport = transport(c -> { });

        Historie h = transport.get("historie", historieUri(transport), Historie.class, transport.newDeadline());

        assertEquals(1, h.zaznamy().size());
        WM.verify(2, getRequestedFor(urlEqualTo(HISTORIE_PATH)));
        assertEquals(1.0, calls("rate_limited"));
        assertEquals(1.0, calls("success"));
    }

    @Test
    void exhausted429sAre503AndNeverTripTheBreaker() {
        WM.stubFor(get(urlEqualTo(HISTORIE_PATH)).willReturn(html(429)));
        EsbirkaRestTransport transport = transport(c -> { });

        for (int i = 0; i < BREAKER_THRESHOLD + 1; i++) {
            assertThrows(SparqlEndpointUnavailableException.class,
                    () -> transport.get("historie", historieUri(transport), Historie.class, transport.newDeadline()));
        }
        WM.verify(4 * (BREAKER_THRESHOLD + 1), getRequestedFor(urlEqualTo(HISTORIE_PATH)));

        assertBreakerClosed(transport);
    }

    @Test
    void retriesConnectionResetAndCountsExhaustionAsOutage() {
        WM.stubFor(get(urlEqualTo(HISTORIE_PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        EsbirkaRestTransport transport = transport(c -> { });

        for (int i = 0; i < BREAKER_THRESHOLD; i++) {
            assertThrows(SparqlEndpointUnavailableException.class,
                    () -> transport.get("historie", historieUri(transport), Historie.class, transport.newDeadline()));
        }
        // WireMock sees more: the JDK client itself resends an idempotent GET once on a dropped connection.
        assertEquals(4.0 * BREAKER_THRESHOLD, calls("io_error"));

        assertBreakerOpen(transport);
    }

    // --- error mapping -------------------------------------------------------------------------

    @Test
    void http400DokumentNenalezenIsNotFoundNotRetriedAndNeverTripsTheBreaker() {
        WM.stubFor(get(urlEqualTo(HISTORIE_PATH)).willReturn(
                json(400, EsbirkaRestFixtures.text("errors/chyby-dokument-nenalezen.json"))));
        EsbirkaRestTransport transport = transport(c -> { });

        for (int i = 0; i < BREAKER_THRESHOLD + 1; i++) {
            assertThrows(EsbirkaDocumentNotFoundException.class,
                    () -> transport.get("historie", historieUri(transport), Historie.class, transport.newDeadline()));
        }
        WM.verify(BREAKER_THRESHOLD + 1, getRequestedFor(urlEqualTo(HISTORIE_PATH)));
        assertEquals(BREAKER_THRESHOLD + 1.0, calls("not_found"));

        assertBreakerClosed(transport);
    }

    @Test
    void invalidApiKeyIs503WithErrorLogAndNoRetry() {
        WM.stubFor(get(urlEqualTo(HISTORIE_PATH)).willReturn(json(401, chyby("NEPLATNY_API_KLIC"))));
        EsbirkaRestTransport transport = transport(c -> { });

        assertThrows(SparqlEndpointUnavailableException.class,
                () -> transport.get("historie", historieUri(transport), Historie.class, transport.newDeadline()));

        WM.verify(1, getRequestedFor(urlEqualTo(HISTORIE_PATH)));
        assertTrue(logs.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR
                && e.getFormattedMessage().contains("API key missing/invalid")));
    }

    @Test
    void invalidRequestIs503WithoutRetry() {
        WM.stubFor(get(urlEqualTo(HISTORIE_PATH)).willReturn(json(400, chyby("ZADAN_NEPLATNY_VSTUPNI_PARAMETR"))));
        EsbirkaRestTransport transport = transport(c -> { });

        assertThrows(SparqlEndpointUnavailableException.class,
                () -> transport.get("historie", historieUri(transport), Historie.class, transport.newDeadline()));

        WM.verify(1, getRequestedFor(urlEqualTo(HISTORIE_PATH)));
        assertBreakerClosed(transport);
    }

    @Test
    void apiServerErrorsTripTheBreaker() {
        WM.stubFor(get(urlEqualTo(HISTORIE_PATH)).willReturn(json(500, chyby("INTERNI_CHYBA"))));
        EsbirkaRestTransport transport = transport(c -> { });

        for (int i = 0; i < BREAKER_THRESHOLD; i++) {
            assertThrows(SparqlEndpointUnavailableException.class,
                    () -> transport.get("historie", historieUri(transport), Historie.class, transport.newDeadline()));
        }
        WM.verify(BREAKER_THRESHOLD, getRequestedFor(urlEqualTo(HISTORIE_PATH)));

        assertBreakerOpen(transport);
    }

    @Test
    void blankKeyIs503WithoutCallingUpstream() {
        EsbirkaRestTransport transport = transport(c -> c.getRest().setApiKey("  "));

        assertThrows(SparqlEndpointUnavailableException.class,
                () -> transport.get("historie", historieUri(transport), Historie.class, transport.newDeadline()));

        WM.verify(0, anyRequestedFor(anyUrl()));
    }

    // --- deadline and timeouts -----------------------------------------------------------------

    @Test
    void deadlineCutsASlowCall() {
        WM.stubFor(get(urlEqualTo(HISTORIE_PATH)).willReturn(json(200, HISTORIE_JSON).withFixedDelay(3_000)));
        EsbirkaRestTransport transport = transport(c -> c.getRest().setRequestDeadlineMs(400));

        long t0 = System.nanoTime();
        assertThrows(SparqlEndpointUnavailableException.class,
                () -> transport.get("historie", historieUri(transport), Historie.class, transport.newDeadline()));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertTrue(elapsedMs < 1_500, "deadline 400 ms, took " + elapsedMs + " ms");
        assertBreakerClosed(transport);
    }

    @Test
    void deadlineCutsALongGateWait() {
        WM.stubFor(get(urlEqualTo(HISTORIE_PATH)).willReturn(json(200, HISTORIE_JSON)));
        EsbirkaRestTransport transport = transport(c -> {
            c.getRest().setMinRequestSpacingMs(5_000);
            c.getRest().setRequestDeadlineMs(300);
        });
        transport.get("historie", historieUri(transport), Historie.class, transport.newDeadline());

        long t0 = System.nanoTime();
        assertThrows(SparqlEndpointUnavailableException.class,
                () -> transport.get("historie", historieUri(transport), Historie.class, transport.newDeadline()));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertTrue(elapsedMs < 1_000, "deadline 300 ms, took " + elapsedMs + " ms");
        WM.verify(1, getRequestedFor(urlEqualTo(HISTORIE_PATH)));
    }

    @Test
    void callTimeoutCountsAsOutage() {
        WM.stubFor(get(urlEqualTo(HISTORIE_PATH)).willReturn(json(200, HISTORIE_JSON).withFixedDelay(1_500)));
        EsbirkaRestTransport transport = transport(c -> c.getRest().setTimeout(200));

        for (int i = 0; i < BREAKER_THRESHOLD; i++) {
            assertThrows(SparqlEndpointUnavailableException.class,
                    () -> transport.get("historie", historieUri(transport), Historie.class, transport.newDeadline()));
        }
        WM.verify(BREAKER_THRESHOLD, getRequestedFor(urlEqualTo(HISTORIE_PATH)));

        assertBreakerOpen(transport);
    }

    // --- helpers -------------------------------------------------------------------------------

    private EsbirkaRestTransport transport(Consumer<EsbirkaConfig> customizer) {
        EsbirkaConfig config = new EsbirkaConfig();
        EsbirkaConfig.Rest rest = config.getRest();
        rest.setBaseUrl(WM.baseUrl());
        rest.setApiKey(API_KEY);
        rest.setTimeout(5_000);
        rest.setRequestDeadlineMs(10_000);
        rest.setMinRequestSpacingMs(1);
        rest.setMaxInFlight(4);
        rest.getRetry().setMaxAttempts(4);
        rest.getRetry().setInitialBackoffMs(5);
        rest.getCircuitBreaker().setFailureThreshold(BREAKER_THRESHOLD);
        rest.getCircuitBreaker().setCooldownMs(60_000);
        customizer.accept(config);
        return new EsbirkaRestTransport(config, JsonMapper.builder().build(), meters);
    }

    private static URI historieUri(EsbirkaRestTransport transport) {
        return transport.uri("/dokumenty-sbirky/" + StaleUrls.encodePathSegment("/sb/1997/49") + "/historie");
    }

    /** A healthy upstream must now be reached: the breaker did not open. */
    private void assertBreakerClosed(EsbirkaRestTransport transport) {
        WM.resetAll();
        WM.stubFor(get(urlEqualTo(HISTORIE_PATH)).willReturn(json(200, HISTORIE_JSON)));
        transport.get("historie", historieUri(transport), Historie.class, generousDeadline());
        WM.verify(1, getRequestedFor(urlEqualTo(HISTORIE_PATH)));
    }

    /** Even a healthy upstream is not called: the breaker fails fast. */
    private void assertBreakerOpen(EsbirkaRestTransport transport) {
        WM.resetAll();
        WM.stubFor(get(urlEqualTo(HISTORIE_PATH)).willReturn(json(200, HISTORIE_JSON)));
        SparqlEndpointUnavailableException e = assertThrows(SparqlEndpointUnavailableException.class,
                () -> transport.get("historie", historieUri(transport), Historie.class, generousDeadline()));
        assertTrue(e.getMessage().contains("circuit breaker open"), e.getMessage());
        WM.verify(0, anyRequestedFor(anyUrl()));
    }

    private double calls(String outcome) {
        return meters.counter("esbirka.rest.calls", "operation", "historie", "outcome", outcome).count();
    }

    private void assertKeyNeverLogged() {
        for (ILoggingEvent event : logs.list) {
            assertFalse(event.getFormattedMessage().contains(API_KEY), "key logged: " + event.getFormattedMessage());
            for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
                assertFalse(String.valueOf(t.getMessage()).contains(API_KEY), "key in logged exception: " + t.getMessage());
            }
        }
    }

    private static ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json")
                .withHeader("x-azure-ref", "20261017T100000Z-test").withBody(body);
    }

    private static ResponseDefinitionBuilder html(int status) {
        return aResponse().withStatus(status).withHeader("Content-Type", "text/html")
                .withHeader("Connection", "close").withBody(HTML_429);
    }

    private static byte[] gzip(String body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(body.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static String chyby(String kod) {
        return "{\"chyby\":[{\"kod\":\"" + kod + "\",\"popis\":\"popis\","
                + "\"correlationId\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\"}]}";
    }

    private static EsbirkaDeadline generousDeadline() {
        return EsbirkaDeadline.after(Duration.ofSeconds(10));
    }
}
