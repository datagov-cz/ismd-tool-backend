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

    // Namespace constants (RDF.uri, not RDF.getURI()): reading them loads no Jena class, so this
    // initialiser is safe even when it is the first Jena use in the JVM.
    private static final Set<String> VOCABULARY_URIS_TO_FILTER = Set.of(
            // RDF vocabulary
            RDF.uri +"type",
            RDF.uri +"Property",
            RDF.uri +"Statement",
            RDF.uri +"subject",
            RDF.uri +"predicate",
            RDF.uri +"object",
            RDF.uri +"List",
            RDF.uri +"nil",
            RDF.uri +"first",
            RDF.uri +"rest",
            RDF.uri +"Seq",
            RDF.uri +"Bag",
            RDF.uri +"Alt",
            RDF.uri +"XMLLiteral",

            RDFS.uri +"Resource",
            RDFS.uri +"Class",
            RDFS.uri +"subClassOf",
            RDFS.uri +"subPropertyOf",
            RDFS.uri +"domain",
            RDFS.uri +"range",
            RDFS.uri +"label",
            RDFS.uri +"comment",
            RDFS.uri +"Literal",
            RDFS.uri +"Datatype",
            RDFS.uri +"Container",
            RDFS.uri +"ContainerMembershipProperty",
            RDFS.uri +"seeAlso",
            RDFS.uri +"isDefinedBy",

            // OWL vocabulary
            OWL2.NS +"Class",
            OWL2.NS +"DatatypeProperty",
            OWL2.NS +"ObjectProperty",
            OWL2.NS +"Ontology",

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

        if (isOntologyResource(subject)) {
            return false;
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

    private static boolean isOntologyResource(Resource resource) {
        if (resource == null || !resource.isURIResource()) {
            return false;
        }

        Model model = resource.getModel();
        StmtIterator typeStatements = model.listStatements(resource, RDF.type, (RDFNode) null);

        while (typeStatements.hasNext()) {
            Statement typeStmt = typeStatements.next();
            if (typeStmt.getObject().isResource()) {
                String typeUri = typeStmt.getObject().asResource().getURI();
                if (OWL2.Ontology.getURI().equals(typeUri)) {
                    return true;
                }
            }
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
