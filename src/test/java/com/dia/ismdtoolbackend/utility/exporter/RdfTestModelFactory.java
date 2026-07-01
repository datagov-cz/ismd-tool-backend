package com.dia.ismdtoolbackend.utility.exporter;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;

import com.dia.ismdtoolbackend.utility.exporter.json.ModelAnalyzer;
import com.dia.ismdtoolbackend.utility.exporter.json.ModelStructure;

import java.util.Map;

import static com.dia.constants.VocabularyConstants.*;

/**
 * Shared test factory for constructing RDF models used by ConceptProcessor and TurtleFormatterUtil tests.
 */
public final class RdfTestModelFactory {

    public static final String TEST_NS = "https://slovník.gov.cz/datový/test-slovník/";
    public static final String TEST_POJEM_NS = TEST_NS + "pojem/";
    public static final String A104_NS = "https://slovník.gov.cz/agendový/104/pojem/";
    public static final String L111_NS = "https://slovník.gov.cz/legislativní/sbírka/111/2009/pojem/";

    private RdfTestModelFactory() {}

    public static OntModel createOntModelWithVocabulary(String namespace) {
        OntModel model = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM);
        model.setNsPrefix("skos", SKOS_NS);
        model.setNsPrefix("dct", DCT_NS);
        model.setNsPrefix("slovníky", OFN_NAMESPACE);

