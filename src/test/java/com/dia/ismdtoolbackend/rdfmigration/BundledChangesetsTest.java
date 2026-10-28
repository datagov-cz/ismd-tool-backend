package com.dia.ismdtoolbackend.rdfmigration;

import com.dia.ismdtoolbackend.testsupport.rdf.Ttl;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.update.UpdateAction;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each bundled changeset on a small graph: the old shapes in, the OFN shapes out, a second run changes
 * nothing, and nothing outside the vocabulary's own concepts is touched.
 */
class BundledChangesetsTest {

    static final String V = "https://slovník.gov.cz/datový/test-slovník";
    private static final List<RdfChangeset> BUNDLED = new RdfChangesetLoader(RdfChangesetLoader.DEFAULT_LOCATION).load();

    /** Applies the changeset with {@code id} to {@code before} (in graph {@link #V}) twice; checks the result. */
    static void assertMigrates(String id, String before, String after) {
        RdfChangeset changeset = BUNDLED.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
        Dataset dataset = DatasetFactory.createTxnMem();
        dataset.getNamedModel(V).add(Ttl.parse(before));
        Model other = Ttl.parse(before);
        dataset.getNamedModel(V + "-jiný").add(other);

        UpdateAction.parseExecute(changeset.boundTo(V), dataset);
        Model once = ModelFactory.createDefaultModel().add(dataset.getNamedModel(V));
        UpdateAction.parseExecute(changeset.boundTo(V), dataset);

        Model expected = Ttl.parse(after);
        assertThat(once.isIsomorphicWith(expected))
                .as("missing: %s%nextra: %s", expected.difference(once), once.difference(expected)).isTrue();
        assertThat(dataset.getNamedModel(V).isIsomorphicWith(once)).as("second run changed the graph").isTrue();
        assertThat(dataset.getNamedModel(V + "-jiný").isIsomorphicWith(other))
                .as("a changeset bound to one graph touched another").isTrue();
    }

    @Test
    void bundledChangesetsAreTheExpectedOnesInOrder() {
        assertThat(BUNDLED).extracting(RdfChangeset::id).containsExactly(
                "001-trida-owl-class", "002-dual-typed-property", "003-legal-type-vsgov",
                "004-privacy-provision-ofn", "005-non-legal-source-ofn", "006-source-url-anyuri",
                "007-drop-nadrazena-trida", "008-drop-in-tezaurus", "009-ppdf-boolean",
                "010-drop-junk-pojem-class", "011-creation-date-instant", "012-foreign-concept-in-scheme",
                "013-derive-role-markers");
    }

    @Nested
    class TridaOwlClass {

        private static final String ID = "001-trida-owl-class";

        @Test
        void markerOnlyTridaGetsOwlClass() {
            assertMigrates(ID, """
                    <%1$s/pojem/osoba> a slovníky:pojem, skos:Concept, slovníky:třída ; skos:inScheme <%1$s> .
                    """.formatted(V), """
                    <%1$s/pojem/osoba> a slovníky:pojem, skos:Concept, slovníky:třída, owl:Class ; skos:inScheme <%1$s> .
                    """.formatted(V));
        }

        @Test
        void conceptsWithAnOwlKindAreLeftAlone() {
            String graph = """
                    <%1$s/pojem/a> a slovníky:třída, owl:Class .
                    <%1$s/pojem/b> a slovníky:třída, owl:ObjectProperty .
                    <%1$s/pojem/c> a slovníky:vlastnost .
                    """.formatted(V);
            assertMigrates(ID, graph, graph);
        }

        @Test
        void foreignOrPrefixSharingConceptsAreLeftAlone() {
            String graph = """
                    <https://slovník.gov.cz/jiný/pojem/x> a slovníky:třída .
                    <%1$s-dva/pojem/y> a slovníky:třída .
                    """.formatted(V);
            assertMigrates(ID, graph, graph);
        }
    }

    private static final String C = V + "/pojem/osoba";

    @Nested
    class DualTypedProperty {

        @Test
        void objectPropertyAndVztahMarkerAreRemovedFromADeclaredVlastnost() {
            assertMigrates("002-dual-typed-property", """
                    <%s> a slovníky:vlastnost, slovníky:vztah, owl:DatatypeProperty, owl:ObjectProperty .
                    """.formatted(C), """
                    <%s> a slovníky:vlastnost, owl:DatatypeProperty .
                    """.formatted(C));
        }

        @Test
        void bothKindsWithoutTheVlastnostMarkerAreLeftForTheGate() {
            String graph = "<%s> a owl:DatatypeProperty, owl:ObjectProperty .".formatted(C);
            assertMigrates("002-dual-typed-property", graph, graph);
        }
    }

    @Nested
    class LegalTypeVsgov {

