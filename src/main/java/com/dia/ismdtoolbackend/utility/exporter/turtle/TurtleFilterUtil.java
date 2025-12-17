package com.dia.ismdtoolbackend.utility.exporter.turtle;

import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import java.util.Set;

import com.dia.ismdtoolbackend.exception.TurtleExportException;

@Slf4j
public class TurtleFilterUtil {

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

        if (subjectUri == null) {
            return shouldFilterBlankNode(subject);
        }

        if (hasOnlyVocabularyTypes(subject)) {
            log.debug("Filtering resource with only vocabulary types: {}", subjectUri);
            return true;
        }

        return false;
    }

    private static boolean isEmptyLiteralStatement(Statement stmt) {
        if (stmt.getObject().isLiteral()) {
            Literal lit = stmt.getObject().asLiteral();
            String value = lit.getString();
            return value == null || value.trim().isEmpty();
        }
        return false;
    }

    private static boolean shouldFilterBlankNode(Resource blankNode) {
        if (!blankNode.isAnon()) {
            return false;
        }

        Model model = blankNode.getModel();
        StmtIterator typeStatements = model.listStatements(blankNode, RDF.type, (RDFNode) null);

        boolean hasTypes = false;
        boolean allTypesAreVocabulary = true;

        while (typeStatements.hasNext()) {
            Statement typeStmt = typeStatements.next();
            hasTypes = true;

            if (typeStmt.getObject().isResource()) {
                String typeUri = typeStmt.getObject().asResource().getURI();

                if (typeUri != null && !isVocabularyType(typeUri)) {
                    allTypesAreVocabulary = false;
                    break;
                }
            }
        }

        if (hasTypes && allTypesAreVocabulary) {
            log.debug("Filtering blank node with only vocabulary types");
            return true;
        }

        return false;
    }

    private static boolean isVocabularyType(String typeUri) {
        if (typeUri == null) {
            return false;
        }

        return VOCABULARY_URIS_TO_FILTER.contains(typeUri) ||
               typeUri.startsWith("http://www.w3.org/2001/XMLSchema#") ||
               typeUri.startsWith("http://schema.org/");
    }

    private static boolean hasOnlyVocabularyTypes(Resource resource) {
        Model model = resource.getModel();
        StmtIterator typeStatements = model.listStatements(resource, RDF.type, (RDFNode) null);

        boolean hasTypes = false;
        boolean allTypesAreVocabulary = true;

        while (typeStatements.hasNext()) {
            Statement typeStmt = typeStatements.next();
            hasTypes = true;

            if (typeStmt.getObject().isResource()) {
                String typeUri = typeStmt.getObject().asResource().getURI();

                if (typeUri != null && !isVocabularyType(typeUri)) {
                    allTypesAreVocabulary = false;
                    break;
                }
            }
        }

        return hasTypes && allTypesAreVocabulary;
    }
}
