package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;

import org.apache.jena.rdf.model.Model;

import java.util.List;
import java.util.Map;

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
     * {@link #deviationFor} for a caller that already holds the canonical local projection (via
     * {@link #canonicalLocalConcept(String, Model)}), so the concept's graph is not read from Fuseki a
     * second time. Pass {@code null} for {@code local} to read it as usual.
     */
    PublishedConceptDeviationModel deviationForWithLocal(String conceptIri, ConceptDetailModel local);

    /**
     * Deviations for every concept in {@code conceptIris}, all read off the one already-transformed
     * {@code processedModel} the caller holds.
     */
    Map<String, PublishedConceptDeviationModel> deviationForAll(Model processedModel, List<String> conceptIris);

    /**
     * {@link #deviationForAll} for concepts that all belong to {@code ontologyIri}'s scheme.
     *
     * <p>Saves a whole NKD round-trip: the ontology CONSTRUCT already returns every in-scheme concept,
     * so the NKD side is derived from that one (cached) model instead of issuing a second batched
     * concept query. Falls back to the batched query when the ontology model is unavailable.
     *
     * @param ontologyIri the scheme every concept in {@code conceptIris} belongs to; {@code null}
     *                    degrades to {@link #deviationForAll}
     */
    Map<String, PublishedConceptDeviationModel> deviationForAllInOntology(Model processedModel,
                                                                          List<String> conceptIris,
                                                                          String ontologyIri);

    /**
     * Bulk counterpart to {@link #canonicalLocalConcept}: extracts every requested concept from one
     * shared {@code OntModel} built over {@code processedModel}, serving and populating the same per-IRI
     * cache.
     */
    Map<String, ConceptDetailModel> canonicalLocalConcepts(Model processedModel, List<String> conceptIris);

    /**
     * The one canonical local read used for deviation comparison (cached per {@code conceptIri}). Exposed
     * on the interface so the self-proxy call from {@link #deviationFor} honours the cache. Returns
     * {@code null} when the concept has no metadata row or its graph is empty.
     */
    ConceptDetailModel canonicalLocalConcept(String conceptIri);

    /**
     * {@link #canonicalLocalConcept} derived from a graph the caller already holds, sharing the same
     * cache entry. Exposed on the interface so the self-proxy call honours that cache.
     *
     * @param rawModel the concept's own graph, untransformed
     */
    ConceptDetailModel canonicalLocalConcept(String conceptIri, Model rawModel);
}
