package com.dia.ismdtoolbackend.utility.creator;

import com.dia.ismdtoolbackend.models.concept.*;
import com.dia.ismdtoolbackend.models.OFNBaseModel;
import com.dia.ismdtoolbackend.utility.URIGenerator;
import com.dia.ismdtoolbackend.utility.UtilityMethods;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.springframework.stereotype.Component;

import java.util.Set;

import static com.dia.ismdtoolbackend.constants.VocabularyConstants.*;

/**
 * Builds the RDF of one new concept. Each call works on its own model and IRI generator, so the
 * instance holds no per-call state.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConceptCreator {

    private final ConceptSchemaSelector schemaSelector = new ConceptSchemaSelector();

    public Resource createSingleConcept(ConceptCreateModel createModel) {
        OntModel ontModel = initializeModel(createModel);
        try {
            URIGenerator uriGenerator = new URIGenerator();
            uriGenerator.setEffectiveNamespace(determineEffectiveNamespace(createModel.getOntologyGraphName()));
            ConceptCharacteristicWriters writers =
                    ConceptCharacteristicWriters.forContext(new ConceptBuildContext(ontModel, uriGenerator));

            Resource concept = switch (createModel.getConceptTypeEnum()) {
                case TRIDA -> new ClassConceptWriter(writers).createClassResource((ClassConceptModel) createModel);
                case VLASTNOST -> new PropertyConceptWriter(writers).createPropertyResource((PropertyConceptModel) createModel);
                case VZTAH -> new RelationshipConceptWriter(writers)
                        .createRelationshipResource((RelationshipConceptModel) createModel);
                case KONCEPT -> throw new IllegalArgumentException(
                        "Nelze vytvořit pojem bez konkrétního typu (TRIDA/VLASTNOST/VZTAH).");
            };

            String conceptURI = concept.getURI();

            Model cleanModel = ModelFactory.createDefaultModel();

            ontModel.listStatements(concept, null, (RDFNode) null).forEachRemaining(stmt -> {
                Resource subj = cleanModel.createResource(conceptURI);
                Property pred = cleanModel.createProperty(stmt.getPredicate().getURI());

                RDFNode obj;
                if (stmt.getObject().isResource()) {
                    Resource objRes = stmt.getObject().asResource();
                    if (objRes.isAnon()) {
                        // Copy blank node and all its statements
                        Resource blankNode = cleanModel.createResource();
                        objRes.listProperties().forEachRemaining(bnStmt -> {
                            Property bnPred = cleanModel.createProperty(bnStmt.getPredicate().getURI());
                            RDFNode bnObj;
                            if (bnStmt.getObject().isResource()) {
                                bnObj = cleanModel.createResource(bnStmt.getObject().asResource().getURI());
                            } else {
                                bnObj = bnStmt.getObject();
                            }
                            cleanModel.add(blankNode, bnPred, bnObj);
                        });
                        obj = blankNode;
                    } else {
                        Resource named = cleanModel.createResource(objRes.getURI());
                        copyCodeListSubject(objRes, named, cleanModel);
                        obj = named;
                    }
                } else {
                    obj = stmt.getObject();
                }

                cleanModel.add(subj, pred, obj);
            });

            log.info("Created clean model with {} statements for: {}", cleanModel.size(), conceptURI);

            return cleanModel.getResource(conceptURI);
        } finally {
            ontModel.close();
        }
    }

    /**
     * Copies a named číselník's own statements into the clean model. Only subjects typed
     * {@code l111-2009:číselník} are carried over, so ordinary IRI references stay bare references.
     */
    private void copyCodeListSubject(Resource source, Resource target, Model cleanModel) {
        Resource codeListType = source.getModel().getResource(OFN_NAMESPACE_LEGAL + CISELNIK);
        if (!source.hasProperty(RDF.type, codeListType)) {
            return;
        }
        source.listProperties().forEachRemaining(stmt -> {
            Property pred = cleanModel.createProperty(stmt.getPredicate().getURI());
            RDFNode obj = stmt.getObject().isResource()
                    ? cleanModel.createResource(stmt.getObject().asResource().getURI())
                    : stmt.getObject();
            cleanModel.add(target, pred, obj);
        });
    }

    private OntModel initializeModel(ConceptCreateModel createModel) {
        Set<String> requiredBaseClasses = schemaSelector.determineRequiredBaseClasses(createModel);
        Set<String> requiredProperties = schemaSelector.determineRequiredProperties(createModel);

        OFNBaseModel baseModel = new OFNBaseModel(requiredBaseClasses, requiredProperties);

        log.debug("Initialized OntModel with base classes: {} and properties: {}",
                requiredBaseClasses, requiredProperties);
        return baseModel.getOntModel();
    }

    private String determineEffectiveNamespace(String namespace) {
        if (namespace != null && !namespace.trim().isEmpty() && UtilityMethods.isValidIRI(namespace)) {
            return UtilityMethods.ensureNamespaceEndsWithDelimiter(namespace);
        }
        return DEFAULT_NS;
    }
}
