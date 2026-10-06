package com.dia.ismdtoolbackend.utility.validation;

import com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ClassConceptModel;
import com.dia.ismdtoolbackend.models.concept.DigitalObjectModel;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.concept.PropertyConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.PropertyConceptModel;
import com.dia.ismdtoolbackend.models.concept.RelationshipConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.RelationshipConceptModel;
import com.dia.ismdtoolbackend.utility.creator.ConceptCreator;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConceptIriNormalizerTest {

    private static final String ELI =
            "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2005/348/2026-01-01/dokument/norma/cast_1/par_2/odst_1";
    private static final String CONCEPT = "https://slovník.gov.cz/agendový/104/pojem/osoba";

    @Test
    void iri_stripsTrailingSlash() {
        assertEquals(ELI, ConceptIriNormalizer.iri(ELI + "/"));
        assertEquals(ELI, ConceptIriNormalizer.iri("  " + ELI + "//  "));
    }

    @Test
    void iri_leavesSlashlessIriUntouched() {
        assertEquals(ELI, ConceptIriNormalizer.iri(ELI));
    }

    @Test
    void iri_leavesNonIriValuesAsSupplied() {
        assertNull(ConceptIriNormalizer.iri(null));
        assertEquals("", ConceptIriNormalizer.iri(""));
        assertEquals("Nadřazená třída/", ConceptIriNormalizer.iri("Nadřazená třída/"));
    }

    @Test
    void create_class() {
        ClassConceptModel m = new ClassConceptModel();
        m.setIdentifier(CONCEPT + "/");
        m.setDefiningLegalSource(List.of(ELI + "/"));
        m.setRelatedLegalSource(Arrays.asList(ELI + "/", null, ELI));
        m.setExactMatch(List.of(CONCEPT + "/"));
        m.setPrivacyProvisions(List.of(ELI + "/"));
        m.setBroaderConcept(List.of(CONCEPT + "/"));

        ConceptIriNormalizer.normalize(m);

        assertEquals(CONCEPT, m.getIdentifier());
        assertEquals(List.of(ELI), m.getDefiningLegalSource());
        assertEquals(Arrays.asList(ELI, null, ELI), m.getRelatedLegalSource());
        assertEquals(List.of(CONCEPT), m.getExactMatch());
        assertEquals(List.of(ELI), m.getPrivacyProvisions());
        assertEquals(List.of(CONCEPT), m.getBroaderConcept());
    }

    @Test
    void create_property() {
        PropertyConceptModel m = new PropertyConceptModel();
        m.setPrivacyProvisions(List.of(ELI + "/"));
        m.setDomain(CONCEPT + "/");
        m.setSuperProperty(List.of(CONCEPT + "/"));
        m.setDataType("https://example.org/datatype/");

        ConceptIriNormalizer.normalize(m);

        assertEquals(List.of(ELI), m.getPrivacyProvisions());
        assertEquals(CONCEPT, m.getDomain());
        assertEquals(List.of(CONCEPT), m.getSuperProperty());
        assertEquals("https://example.org/datatype", m.getDataType());
    }

    @Test
    void create_relationship() {
        RelationshipConceptModel m = new RelationshipConceptModel();
        m.setPrivacyProvisions(List.of(ELI + "/"));
        m.setDomain(CONCEPT + "/");
        m.setRange(CONCEPT + "/");
        m.setSuperRelation(List.of(CONCEPT + "/"));

        ConceptIriNormalizer.normalize(m);

        assertEquals(List.of(ELI), m.getPrivacyProvisions());
        assertEquals(CONCEPT, m.getDomain());
        assertEquals(CONCEPT, m.getRange());
        assertEquals(List.of(CONCEPT), m.getSuperRelation());
    }

    @Test
    void edit_class() {
        ClassConceptEditModel m = new ClassConceptEditModel();
        m.setIdentifier(CONCEPT + "/");
        m.setDefiningLegalSource(List.of(ELI + "/"));
        m.setRelatedLegalSource(List.of(ELI + "/"));
        m.setExactMatch(List.of(CONCEPT + "/"));
        m.setPrivacyProvisions(List.of(ELI + "/"));
        m.setBroaderConcept(List.of(CONCEPT + "/"));

        ConceptIriNormalizer.normalize(m);

        assertEquals(CONCEPT, m.getIdentifier());
        assertEquals(List.of(ELI), m.getDefiningLegalSource());
        assertEquals(List.of(ELI), m.getRelatedLegalSource());
        assertEquals(List.of(CONCEPT), m.getExactMatch());
        assertEquals(List.of(ELI), m.getPrivacyProvisions());
        assertEquals(List.of(CONCEPT), m.getBroaderConcept());
    }

    @Test
    void edit_property() {
        PropertyConceptEditModel m = new PropertyConceptEditModel();
        m.setPrivacyProvisions(List.of(ELI + "/"));
        m.setDomain(CONCEPT + "/");
        m.setSuperProperty(List.of(CONCEPT + "/"));

        ConceptIriNormalizer.normalize(m);

        assertEquals(List.of(ELI), m.getPrivacyProvisions());
        assertEquals(CONCEPT, m.getDomain());
        assertEquals(List.of(CONCEPT), m.getSuperProperty());
    }

    @Test
    void edit_relationship() {
        RelationshipConceptEditModel m = new RelationshipConceptEditModel();
        m.setPrivacyProvisions(List.of(ELI + "/"));
        m.setDomain(CONCEPT + "/");
        m.setRange(CONCEPT + "/");
        m.setSuperRelation(List.of(CONCEPT + "/"));

        ConceptIriNormalizer.normalize(m);

        assertEquals(List.of(ELI), m.getPrivacyProvisions());
        assertEquals(CONCEPT, m.getDomain());
        assertEquals(CONCEPT, m.getRange());
        assertEquals(List.of(CONCEPT), m.getSuperRelation());
    }

    @Test
    void nullFieldsStayNull() {
        ClassConceptEditModel m = new ClassConceptEditModel();

        ConceptIriNormalizer.normalize(m);

        assertNull(m.getIdentifier());
        assertNull(m.getDefiningLegalSource());
        assertNull(m.getPrivacyProvisions());
        assertNull(m.getBroaderConcept());
    }

    @Test
    void externalAddressesKeepTheirSlash() {
        ClassConceptModel m = new ClassConceptModel();
        DigitalObjectModel document = new DigitalObjectModel();
        document.setUrl("https://example.org/dokument/");
        m.setDefiningNonLegalSource(List.of(document));
        m.setCodeListIri("https://example.org/ciselnik/");
        m.setCodeListDataset("https://example.org/datova-sada/");

        ConceptIriNormalizer.normalize(m);

        assertEquals("https://example.org/dokument/", m.getDefiningNonLegalSource().get(0).getUrl());
        assertEquals("https://example.org/ciselnik/", m.getCodeListIri());
        assertEquals("https://example.org/datova-sada/", m.getCodeListDataset());
    }

    @Test
    void createdConceptCarriesNoTrailingSlashIri() {
        ClassConceptModel m = new ClassConceptModel();
        m.setConceptType("TRIDA");
        m.setOntologyGraphName("https://slovník.gov.cz/agendový/104");
        m.setNamespace("https://slovník.gov.cz/agendový/104");
        NameModel name = new NameModel();
        name.setName(Map.of("cs", "Osoba"));
        m.setNameModel(name);
        m.setIdentifier(CONCEPT + "/");
        m.setDefiningLegalSource(List.of(ELI + "/"));
        m.setRelatedLegalSource(List.of(ELI + "/"));
        m.setPrivacyProvisions(List.of(ELI + "/"));
        m.setIsPublic(false);
        m.setExactMatch(List.of("https://example.org/pojem/osoba/"));
        m.setBroaderConcept(List.of("https://example.org/pojem/subjekt/"));
        m.setAgendaCode("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A104/");
        m.setType("objekt");

        ConceptIriNormalizer.normalize(m);
        assertTrue(ConceptInputValidator.validate(m).isEmpty());
        Resource concept = new ConceptCreator().createSingleConcept(m);

        assertEquals(CONCEPT, concept.getURI());
        List<String> objects = concept.listProperties().mapWith(s -> s.getObject())
                .filterKeep(RDFNode::isURIResource).mapWith(o -> o.asResource().getURI()).toList();
        assertTrue(objects.contains(ELI));
        assertTrue(objects.contains("https://example.org/pojem/osoba"));
        assertTrue(objects.contains("https://example.org/pojem/subjekt"));
        assertTrue(objects.contains("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A104"));
        assertFalse(objects.stream().anyMatch(o -> o.endsWith("/")), objects.toString());
    }
}
