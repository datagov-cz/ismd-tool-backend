package com.dia.ismdtoolbackend.utility.creator;

import com.dia.ismdtoolbackend.models.concept.*;
import com.dia.ismdtoolbackend.utility.eli.EsbirkaEliParser;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import com.dia.ismdtoolbackend.utility.DataTypeConverter;
import com.dia.ismdtoolbackend.utility.URIGenerator;
import com.dia.ismdtoolbackend.utility.UtilityMethods;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

import java.util.List;

import static com.dia.ismdtoolbackend.constants.ExportConstants.Common.DEFAULT_LANG;
import static com.dia.ismdtoolbackend.constants.VocabularyConstants.*;

/** Writes legal and non-legal sources, including their digital documents. */
@Slf4j
final class ConceptSourceWriter {

    private final OntModel ontModel;
    private final URIGenerator uriGenerator;

    ConceptSourceWriter(ConceptBuildContext context) {
        this.ontModel = context.ontModel();
        this.uriGenerator = context.uriGenerator();
    }

    void addSourceMetadata(Resource resource, ConceptCreateModel model) {
        addLegalSourceMetadata(resource, model);
        addNonLegalSourceMetadata(resource, model);
    }

    void addLegalSourceMetadata(Resource resource, ConceptCreateModel model) {
        processLegalSources(resource, model.getDefiningLegalSource(), true);
        processLegalSources(resource, model.getRelatedLegalSource(), false);
    }

    void addNonLegalSourceMetadata(Resource resource, ConceptCreateModel model) {
        processNonLegalSources(resource, model.getDefiningNonLegalSource(), true);
        processNonLegalSources(resource, model.getRelatedNonLegalSource(), false);
    }

    void processLegalSources(Resource resource, List<String> sources, boolean isDefining) {
        if (sources == null || sources.isEmpty()) {
            return;
        }

        for (String source : sources) {
            if (isValidSource(source)) {
                processLegalSource(resource, source, isDefining);
            }
        }
    }

    void processNonLegalSources(Resource resource, List<DigitalObjectModel> sources, boolean isDefining) {
        if (sources == null || sources.isEmpty()) {
            return;
        }

        for (DigitalObjectModel source : sources) {
            if (isValidDigitalObject(source)) {
                processNonLegalSource(resource, source, isDefining);
            } else if (source != null) {
                log.warn("Skipping non-legal source — url is blank or invalid: {}", source.getUrl());
            }
        }
    }

    boolean isValidSource(String source) {
        return source != null && !source.trim().isEmpty();
    }

    boolean isValidDigitalObject(DigitalObjectModel source) {
        if (source == null) return false;
        String url = source.getUrl();
        if (url == null || url.trim().isEmpty()) return false;
        return UtilityMethods.isValidUrl(url.trim());
    }

    void processLegalSource(Resource resource, String source, boolean isDefining) {
        String propertyName = isDefining ? DEFINUJICI_USTANOVENI : SOUVISEJICI_USTANOVENI;
        Property property = ontModel.createProperty(OFN_NAMESPACE + propertyName);

        if (source == null || source.trim().isEmpty()) return;
        String canonical = EsbirkaEliParser.canonicalizeHost(source.trim());
        if (!SparqlIriValidator.isEsbirkaEliIri(canonical)) {
            log.warn("Skipping legal source — not a canonical e-Sbírka ELI IRI: {}", source.trim());
            return;
        }
        resource.addProperty(property, ontModel.createResource(canonical));
    }

    void processNonLegalSource(Resource resource, DigitalObjectModel source, boolean isDefining) {
        String propertyName = isDefining ? DEFINUJICI_NELEGISLATIVNI_ZDROJ : SOUVISEJICI_NELEGISLATIVNI_ZDROJ;
        Property property = ontModel.createProperty(uriGenerator.getEffectiveNamespace() + propertyName);

        Resource digitalDocument = ontModel.createResource();
        digitalDocument.addProperty(RDF.type, ontModel.createResource(DIGITALNI_OBJEKT));

        Property schemaUrlProperty = ontModel.createProperty(SCHEMA_URL);
        digitalDocument.addProperty(schemaUrlProperty, ontModel.createResource(source.getUrl().trim()));

        if (source.getName() != null && !source.getName().trim().isEmpty()) {
            Property titleProperty = ontModel.createProperty(DCT_NS + "title");
            DataTypeConverter.addTypedProperty(digitalDocument, titleProperty,
                    source.getName().trim(), DEFAULT_LANG, ontModel);
        }

        if (source.getDescription() != null && !source.getDescription().trim().isEmpty()) {
            Property descriptionProperty = ontModel.createProperty(DCT_NS + "description");
            DataTypeConverter.addTypedProperty(digitalDocument, descriptionProperty,
                    source.getDescription().trim(), DEFAULT_LANG, ontModel);
        }

        resource.addProperty(property, digitalDocument);
    }
}
