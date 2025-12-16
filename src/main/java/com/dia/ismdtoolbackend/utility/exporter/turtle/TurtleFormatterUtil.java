package com.dia.ismdtoolbackend.utility.exporter.turtle;

import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.dia.ismdtoolbackend.exception.TurtleExportException;

import static com.dia.constants.VocabularyConstants.*;

@Slf4j
public class TurtleFormatterUtil {

    private static final String NADRAZENA_TRIDA = "https://slovník.gov.cz/nadřazená-třída";
    private static final String POJEM_URI = "/pojem/";
    private static final String CONCEPT = "Concept";
    private static final String AGENDOVY_POJEM = "https://slovník.gov.cz/agendový/104/pojem/";
    private static final String SCHEMA = "http://schema.org/";
    private static final String TYP_OBSAHU_UDAJU = "https://slovník.gov.cz/legislativní/sbírka/360/2023/pojem/má-typ-obsahu-údaje";
    private static final String ZPUSOB_SDILENI_UDAJU = "https://slovník.gov.cz/legislativní/sbírka/360/2023/pojem/má-způsob-sdílení-údaje";
    private static final String ZPUSOB_ZISKANI_UDAJU = "https://slovník.gov.cz/legislativní/sbírka/360/2023/pojem/má-způsob-získání-údaje";
    private static final String USTANOVENI_NEVEREJNOST= "https://slovník.gov.cz/legislativní/sbírka/111/2009/pojem/je-vymezen-ustanovení-stanovujícím-jeho-neveřejnost";

    private static final Map<String, String> OFN_PREFIXES = new HashMap<>();

    static {
        OFN_PREFIXES.put("dct", DCT_NS);
        OFN_PREFIXES.put("owl", OWL2.getURI());
        OFN_PREFIXES.put("rdf", RDF.getURI());
        OFN_PREFIXES.put("rdfs", RDFS.getURI());
        OFN_PREFIXES.put("skos", SKOS_NS);
        OFN_PREFIXES.put("slovníky", OFN_NAMESPACE);
        OFN_PREFIXES.put("vsgov", "https://slovník.gov.cz/veřejný-sektor/pojem/");
        OFN_PREFIXES.put("xsd", XSD);
        OFN_PREFIXES.put("čas", CAS_NS);
        OFN_PREFIXES.put("a104", "https://slovník.gov.cz/agendový/104/pojem/");
        OFN_PREFIXES.put("l111-2009", "https://slovník.gov.cz/legislativním/sbírka/111/2009/pojem/");
        OFN_PREFIXES.put("schema", "http://schema.org/");
    }

    private TurtleFormatterUtil() {}

