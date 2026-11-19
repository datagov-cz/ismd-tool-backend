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

import static org.assertj.core.api.Assertions.assertThat;

class ConceptIriNormalizerTest {

    private static final String ELI =
            "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2005/348/2026-01-01/dokument/norma/cast_1/par_2/odst_1";
    private static final String CONCEPT = "https://slovník.gov.cz/agendový/104/pojem/osoba";

    @Test
    void iri_stripsTrailingSlash() {
        assertThat(ConceptIriNormalizer.iri(ELI + "/")).isEqualTo(ELI);
        assertThat(ConceptIriNormalizer.iri("  " + ELI + "//  ")).isEqualTo(ELI);
    }

    @Test
    void iri_leavesSlashlessIriUntouched() {
        assertThat(ConceptIriNormalizer.iri(ELI)).isEqualTo(ELI);
    }

    @Test
    void iri_leavesNonIriValuesAsSupplied() {
        assertThat(ConceptIriNormalizer.iri(null)).isNull();
        assertThat(ConceptIriNormalizer.iri("")).isEqualTo("");
        assertThat(ConceptIriNormalizer.iri("Nadřazená třída/")).isEqualTo("Nadřazená třída/");
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

        assertThat(m.getIdentifier()).isEqualTo(CONCEPT);
        assertThat(m.getDefiningLegalSource()).isEqualTo(List.of(ELI));
        assertThat(m.getRelatedLegalSource()).isEqualTo(Arrays.asList(ELI, null, ELI));
        assertThat(m.getExactMatch()).isEqualTo(List.of(CONCEPT));
        assertThat(m.getPrivacyProvisions()).isEqualTo(List.of(ELI));
        assertThat(m.getBroaderConcept()).isEqualTo(List.of(CONCEPT));
    }

    @Test
    void create_property() {
        PropertyConceptModel m = new PropertyConceptModel();
        m.setPrivacyProvisions(List.of(ELI + "/"));
        m.setDomain(CONCEPT + "/");
        m.setSuperProperty(List.of(CONCEPT + "/"));
        m.setDataType("https://example.org/datatype/");

        ConceptIriNormalizer.normalize(m);

        assertThat(m.getPrivacyProvisions()).isEqualTo(List.of(ELI));
        assertThat(m.getDomain()).isEqualTo(CONCEPT);
        assertThat(m.getSuperProperty()).isEqualTo(List.of(CONCEPT));
        assertThat(m.getDataType()).isEqualTo("https://example.org/datatype");
    }

    @Test
    void create_relationship() {
        RelationshipConceptModel m = new RelationshipConceptModel();
        m.setPrivacyProvisions(List.of(ELI + "/"));
        m.setDomain(CONCEPT + "/");
        m.setRange(CONCEPT + "/");
        m.setSuperRelation(List.of(CONCEPT + "/"));

        ConceptIriNormalizer.normalize(m);

        assertThat(m.getPrivacyProvisions()).isEqualTo(List.of(ELI));
        assertThat(m.getDomain()).isEqualTo(CONCEPT);
        assertThat(m.getRange()).isEqualTo(CONCEPT);
        assertThat(m.getSuperRelation()).isEqualTo(List.of(CONCEPT));
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

        assertThat(m.getIdentifier()).isEqualTo(CONCEPT);
        assertThat(m.getDefiningLegalSource()).isEqualTo(List.of(ELI));
        assertThat(m.getRelatedLegalSource()).isEqualTo(List.of(ELI));
        assertThat(m.getExactMatch()).isEqualTo(List.of(CONCEPT));
        assertThat(m.getPrivacyProvisions()).isEqualTo(List.of(ELI));
        assertThat(m.getBroaderConcept()).isEqualTo(List.of(CONCEPT));
    }

    @Test
    void edit_property() {
        PropertyConceptEditModel m = new PropertyConceptEditModel();
        m.setPrivacyProvisions(List.of(ELI + "/"));
        m.setDomain(CONCEPT + "/");
        m.setSuperProperty(List.of(CONCEPT + "/"));

        ConceptIriNormalizer.normalize(m);

        assertThat(m.getPrivacyProvisions()).isEqualTo(List.of(ELI));
        assertThat(m.getDomain()).isEqualTo(CONCEPT);
        assertThat(m.getSuperProperty()).isEqualTo(List.of(CONCEPT));
    }

    @Test
    void edit_relationship() {
        RelationshipConceptEditModel m = new RelationshipConceptEditModel();
        m.setPrivacyProvisions(List.of(ELI + "/"));
        m.setDomain(CONCEPT + "/");
        m.setRange(CONCEPT + "/");
        m.setSuperRelation(List.of(CONCEPT + "/"));

        ConceptIriNormalizer.normalize(m);

        assertThat(m.getPrivacyProvisions()).isEqualTo(List.of(ELI));
        assertThat(m.getDomain()).isEqualTo(CONCEPT);
        assertThat(m.getRange()).isEqualTo(CONCEPT);
        assertThat(m.getSuperRelation()).isEqualTo(List.of(CONCEPT));
    }

    @Test
    void nullFieldsStayNull() {
        ClassConceptEditModel m = new ClassConceptEditModel();

        ConceptIriNormalizer.normalize(m);

        assertThat(m.getIdentifier()).isNull();
        assertThat(m.getDefiningLegalSource()).isNull();
        assertThat(m.getPrivacyProvisions()).isNull();
        assertThat(m.getBroaderConcept()).isNull();
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

        assertThat(m.getDefiningNonLegalSource().get(0).getUrl()).isEqualTo("https://example.org/dokument/");
        assertThat(m.getCodeListIri()).isEqualTo("https://example.org/ciselnik/");
        assertThat(m.getCodeListDataset()).isEqualTo("https://example.org/datova-sada/");
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
        assertThat(ConceptInputValidator.validate(m)).isEmpty();
        Resource concept = new ConceptCreator().createSingleConcept(m);

        assertThat(concept.getURI()).isEqualTo(CONCEPT);
        List<String> objects = concept.listProperties().mapWith(s -> s.getObject())
                .filterKeep(RDFNode::isURIResource).mapWith(o -> o.asResource().getURI()).toList();
        assertThat(objects).contains(ELI);
        assertThat(objects).contains("https://example.org/pojem/osoba");
        assertThat(objects).contains("https://example.org/pojem/subjekt");
        assertThat(objects).contains("https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A104");
        assertThat(objects.stream().anyMatch(o -> o.endsWith("/"))).as(objects.toString()).isFalse();
    }
}
