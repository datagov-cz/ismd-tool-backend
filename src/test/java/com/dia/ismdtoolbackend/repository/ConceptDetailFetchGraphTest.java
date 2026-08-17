package com.dia.ismdtoolbackend.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.EntityGraph;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the fetch graphs that let {@code ConceptServiceImpl.getConceptDetail} run without a
 * request-wide transaction. It is deliberately not {@code @Transactional}, so a LAZY association
 * dereferenced after its repository call returns throws {@code LazyInitializationException} — and
 * {@code surfaceLinkSnapshots} swallows it, dropping snapshots from the response silently.
 *
 * <p>Reflection rather than a query count: it asserts the declaration itself and needs no database.
 *
 * @see ConceptMetadataFetchGraphTest for the N+1-motivated graphs on the concept repository
 */
class ConceptDetailFetchGraphTest {

    @Test
    void snapshotFinder_joinFetchesOwningConcept() {
        EntityGraph graph = graphOn(NkdConceptSnapshotRepository.class, "findByOwningConceptId");

        assertThat(graph)
                .as("findByOwningConceptId must join-fetch owningConcept — LinkSnapshotAssembler reads "
                        + "owningConcept.conceptIri outside the fetching transaction")
                .isNotNull();
        assertThat(graph.attributePaths()).containsExactly("owningConcept");
    }

    @Test
    void conceptCommentFinder_joinFetchesBothOwners() {
        EntityGraph graph = graphOn(CommentRepository.class, "findByConceptMetadataId");

        assertThat(graph)
                .as("findByConceptMetadataId must join-fetch both owners — commentEntityToModel reads "
                        + "ontologyMetadata.graphName and conceptMetadata.conceptIri outside the transaction")
                .isNotNull();
        assertThat(graph.attributePaths())
                .containsExactlyInAnyOrder("ontologyMetadata", "conceptMetadata");
    }

    private static EntityGraph graphOn(Class<?> repository, String methodName) {
        for (Method m : repository.getMethods()) {
            if (m.getName().equals(methodName)) {
                return m.getAnnotation(EntityGraph.class);
            }
        }
        throw new AssertionError(repository.getSimpleName() + "." + methodName + " not found — if it was "
                + "renamed, update this guard rather than deleting it");
    }
}