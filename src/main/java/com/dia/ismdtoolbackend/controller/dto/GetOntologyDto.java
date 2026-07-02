package com.dia.ismdtoolbackend.controller.dto;

import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.models.PublishedOntologyDeviationModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Data
@Getter
@Setter
public class GetOntologyDto {
    private OntologyMetadataModel ontologyMetadata;
    private OntologyDetailModel ontologyDetail;

    /** SELF_PUBLISHED deviation per concept ("concept vs its own NKD twin"). */
    private PublishedOntologyDeviationModel publishedOntologyDeviationModel;
    private Map<String, PublishedConceptDeviationModel> publishedConceptDeviations;

    /**
     * LINK_TARGET local copies ("a copied foreign NKD concept vs its NKD source"), keyed by owner
     * concept IRI — one list per concept that links published NKD concepts. {@code NON_NULL} →
     * omitted when no concept in the ontology links a published concept, so existing ontology
     * payloads are byte-identical. Cold/stale entries carry {@code status=PENDING} while the async
     * warmer runs; disambiguated from {@link #publishedConceptDeviations} by each entry's {@code origin}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, List<LinkSnapshotDto>> linkSnapshots;
}
