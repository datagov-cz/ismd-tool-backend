package com.dia.ismdtoolbackend.service.search;

import com.dia.ismdtoolbackend.controller.dto.SearchResultDto;

/** Folds one search row into another that shares its IRI. */
final class SearchResultMerge {

    private SearchResultMerge() {
    }

    static void mergeNonNullFields(SearchResultDto target, SearchResultDto source) {
        if (source.getLabel() != null) target.setLabel(source.getLabel());
        if (source.getLabelLang() != null) target.setLabelLang(source.getLabelLang());
        if (source.getAltName() != null) target.setAltName(source.getAltName());
        if (source.getDescription() != null) target.setDescription(source.getDescription());
        if (source.getDefinition() != null) target.setDefinition(source.getDefinition());
        if (source.getOntologyIri() != null) target.setOntologyIri(source.getOntologyIri());
        if (source.getConceptType() != null) target.setConceptType(source.getConceptType());
        if (source.getSlug() != null) target.setSlug(source.getSlug());
        if (source.getIsPublished() != null) target.setIsPublished(source.getIsPublished());
        if (source.getLastModified() != null) target.setLastModified(source.getLastModified());
        if (source.getConceptCount() != null) target.setConceptCount(source.getConceptCount());
    }
}