    public static Model transformToOFNFormat(Model filteredModel) {
        if (filteredModel == null) {
            throw new TurtleExportException("Filtered model cannot be null");
        }

        log.debug("Starting OFN format transformation");

        try {
            OntModel ofnModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM);

            setupOFNPrefixes(ofnModel);

            StmtIterator stmtIter = filteredModel.listStatements();
            while (stmtIter.hasNext()) {
                Statement stmt = stmtIter.next();
                ofnModel.add(stmt);
            }

            transformToSKOSConcepts(ofnModel);
            transformPropertiesToOFNFormat(ofnModel);
            transformDescriptionProperties(ofnModel);
            transformConformsToProperties(ofnModel);
            transformSubClassRelationships(ofnModel);
            transformLabelsToSKOS(ofnModel);
            ensureConceptSchemeFormat(ofnModel);

            log.debug("OFN format transformation completed successfully");
            return ofnModel;

        } catch (Exception e) {
            log.error("Error during OFN format transformation: {}", e.getMessage(), e);
            throw new TurtleExportException("Failed to transform to OFN format: " + e.getMessage(), e);
        }
    }

    private static void setupOFNPrefixes(OntModel model) {
        for (Map.Entry<String, String> prefix : OFN_PREFIXES.entrySet()) {
            model.setNsPrefix(prefix.getKey(), prefix.getValue());
        }
    }

    private static void transformToSKOSConcepts(OntModel model) {
        Property slovnikyPojem = model.getProperty(OFN_NAMESPACE + "pojem");
        Property slovnikyTridaProperty = model.getProperty(OFN_NAMESPACE + "třída");
        Property skosConceptProperty = model.getProperty(SKOS_NS + CONCEPT);
        Property skosInScheme = model.getProperty(SKOS_NS + "inScheme");

        List<Resource> classesToTransform = new ArrayList<>();
        StmtIterator iter = model.listStatements(null, RDF.type, OWL2.Class);
        while (iter.hasNext()) {
            Resource subject = iter.next().getSubject();
            if (isConceptResource(subject)) {
                classesToTransform.add(subject);
            }
        }

        for (Resource classResource : classesToTransform) {
            if (!classResource.hasProperty(RDF.type, skosConceptProperty)) {
                classResource.addProperty(RDF.type, skosConceptProperty);
            }
            if (!classResource.hasProperty(RDF.type, slovnikyPojem)) {
                classResource.addProperty(RDF.type, slovnikyPojem);
            }
            if (!classResource.hasProperty(RDF.type, slovnikyTridaProperty)) {
                classResource.addProperty(RDF.type, slovnikyTridaProperty);
            }

            String ontologyIRI = extractOntologyIRI(classResource.getURI());
            if (ontologyIRI != null) {
                Resource conceptScheme = model.getResource(ontologyIRI);
                if (!classResource.hasProperty(skosInScheme)) {
                    classResource.addProperty(skosInScheme, conceptScheme);
                }
            }
        }
    }

    private static void transformPropertiesToOFNFormat(OntModel model) {
        Property slovnikyPojem = model.getProperty(OFN_NAMESPACE + "pojem");
        Property slovnikyVlastnost = model.getProperty(OFN_NAMESPACE + "vlastnost");

        List<Resource> properties = new ArrayList<>();
        StmtIterator iter = model.listStatements(null, RDF.type, OWL2.DatatypeProperty);
        while (iter.hasNext()) {
            properties.add(iter.next().getSubject());
        }

        iter = model.listStatements(null, RDF.type, OWL2.ObjectProperty);
        while (iter.hasNext()) {
            properties.add(iter.next().getSubject());
        }

        for (Resource property : properties) {
            if (property.getURI() != null && property.getURI().contains(POJEM_URI)) {
                if (!property.hasProperty(RDF.type, slovnikyPojem)) {
                    property.addProperty(RDF.type, slovnikyPojem);
                }

                Property slovnikyVztah = model.getProperty(OFN_NAMESPACE + "vztah");
                if (!property.hasProperty(RDF.type, slovnikyVztah) &&
                        !property.hasProperty(RDF.type, slovnikyVlastnost)) {
                    property.addProperty(RDF.type, slovnikyVlastnost);
                }
            }
        }
    }

    private static void transformDescriptionProperties(OntModel model) {
        Property dctermsDescript = model.getProperty(DCT_NS + "description");

        List<Statement> toUpdate = new ArrayList<>();
        StmtIterator iter = model.listStatements(null, dctermsDescript, (RDFNode) null);
        while (iter.hasNext()) {
            Statement stmt = iter.next();
            toUpdate.add(stmt);
        }

        model.setNsPrefix("dct", DCT_NS);

        log.debug("Processed {} description properties", toUpdate.size());
    }

    private static void transformConformsToProperties(OntModel model) {
        Property conformsTo = model.getProperty(DCT_NS + "conformsTo");
        Property ofnDefinujiciUstanoveni = model.getProperty(OFN_NAMESPACE + "definující-ustanovení-právního-předpisu");

        List<Statement> toReplace = new ArrayList<>();
        StmtIterator iter = model.listStatements(null, conformsTo, (RDFNode) null);
        while (iter.hasNext()) {
            toReplace.add(iter.next());
        }

        for (Statement stmt : toReplace) {
            model.remove(stmt);
            model.add(stmt.getSubject(), ofnDefinujiciUstanoveni, stmt.getObject());
        }
    }

    private static void transformSubClassRelationships(OntModel model) {
        Property nadrazenaTrida = model.createProperty(NADRAZENA_TRIDA);

        List<Statement> subClassStatements = new ArrayList<>();
        StmtIterator iter = model.listStatements(null, RDFS.subClassOf, (RDFNode) null);
        while (iter.hasNext()) {
            Statement stmt = iter.next();
            if (stmt.getObject().isResource() &&
                    !stmt.getObject().asResource().getURI().equals(RDFS.Resource.getURI())) {
                subClassStatements.add(stmt);
            }
        }

        for (Statement stmt : subClassStatements) {
            Resource subject = stmt.getSubject();
            Resource object = stmt.getObject().asResource();

            subject.addProperty(nadrazenaTrida, object);
        }
    }

    private static void transformLabelsToSKOS(OntModel model) {
        Property skosPrefLabel = model.getProperty(SKOS_NS + "prefLabel");
        Property skosDefinition = model.getProperty(SKOS_NS + "definition");

        List<Statement> labelStatements = new ArrayList<>();
        StmtIterator iter = model.listStatements(null, RDFS.label, (RDFNode) null);
        while (iter.hasNext()) {
            Statement stmt = iter.next();
            Resource subject = stmt.getSubject();

            if (subject.hasProperty(RDF.type, model.getProperty(SKOS_NS + CONCEPT))) {
                labelStatements.add(stmt);
            }
        }

        for (Statement stmt : labelStatements) {
            model.remove(stmt);
            model.add(stmt.getSubject(), skosPrefLabel, stmt.getObject());
        }
    }

    private static void ensureConceptSchemeFormat(OntModel model) {
        StmtIterator iter = model.listStatements(null, RDF.type, OWL2.Ontology);
        while (iter.hasNext()) {
            Resource ontology = iter.next().getSubject();

            Property skosConceptScheme = model.getProperty(SKOS_NS + "ConceptScheme");
            Property slovnikType = model.getProperty(OFN_NAMESPACE + "slovník");

            if (!ontology.hasProperty(RDF.type, skosConceptScheme)) {
                ontology.addProperty(RDF.type, skosConceptScheme);
            }
            if (!ontology.hasProperty(RDF.type, slovnikType)) {
                ontology.addProperty(RDF.type, slovnikType);
            }
        }
    }

    private static boolean isConceptResource(Resource resource) {
        return resource.getURI() != null &&
               resource.getURI().contains(POJEM_URI) &&
               !isBaseVocabularyClass(resource.getURI());
    }

    private static boolean isBaseVocabularyClass(String uri) {
        return uri.startsWith("http://www.w3.org/") ||
                uri.startsWith("https://slovník.gov.cz/veřejný-sektor/pojem/typ-") ||
                uri.contains("/generický/") ||
                uri.contains("cz:třída") ||
                uri.contains("cz:pojem");
    }

    private static String extractOntologyIRI(String resourceURI) {
        if (resourceURI.contains(POJEM_URI)) {
            return resourceURI.substring(0, resourceURI.lastIndexOf(POJEM_URI));
        }
        return null;
    }
}
