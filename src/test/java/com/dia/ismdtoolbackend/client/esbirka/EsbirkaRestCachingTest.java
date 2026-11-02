package com.dia.ismdtoolbackend.client.esbirka;

import com.dia.ismdtoolbackend.client.EsbirkaClient;
import com.dia.ismdtoolbackend.config.CacheConfig;
import com.dia.ismdtoolbackend.config.EsbirkaConfig;
import com.dia.ismdtoolbackend.testsupport.cache.CacheTestSupport;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/** Through Spring's caching proxy: one fetch per version, shared by content loads and resolves. */
@SpringJUnitConfig(EsbirkaRestCachingTest.Beans.class)
@TestPropertySource(properties = "esbirka.provider=rest")
class EsbirkaRestCachingTest {

    /** Started before the Spring context loads, which reads its base URL. */
    @RegisterExtension
    static final WireMockExtension WM = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();
    private static final String B = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz";
    private static final String V_49 = B + "/sb/1997/49/2025-11-01";
    private static final String PAGES = "/dokumenty-sbirky/%2Fsb%2F1997%2F49%2F2025-11-01/fragmenty";

    @Configuration
    @Import({CacheConfig.class, EsbirkaRestTransport.class, EsbirkaRestReader.class, EsbirkaRestNameSearch.class,
            EsbirkaRestClient.class})
    static class Beans {
        @Bean
        EsbirkaConfig esbirkaConfig() {
            EsbirkaConfig config = new EsbirkaConfig();
            config.getRest().setBaseUrl(WM.baseUrl());
            config.getRest().setApiKey("test-key");
            config.getRest().setMinRequestSpacingMs(1);
            return config;
        }

        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    private EsbirkaClient client;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void stub() {
        CacheTestSupport.clearAll(cacheManager);
        for (int n = 0; n < 3; n++) {
            WM.stubFor(get(urlEqualTo(PAGES + "?cisloStranky=" + n)).willReturn(aResponse().withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(EsbirkaRestFixtures.text("sb-1997-49/fragmenty-" + n + ".json"))
                    .withFixedDelay(100)));
        }
        WM.stubFor(get(urlEqualTo("/dokumenty-sbirky/%2Fsb%2F1997%2F49/historie")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(EsbirkaRestFixtures.text("sb-1997-49/historie.json"))));
    }

    @Test
    void concurrentColdLoadsShareOneFetchAndResolvesReuseIt() {
        // Not the common pool: the JDK HttpClient completes sendAsync through it, so callers blocked
        // there can starve their own response (with 4 CPUs, three callers fill its three workers).
        ExecutorService callers = Executors.newFixedThreadPool(3);
        try {
            List<CompletableFuture<Integer>> loads = List.of(
                    CompletableFuture.supplyAsync(() -> client.fetchVersionContent(V_49).size(), callers),
                    CompletableFuture.supplyAsync(() -> client.fetchVersionContent(V_49).size(), callers),
                    CompletableFuture.supplyAsync(() -> client.fetchVersionContent(V_49).size(), callers));
            loads.forEach(load -> assertThat(load.join()).isEqualTo(2508));
        } finally {
            callers.shutdownNow();
        }

        assertThat(client.resolveFragment(V_49 + "/dokument/norma/cast_1/par_1", V_49, B + "/sb/1997/49")).isPresent();
        assertThat(client.resolveFragment(V_49 + "/dokument/norma/cast_1", V_49, B + "/sb/1997/49")).isPresent();
        client.fetchVersions(B + "/sb/1997/49");

        for (int n = 0; n < 3; n++) {
            WM.verify(1, getRequestedFor(urlEqualTo(PAGES + "?cisloStranky=" + n)));
        }
        WM.verify(1, getRequestedFor(urlPathEqualTo("/dokumenty-sbirky/%2Fsb%2F1997%2F49/historie")));
    }
}
