package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.exception.OntologyValidationException;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.PropertyConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.RelationshipConceptEditModel;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.utility.published.WorkingCopySyncFields;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static com.dia.constants.VocabularyConstants.NEVEREJNY_UDAJ;
import static com.dia.constants.VocabularyConstants.OFN_NAMESPACE_LEGAL;
import static com.dia.constants.VocabularyConstants.USTANOVENI_NEVEREJNOST;
import static com.dia.constants.VocabularyConstants.VEREJNY_UDAJ;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * The edit model a sync builds: the concept's own type, seeded with its current data classification
 * read from the graph, and the public/private coupling rules. Sync arithmetic is in
 * {@link SyncWorkingCopySeverTest}.
 */
@ExtendWith(MockitoExtension.class)
class WorkingCopySyncOperationTest {

    private static final String GRAPH = "https://slovník.gov.cz/test-slovnik";
    private static final String IRI = GRAPH + "/pojem/obec";
    private static final String PROVISION_IRI = "https://opendata.eselpoint.cz/esel-esb/eli/cz/sb/1999/106/2024-01-01/dokument/norma/cast_1/par_11";

    @Mock
    private ConceptMetadataRepository conceptMetadataRepository;
    @Mock
    private JenaTDB2Repository jenaTDB2Repository;
    @Mock
    private NkdSparqlClient nkdSparqlClient;
    @Mock
    private ConceptEditOperation editOperation;
    @Mock
    private ConceptDetailReader detailReader;

    private WorkingCopySyncOperation operation;
    private Model graph;
    private Resource concept;

    @BeforeEach
    void setUp() {
        operation = new WorkingCopySyncOperation(conceptMetadataRepository, jenaTDB2Repository, nkdSparqlClient,
                new ConceptDeviationComparator(), new WorkingCopySyncFields(),
                new ConceptAccessGuard(conceptMetadataRepository), editOperation, detailReader);
        graph = ModelFactory.createDefaultModel();
        concept = graph.createResource(IRI);
    }

    private ConceptMetadataEntity metadata(ConceptType type) {
        ConceptMetadataEntity metadata = new ConceptMetadataEntity();
        metadata.setConceptIri(IRI);
        metadata.setGraphName(GRAPH);
        metadata.setConceptType(type);
        return metadata;
    }

    private ConceptEditModel build(ConceptType type) {
        when(jenaTDB2Repository.fetchGraph(GRAPH)).thenReturn(graph);
        return operation.buildEditModelFromAcceptedFields(metadata(type), Set.of(),
                OntologyDetailModel.ConceptDetailModel.builder().build());
    }

    // ---- carry-through of the current classification ---------------------------------------------

    @Test
    void privateProperty_carriesItsFlagAndBothIriAndLiteralProvisions() {
        concept.addProperty(RDF.type, graph.createResource(OFN_NAMESPACE_LEGAL + NEVEREJNY_UDAJ));
        concept.addProperty(graph.createProperty(OFN_NAMESPACE_LEGAL + USTANOVENI_NEVEREJNOST),
                graph.createResource(PROVISION_IRI));
        concept.addProperty(graph.createProperty(OFN_NAMESPACE_LEGAL + USTANOVENI_NEVEREJNOST), "§ 11 odst. 1");

        ConceptEditModel edit = build(ConceptType.VLASTNOST);

        assertThat(edit).isInstanceOf(PropertyConceptEditModel.class);
        assertThat(edit.getConceptType()).isEqualTo("VLASTNOST");
        PropertyConceptEditModel property = (PropertyConceptEditModel) edit;
        assertThat(property.getIsPublic()).isFalse();
        assertThat(property.getPrivacyProvisions()).containsExactlyInAnyOrder(PROVISION_IRI, "§ 11 odst. 1");
    }

