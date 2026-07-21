package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import com.dia.ismdtoolbackend.mapper.ConceptMetadataMapper;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import com.dia.ismdtoolbackend.repository.CommentRepository;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.impl.ConceptDeviationComparator;
import com.dia.ismdtoolbackend.service.impl.ConceptServiceImpl;
import com.dia.ismdtoolbackend.service.impl.WorkingCopyDeviationServiceImpl;
import com.dia.ismdtoolbackend.service.rpp.RppSnapshotHolder;
import com.dia.ismdtoolbackend.service.impl.ReferencedConceptsEnricher;
import com.dia.ismdtoolbackend.utility.creator.ConceptCreator;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import com.dia.ismdtoolbackend.utility.editor.ConceptEditor;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.dia.constants.VocabularyConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * End-to-end edit flow with the REAL ConceptEditor (and its real validator and
 * field updaters) wired into ConceptServiceImpl. Only the persistence boundary
 * (repositories, Fuseki, mapper) is mocked. This catches wiring regressions that
 * the layer-isolated tests (which mock the editor) cannot — e.g. a valid edit
 * actually mutating the model, and an invalid edit producing a 400 with no write.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConceptEditFlowIntegrationTest {

    @Mock private ConceptMetadataRepository conceptMetadataRepository;
    @Mock private OntologyMetadataRepository ontologyMetadataRepository;
    @Mock private ConceptMetadataMapper conceptMetadataMapper;
    @Mock private ConceptCreator conceptCreator;
    @Mock private JenaTDB2Repository jenaTDB2Repository;
    @Mock private OntologyDetailExtractor detailExtractor;
    @Mock private CommentRepository commentRepository;
    @Mock private NkdSparqlClient nkdSparqlClient;
    @Mock private ConceptDeviationComparator deviationComparator;
    @Mock private RppSnapshotHolder rppSnapshotHolder;
    @Mock private ReferencedConceptsEnricher referencedConceptsEnricher;
    @Mock private com.dia.ismdtoolbackend.outbox.OutboxConfig outboxConfig;
    @Mock private com.dia.ismdtoolbackend.outbox.OutboxWriter outboxWriter;
    @Mock private com.dia.ismdtoolbackend.outbox.OutboxRelayTrigger outboxRelayTrigger;
    @Mock private com.dia.ismdtoolbackend.service.NkdSnapshotService nkdSnapshotService;

    private ConceptServiceImpl conceptService;

    private static final Long CONCEPT_ID = 1L;
    private static final String CONCEPT_IRI = "http://example.org/pojem/test-concept";
    private static final String GRAPH_NAME = "http://example.org/test-ontology";
    private static final String VALID_ELI =
            "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187";

    @BeforeEach
    void setUp() {
        // Real editor — the whole point of this test.
        ConceptEditor realEditor = new ConceptEditor();
        // Real detector — stateless, pure; on these test models (no external NKD links) allowedTargets is
        // empty so reconcileNkdLinks is a no-op and never calls the (mocked) snapshot service.
        com.dia.ismdtoolbackend.service.snapshot.NkdLinkDetector linkDetector =
                new com.dia.ismdtoolbackend.service.snapshot.NkdLinkDetector();
        conceptService = new ConceptServiceImpl(
                conceptMetadataRepository, ontologyMetadataRepository, conceptMetadataMapper,
                conceptCreator, realEditor, jenaTDB2Repository, detailExtractor,
                commentRepository, nkdSparqlClient, deviationComparator,
                rppSnapshotHolder, referencedConceptsEnricher,
                outboxConfig, outboxWriter, outboxRelayTrigger,
                nkdSnapshotService, linkDetector,
                new com.dia.ismdtoolbackend.utility.published.WorkingCopySyncFields(),
                org.mockito.Mockito.mock(com.dia.ismdtoolbackend.service.snapshot.NkdSnapshotWarmer.class),
                new com.dia.ismdtoolbackend.config.NkdConfig(),
                org.mockito.Mockito.mock(WorkingCopyDeviationServiceImpl.class));

        Model model = ModelFactory.createDefaultModel();
        Resource concept = model.createResource(CONCEPT_IRI);
        concept.addProperty(SKOS.prefLabel, model.createLiteral("Test Concept", "cs"));
        concept.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));

        ConceptMetadataEntity entity = new ConceptMetadataEntity();
        entity.setId(CONCEPT_ID);
        entity.setConceptIri(CONCEPT_IRI);
        entity.setConceptName("Test Concept");
        entity.setConceptType(ConceptType.TRIDA);
        entity.setGraphName(GRAPH_NAME);
        entity.setUserId("user123");

        when(conceptMetadataRepository.findById(CONCEPT_ID)).thenReturn(Optional.of(entity));
        when(jenaTDB2Repository.fetchGraph(GRAPH_NAME)).thenReturn(model);
        when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(conceptMetadataMapper.toDto(any(ConceptMetadataEntity.class))).thenReturn(new ConceptMetadataModel());
    }

    private ClassConceptEditModel editModel() {
        ClassConceptEditModel m = new ClassConceptEditModel();
        m.setConceptType("TRIDA");
        return m;
    }

    @Test
    void validEdit_mutatesModelAndPersists() {
        ClassConceptEditModel m = editModel();
        // change the description (no name change → no IRI rename)
        var desc = new com.dia.ismdtoolbackend.models.DescriptionModel();
        desc.setDescription(Map.of("cs", "Nový popis"));
        m.setDescriptionModel(desc);
        m.setPrivacyProvisions(List.of(VALID_ELI));
        m.setIsPublic(Boolean.FALSE);

        conceptService.editConcept(CONCEPT_ID, m);

        // the real editor mutated the in-memory model, and the service persisted it
        ArgumentCaptor<Model> saved = ArgumentCaptor.forClass(Model.class);
        verify(jenaTDB2Repository).putOntologyModel(eq(GRAPH_NAME), saved.capture());

        Resource concept = saved.getValue().getResource(CONCEPT_IRI);
        Property descProp = saved.getValue().createProperty("http://purl.org/dc/terms/description");
        assertTrue(concept.hasProperty(descProp), "description must be written");
        Property provisionProp = saved.getValue().createProperty(OFN_NAMESPACE_LEGAL + USTANOVENI_NEVEREJNOST);
        assertTrue(concept.hasProperty(provisionProp, saved.getValue().createResource(VALID_ELI)),
                "privacy provision must be written end-to-end");
        verify(conceptMetadataRepository).save(any());
    }

    @Test
    void invalidEdit_rejectedAs400_withNoWrite() {
        ClassConceptEditModel m = editModel();
        m.setExactMatch(List.of("not-a-valid-iri"));   // real validator rejects this

        ConceptValidationException ex = assertThrows(ConceptValidationException.class,
                () -> conceptService.editConcept(CONCEPT_ID, m));

        assertTrue(ex.getMessage().contains("exactMatch"), ex.getMessage());
        // no persistence on rejection — atomic
        verify(jenaTDB2Repository, never()).putOntologyModel(anyString(), any());
        verify(conceptMetadataRepository, never()).save(any());
    }

    @Test
    void nameChangeEdit_renamesIriEndToEnd() {
        ClassConceptEditModel m = editModel();
        NameModel newName = new NameModel();
        newName.setName(Map.of("cs", "Nový název"));
        m.setNameModel(newName);

        conceptService.editConcept(CONCEPT_ID, m);

        ArgumentCaptor<Model> saved = ArgumentCaptor.forClass(Model.class);
        // IRI change path saves via the rename handler; capture whichever save ran
        verify(jenaTDB2Repository, atLeastOnce()).putOntologyModel(anyString(), saved.capture());
        // the old IRI no longer carries the prefLabel (it moved to the new IRI)
        Resource oldConcept = saved.getValue().getResource(CONCEPT_IRI);
        assertFalse(oldConcept.hasProperty(SKOS.prefLabel),
                "after rename, the old IRI must not retain the prefLabel");
    }
}
