package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.ValidationClient;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapper;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.repository.ValidationReportRepository;
import com.dia.ismdtoolbackend.service.snapshot.NkdLinkDetector;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import com.dia.ismdtoolbackend.utility.published.PublishedResourceUtil;
import org.apache.jena.rdf.model.Resource;

import static org.mockito.Mockito.mock;

/** Builds an {@link OntologyUploadServiceImpl} and its step classes from leaf collaborators, for tests. */
public final class OntologyUploadServiceWiring {

    private static final String MAX_FILE_SIZE = "10MB";
    private static final int PARSE_TIMEOUT_SECONDS = 60;

    private OntologyUploadServiceWiring() {
    }

    /** The service with the real concept gate. */
    public static OntologyUploadServiceImpl build(OntologyMetadataMapper ontologyMetadataMapper,
                                                  OntologyMetadataRepository ontologyMetadataRepository,
                                                  ConceptMetadataRepository conceptMetadataRepository,
                                                  ValidationClient validationClient,
                                                  ValidationReportRepository validationReportRepository,
                                                  JenaTDB2Repository jenaTDB2Repository,
                                                  PublishedResourceUtil deviationChecker) {
        UploadConceptGate gate = new UploadConceptGate(new OntologyDetailExtractor(conceptMetadataRepository),
                new NkdLinkDetector(), conceptMetadataRepository);
        return build(ontologyMetadataMapper, ontologyMetadataRepository, conceptMetadataRepository,
                validationClient, validationReportRepository, jenaTDB2Repository, deviationChecker, gate);
    }

    /** The service with a mocked concept gate, which rejects nothing. */
    public static OntologyUploadServiceImpl buildWithMockedGate(OntologyMetadataMapper ontologyMetadataMapper,
                                                                OntologyMetadataRepository ontologyMetadataRepository,
                                                                ConceptMetadataRepository conceptMetadataRepository,
                                                                ValidationClient validationClient,
                                                                ValidationReportRepository validationReportRepository,
                                                                JenaTDB2Repository jenaTDB2Repository,
                                                                PublishedResourceUtil deviationChecker) {
        return build(ontologyMetadataMapper, ontologyMetadataRepository, conceptMetadataRepository,
                validationClient, validationReportRepository, jenaTDB2Repository, deviationChecker,
                mock(UploadConceptGate.class));
    }

    private static OntologyUploadServiceImpl build(OntologyMetadataMapper ontologyMetadataMapper,
                                                   OntologyMetadataRepository ontologyMetadataRepository,
                                                   ConceptMetadataRepository conceptMetadataRepository,
                                                   ValidationClient validationClient,
                                                   ValidationReportRepository validationReportRepository,
                                                   JenaTDB2Repository jenaTDB2Repository,
                                                   PublishedResourceUtil deviationChecker,
                                                   UploadConceptGate gate) {
        UploadConceptRoleResolver roleResolver = new UploadConceptRoleResolver();
        UploadMetadataWriter metadataWriter = new UploadMetadataWriter(ontologyMetadataMapper,
                ontologyMetadataRepository, conceptMetadataRepository, roleResolver);
        OntologyUploadTransactions transactions = new OntologyUploadTransactions(
                ontologyMetadataRepository, validationReportRepository, metadataWriter);
        return new OntologyUploadServiceImpl(
                new UploadFileReader(MAX_FILE_SIZE, PARSE_TIMEOUT_SECONDS),
                new UploadModelNormalizer(),
                new UploadVocabularyLock(),
                roleResolver,
                gate,
                deviationChecker,
                jenaTDB2Repository,
                metadataWriter,
                transactions,
                new UploadValidationRunner(validationClient, transactions));
    }

    /** Runs the post-upload validation step on its own. */
    public static void runValidation(ValidationClient validationClient,
                                     OntologyMetadataRepository ontologyMetadataRepository,
                                     ValidationReportRepository validationReportRepository,
                                     String ontologyContent, String iri) {
        validationRunner(validationClient, ontologyMetadataRepository, validationReportRepository)
                .requestAndSaveValidationReport(ontologyContent, iri);
    }

    /** The role the upload resolves for {@code concept}, or {@code null} when it rejects the concept. */
    public static ConceptType resolveConceptType(Resource concept) {
        return new UploadConceptRoleResolver().resolveRole(concept).conceptType();
    }

    /** Why the upload rejects {@code concept}, or {@code null} when it resolves a role. */
    public static String roleRejectionReason(Resource concept) {
        return new UploadConceptRoleResolver().resolveRole(concept).reason();
    }

    private static UploadValidationRunner validationRunner(ValidationClient validationClient,
                                                           OntologyMetadataRepository ontologyMetadataRepository,
                                                           ValidationReportRepository validationReportRepository) {
        return new UploadValidationRunner(validationClient, new OntologyUploadTransactions(
                ontologyMetadataRepository, validationReportRepository, null));
    }
}
