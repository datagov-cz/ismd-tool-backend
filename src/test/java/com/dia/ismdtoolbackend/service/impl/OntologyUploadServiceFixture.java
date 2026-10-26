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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.apache.jena.rdf.model.Resource;

import java.util.concurrent.Executor;

import static org.mockito.Mockito.mock;

/**
 * Builds an {@link OntologyUploadServiceImpl} and its step classes for tests. Every collaborator is a mock unless a
 * test replaces it, and a test reads its mocks from here, so it can only stub what the upload really uses.
 * Create the fixture in {@code @BeforeEach}: mocks made there are covered by the Mockito extension's strict stubs.
 */
@Getter
@Setter
@Accessors(fluent = true, chain = true)
public final class OntologyUploadServiceFixture {

    private static final String MAX_FILE_SIZE = "10MB";
    private static final int PARSE_TIMEOUT_SECONDS = 60;

    private OntologyMetadataMapper ontologyMetadataMapper = mock(OntologyMetadataMapper.class);
    private OntologyMetadataRepository ontologyMetadataRepository = mock(OntologyMetadataRepository.class);
    private ConceptMetadataRepository conceptMetadataRepository = mock(ConceptMetadataRepository.class);
    private ValidationClient validationClient = mock(ValidationClient.class);
    private ValidationReportRepository validationReportRepository = mock(ValidationReportRepository.class);
    private JenaTDB2Repository jenaTDB2Repository = mock(JenaTDB2Repository.class);
    private PublishedResourceUtil publishedResourceUtil = mock(PublishedResourceUtil.class);
    /** {@code null} builds the real gate over {@link #conceptMetadataRepository}. */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private UploadConceptGate uploadConceptGate;
    /** Runs the post-upload validation on the calling thread, so it has finished when the upload returns. */
    private Executor validationExecutor = Runnable::run;

    /** Replaces the concept gate with a mock that rejects nothing. */
    public OntologyUploadServiceFixture withMockedConceptGate() {
        uploadConceptGate = mock(UploadConceptGate.class);
        return this;
    }

    public OntologyUploadServiceImpl build() {
        UploadConceptGate gate = uploadConceptGate != null ? uploadConceptGate
                : new UploadConceptGate(new OntologyDetailExtractor(conceptMetadataRepository),
                        new NkdLinkDetector(), conceptMetadataRepository);
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
                publishedResourceUtil,
                jenaTDB2Repository,
                metadataWriter,
                transactions,
                new UploadValidationRunner(validationClient, transactions, validationExecutor));
    }

    /** Runs the post-upload validation step on its own. */
    public void runValidation(String ontologyContent, String iri) {
        new UploadValidationRunner(validationClient, new OntologyUploadTransactions(
                ontologyMetadataRepository, validationReportRepository, null), validationExecutor)
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
}