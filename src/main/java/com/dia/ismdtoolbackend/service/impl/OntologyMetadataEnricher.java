package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.OntologySummaryDto;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapper;
import com.dia.ismdtoolbackend.repository.*;
import com.dia.utility.UtilityMethods;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.DCTerms;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.StmtIterator;
import static com.dia.constants.ExportConstants.Common.DEFAULT_LANG;
import org.springframework.stereotype.Component;

/** Fills an ontology's name and description from RDF, singly or for a whole page. */
@Slf4j
@Component
@RequiredArgsConstructor
class OntologyMetadataEnricher {

    private final JenaTDB2Repository jenaTDB2Repository;
    private final OntologyMetadataMapper ontologyMetadataMapper;
    private final ConceptMetadataRepository conceptMetadataRepository;

    List<OntologySummaryDto> enrichEntitiesWithBatchMetadata(List<OntologyMetadataEntity> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }

        List<String> graphNames = entities.stream()
                .map(OntologyMetadataEntity::getGraphName)
                .filter(g -> g != null && !g.isEmpty())
                .toList();

        Model batchMetadata = jenaTDB2Repository.fetchMetadataProperties(graphNames);
        Map<String, Model> perGraphModels = partitionModelBySubject(batchMetadata, graphNames);
        Map<String, Integer> conceptCounts = conceptCountsByGraph(graphNames);

        return entities.stream()
                .map(entity -> {
                    OntologySummaryDto model = ontologyMetadataMapper.toSummary(entity);
                    enrichMetadataFromModel(model, entity, perGraphModels.get(entity.getGraphName()));
                    model.setConceptCount(conceptCounts.getOrDefault(entity.getGraphName(), 0));
                    return model;
                })
                .toList();
    }

    /** One grouped count for the whole page; a graph with no concept rows is absent. */
    Map<String, Integer> conceptCountsByGraph(List<String> graphNames) {
        Map<String, Integer> counts = new HashMap<>();
        if (graphNames.isEmpty()) {
            return counts;
        }
        for (Object[] row : conceptMetadataRepository.countByGraphNameIn(graphNames)) {
            counts.put(row[0].toString(), ((Number) row[1]).intValue());
        }
        return counts;
    }

    Map<String, Model> partitionModelBySubject(Model batchModel, List<String> graphNames) {
        Map<String, Model> result = new HashMap<>();
        for (String graphName : graphNames) {
            Resource subject = batchModel.getResource(graphName);
            Model subModel = ModelFactory.createDefaultModel();
            StmtIterator stmts = batchModel.listStatements(subject, null, (org.apache.jena.rdf.model.RDFNode) null);
            while (stmts.hasNext()) {
                subModel.add(stmts.next());
            }
            result.put(graphName, subModel);
        }
        return result;
    }

    void enrichMetadataFromRDF(OntologySummaryDto model, OntologyMetadataEntity entity) {
        String graphName = entity.getGraphName();
        if (graphName == null || graphName.isEmpty()) {
            log.warn("Cannot enrich metadata: graphName is null or empty");
            return;
        }

        try {
            Model rdfModel = jenaTDB2Repository.fetchMetadataProperties(graphName);
            enrichMetadataFromModel(model, entity, rdfModel);
        } catch (Exception e) {
            log.warn("Failed to enrich metadata from RDF for graph {}: {}", graphName, e.getMessage());
            if ((model.getName() == null || model.getName().isEmpty())) {
                model.setName(fallbackNameFromGraphName(graphName));
            }
        }
    }

    void enrichMetadataFromModel(OntologySummaryDto model, OntologyMetadataEntity entity, Model rdfModel) {
        String graphName = entity.getGraphName();
        Map<String, String> fallbackName = fallbackNameFromGraphName(graphName);

        try {
            if (rdfModel == null || rdfModel.isEmpty()) {
                log.warn("Cannot enrich metadata: RDF model is empty for graph {}", graphName);
                model.setName(fallbackName);
                return;
            }

            Resource ontologyResource = rdfModel.getResource(graphName);
            Map<String, String> names = extractMultilingualValue(ontologyResource, SKOS.prefLabel);
            if (names.isEmpty()) {
                model.setName(fallbackName);
            } else {
                model.setName(names);
                log.debug("Enriched name from skos:prefLabel with {} language variant(s)", names.size());
            }

            Map<String, String> descriptions = extractMultilingualValue(ontologyResource, DCTerms.description);
            if (!descriptions.isEmpty()) {
                model.setPopis(descriptions);
                log.debug("Enriched popis from dcterms:description with {} language variant(s)", descriptions.size());
            }
        } catch (Exception e) {
            log.warn("Failed to enrich metadata from model for graph {}: {}", graphName, e.getMessage());
            if (model.getName() == null || model.getName().isEmpty()) {
                model.setName(fallbackName);
            }
        }
    }

    Map<String, String> fallbackNameFromGraphName(String graphName) {
        String derived = extractNameFromGraphName(UtilityMethods.extractNameFromIRI(graphName));
        return derived == null || derived.isEmpty() ? Map.of() : Map.of(DEFAULT_LANG, derived);
    }

    Map<String, String> extractMultilingualValue(Resource resource, Property property) {
        Map<String, String> valuesByLang = new LinkedHashMap<>();
        StmtIterator iter = resource.listProperties(property);
        while (iter.hasNext()) {
            Statement stmt = iter.next();
            RDFNode object = stmt.getObject();
            if (!object.isLiteral()) {
                continue;
            }
            Literal literal = object.asLiteral();
            // Jena returns "" (never null) for both an empty lexical form and a missing language.
            String value = literal.getString();
            if (value.trim().isEmpty()) {
                continue;
            }
            String lang = literal.getLanguage();
            valuesByLang.putIfAbsent(lang.isEmpty() ? DEFAULT_LANG : lang, value);
        }
        return valuesByLang;
    }

    String extractNameFromGraphName(String graphName) {
        if (graphName == null || graphName.isEmpty()) {
            return graphName;
        }

        String result = graphName.replace("-", " ");

        result = result.substring(0, 1).toUpperCase() + result.substring(1);

        return result;
    }
}
