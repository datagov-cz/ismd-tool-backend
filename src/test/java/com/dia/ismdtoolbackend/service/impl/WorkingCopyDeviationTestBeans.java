package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;

import static org.mockito.Mockito.mock;

/** The real working-copy deviation service and its cached local projection reader, as Spring beans for tests. */
@TestConfiguration
public class WorkingCopyDeviationTestBeans {

    @Bean
    LocalConceptProjectionReader localConceptProjectionReader(ConceptMetadataRepository conceptMetadataRepository,
                                                              JenaTDB2Repository jenaTDB2Repository,
                                                              OntologyDetailExtractor detailExtractor,
                                                              CacheManager cacheManager) {
        return new LocalConceptProjectionReader(conceptMetadataRepository, jenaTDB2Repository, detailExtractor,
                cacheManager);
    }

    @Bean
    WorkingCopyDeviationServiceImpl workingCopyDeviationService(NkdSparqlClient nkdSparqlClient,
                                                                CacheManager cacheManager,
                                                                LocalConceptProjectionReader localProjectionReader) {
        return new WorkingCopyDeviationServiceImpl(new ConceptDeviationComparator(), nkdSparqlClient,
                mock(DeviationResolutionEnricher.class), cacheManager, localProjectionReader);
    }
}
