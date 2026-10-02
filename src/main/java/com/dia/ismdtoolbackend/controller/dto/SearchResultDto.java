package com.dia.ismdtoolbackend.controller.dto;

import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.MatchedBy;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.enums.SearchType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResultDto {
    private Long id;
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
    /**
     * The owning ontology's {@code skos:prefLabel} as a language→value map. Sourced from RDF, since
     * Postgres holds no ontology name; null when the ontology carries no label or Fuseki is
     * unreachable. Currently populated for {@code type=DIAGRAM} rows.
     */
    private Map<String, String> ontologyLabel;
    private Boolean isPublished;
    private MatchedBy matchedBy;
    /**
     * ISO-8601 timestamp of the last known modification. Sourced from
     * Postgres {@code updated_at} for ISMD rows; from {@code dcterms:modified}
     * (when published) for NKD rows — null otherwise, since NKD's public
     * SPARQL does not currently expose modification dates.
     */
    private String lastModified;
    /** Populated only for {@code type=ONTOLOGY} results. */
    private Integer conceptCount;
    /**
     * Populated only for {@code type=DIAGRAM} results — the routing key, used with {@code slug}:
     * {@code GET /api/diagram/{slug}/{diagramId}/detail}. An ontology may hold many diagrams, so the
     * slug alone no longer identifies one.
     */
    private Long diagramId;
}
