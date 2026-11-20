package com.dia.ismdtoolbackend.models;

import java.util.List;

/**
 * Filters of the ontology list; every one is optional and they combine with AND. Blank strings and an empty
 * slug list mean "no filter".
 *
 * @param q text matched against the name, description and slug, ignoring case and diacritics
 */
public record OntologyListQuery(String userId, Boolean isPublished, List<String> slugs, String graphName,
                                String q, OntologyListSort sort) {

    public OntologyListQuery {
        userId = blankToNull(userId);
        graphName = blankToNull(graphName);
        q = blankToNull(q);
        slugs = slugs == null ? List.of() : slugs.stream().filter(s -> s != null && !s.isBlank()).toList();
        sort = sort == null ? OntologyListSort.NAME : sort;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
