package com.dia.ismdtoolbackend.exporter;

import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import java.util.Set;

@Slf4j
public class TurtleExporter {

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
            "http://www.w3.org/2004/02/skos/core#ConceptScheme"
    );

    private TurtleExporter() {}

    public static Model createFilteredModel(Model originalModel) {
        Model filteredModel = ModelFactory.createDefaultModel();
        filteredModel.setNsPrefixes(originalModel.getNsPrefixMap());

        StmtIterator stmtIter = originalModel.listStatements();
        int originalCount = 0;
        int filteredCount = 0;

        while (stmtIter.hasNext()) {
            Statement stmt = stmtIter.next();
            originalCount++;

            if (shouldFilterStatement(stmt)) {
                filteredCount++;
                log.debug("Filtering statement: {}", stmt);
                continue;
            }

            filteredModel.add(stmt);
        }

        log.debug("Filtered {} out of {} statements", filteredCount, originalCount);
        return filteredModel;
    }

    public static boolean shouldFilterStatement(Statement stmt) {
        Resource subject = stmt.getSubject();
        String subjectUri = subject.getURI();

        if (isVocabularyDefinition(subjectUri)) {
            return true;
        }

        if (isEmptyLiteralStatement(stmt)) {
            return true;
        }

        if (isVocabularySelfReference(stmt)) {
            return true;
        }

        return isBaseSchemaResource(subjectUri);
    }

    private static boolean isVocabularyDefinition(String uri) {
        if (uri == null) return false;

        return VOCABULARY_URIS_TO_FILTER.contains(uri) ||
                uri.startsWith("http://www.w3.org/2001/XMLSchema#") ||
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

    private static boolean isBaseSchemaResource(String uri) {
        if (uri == null) return false;

        if (uri.startsWith("http://www.w3.org/2001/XMLSchema#") ||
                uri.startsWith("http://schema.org/")) {
            return true;
        }

        if (uri.startsWith("https://slovník.gov.cz/")) {
            if (uri.startsWith("https://slovník.gov.cz/datový") ||
                    uri.startsWith("https://slovník.gov.cz/legislativní") ||
                    uri.startsWith("https://slovník.gov.cz/veřejný-sektor")) {
                return false;
            }

            return uri.contains("/datový-slovník-ofn-slovníků/pojem/");
        }

        return false;
    }
}
