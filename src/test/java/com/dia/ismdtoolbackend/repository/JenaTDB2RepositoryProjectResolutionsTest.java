package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.controller.dto.ResolvedConceptDto;
import com.dia.ismdtoolbackend.enums.SearchSource;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.dia.constants.VocabularyConstants.OFN_NAMESPACE;
import static com.dia.constants.VocabularyConstants.VZTAH;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for {@link JenaTDB2Repository#projectResolutions} — specifically the
 * relationship domain/range projection: a relationship concept (rdf:type {@code …/vztah})
 * must yield iri-only domain/range stubs for the resolver's second hop, while
 * non-relationships must not.
 */
class JenaTDB2RepositoryProjectResolutionsTest {

    private static final String SCHEME = "https://data.gov.cz/zdroj/slovnik/local";
    private static final String REL_IRI = SCHEME + "/pojem/rel";
    private static final String CLASS_IRI = SCHEME + "/pojem/trida";
    private static final String DOMAIN_IRI = SCHEME + "/pojem/trida-a";
    private static final String RANGE_IRI = SCHEME + "/pojem/trida-b";
    private static final String RELATIONSHIP_TYPE = OFN_NAMESPACE + VZTAH;

    private static Resource res(Model m, String iri) {
        return m.createResource(iri);
    }

    @Test
    @DisplayName("relationship with rdf:type vztah yields iri-only domain/range stubs")
    void relationshipYieldsStubs() {
        Model m = ModelFactory.createDefaultModel();
        Resource scheme = res(m, SCHEME);
        Resource rel = res(m, REL_IRI);
        rel.addProperty(SKOS.inScheme, scheme);
        rel.addProperty(SKOS.prefLabel, m.createLiteral("Vztah", "cs"));
        rel.addProperty(RDF.type, res(m, RELATIONSHIP_TYPE));
        rel.addProperty(RDFS.domain, res(m, DOMAIN_IRI));
        rel.addProperty(RDFS.range, res(m, RANGE_IRI));

        Map<String, ResolvedConceptDto> out = JenaTDB2Repository.projectResolutions(m, SearchSource.ISMD);

        ResolvedConceptDto dto = out.get(REL_IRI);
        assertThat(dto).isNotNull();
        assertThat(dto.resolvedDomain()).isNotNull();
        assertThat(dto.resolvedDomain().iri()).isEqualTo(DOMAIN_IRI);
        // Stub carries only the IRI — name/ontology are filled by the resolver's second hop.
        assertThat(dto.resolvedDomain().conceptName()).isNull();
        assertThat(dto.resolvedRange()).isNotNull();
        assertThat(dto.resolvedRange().iri()).isEqualTo(RANGE_IRI);
    }

    @Test
    @DisplayName("non-relationship concept produces no domain/range stubs even if domain/range triples exist")
    void nonRelationshipProducesNoStubs() {
        Model m = ModelFactory.createDefaultModel();
        Resource scheme = res(m, SCHEME);
        Resource clazz = res(m, CLASS_IRI);
        clazz.addProperty(SKOS.inScheme, scheme);
        clazz.addProperty(SKOS.prefLabel, m.createLiteral("Třída", "cs"));
        // No rdf:type vztah; even if stray domain/range triples are present they must be ignored.
        clazz.addProperty(RDFS.domain, res(m, DOMAIN_IRI));

        Map<String, ResolvedConceptDto> out = JenaTDB2Repository.projectResolutions(m, SearchSource.ISMD);

        ResolvedConceptDto dto = out.get(CLASS_IRI);
        assertThat(dto).isNotNull();
        assertThat(dto.resolvedDomain()).isNull();
        assertThat(dto.resolvedRange()).isNull();
    }

    @Test
    @DisplayName("relationship missing domain/range yields null stubs (not empty stubs)")
    void relationshipMissingDomainRange() {
        Model m = ModelFactory.createDefaultModel();
        Resource scheme = res(m, SCHEME);
        Resource rel = res(m, REL_IRI);
        rel.addProperty(SKOS.inScheme, scheme);
        rel.addProperty(RDF.type, res(m, RELATIONSHIP_TYPE));
        // domain present, range absent
        rel.addProperty(RDFS.domain, res(m, DOMAIN_IRI));

        Map<String, ResolvedConceptDto> out = JenaTDB2Repository.projectResolutions(m, SearchSource.ISMD);

        ResolvedConceptDto dto = out.get(REL_IRI);
        assertThat(dto.resolvedDomain()).isNotNull();
        assertThat(dto.resolvedRange()).isNull();
    }
}