        @Test
        void legacyTypesBecomeVsgov() {
            assertMigrates("003-legal-type-vsgov", """
                    <%1$s> a owl:Class, slovníky:typ-subjektu-práva .
                    <%1$s-2> a owl:Class, slovníky:typ-objektu-práva .
                    """.formatted(C), """
                    <%1$s> a owl:Class, vsgov:typ-subjektu-práva .
                    <%1$s-2> a owl:Class, vsgov:typ-objektu-práva .
                    """.formatted(C));
        }
    }

    @Nested
    class PrivacyProvision {

        private static final String ELI = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187";

        @Test
        void provisionsOfANeverejnyUdajMoveToTheOfnPredicate() {
            assertMigrates("004-privacy-provision-ofn", """
                    <%1$s> a l111:neveřejný-údaj ;
                        l111:ustanovení-dokládající-neveřejnost-údaje <%2$s> ;
                        <%3$s/ustanovení-dokládající-neveřejnost-údaje> <%2$s> ;
                        <https://slovník.gov.cz/jiný/je-vymezen-ustanovením-stanovujícím-jeho-neveřejnost> <%2$s-b> .
                    """.formatted(C, ELI, V), """
                    <%1$s> a l111:neveřejný-údaj ;
                        l111:je-vymezen-ustanovením-stanovujícím-jeho-neveřejnost <%2$s>, <%2$s-b> .
                    """.formatted(C, ELI));
        }

        @Test
        void legacyProvisionsOfAnyOtherConceptAreDropped() {
            assertMigrates("004-privacy-provision-ofn", """
                    <%1$s> a l111:veřejný-údaj ; l111:ustanovení-dokládající-neveřejnost-údaje <%2$s> .
                    <%1$s-2> a owl:Class ; <%3$s/ustanovení-dokládající-neveřejnost-údaje> <%2$s> .
                    """.formatted(C, ELI, V), """
                    <%1$s> a l111:veřejný-údaj .
                    <%1$s-2> a owl:Class .
                    """.formatted(C));
        }

        @Test
        void ofnProvisionsAreLeftAlone() {
            String graph = """
                    <%s> a l111:neveřejný-údaj ; l111:je-vymezen-ustanovením-stanovujícím-jeho-neveřejnost <%s> .
                    """.formatted(C, ELI);
            assertMigrates("004-privacy-provision-ofn", graph, graph);
        }
    }

    @Nested
    class NonLegalSources {

        @Test
        void legacySourceLinksMoveAndKeepTheirDocument() {
            assertMigrates("005-non-legal-source-ofn", """
                    <%1$s> <%2$s/definující-nelegislativní-zdroj> [ a do:digitální-objekt ; schema:url <https://example.org/d> ] ;
                        sl:související-nelegislativní-zdroj [ a do:digitální-objekt ; dct:title "R"@cs ] .
                    """.formatted(C, V), """
                    <%1$s> slovníky:definující-nelegislativní-zdroj [ a do:digitální-objekt ; schema:url <https://example.org/d> ] ;
                        slovníky:související-nelegislativní-zdroj [ a do:digitální-objekt ; dct:title "R"@cs ] .
                    """.formatted(C));
        }

        @Test
        void urlsBecomeAnyUriLiterals() {
            assertMigrates("006-source-url-anyuri", """
                    <%1$s> slovníky:definující-nelegislativní-zdroj [ schema:url <https://example.org/d> ] ;
                        slovníky:související-nelegislativní-zdroj [ schema:url "https://example.org/r" ],
                            [ schema:url "https://example.org/ok"^^xsd:anyURI ] .
                    """.formatted(C), """
                    <%1$s> slovníky:definující-nelegislativní-zdroj [ schema:url "https://example.org/d"^^xsd:anyURI ] ;
                        slovníky:související-nelegislativní-zdroj [ schema:url "https://example.org/r"^^xsd:anyURI ],
                            [ schema:url "https://example.org/ok"^^xsd:anyURI ] .
                    """.formatted(C));
        }
    }

    @Nested
    class InternalPredicates {

        @Test
        void nadrazenaTridaBecomesSubClassOfOnly() {
            assertMigrates("007-drop-nadrazena-trida", """
                    <%1$s> rdfs:subClassOf <%1$s-a> ; <%2$s/nadřazená-třída> <%1$s-a> ; sl:nadřazená-třída <%1$s-b> .
                    """.formatted(C, V), """
                    <%1$s> rdfs:subClassOf <%1$s-a>, <%1$s-b> .
                    """.formatted(C));
        }

        @Test
        void inTezaurusIsRemoved() {
            assertMigrates("008-drop-in-tezaurus", """
                    <%1$s> a owl:Class ; <%2$s/inTezaurus> "true" ; sl:inTezaurus "false" .
                    """.formatted(C, V), """
                    <%s> a owl:Class .
                    """.formatted(C));
        }