    @Test
    void publicRelationship_carriesTrueAndNoProvisions() {
        concept.addProperty(RDF.type, graph.createResource(OFN_NAMESPACE_LEGAL + VEREJNY_UDAJ));

        ConceptEditModel edit = build(ConceptType.VZTAH);

        assertThat(edit).isInstanceOf(RelationshipConceptEditModel.class);
        RelationshipConceptEditModel relationship = (RelationshipConceptEditModel) edit;
        assertThat(relationship.getIsPublic()).isTrue();
        assertThat(relationship.getPrivacyProvisions()).isEmpty();
    }

    @Test
    void unclassifiedClass_carriesNull() {
        ConceptEditModel edit = build(ConceptType.TRIDA);

        assertThat(edit).isInstanceOf(ClassConceptEditModel.class);
        assertThat(((ClassConceptEditModel) edit).getIsPublic()).isNull();
        assertThat(((ClassConceptEditModel) edit).getPrivacyProvisions()).isEmpty();
    }

    @Test
    void koncept_cannotBeSynced() {
        assertThatThrownBy(() -> operation.newEditModelFor(ConceptType.KONCEPT))
                .isInstanceOf(OntologyValidationException.class)
                .hasMessageContaining("KONCEPT");
    }

    // ---- public/private coupling ------------------------------------------------------------------

    private static OntologyDetailModel.ConceptDetailModel nkd(List<String> types, List<String> provisions) {
        return OntologyDetailModel.ConceptDetailModel.builder().types(types).privacyProvisions(provisions).build();
    }

    @Test
    void goingPublic_needsNoProvision() {
        Set<String> accepted = Set.of(WorkingCopySyncFields.IS_PUBLIC_KEY);

        Set<String> effective = operation.resolvePublicPrivateSync(accepted, nkd(List.of("Veřejný údaj"), null));

        assertThat(effective).containsExactly(WorkingCopySyncFields.IS_PUBLIC_KEY);
    }

    @Test
    void goingPrivate_withNullProvisions_isRejected() {
        Set<String> accepted = Set.of(WorkingCopySyncFields.IS_PUBLIC_KEY);

        assertThatThrownBy(() -> operation.resolvePublicPrivateSync(accepted, nkd(List.of("Neveřejný údaj"), null)))
                .isInstanceOf(OntologyValidationException.class)
                .hasMessageContaining("neobsahuje platné ustanovení");
    }

    @Test
    void goingPrivate_withOnlyNullAndBlankProvisions_isRejected() {
        Set<String> accepted = Set.of(WorkingCopySyncFields.IS_PUBLIC_KEY);
        OntologyDetailModel.ConceptDetailModel twin = nkd(List.of("Neveřejný údaj"), Arrays.asList(null, "  "));

        assertThatThrownBy(() -> operation.resolvePublicPrivateSync(accepted, twin))
                .isInstanceOf(OntologyValidationException.class);
    }

    @Test
    void goingPrivate_skipsBlankEntriesAndCoSyncsTheProvision() {
        Set<String> accepted = Set.of(WorkingCopySyncFields.IS_PUBLIC_KEY);
        OntologyDetailModel.ConceptDetailModel twin = nkd(List.of("Neveřejný údaj"), Arrays.asList(" ", PROVISION_IRI));

        assertThat(operation.resolvePublicPrivateSync(accepted, twin))
                .containsExactly(WorkingCopySyncFields.IS_PUBLIC_KEY, "ustanovení-dokládající-neveřejnost-údaje");
    }

    // ---- accepted-key validation ------------------------------------------------------------------

    @Test
    void acceptedKeyThatDoesNotDeviate_isRejectedByName() {
        assertThatThrownBy(() -> operation.validateAcceptedKeys(Set.of("popis"), Set.of("definice")))
                .isInstanceOf(OntologyValidationException.class)
                .hasMessage("Tyto vlastnosti se neliší od NKD: [popis]");
    }

    @Test
    void deviatingKnownKeys_pass() {
        assertThatCode(() -> operation.validateAcceptedKeys(Set.of("popis"), Set.of("popis", "definice")))
                .doesNotThrowAnyException();
    }
}
