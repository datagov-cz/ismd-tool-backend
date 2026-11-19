package com.dia.ismdtoolbackend.testsupport.pg;

import com.dia.ismdtoolbackend.mapper.ConceptMetadataMapper;
import com.dia.ismdtoolbackend.mapper.ConceptMetadataMapperImpl;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapperImpl;
import com.dia.ismdtoolbackend.outbox.InMemoryTdb2;
import com.dia.ismdtoolbackend.outbox.OutboxConfig;
import com.dia.ismdtoolbackend.outbox.OutboxEntryRepository;
import com.dia.ismdtoolbackend.outbox.OutboxRelay;
import com.dia.ismdtoolbackend.outbox.OutboxRelayScheduler;
import com.dia.ismdtoolbackend.outbox.OutboxRelayTrigger;
import com.dia.ismdtoolbackend.outbox.OutboxRetentionScheduler;
import com.dia.ismdtoolbackend.outbox.OutboxWriter;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.impl.ConceptServiceFixture;
import com.dia.ismdtoolbackend.service.impl.ConceptServiceImpl;
import com.dia.ismdtoolbackend.service.impl.MetadataTouchService;
import com.dia.ismdtoolbackend.service.impl.OntologyServiceFixture;
import com.dia.ismdtoolbackend.service.impl.OntologyServiceImpl;
import com.dia.ismdtoolbackend.utility.creator.ConceptCreator;
import com.dia.ismdtoolbackend.utility.editor.ConceptEditor;
import com.dia.ismdtoolbackend.utility.editor.OntologyEditor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

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
        // No external NKD links in this test data: the real detector finds none, so the mocked snapshot
        // service is never called.
        return new ConceptServiceFixture()
                .conceptMetadataRepository(conceptRepository)
                .ontologyMetadataRepository(ontologyRepository)
                .metadataTouchService(touchService)
                .conceptMetadataMapper(mapper)
                .conceptCreator(new ConceptCreator())
                .conceptEditor(new ConceptEditor())
                .jenaTDB2Repository(tdb2)
                .outboxWriter(writer)
                .outboxRepository(outboxRepository)
                .outboxRelayTrigger(trigger)
                .build();
    }

    @Bean
    OntologyServiceImpl ontologyServiceImpl(OntologyMetadataRepository ontologyRepository,
                                            MetadataTouchService touchService,
                                            ConceptMetadataRepository conceptRepository,
                                            InMemoryTdb2 tdb2, OutboxWriter writer,
                                            OutboxEntryRepository outboxRepository, OutboxRelayTrigger trigger) {
        return new OntologyServiceFixture()
                .ontologyMetadataRepository(ontologyRepository)
                .metadataTouchService(touchService)
                .conceptMetadataRepository(conceptRepository)
                .jenaTDB2Repository(tdb2)
                .ontologyMetadataMapper(new OntologyMetadataMapperImpl())
                .ontologyEditor(new OntologyEditor())
                .outboxWriter(writer)
                .outboxRepository(outboxRepository)
                .outboxRelayTrigger(trigger)
                .build();
    }
}
