package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.EsbirkaSparqlClient;
import com.dia.ismdtoolbackend.config.CacheConfig;
import com.dia.ismdtoolbackend.controller.dto.LawContentDto;
import com.dia.ismdtoolbackend.models.eli.FragmentModel;
import com.dia.ismdtoolbackend.models.eli.LawModel;
import com.dia.ismdtoolbackend.models.eli.LawVersionModel;
import com.dia.ismdtoolbackend.service.EsbirkaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cache behaviour of {@code getLawContent}'s version-aware key. The {@code @Cacheable} SpEL
 * key is evaluated only through the Spring proxy, so these tests run the real cache manager —
 * a plain Mockito unit test cannot catch a key collision between two znění of one law.
 */
@SpringBootTest(classes = {
        CacheConfig.class,
        EsbirkaServiceImpl.class,
        EsbirkaFragmentResolutionCache.class
})
@Import(EsbirkaLawContentCacheTest.NoOpAppConfig.class)
class EsbirkaLawContentCacheTest {

    private static final String LAW_IRI = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/1997/49";
    private static final String LATEST_IRI = LAW_IRI + "/2025-11-01";
    private static final String OLDER_IRI = LAW_IRI + "/2020-01-01";

    /** The pre-.gov.cz host, still present in stored concept sources. */
    private static final String LEGACY_LAW_IRI =
            "https://opendata.eselpoint.cz/esel-esb/eli/cz/sb/1997/49";
    private static final String LEGACY_OLDER_IRI = LEGACY_LAW_IRI + "/2020-01-01";

    @MockitoBean
    private EsbirkaSparqlClient client;

