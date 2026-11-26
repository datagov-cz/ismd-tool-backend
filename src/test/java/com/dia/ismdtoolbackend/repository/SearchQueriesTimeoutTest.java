package com.dia.ismdtoolbackend.repository;

import jakarta.persistence.QueryHint;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Every PG text-search query carries the statement timeout, so an abandoned search frees its connection. */
class SearchQueriesTimeoutTest {

    @ParameterizedTest
    @ValueSource(classes = {ConceptMetadataRepository.class, OntologyMetadataRepository.class, DiagramRepository.class})
    void textSearchQueriesHaveAStatementTimeout(Class<?> repository) {
        List<Method> textSearches = Arrays.stream(repository.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Query.class))
                .filter(m -> m.getAnnotation(Query.class).value().contains("unaccent"))
                .toList();

        assertThat(textSearches).isNotEmpty().allSatisfy(m -> {
            QueryHints hints = m.getAnnotation(QueryHints.class);
            assertThat(hints).as(m.getName()).isNotNull();
            assertThat(Arrays.stream(hints.value()).map(QueryHint::name)).as(m.getName())
                    .contains(SearchQueries.TIMEOUT_HINT);
        });
    }
}
