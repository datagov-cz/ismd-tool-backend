package com.dia.ismdtoolbackend.testsupport.pg;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.config.NkdConfig;
import com.dia.ismdtoolbackend.mapper.ConceptMetadataMapper;
import com.dia.ismdtoolbackend.mapper.ConceptMetadataMapperImpl;
import com.dia.ismdtoolbackend.outbox.InMemoryTdb2;
import com.dia.ismdtoolbackend.outbox.OutboxConfig;
import com.dia.ismdtoolbackend.outbox.OutboxEntryRepository;
import com.dia.ismdtoolbackend.outbox.OutboxRelay;
import com.dia.ismdtoolbackend.outbox.OutboxRelayScheduler;
import com.dia.ismdtoolbackend.outbox.OutboxRelayTrigger;
import com.dia.ismdtoolbackend.outbox.OutboxRetentionScheduler;
import com.dia.ismdtoolbackend.outbox.OutboxWriter;
import com.dia.ismdtoolbackend.repository.CommentRepository;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.NkdSnapshotService;
import com.dia.ismdtoolbackend.service.impl.ConceptDeviationComparator;
import com.dia.ismdtoolbackend.service.impl.ConceptServiceImpl;
import com.dia.ismdtoolbackend.service.impl.ConceptServiceWiring;
import com.dia.ismdtoolbackend.service.impl.MetadataTouchService;
import com.dia.ismdtoolbackend.service.impl.ReferencedConceptsEnricher;
import com.dia.ismdtoolbackend.service.impl.WorkingCopyDeviationServiceImpl;
import com.dia.ismdtoolbackend.service.nkod.NkodCodelistService;
import com.dia.ismdtoolbackend.service.rpp.RppSnapshotHolder;
import com.dia.ismdtoolbackend.service.snapshot.NkdLinkDetector;
import com.dia.ismdtoolbackend.service.snapshot.NkdSnapshotWarmer;
import com.dia.ismdtoolbackend.utility.creator.ConceptCreator;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import com.dia.ismdtoolbackend.utility.editor.ConceptEditor;
import com.dia.ismdtoolbackend.utility.published.WorkingCopySyncFields;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapperImpl;
import com.dia.ismdtoolbackend.repository.NkdConceptSnapshotRepository;
import com.dia.ismdtoolbackend.repository.ValidationReportRepository;
import com.dia.ismdtoolbackend.service.NkdDetailService;
import com.dia.ismdtoolbackend.service.impl.OntologyServiceImpl;
import com.dia.ismdtoolbackend.service.impl.OntologyServiceWiring;
import com.dia.ismdtoolbackend.utility.editor.OntologyEditor;
import com.dia.ismdtoolbackend.utility.published.PublishedResourceUtil;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.mockito.Mockito.mock;

/**
 * The real outbox (writer, relay, nudge, schedulers) over an {@link InMemoryTdb2}, and a real
 * {@link ConceptServiceImpl} on top of it. The concept service's read-path collaborators are
 * unstubbed mocks: the write flows under test never reach them.
 */
@TestConfiguration
public class OutboxFlowTestBeans {

    @Bean
    OutboxConfig outboxConfig() {
        return new OutboxConfig();
    }

    @Bean
    InMemoryTdb2 inMemoryTdb2() {
        return new InMemoryTdb2();
    }

    @Bean
    @Primary
    ConceptMetadataMapper conceptMetadataMapper() {
        return new ConceptMetadataMapperImpl();
    }

    @Bean
    MetadataTouchService metadataTouchService(ConceptMetadataRepository conceptRepository,
                                              OntologyMetadataRepository ontologyRepository) {
        return new MetadataTouchService(conceptRepository, ontologyRepository);
    }

    @Bean
    OutboxWriter outboxWriter(OutboxEntryRepository repository) {
        return new OutboxWriter(repository);
    }

    @Bean
    OutboxRelay outboxRelay(OutboxEntryRepository repository, InMemoryTdb2 tdb2, OutboxConfig config) {
        return new OutboxRelay(repository, tdb2, config);
    }

    @Bean
    OutboxRelayTrigger outboxRelayTrigger(OutboxRelay relay) {
        return new OutboxRelayTrigger(relay);
    }

    @Bean
    OutboxRelayScheduler outboxRelayScheduler(OutboxRelay relay) {
        return new OutboxRelayScheduler(relay);
    }

    @Bean
    OutboxRetentionScheduler outboxRetentionScheduler(OutboxConfig config, OutboxEntryRepository repository) {
        return new OutboxRetentionScheduler(config, repository);
    }

    @Bean
    ConceptServiceImpl conceptServiceImpl(ConceptMetadataRepository conceptRepository,
                                          OntologyMetadataRepository ontologyRepository,
                                          MetadataTouchService touchService, ConceptMetadataMapper mapper,
                                          InMemoryTdb2 tdb2, OutboxWriter writer,
                                          OutboxEntryRepository outboxRepository, OutboxRelayTrigger trigger) {
        return ConceptServiceWiring.build(
                conceptRepository, ontologyRepository, touchService, mapper,
                new ConceptCreator(), new ConceptEditor(), tdb2,
                mock(OntologyDetailExtractor.class),
                mock(CommentRepository.class),
                mock(NkdSparqlClient.class),
                mock(ConceptDeviationComparator.class),
                mock(RppSnapshotHolder.class),
                mock(NkodCodelistService.class),
                mock(ReferencedConceptsEnricher.class),
                writer, outboxRepository, trigger,
                // No external NKD links in this test data: the real detector finds none, so the
                // mocked snapshot service is never called.
                mock(NkdSnapshotService.class),
                new NkdLinkDetector(),
                new WorkingCopySyncFields(),
                mock(NkdSnapshotWarmer.class),
                new NkdConfig(),
                mock(WorkingCopyDeviationServiceImpl.class));
    }

    @Bean
    OntologyServiceImpl ontologyServiceImpl(OntologyMetadataRepository ontologyRepository,
                                            MetadataTouchService touchService,
                                            ConceptMetadataRepository conceptRepository,
                                            InMemoryTdb2 tdb2, OutboxWriter writer,
                                            OutboxEntryRepository outboxRepository, OutboxRelayTrigger trigger) {
        return OntologyServiceWiring.build(ontologyRepository, touchService, conceptRepository,
                mock(ValidationReportRepository.class), tdb2, mock(CommentRepository.class),
                new OntologyMetadataMapperImpl(), mock(ConceptMetadataMapper.class), new OntologyEditor(),
                mock(OntologyDetailExtractor.class), mock(PublishedResourceUtil.class),
                writer, outboxRepository, trigger, mock(NkdConceptSnapshotRepository.class),
                mock(NkdSnapshotWarmer.class), mock(NkdSnapshotService.class), new NkdConfig(), mock(NkdDetailService.class));
    }
}
