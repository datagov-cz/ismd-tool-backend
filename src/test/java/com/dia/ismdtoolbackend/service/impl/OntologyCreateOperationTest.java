package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.OntologyIriCheckRequestDto;
import com.dia.ismdtoolbackend.controller.dto.OntologyIriCheckResponseDto;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.exception.OntologyCreationConflictException;
import com.dia.ismdtoolbackend.exception.OntologyValidationException;
import com.dia.ismdtoolbackend.models.DescriptionModel;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.OntologyCreateModel;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.outbox.OutboxWriter;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.Model;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.GRAPH;
import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.NAMESPACE;
import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.ONTOLOGY_ID;
import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.SLUG;
import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.USER_ID;
import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.createModel;
import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.ontologyEntity;
import static com.dia.ismdtoolbackend.testsupport.fixtures.Texts.description;
import static com.dia.ismdtoolbackend.testsupport.fixtures.Texts.name;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Creating a vocabulary and checking whether its IRI is free. */
@ExtendWith(MockitoExtension.class)
class OntologyCreateOperationTest {

    private OntologyMetadataRepository ontologyMetadataRepository;
    private ConceptMetadataRepository conceptMetadataRepository;
    private JenaTDB2Repository jenaTDB2Repository;
    private OutboxWriter outboxWriter;

    private OntologyCreateOperation operation;

    @BeforeEach
    void setUp() {
        OntologyServiceFixture fixture = new OntologyServiceFixture();
        ontologyMetadataRepository = fixture.ontologyMetadataRepository();
        conceptMetadataRepository = fixture.conceptMetadataRepository();
        jenaTDB2Repository = fixture.jenaTDB2Repository();
        outboxWriter = fixture.outboxWriter();
        operation = fixture.createOperation();
    }

    private OntologyIriCheckResponseDto checkIri(OntologyCreateModel model) {
        return operation.checkIri(new OntologyIriCheckRequestDto(model.getNamespace(), model.getNameModel()));
    }

    @Nested
    class CheckIri {

        @Test
        void freeIri_isAvailable_andNothingIsWritten() {
            OntologyIriCheckResponseDto result = checkIri(createModel());

            assertThat(result.iri()).isEqualTo(GRAPH);
            assertThat(result.valid()).isTrue();
            assertThat(result.available()).isTrue();
            verify(ontologyMetadataRepository).findByGraphName(GRAPH);
            verify(ontologyMetadataRepository).findBySlug(SLUG);
            verifyNoMoreInteractions(ontologyMetadataRepository);
            verifyNoInteractions(jenaTDB2Repository, conceptMetadataRepository, outboxWriter);
        }

        @Test
        void takenIri_isUnavailable_andTheExistingRowIsLeftAlone() {
            when(ontologyMetadataRepository.findByGraphName(GRAPH)).thenReturn(Optional.of(ontologyEntity()));

            OntologyIriCheckResponseDto result = checkIri(createModel());

            assertThat(result.iri()).isEqualTo(GRAPH);
            assertThat(result.valid()).isTrue();
            assertThat(result.available()).isFalse();
            verify(ontologyMetadataRepository).findByGraphName(GRAPH);
            verifyNoMoreInteractions(ontologyMetadataRepository);
            verifyNoInteractions(jenaTDB2Repository, outboxWriter);
        }

        @Test
        void invalidNamespace_isInvalid_withoutQueryingTheDatabase() {
            OntologyIriCheckResponseDto result = operation.checkIri(
                    new OntologyIriCheckRequestDto("invalid iri with spaces", createModel().getNameModel()));

            assertThat(result.valid()).isFalse();
            assertThat(result.available()).isFalse();
            verifyNoInteractions(ontologyMetadataRepository, jenaTDB2Repository);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        void czechNameIsRequired(String czechName) {
            Map<String, String> names = new HashMap<>();
            names.put("en", "Road traffic");
            if (czechName != null) {
                names.put("cs", czechName);
            }

            assertThatThrownBy(() -> operation.checkIri(new OntologyIriCheckRequestDto(NAMESPACE, name(names))))
                    .isInstanceOf(OntologyValidationException.class);
            verifyNoInteractions(ontologyMetadataRepository, jenaTDB2Repository);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {NAMESPACE, "https://slovník.gov.cz/datový/"})
        void checkedIri_isTheIriCreateWrites(String namespace) {
            OntologyCreateModel model = createModel();
            model.setNamespace(namespace);
            model.setNameModel(name(Map.of("cs", "Silniční provoz", "en", "Road traffic")));
            OntologyIriCheckResponseDto result = checkIri(model);
            assertThat(result.valid()).isTrue();
            clearInvocations(ontologyMetadataRepository);
            when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(ontologyEntity());

            operation.createOntology(model, USER_ID);

            verify(ontologyMetadataRepository).findByGraphName(result.iri());
            verify(outboxWriter).enqueueCreateGraph(eq(result.iri()), any(Model.class));
        }

        @Test
        void sameSlugInAnotherNamespace_isUnavailable_andCreateConflicts() {
            OntologyMetadataEntity existing = ontologyEntity(2L, "https://other.example/" + SLUG);
            when(ontologyMetadataRepository.findBySlug(SLUG)).thenReturn(Optional.of(existing));

            assertThat(checkIri(createModel()).available()).isFalse();
            assertThatThrownBy(() -> operation.createOntology(createModel(), USER_ID))
                    .isInstanceOf(OntologyCreationConflictException.class);
            verify(ontologyMetadataRepository, never()).save(any());
        }
    }

