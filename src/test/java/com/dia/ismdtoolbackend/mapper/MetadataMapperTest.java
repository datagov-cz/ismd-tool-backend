package com.dia.ismdtoolbackend.mapper;

import com.dia.ismdtoolbackend.entity.CommentEntity;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.models.CommentModel;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.models.UserModel;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The real MapStruct-generated metadata mappers; service tests mock them. */
class MetadataMapperTest {

    private static final String GRAPH = "https://slovník.gov.cz/agendový/104";
    private static final String CONCEPT = GRAPH + "/pojem/adresa";
    private static final LocalDateTime POSTED = LocalDateTime.of(2026, 10, 21, 9, 30);

    private final OntologyMetadataMapper ontologyMapper = new OntologyMetadataMapperImpl();
    private final ConceptMetadataMapper conceptMapper = new ConceptMetadataMapperImpl();

    /** Both mappers carry the same comment mapping; each is checked. */
    static Stream<Arguments> commentMappers() {
        OntologyMetadataMapper ontology = new OntologyMetadataMapperImpl();
        ConceptMetadataMapper concept = new ConceptMetadataMapperImpl();
        return Stream.of(
                Arguments.of("ontology", (Function<List<CommentEntity>, List<CommentModel>>) ontology::commentEntitiesToModels),
                Arguments.of("concept", (Function<List<CommentEntity>, List<CommentModel>>) concept::commentEntitiesToModels));
    }

    @ParameterizedTest(name = "{0} mapper")
    @MethodSource("commentMappers")
    void commentCarriesItsOwnersIris(String mapper, Function<List<CommentEntity>, List<CommentModel>> map) {
        OntologyMetadataEntity ontology = new OntologyMetadataEntity();
        ontology.setGraphName(GRAPH);
        ConceptMetadataEntity concept = new ConceptMetadataEntity();
        concept.setConceptIri(CONCEPT);
        CommentEntity comment = comment();
        comment.setOntologyMetadata(ontology);
        comment.setConceptMetadata(concept);

        assertThat(map.apply(List.of(comment))).singleElement().satisfies(model -> {
            assertThat(model.getId()).isEqualTo(3L);
            assertThat(model.getUserId()).isEqualTo("user-1");
            assertThat(model.getComment()).isEqualTo("Chybí definice.");
            assertThat(model.getPostedTime()).isEqualTo(POSTED);
            assertThat(model.getOntologyIRI()).isEqualTo(GRAPH);
            assertThat(model.getConceptIRI()).isEqualTo(CONCEPT);
        });
    }

    @ParameterizedTest(name = "{0} mapper")
    @MethodSource("commentMappers")
    void commentWithoutOwnersHasNoIris(String mapper, Function<List<CommentEntity>, List<CommentModel>> map) {
        assertThat(map.apply(List.of(comment()))).singleElement().satisfies(model -> {
            assertThat(model.getOntologyIRI()).isNull();
            assertThat(model.getConceptIRI()).isNull();
        });
    }

    @ParameterizedTest(name = "{0} mapper")
    @MethodSource("commentMappers")
    void noCommentsIsAnEmptyList(String mapper, Function<List<CommentEntity>, List<CommentModel>> map) {
        assertThat(map.apply(null)).isEmpty();
        assertThat(map.apply(List.of())).isEmpty();
    }

    @Test
    void nullCommentMapsToNull() {
        assertThat(ontologyMapper.commentEntityToModel(null)).isNull();
        assertThat(conceptMapper.commentEntityToModel(null)).isNull();
    }

    @Test
    void ontologyOwnerRoundTripsBetweenUserAndUserId() {
        OntologyMetadataModel model = new OntologyMetadataModel();
        model.setGraphName(GRAPH);
        model.setUser(new UserModel("user-1"));

        OntologyMetadataEntity entity = ontologyMapper.toEntity(model);

        assertThat(entity.getUserId()).isEqualTo("user-1");
        assertThat(entity.getGraphName()).isEqualTo(GRAPH);
        assertThat(ontologyMapper.toDto(entity).getUser()).isEqualTo(new UserModel("user-1"));
    }

    @Test
    void ontologyWithoutOwnerStaysWithoutOwner() {
        OntologyMetadataModel model = new OntologyMetadataModel();
        model.setGraphName(GRAPH);

        OntologyMetadataEntity entity = ontologyMapper.toEntity(model);

        assertThat(entity.getUserId()).isNull();
        assertThat(ontologyMapper.toDto(entity).getUser()).isNull();
    }

    @Test
    void conceptCarriesItsOwnerAndOntologySlug() {
        OntologyMetadataEntity ontology = new OntologyMetadataEntity();
        ontology.setSlug("adresy");
        ConceptMetadataEntity concept = new ConceptMetadataEntity();
        concept.setConceptIri(CONCEPT);
        concept.setUserId("user-1");
        concept.setOntologyMetadata(ontology);

        ConceptMetadataModel model = conceptMapper.toDto(concept);

        assertThat(model.getConceptIri()).isEqualTo(CONCEPT);
        assertThat(model.getUser()).isEqualTo(new UserModel("user-1"));
        assertThat(model.getOntologySlug()).isEqualTo("adresy");
    }

    @Test
    void conceptWithoutOwnerOrOntologyMapsToNulls() {
        ConceptMetadataEntity concept = new ConceptMetadataEntity();
        concept.setConceptIri(CONCEPT);

        ConceptMetadataModel model = conceptMapper.toDto(concept);

        assertThat(model.getUser()).isNull();
        assertThat(model.getOntologySlug()).isNull();
    }

    @Test
    void nullEntitiesMapToNull() {
        assertThat(ontologyMapper.toDto(null)).isNull();
        assertThat(ontologyMapper.toEntity(null)).isNull();
        assertThat(conceptMapper.toDto(null)).isNull();
    }

    private static CommentEntity comment() {
        CommentEntity comment = new CommentEntity();
        comment.setId(3L);
        comment.setUserId("user-1");
        comment.setComment("Chybí definice.");
        comment.setPostedTime(POSTED);
        return comment;
    }
}
