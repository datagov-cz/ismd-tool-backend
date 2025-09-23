package com.dia.ismdtoolbackend.exporter.json;

import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import java.util.*;

import static com.dia.ismdtoolbackend.constants.OFNJsonConstants.*;

@Slf4j
public class ModelAnalyzer {

    public ModelStructure analyzeModel(Model model) {
        log.debug("Analyzing model structure - extracting metadata from RDF");

        Resource vocabularyResource = findVocabularyResource(model);
        String effectiveNamespace = determineEffectiveNamespace(model, vocabularyResource);
        String ontologyIRI = extractOntologyIRI(model, vocabularyResource);
        Map<String, Resource> resourceMap = buildResourceMap(model);

        String modelName = extractModelName(vocabularyResource, model);
        String modelDescription = extractModelDescription(vocabularyResource, model);
        String creationDate = extractTemporalMetadata(vocabularyResource, model, OKAMZIK_VYTVORENI);
        String modificationDate = extractTemporalMetadata(vocabularyResource, model, OKAMZIK_POSLEDNI_ZMENY);

        List<String> vocabularyTypes = determineVocabularyTypes(model);

        return ModelStructure.builder()
                .modelName(modelName)
                .modelDescription(modelDescription)
                .effectiveNamespace(effectiveNamespace)
                .ontologyIRI(ontologyIRI)
                .vocabularyResource(vocabularyResource)
                .resourceMap(resourceMap)
                .creationDate(creationDate)
                .modificationDate(modificationDate)
                .vocabularyTypes(vocabularyTypes)
                .build();
    }

    private String determineEffectiveNamespace(Model model, Resource vocabularyResource) {
        if (vocabularyResource != null && vocabularyResource.getURI() != null) {
            String uri = vocabularyResource.getURI();
            int lastSlash = uri.lastIndexOf('/');
            if (lastSlash > 0) {
                return uri.substring(0, lastSlash + 1);
            }
        }

        Map<String, String> prefixes = model.getNsPrefixMap();
        for (Map.Entry<String, String> entry : prefixes.entrySet()) {
            String prefix = entry.getKey();
            String namespace = entry.getValue();

            if (!prefix.isEmpty() && !isStandardPrefix(prefix)) {
                return namespace;
            }
        }

        return DEFAULT_NS;
    }

    private boolean isStandardPrefix(String prefix) {
        return Arrays.asList("rdf", "rdfs", "owl", "skos", "dct", "xsd", "slovníky", "čas").contains(prefix);
    }

    private String extractOntologyIRI(Model model, Resource vocabularyResource) {
        if (vocabularyResource != null && vocabularyResource.getURI() != null) {
            return vocabularyResource.getURI();
        }

        StmtIterator iter = model.listStatements(null, RDF.type, OWL2.Ontology);
        if (iter.hasNext()) {
            Resource ontologyResource = iter.next().getSubject();
            return ontologyResource.getURI();
        }

        return null;
    }

    private String extractModelName(Resource vocabularyResource, Model model) {
        if (vocabularyResource == null) {
            return null;
        }

        Property[] nameProperties = {
                model.createProperty(RDFS.getURI() + "label"),
                model.createProperty(SKOS_NS + "prefLabel")
        };

        for (Property nameProperty : nameProperties) {
            if (vocabularyResource.hasProperty(nameProperty)) {
                Statement stmt = vocabularyResource.getProperty(nameProperty);
                if (stmt.getObject().isLiteral()) {
                    String value = stmt.getString();
                    if (value != null && !value.trim().isEmpty()) {
                        return value;
                    }
                }
            }
        }

        return null;
    }

    private String extractModelDescription(Resource vocabularyResource, Model model) {
        if (vocabularyResource == null) {
            return null;
        }

        Property[] descriptionProperties = {
                model.createProperty(DCT_NS + "description"),
                model.createProperty(SKOS_NS + "definition")
        };

        for (Property descProperty : descriptionProperties) {
            if (vocabularyResource.hasProperty(descProperty)) {
                Statement stmt = vocabularyResource.getProperty(descProperty);
                if (stmt.getObject().isLiteral()) {
                    String value = stmt.getString();
                    if (value != null && !value.trim().isEmpty()) {
                        return value;
                    }
                }
            }
        }

        return null;
    }

