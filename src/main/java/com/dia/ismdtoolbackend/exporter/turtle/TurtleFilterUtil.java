package com.dia.ismdtoolbackend.exporter.turtle;

import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import java.util.Set;

import com.dia.ismdtoolbackend.exception.TurtleExportException;

@Slf4j
public class TurtleFilterUtil {

    private static final Set<String> EXPLICIT_BASE_FRAMEWORK_URIS = Set.of(
            "https://slovník.gov.cz/pojem",
            "https://slovník.gov.cz/třída",
            "https://slovník.gov.cz/typ-subjektu-práva",
            "https://slovník.gov.cz/typ-objektu-práva",
            "https://slovník.gov.cz/datový-typ",
            "https://slovník.gov.cz/údaj",
            "https://slovník.gov.cz/veřejný-údaj",
            "https://slovník.gov.cz/neveřejný-údaj",
            "https://slovník.gov.cz/způsob-sdílení-údaje",
            "https://slovník.gov.cz/způsob-získání-údaje",
            "https://slovník.gov.cz/číselník",
            "https://slovník.gov.cz/položka-číselníku",
            "https://slovník.gov.cz/typ-vlastnosti",
            "http://www.w3.org/2004/02/skos/core#Concept"
    );

    private static final Set<String> VOCABULARY_URIS_TO_FILTER = Set.of(
            // RDF vocabulary
            RDF.getURI() + "type",
            RDF.getURI() + "Property",
            RDF.getURI() + "Statement",
            RDF.getURI() + "subject",
            RDF.getURI() + "predicate",
            RDF.getURI() + "object",
            RDF.getURI() + "List",
            RDF.getURI() + "nil",
            RDF.getURI() + "first",
            RDF.getURI() + "rest",
            RDF.getURI() + "Seq",
            RDF.getURI() + "Bag",
            RDF.getURI() + "Alt",
            RDF.getURI() + "XMLLiteral",

            RDFS.getURI() + "Resource",
            RDFS.getURI() + "Class",
            RDFS.getURI() + "subClassOf",
            RDFS.getURI() + "subPropertyOf",
            RDFS.getURI() + "domain",
            RDFS.getURI() + "range",
            RDFS.getURI() + "label",
            RDFS.getURI() + "comment",
            RDFS.getURI() + "Literal",
            RDFS.getURI() + "Datatype",
            RDFS.getURI() + "Container",
            RDFS.getURI() + "ContainerMembershipProperty",
            RDFS.getURI() + "seeAlso",
            RDFS.getURI() + "isDefinedBy",

            // OWL vocabulary
            OWL2.getURI() + "Class",
            OWL2.getURI() + "DatatypeProperty",
            OWL2.getURI() + "ObjectProperty",
            OWL2.getURI() + "Ontology",

            // SKOS vocabulary
            "http://www.w3.org/2004/02/skos/core#ConceptScheme",

            // OFN definitions
            "https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/slovník",
            "https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/pojem",
            "https://slovník.gov.cz/veřejný-sektor/pojem/typ-objektu-práva",
            "https://slovník.gov.cz/veřejný-sektor/pojem/typ-subjektu-práva"
    );

    private TurtleFilterUtil() {}

    public static Model createFilteredModel(Model originalModel) {
        if (originalModel == null) {
            throw new TurtleExportException("Original model cannot be null");
        }

        log.debug("Starting model filtering");

        try {
            Model filteredModel = ModelFactory.createDefaultModel();
            filteredModel.setNsPrefixes(originalModel.getNsPrefixMap());

            StmtIterator stmtIter = originalModel.listStatements();
            int originalCount = 0;
            int filteredCount = 0;

            while (stmtIter.hasNext()) {
                Statement stmt = stmtIter.next();
                originalCount++;

                if (shouldFilterStatement(stmt) || isEmptyLiteralStatement(stmt)) {
                    filteredCount++;
                    log.debug("Filtering statement: {}", stmt);
                    continue;
                }

                filteredModel.add(stmt);
            }

            log.debug("Model filtering completed. Filtered {} out of {} statements", filteredCount, originalCount);
            return filteredModel;

        } catch (Exception e) {
            log.error("Error during model filtering: {}", e.getMessage(), e);
            throw new TurtleExportException("Failed to filter model: " + e.getMessage(), e);
        }
    }

