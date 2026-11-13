package com.dia.ismdtoolbackend.utility.detail;

import com.dia.ismdtoolbackend.controller.dto.ResolvedLegalSourceDto;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.utility.exporter.json.ConceptData;
import com.dia.ismdtoolbackend.utility.exporter.json.ConceptJsonMapper;
import com.dia.ismdtoolbackend.utility.exporter.json.ModelAnalyzer;
import com.dia.ismdtoolbackend.utility.exporter.json.ModelStructure;

import com.dia.ismdtoolbackend.utility.exporter.turtle.TurtleFilterUtil;
import com.dia.ismdtoolbackend.utility.exporter.turtle.TurtleFormatterUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Component
@Slf4j
public class OntologyDetailExtractor {

    private final DetailSlugResolvers slugResolvers;
    private final DetailModelMapper mapper = new DetailModelMapper();

    public OntologyDetailExtractor(ConceptMetadataRepository conceptMetadataRepository) {
        this.slugResolvers = new DetailSlugResolvers(conceptMetadataRepository);
    }

    Function<String, String> graphSlugResolver(Model processedModel) {
        return slugResolvers.graphSlugResolver(processedModel);
    }

    /**
     * Resolver that returns the IRI unchanged. Intended for the NKD detail pipeline,
     * where the navigation reference is the full IRI used by /api/nkd/.../detail endpoints.
     */
    public static Function<String, String> iriResolver() {
        return iri -> iri;
    }

    /** Which data is read, and so how much tolerance the read needs. */
    private enum Source {
        /** Stored ISMD data: OFN-shaped by the write gate, read as it is. */
        STORED,
        /** Upload input, before it is stored: {@link OutsideDataNormalizer#normalizeUploadInput}. */
        UPLOAD,
        /** NKD responses: {@link OutsideDataNormalizer#normalizeNkd}. */
        NKD
    }

    /**
     * Detail transform of stored ISMD data (ontology and concept detail, diagram, working-copy projection).
     * {@code rawModel} is left unchanged; the transform works on a copy.
     */
    public Model applyOFNTransformations(Model rawModel) {
        return applyOFNTransformations(rawModel, Source.STORED);
    }

    /** Detail transform of upload input, which ISMD does not own yet. {@code rawModel} is left unchanged. */
    public Model applyOFNTransformationsForUpload(Model rawModel) {
        return applyOFNTransformations(rawModel, Source.UPLOAD);
    }

    /**
     * Detail transform of NKD data, which ISMD does not own: also derives {@code skos:inScheme}, which NKD
     * data often lacks. {@code rawModel} is left unchanged.
     */
    public Model applyOFNTransformationsForNkd(Model rawModel) {
        return applyOFNTransformations(rawModel, Source.NKD);
    }

    private Model applyOFNTransformations(Model rawModel, Source source) {
        log.debug("Applying OFN transformations ({})", source);
        // The normalizers mutate the model they are handed, and callers pass models they do not
        // own (a cached NKD model, a graph the request extracts from again), so they run on a copy.
        Model workingModel = ModelFactory.createDefaultModel();
        workingModel.setNsPrefixes(rawModel.getNsPrefixMap());
        workingModel.add(rawModel);

        // Outside data may lack skos:Concept and slovníky:pojem; TurtleFilterUtil would strip such
        // concepts as vocabulary noise, so they are inferred before filtering.
        int normalized = switch (source) {
            case STORED -> 0;
            case UPLOAD -> OutsideDataNormalizer.normalizeUploadInput(workingModel);
            case NKD -> OutsideDataNormalizer.normalizeNkd(workingModel);
        };
        if (normalized > 0) {
            log.debug("Inferred OFN role tags on {} resources before filtering", normalized);
        }
        Model filteredModel = TurtleFilterUtil.createFilteredModel(workingModel);
        Model ofnFormattedModel = TurtleFormatterUtil.transformToOFNFormat(filteredModel);
        log.debug("OFN transformation complete: {} -> {} -> {} statements",
                workingModel.size(), filteredModel.size(), ofnFormattedModel.size());
        return ofnFormattedModel;
    }

    @Transactional(readOnly = true)
    public OntologyDetailModel extractOntologyDetail(Model processedModel) {
        return extractOntologyDetail(processedModel, graphSlugResolver(processedModel));
    }

    public OntologyDetailModel extractOntologyDetail(Model processedModel, Function<String, String> refResolver) {
        OntModel ontModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM, processedModel);

        ModelAnalyzer modelAnalyzer = new ModelAnalyzer();
        ConceptJsonMapper conceptJsonMapper = new ConceptJsonMapper();

        ModelStructure structure = modelAnalyzer.analyzeModel(processedModel);
        ConceptData conceptData = conceptJsonMapper.processAllConcepts(ontModel, structure);

        return mapper.mapToOntologyDetailModel(structure, conceptData, refResolver);
    }

    public OntologyDetailModel.ConceptDetailModel extractConceptDetail(Model processedModel, String conceptIri) {
        OntModel ontModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM, processedModel);
        return extractConceptDetail(ontModel, processedModel, conceptIri, slugResolvers.batchSlugResolver(ontModel, conceptIri));
    }

    public OntologyDetailModel.ConceptDetailModel extractConceptDetail(Model processedModel, String conceptIri,
                                                                      Function<String, String> refResolver) {
        OntModel ontModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM, processedModel);
        return extractConceptDetail(ontModel, processedModel, conceptIri, refResolver);
    }

    /**
     * Extracts several concepts from ONE shared {@code OntModel} + {@code ModelStructure}.
     *
     * <p>IRIs absent from the model map to {@code null}.
     */
    public Map<String, OntologyDetailModel.ConceptDetailModel> extractConceptDetails(Model processedModel,
                                                                                     Collection<String> conceptIris) {
        OntModel ontModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM, processedModel);
        ModelAnalyzer modelAnalyzer = new ModelAnalyzer();
        ConceptJsonMapper conceptJsonMapper = new ConceptJsonMapper();
        ModelStructure structure = modelAnalyzer.analyzeModel(processedModel);

        // One slug query for every concept's members, not one per concept.
        Function<String, String> refResolver = slugResolvers.batchSlugResolver(ontModel, conceptIris);

        Map<String, OntologyDetailModel.ConceptDetailModel> out = new LinkedHashMap<>();
        for (String conceptIri : conceptIris) {
            if (!processedModel.containsResource(processedModel.getResource(conceptIri))) {
                out.put(conceptIri, null);
                continue;
            }
            Map<String, Object> conceptMap = conceptJsonMapper.processConceptByIri(ontModel, structure, conceptIri);
            out.put(conceptIri, mapper.mapToConceptDetailModel(conceptMap, null, ontModel, refResolver));
        }
        return out;
    }

    private OntologyDetailModel.ConceptDetailModel extractConceptDetail(OntModel ontModel, Model processedModel,
                                                                       String conceptIri,
                                                                       Function<String, String> refResolver) {
        ModelAnalyzer modelAnalyzer = new ModelAnalyzer();
        ConceptJsonMapper conceptJsonMapper = new ConceptJsonMapper();

        ModelStructure structure = modelAnalyzer.analyzeModel(processedModel);
        Map<String, Object> conceptMap = conceptJsonMapper.processConceptByIri(ontModel, structure, conceptIri);

        return mapper.mapToConceptDetailModel(conceptMap, null, ontModel, refResolver);
    }

    static List<ResolvedLegalSourceDto> buildResolvedSources(List<String> urls) {
        return DetailDtoBuilders.buildResolvedSources(urls);
    }
}
