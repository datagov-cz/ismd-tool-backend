package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptSourceTag;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.concept.ClassConceptModel;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import com.dia.ismdtoolbackend.outbox.OutboxRelayTrigger;
import com.dia.ismdtoolbackend.outbox.OutboxWriter;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.testsupport.fixtures.Texts;
import com.dia.ismdtoolbackend.utility.creator.ConceptCreator;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.dia.ismdtoolbackend.service.impl.ConceptServiceTestData.CONCEPT_ID;
import static com.dia.ismdtoolbackend.service.impl.ConceptServiceTestData.CONCEPT_IRI;
import static com.dia.ismdtoolbackend.service.impl.ConceptServiceTestData.CONCEPT_NAME;
import static com.dia.ismdtoolbackend.service.impl.ConceptServiceTestData.GRAPH_NAME;
import static com.dia.ismdtoolbackend.service.impl.ConceptServiceTestData.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConceptCreateOperationTest {

    private ConceptMetadataRepository conceptMetadataRepository;
    private OntologyMetadataRepository ontologyMetadataRepository;
    private ConceptCreator conceptCreator;
    private JenaTDB2Repository jenaTDB2Repository;
    private OutboxWriter outboxWriter;
    private OutboxRelayTrigger outboxRelayTrigger;
    private ConceptCreateOperation operation;

    @Captor private ArgumentCaptor<Set<Statement>> addedCaptor;
    @Captor private ArgumentCaptor<ConceptMetadataEntity> savedCaptor;

    private ConceptMetadataEntity savedConcept;
    private Model createdModel;
    private Resource createdResource;
    private ClassConceptModel createModel;

    @BeforeEach
    void setUp() {
        ConceptServiceFixture fixture = new ConceptServiceFixture();
        conceptMetadataRepository = fixture.conceptMetadataRepository();
        ontologyMetadataRepository = fixture.ontologyMetadataRepository();
        conceptCreator = fixture.conceptCreator();
        jenaTDB2Repository = fixture.jenaTDB2Repository();
        outboxWriter = fixture.outboxWriter();
        outboxRelayTrigger = fixture.outboxRelayTrigger();
        operation = fixture.buildCreateOperation();

        savedConcept = ConceptServiceTestData.concept();
        createdModel = ModelFactory.createDefaultModel();
        createdResource = createdModel.createResource(CONCEPT_IRI);
        createModel = ConceptServiceTestData.createModel();
    }

    /** The creator builds the concept, its IRI is free and the vocabulary exists. */
    private void givenCreatableConcept() {
        when(conceptCreator.createSingleConcept(createModel)).thenReturn(createdResource);
        when(conceptMetadataRepository.findByConceptIri(CONCEPT_IRI)).thenReturn(Optional.empty());
        when(ontologyMetadataRepository.findByGraphName(GRAPH_NAME))
                .thenReturn(Optional.of(ConceptServiceTestData.ontology()));
    }

    @Nested
    class Success {

        @Test
        void returnsTheSavedRowMapped() {
            givenCreatableConcept();
            when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class))).thenReturn(savedConcept);

            ConceptMetadataModel result = operation.createConcept(createModel, USER_ID);

            assertThat(result.getId()).isEqualTo(CONCEPT_ID);
            assertThat(result.getConceptIri()).isEqualTo(CONCEPT_IRI);
            assertThat(result.getConceptName()).isEqualTo(CONCEPT_NAME);
            assertThat(result.getUser().getUserId()).isEqualTo(USER_ID);
            assertThat(result.getSourceTag()).isEqualTo(ConceptSourceTag.DRAFT);
        }

        @Test
        void savesMetadataBuiltFromTheModel() {
            givenCreatableConcept();
            when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class))).thenReturn(savedConcept);

            operation.createConcept(createModel, USER_ID);

            verify(conceptMetadataRepository).save(savedCaptor.capture());
            ConceptMetadataEntity saved = savedCaptor.getValue();
            assertThat(saved.getConceptName()).isEqualTo(CONCEPT_NAME);
            assertThat(saved.getConceptType()).isEqualTo(ConceptType.TRIDA);
            assertThat(saved.getConceptIri()).isEqualTo(CONCEPT_IRI);
            assertThat(saved.getGraphName()).isEqualTo(GRAPH_NAME);
            assertThat(saved.getUserId()).isEqualTo(USER_ID);
            assertThat(saved.getIsPublished()).isFalse();
        }

        @Test
        void enqueuesTheConceptTriplesAndNudgesWithoutWritingTdb2() {
            createdResource.addProperty(SKOS.prefLabel, "C");
            Statement label = createdResource.getProperty(SKOS.prefLabel);
            givenCreatableConcept();
            when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class))).thenReturn(savedConcept);

            operation.createConcept(createModel, USER_ID);

            verify(outboxWriter).enqueueUpsert(eq(GRAPH_NAME), eq(CONCEPT_IRI), eq(Set.of()), addedCaptor.capture());
            assertThat(addedCaptor.getValue()).containsExactly(label);
            verify(outboxRelayTrigger).nudgeAfterCommit();
            verifyNoInteractions(jenaTDB2Repository);
        }

        @Test
        void acceptsAnEliLegalSource() {
            createModel.setDefiningLegalSource(List.of(
                    "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187"));
            givenCreatableConcept();
            when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class))).thenReturn(savedConcept);

            assertThat(operation.createConcept(createModel, USER_ID)).isNotNull();
            verify(conceptCreator).createSingleConcept(createModel);
        }

        @Test
        void stripsTrailingSlashFromIrisBeforeCreating() {
            createModel.setExactMatch(List.of("https://example.org/pojem/osoba/"));
            when(conceptCreator.createSingleConcept(createModel)).thenReturn(createdResource);
            when(conceptMetadataRepository.findByConceptIri(CONCEPT_IRI)).thenReturn(Optional.of(savedConcept));

            assertThatThrownBy(() -> operation.createConcept(createModel, USER_ID))
                    .isInstanceOf(ConceptValidationException.class);

            assertThat(createModel.getExactMatch()).containsExactly("https://example.org/pojem/osoba");
        }
    }

    @Nested
    class Validation {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = "  ")
        void rejectsAMissingUserId(String userId) {
            assertThatThrownBy(() -> operation.createConcept(createModel, userId))
                    .isInstanceOf(OntologyException.class)
                    .hasMessageContaining("povinné");
            verify(conceptCreator, never()).createSingleConcept(any());
        }

        @Test
        void rejectsAMissingName() {
            createModel.setNameModel(new NameModel());

            assertThatThrownBy(() -> operation.createConcept(createModel, USER_ID))
                    .isInstanceOf(ConceptValidationException.class)
                    .hasMessageContaining("Název pojmu je povinný");
            verify(conceptCreator, never()).createSingleConcept(any());
        }

        @Test
        void rejectsANameWithoutCzech() {
            createModel.setNameModel(Texts.name("en", "name"));

            assertThatThrownBy(() -> operation.createConcept(createModel, USER_ID))
                    .isInstanceOf(ConceptValidationException.class)
                    .hasMessageContaining("českou variantu");
        }

        @Test
        void rejectsADescriptionWithoutCzech() {
            createModel.setDescriptionModel(Texts.description("en", "English only"));

            assertThatThrownBy(() -> operation.createConcept(createModel, USER_ID))
                    .isInstanceOf(ConceptValidationException.class)
                    .hasMessageContaining("Popis pojmu");
        }

        @Test
        void rejectsADefinitionWithoutCzech() {
            createModel.setDefinitionModel(Texts.definition("en", "English only"));

            assertThatThrownBy(() -> operation.createConcept(createModel, USER_ID))
                    .isInstanceOf(ConceptValidationException.class)
                    .hasMessageContaining("Definice pojmu");
        }

        @Test
        void rejectsANonEliLegalSourceAndCreatesNothing() {
            createModel.setDefiningLegalSource(List.of("http://example.org/not-eli"));

            assertThatThrownBy(() -> operation.createConcept(createModel, USER_ID))
                    .isInstanceOf(ConceptValidationException.class)
                    .hasMessageContaining("definingLegalSource")
                    .hasMessageContaining("http://example.org/not-eli");
            verify(conceptCreator, never()).createSingleConcept(any());
            verifyNoInteractions(outboxWriter);
            verify(conceptMetadataRepository, never()).save(any());
        }

        @Test
        void rejectsANonEliPrivacyProvision() {
            createModel.setIsPublic(false);
            createModel.setPrivacyProvisions(List.of("http://example.org/not-eli"));

            assertThatThrownBy(() -> operation.createConcept(createModel, USER_ID))
                    .isInstanceOf(ConceptValidationException.class)
                    .hasMessageContaining("privacyProvisions");
            verify(conceptCreator, never()).createSingleConcept(any());
        }

        @Test
        void rejectsAnIriAnotherConceptHolds() {
            when(conceptCreator.createSingleConcept(createModel)).thenReturn(createdResource);
            when(conceptMetadataRepository.findByConceptIri(CONCEPT_IRI)).thenReturn(Optional.of(savedConcept));

            assertThatThrownBy(() -> operation.createConcept(createModel, USER_ID))
                    .isInstanceOf(ConceptValidationException.class);
            verifyNoInteractions(outboxWriter);
            verify(conceptMetadataRepository, never()).save(any());
        }
    }

    @Nested
    class Failures {

        @Test
        void wrapsACreatorFailure() {
            when(conceptCreator.createSingleConcept(createModel)).thenThrow(new RuntimeException("Creator error"));

            assertThatThrownBy(() -> operation.createConcept(createModel, USER_ID))
                    .isInstanceOf(OntologyException.class)
                    .hasMessageContaining("Nepodařilo se transformovat pojem");
            verifyNoInteractions(outboxWriter);
        }

        @Test
        void reportsASaveFailureWithoutNudging() {
            givenCreatableConcept();
            when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class)))
                    .thenThrow(new RuntimeException("DB error"));

            assertThatThrownBy(() -> operation.createConcept(createModel, USER_ID))
                    .isInstanceOf(OntologyException.class)
                    .hasMessage("Nepodařilo se uložit metadata pojmu.");
            // The outbox row rolls back with the transaction.
            verify(outboxRelayTrigger, never()).nudgeAfterCommit();
            verifyNoInteractions(jenaTDB2Repository);
        }
    }
}
