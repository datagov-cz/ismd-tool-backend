package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.EsbirkaSparqlClient;
import com.dia.ismdtoolbackend.config.CacheConfig;
import com.dia.ismdtoolbackend.models.eli.FragmentResolutionModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = {
        CacheConfig.class,
        EsbirkaFragmentResolutionCache.class
})
@Import(EsbirkaFragmentResolutionCacheTest.NoOpAppConfig.class)
class EsbirkaFragmentResolutionCacheTest {

    private static final String FRAGMENT_IRI =
            "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2000/361/2024-04-01/dokument/norma/par_2/pism_d";
    private static final String VERSION_IRI =
            "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2000/361/2024-04-01";
    private static final String LAW_IRI =
            "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2000/361";

    @MockBean
    private EsbirkaSparqlClient client;

    @Autowired
    private EsbirkaFragmentResolutionCache cache;

    @Autowired
    private CacheManager cacheManager;

    @Test
    void fetch_delegatesToClient() {
        FragmentResolutionModel model = new FragmentResolutionModel("§ 2 písm. d)", LocalDate.of(2024, 12, 31), true);
        when(client.resolveFragment(FRAGMENT_IRI, VERSION_IRI, LAW_IRI))
                .thenReturn(Optional.of(model));

        Optional<FragmentResolutionModel> out = cache.fetch(FRAGMENT_IRI, VERSION_IRI, LAW_IRI);

        assertEquals(Optional.of(model), out);
        verify(client, times(1)).resolveFragment(FRAGMENT_IRI, VERSION_IRI, LAW_IRI);
    }

    @Test
    void fetch_isCached_secondCallSkipsClient() {
        cacheManager.getCache("esbirkaFragmentResolution").clear();
        FragmentResolutionModel model = new FragmentResolutionModel("§ 2 písm. d)", null, true);
        when(client.resolveFragment(eq(FRAGMENT_IRI), eq(VERSION_IRI), eq(LAW_IRI)))
                .thenReturn(Optional.of(model));

        Optional<FragmentResolutionModel> first = cache.fetch(FRAGMENT_IRI, VERSION_IRI, LAW_IRI);
        Optional<FragmentResolutionModel> second = cache.fetch(FRAGMENT_IRI, VERSION_IRI, LAW_IRI);

        assertEquals(first, second);
        verify(client, times(1)).resolveFragment(eq(FRAGMENT_IRI), eq(VERSION_IRI), eq(LAW_IRI));
    }

    @Test
    void cacheRegistered_withExpectedName() {
        assertNotNull(cacheManager.getCache("esbirkaFragmentResolution"),
                "esbirkaFragmentResolution cache must be registered");
        assertNotNull(cacheManager.getCache("esbirkaLawSearch"));
        assertNotNull(cacheManager.getCache("esbirkaLawVersions"));
    }

    @org.springframework.boot.SpringBootConfiguration
    @org.springframework.context.annotation.ComponentScan(useDefaultFilters = false)
    static class NoOpAppConfig {
    }
}
