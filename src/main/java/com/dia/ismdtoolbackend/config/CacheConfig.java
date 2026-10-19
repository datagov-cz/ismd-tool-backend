package com.dia.ismdtoolbackend.config;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.service.impl.WorkingCopyDeviationServiceImpl;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import org.springframework.core.env.Environment;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine-backed caches for read-only e-Sbírka SPARQL operations.
 *
 * <p>Caches with distinct lifetimes:
 * <ul>
 *   <li>{@code esbirkaLawSearch} — law-search results, 60 min TTL (catalogue evolves daily).</li>
 *   <li>{@code esbirkaLawVersions} — version lists per law, 60 min TTL.</li>
 *   <li>{@code esbirkaFragmentResolution} — fragment citation + version metadata,
 *       24 h TTL (fragments are immutable; only is-latest can shift when a new
 *       version is published).</li>
 *   <li>{@code esbirkaLawContent} — whole-version content trees (fragment tree + HTML
 *       bodies), 24 h TTL (published text is immutable).</li>
 *   <li>{@code esbirkaVersionContent} — a version's fragments as read from the e-Sbírka REST
 *       API (all pages), 24 h TTL. Splits the weight budget with {@code esbirkaLawContent}.</li>
 *   <li>{@code esbirkaRestVersions} — REST version lists per law, 5 min TTL.</li>
 *   <li>{@code esbirkaVersionSubtreeBodies} — per-version fragment IRI → assembled subtree
 *       HTML, backing the {@code /resolve} body fallback for structural fragments. Same
 *       TTL/sizing as {@code esbirkaLawContent}.</li>
 * </ul>
 *
 * <p>Per-cache specs require {@code registerCustomCache} rather than the shared
 * {@code setCaffeine}.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    static final long ESBIRKA_CATALOGUE_TTL_MINUTES = 60;
    static final long ESBIRKA_CATALOGUE_MAX_ENTRIES = 1_000;

    static final long ESBIRKA_RESOLUTION_TTL_HOURS = 24;
    static final long ESBIRKA_RESOLUTION_MAX_ENTRIES = 5_000;

    // Whole-version content runs to ~10 MB for the largest laws: bounded by weight
    // (esbirka.cache-budget-mb), with a long TTL — a published version's text is immutable.
    static final long ESBIRKA_CONTENT_TTL_HOURS = 24;
    static final long ESBIRKA_REST_VERSIONS_TTL_MINUTES = 5;
    static final long ESBIRKA_SUBTREE_MAX_ENTRIES = 200;

    static final long CONCEPT_METADATA_TTL_HOURS = 24;
    static final long CONCEPT_METADATA_MAX_ENTRIES = 10_000;

    // NKD-published concept/ontology projections behind the deviation checks. NKD published data
    // is near-immutable, so a 24h write-TTL is the freshness mechanism; local ISMD edits do NOT
    // evict (the cached value is the NKD side, not the local copy). See NkdSparqlClient.
    static final long NKD_PUBLISHED_TTL_HOURS = 24;
    static final long NKD_PUBLISHED_MAX_ENTRIES = 10_000;

    @Bean
    public CacheManager cacheManager(ObjectProvider<EsbirkaConfig> esbirkaConfig, Environment environment) {
        CaffeineCacheManager mgr = new CaffeineCacheManager();
        EsbirkaConfig esbirka = esbirkaConfig.getIfAvailable(EsbirkaConfig::new);
        long budgetBytes = esbirka.getCacheBudgetMb() * 1024L * 1024L;

        mgr.registerCustomCache("esbirkaLawSearch", Caffeine.newBuilder()
                .expireAfterWrite(ESBIRKA_CATALOGUE_TTL_MINUTES, TimeUnit.MINUTES)
                .maximumSize(ESBIRKA_CATALOGUE_MAX_ENTRIES)
                .build());

        mgr.registerCustomCache("esbirkaLawVersions", Caffeine.newBuilder()
                .expireAfterWrite(ESBIRKA_CATALOGUE_TTL_MINUTES, TimeUnit.MINUTES)
                .maximumSize(ESBIRKA_CATALOGUE_MAX_ENTRIES)
                .build());

        mgr.registerCustomCache("esbirkaFragmentResolution", Caffeine.newBuilder()
                .expireAfterWrite(ESBIRKA_RESOLUTION_TTL_HOURS, TimeUnit.HOURS)
                .maximumSize(ESBIRKA_RESOLUTION_MAX_ENTRIES)
                .build());

        boolean rest = "rest".equals(environment.getProperty("esbirka.provider", "sparql"));
        long versionContentBytes = rest ? budgetBytes / 2 : 0;
        mgr.registerCustomCache("esbirkaLawContent", contentCache(budgetBytes - versionContentBytes));
        mgr.registerCustomCache("esbirkaVersionContent", contentCache(versionContentBytes));

        // Short: dedups the historie call within one content load or burst of /resolve calls.
        // esbirkaLawVersions caches /law/versions on top, so a new version shows within ~65 min.
        mgr.registerCustomCache("esbirkaRestVersions", Caffeine.newBuilder()
                .expireAfterWrite(ESBIRKA_REST_VERSIONS_TTL_MINUTES, TimeUnit.MINUTES)
                .maximumSize(ESBIRKA_CATALOGUE_MAX_ENTRIES)
                .build());

        // Per-version map of fragment IRI -> assembled subtree HTML, backing the /resolve
        // body fallback for structural fragments (document root, containers) that carry no
        // obsah of their own. Keyed by version IRI, so one fetch serves every container of
        // that znění. Built from fetchVersionContent, which the REST client serves from
        // esbirkaVersionContent, so a version is not fetched twice.
        mgr.registerCustomCache("esbirkaVersionSubtreeBodies", Caffeine.newBuilder()
                .expireAfterWrite(ESBIRKA_CONTENT_TTL_HOURS, TimeUnit.HOURS)
                .maximumSize(ESBIRKA_SUBTREE_MAX_ENTRIES)
                .build());

        // Backs the referenced-concept resolution engine (ReferencedConceptResolutionEngine),
        // driven inline from the concept-detail flow via ReferencedConceptsEnricher.
        // Per-IRI entries so partially-overlapping detail views share cache hits. 24h
        // TTL is the safety net for NKD-side changes we can't observe; ISMD mutations
        // are invalidated synchronously via @CacheEvict on OntologyServiceImpl.
        mgr.registerCustomCache("conceptMetadataResolution", Caffeine.newBuilder()
                .expireAfterWrite(CONCEPT_METADATA_TTL_HOURS, TimeUnit.HOURS)
                .maximumSize(CONCEPT_METADATA_MAX_ENTRIES)
                .build());

        // NKD-published projections (NkdSparqlClient.PUBLISHED_RESOURCE_CACHE). Key shapes share it,
        // all NKD-sourced with the same 24h-TTL freshness model: 'concept:' / 'conceptWithScheme:' /
        // 'ontology:' / 'ontologyRaw:' from NkdSparqlClient, and 'conceptList:' from
        // NkdDetailServiceImpl.listOntologyConcepts.
        //
        // Sizing note: maximumSize counts ENTRIES, not bytes, and 'ontologyRaw:' holds a whole Jena
        // model for one vocabulary (~400 KB for a 381-concept scheme) against ~1 KB for a 'concept:'
        // entry. One raw entry exists per distinct vocabulary read, so the realistic count is small
        // (tens), but if the cache is ever dominated by raw models the count-based cap no longer
        // bounds heap usefully — switch to Caffeine weigher/maximumWeight before raising it.
        mgr.registerCustomCache(NkdSparqlClient.PUBLISHED_RESOURCE_CACHE, Caffeine.newBuilder()
                .expireAfterWrite(NKD_PUBLISHED_TTL_HOURS, TimeUnit.HOURS)
                .maximumSize(NKD_PUBLISHED_MAX_ENTRIES)
                .build());

        // Canonical local concept projection behind working-copy deviation (WorkingCopyDeviationService).
        // Both ontology detail and concept detail compare against THIS one cached local read, so they can
        // never disagree. ISMD edits evict it synchronously (@CacheEvict on the concept/ontology write
        // paths); its freshness w.r.t. NKD rides the NKD projection TTL above, so a 24h write-TTL matches.
        mgr.registerCustomCache(WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE, Caffeine.newBuilder()
                .expireAfterWrite(NKD_PUBLISHED_TTL_HOURS, TimeUnit.HOURS)
                .maximumSize(NKD_PUBLISHED_MAX_ENTRIES)
                .build());

        return mgr;
    }

    private static com.github.benmanes.caffeine.cache.Cache<Object, Object> contentCache(long maxBytes) {
        return Caffeine.newBuilder()
                .expireAfterWrite(ESBIRKA_CONTENT_TTL_HOURS, TimeUnit.HOURS)
                .maximumWeight(maxBytes)
                .weigher(new EsbirkaContentWeigher())
                .build();
    }
}
