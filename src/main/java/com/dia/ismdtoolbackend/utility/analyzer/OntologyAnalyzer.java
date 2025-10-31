package com.dia.ismdtoolbackend.utility.analyzer;

import com.dia.ismdtoolbackend.exception.OntologyAnalysisException;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntClass;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntProperty;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.dia.constants.VocabularyConstants.*;


@Component
@Slf4j
public class OntologyAnalyzer {

    private static final String POJEM_GENERIC_IRI = "https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/pojem";
    private static final String TSP_GENERIC_IRI = "https://slovník.gov.cz/veřejný-sektor/pojem/typ-subjektu-práva";
    private static final String TOP_GENERIC_IRI = "https://slovník.gov.cz/veřejný-sektor/pojem/typ-objektu-práva";

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

    private void analyzeTypeDeclarations(OntModel uploadedModel, Set<String> requiredBaseClasses, Set<String> requiredProperties) throws OntologyAnalysisException {
        ExtendedIterator<OntClass> classes = uploadedModel.listClasses();
        List<String> classURIs = new ArrayList<>();

        while (classes.hasNext()) {
            OntClass ontClass = classes.next();
            
            if (ontClass.isURIResource()) {
                String classURI = ontClass.getURI();
                classURIs.add(classURI);

                analyzeClassForOFNRequirements(ontClass, classURI, requiredBaseClasses);
            }
        }

        for (String classURI : classURIs) {
            log.debug("CLASS URI EXTRACTED - {}", classURI);
        }
        
        analyzePropertiesForOFNRequirements(uploadedModel, requiredProperties);

        ensureClassHierarchy(requiredBaseClasses);
    }

    private void analyzeClassForOFNRequirements(OntClass ontClass, String classURI, Set<String> requiredBaseClasses) {
        String localName = getLocalName(classURI);
        
        log.debug("Analyzing class: {} (localName: {})", classURI, localName);
        
        if (isOFNClass(classURI, localName)) {
            log.debug("Class {} identified as OFN class, analyzing requirements", classURI);
            determineRequiredClassesFromLabels(ontClass, classURI, requiredBaseClasses);
        } else {
            log.debug("Class {} not identified as OFN class", classURI);
        }
        
        ExtendedIterator<OntClass> superClasses = ontClass.listSuperClasses(true);
        while (superClasses.hasNext()) {
            OntClass superClass = superClasses.next();
            if (superClass.isURIResource()) {
                String superClassURI = superClass.getURI();
                String superLocalName = getLocalName(superClassURI);
                log.debug("Checking superclass: {} (localName: {})", superClassURI, superLocalName);
                if (isOFNClass(superClassURI, superLocalName)) {
                    log.debug("Superclass {} identified as OFN class, analyzing requirements", superClassURI);
                    determineRequiredClassesFromLabels(superClass, superClassURI, requiredBaseClasses);
                }
            }
        }
    }
    
    private void analyzePropertiesForOFNRequirements(OntModel uploadedModel, Set<String> requiredProperties) {
        ExtendedIterator<OntProperty> properties = uploadedModel.listOntProperties();
        
        while (properties.hasNext()) {
            OntProperty ontProperty = properties.next();
            if (ontProperty.isURIResource()) {
                String propertyURI = ontProperty.getURI();
                String localName = getLocalName(propertyURI);
                
                if (isOFNProperty(propertyURI, localName)) {
                    requiredProperties.add(localName);
                    log.debug("Found OFN property: {}", localName);
                }
            }
        }
    }
    
    private boolean isOFNClass(String classURI, String localName) {
        boolean isOFN = OFNSets.OFN_NAMESPACES.stream().anyMatch(classURI::startsWith) ||
                OFNSets.OFN_CLASSES.contains(localName);

        if (isOFN) {
            log.debug("Class {} (localName: {}) identified as OFN class", classURI, localName);
        }

        return isOFN;
    }
    
    private boolean isOFNProperty(String propertyURI, String localName) {
        return OFNSets.OFN_NAMESPACES.stream().anyMatch(propertyURI::startsWith) ||
                OFNSets.OFN_SPECIAL_PROPERTIES.contains(propertyURI) ||
                OFNSets.OFN_PROPERTIES.contains(localName);
    }
    
