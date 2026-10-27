package com.dia.ismdtoolbackend.utility.validation;

import com.dia.ismdtoolbackend.exception.OntologyUploadIriCollisionException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class UploadIriNormalizerTest {

    private static final String PREFIXES = "@prefix owl: <http://www.w3.org/2002/07/owl#> ."
            + " @prefix skos: <http://www.w3.org/2004/02/skos/core#> ."
            + " @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> ."
            + " @prefix ofn: <https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/> . ";
    private static final String VOCABULARY = "https://slovník.gov.cz/agendový/999";
    private static final String CONCEPT = VOCABULARY + "/pojem/osoba";
    private static final String ELI =
            "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2005/348/2026-01-01/dokument/norma/cast_1/par_2/odst_1";

    private static Model model(String turtle) {
        Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, new ByteArrayInputStream((PREFIXES + turtle).getBytes(StandardCharsets.UTF_8)), Lang.TURTLE);
        return model;
    }

    private static boolean has(Model model, String subject, String predicate, String object) {
        return model.contains(model.createResource(subject), model.createProperty(predicate), model.createResource(object));
    }

    private static boolean isSubject(Model model, String iri) {
        return model.contains(model.createResource(iri), null, (RDFNode) null);
    }

    @Test
    void renamesVocabularyAndConceptAndRewritesReferencesToThem() {
        Model model = model("<" + VOCABULARY + "/> a owl:Ontology, skos:ConceptScheme ."
                + " <" + CONCEPT + "/> a ofn:pojem, owl:Class ; skos:inScheme <" + VOCABULARY + "/> ."
                + " <" + VOCABULARY + "/pojem/student> a ofn:pojem, owl:Class ; rdfs:subClassOf <" + CONCEPT + "/> .");

        List<String> corrected = UploadIriNormalizer.normalize(model);

        assertThat(corrected).isEqualTo(List.of(VOCABULARY + "/", CONCEPT + "/"));
        assertThat(isSubject(model, VOCABULARY)).isTrue();
        assertThat(isSubject(model, VOCABULARY + "/")).isFalse();
        assertThat(has(model, CONCEPT, SKOS_IN_SCHEME, VOCABULARY)).isTrue();
        assertThat(has(model, VOCABULARY + "/pojem/student", RDFS_SUB_CLASS_OF, CONCEPT)).isTrue();
        assertThat(model.listStatements().toList().toString()).doesNotContain(CONCEPT + "/");
    }

    @Test
    void stripsReferencedIrisInOfnAndVocabularyNamespaces() {
        Model model = model("<" + CONCEPT + "> a ofn:pojem ;"
                + " ofn:definující-ustanovení <" + ELI + "/> ;"
                + " <" + VOCABULARY + "/související-ustanovení> <" + ELI + "/> ;"
                + " <" + VOCABULARY + "/ustanovení-dokládající-neveřejnost-údaje> <" + ELI + "/> ;"
                + " <" + VOCABULARY + "/agenda> <https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A104/> ;"
                + " skos:exactMatch <https://example.org/pojem/osoba/> ;"
                + " rdfs:domain <https://example.org/pojem/subjekt/> .");

        List<String> corrected = UploadIriNormalizer.normalize(model);

        assertThat(corrected).hasSize(4);
        assertThat(has(model, CONCEPT, OFN + "definující-ustanovení", ELI)).isTrue();
        assertThat(has(model, CONCEPT, VOCABULARY + "/související-ustanovení", ELI)).isTrue();
        assertThat(has(model, CONCEPT, VOCABULARY + "/ustanovení-dokládající-neveřejnost-údaje", ELI)).isTrue();
        assertThat(has(model, CONCEPT, VOCABULARY + "/agenda", "https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A104"))
                .isTrue();
        assertThat(has(model, CONCEPT, "http://www.w3.org/2004/02/skos/core#exactMatch", "https://example.org/pojem/osoba"))
                .isTrue();
        assertThat(has(model, CONCEPT, "http://www.w3.org/2000/01/rdf-schema#domain", "https://example.org/pojem/subjekt"))
                .isTrue();
    }

    @Test
    void leavesExternalAddressesAndSlashlessIrisUntouched() {
        String turtle = "<" + VOCABULARY + "> a owl:Ontology ."
                + " <" + CONCEPT + "> a ofn:pojem ;"
                + " ofn:definující-ustanovení <" + ELI + "> ;"
                + " ofn:má-instance-definované-číselníkem <https://example.org/ciselnik/> ;"
                + " <" + VOCABULARY + "/definující-nelegislativní-zdroj> [ <http://schema.org/url> <https://example.org/dokument/> ] ."
                + " <https://example.org/ciselnik/> <https://example.org/datova-sada> <https://example.org/sada/> .";
        Model model = model(turtle);
        Model before = model(turtle);

        assertThat(UploadIriNormalizer.normalize(model)).isEmpty();
        assertThat(model.isIsomorphicWith(before)).isTrue();
    }

    @Test
    void referenceToTheSlashlessFormIsNotACollision() {
        Model model = model("<" + CONCEPT + "/> a ofn:pojem, owl:Class ."
                + " <" + VOCABULARY + "/pojem/student> a ofn:pojem ; rdfs:subClassOf <" + CONCEPT + "> .");

        assertThat(UploadIriNormalizer.normalize(model)).isEqualTo(List.of(CONCEPT + "/"));
        assertThat(isSubject(model, CONCEPT)).isTrue();
    }

    @Test
    void rejectsAFileHoldingBothForms() {
        Model model = model("<" + CONCEPT + "/> a ofn:pojem ; skos:prefLabel \"A\"@cs ."
                + " <" + CONCEPT + "> a ofn:pojem ; skos:prefLabel \"B\"@cs .");
        long sizeBefore = model.size();

        OntologyUploadIriCollisionException e = assertThatExceptionOfType(OntologyUploadIriCollisionException.class)
                .isThrownBy(() -> UploadIriNormalizer.normalize(model))
                .actual();

        assertThat(e).hasMessageContaining(CONCEPT + "/");
        assertThat(model.size()).isEqualTo(sizeBefore);
        assertThat(isSubject(model, CONCEPT + "/")).isTrue();
    }

    private static final String OFN = "https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/";
    private static final String SKOS_IN_SCHEME = "http://www.w3.org/2004/02/skos/core#inScheme";
    private static final String RDFS_SUB_CLASS_OF = "http://www.w3.org/2000/01/rdf-schema#subClassOf";
}
