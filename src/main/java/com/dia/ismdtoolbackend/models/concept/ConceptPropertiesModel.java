package com.dia.ismdtoolbackend.models.concept;

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
}
