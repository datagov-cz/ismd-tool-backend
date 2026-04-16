package com.dia.ismdtoolbackend.controller.dto;

import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.MatchedBy;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.enums.SearchType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResultDto {
    private String iri;
    private String slug;
    private String label;
    private String labelLang;
    private String altName;
    private String description;
    private String definition;
    private SearchType type;
    private SearchSource source;
    private ConceptType conceptType;
    private String ontologyIri;
    private Boolean isPublished;
    private MatchedBy matchedBy;
}
