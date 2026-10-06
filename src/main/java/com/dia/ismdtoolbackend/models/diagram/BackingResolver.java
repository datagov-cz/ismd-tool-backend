package com.dia.ismdtoolbackend.models.diagram;

import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;

import java.util.Map;
import java.util.Set;

/**
 * The one place a placed element's {@link Backing} is decided. Built once per read from the resolved live
 * content, then consulted by every element type — class node, VZTAH edge, hierarchy or equivalence edge, and
 * property row — so none of them re-derives the rule or hardcodes the answer.
 *
 * <p>The rule was previously copied into four call sites, three of which simply wrote {@code false}, which is
 * how deleted properties and edges came to vanish from the canvas while deleted nodes were correctly flagged.
 * Routing every element through {@link #of} is what makes that asymmetry impossible rather than merely fixed.
 */
public record BackingResolver(Map<String, ConceptDetailModel> byIri, Set<String> unavailable) {

    public BackingResolver {
        byIri = byIri != null ? byIri : Map.of();
        unavailable = unavailable != null ? unavailable : Set.of();
    }

    /**
     * The verdict for one concept IRI. An IRI whose graph could not be read is
     * {@link Backing.Presence#UNAVAILABLE} and presumed intact; one absent from a graph that <em>was</em>
     * read is {@link Backing.Presence#STALE}, meaning deleted.
     */
    public Backing of(String conceptIri) {
        ConceptDetailModel detail = byIri.get(conceptIri);
        if (detail != null) {
            return new Backing(Backing.Presence.LIVE, detail);
        }
        return new Backing(
                unavailable.contains(conceptIri) ? Backing.Presence.UNAVAILABLE : Backing.Presence.STALE,
                null);
    }

    /** The live content, for the traversals that still iterate every resolved concept. */
    public ConceptDetailModel get(String conceptIri) {
        return byIri.get(conceptIri);
    }
}