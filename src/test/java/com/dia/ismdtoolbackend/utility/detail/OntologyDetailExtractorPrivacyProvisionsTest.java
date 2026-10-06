package com.dia.ismdtoolbackend.utility.detail;

import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.concept.ClassConceptModel;
import com.dia.ismdtoolbackend.utility.creator.ConceptCreator;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The privacy provisions a concept is created with must be readable both from the stored graph
 * (concept detail) and after the OFN transform (vocabulary detail, diagrams, deviation), which
 * renames the property they are stored under.
 */
class OntologyDetailExtractorPrivacyProvisionsTest {

    private static final String VOCABULARY = "https://slovník.gov.cz/agendový/999";
    private static final String ELI = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187";

    private final OntologyDetailExtractor extractor = new OntologyDetailExtractor(null);

    private static Model graphWithNonPublicConcept() {
        ClassConceptModel model = new ClassConceptModel();
        model.setConceptType("TRIDA");
        model.setType("objekt");
        model.setOntologyGraphName(VOCABULARY);
        model.setNamespace(VOCABULARY);
        NameModel name = new NameModel();
        name.setName(Map.of("cs", "Osoba"));
        model.setNameModel(name);
        model.setIsPublic(false);
        model.setPrivacyProvisions(List.of(ELI));

        Resource created = new ConceptCreator().createSingleConcept(model);
        Model graph = ModelFactory.createDefaultModel().add(created.getModel());
        graph.createResource(VOCABULARY).addProperty(RDF.type, OWL2.Ontology).addProperty(RDF.type, SKOS.ConceptScheme);
        return graph;
    }

    @Test
    void readFromTheStoredGraph() {
        Model graph = graphWithNonPublicConcept();

        OntologyDetailModel detail = extractor.extractOntologyDetail(graph, iri -> null);

        assertEquals(List.of(ELI), detail.getConcepts().get(0).getPrivacyProvisions());
    }

    @Test
    void readAfterTheOfnTransform() {
        Model graph = graphWithNonPublicConcept();

        OntologyDetailModel detail = extractor.extractOntologyDetail(
                extractor.applyOFNTransformations(graph), iri -> null);

        assertEquals(List.of(ELI), detail.getConcepts().get(0).getPrivacyProvisions());
    }
}