    private void determineRequiredClassesFromLabels(OntClass ontClass, String classURI, Set<String> requiredBaseClasses) {
        log.debug("Determining required classes from RDF analysis for URI: {}", classURI);
        analyzeRDFTypeStatements(ontClass, requiredBaseClasses);
    }
    
    private void analyzeRDFTypeStatements(OntClass ontClass, Set<String> requiredBaseClasses) {
        log.debug("Analyzing rdf:type statements for class: {}", ontClass.getURI());
        
        StmtIterator typeIter = ontClass.listProperties(org.apache.jena.vocabulary.RDF.type);
        while (typeIter.hasNext()) {
            Statement stmt = typeIter.next();
            if (stmt.getObject().isURIResource()) {
                String typeURI = stmt.getObject().asResource().getURI();
                log.debug("Found rdf:type: {} -> {}", ontClass.getURI(), typeURI);
                
                matchTypeURIToOFNBaseClass(typeURI, requiredBaseClasses);
            }
        }
    }
    
    private void matchTypeURIToOFNBaseClass(String typeURI, Set<String> requiredBaseClasses) {
        log.debug("Matching type URI '{}' to OFN base classes", typeURI);
        
        if (typeURI.equals(POJEM_GENERIC_IRI)) {
            log.debug("Type URI matches POJEM base class");
        } else if (typeURI.equals(TSP_GENERIC_IRI)) {
            log.debug("Type URI matches TSP base class");
            addRequiredClass(requiredBaseClasses, TSP, TSP);
            addRequiredClass(requiredBaseClasses, TRIDA, TRIDA);
        } else if (typeURI.equals(TOP_GENERIC_IRI)) {
            log.debug("Type URI matches TOP base class");
            addRequiredClass(requiredBaseClasses, TOP, TOP);
            addRequiredClass(requiredBaseClasses, TRIDA, TRIDA);
        } else if (typeURI.contains("/pojem/údaj")) {
            log.debug("Type URI appears to be an UDAJ variant");
            addRequiredClass(requiredBaseClasses, UDAJ, UDAJ);
        } else if (typeURI.contains("datový-slovník-ofn") && typeURI.contains("/pojem/")) {
            log.debug("Type URI appears to be a generic OFN concept, ensuring POJEM base class");
        } else {
            log.debug("Type URI '{}' does not match known OFN base class patterns", typeURI);
        }
    }
    
    private void ensureClassHierarchy(Set<String> requiredBaseClasses) {
        if (requiredBaseClasses.contains(TRIDA)) {
            requiredBaseClasses.add(POJEM);
        }
        if (requiredBaseClasses.contains(TSP)) {
            requiredBaseClasses.add(TRIDA);
        }
        if (requiredBaseClasses.contains(TOP)) {
            requiredBaseClasses.add(TRIDA);
        }
        if (requiredBaseClasses.contains(UDAJ)) {
            requiredBaseClasses.add(POJEM);
        }
        if ((requiredBaseClasses.contains(VEREJNY_UDAJ) || requiredBaseClasses.contains(NEVEREJNY_UDAJ))) {
            requiredBaseClasses.add(UDAJ);
        }
        if ((requiredBaseClasses.contains(ZPUSOB_SDILENI_UDAJE) || requiredBaseClasses.contains(ZPUSOB_ZISKANI_UDAJE))) {
            requiredBaseClasses.add(POLOZKA_CISELNIKU);
        }
    }

    private String getLocalName(String uri) {
        if (uri.contains("#")) {
            return uri.substring(uri.lastIndexOf('#') + 1);
        } else if (uri.contains("/")) {
            return uri.substring(uri.lastIndexOf('/') + 1);
        }
        return uri;
    }
    
    private void addRequiredClass(Set<String> requiredBaseClasses, String className, String displayName) {
        if (!requiredBaseClasses.contains(className)) {
            requiredBaseClasses.add(className);
            log.debug("Found {} class, adding {} to required classes", displayName, className);
        }
    }
}