    public static boolean shouldFilterStatement(Statement stmt) {
        Resource subject = stmt.getSubject();
        String subjectUri = subject.getURI();

        if (EXPLICIT_BASE_FRAMEWORK_URIS.contains(subjectUri)) {
            log.debug("Filtering statement about base framework class: {}", subjectUri);
            return true;
        }

        if (isVocabularyDefinition(subjectUri)) {
            return true;
        }


        if (isVocabularySelfReference(stmt)) {
            return true;
        }

        if (isBaseSchemaResource(subjectUri)) {
            return true;
        }

        if (stmt.getObject().isResource()) {
            String objectUri = stmt.getObject().asResource().getURI();
            Property predicate = stmt.getPredicate();

            if ("https://slovník.gov.cz/nadřazená-třída".equals(predicate.getURI()) &&
                    EXPLICIT_BASE_FRAMEWORK_URIS.contains(objectUri)) {
                log.debug("Filtering nadřazená-třída relationship to base class: {} -> {}", subjectUri, objectUri);
                return true;
            }

            if (RDFS.subClassOf.equals(predicate) &&
                    EXPLICIT_BASE_FRAMEWORK_URIS.contains(objectUri) &&
                    !objectUri.contains("/legislativní/") &&
                    !objectUri.contains("/agendový/")) {
                log.debug("Filtering subClassOf relationship to base framework class: {} -> {}", subjectUri, objectUri);
                return true;
            }
        }

        return false;
    }

    private static boolean isBaseSchemaResource(String uri) {
        if (uri == null) return false;

        if (EXPLICIT_BASE_FRAMEWORK_URIS.contains(uri)) {
            log.debug("Filtering explicit base framework URI: {}", uri);
            return true;
        }

        if (uri.startsWith("http://www.w3.org/2001/XMLSchema#")) {
            return true;
        }

        if (uri.startsWith("http://schema.org/")) {
            return true;
        }

        if (uri.startsWith("https://slovník.gov.cz/")) {

            if (uri.startsWith("https://slovník.gov.cz/datový") ||
                    uri.startsWith("https://slovník.gov.cz/legislativní") ||
                    uri.startsWith("https://slovník.gov.cz/veřejný-sektor")) {
                return false;
            }

            if (uri.contains("/datový-slovník-ofn-slovníků/pojem/")) {
                return true;
            }

            if (uri.startsWith("https://slovník.gov.cz/agendový") && !uri.contains("/pojem/")) {
                log.debug("Filtering out base schema property: {}", uri);
                return true;
            }

            if (uri.startsWith("https://slovník.gov.cz/agendový") && uri.contains("/pojem/")) {
                return false;
            }

            if (uri.startsWith("https://slovník.gov.cz/generický") ||
                    (uri.startsWith("https://slovník.gov.cz/") &&
                            !uri.contains("/pojem/") &&
                            !uri.contains("/slovník") &&
                            !uri.contains("/legislativní/") &&
                            !uri.contains("/datový/") &&
                            !uri.contains("/agendový/"))) {
                log.debug("Filtering out base schema property: {}", uri);
                return true;
            }
        }

        return false;
    }

    private static boolean isVocabularyDefinition(String uri) {
        if (uri == null) return false;

        if (VOCABULARY_URIS_TO_FILTER.contains(uri)) {
            return true;
        }

        if (EXPLICIT_BASE_FRAMEWORK_URIS.contains(uri)) {
            return true;
        }

        return uri.startsWith("http://www.w3.org/2001/XMLSchema#") ||
                uri.startsWith("http://schema.org/");
    }

    private static boolean isEmptyLiteralStatement(Statement stmt) {
        if (stmt.getObject().isLiteral()) {
            Literal lit = stmt.getObject().asLiteral();
            String value = lit.getString();
            return value == null || value.trim().isEmpty();
        }
        return false;
    }

    private static boolean isVocabularySelfReference(Statement stmt) {
        Resource subject = stmt.getSubject();
        Property predicate = stmt.getPredicate();

        if (predicate.equals(RDFS.subPropertyOf) &&
                stmt.getObject().isResource() &&
                subject.equals(stmt.getObject().asResource())) {
            return true;
        }

        return predicate.equals(RDFS.subClassOf) &&
                stmt.getObject().isResource() &&
                subject.equals(stmt.getObject().asResource());
    }
}
