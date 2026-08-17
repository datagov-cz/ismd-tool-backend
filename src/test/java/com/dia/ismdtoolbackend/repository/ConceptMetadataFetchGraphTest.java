package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.EntityGraph;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Guards the {@code ontologyMetadata} fetch graph on every finder whose results get mapped through
 * {@code ConceptMetadataMapper}.
 *
 * <p>That mapper populates {@code ontologySlug} from the nested {@code ontologyMetadata.slug}, which
 * dereferences a LAZY proxy. Without the fetch graph each mapped row costs an extra SELECT, so
 * {@code /api/concept/list} over an unfiltered {@code findAll()} degrades to 1+N across the whole
 * concept table. The annotations are the only thing preventing that and are easy to drop silently —
 * nothing else fails when they go.
 *
 * <p>Reflection rather than a query-count test on purpose: this asserts the declaration itself, so it
 * holds without a database and points straight at the missing annotation.
 */
class ConceptMetadataFetchGraphTest {

    /** Finders whose rows reach ConceptMetadataMapper.toDto — see ConceptServiceImpl.getAll. */
    private static final List<String> FINDERS_REQUIRING_GRAPH =
            List.of("findAll", "findAllByUserId", "findAllByIsPublished",
                    "findAllByUserIdAndIsPublished", "findBySlug", "findByGraphName");

    @Test
    void mappedFinders_declareOntologyMetadataFetchGraph() {
        for (String name : FINDERS_REQUIRING_GRAPH) {
            Method method = findMethod(name);
            EntityGraph graph = method.getAnnotation(EntityGraph.class);
            assertNotNull(graph, () -> name + " must declare @EntityGraph(attributePaths = \"ontologyMetadata\") — "
                    + "its rows are mapped through ConceptMetadataMapper, which walks the LAZY "
                    + "ontologyMetadata proxy and would otherwise issue one SELECT per row");
            assertEquals(List.of("ontologyMetadata"), List.of(graph.attributePaths()),
                    () -> name + " must fetch exactly ontologyMetadata");
        }
    }

    private Method findMethod(String name) {
        for (Method m : ConceptMetadataRepository.class.getMethods()) {
            if (m.getName().equals(name) && returnsConceptRows(m)) {
                return m;
            }
        }
        throw new AssertionError("ConceptMetadataRepository." + name + " not found — if it was renamed, "
                + "update FINDERS_REQUIRING_GRAPH rather than deleting this guard");
    }

    /** Filters out unrelated overloads (e.g. Spring Data's findAll(Sort)/findAll(Pageable)). */
    private boolean returnsConceptRows(Method m) {
        java.lang.reflect.Type generic = m.getGenericReturnType();
        return generic.getTypeName().contains(ConceptMetadataEntity.class.getName());
    }
}
