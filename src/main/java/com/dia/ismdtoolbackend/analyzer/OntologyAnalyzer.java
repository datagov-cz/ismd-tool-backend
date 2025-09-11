package com.dia.ismdtoolbackend.analyzer;

import com.dia.ismdtoolbackend.exception.OntologyAnalysisException;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import static com.dia.constants.ArchiConstants.*;

@Component
@Slf4j
public class OntologyAnalyzer {

    public AnalysisResult analyzeUploadedOntology(OntModel uploadedModel) throws OntologyAnalysisException {
        Set<String> requiredBaseClasses = new HashSet<>();
        Set<String> requiredProperties = new HashSet<>();
        try {
            requiredBaseClasses.add(POJEM);

            analyzeTypeDeclarations(uploadedModel, requiredBaseClasses, requiredProperties);
        } catch (Exception e) {
            throw new OntologyAnalysisException(e);
        }
        return new AnalysisResult(requiredBaseClasses, requiredProperties);
    }

    private void analyzeTypeDeclarations(OntModel uploadedModel, Set<String> requiredBaseClasses, Set<String> requiredProperties) throws OntologyAnalysisException, IOException {
        StmtIterator typeStmts = uploadedModel.listStatements();

        while (typeStmts.hasNext()) {
            Statement stmt = typeStmts.next();
            Resource subject = stmt.getSubject();
            Resource type = stmt.getObject().asResource();

            if (type.isURIResource()) {
                String typeUri = type.getURI();
                if (isOFNClassType(typeUri)) {
                    analyzeClassTypeNeeds(typeUri, requiredBaseClasses);
                }

                if (isOFNPropertyType(typeUri)) {
                    analyzePropertyNeeds(subject, requiredProperties);
                }
            }
        }

        ensureClassHierarchy(requiredBaseClasses);
    }

    private boolean isOFNClassType(String uri) {
        return uri.contains("/pojem/třída") ||
                uri.contains("/pojem/typ-subjektu-práva") ||
                uri.contains("/pojem/typ-objektu-práva");
    }

    private void analyzeClassTypeNeeds(String typeUri, Set<String> requiredBaseClasses) {
        if (typeUri.contains("/třída")) {
            requiredBaseClasses.add(TRIDA);
            log.debug("Found třída type, adding TRIDA to required classes");

        } else if (typeUri.contains("/typ-subjektu-práva")) {
            requiredBaseClasses.add(TSP);
            requiredBaseClasses.add(TRIDA);
            log.debug("Found typ-subjektu-práva, adding TSP and TRIDA to required classes");

        } else if (typeUri.contains("/typ-objektu-práva")) {
            requiredBaseClasses.add(TOP);
            requiredBaseClasses.add(TRIDA);
            log.debug("Found typ-objektu-práva, adding TOP and TRIDA to required classes");
        }
    }

    private boolean isOFNPropertyType(String uri) {
        // TODO
        return true;
    }

    private void analyzePropertyNeeds(Resource subject, Set<String> requiredBaseClasses) {
        // TODO
    }

    private void ensureClassHierarchy(Set<String> requiredBaseClasses) {
        // TODO
    }
}
