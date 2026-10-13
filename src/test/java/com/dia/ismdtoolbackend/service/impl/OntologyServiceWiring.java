package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.config.NkdConfig;
import com.dia.ismdtoolbackend.mapper.ConceptMetadataMapper;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapper;
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

/** Builds an {@link OntologyServiceImpl} and its operation classes from leaf collaborators, for tests. */
public final class OntologyServiceWiring {

    private OntologyServiceWiring() {
    }

    public static OntologyServiceImpl build(OntologyMetadataRepository ontologyMetadataRepository,
                                            MetadataTouchService metadataTouchService,
                                            ConceptMetadataRepository conceptMetadataRepository,
                                            ValidationReportRepository validationReportRepository,
                                            JenaTDB2Repository jenaTDB2Repository,
                                            CommentRepository commentRepository,
                                            OntologyMetadataMapper ontologyMetadataMapper,
                                            ConceptMetadataMapper conceptMetadataMapper,
                                            OntologyEditor ontologyEditor,
                                            OntologyDetailExtractor detailExtractor,
                                            PublishedResourceUtil deviationChecker,
                                            OutboxWriter outboxWriter,
                                            OutboxEntryRepository outboxRepository,
                                            OutboxRelayTrigger outboxRelayTrigger,
                                            NkdConceptSnapshotRepository nkdSnapshotRepository,
                                            NkdSnapshotWarmer nkdSnapshotWarmer,
                                            NkdSnapshotService nkdSnapshotService,
                                            NkdConfig nkdConfig,
                                            NkdDetailService nkdDetailService) {
        OntologyMetadataEnricher enricher = new OntologyMetadataEnricher(
                jenaTDB2Repository, ontologyMetadataMapper, commentRepository);
        InitialGraphGuard initialGraphGuard = new InitialGraphGuard(outboxRepository);
        return new OntologyServiceImpl(
                new OntologyCreateOperation(ontologyMetadataRepository, conceptMetadataRepository,
                        ontologyMetadataMapper, outboxWriter, outboxRepository, outboxRelayTrigger, enricher),
                new OntologyEditOperation(ontologyMetadataRepository, conceptMetadataRepository, jenaTDB2Repository,
                        ontologyEditor, metadataTouchService, ontologyMetadataMapper, enricher, initialGraphGuard),
                new OntologyDeleteOperation(ontologyMetadataRepository, conceptMetadataRepository,
                        validationReportRepository, jenaTDB2Repository, nkdSnapshotService, outboxWriter,
                        outboxRelayTrigger, initialGraphGuard),
                new OntologyDetailReader(ontologyMetadataRepository, conceptMetadataRepository, jenaTDB2Repository,
                        commentRepository, ontologyMetadataMapper, conceptMetadataMapper, detailExtractor,
                        deviationChecker, nkdSnapshotRepository, nkdSnapshotWarmer, nkdConfig, nkdDetailService,
                        enricher),
                new OntologyMetadataReader(ontologyMetadataRepository, ontologyMetadataMapper, enricher),
                new OntologyTurtleExport(jenaTDB2Repository));
    }
}