        @Test
        void ppdfTextBecomesBoolean() {
            assertMigrates("009-ppdf-boolean", """
                    <%1$s> a104:je-sdílen-v-propojeném-datovém-fondu "True" .
                    <%1$s-2> a104:je-sdílen-v-propojeném-datovém-fondu false .
                    <%1$s-3> a104:je-sdílen-v-propojeném-datovém-fondu "možná" .
                    """.formatted(C), """
                    <%1$s> a104:je-sdílen-v-propojeném-datovém-fondu true .
                    <%1$s-2> a104:je-sdílen-v-propojeném-datovém-fondu false .
                    <%1$s-3> a104:je-sdílen-v-propojeném-datovém-fondu "možná" .
                    """.formatted(C));
        }

        @Test
        void junkPojemClassIsRemoved() {
            assertMigrates("010-drop-junk-pojem-class", """
                    <https://slovník.gov.cz/pojem> a owl:Class ; rdfs:label "pojem"@cs .
                    <%s> a owl:Class .
                    """.formatted(C), """
                    <%s> a owl:Class .
                    """.formatted(C));
        }
    }

    @Nested
    class CreationDate {

        @Test
        void namedInstantWithoutAZoneBecomesABlankUtcDateTimeStamp() {
            assertMigrates("011-creation-date-instant", """
                    <%1$s> slovníky:okamžik-vytvoření <%1$s/casovy-okamzik-vytvoreni> .
                    <%1$s/casovy-okamzik-vytvoreni> a čas:časový-okamžik ; čas:datum-a-čas "2026-10-07T13:22:28.098731" .
                    """.formatted(V), """
                    <%s> slovníky:okamžik-vytvoření [ a čas:časový-okamžik ;
                        čas:datum-a-čas "2026-10-07T13:22:28.098731Z"^^xsd:dateTimeStamp ] .
                    """.formatted(V));
        }

        @Test
        void valueThatHasAZoneKeepsIt() {
            assertMigrates("011-creation-date-instant", """
                    <%1$s> slovníky:okamžik-vytvoření [ a čas:časový-okamžik ; čas:datum-a-čas "2026-10-07T13:22:28+02:00" ] .
                    """.formatted(V), """
                    <%s> slovníky:okamžik-vytvoření [ a čas:časový-okamžik ;
                        čas:datum-a-čas "2026-10-07T13:22:28+02:00"^^xsd:dateTimeStamp ] .
                    """.formatted(V));
        }

        @Test
        void ofnDatesAndNonDateTimesAreLeftAlone() {
            String graph = """
                    <%1$s> slovníky:okamžik-vytvoření [ a čas:časový-okamžik ;
                        čas:datum-a-čas "2026-10-07T13:22:28+01:00"^^xsd:dateTimeStamp ] ;
                      slovníky:okamžik-poslední-změny <%1$s/změna> .
                    <%1$s/změna> čas:datum-a-čas "včera" .
                    """.formatted(V);
            assertMigrates("011-creation-date-instant", graph, graph);
        }
    }

    @Nested
    class ForeignConcepts {

        @Test
        void foreignConceptsLeaveTheSchemeAndKeepTheRest() {
            assertMigrates("012-foreign-concept-in-scheme", """
                    <https://slovník.gov.cz/legislativní/sbírka/111/2009/pojem/fyzická-osoba> a owl:Class ;
                        skos:inScheme <%1$s> ; skos:prefLabel "Fyzická osoba"@cs .
                    <%1$s-dva/pojem/x> skos:inScheme <%1$s> .
                    <%2$s> skos:inScheme <%1$s> .
                    """.formatted(V, C), """
                    <https://slovník.gov.cz/legislativní/sbírka/111/2009/pojem/fyzická-osoba> a owl:Class ;
                        skos:prefLabel "Fyzická osoba"@cs .
                    <%2$s> skos:inScheme <%1$s> .
                    """.formatted(V, C));
        }
    }

    @Nested
    class RoleMarkers {

        @Test
        void markersFollowTheSingleOwlKind() {
            assertMigrates("013-derive-role-markers", """
                    <%1$s-a> a owl:Class .
                    <%1$s-b> a owl:DatatypeProperty, slovníky:vztah .
                    <%1$s-c> a owl:ObjectProperty, slovníky:vztah .
                    """.formatted(C), """
                    <%1$s-a> a owl:Class, slovníky:třída .
                    <%1$s-b> a owl:DatatypeProperty, slovníky:vlastnost .
                    <%1$s-c> a owl:ObjectProperty, slovníky:vztah .
                    """.formatted(C));
        }

        @Test
        void conceptsWithNoOrSeveralKindsAreLeftForTheGate() {
            String graph = """
                    <%1$s-a> a slovníky:třída .
                    <%1$s-b> a owl:Class, owl:ObjectProperty, slovníky:vztah .
                    """.formatted(C);
            assertMigrates("013-derive-role-markers", graph, graph);
        }
    }
}
