package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.config.NkdConfig;
import com.dia.ismdtoolbackend.mapper.ConceptMetadataMapper;
import com.dia.ismdtoolbackend.mapper.ConceptMetadataMapperImpl;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapper;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapperImpl;
import com.dia.ismdtoolbackend.outbox.OutboxEntryRepository;
import com.dia.ismdtoolbackend.outbox.OutboxRelayTrigger;
import com.dia.ismdtoolbackend.outbox.OutboxWriter;
import com.dia.ismdtoolbackend.repository.CommentRepository;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.NkdConceptSnapshotRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.repository.ValidationReportRepository;
import com.dia.ismdtoolbackend.service.NkdDetailService;
import com.dia.ismdtoolbackend.service.NkdSnapshotService;
import com.dia.ismdtoolbackend.service.snapshot.NkdSnapshotWarmer;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import com.dia.ismdtoolbackend.utility.editor.OntologyEditor;
import com.dia.ismdtoolbackend.utility.published.PublishedResourceUtil;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import static org.mockito.Mockito.mock;

/**
 * Builds an {@link OntologyServiceImpl} and its operation classes for tests. Every collaborator is a mock unless a
 * test replaces it, and a test reads its mocks from here, so it can only stub what the service really uses.
 * Create the fixture in {@code @BeforeEach}: mocks made there are covered by the Mockito extension's strict stubs.
 */
@Getter
@Setter
@Accessors(fluent = true, chain = true)
public final class OntologyServiceFixture {

    private OntologyMetadataRepository ontologyMetadataRepository = mock(OntologyMetadataRepository.class);
    private MetadataTouchService metadataTouchService = mock(MetadataTouchService.class);
    private ConceptMetadataRepository conceptMetadataRepository = mock(ConceptMetadataRepository.class);
    private ValidationReportRepository validationReportRepository = mock(ValidationReportRepository.class);
    private JenaTDB2Repository jenaTDB2Repository = mock(JenaTDB2Repository.class);
    private CommentRepository commentRepository = mock(CommentRepository.class);
    /** Real mappers: generated and pure, so a test asserts what the mapping really produces. */
    private OntologyMetadataMapper ontologyMetadataMapper = new OntologyMetadataMapperImpl();
    private ConceptMetadataMapper conceptMetadataMapper = new ConceptMetadataMapperImpl();
    private OntologyEditor ontologyEditor = mock(OntologyEditor.class);
    private OntologyDetailExtractor detailExtractor = mock(OntologyDetailExtractor.class);
    private PublishedResourceUtil deviationChecker = mock(PublishedResourceUtil.class);
    private OutboxWriter outboxWriter = mock(OutboxWriter.class);
    private OutboxEntryRepository outboxRepository = mock(OutboxEntryRepository.class);
    private OutboxRelayTrigger outboxRelayTrigger = mock(OutboxRelayTrigger.class);
    private NkdConceptSnapshotRepository nkdSnapshotRepository = mock(NkdConceptSnapshotRepository.class);
    private NkdSnapshotWarmer nkdSnapshotWarmer = mock(NkdSnapshotWarmer.class);
    private NkdSnapshotService nkdSnapshotService = mock(NkdSnapshotService.class);
    private NkdDetailService nkdDetailService = mock(NkdDetailService.class);
    private NkdConfig nkdConfig = new NkdConfig();

    public OntologyServiceImpl build() {
        OntologyMetadataEnricher enricher = new OntologyMetadataEnricher(
                jenaTDB2Repository, ontologyMetadataMapper, commentRepository);
        InitialGraphGuard initialGraphGuard = new InitialGraphGuard(outboxRepository);
        return new OntologyServiceImpl(
                new OntologyCreateOperation(ontologyMetadataRepository, conceptMetadataRepository,
                        ontologyMetadataMapper, outboxWriter, outboxRepository, outboxRelayTrigger, enricher),
                new OntologyEditOperation(ontologyMetadataRepository, conceptMetadataRepository, jenaTDB2Repository,
                        ontologyEditor, metadataTouchService, ontologyMetadataMapper, enricher, initialGraphGuard),
                new OntologyDeleteOperation(ontologyMetadataRepository, conceptMetadataRepository,
                        validationReportRepository, nkdSnapshotService, outboxWriter,
                        outboxRelayTrigger, initialGraphGuard),
                new OntologyDetailReader(ontologyMetadataRepository, conceptMetadataRepository, jenaTDB2Repository,
                        commentRepository, ontologyMetadataMapper, conceptMetadataMapper, detailExtractor,
                        deviationChecker, nkdSnapshotRepository, nkdSnapshotWarmer, nkdConfig, nkdDetailService,
                        enricher),
                new OntologyMetadataReader(ontologyMetadataRepository, ontologyMetadataMapper, enricher),
                new OntologyTurtleExport(jenaTDB2Repository));
    }
}