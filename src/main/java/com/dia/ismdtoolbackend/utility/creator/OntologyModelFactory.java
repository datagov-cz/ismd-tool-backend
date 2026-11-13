package com.dia.ismdtoolbackend.utility.creator;

import com.dia.ismdtoolbackend.models.OntologyCreateModel;
import com.dia.ismdtoolbackend.ofn.OfnDates;
import com.dia.ismdtoolbackend.ofn.OfnIris;
import com.dia.ismdtoolbackend.utility.DataTypeConverter;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;

import java.time.Clock;
import java.util.Map;

import static com.dia.ismdtoolbackend.constants.ExportConstants.Common.DEFAULT_LANG;

/** Builds a new vocabulary's own triples: its three types, name, description and creation time. */
public final class OntologyModelFactory {
    private OntologyModelFactory() {}

    public static OntModel create(String ontologyIRI, OntologyCreateModel ontologyCreateModel) {
        return create(ontologyIRI, ontologyCreateModel, Clock.systemUTC());
    }

    static OntModel create(String ontologyIRI, OntologyCreateModel ontologyCreateModel, Clock clock) {
        OntModel model = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM);
        model.setNsPrefix("cz", "https://slovník.gov.cz/");
        model.setNsPrefix("rdf", RDF.getURI());
        model.setNsPrefix("rdfs", RDFS.getURI());
        model.setNsPrefix("owl", OWL2.getURI());
        model.setNsPrefix("xsd", OfnIris.XSD);
        model.setNsPrefix("čas", OfnIris.CAS);
        model.setNsPrefix("slovníky", OfnIris.SLOVNIKY);

        model.createOntology(ontologyIRI);
        Resource ontologyResource = model.getResource(ontologyIRI);

        if (ontologyCreateModel.getNameModel() != null && ontologyCreateModel.getNameModel().getName() != null) {
            addLangValues(ontologyResource, SKOS.prefLabel, ontologyCreateModel.getNameModel().getName());
        }
        ontologyResource.addProperty(RDF.type, OWL2.Ontology);
        ontologyResource.addProperty(RDF.type, SKOS.ConceptScheme);
        ontologyResource.addProperty(RDF.type, model.getResource(OfnIris.SLOVNIK));

        if (ontologyCreateModel.getDescriptionModel() != null
                && ontologyCreateModel.getDescriptionModel().getDescription() != null) {
            addLangValues(ontologyResource, model.createProperty(OfnIris.DCTERMS + "description"),
                    ontologyCreateModel.getDescriptionModel().getDescription());
        }

        ontologyResource.addProperty(model.createProperty(OfnIris.OKAMZIK_VYTVORENI),
                OfnDates.instant(model, OfnDates.now(clock)));

        return model;
    }

    private static void addLangValues(Resource resource, Property property, Map<String, String> values) {
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().trim().isEmpty()) {
                String languageTag = entry.getKey() != null && !entry.getKey().trim().isEmpty()
                        ? entry.getKey()
                        : DEFAULT_LANG;
                DataTypeConverter.addLangLiteral(resource, property, entry.getValue().trim(), languageTag);
            }
        }
    }
}
