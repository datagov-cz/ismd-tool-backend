package com.dia.ismdtoolbackend.utility.detail;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDFS;

import java.util.*;
import java.util.function.Function;

/** Resolvers that map a concept IRI to its local DB slug. */
class DetailSlugResolvers {

    private final ConceptMetadataRepository conceptMetadataRepository;

    DetailSlugResolvers(ConceptMetadataRepository conceptMetadataRepository) {
        this.conceptMetadataRepository = conceptMetadataRepository;
    }

    /**
     * Resolver that maps a concept IRI to its local DB slug, or {@code null} when the concept
     * has no local metadata row. Intended for the local detail pipeline.
     */
    Function<String, String> dbSlugResolver() {
        return iri -> conceptMetadataRepository.findByConceptIri(iri)
                .map(ConceptMetadataEntity::getSlug)
                .orElse(null);
    }

    /**
     * Batch-prefetched variant of {@link #dbSlugResolver()} for the whole-ontology path.
     *
     * <p>Falls back to the per-IRI resolver for IRIs outside the model (cross-graph members), so
     * resolution stays identical to {@link #dbSlugResolver()} rather than silently narrowing to
     * this graph.
     */
    Function<String, String> graphSlugResolver(Model processedModel) {
        Set<String> subjectIris = new LinkedHashSet<>();
        ResIterator it = processedModel.listSubjects();
        while (it.hasNext()) {
            Resource r = it.nextResource();
            if (r.isURIResource()) {
                subjectIris.add(r.getURI());
            }
        }
        if (subjectIris.isEmpty()) {
            return dbSlugResolver();
        }

        Map<String, String> slugByIri = new HashMap<>();
        conceptMetadataRepository.findByConceptIriIn(new ArrayList<>(subjectIris))
                .forEach(e -> slugByIri.put(e.getConceptIri(), e.getSlug()));
        Function<String, String> fallback = dbSlugResolver();
        return iri -> subjectIris.contains(iri) ? slugByIri.get(iri) : fallback.apply(iri);
    }

    /** Single-concept variant of {@link #batchSlugResolver(OntModel, Collection)}. */
    Function<String, String> batchSlugResolver(OntModel ontModel, String conceptIri) {
        return batchSlugResolver(ontModel, List.of(conceptIri));
    }

    /**
     * Batch-prefetched variant of {@link #dbSlugResolver()}. Collects every property/relationship
     * member IRI reachable from {@code conceptIris}, resolves their slugs in one
     * {@code findByConceptIriIn}, and serves each {@code apply} from the resulting map — so the bulk
     * path pays one query for all concepts rather than one each.
     */
    Function<String, String> batchSlugResolver(OntModel ontModel, Collection<String> conceptIris) {
        Set<String> memberIris = new LinkedHashSet<>();
        for (String conceptIri : conceptIris) {
            Resource conceptResource = ontModel.getResource(conceptIri);
            ontModel.listSubjectsWithProperty(RDFS.domain, conceptResource)
                    .forEachRemaining(r -> { if (r.isURIResource()) memberIris.add(r.getURI()); });
            ontModel.listSubjectsWithProperty(RDFS.range, conceptResource)
                    .forEachRemaining(r -> { if (r.isURIResource()) memberIris.add(r.getURI()); });
        }

        if (memberIris.isEmpty()) {
            return iri -> null;
        }

        Map<String, String> slugByIri = new HashMap<>();
        conceptMetadataRepository.findByConceptIriIn(new ArrayList<>(memberIris))
                .forEach(e -> slugByIri.put(e.getConceptIri(), e.getSlug()));
        return slugByIri::get;
    }
}
