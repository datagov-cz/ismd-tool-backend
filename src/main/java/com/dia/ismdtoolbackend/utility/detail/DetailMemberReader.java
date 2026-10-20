package com.dia.ismdtoolbackend.utility.detail;

import com.dia.ismdtoolbackend.models.concept.ConceptPropertiesModel;
import com.dia.ismdtoolbackend.models.concept.ConceptRelationshipsModel;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.SKOS;

import java.util.*;
import java.util.function.Function;

import static com.dia.constants.VocabularyConstants.*;
import static com.dia.ismdtoolbackend.utility.detail.DetailDtoBuilders.*;
import static com.dia.ismdtoolbackend.utility.detail.DetailValues.*;

/** Reads the properties and relationships that belong to a concept. */
class DetailMemberReader {

    List<ConceptPropertiesModel> extractConceptProperties(String conceptIri, DetailMemberIndex memberIndex,
                                                          Function<String, String> refResolver) {
        List<ConceptPropertiesModel> properties = new ArrayList<>();

        for (Map<String, Object> conceptMap : memberIndex.propertiesWithDomain(conceptIri)) {
            String propertyIri = (String) conceptMap.get("iri");
            ConceptPropertiesModel propertyModel = new ConceptPropertiesModel();

            Map<String, String> nameMap = coerceToStringMap(conceptMap.get(NAZEV), conceptIri, NAZEV);
            String name = extractFirstAvailableName(nameMap);

            propertyModel.setName(name);
            propertyModel.setIri(propertyIri);
            propertyModel.setRef(refResolver.apply(propertyIri));

            Object rawRange = conceptMap.get(OBOR_HODNOT);
            propertyModel.setRange(rawRange instanceof String s ? s : null);
            propertyModel.setRangeResolved(buildDataTypeDto(rawRange));

            properties.add(propertyModel);
        }

        return properties;
    }

    List<ConceptPropertiesModel> extractConceptPropertiesFromModel(OntModel ontModel, String conceptIri,
                                                                  Function<String, String> refResolver) {
        List<ConceptPropertiesModel> properties = new ArrayList<>();

        Resource conceptResource = ontModel.getResource(conceptIri);
        Resource vlastnostType = ontModel.getResource(OFN_NAMESPACE + VLASTNOST);

        ResIterator propertyIterator = ontModel.listSubjectsWithProperty(
            org.apache.jena.vocabulary.RDFS.domain,
            conceptResource
        );

        while (propertyIterator.hasNext()) {
            Resource propertyResource = propertyIterator.next();

            if (propertyResource.hasProperty(org.apache.jena.rdf.model.ResourceFactory.createProperty(
                "http://www.w3.org/1999/02/22-rdf-syntax-ns#", "type"), vlastnostType)) {

                ConceptPropertiesModel propertyModel = new ConceptPropertiesModel();
                String propertyIri = propertyResource.getURI();

                Statement nameStmt = propertyResource.getProperty(org.apache.jena.vocabulary.SKOS.prefLabel);
                String name = nameStmt != null ? nameStmt.getString() : null;

                propertyModel.setName(name);
                propertyModel.setIri(propertyIri);
                propertyModel.setRef(refResolver.apply(propertyIri));

                String rawRange = extractRawRange(propertyResource);
                propertyModel.setRange(rawRange);
                propertyModel.setRangeResolved(buildDataTypeDto(rawRange));

                properties.add(propertyModel);
            }
        }

        return properties;
    }

    List<ConceptRelationshipsModel> extractConceptRelationships(String conceptIri, DetailMemberIndex memberIndex,
                                                                Function<String, String> refResolver) {
        List<ConceptRelationshipsModel> relationships = new ArrayList<>();

        for (Map<String, Object> conceptMap : memberIndex.relationshipsTouching(conceptIri)) {
            String relationshipIri = (String) conceptMap.get("iri");
            ConceptRelationshipsModel relationshipModel = new ConceptRelationshipsModel();

            Map<String, String> nameMap = coerceToStringMap(conceptMap.get(NAZEV), relationshipIri, NAZEV);
            String name = extractFirstAvailableName(nameMap);

            relationshipModel.setName(name);
            relationshipModel.setIri(relationshipIri);
            relationshipModel.setRef(refResolver.apply(relationshipIri));

            relationships.add(relationshipModel);
        }

        return relationships;
    }

    List<ConceptRelationshipsModel> extractConceptRelationshipsFromModel(OntModel ontModel, String conceptIri,
                                                                        Function<String, String> refResolver) {
        List<ConceptRelationshipsModel> relationships = new ArrayList<>();

        Resource conceptResource = ontModel.getResource(conceptIri);
        Resource vztahType = ontModel.getResource(OFN_NAMESPACE + VZTAH);

        Set<Resource> relationshipResources = new LinkedHashSet<>();
        ontModel.listSubjectsWithProperty(org.apache.jena.vocabulary.RDFS.domain, conceptResource)
                .forEachRemaining(relationshipResources::add);
        ontModel.listSubjectsWithProperty(org.apache.jena.vocabulary.RDFS.range, conceptResource)
                .forEachRemaining(relationshipResources::add);

        for (Resource relationshipResource : relationshipResources) {
            if (relationshipResource.hasProperty(ResourceFactory.createProperty(
                "http://www.w3.org/1999/02/22-rdf-syntax-ns#", "type"), vztahType)) {

                ConceptRelationshipsModel relationshipModel = new ConceptRelationshipsModel();
                String relationshipIri = relationshipResource.getURI();

                Statement nameStmt = relationshipResource.getProperty(SKOS.prefLabel);
                String name = nameStmt != null ? nameStmt.getString() : null;

                relationshipModel.setName(name);
                relationshipModel.setIri(relationshipIri);
                relationshipModel.setRef(refResolver.apply(relationshipIri));

                relationships.add(relationshipModel);
            }
        }

        return relationships;
    }

    /**
     * Read {@code rdfs:range} from a property {@code Resource} and emit it in
     * the same shape {@code ConceptRelationJsonMapper.addDomainAndRange} writes to the
     * JSON map: abbreviated to {@code xsd:*} when in the XSD namespace, else
     * the raw URI. Returns {@code null} when no range is set.
     */
    static String extractRawRange(Resource propertyResource) {
        Statement rangeStmt = propertyResource.getProperty(org.apache.jena.vocabulary.RDFS.range);
        if (rangeStmt == null || !rangeStmt.getObject().isResource()) {
            return null;
        }
        String rangeUri = rangeStmt.getObject().asResource().getURI();
        if (rangeUri == null) {
            return null;
        }
        if (rangeUri.startsWith(XSD)) {
            return "xsd:" + rangeUri.substring(XSD.length());
        }
        return rangeUri;
    }
}
