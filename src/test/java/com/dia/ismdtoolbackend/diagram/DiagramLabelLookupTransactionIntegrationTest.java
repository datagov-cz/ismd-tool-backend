package com.dia.ismdtoolbackend.diagram;

import com.dia.ismdtoolbackend.config.JpaAuditingConfig;
import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramSummaryDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.mapper.DiagramMapper;
import com.dia.ismdtoolbackend.outbox.PostgresIntegrationTestBase;
import com.dia.ismdtoolbackend.outbox.TransactionTemplateConfig;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.DiagramPendingEditRepository;
import com.dia.ismdtoolbackend.repository.DiagramRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.OntologyLabelLookup;
import com.dia.ismdtoolbackend.service.impl.DiagramLayoutReconciler;
import com.dia.ismdtoolbackend.service.impl.DiagramMaterializeService;
import com.dia.ismdtoolbackend.service.impl.DiagramServiceBeans;
import com.dia.ismdtoolbackend.service.impl.DiagramServiceImpl;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * The ontology label comes from Fuseki, so the list and rename endpoints resolve it only after their
 * Postgres transaction has ended: no pooled connection is held across the call.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("junit")
@EntityScan(basePackageClasses = {DiagramEntity.class, ConceptMetadataEntity.class})
@EnableJpaRepositories(basePackageClasses = {DiagramRepository.class, ConceptMetadataRepository.class})
@Import({JpaAuditingConfig.class, DiagramLabelLookupTransactionIntegrationTest.Beans.class,
        DiagramServiceBeans.class, TransactionTemplateConfig.class})
// NOT_SUPPORTED: the only transactions open are the ones the service opens itself.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DiagramLabelLookupTransactionIntegrationTest extends PostgresIntegrationTestBase {

    private static final String SLUG = "label-ontology";
    private static final String GRAPH = "https://x/label-ontology";
    private static final Map<String, String> LABEL = Map.of("cs", "Slovník");

    @Autowired private DiagramRepository diagramRepository;
    @Autowired private OntologyMetadataRepository ontologyRepository;
    @Autowired private ConceptMetadataRepository conceptRepository;
    @Autowired private TransactionTemplate txTemplate;
    @Autowired private OntologyLabelLookup ontologyLabelLookup;
    @Autowired private DiagramServiceImpl service;

    private final AtomicReference<Boolean> transactionOpenDuringLookup = new AtomicReference<>();
    private Long diagramId;

    @BeforeEach
    void setUp() {
        reset(ontologyLabelLookup);
        transactionOpenDuringLookup.set(null);
        when(ontologyLabelLookup.labelsByGraph(any())).thenAnswer(invocation -> {
            transactionOpenDuringLookup.set(TransactionSynchronizationManager.isActualTransactionActive());
            return Map.of(GRAPH, LABEL);
        });
        when(ontologyLabelLookup.labelFor(anyString())).thenAnswer(invocation -> {
            transactionOpenDuringLookup.set(TransactionSynchronizationManager.isActualTransactionActive());
            return LABEL;
        });

        txTemplate.executeWithoutResult(tx -> {
            diagramRepository.deleteAllInBatch();
            conceptRepository.deleteAllInBatch();
            ontologyRepository.deleteAllInBatch();
        });
        diagramId = txTemplate.execute(tx -> {
            OntologyMetadataEntity ontology = new OntologyMetadataEntity();
            ontology.setSlug(SLUG);
            ontology.setGraphName(GRAPH);
            ontology.setUserId("u1");
            ontology.setIsPublished(false);
            ontologyRepository.saveAndFlush(ontology);
            DiagramEntity diagram = new DiagramEntity();
            diagram.setOntologyMetadata(ontology);
            diagram.setName("Hlavní diagram");
            return diagramRepository.saveAndFlush(diagram).getId();
        });
    }

    @Test
    void listAll_resolvesLabelsAfterItsTransaction() {
        List<DiagramSummaryDto> result = service.listAll(null);

        assertThat(result).singleElement().extracting(DiagramSummaryDto::ontologyLabel).isEqualTo(LABEL);
        assertThat(transactionOpenDuringLookup.get()).isFalse();
    }

    @Test
    void listForOntology_resolvesLabelsAfterItsTransaction() {
        List<DiagramSummaryDto> result = service.listForOntology(SLUG);

        assertThat(result).singleElement().extracting(DiagramSummaryDto::ontologyLabel).isEqualTo(LABEL);
        assertThat(transactionOpenDuringLookup.get()).isFalse();
    }

    @Test
    void renameDiagram_resolvesTheLabelAfterTheRenameIsCommitted() {
        DiagramSummaryDto result = service.renameDiagram(SLUG, diagramId, "Pohled HR");

        assertThat(result.name()).isEqualTo("Pohled HR");
        assertThat(result.ontologyLabel()).isEqualTo(LABEL);
        assertThat(transactionOpenDuringLookup.get()).isFalse();
    }

    @Test
    void renameDiagram_toTheCurrentName_stillCarriesTheLabel() {
        DiagramSummaryDto result = service.renameDiagram(SLUG, diagramId, "Hlavní diagram");

        assertThat(result.ontologyLabel()).isEqualTo(LABEL);
        assertThat(transactionOpenDuringLookup.get()).isFalse();
    }

    @TestConfiguration
    static class Beans {

        @Bean JenaTDB2Repository jenaTDB2Repository() {
            return mock(JenaTDB2Repository.class);
        }

        @Bean OntologyDetailExtractor ontologyDetailExtractor() {
            return mock(OntologyDetailExtractor.class);
        }

        @Bean DiagramMapper diagramMapper() {
            return new DiagramMapper();
        }

        @Bean DiagramLayoutReconciler diagramLayoutReconciler(DiagramMapper mapper,
                                                              ConceptMetadataRepository conceptRepo,
                                                              DiagramPendingEditRepository pendingEditRepo) {
            return new DiagramLayoutReconciler(mapper, conceptRepo, pendingEditRepo);
        }

        @Bean OntologyLabelLookup ontologyLabelLookup() {
            return mock(OntologyLabelLookup.class);
        }

        @Bean DiagramMaterializeService diagramMaterializeService() {
            return mock(DiagramMaterializeService.class);
        }
    }
}
