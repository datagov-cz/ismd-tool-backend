package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.config.NkdConfig;
import com.dia.ismdtoolbackend.mapper.ConceptMetadataMapper;
import com.dia.ismdtoolbackend.mapper.ConceptMetadataMapperImpl;
import com.dia.ismdtoolbackend.outbox.OutboxEntryRepository;
import com.dia.ismdtoolbackend.outbox.OutboxRelayTrigger;
import com.dia.ismdtoolbackend.outbox.OutboxWriter;
import com.dia.ismdtoolbackend.repository.CommentRepository;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.NkdSnapshotService;
import com.dia.ismdtoolbackend.service.nkod.NkodCodelistService;
import com.dia.ismdtoolbackend.service.rpp.RppSnapshotHolder;
import com.dia.ismdtoolbackend.service.snapshot.NkdLinkDetector;
import com.dia.ismdtoolbackend.service.snapshot.NkdSnapshotWarmer;
import com.dia.ismdtoolbackend.utility.creator.ConceptCreator;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import com.dia.ismdtoolbackend.utility.editor.ConceptEditor;
import com.dia.ismdtoolbackend.utility.published.WorkingCopySyncFields;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import static org.mockito.Mockito.mock;

/**
 * Builds a {@link ConceptServiceImpl} and its operation classes for tests. Collaborators doing I/O are mocks and pure
 * ones are real, unless a test replaces them; a test reads its mocks from here, so it can only stub what the service
 * really uses. A delegate's own test builds only that delegate with the matching {@code build*} method.
 * Create the fixture in {@code @BeforeEach}: mocks made there are covered by the Mockito extension's strict stubs.
 */
@Getter
@Setter
@Accessors(fluent = true, chain = true)
public final class ConceptServiceFixture {

    private ConceptMetadataRepository conceptMetadataRepository = mock(ConceptMetadataRepository.class);
    private OntologyMetadataRepository ontologyMetadataRepository = mock(OntologyMetadataRepository.class);
    private MetadataTouchService metadataTouchService = mock(MetadataTouchService.class);
    /** Real: generated and pure, so a test asserts what the mapping really produces. */
    private ConceptMetadataMapper conceptMetadataMapper = new ConceptMetadataMapperImpl();
    private ConceptCreator conceptCreator = mock(ConceptCreator.class);
    private ConceptEditor conceptEditor = mock(ConceptEditor.class);
    private JenaTDB2Repository jenaTDB2Repository = mock(JenaTDB2Repository.class);
    private OntologyDetailExtractor detailExtractor = mock(OntologyDetailExtractor.class);
    private CommentRepository commentRepository = mock(CommentRepository.class);
    private NkdSparqlClient nkdSparqlClient = mock(NkdSparqlClient.class);
    /** Real: pure, so a sync test sees the deviation its inputs really produce. */
    private ConceptDeviationComparator deviationComparator = new ConceptDeviationComparator();
    private RppSnapshotHolder rppSnapshotHolder = mock(RppSnapshotHolder.class);
    private NkodCodelistService nkodCodelistService = mock(NkodCodelistService.class);
    private ReferencedConceptsEnricher referencedConceptsEnricher = mock(ReferencedConceptsEnricher.class);
    private OutboxWriter outboxWriter = mock(OutboxWriter.class);
    private OutboxEntryRepository outboxRepository = mock(OutboxEntryRepository.class);
    private OutboxRelayTrigger outboxRelayTrigger = mock(OutboxRelayTrigger.class);
    private NkdSnapshotService nkdSnapshotService = mock(NkdSnapshotService.class);
    private NkdSnapshotWarmer nkdSnapshotWarmer = mock(NkdSnapshotWarmer.class);
    private WorkingCopyDeviationServiceImpl workingCopyDeviationService = mock(WorkingCopyDeviationServiceImpl.class);
    /** Real: pure, and finds no links in data without external NKD triples. */
    private NkdLinkDetector nkdLinkDetector = new NkdLinkDetector();
    private WorkingCopySyncFields syncFields = new WorkingCopySyncFields();
    private NkdConfig nkdConfig = new NkdConfig();

    public ConceptServiceImpl build() {
        ConceptEditOperation editOperation = buildEditOperation();
        ConceptDetailReader detailReader = buildDetailReader();
        return new ConceptServiceImpl(
                buildCreateOperation(),
                editOperation,
                buildDeleteOperation(),
                new WorkingCopySyncOperation(conceptMetadataRepository, jenaTDB2Repository, nkdSparqlClient,
                        deviationComparator, syncFields, accessGuard(), editOperation, detailReader),
                detailReader);
    }

    ConceptCreateOperation buildCreateOperation() {
        return new ConceptCreateOperation(conceptMetadataRepository, ontologyMetadataRepository, metadataTouchService,
                conceptMetadataMapper, conceptCreator, outboxWriter, outboxRelayTrigger, initialGraphGuard());
    }

    ConceptEditOperation buildEditOperation() {
        return new ConceptEditOperation(conceptMetadataRepository, jenaTDB2Repository,
                metadataTouchService, conceptMetadataMapper, conceptEditor, outboxWriter, outboxRelayTrigger,
                accessGuard(), initialGraphGuard(), buildNkdLinkReconciler());
    }

    ConceptDeleteOperation buildDeleteOperation() {
        return new ConceptDeleteOperation(conceptMetadataRepository, jenaTDB2Repository, metadataTouchService,
                nkdSnapshotService, outboxWriter, outboxRelayTrigger, accessGuard(), initialGraphGuard());
    }

    ConceptDetailReader buildDetailReader() {
        return new ConceptDetailReader(conceptMetadataRepository, jenaTDB2Repository,
                conceptMetadataMapper, commentRepository, detailExtractor, referencedConceptsEnricher,
                rppSnapshotHolder, nkodCodelistService, nkdSnapshotService, nkdSnapshotWarmer, nkdConfig,
                workingCopyDeviationService);
    }

    NkdLinkReconciler buildNkdLinkReconciler() {
        return new NkdLinkReconciler(conceptMetadataRepository, nkdSparqlClient, nkdSnapshotService, nkdLinkDetector);
    }

    private ConceptAccessGuard accessGuard() {
        return new ConceptAccessGuard(conceptMetadataRepository);
    }

    private InitialGraphGuard initialGraphGuard() {
        return new InitialGraphGuard(outboxRepository);
    }
}
