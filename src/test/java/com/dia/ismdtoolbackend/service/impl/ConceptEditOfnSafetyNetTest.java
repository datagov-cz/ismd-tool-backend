package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.models.concept.ConceptCreateModel;
import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.DigitalObjectModel;
import com.dia.ismdtoolbackend.outbox.OutboxWriter;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.testsupport.fixtures.ConceptModels;
import com.dia.ismdtoolbackend.testsupport.fixtures.EditModels;
import com.dia.ismdtoolbackend.testsupport.fixtures.Texts;
import com.dia.ismdtoolbackend.utility.creator.ConceptCreator;
import com.dia.ismdtoolbackend.utility.editor.ConceptEditor;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every edit the form can make passes the OFN write gate: a concept written by the real {@link ConceptCreator},
 * edited by the real {@link ConceptEditor} and checked by the real gate, so an updater regression that breaks
 * T1 or T2 fails here instead of in production. Each edit runs once in place and once with a rename.
 */
@ExtendWith(MockitoExtension.class)
class ConceptEditOfnSafetyNetTest {

    private static final Long CONCEPT_ID = 7L;
    private static final String GRAPH = "https://slovník.gov.cz/test-brana-uprav";
    private static final String POJEM = GRAPH + "/pojem/";
    private static final String ELI = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/";
    private static final String CODE_LIST = "https://data.mvcr.gov.cz/zdroj/číselníky/typy-turistických-cílů";
    private static final String DATASET = "https://data.gov.cz/zdroj/datové-sady/test-dataset";

    private ConceptMetadataRepository conceptMetadataRepository;
    private JenaTDB2Repository jenaTDB2Repository;
    private OutboxWriter outboxWriter;
    private ConceptEditOperation operation;

    @BeforeEach
    void setUp() {
        ConceptServiceFixture fixture = new ConceptServiceFixture().conceptEditor(new ConceptEditor());
        conceptMetadataRepository = fixture.conceptMetadataRepository();
        jenaTDB2Repository = fixture.jenaTDB2Repository();
        outboxWriter = fixture.outboxWriter();
        operation = fixture.buildEditOperation();
    }

    /** Fields every concept type shares, set to non-default values. */
    private static void commonFields(ConceptEditModel m) {
        m.setDescriptionModel(Texts.description("cs", "Nový popis"));
        m.setDefinitionModel(Texts.definition(Map.of("cs", "Nová definice", "en", "New definition")));
        m.setAltNameModel(Texts.altName("cs", "Synonymum", "Jiný název"));
        m.setDefiningLegalSource(List.of(ELI + "2021/12"));
        m.setRelatedLegalSource(List.of(ELI + "2000/361"));
        m.setDefiningNonLegalSource(List.of(new DigitalObjectModel("Metodika", "Popis", "https://example.org/doc")));
        m.setRelatedNonLegalSource(List.of(new DigitalObjectModel(null, null, "https://example.org/doc2")));
        m.setExactMatch(List.of("https://example.org/exact"));
    }

    static Stream<Arguments> formEdits() {
        return Stream.of(
                Arguments.of("třída: every field, non-public subjekt", ConceptModels.trida("Osoba"), ConceptType.TRIDA,
                        EditModels.trida(m -> {
                            commonFields(m);
                            m.setType("subjekt");
                            m.setIsPublic(false);
                            m.setPrivacyProvisions(List.of(ELI + "2020/50"));
                            m.setIsInPPDF(true);
                            m.setSharingMethod(List.of("nesdílené"));
                            m.setAcquisitionMethod("vlastní");
                            m.setContentType("provozní");
                            m.setAgendaCode("A123");
                            m.setAgendaSystemCode("456");
                            m.setBroaderConcept(List.of(POJEM + "bytost"));
                        })),
                Arguments.of("třída: non-public to public objekt with a code list",
                        ConceptModels.trida("Turistický cíl", m -> {
                            m.setIsPublic(false);
                            m.setPrivacyProvisions(List.of(ELI + "2020/50"));
                        }), ConceptType.TRIDA,
                        EditModels.trida(m -> {
                            m.setType("objekt");
                            m.setIsPublic(true);
                            m.setCodeListIri(CODE_LIST);
                            m.setCodeListDataset(DATASET);
                        })),
                Arguments.of("vlastnost: datatype, domain, super-property, non-public",
                        ConceptModels.vlastnost("Výška"), ConceptType.VLASTNOST,
                        EditModels.vlastnost(m -> {
                            commonFields(m);
                            m.setDataType("double");
                            m.setDomain(POJEM + "vozidlo");
                            m.setSuperProperty(List.of(POJEM + "rozměr"));
                            m.setIsPublic(false);
                            m.setPrivacyProvisions(List.of(ELI + "2020/50"));
                            m.setIsInPPDF(true);
                            m.setAgendaCode("A123");
                        })),
                Arguments.of("vlastnost: datatype cleared", ConceptModels.vlastnost("Poznámka", m -> m.setDataType("string")),
                        ConceptType.VLASTNOST, EditModels.vlastnost(m -> m.setDataType(""))),
                Arguments.of("vztah: domain, range, super-relation, public",
                        ConceptModels.vztah("Vlastní"), ConceptType.VZTAH,
                        EditModels.vztah(m -> {
                            commonFields(m);
                            m.setDomain(POJEM + "osoba");
                            m.setRange(POJEM + "vozidlo");
                            m.setSuperRelation(List.of(POJEM + "má"));
                            m.setIsPublic(true);
                            m.setSharingMethod(List.of("nesdílené"));
                        })));
    }

    static Stream<Arguments> formEditsWithAndWithoutRename() {
        return formEdits().flatMap(arguments -> {
            Object[] a = arguments.get();
            return Stream.of(
                    Arguments.of(a[0] + ", in place", a[1], a[2], a[3], false),
                    Arguments.of(a[0] + ", renamed", a[1], a[2], a[3], true));
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("formEditsWithAndWithoutRename")
    void passesTheGateAndIsQueued(String label, ConceptCreateModel created, ConceptType type, ConceptEditModel edit,
                                  boolean rename) {
        String conceptIri = givenStoredConcept(created, type);
        if (rename) {
            edit.setNameModel(Texts.name("cs", "Přejmenovaný pojem"));
        }
        when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        operation.editConcept(CONCEPT_ID, edit);

        verify(outboxWriter).enqueueUpsert(eq(GRAPH), eq(conceptIri), anySet(), anySet());
    }

    /** Writes the concept with the real creator and serves it as the stored graph; returns its IRI. */
    private String givenStoredConcept(ConceptCreateModel created, ConceptType type) {
        created.setOntologyGraphName(GRAPH);
        created.setNamespace(GRAPH);
        Resource concept = new ConceptCreator().createSingleConcept(created);
        Model graph = ModelFactory.createDefaultModel().add(concept.getModel());

        ConceptMetadataEntity entity = new ConceptMetadataEntity();
        entity.setId(CONCEPT_ID);
        entity.setConceptIri(concept.getURI());
        entity.setConceptType(type);
        entity.setGraphName(GRAPH);
        entity.setUserId("user");
        when(conceptMetadataRepository.findWithLockById(CONCEPT_ID)).thenReturn(Optional.of(entity));
        when(jenaTDB2Repository.fetchGraph(GRAPH)).thenReturn(graph);
        return concept.getURI();
    }
}
