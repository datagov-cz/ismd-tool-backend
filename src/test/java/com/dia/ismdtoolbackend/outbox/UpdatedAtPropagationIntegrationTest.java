package com.dia.ismdtoolbackend.outbox;

import com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ClassConceptModel;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.impl.ConceptServiceImpl;
import com.dia.ismdtoolbackend.service.impl.MetadataTouchService;
import com.dia.ismdtoolbackend.testsupport.pg.OutboxFlowIT;
import com.dia.ismdtoolbackend.testsupport.pg.OutboxFlowTestBase;
import com.dia.ismdtoolbackend.testsupport.pg.PgTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards {@code updatedAt} propagation against REAL Postgres through the REAL {@link ConceptServiceImpl}.
 *
 * <p>The headline case is the **RDF-only edit**: changing a concept's definition dirties no mapped
 * column, so before {@link MetadataTouchService} the {@code save()} was a silent no-op and neither
 * timestamp moved. The whole suite missed this because it mocks the persistence layer — only a real
 * PG flush can tell "saved" apart from "no UPDATE emitted".
 *
 * <p>No ambient test transaction: the service runs in its own boundary, so each write really
 * commits and the next read sees a committed timestamp.
 */
@OutboxFlowIT
class UpdatedAtPropagationIntegrationTest extends OutboxFlowTestBase {

    private static final String GRAPH = "https://slovnik.gov.cz/g";
    private static final String USER = "user123";

    @Autowired private ConceptMetadataRepository conceptMetadataRepository;
    @Autowired private OntologyMetadataRepository ontologyMetadataRepository;
    @Autowired private ConceptServiceImpl service;
    @Autowired private PgTestData data;

    @BeforeEach
    void setUp() {
        data.createOntology("g-ontology", GRAPH, USER, false);
    }

    private ClassConceptModel classModel(String name) {
        ClassConceptModel m = new ClassConceptModel();
        m.setConceptType("třída");
        m.setType("objekt");
        m.setOntologyGraphName(GRAPH);
        m.setNamespace(GRAPH);
        NameModel nm = new NameModel();
        Map<String, String> names = new HashMap<>();
        names.put("cs", name);
        nm.setName(names);
        m.setNameModel(nm);
        return m;
    }

    private LocalDateTime ontologyUpdatedAt() {
        return ontologyMetadataRepository.findByGraphName(GRAPH).orElseThrow().getUpdatedAt();
    }

    private LocalDateTime conceptUpdatedAt(Long id) {
        return conceptMetadataRepository.findById(id).orElseThrow().getUpdatedAt();
    }

    private void pause() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** An edit that changes ONLY RDF (definition) must still bump both timestamps. */
    @Test
    void rdfOnlyEdit_bumpsConceptAndOntologyUpdatedAt() {
        service.createConcept(classModel("Alpha"), USER);
        Long id = conceptMetadataRepository.findAll().get(0).getId();

        String iriBefore = conceptMetadataRepository.findById(id).orElseThrow().getConceptIri();
        LocalDateTime conceptBefore = conceptUpdatedAt(id);
        LocalDateTime ontologyBefore = ontologyUpdatedAt();
        pause();

        // Same name (no IRI/name change) + a new definition → RDF changes, mapped columns do not.
        ClassConceptEditModel edit = new ClassConceptEditModel();
        edit.setConceptType("třída");
        NameModel nm = new NameModel();
        Map<String, String> names = new HashMap<>();
        names.put("cs", "Alpha");
        nm.setName(names);
        edit.setNameModel(nm);
        Map<String, String> definition = new HashMap<>();
        definition.put("cs", "Nová definice pojmu.");
        com.dia.ismdtoolbackend.models.concept.DefinitionModel dm =
                new com.dia.ismdtoolbackend.models.concept.DefinitionModel();
        dm.setDefinition(definition);
        edit.setDefinitionModel(dm);

        service.editConcept(id, edit);

        assertThat(conceptMetadataRepository.findById(id).orElseThrow().getConceptIri())
                .as("precondition: this edit is RDF-only — the IRI must not change")
                .isEqualTo(iriBefore);
        assertThat(conceptUpdatedAt(id))
                .as("RDF-only edit bumps the concept's updatedAt")
                .isAfter(conceptBefore);
        assertThat(ontologyUpdatedAt())
                .as("concept edit propagates to the parent ontology's updatedAt")
                .isAfter(ontologyBefore);
    }

    /** Creating a concept modifies the vocabulary, so the parent ontology must move. */
    @Test
    void createConcept_bumpsOntologyUpdatedAt() {
        LocalDateTime before = ontologyUpdatedAt();
        pause();

        service.createConcept(classModel("Alpha"), USER);

        assertThat(ontologyUpdatedAt()).isAfter(before);
    }

    /** Deleting a concept likewise modifies the vocabulary. */
    @Test
    void deleteConcept_bumpsOntologyUpdatedAt() {
        service.createConcept(classModel("Alpha"), USER);
        Long id = conceptMetadataRepository.findAll().get(0).getId();

        LocalDateTime before = ontologyUpdatedAt();
        pause();

        service.deleteConcept(id);

        assertThat(conceptMetadataRepository.findById(id)).isEmpty();
        assertThat(ontologyUpdatedAt())
                .as("deleting a concept bumps the parent ontology's updatedAt")
                .isAfter(before);
    }
}