    // Injected as the interface: @Cacheable wraps the bean in a JDK dynamic proxy,
    // so it is not assignable to the impl type.
    @Autowired
    private EsbirkaService service;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        cacheManager.getCache("esbirkaLawContent").clear();
        when(client.findLawByNumberYear("49", 1997)).thenReturn(Optional.of(
                new LawModel(LAW_IRI, "49/1997 Sb.", "49", 1997, "sb")));
        when(client.fetchVersions(LAW_IRI)).thenReturn(List.of(
                new LawVersionModel(LATEST_IRI, LocalDate.of(2025, 11, 1), null, "KONSOL", true),
                new LawVersionModel(OLDER_IRI, LocalDate.of(2020, 1, 1), null, "KONSOL", false)));
        when(client.fetchVersionContent(LATEST_IRI)).thenReturn(List.of(
                fragment(LATEST_IRI, "<p>nové znění</p>")));
        when(client.fetchVersionContent(OLDER_IRI)).thenReturn(List.of(
                fragment(OLDER_IRI, "<p>staré znění</p>")));
    }

    private static FragmentModel fragment(String versionIri, String body) {
        return new FragmentModel(versionIri + "/dokument/norma/par_1",
                versionIri + "/dokument/norma", "§ 1", "par", "0001", body);
    }

    @Test
    void contentNavigationTreeIsFullyLabelled() {
        // The navigation tree is built from /law/content, not /law/fragments. This walks the
        // real 49/1997 shape through getLawContent to prove the labels reach that response:
        // the document root and its containers used to render blank, taking the top two
        // navigation levels with them.
        String dokument = LATEST_IRI + "/dokument";
        String norma = dokument + "/norma";
        String cast = norma + "/cast_1";
        String par = cast + "/par_1";
        String textBlock = par + "/frag_3304837";
        when(client.fetchVersionContent(LATEST_IRI)).thenReturn(List.of(
                new FragmentModel(dokument, null, null, "dokument", "0001", null),
                new FragmentModel(norma, dokument, null, "norma", "0002", null),
                new FragmentModel(dokument + "/prefix", dokument, null, "prefix", "0003", null),
                new FragmentModel(cast, norma, "Část 1", "cast", "0004", null),
                new FragmentModel(par, cast, "§ 1", "par", "0005", null),
                new FragmentModel(textBlock, par, null, "frag", "0006", "<p>text</p>")));

        LawContentDto out = service.getLawContent("49/1997", LATEST_IRI);

        var root = out.getFragments().get(0);
        assertEquals("Zákon č. 49/1997 Sb.", root.getCitation());
        assertEquals("Text předpisu", root.getChildren().get(0).getCitation());
        assertEquals("Úvodní ustanovení", root.getChildren().get(1).getCitation());

        // The unnumbered text block stays in the body but is flagged out of the navigation,
        // rather than surfacing its internal id as "§ 1 frag 3304837".
        var leaf = root.getChildren().get(0).getChildren().get(0).getChildren().get(0)
                .getChildren().get(0);
        assertNull(leaf.getCitation());
        assertFalse(leaf.isNavigable());
        assertTrue(out.getBodyHtml().contains("<p>text</p>"),
                "a non-navigable node must still contribute its text to the rendered body");
    }

    @Test
    void differentVersionsOfSameLawDoNotShareACacheEntry() {
        LawContentDto latest = service.getLawContent("49/1997", LATEST_IRI);
        LawContentDto older = service.getLawContent("49/1997", OLDER_IRI);

        // Each znění must render its OWN text — a version-blind key would return the
        // first-cached body for both.
        assertEquals(LATEST_IRI, latest.getVersionIri());
        assertTrue(latest.getBodyHtml().contains("<p>nové znění</p>"));
        assertEquals(OLDER_IRI, older.getVersionIri());
        assertTrue(older.getBodyHtml().contains("<p>staré znění</p>"),
                "older znění must not serve the latest znění's cached body");

        verify(client, times(1)).fetchVersionContent(LATEST_IRI);
        verify(client, times(1)).fetchVersionContent(OLDER_IRI);
    }

    @Test
    void repeatedRequestForSameVersionIsServedFromCache() {
        LawContentDto first = service.getLawContent("49/1997", OLDER_IRI);
        LawContentDto second = service.getLawContent("49/1997", OLDER_IRI);

        assertEquals(first.getVersionIri(), second.getVersionIri());
        verify(client, times(1)).fetchVersionContent(OLDER_IRI);
    }

    @Test
    void latestByOmissionAndLatestByExplicitIriBothResolveToTheSameContent() {
        // These take different cache keys (null vs the IRI) — an acceptable duplicate entry,
        // but they must agree on what they render.
        LawContentDto implicit = service.getLawContent("49/1997", null);
        LawContentDto explicit = service.getLawContent("49/1997", LATEST_IRI);

        assertEquals(LATEST_IRI, implicit.getVersionIri());
        assertEquals(LATEST_IRI, explicit.getVersionIri());
        assertTrue(implicit.isVersionLatest());
        assertTrue(explicit.isVersionLatest());
        assertEquals(implicit.getBodyHtml(), explicit.getBodyHtml());
    }

    @Test
    void equivalentLawRefVariantsShareOneEntryPerVersion() {
        // normalizeLawRef collapses trim/"Sb." variants; adding the version to the key
        // must not break that collapsing.
        service.getLawContent("49/1997", OLDER_IRI);
        service.getLawContent("  49/1997 Sb. ", OLDER_IRI);

        verify(client, times(1)).fetchVersionContent(OLDER_IRI);
    }


    @Test
    void legacyHostVersionIriRendersTheSameZneniAsItsCanonicalTwin() {
        // A legacy-host IRI used to be rejected outright by /law/content. It is a host
        // spelling, not a different znění — and the membership scan compares against
        // e-Sbírka's own canonical IRIs, so it must be canonicalized before that scan.
        LawContentDto legacy = service.getLawContent("49/1997", LEGACY_OLDER_IRI);

        assertEquals(OLDER_IRI, legacy.getVersionIri(), "echoed back canonical");
        assertTrue(legacy.getBodyHtml().contains("<p>staré znění</p>"));
    }

    @Test
    void legacyAndCanonicalVersionIrisShareOneCacheEntry() {
        // Same znění, two spellings: a raw-string key would issue the identical ~2 MB
        // content fetch twice and hold two copies of it.
        service.getLawContent("49/1997", OLDER_IRI);
        service.getLawContent("49/1997", LEGACY_OLDER_IRI);

        verify(client, times(1)).fetchVersionContent(OLDER_IRI);
    }

    @Test
    void legacyHostLawIriSharesTheVersionListCacheEntry() {
        // getVersions is keyed on the canonical IRI for the same reason.
        cacheManager.getCache("esbirkaLawVersions").clear();
        service.getVersions(LAW_IRI);
        service.getVersions(LEGACY_LAW_IRI);

        verify(client, times(1)).fetchVersions(LAW_IRI);
    }

    @Test
    void oneArgOverloadIsAlsoCached() {
        // The one-arg overload delegates to the two-arg @Cacheable method. A direct
        // this.getLawContent(ref, null) call would bypass the Spring proxy and re-run the
        // whole ~2 MB fetch every time; delegation must go through the self-proxy.
        LawContentDto first = service.getLawContent("49/1997");
        LawContentDto second = service.getLawContent("49/1997");

        assertEquals(LATEST_IRI, first.getVersionIri());
        assertEquals(first.getVersionIri(), second.getVersionIri());
        verify(client, times(1)).fetchVersionContent(LATEST_IRI);
    }

    @Test
    void oneArgAndExplicitNullShareTheSameCacheEntry() {
        // Both routes resolve to the same key, so the second call must be a cache hit.
        service.getLawContent("49/1997");
        service.getLawContent("49/1997", null);

        verify(client, times(1)).fetchVersionContent(LATEST_IRI);
    }

    @Test
    void contentCacheRegistered() {
        assertNotNull(cacheManager.getCache("esbirkaLawContent"));
    }

    @org.springframework.boot.SpringBootConfiguration
    @org.springframework.context.annotation.ComponentScan(useDefaultFilters = false)
    static class NoOpAppConfig {
    }
}