package com.dia.ismdtoolbackend.utility.validation;

import com.dia.ismdtoolbackend.exception.OntologyUploadIriCollisionException;
import com.dia.utility.UtilityMethods;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.util.ResourceUtils;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.dia.constants.VocabularyConstants.*;

/**
 * Strips trailing slashes from the IRIs of an uploaded vocabulary, in place, so an uploaded
 * vocabulary gets the same IRIs as one entered through create/edit
 * (see {@link ConceptIriNormalizer}).
 *
 * <p>Renames the vocabulary and its concepts, which also rewrites every reference to them
 * inside the file, and strips the IRIs a concept references through the fields
 * {@link ConceptIriNormalizer} covers. Non-legal source URLs and code-list IRIs are left as
 * supplied.
 */
public final class UploadIriNormalizer {

    private static final Set<Resource> RENAMED_TYPES = Set.of(
            OWL2.Ontology, SKOS.ConceptScheme, SKOS.Concept,
            OWL2.Class, OWL2.DatatypeProperty, OWL2.ObjectProperty);

    private static final Set<String> RENAMED_OFN_TYPES = Set.of(
            OFN_NAMESPACE + POJEM, OFN_NAMESPACE + TRIDA, OFN_NAMESPACE + VZTAH, OFN_NAMESPACE + VLASTNOST);

    private static final Set<String> REFERENCE_PREDICATES = Set.of(
            SKOS.exactMatch.getURI(), RDFS.subClassOf.getURI(), RDFS.subPropertyOf.getURI(),
            RDFS.domain.getURI(), RDFS.range.getURI());

    /** Matched by local name: files name these under the OFN or the vocabulary's own namespace. */
    private static final Set<String> REFERENCE_LOCAL_NAMES = Set.of(
            DEFINUJICI_USTANOVENI, SOUVISEJICI_USTANOVENI, USTANOVENI_NEVEREJNOST, USTANOVENI_LONG,
            "nadřazená-třída", AGENDA_LONG, AGENDA, UDAJE_AIS, AIS);

    private UploadIriNormalizer() {
    }

    /**
     * @return the IRIs that were corrected, as they appeared in the file
     * @throws OntologyUploadIriCollisionException when the file holds both {@code X} and {@code X/}
     */
    public static List<String> normalize(Model model) {
        Map<Resource, String> renames = new LinkedHashMap<>();
        model.listSubjectsWithProperty(RDF.type)
                .filterKeep(s -> s.isURIResource() && isVocabularyOrConcept(s))
                .forEachRemaining(s -> {
                    String stripped = UtilityMethods.removeTrailingSlash(s.getURI());
                    if (!stripped.equals(s.getURI())) renames.put(s, stripped);
                });

        List<String> collisions = renames.entrySet().stream()
                .filter(e -> model.contains(model.createResource(e.getValue()), null, (RDFNode) null))
                .map(e -> e.getValue() + " ↔ " + e.getKey().getURI())
                .toList();
        if (!collisions.isEmpty()) {
            throw new OntologyUploadIriCollisionException(
                    "Soubor obsahuje IRI, která se liší pouze koncovým lomítkem: " + String.join(", ", collisions));
        }

        Set<String> corrected = new TreeSet<>();
        renames.forEach((resource, stripped) -> {
            corrected.add(resource.getURI());
            ResourceUtils.renameResource(resource, stripped);
        });

        List<Statement> references = model.listStatements()
                .filterKeep(UploadIriNormalizer::isSlashedReference).toList();
        for (Statement stmt : references) {
            String iri = stmt.getObject().asResource().getURI();
            corrected.add(iri);
            model.remove(stmt);
            model.add(stmt.getSubject(), stmt.getPredicate(),
                    model.createResource(UtilityMethods.removeTrailingSlash(iri)));
        }
        return new ArrayList<>(corrected);
    }

    private static boolean isVocabularyOrConcept(Resource subject) {
        return subject.listProperties(RDF.type).toList().stream()
                .map(Statement::getObject)
                .filter(RDFNode::isURIResource)
                .map(RDFNode::asResource)
                .anyMatch(type -> RENAMED_TYPES.contains(type) || RENAMED_OFN_TYPES.contains(type.getURI()));
    }

    private static boolean isSlashedReference(Statement stmt) {
        if (!stmt.getObject().isURIResource()) return false;
        String iri = stmt.getObject().asResource().getURI();
        if (iri.equals(UtilityMethods.removeTrailingSlash(iri))) return false;
        String predicate = stmt.getPredicate().getURI();
        return REFERENCE_PREDICATES.contains(predicate)
                || REFERENCE_LOCAL_NAMES.contains(UtilityMethods.extractNameFromIRI(predicate));
    }
}
