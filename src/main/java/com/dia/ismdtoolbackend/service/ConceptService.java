package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.controller.dto.GetConceptDto;
import com.dia.ismdtoolbackend.models.concept.ConceptCreateModel;
import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import org.apache.jena.ontology.OntologyException;

import java.util.List;

public interface ConceptService {
    ConceptMetadataModel createConcept(ConceptCreateModel createModel, String userId);
    void deleteConcept(Long conceptId);
    ConceptMetadataModel editConcept(Long conceptId, ConceptEditModel conceptEditModel);

    /**
     * Takes the listed deviating characteristics of a working copy from live NKD. Accepting every
     * deviating field keeps the concept a working copy; accepting a strict subset <strong>severs</strong>
     * it — {@code is_published=false}, so it becomes a plain draft and stops being deviation-tracked.
     *
     * @param conceptId      the working copy ({@code is_published=true}, else 400)
     * @param fieldsToAccept deviation field keys (the {@code @JsonProperty} names on
     *                       {@code PublishedConceptDeviationModel}); unknown/non-deviating keys → 400
     * @return the concept's slug. The RDF delta reaches the store only after this transaction commits,
     *         so the post-sync detail is read by the caller via {@link #getConceptDetail}
     */
    String syncWorkingCopy(Long conceptId, List<String> fieldsToAccept);
    List<ConceptMetadataModel> getAll(String userId, Boolean isPublished);
    GetConceptDto getConceptDetail(String conceptSlug) throws OntologyException;
}
