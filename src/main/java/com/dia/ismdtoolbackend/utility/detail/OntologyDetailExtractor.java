package com.dia.ismdtoolbackend.utility.detail;

import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.utility.exporter.json.ConceptData;
import com.dia.ismdtoolbackend.utility.exporter.json.ConceptProcessor;
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

import java.util.*;

import static com.dia.constants.ExportConstants.Common.DEFAULT_LANG;
import static com.dia.constants.VocabularyConstants.*;

@Component
@Slf4j
public class OntologyDetailExtractor {

    public Model applyOFNTransformations(Model rawModel) {
        log.debug("Applying OFN transformations");
        Model filteredModel = TurtleFilterUtil.createFilteredModel(rawModel);
        Model ofnFormattedModel = TurtleFormatterUtil.transformToOFNFormat(filteredModel);
        log.debug("OFN transformation complete: {} -> {} -> {} statements",
                rawModel.size(), filteredModel.size(), ofnFormattedModel.size());
        return ofnFormattedModel;
    }

    public OntologyDetailModel extractOntologyDetail(Model processedModel) {
        OntModel ontModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM, processedModel);

        ModelAnalyzer modelAnalyzer = new ModelAnalyzer();
        ConceptProcessor conceptProcessor = new ConceptProcessor();

        ModelStructure structure = modelAnalyzer.analyzeModel(processedModel);
        ConceptData conceptData = conceptProcessor.processAllConcepts(ontModel, structure);

        return mapToOntologyDetailModel(structure, conceptData);
    }

    public OntologyDetailModel.ConceptDetailModel extractConceptDetail(Model processedModel, String conceptIri) {
        OntModel ontModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM, processedModel);

        ModelAnalyzer modelAnalyzer = new ModelAnalyzer();
        ConceptProcessor conceptProcessor = new ConceptProcessor();

        ModelStructure structure = modelAnalyzer.analyzeModel(processedModel);
        ConceptData conceptData = conceptProcessor.processAllConcepts(ontModel, structure);

        return findConceptInData(conceptData, conceptIri);
    }

    private OntologyDetailModel mapToOntologyDetailModel(ModelStructure structure, ConceptData conceptData) {
        List<OntologyDetailModel.ConceptDetailModel> concepts = conceptData.getConcepts().stream()
                .map(this::mapToConceptDetailModel)
                .toList();

        return OntologyDetailModel.builder()
                .context(CONTEXT_JSONLD)
                .iri(structure.getOntologyIRI())
                .types(structure.getVocabularyTypes())
                .name(createMultilingualMap(structure.getModelName()))
                .description(createMultilingualMap(structure.getModelDescription()))
                .creationDate(structure.getCreationDate())
                .modificationDate(structure.getModificationDate())
                .concepts(concepts)
                .build();
    }

    private OntologyDetailModel.ConceptDetailModel findConceptInData(ConceptData conceptData, String conceptIri) {
        for (Map<String, Object> conceptMap : conceptData.getConcepts()) {
            String iri = (String) conceptMap.get("iri");
            if (conceptIri.equals(iri)) {
                return mapToConceptDetailModel(conceptMap);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private OntologyDetailModel.ConceptDetailModel mapToConceptDetailModel(Map<String, Object> conceptMap) {
        return OntologyDetailModel.ConceptDetailModel.builder()
                .iri((String) conceptMap.get("iri"))
                .types((List<String>) conceptMap.get("typ"))
                .name((Map<String, String>) conceptMap.get(NAZEV))
                .alternativeName((Map<String, String>) conceptMap.get(ALTERNATIVNI_NAZEV))
                .definition((Map<String, String>) conceptMap.get(DEFINICE))
                .description((Map<String, String>) conceptMap.get(POPIS))
                .identifiers((List<String>) conceptMap.get(IDENTIFIKATOR))
                .exactMatches((List<Map<String, String>>) conceptMap.get(EKVIVALENTNI_POJEM))
                .domain((String) conceptMap.get(DEFINICNI_OBOR))
                .range((String) conceptMap.get(OBOR_HODNOT))
                .broaderClasses((List<String>) conceptMap.get(NADRAZENA_TRIDA))
                .broaderRelations((List<String>) conceptMap.get(NADRAZENY_VZTAH))
                .broaderProperties((List<String>) conceptMap.get(NADRAZENA_VLASTNOST))
                .definingLegalSources((List<String>) conceptMap.get(DEFINUJICI_USTANOVENI_PRAVNIHO_PREDPISU))
                .relatedLegalSources((List<String>) conceptMap.get(SOUVISEJICI_USTANOVENI_PRAVNIHO_PREDPISU))
                .definingNonLegalSources((List<String>) conceptMap.get(DEFINUJICI_NELEGISLATIVNI_ZDROJ))
                .relatedNonLegalSources((List<String>) conceptMap.get(SOUVISEJICI_NELEGISLATIVNI_ZDROJ))
                .sharingMethods((List<String>) conceptMap.get(ZPUSOB_SDILENI))
                .acquisitionMethod((String) conceptMap.get(ZPUSOB_ZISKANI))
                .contentType((String) conceptMap.get(TYP_OBSAHU))
                .isPpdf((Boolean) conceptMap.get(JE_PPDF))
                .ais((String) conceptMap.get(AIS))
                .agenda((String) conceptMap.get(AGENDA))
                .privacyProvisions((List<String>) conceptMap.get(USTANOVENI_NEVEREJNOST))
                .build();
    }

    private Map<String, String> createMultilingualMap(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> map = new LinkedHashMap<>();
        map.put(DEFAULT_LANG, value);
        return map;
    }
}
