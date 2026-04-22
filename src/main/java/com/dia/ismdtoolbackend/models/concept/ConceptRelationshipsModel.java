package com.dia.ismdtoolbackend.models.concept;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Getter
@Setter
public class ConceptRelationshipsModel {
    private String name;
    /**
     * Navigation reference. Slug for local resources (used with /api/concept/{slug}/detail);
     * full IRI for NKD resources (used with /api/nkd/concept/detail?iri=...).
     */
    private String ref;
}
