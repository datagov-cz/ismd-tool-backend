package com.dia.ismdtoolbackend.utility.creator;

import com.dia.ismdtoolbackend.models.OntologyCreateModel;
import com.dia.ismdtoolbackend.utility.DataTypeConverter;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import static com.dia.ismdtoolbackend.constants.ExportConstants.Common.DEFAULT_LANG;
import static com.dia.ismdtoolbackend.constants.VocabularyConstants.*;

public final class OntologyModelFactory {
    private static final ZoneId CREATION_ZONE = ZoneId.of("Europe/Prague");
    /** Always prints seconds and the offset, both required by xsd:dateTimeStamp. */
    private static final DateTimeFormatter DATE_TIME_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private OntologyModelFactory() {}

    public static OntModel create(String ontologyIRI, OntologyCreateModel ontologyCreateModel) {
        OntModel model = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM);
        model.createOntology(ontologyIRI);
        Resource ontologyResource = model.getResource(ontologyIRI);

        Property prefLabel = model.createProperty(SKOS_NS + "prefLabel");
        if (ontologyCreateModel.getNameModel() != null && ontologyCreateModel.getNameModel().getName() != null) {
            for (Map.Entry<String, String> entry : ontologyCreateModel.getNameModel().getName().entrySet()) {
                if (entry.getValue() != null && !entry.getValue().trim().isEmpty()) {
                    String languageTag = entry.getKey() != null && !entry.getKey().trim().isEmpty()
                            ? entry.getKey()
                            : DEFAULT_LANG;
                    DataTypeConverter.addLangLiteral(ontologyResource, prefLabel,
                            entry.getValue().trim(), languageTag);
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
                    DataTypeConverter.addLangLiteral(ontologyResource, descProperty,
                            entry.getValue().trim(), languageTag);
                }
            }
        }

        String temporalMomentIRI = ontologyIRI + "/casovy-okamzik-vytvoreni";
        Resource temporalMoment = model.createResource(temporalMomentIRI);
        temporalMoment.addProperty(RDF.type, model.createResource(CAS_NS + CASOVY_OKAMZIK));

        Property datumACasProperty = model.createProperty(CAS_NS + DATUM_A_CAS);
        String createdAt = OffsetDateTime.now(CREATION_ZONE).format(DATE_TIME_STAMP);
        temporalMoment.addProperty(datumACasProperty, model.createTypedLiteral(createdAt, XSDDatatype.XSDdateTimeStamp));

        Property okamzikVytvoreniProperty = model.createProperty(SLOVNIKY_NS + OKAMZIK_VYTVORENI);
        ontologyResource.addProperty(okamzikVytvoreniProperty, temporalMoment);

        return model;
    }
}
