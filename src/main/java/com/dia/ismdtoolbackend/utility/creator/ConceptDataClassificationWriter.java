package com.dia.ismdtoolbackend.utility.creator;

import com.dia.ismdtoolbackend.utility.eli.EsbirkaEliParser;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

import java.util.List;

import static com.dia.ismdtoolbackend.constants.VocabularyConstants.*;

/** Writes the public or non-public classification and its privacy provisions. */
@Slf4j
final class ConceptDataClassificationWriter {

    private final OntModel ontModel;

    ConceptDataClassificationWriter(ConceptBuildContext context) {
        this.ontModel = context.ontModel();
    }

    /**
     * Writes the veřejný/neveřejný-údaj classification. Malformed provisions are rejected
     * upstream by {@code ConceptInputValidator}, so {@code validProvisions} mirrors the
     * non-blank input; the filtering here is a defensive backstop for non-service callers.
     */
    void addDataClassification(Resource resource, Boolean isPublic, List<String> privacyProvisions) {
        boolean hasNonEmptyProvisions = privacyProvisions != null &&
                privacyProvisions.stream().anyMatch(p -> p != null && !p.trim().isEmpty());

        List<String> validProvisions = new java.util.ArrayList<>();
        if (privacyProvisions != null) {
            for (String provision : privacyProvisions) {
                if (provision == null || provision.trim().isEmpty()) continue;
                String canonical = EsbirkaEliParser.canonicalizeHost(provision.trim());
                if (SparqlIriValidator.isEsbirkaEliIri(canonical)) {
                    validProvisions.add(canonical);
                } else {
                    log.warn("Skipping privacy provision — not a canonical e-Sbírka ELI IRI: {}", provision.trim());
                }
            }
        }

        if (Boolean.TRUE.equals(isPublic)) {
            if (!hasNonEmptyProvisions) {
                resource.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE_LEGAL + VEREJNY_UDAJ));
            }
        } else if (Boolean.FALSE.equals(isPublic) && !validProvisions.isEmpty()) {
                resource.addProperty(RDF.type, ontModel.getResource(OFN_NAMESPACE_LEGAL + NEVEREJNY_UDAJ));
                Property provisionProperty = ontModel.createProperty(OFN_NAMESPACE_LEGAL + USTANOVENI_NEVEREJNOST);
                for (String transformedProvision : validProvisions) {
                    resource.addProperty(provisionProperty, ontModel.createResource(transformedProvision));
                }
            }
    }
}
