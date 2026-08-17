package com.dia.ismdtoolbackend.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.EntityGraph;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the fetch graphs that let {@code ConceptServiceImpl.getConceptDetail} run without a
 * request-wide transaction.
 *
 * <p>That method interleaves PG reads with Fuseki, NKD and RPP calls (10s timeouts each), so it is
 * deliberately NOT {@code @Transactional} — a transaction spanning it would pin a Hikari connection
 * across every one of those calls, which is exactly the pinning {@code spring.jpa.open-in-view=false}
 * exists to prevent. With no open transaction, any LAZY association dereferenced after its fetching
 * repository call returns throws {@code LazyInitializationException}.
 *
 * <p>Both failures below would be <em>silent</em>: {@code surfaceLinkSnapshots} swallows every
 * exception, so link snapshots would simply vanish from the response. Reflection rather than a
 * query-count test on purpose — it asserts the declaration itself and points straight at the missing
 * annotation.
 *
 * @see ConceptMetadataFetchGraphTest for the N+1-motivated graphs on the concept repository
 */
class ConceptDetailFetchGraphTest {

    @Test
    void snapshotFinder_joinFetchesOwningConcept() {
        EntityGraph graph = graphOn(NkdConceptSnapshotRepository.class, "findByOwningConceptId");

        assertThat(graph)
                .as("NkdConceptSnapshotRepository.findByOwningConceptId must declare "
                        + "@EntityGraph(attributePaths = \"owningConcept\") — LinkSnapshotAssembler reads "
                        + "owningConcept.conceptIri outside the fetching transaction, and getConceptDetail "
                        + "swallows the resulting LazyInitializationException, silently dropping snapshots")
                .isNotNull();
        assertThat(graph.attributePaths()).containsExactly("owningConcept");
    }

    @Test
    void conceptCommentFinder_joinFetchesBothOwners() {
        EntityGraph graph = graphOn(CommentRepository.class, "findByConceptMetadataId");

        assertThat(graph)
                .as("CommentRepository.findByConceptMetadataId must join-fetch both owners — "
                        + "ConceptMetadataMapper.commentEntityToModel reads ontologyMetadata.graphName "
                        + "and conceptMetadata.conceptIri outside the fetching transaction")
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