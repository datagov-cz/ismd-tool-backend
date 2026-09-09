package com.dia.ismdtoolbackend.utility.creator;

import com.dia.ismdtoolbackend.models.OntologyCreateModel;
import com.dia.models.OFNBaseModel;
import com.dia.utility.DataTypeConverter;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import java.time.LocalDateTime;
import java.util.Map;
import static com.dia.constants.ExportConstants.Common.DEFAULT_LANG;
import static com.dia.constants.VocabularyConstants.*;

public final class OntologyModelFactory {
    private OntologyModelFactory() {}

    public static OntModel create(String ontologyIRI, OntologyCreateModel ontologyCreateModel) {
        OFNBaseModel ofnModel = new OFNBaseModel();

        OntModel model = ofnModel.getOntModel();
        model.createOntology(ontologyIRI);
        Resource ontologyResource = model.getResource(ontologyIRI);

        Property prefLabel = model.createProperty(SKOS_NS + "prefLabel");
        if (ontologyCreateModel.getNameModel() != null && ontologyCreateModel.getNameModel().getName() != null) {
            for (Map.Entry<String, String> entry : ontologyCreateModel.getNameModel().getName().entrySet()) {
                if (entry.getValue() != null && !entry.getValue().trim().isEmpty()) {
                    String languageTag = entry.getKey() != null && !entry.getKey().trim().isEmpty()
                            ? entry.getKey()
                            : DEFAULT_LANG;
                    DataTypeConverter.addTypedProperty(ontologyResource, prefLabel,
                            entry.getValue().trim(), languageTag, model);
                }
            }
        }
        ontologyResource.addProperty(RDF.type, model.getResource("http://www.w3.org/2002/07/owl#Ontology"));
        ontologyResource.addProperty(RDF.type, SKOS.ConceptScheme);
        ontologyResource.addProperty(RDF.type, model.getResource(SLOVNIKY_NS + SLOVNIK));

        if (ontologyCreateModel.getDescriptionModel() != null && ontologyCreateModel.getDescriptionModel().getDescription() != null) {
            Property descProperty = model.createProperty("http://purl.org/dc/terms/description");
            for (Map.Entry<String, String> entry : ontologyCreateModel.getDescriptionModel().getDescription().entrySet()) {
                if (entry.getValue() != null && !entry.getValue().trim().isEmpty()) {
                    String languageTag = entry.getKey() != null && !entry.getKey().trim().isEmpty()
                            ? entry.getKey()
                            : DEFAULT_LANG;
                    DataTypeConverter.addTypedProperty(ontologyResource, descProperty,
                            entry.getValue().trim(), languageTag, model);
                }
            }
        }

        String temporalMomentIRI = ontologyIRI + "/casovy-okamzik-vytvoreni";
        Resource temporalMoment = model.createResource(temporalMomentIRI);
        temporalMoment.addProperty(RDF.type, model.createResource(CAS_NS + CASOVY_OKAMZIK));

        Property datumACasProperty = model.createProperty(CAS_NS + DATUM_A_CAS);
        String currentDateTime = LocalDateTime.now().toString();
        temporalMoment.addProperty(datumACasProperty, currentDateTime);

        Property okamzikVytvoreniProperty = model.createProperty(SLOVNIKY_NS + OKAMZIK_VYTVORENI);
        ontologyResource.addProperty(okamzikVytvoreniProperty, temporalMoment);

        return model;
    }
}
