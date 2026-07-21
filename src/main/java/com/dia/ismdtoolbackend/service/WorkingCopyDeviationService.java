package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;

/**
 * Single source of truth for a working copy's deviation against its NKD twin, so ontology detail and
 * concept detail can never show conflicting results. Both surfaces call {@link #deviationFor}.
 */
public interface WorkingCopyDeviationService {

    /**
     * The working-copy deviation for {@code conceptIri}: the canonical local projection compared against
     * the live NKD twin. Safe to call from either surface — they always agree.
     */
    PublishedConceptDeviationModel deviationFor(String conceptIri);

    /**
     * The one canonical local read used for deviation comparison (cached per {@code conceptIri}). Exposed
     * on the interface so the self-proxy call from {@link #deviationFor} honours the cache. Returns
     * {@code null} when the concept has no metadata row or its graph is empty.
     */
    ConceptDetailModel canonicalLocalConcept(String conceptIri);
}