        Resource ontology = model.createResource(namespace);
        ontology.addProperty(RDF.type, OWL2.Ontology);
        ontology.addProperty(RDF.type, model.createResource(SKOS_NS + "ConceptScheme"));
        ontology.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + "slovník"));

        return model;
    }

    public static OntModel createDefaultModel() {
        return createOntModelWithVocabulary(TEST_NS);
    }

    public static ModelStructure createModelStructure(OntModel model) {
        ModelAnalyzer analyzer = new ModelAnalyzer();
        return analyzer.analyzeModel(model);
    }

    public static Resource addSkosConcept(OntModel model, String localName, String csLabel) {
        Resource concept = model.createResource(TEST_POJEM_NS + localName);
        concept.addProperty(RDF.type, model.createResource(SKOS_NS + "Concept"));
        concept.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + POJEM));
        concept.addProperty(SKOS.prefLabel, model.createLiteral(csLabel, "cs"));
        return concept;
    }

    public static Resource addOwlClass(OntModel model, String localName, String csLabel) {
        Resource concept = addSkosConcept(model, localName, csLabel);
        concept.addProperty(RDF.type, OWL2.Class);
        concept.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + TRIDA));
        return concept;
    }

    public static Resource addObjectProperty(OntModel model, String localName, String csLabel,
                                              Resource domain, Resource range) {
        Resource concept = addSkosConcept(model, localName, csLabel);
        concept.addProperty(RDF.type, OWL2.ObjectProperty);
        concept.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + VZTAH));
        if (domain != null) {
            concept.addProperty(RDFS.domain, domain);
        }
        if (range != null) {
            concept.addProperty(RDFS.range, range);
        }
        return concept;
    }

    public static Resource addDatatypeProperty(OntModel model, String localName, String csLabel,
                                                Resource domain, String xsdRange) {
        Resource concept = addSkosConcept(model, localName, csLabel);
        concept.addProperty(RDF.type, OWL2.DatatypeProperty);
        concept.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + VLASTNOST));
        if (domain != null) {
            concept.addProperty(RDFS.domain, domain);
        }
        if (xsdRange != null) {
            concept.addProperty(RDFS.range, model.createResource(XSD + xsdRange));
        }
        return concept;
    }

    public static void addMultilingualLabel(Resource resource, Property property,
                                             Map<String, String> langValues, OntModel model) {
        for (Map.Entry<String, String> entry : langValues.entrySet()) {
            resource.addProperty(property, model.createLiteral(entry.getValue(), entry.getKey()));
        }
    }

    public static void addSubClassRelationship(Resource child, Resource parent) {
        child.addProperty(RDFS.subClassOf, parent);
    }

    public static void addSkosBroader(Resource child, Resource parent) {
        child.addProperty(SKOS.broader, parent);
    }

    public static void addSubPropertyRelationship(Resource child, Resource parent) {
        child.addProperty(RDFS.subPropertyOf, parent);
    }

    public static void addGovernanceProperties(Resource concept, OntModel model,
                                                String sharingMethod, String acquisitionMethod,
                                                String contentType) {
        if (sharingMethod != null) {
            Property prop = model.createProperty(OFN_NAMESPACE + ZPUSOB_SDILENI);
            concept.addProperty(prop, sharingMethod);
        }
        if (acquisitionMethod != null) {
            Property prop = model.createProperty(OFN_NAMESPACE + ZPUSOB_ZISKANI);
            concept.addProperty(prop, acquisitionMethod);
        }
        if (contentType != null) {
            Property prop = model.createProperty(OFN_NAMESPACE + TYP_OBSAHU);
            concept.addProperty(prop, contentType);
        }
    }

    public static void addMetadataProperties(Resource concept, OntModel model,
                                              Boolean isPpdf, String agenda, String ais) {
        if (isPpdf != null) {
            Property ppdfProp = model.createProperty(A104_NS + JE_PPDF_LONG);
            concept.addLiteral(ppdfProp, isPpdf);
        }
        if (agenda != null) {
            Property agendaProp = model.createProperty(A104_NS + AGENDA_LONG);
            concept.addProperty(agendaProp, agenda);
        }
        if (ais != null) {
            Property aisProp = model.createProperty(A104_NS + UDAJE_AIS);
            concept.addProperty(aisProp, ais);
        }
    }

    public static Resource addDigitalDocument(OntModel model, String iri, String titleCs, String url) {
        return addDigitalDocument(model, iri, titleCs, null, url);
    }

    public static Resource addDigitalDocument(OntModel model, String iri, String titleCs, String descriptionCs, String url) {
        Resource doc = model.createResource(iri);
        doc.addProperty(RDF.type, model.createResource(DIGITALNI_OBJEKT));
        if (titleCs != null) {
            Property titleProp = model.createProperty(DCT_NS + "title");
            doc.addProperty(titleProp, model.createLiteral(titleCs, "cs"));
        }
        if (descriptionCs != null) {
            Property descProp = model.createProperty(DCT_NS + "description");
            doc.addProperty(descProp, model.createLiteral(descriptionCs, "cs"));
        }
        if (url != null) {
            Property urlProp = model.createProperty(SCHEMA_URL);
            doc.addProperty(urlProp, model.createResource(url));
        }
        return doc;
    }

    public static void addExactMatch(Resource concept, OntModel model, String targetIri) {
        Property exactMatch = model.createProperty(SKOS_NS + "exactMatch");
        concept.addProperty(exactMatch, model.createResource(targetIri));
    }

    public static void addDefinition(Resource concept, OntModel model, String value, String lang) {
        Property definition = model.createProperty(SKOS_NS + "definition");
        concept.addProperty(definition, model.createLiteral(value, lang));
    }

    public static void addDescription(Resource concept, OntModel model, String value, String lang) {
        Property description = model.createProperty(DCT_NS + "description");
        concept.addProperty(description, model.createLiteral(value, lang));
    }

    public static void addSourceProperty(Resource concept, OntModel model,
                                          String propertyLocalName, Resource target) {
        Property prop = model.createProperty(OFN_NAMESPACE + propertyLocalName);
        concept.addProperty(prop, target);
    }

    public static void addUstanoveniNeverejnost(Resource concept, OntModel model, String targetIri) {
        // Mirror the production writers (ConceptCreator / ConceptFieldUpdaters), which
        // store this provision under OFN_NAMESPACE_LEGAL + USTANOVENI_NEVEREJNOST.
        Property prop = model.createProperty(L111_NS + USTANOVENI_NEVEREJNOST);
        concept.addProperty(prop, model.createResource(targetIri));
    }

    public static Resource addConceptWithType(OntModel model, String localName, String csLabel, String ofnType) {
        Resource concept = addSkosConcept(model, localName, csLabel);
        concept.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + ofnType));
        return concept;
    }

    public static Resource addConceptWithLegalType(OntModel model, String localName, String csLabel, String legalType) {
        Resource concept = addSkosConcept(model, localName, csLabel);
        concept.addProperty(RDF.type, model.createResource(OFN_NAMESPACE_LEGAL + legalType));
        return concept;
    }
}
