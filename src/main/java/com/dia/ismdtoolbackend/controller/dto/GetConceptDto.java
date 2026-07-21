package com.dia.ismdtoolbackend.controller.dto;

import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Data
@Getter
@Setter
public class GetConceptDto {
    private ConceptMetadataModel conceptMetadata;
    private OntologyDetailModel.ConceptDetailModel conceptDetail;

    /** Working-copy deviation: this concept vs its own NKD twin. Null unless {@code is_published}. */
    private PublishedConceptDeviationModel publishedConceptDeviationModel;

    /**
     * This concept's tracked local copies of the published NKD concepts it links to — the same
     * {@link LinkSnapshotDto}s the ontology detail carries, narrowed to this owner, so the copy cards and
     * their action URLs (which need {@code snapshotId}) are available on the concept's own page.
     * {@code NON_NULL} → omitted when the concept links no published concept.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<LinkSnapshotDto> linkSnapshots;
}