    @Nested
    class Create {

        @Test
        void newIri_savesMetadataAndQueuesTheGraph() {
            when(ontologyMetadataRepository.findByGraphName(anyString())).thenReturn(Optional.empty());
            when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(ontologyEntity());

            OntologyMetadataModel result = operation.createOntology(createModel(), USER_ID);

            assertThat(result.getId()).isEqualTo(ONTOLOGY_ID);
            assertThat(result.getGraphName()).isEqualTo(GRAPH);
            assertThat(result.getUser().getUserId()).isEqualTo(USER_ID);
            verify(outboxWriter).enqueueCreateGraph(eq(GRAPH), any(Model.class));
            verify(ontologyMetadataRepository).save(any(OntologyMetadataEntity.class));
        }

        @Test
        void existingIri_returnsTheExistingVocabularyUnchanged() {
            when(ontologyMetadataRepository.findByGraphName(anyString())).thenReturn(Optional.of(ontologyEntity()));

            OntologyMetadataModel result = operation.createOntology(createModel(), USER_ID);

            assertThat(result.getId()).isEqualTo(ONTOLOGY_ID);
            assertThat(result.getSlug()).isEqualTo(SLUG);
            verify(ontologyMetadataRepository, never()).save(any(OntologyMetadataEntity.class));
            verifyNoInteractions(outboxWriter);
        }

        @Test
        void nullRequest_throws() {
            assertThatThrownBy(() -> operation.createOntology(null, USER_ID))
                    .isInstanceOf(OntologyException.class)
                    .hasMessageContaining("prázdná");
        }

        @Test
        void invalidNamespace_throws() {
            OntologyCreateModel model = createModel();
            model.setNamespace("invalid iri with spaces");

            assertThatThrownBy(() -> operation.createOntology(model, USER_ID))
                    .isInstanceOf(OntologyException.class);
            verifyNoInteractions(outboxWriter);
        }

        @Test
        void failedMetadataSave_queuesNoGraph() {
            when(ontologyMetadataRepository.findByGraphName(anyString())).thenReturn(Optional.empty());
            when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class)))
                    .thenThrow(new RuntimeException("DB error"));

            assertThatThrownBy(() -> operation.createOntology(createModel(), USER_ID))
                    .isInstanceOf(OntologyException.class)
                    .hasMessageContaining("Nepodařilo se uložit metadata");
            verifyNoInteractions(jenaTDB2Repository, outboxWriter);
        }
    }

    @Nested
    class Validation {

        static Stream<Arguments> invalidNames() {
            return Stream.of(
                    Arguments.of(new NameModel(), "Název slovníku je povinný"),
                    Arguments.of(name("en", SLUG), "českou variantu"),
                    Arguments.of(name("cs", "   "), "českou variantu"));
        }

        @ParameterizedTest
        @MethodSource("invalidNames")
        void nameWithoutCzechVariant_isRejectedBeforeAnyWrite(NameModel nameModel, String message) {
            OntologyCreateModel model = createModel();
            model.setNameModel(nameModel);

            assertThatThrownBy(() -> operation.createOntology(model, USER_ID))
                    .isInstanceOf(OntologyValidationException.class)
                    .hasMessageContaining(message);
            verifyNoInteractions(ontologyMetadataRepository, outboxWriter);
        }

        @Test
        void descriptionWithoutCzechVariant_isRejected() {
            OntologyCreateModel model = createModel();
            model.setDescriptionModel(description("en", "English only description"));

            assertThatThrownBy(() -> operation.createOntology(model, USER_ID))
                    .isInstanceOf(OntologyValidationException.class)
                    .hasMessageContaining("Popis slovníku");
            verifyNoInteractions(ontologyMetadataRepository, outboxWriter);
        }

        @Test
        void emptyDescription_isAccepted() {
            OntologyCreateModel model = createModel();
            model.setDescriptionModel(new DescriptionModel());
            when(ontologyMetadataRepository.findByGraphName(anyString())).thenReturn(Optional.empty());
            when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class))).thenReturn(ontologyEntity());

            assertThat(operation.createOntology(model, USER_ID)).isNotNull();
        }
    }
}
