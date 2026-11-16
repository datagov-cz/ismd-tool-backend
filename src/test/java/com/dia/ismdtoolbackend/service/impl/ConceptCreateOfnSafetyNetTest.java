package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.models.concept.ConceptCreateModel;
import com.dia.ismdtoolbackend.models.concept.DigitalObjectModel;
import com.dia.ismdtoolbackend.outbox.OutboxWriter;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.testsupport.fixtures.ConceptModels;
import com.dia.ismdtoolbackend.utility.creator.ConceptCreator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every concept shape the form can create passes the OFN write gate: the real {@link ConceptCreator} and the
 * real gate, so a writer regression that breaks T1 or T2 fails here instead of in production.
 */
@ExtendWith(MockitoExtension.class)
class ConceptCreateOfnSafetyNetTest {

    private static final String GRAPH = "https://slovník.gov.cz/test-brana-zapisu";
    private static final String ELI = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/";
    private static final String CODE_LIST = "https://data.mvcr.gov.cz/zdroj/číselníky/typy-turistických-cílů";
    private static final String DATASET = "https://data.gov.cz/zdroj/datové-sady/test-dataset";

    private ConceptMetadataRepository conceptMetadataRepository;
    private OntologyMetadataRepository ontologyMetadataRepository;
    private OutboxWriter outboxWriter;
    private ConceptCreateOperation operation;

    @BeforeEach
    void setUp() {
        ConceptServiceFixture fixture = new ConceptServiceFixture().conceptCreator(new ConceptCreator());
        conceptMetadataRepository = fixture.conceptMetadataRepository();
        ontologyMetadataRepository = fixture.ontologyMetadataRepository();
        outboxWriter = fixture.outboxWriter();
        operation = fixture.buildCreateOperation();
    }

    static Stream<Arguments> formConcepts() {
        return Stream.of(
                Arguments.of("plain třída", ConceptModels.trida("Osoba")),
                Arguments.of("non-public subjekt with every optional field", ConceptModels.trida("Žadatel", m -> {
                    m.setType("subjekt");
                    m.setIsPublic(false);
                    m.setPrivacyProvisions(List.of(ELI + "2020/50"));
                    m.setIsInPPDF(true);
                    m.setSharingMethod(List.of("nesdílené"));
                    m.setAcquisitionMethod("vlastní");
                    m.setContentType("provozní");
                    m.setAgendaCode("A123");
                    m.setAgendaSystemCode("456");
                    m.setDefiningLegalSource(List.of(ELI + "2021/12"));
                    m.setDefiningNonLegalSource(List.of(new DigitalObjectModel("Metodika", "Popis", "https://example.org/doc")));
                    m.setRelatedNonLegalSource(List.of(new DigitalObjectModel(null, null, "https://example.org/doc2")));
                    m.setExactMatch(List.of("https://example.org/exact"));
                })),
                Arguments.of("objekt with a code list", ConceptModels.trida("Turistický cíl", m -> {
                    m.setType("objekt");
                    m.setCodeListIri(CODE_LIST);
                    m.setCodeListDataset(DATASET);
                })),
                Arguments.of("vlastnost with a datatype code", ConceptModels.vlastnost("Výška", m -> {
                    m.setDataType("double");
                    m.setDomain("Vozidlo");
                })),
                Arguments.of("vlastnost without a datatype", ConceptModels.vlastnost("Poznámka")),
                Arguments.of("vztah", ConceptModels.vztah("Vlastní", m -> {
                    m.setDomain("Osoba");
                    m.setRange("Vozidlo");
                })));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("formConcepts")
    void passesTheGateAndIsQueued(String label, ConceptCreateModel model) {
        model.setOntologyGraphName(GRAPH);
        model.setNamespace(GRAPH);
        OntologyMetadataEntity ontology = new OntologyMetadataEntity();
        ontology.setId(1L);
        ontology.setGraphName(GRAPH);
        when(ontologyMetadataRepository.findByGraphName(GRAPH)).thenReturn(Optional.of(ontology));
        when(conceptMetadataRepository.save(any(ConceptMetadataEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        operation.createConcept(model, "user");

        verify(outboxWriter).enqueueUpsert(eq(GRAPH), anyString(), eq(java.util.Set.of()), anySet());
    }
}