    private Resource findVocabularyResource(Model model) {
        StmtIterator iter = model.listStatements(null, RDF.type, OWL2.Ontology);
        if (iter.hasNext()) {
            return iter.next().getSubject();
        }

        Property conceptScheme = model.createProperty(SKOS_NS + "ConceptScheme");
        iter = model.listStatements(null, RDF.type, conceptScheme);
        if (iter.hasNext()) {
            return iter.next().getSubject();
        }

        return null;
    }

    private Map<String, Resource> buildResourceMap(Model model) {
        Map<String, Resource> resourceMap = new HashMap<>();

        StmtIterator iter = model.listStatements(null, RDF.type, OWL2.Ontology);
        if (iter.hasNext()) {
            resourceMap.put("ontology", iter.next().getSubject());
        }

        addConceptResources(model, resourceMap);

        return resourceMap;
    }

    private void addConceptResources(Model model, Map<String, Resource> resourceMap) {
        String[] conceptTypes = {POJEM, TRIDA, VZTAH, VLASTNOST, TSP, TOP, VEREJNY_UDAJ, NEVEREJNY_UDAJ};

        for (String conceptType : conceptTypes) {
            Resource typeResource = model.getResource(OFN_NAMESPACE + conceptType);
            StmtIterator iter = model.listStatements(null, RDF.type, typeResource);

            while (iter.hasNext()) {
                Resource resource = iter.next().getSubject();
                if (resource.getURI() != null) {
                    resourceMap.put(resource.getURI(), resource);
                }
            }
        }

        Property skosConceptProperty = model.createProperty(SKOS_NS + "Concept");
        StmtIterator iter = model.listStatements(null, RDF.type, skosConceptProperty);
        while (iter.hasNext()) {
            Resource resource = iter.next().getSubject();
            if (resource.getURI() != null) {
                resourceMap.put(resource.getURI(), resource);
            }
        }
    }

    private String extractTemporalMetadata(Resource vocabularyResource, Model model, String propertyName) {
        if (vocabularyResource == null) {
            return null;
        }

        Property temporalProperty = model.getProperty(SLOVNIKY_NS + propertyName);
        if (!vocabularyResource.hasProperty(temporalProperty)) {
            return null;
        }

        Statement stmt = vocabularyResource.getProperty(temporalProperty);
        if (stmt.getObject().isResource()) {
            Resource instantResource = stmt.getObject().asResource();
            return extractTemporalValue(instantResource, model);
        }

        return null;
    }

    private String extractTemporalValue(Resource instantResource, Model model) {
        Property dateTimeProperty = model.getProperty(CAS_NS + DATUM_A_CAS);
        if (instantResource.hasProperty(dateTimeProperty)) {
            Statement dateTimeStmt = instantResource.getProperty(dateTimeProperty);
            if (dateTimeStmt.getObject().isLiteral()) {
                return dateTimeStmt.getString();
            }
        }

        Property dateProperty = model.getProperty(CAS_NS + DATUM);
        if (instantResource.hasProperty(dateProperty)) {
            Statement dateStmt = instantResource.getProperty(dateProperty);
            if (dateStmt.getObject().isLiteral()) {
                return dateStmt.getString();
            }
        }

        return null;
    }

    private List<String> determineVocabularyTypes(Model model) {
        List<String> types = new ArrayList<>();

        types.add(TYPE_SLOVNIK);

        boolean hasPojem = hasConceptOfType(model, POJEM);

        boolean hasConceptualModelTypes = hasConceptOfType(model, TSP) ||
                                         hasConceptOfType(model, TOP) ||
                                         hasConceptOfType(model, VLASTNOST) ||
                                         hasConceptOfType(model, VZTAH);

        if (hasPojem || hasConceptualModelTypes) {
            types.add(TYPE_TEZAURUS);
        }

        if (hasConceptualModelTypes) {
            types.add(TYPE_KM);
        }

        return types;
    }

    private boolean hasConceptOfType(Model model, String conceptType) {
        Resource typeResource = model.getResource(OFN_NAMESPACE + conceptType);
        StmtIterator iter = model.listStatements(null, RDF.type, typeResource);
        return iter.hasNext();
    }
}
