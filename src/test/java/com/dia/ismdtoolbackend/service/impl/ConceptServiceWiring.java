package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.config.NkdConfig;
import com.dia.ismdtoolbackend.mapper.ConceptMetadataMapper;
import com.dia.ismdtoolbackend.outbox.OutboxEntryRepository;
import com.dia.ismdtoolbackend.outbox.OutboxRelayTrigger;
import com.dia.ismdtoolbackend.outbox.OutboxWriter;
import com.dia.ismdtoolbackend.repository.CommentRepository;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.NkdSnapshotService;
import com.dia.ismdtoolbackend.service.WorkingCopyDeviationService;
import com.dia.ismdtoolbackend.service.nkod.NkodCodelistService;
import com.dia.ismdtoolbackend.service.rpp.RppSnapshotHolder;
import com.dia.ismdtoolbackend.service.snapshot.NkdLinkDetector;
import com.dia.ismdtoolbackend.service.snapshot.NkdSnapshotWarmer;
import com.dia.ismdtoolbackend.utility.creator.ConceptCreator;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import com.dia.ismdtoolbackend.utility.editor.ConceptEditor;
import com.dia.ismdtoolbackend.utility.published.WorkingCopySyncFields;

/** Builds a {@link ConceptServiceImpl} and its operation classes from leaf collaborators, for tests. */
public final class ConceptServiceWiring {

    private ConceptServiceWiring() {
    }

    public static ConceptServiceImpl build(ConceptMetadataRepository conceptMetadataRepository,
                                           OntologyMetadataRepository ontologyMetadataRepository,
                                           MetadataTouchService metadataTouchService,
                                           ConceptMetadataMapper conceptMetadataMapper,
                                           ConceptCreator conceptCreator,
                                           ConceptEditor conceptEditor,
                                           JenaTDB2Repository jenaTDB2Repository,
                                           OntologyDetailExtractor detailExtractor,
                                           CommentRepository commentRepository,
                                           NkdSparqlClient nkdSparqlClient,
                                           ConceptDeviationComparator deviationComparator,
                                           RppSnapshotHolder rppSnapshotHolder,
                                           NkodCodelistService nkodCodelistService,
                                           ReferencedConceptsEnricher referencedConceptsEnricher,
                                           OutboxWriter outboxWriter,
                                           OutboxEntryRepository outboxRepository,
                                           OutboxRelayTrigger outboxRelayTrigger,
                                           NkdSnapshotService nkdSnapshotService,
                                           NkdLinkDetector nkdLinkDetector,
                                           WorkingCopySyncFields syncFields,
                                           NkdSnapshotWarmer nkdSnapshotWarmer,
                                           NkdConfig nkdConfig,
                                           WorkingCopyDeviationService workingCopyDeviationService) {
        ConceptAccessGuard accessGuard = new ConceptAccessGuard(conceptMetadataRepository);
        InitialGraphGuard initialGraphGuard = new InitialGraphGuard(outboxRepository);
        ConceptEditOperation editOperation = new ConceptEditOperation(conceptMetadataRepository, jenaTDB2Repository,
                metadataTouchService, conceptMetadataMapper, conceptEditor, outboxWriter, outboxRelayTrigger,
                accessGuard, initialGraphGuard,
                new NkdLinkReconciler(conceptMetadataRepository, nkdSparqlClient, nkdSnapshotService, nkdLinkDetector));
        ConceptDetailReader detailReader = new ConceptDetailReader(conceptMetadataRepository, jenaTDB2Repository,
                conceptMetadataMapper, commentRepository, detailExtractor, referencedConceptsEnricher,
                rppSnapshotHolder, nkodCodelistService, nkdSnapshotService, nkdSnapshotWarmer, nkdConfig,
                workingCopyDeviationService);
        return new ConceptServiceImpl(
                new ConceptCreateOperation(conceptMetadataRepository, ontologyMetadataRepository, metadataTouchService,
                        conceptMetadataMapper, conceptCreator, outboxWriter, outboxRelayTrigger, initialGraphGuard),
                editOperation,
                new ConceptDeleteOperation(conceptMetadataRepository, jenaTDB2Repository, metadataTouchService,
                        nkdSnapshotService, outboxWriter, outboxRelayTrigger, accessGuard, initialGraphGuard),
                new WorkingCopySyncOperation(conceptMetadataRepository, jenaTDB2Repository, nkdSparqlClient,
                        deviationComparator, syncFields, accessGuard, editOperation, detailReader),
                detailReader);
    }
}
