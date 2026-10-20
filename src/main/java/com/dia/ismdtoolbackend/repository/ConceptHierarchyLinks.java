package com.dia.ismdtoolbackend.repository;

import java.util.List;

/** One concept's hierarchy link targets, as raw IRIs. */
public record ConceptHierarchyLinks(List<String> broader, List<String> exactMatch) {

    public static ConceptHierarchyLinks empty() {
        return new ConceptHierarchyLinks(List.of(), List.of());
    }
}
