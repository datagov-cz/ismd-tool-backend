package com.dia.ismdtoolbackend.models.concept;

import com.dia.ismdtoolbackend.controller.dto.DataTypeDto;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConceptPropertiesModel {
    private String name;

    /**
     * Concept IRI of this property. Use this as the lookup key into
     * {@code ConceptDetailModel.referencedConceptsResolved} to retrieve
     * pre-resolved metadata (conceptName, ontologyIri, ontologyName, source).
     */
    private String iri;

    /**
     * Navigation reference. Slug for local resources (used with /api/concept/{slug}/detail);
     * full IRI for NKD resources (used with /api/nkd/concept/detail?iri=...).
     */
    private String ref;

    /**
     * Raw {@code rdfs:range} of this property concept, mirroring the parent
     * concept's {@code obor-hodnot} field (e.g. {@code "xsd:string"}).
     */
    private String range;

    /**
     * Codelist-resolved view of {@link #range}. Falls back to the {@code Literal}
     * entry when the underlying range is null/empty/unrecognised — matches the
     * write-path default in {@code ConceptCreator.addRangeProperty}.
     */
    private DataTypeDto rangeResolved;
}
