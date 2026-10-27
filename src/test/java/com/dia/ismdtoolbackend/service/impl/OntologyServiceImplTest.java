package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.GetOntologyDto;
import com.dia.ismdtoolbackend.controller.dto.MinimalConceptDto;
import com.dia.ismdtoolbackend.controller.dto.OntologyCreateWithConceptsRequestDto;
import com.dia.ismdtoolbackend.controller.dto.OntologyCreateWithConceptsResponseDto;
import com.dia.ismdtoolbackend.controller.dto.OntologyIriCheckRequestDto;
import com.dia.ismdtoolbackend.controller.dto.OntologyIriCheckResponseDto;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.models.OntologyCreateModel;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.OntologyEditModel;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.GRAPH;
import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.ONTOLOGY_ID;
import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.SLUG;
import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.USER_ID;
import static com.dia.ismdtoolbackend.service.impl.OntologyTestData.createModel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The orchestrator: each call reaches its delegate unchanged, writes evict the concept caches inside a
 * transaction, and reads run read-only. Delegate behaviour lives in the delegates' own tests.
 */
@ExtendWith(MockitoExtension.class)
class OntologyServiceImplTest {

    @Mock private OntologyCreateOperation createOperation;
    @Mock private OntologyEditOperation editOperation;
    @Mock private OntologyDeleteOperation deleteOperation;
    @Mock private OntologyDetailReader detailReader;
    @Mock private OntologyMetadataReader metadataReader;
    @Mock private OntologyTurtleExport turtleExport;

    private OntologyServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OntologyServiceImpl(createOperation, editOperation, deleteOperation, detailReader,
                metadataReader, turtleExport);
    }

    @Nested
    class Routing {

        @Test
        void writes() {
            OntologyCreateModel create = createModel();
            OntologyMetadataModel created = new OntologyMetadataModel();
            when(createOperation.createOntology(create, USER_ID)).thenReturn(created);
            OntologyCreateWithConceptsRequestDto withConcepts =
                    new OntologyCreateWithConceptsRequestDto(create, List.of(), List.of(), List.of());
            OntologyCreateWithConceptsResponseDto createdWithConcepts =
                    new OntologyCreateWithConceptsResponseDto(created, Map.of());
            when(createOperation.createWithConcepts(withConcepts, USER_ID)).thenReturn(createdWithConcepts);
            OntologyEditModel edit = new OntologyEditModel();
            OntologyMetadataModel edited = new OntologyMetadataModel();
            when(editOperation.editOntology(ONTOLOGY_ID, edit)).thenReturn(edited);

            assertThat(service.createOntology(create, USER_ID)).isSameAs(created);
            assertThat(service.createWithConcepts(withConcepts, USER_ID)).isSameAs(createdWithConcepts);
            assertThat(service.editOntology(ONTOLOGY_ID, edit)).isSameAs(edited);
            service.deleteOntology(ONTOLOGY_ID);

            verify(deleteOperation).deleteOntology(ONTOLOGY_ID);
        }

        @Test
        void detailReads() {
            OntologyDetailModel detail = OntologyDetailModel.builder().iri(GRAPH).build();
            GetOntologyDto dto = new GetOntologyDto();
            List<MinimalConceptDto> concepts = List.of(MinimalConceptDto.builder().iri(GRAPH + "/pojem/a").build());
            when(detailReader.getOntologyDetail(SLUG)).thenReturn(detail);
            when(detailReader.getOntologyDetailModel(SLUG)).thenReturn(dto);
            when(detailReader.getConceptsByIri(GRAPH, SearchSource.NKD)).thenReturn(concepts);

            assertThat(service.getOntologyDetail(SLUG)).isSameAs(detail);
            assertThat(service.getOntologyDetailModel(SLUG)).isSameAs(dto);
            assertThat(service.getConceptsByIri(GRAPH, SearchSource.NKD)).isSameAs(concepts);
        }

        @Test
        void metadataReads() {
            OntologyMetadataModel byId = new OntologyMetadataModel();
            OntologyMetadataModel bySlug = new OntologyMetadataModel();
            List<OntologyMetadataModel> all = List.of(byId);
            List<OntologyMetadataModel> bySlugs = List.of(bySlug);
            when(metadataReader.getOntologyMetadata(ONTOLOGY_ID)).thenReturn(byId);
            when(metadataReader.getOntologyMetadataBySlug(SLUG)).thenReturn(bySlug);
            when(metadataReader.getAll(USER_ID, true)).thenReturn(all);
            when(metadataReader.getBySlugs(List.of(SLUG))).thenReturn(bySlugs);

            assertThat(service.getOntologyMetadata(ONTOLOGY_ID)).isSameAs(byId);
            assertThat(service.getOntologyMetadataBySlug(SLUG)).isSameAs(bySlug);
            assertThat(service.getAll(USER_ID, true)).isSameAs(all);
            assertThat(service.getBySlugs(List.of(SLUG))).isSameAs(bySlugs);
        }

        @Test
        void iriCheckAndExport() {
            OntologyIriCheckRequestDto request = new OntologyIriCheckRequestDto("http://example.org/", null);
            OntologyIriCheckResponseDto response = new OntologyIriCheckResponseDto(GRAPH, true, true);
            OntologyMetadataModel metadata = new OntologyMetadataModel();
            when(createOperation.checkIri(request)).thenReturn(response);
            when(turtleExport.getTtlContentFromOntology(metadata)).thenReturn("@prefix : <x> .");

            assertThat(service.checkIri(request)).isSameAs(response);
            assertThat(service.getTtlContentFromOntology(metadata)).isEqualTo("@prefix : <x> .");
        }
    }

    @Nested
    class Boundaries {

        @ParameterizedTest
        @ValueSource(strings = {"createOntology", "createWithConcepts", "editOntology", "deleteOntology"})
        void writesRunInATransaction_andEvictTheConceptCaches(String name) {
            Method method = method(name);

            Transactional tx = method.getAnnotation(Transactional.class);
            assertThat(tx).isNotNull();
            assertThat(tx.readOnly()).isFalse();
            CacheEvict evict = method.getAnnotation(CacheEvict.class);
            assertThat(evict).isNotNull();
            assertThat(evict.allEntries()).isTrue();
            assertThat(evict.cacheNames()).containsExactlyInAnyOrder(ReferencedConceptResolutionEngine.CACHE_NAME,
                    WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE);
        }

        @ParameterizedTest
        @ValueSource(strings = {"getOntologyDetail", "getOntologyDetailModel", "getConceptsByIri", "checkIri",
                "getAll", "getBySlugs", "getOntologyMetadata", "getOntologyMetadataBySlug"})
        void readsRunReadOnly_andEvictNothing(String name) {
            Method method = method(name);

            Transactional tx = method.getAnnotation(Transactional.class);
            assertThat(tx).isNotNull();
            assertThat(tx.readOnly()).isTrue();
            assertThat(method.getAnnotation(CacheEvict.class)).isNull();
        }

        private static Method method(String name) {
            return Arrays.stream(OntologyServiceImpl.class.getDeclaredMethods())
                    .filter(m -> m.getName().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no method " + name));
        }
    }
}
