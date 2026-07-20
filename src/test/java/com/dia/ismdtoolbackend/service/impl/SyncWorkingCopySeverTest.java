package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.GetConceptDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.exception.OntologyValidationException;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.outbox.OutboxConfig;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.utility.published.WorkingCopySyncFields;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sever decision in {@code syncWorkingCopy}: accepting every deviating syncable field keeps the
 * concept a working copy; accepting only some of them severs it to a draft.
 *
 * <p>The sever is irreversible through the API — {@code is_published} only ever flips to false here —
 * so both directions are pinned. A real {@link WorkingCopySyncFields} is used rather than a mock so
 * the arithmetic runs against the true field vocabulary.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SyncWorkingCopySeverTest {

    private static final Long CONCEPT_ID = 7L;
    private static final String SLUG = "test-slovnik-obec";
    private static final String IRI = "https://slovník.gov.cz/test-slovnik/pojem/obec";

    @Mock
    private ConceptMetadataRepository conceptMetadataRepository;
    @Mock
    private NkdSparqlClient nkdSparqlClient;
    @Mock
    private OutboxConfig outboxConfig;
    @Mock
    private JenaTDB2Repository jenaTDB2Repository;

    private ConceptServiceImpl service;
    private ConceptMetadataEntity metadata;

    @BeforeEach
    void setUp() {
        metadata = new ConceptMetadataEntity();
        metadata.setId(CONCEPT_ID);
        metadata.setSlug(SLUG);
        metadata.setConceptIri(IRI);
        metadata.setConceptType(ConceptType.TRIDA);
        metadata.setGraphName("https://slovník.gov.cz/test-slovnik");
        metadata.setIsPublished(true);

        // Field injection rather than positional constructor args: ConceptServiceImpl has 20
        // @RequiredArgsConstructor dependencies and this test needs only four of them.
        service = spy(newServiceWith(Map.of(
                "conceptMetadataRepository", conceptMetadataRepository,
                "nkdSparqlClient", nkdSparqlClient,
                "outboxConfig", outboxConfig,
                "jenaTDB2Repository", jenaTDB2Repository,
                "syncFields", new WorkingCopySyncFields(),
                "deviationComparator", new ConceptDeviationComparator())));

        // The data-classification carry-through reads the concept's current RDF; an empty graph means
        // "nothing recorded", which is a valid state and keeps these tests focused on the sever.
        when(jenaTDB2Repository.fetchGraph(anyString()))
                .thenReturn(ModelFactory.createDefaultModel());

        when(outboxConfig.isEnabled()).thenReturn(true);
        // outbox enabled → the service takes the locking read; the post-edit re-read is unlocked.
        when(conceptMetadataRepository.findWithLockById(CONCEPT_ID)).thenReturn(Optional.of(metadata));
        when(conceptMetadataRepository.findById(CONCEPT_ID)).thenReturn(Optional.of(metadata));
        when(nkdSparqlClient.fetchPublishedConcept(anyString()))
                .thenReturn(Optional.of(OntologyDetailModel.ConceptDetailModel.builder()
                        .name(Map.of("cs", "Publikovaná obec"))
                        .description(Map.of("cs", "Publikovaný popis"))
                        .build()));
        doReturn(null).when(service).editConcept(anyLong(), any());
    }

    /** Builds the service with only the dependencies these tests exercise; the rest stay null. */
    private static ConceptServiceImpl newServiceWith(Map<String, Object> deps) {
        try {
            Constructor<?> ctor = ConceptServiceImpl.class.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            ConceptServiceImpl instance =
                    (ConceptServiceImpl) ctor.newInstance(new Object[ctor.getParameterCount()]);
            for (Map.Entry<String, Object> dep : deps.entrySet()) {
                Field f = ConceptServiceImpl.class.getDeclaredField(dep.getKey());
                f.setAccessible(true);
                f.set(instance, dep.getValue());
            }
            return instance;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not build ConceptServiceImpl for test", e);
        }
    }

    /**
     * Sets up a local concept that differs from its NKD twin on exactly {@code fields}.
     *
     * <p>Both sides are real {@code ConceptDetailModel}s run through the real
     * {@link ConceptDeviationComparator} — the deviation is never hand-built. A synthetic deviation
     * would not reproduce what the comparator actually emits, which is how the empty-vs-null defect
     * stayed invisible to the suite.
     */
    private void deviatingOn(String... fields) {
        Set<String> differing = Set.of(fields);
        OntologyDetailModel.ConceptDetailModel local = detail(
                differing.contains("název") ? "Místní obec" : "Obec",
                differing.contains("popis") ? "Místní popis" : "Popis",
                differing.contains("definice") ? "Místní definice" : "Definice");
        OntologyDetailModel.ConceptDetailModel published = detail("Obec", "Popis", "Definice");

        GetConceptDto dto = new GetConceptDto();
        dto.setConceptDetail(local);
        doReturn(dto).when(service).getConceptDetail(SLUG);
        when(nkdSparqlClient.fetchPublishedConcept(anyString())).thenReturn(Optional.of(published));
    }

    private static OntologyDetailModel.ConceptDetailModel detail(String name, String description,
                                                                 String definition) {
        return OntologyDetailModel.ConceptDetailModel.builder()
                .name(Map.of("cs", name))
                .description(Map.of("cs", description))
                .definition(Map.of("cs", definition))
                .build();
    }

    /** A local concept identical to its twin — nothing to sync. */
    private void noDeviation() {
        GetConceptDto dto = new GetConceptDto();
        dto.setConceptDetail(detail("Obec", "Popis", "Definice"));
        doReturn(dto).when(service).getConceptDetail(SLUG);
        when(nkdSparqlClient.fetchPublishedConcept(anyString()))
                .thenReturn(Optional.of(detail("Obec", "Popis", "Definice")));
    }

    @Test
    void acceptingEveryDeviatingField_keepsItAWorkingCopy() {
        deviatingOn("název", "popis");

        service.syncWorkingCopy(CONCEPT_ID, List.of("název", "popis"));

        verify(conceptMetadataRepository, never()).save(any());
        assertTrue(metadata.getIsPublished(), "accepting everything must not sever");
    }

    @Test
    void acceptingOnlySomeDeviatingFields_severs() {
        deviatingOn("název", "popis");

        service.syncWorkingCopy(CONCEPT_ID, List.of("název"));

        ArgumentCaptor<ConceptMetadataEntity> saved = ArgumentCaptor.forClass(ConceptMetadataEntity.class);
        verify(conceptMetadataRepository).save(saved.capture());
        assertEquals(Boolean.FALSE, saved.getValue().getIsPublished(),
                "accepting a strict subset must sever the working copy");
    }

    /** The boundary: one deviating field, accepted. Sizes are equal, so this must NOT sever. */
    @Test
    void singleDeviatingField_accepted_doesNotSever() {
        deviatingOn("definice");

        service.syncWorkingCopy(CONCEPT_ID, List.of("definice"));

        verify(conceptMetadataRepository, never()).save(any());
    }

    /** Duplicates must not inflate the accepted count into a false "accepted everything". */
    @Test
    void duplicateAcceptedKeys_stillSever_whenTheyCoverOnlyPartOfTheDeviation() {
        deviatingOn("název", "popis");

        service.syncWorkingCopy(CONCEPT_ID, List.of("název", "název"));

        ArgumentCaptor<ConceptMetadataEntity> saved = ArgumentCaptor.forClass(ConceptMetadataEntity.class);
        verify(conceptMetadataRepository).save(saved.capture());
        assertEquals(Boolean.FALSE, saved.getValue().getIsPublished(),
                "two copies of one key cover one of two deviating fields — still a strict subset");
    }

    @Test
    void notAWorkingCopy_isRejected() {
        metadata.setIsPublished(false);

        OntologyValidationException e = assertThrows(OntologyValidationException.class,
                () -> service.syncWorkingCopy(CONCEPT_ID, List.of("název")));

        assertTrue(e.getMessage().contains("není pracovní kopií"));
        verify(conceptMetadataRepository, never()).save(any());
    }

    @Test
    void noDeviation_isRejected() {
        noDeviation();

        OntologyValidationException e = assertThrows(OntologyValidationException.class,
                () -> service.syncWorkingCopy(CONCEPT_ID, List.of("název")));

        assertTrue(e.getMessage().contains("neliší"));
    }

    @Test
    void nonSyncableKey_isRejected_andNothingIsSevered() {
        deviatingOn("název");

        assertThrows(OntologyValidationException.class,
                () -> service.syncWorkingCopy(CONCEPT_ID, List.of(WorkingCopySyncFields.TYPE_KEY)));

        verify(conceptMetadataRepository, never()).save(any());
    }

    @Test
    void unknownKey_isRejected_andNothingIsSevered() {
        deviatingOn("název");

        assertThrows(OntologyValidationException.class,
                () -> service.syncWorkingCopy(CONCEPT_ID, List.of("neexistující-pole")));

        verify(conceptMetadataRepository, never()).save(any());
    }

    @Test
    void nkdConceptGoneAtApplyTime_isRejected_andNothingIsSevered() {
        deviatingOn("název");
        when(nkdSparqlClient.fetchPublishedConcept(anyString())).thenReturn(Optional.empty());

        assertThrows(OntologyValidationException.class,
                () -> service.syncWorkingCopy(CONCEPT_ID, List.of("název")));

        verify(conceptMetadataRepository, never()).save(any());
    }
}