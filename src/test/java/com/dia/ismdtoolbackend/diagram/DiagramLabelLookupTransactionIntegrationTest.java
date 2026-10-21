package com.dia.ismdtoolbackend.diagram;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramSummaryDto;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.service.OntologyLabelLookup;
import com.dia.ismdtoolbackend.service.impl.DiagramServiceImpl;
import com.dia.ismdtoolbackend.testsupport.pg.DiagramServiceIT;
import com.dia.ismdtoolbackend.testsupport.pg.PgTestData;
import com.dia.ismdtoolbackend.testsupport.pg.PostgresIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The ontology label comes from Fuseki, so the list and rename endpoints resolve it only after their
 * Postgres transaction has ended: no pooled connection is held across the call.
 */
// NOT_SUPPORTED (via @DiagramServiceIT): the only transactions open are the ones the service opens itself.
@DiagramServiceIT
class DiagramLabelLookupTransactionIntegrationTest extends PostgresIntegrationTestBase {

    private static final String SLUG = "label-ontology";
    private static final String GRAPH = "https://x/label-ontology";
    private static final Map<String, String> LABEL = Map.of("cs", "Slovník");

    @Autowired private OntologyLabelLookup ontologyLabelLookup;
    @Autowired private DiagramServiceImpl service;
    @Autowired private PgTestData data;

    private final AtomicReference<Boolean> transactionOpenDuringLookup = new AtomicReference<>();
    private Long diagramId;

    @BeforeEach
    void setUp() {
        transactionOpenDuringLookup.set(null);
        when(ontologyLabelLookup.labelsByGraph(any())).thenAnswer(invocation -> {
            transactionOpenDuringLookup.set(TransactionSynchronizationManager.isActualTransactionActive());
            return Map.of(GRAPH, LABEL);
        });
        when(ontologyLabelLookup.labelFor(anyString())).thenAnswer(invocation -> {
            transactionOpenDuringLookup.set(TransactionSynchronizationManager.isActualTransactionActive());
            return LABEL;
        });

        OntologyMetadataEntity ontology = data.createOntology(SLUG, GRAPH, "u1", false);
        diagramId = data.createDiagram(ontology, "Hlavní diagram");
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
}
