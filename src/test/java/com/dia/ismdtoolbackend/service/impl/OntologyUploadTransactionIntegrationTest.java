package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.IsmdToolBackendApplication;
import com.dia.ismdtoolbackend.client.ValidationClient;
import com.dia.ismdtoolbackend.config.JpaAuditingConfig;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.OntologyValidationStatus;
import com.dia.ismdtoolbackend.exception.OntologyAlreadyExistsException;
import com.dia.ismdtoolbackend.exception.OntologyUploadInProgressException;
import com.dia.ismdtoolbackend.exception.OntologyUploadMetadataException;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapper;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapperImpl;
import com.dia.ismdtoolbackend.outbox.PostgresIntegrationTestBase;
import com.dia.ismdtoolbackend.outbox.TransactionTemplateConfig;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.repository.ValidationReportRepository;
import com.dia.ismdtoolbackend.service.snapshot.NkdLinkDetector;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import com.dia.ismdtoolbackend.utility.published.PublishedResourceUtil;
import org.apache.jena.ontology.OntModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The upload's transaction boundaries against real Postgres: the metadata rows commit together,
 * no transaction is open while the graph is written, and validation starts only after the commit.
 */
@DataJpaTest
// Named explicitly: this package holds other tests' nested configurations, which the search would find.
@ContextConfiguration(classes = IsmdToolBackendApplication.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("junit")
@EntityScan(basePackageClasses = ConceptMetadataEntity.class)
@EnableJpaRepositories(basePackageClasses = ConceptMetadataRepository.class)
@Import({JpaAuditingConfig.class, TransactionTemplateConfig.class,
        OntologyUploadServiceImpl.class, UploadFileReader.class, UploadModelNormalizer.class, UploadVocabularyLock.class,
        UploadConceptRoleResolver.class, UploadConceptGate.class, UploadMetadataWriter.class,
        UploadValidationRunner.class, OntologyUploadTransactions.class,
        OntologyUploadTransactionIntegrationTest.Beans.class})
// NOT_SUPPORTED: the upload opens its own transactions, as it does behind a real request.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OntologyUploadTransactionIntegrationTest extends PostgresIntegrationTestBase {

    private static final String VOCABULARY = "https://slovník.gov.cz/agendový/upload-tx";
    private static final String CONCEPT = VOCABULARY + "/pojem/osoba";
    private static final String TURTLE = "@prefix owl: <http://www.w3.org/2002/07/owl#> ."
            + " @prefix skos: <http://www.w3.org/2004/02/skos/core#> ."
            + " @prefix ofn: <https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/> ."
            + " <" + VOCABULARY + "> a owl:Ontology, skos:ConceptScheme ."
            + " <" + CONCEPT + "> a ofn:pojem, owl:Class ; skos:inScheme <" + VOCABULARY + "> .";

    @Autowired private OntologyUploadServiceImpl uploadService;
    @Autowired private OntologyMetadataRepository ontologyRepository;
    @Autowired private ConceptMetadataRepository conceptRepository;
    @Autowired private ValidationReportRepository validationReportRepository;
    @Autowired private TransactionTemplate txTemplate;
    @Autowired private JenaTDB2Repository jenaTDB2Repository;
    @Autowired private ValidationClient validationClient;
    @Autowired private PublishedResourceUtil publishedResourceUtil;
    @Autowired private OntologyMetadataMapper ontologyMetadataMapper;

    @BeforeEach
    void setUp() {
        reset(jenaTDB2Repository, validationClient, publishedResourceUtil, ontologyMetadataMapper);
        when(publishedResourceUtil.checkPublishedResourcesInNKD(any(), any())).thenReturn(List.of());
        when(validationClient.requestValidationLenient(anyString(), anyString())).thenReturn(Optional.empty());
        txTemplate.executeWithoutResult(tx -> {
            validationReportRepository.deleteAllInBatch();
            conceptRepository.deleteAllInBatch();
            ontologyRepository.deleteAllInBatch();
        });
    }

    @Test
    void upload_commitsTheOntologyAndItsConceptsTogether() throws Exception {
        uploadService.uploadFromFile(file(), "user", null, null);

        assertThat(ontologyRepository.findByGraphName(VOCABULARY)).isPresent();
        assertThat(conceptRepository.findByConceptIri(CONCEPT)).isPresent();
    }

    @Test
    void upload_holdsNoTransactionWhileWritingTheGraph() throws Exception {
        AtomicReference<Boolean> transactionActive = new AtomicReference<>();
        doAnswer(invocation -> {
            transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(jenaTDB2Repository).putOntologyModel(anyString(), any(OntModel.class));

        uploadService.uploadFromFile(file(), "user", null, null);

        assertThat(transactionActive.get()).isFalse();
    }

    @Test
    void upload_startsValidationOnlyAfterTheMetadataIsCommitted() throws Exception {
        AtomicReference<Boolean> rowVisibleToValidation = new AtomicReference<>();
        when(validationClient.requestValidationLenient(anyString(), anyString())).thenAnswer(invocation -> {
            // Runs on the validation thread, on its own connection: it sees only committed rows.
            rowVisibleToValidation.set(ontologyRepository.findByGraphName(VOCABULARY).isPresent());
            return Optional.empty();
        });

        uploadService.uploadFromFile(file(), "user", null, null);

        assertThat(await(rowVisibleToValidation::get)).isTrue();
    }

    @Test
    void upload_recordsTheOutcomeOfAValidatorThatAnswersAtOnce() throws Exception {
        uploadService.uploadFromFile(file(), "user", null, null);

        OntologyValidationStatus status = await(() -> ontologyRepository.findByGraphName(VOCABULARY)
                .map(OntologyMetadataEntity::getLastValidationStatus).orElse(null));

        assertThat(status).isEqualTo(OntologyValidationStatus.SKIPPED_UNAVAILABLE);
    }

    @Test
    void upload_failingMetadataWrite_rollsBackEveryRowAndDeletesTheGraph() {
        // Fails after the ontology row is written, so the rollback has something to undo.
        doThrow(new IllegalStateException("mapper failed")).when(ontologyMetadataMapper)
                .toDto(any(OntologyMetadataEntity.class));

        assertThatThrownBy(() -> uploadService.uploadFromFile(file(), "user", null, null))
                .isInstanceOf(OntologyUploadMetadataException.class);

        assertThat(ontologyRepository.findByGraphName(VOCABULARY)).isEmpty();
        assertThat(conceptRepository.findByConceptIri(CONCEPT)).isEmpty();
        verify(jenaTDB2Repository).deleteGraph(VOCABULARY);
    }

    @Test
    void secondUploadOfAVocabularyStillInProgress_isRejectedBeforeItWritesAnything() throws Exception {
        CountDownLatch firstIsWritingTheGraph = new CountDownLatch(1);
        CountDownLatch letTheFirstFinish = new CountDownLatch(1);
        doAnswer(invocation -> {
            firstIsWritingTheGraph.countDown();
            letTheFirstFinish.await(10, TimeUnit.SECONDS);
            return null;
        }).when(jenaTDB2Repository).putOntologyModel(anyString(), any(OntModel.class));

        ExecutorService uploads = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = uploads.submit(() -> uploadService.uploadFromFile(file(), "user", null, null));
            assertThat(firstIsWritingTheGraph.await(10, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> uploadService.uploadFromFile(file(), "user", null, null))
                    .isInstanceOf(OntologyUploadInProgressException.class);

            letTheFirstFinish.countDown();
            first.get(30, TimeUnit.SECONDS);
        } finally {
            uploads.shutdownNow();
        }

        verify(jenaTDB2Repository, times(1)).putOntologyModel(anyString(), any(OntModel.class));
        verify(jenaTDB2Repository, never()).deleteGraph(anyString());
        // The vocabulary is free again, and now reported as the duplicate it is.
        assertThatThrownBy(() -> uploadService.uploadFromFile(file(), "user", null, null))
                .isInstanceOf(OntologyAlreadyExistsException.class);
    }

    @Test
    void failedUpload_freesTheVocabularyForARetry() throws Exception {
        doThrow(new IllegalStateException("mapper failed")).doCallRealMethod().when(ontologyMetadataMapper)
                .toDto(any(OntologyMetadataEntity.class));
        assertThatThrownBy(() -> uploadService.uploadFromFile(file(), "user", null, null))
                .isInstanceOf(OntologyUploadMetadataException.class);

        uploadService.uploadFromFile(file(), "user", null, null);

        assertThat(ontologyRepository.findByGraphName(VOCABULARY)).isPresent();
    }

    @Test
    void vocabularyCreatedForTheSameGraphMeanwhile_keepsItsGraphAndIsReportedAsDuplicate() {
        createdMeanwhile("upload-tx", VOCABULARY);

        assertThatThrownBy(() -> uploadService.uploadFromFile(file(), "user", null, null))
                .isInstanceOf(OntologyAlreadyExistsException.class);

        verify(jenaTDB2Repository, never()).deleteGraph(anyString());
    }

    @Test
    void vocabularyCreatedForAnotherGraphWithTheSameSlugMeanwhile_removesTheUploadedGraph() {
        createdMeanwhile("upload-tx", "https://jiný.example/upload-tx");

        assertThatThrownBy(() -> uploadService.uploadFromFile(file(), "user", null, null))
                .isInstanceOf(OntologyAlreadyExistsException.class);

        verify(jenaTDB2Repository).deleteGraph(VOCABULARY);
    }

    /** Another writer commits a vocabulary row after this upload's existence check, during the graph write. */
    private void createdMeanwhile(String slug, String graphName) {
        doAnswer(invocation -> {
            txTemplate.executeWithoutResult(tx -> {
                OntologyMetadataEntity other = new OntologyMetadataEntity();
                other.setSlug(slug);
                other.setGraphName(graphName);
                other.setUserId("other");
                other.setIsPublished(false);
                ontologyRepository.saveAndFlush(other);
            });
            return null;
        }).when(jenaTDB2Repository).putOntologyModel(anyString(), any(OntModel.class));
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "slovnik.ttl", "text/turtle", TURTLE.getBytes(StandardCharsets.UTF_8));
    }

    /** Polls for a value the validation thread produces; {@code null} if it never arrives. */
    private static <T> T await(Supplier<T> value) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            T current = value.get();
            if (current != null) {
                return current;
            }
            Thread.sleep(50);
        }
        return null;
    }

    @TestConfiguration
    static class Beans {

        @Bean JenaTDB2Repository jenaTDB2Repository() {
            return mock(JenaTDB2Repository.class);
        }

        @Bean ValidationClient validationClient() {
            return mock(ValidationClient.class);
        }

        @Bean PublishedResourceUtil publishedResourceUtil() {
            return mock(PublishedResourceUtil.class);
        }

        @Bean OntologyMetadataMapper ontologyMetadataMapper() {
            return spy(new OntologyMetadataMapperImpl());
        }

        @Bean OntologyDetailExtractor ontologyDetailExtractor(ConceptMetadataRepository conceptRepository) {
            return new OntologyDetailExtractor(conceptRepository);
        }

        @Bean NkdLinkDetector nkdLinkDetector() {
            return new NkdLinkDetector();
        }
    }
}