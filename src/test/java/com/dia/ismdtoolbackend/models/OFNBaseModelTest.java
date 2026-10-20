package com.dia.ismdtoolbackend.models;

import org.apache.jena.ontology.OntClass;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.ontology.OntProperty;
import org.apache.jena.ontology.impl.OntModelImpl;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.lang.reflect.Method;
import java.util.Set;

import static com.dia.ismdtoolbackend.constants.VocabularyConstants.*;
import static org.junit.jupiter.api.Assertions.*;

class OFNBaseModelTest {

    @Nested
    class ConstructorTests {

        @Test
        void testDefaultConstructor_initializeModelAndPrefixes() {
            // Test default constructor that creates minimal model with just POJEM
            OFNBaseModel baseModel = new OFNBaseModel();
            OntModel ontModel = baseModel.getOntModel();

            assertNotNull(ontModel, "OntModel should be initialized by constructor");
            assertInstanceOf(OntModelImpl.class, ontModel, "ontModel must be instance of OntModelImpl");
            assertEquals(OntModelSpec.OWL_MEM, ontModel.getSpecification(), "Model specification must be OWL_MEM");

            // Verify namespace prefixes
            assertEquals(DEFAULT_NS, ontModel.getNsPrefixURI("cz"), "Prefix 'cz' should correspond to DEFAULT_NS");
            assertEquals(RDF.getURI(), ontModel.getNsPrefixURI("rdf"), "Prefix 'rdf' should be registered");
            assertEquals(RDFS.getURI(), ontModel.getNsPrefixURI("rdfs"), "Prefix 'rdfs' should be registered");
            assertEquals(OWL2.getURI(), ontModel.getNsPrefixURI("owl"), "Prefix 'owl' should be registered");
            assertEquals(XSD, ontModel.getNsPrefixURI("xsd"), "Prefix 'xsd' should correspond to XSD");
            assertEquals(CAS_NS, ontModel.getNsPrefixURI("čas"), "Prefix 'čas' should correspond to CAS_NS");
            assertEquals(SLOVNIKY_NS, ontModel.getNsPrefixURI("slovníky"), "Prefix 'slovníky' should correspond to SLOVNIKY_NS");

            // Verify non-existent prefix returns null
            assertNull(ontModel.getNsPrefixURI("nonexistent"), "Non-existent prefix should return null");
        }

        @Test
        void testParameterizedConstructor_withRequiredSets() {
            Set<String> requiredClasses = Set.of(POJEM, TRIDA, "typ-vlastnosti");
            Set<String> requiredProperties = Set.of(NAZEV, POPIS);

            OFNBaseModel baseModel = new OFNBaseModel(requiredClasses, requiredProperties);
            OntModel ontModel = baseModel.getOntModel();

            assertNotNull(ontModel, "OntModel should be initialized");

            // Verify only required classes are created
            assertNotNull(ontModel.getOntClass(DEFAULT_NS + POJEM), "POJEM class should exist");
            assertNotNull(ontModel.getOntClass(DEFAULT_NS + TRIDA), "TRIDA class should exist");
            assertNotNull(ontModel.getOntClass(DEFAULT_NS + "typ-vlastnosti"), "typ-vlastnosti class should exist");

            // Verify non-required classes are not created
            assertNull(ontModel.getOntClass(DEFAULT_NS + TSP), "TSP class should not exist");
            assertNull(ontModel.getOntClass(DEFAULT_NS + TOP), "TOP class should not exist");
        }

        @Test
        void testConstructor_eachCallYieldsNewModel() {
            OntModel m1 = new OFNBaseModel().getOntModel();
            OntModel m2 = new OFNBaseModel().getOntModel();

            assertNotSame(m1, m2, "Each constructor call should return new model instance");
            assertEquals(m1.getNsPrefixURI("cz"), m2.getNsPrefixURI("cz"), "Prefixes 'cz' should be same");
            assertEquals(m1.getNsPrefixURI("rdf"), m2.getNsPrefixURI("rdf"), "Prefixes 'rdf' should be same");
        }
    }

    @Nested
    class DynamicModelCreationTests {

        @Test
        void testMinimalModel_onlyPOJEM() {
            Set<String> requiredClasses = Set.of(POJEM);
            Set<String> requiredProperties = Set.of();

            OFNBaseModel baseModel = new OFNBaseModel(requiredClasses, requiredProperties);
            OntModel ontModel = baseModel.getOntModel();

            // Should have POJEM
            OntClass pojemClass = ontModel.getOntClass(DEFAULT_NS + POJEM);
            assertNotNull(pojemClass, "POJEM class should exist");
            assertEquals(POJEM, pojemClass.getLabel("cs"), "POJEM should have correct label");

            // Should not have other classes
            assertNull(ontModel.getOntClass(DEFAULT_NS + TRIDA), "TRIDA should not exist");
            assertNull(ontModel.getOntClass(DEFAULT_NS + "typ-vlastnosti"), "typ-vlastnosti should not exist");
            assertNull(ontModel.getOntClass(DEFAULT_NS + VZTAH), "VZTAH should not exist");
        }

        @Test
        void testFullModel_allClassesAndProperties() {
            Set<String> requiredClasses = Set.of(POJEM, TRIDA, TSP, TOP, "typ-vlastnosti", 
                    DATOVY_TYP, UDAJ, VEREJNY_UDAJ, NEVEREJNY_UDAJ, POLOZKA_CISELNIKU,
                    ZPUSOBY_SDILENI_UDAJE, ZPUSOB_ZISKANI_UDAJE, "číselník");
            Set<String> requiredProperties = Set.of(NAZEV, POPIS, DEFINICE, ALTERNATIVNI_NAZEV,
                    DEFINUJICI_USTANOVENI, SOUVISEJICI_USTANOVENI, DEFINUJICI_NELEGISLATIVNI_ZDROJ,
                    SOUVISEJICI_NELEGISLATIVNI_ZDROJ, "schema:url", JE_PPDF,
                    AGENDA, AIS, USTANOVENI_NEVEREJNOST,
                    DEFINICNI_OBOR, OBOR_HODNOT, NADRAZENA_TRIDA,
                    ZPUSOB_SDILENI, ZPUSOB_ZISKANI, TYP_OBSAHU,
                    OKAMZIK_POSLEDNI_ZMENY, OKAMZIK_VYTVORENI, DATUM, DATUM_A_CAS);

            OFNBaseModel baseModel = new OFNBaseModel(requiredClasses, requiredProperties);
            OntModel ontModel = baseModel.getOntModel();

            // Verify all classes exist
            assertNotNull(ontModel.getOntClass(DEFAULT_NS + POJEM), "POJEM should exist");
            assertNotNull(ontModel.getOntClass(DEFAULT_NS + TRIDA), "TRIDA should exist");
            assertNotNull(ontModel.getOntClass(DEFAULT_NS + TSP), "TSP should exist");
            assertNotNull(ontModel.getOntClass(DEFAULT_NS + TOP), "TOP should exist");
            assertNotNull(ontModel.getOntClass(DEFAULT_NS + "typ-vlastnosti"), "typ-vlastnosti should exist");
            assertNotNull(ontModel.getOntClass(DEFAULT_NS + DATOVY_TYP), "DATOVY_TYP should exist");
            assertNotNull(ontModel.getOntClass(DEFAULT_NS + UDAJ), "UDAJ should exist");
            assertNotNull(ontModel.getOntClass(DEFAULT_NS + VEREJNY_UDAJ), "VEREJNY_UDAJ should exist");
            assertNotNull(ontModel.getOntClass(DEFAULT_NS + NEVEREJNY_UDAJ), "NEVEREJNY_UDAJ should exist");

            // Verify all properties exist
            assertNotNull(ontModel.getOntProperty(DEFAULT_NS + NAZEV), "NAZEV property should exist");
            assertNotNull(ontModel.getOntProperty(DEFAULT_NS + POPIS), "POPIS property should exist");
            assertNotNull(ontModel.getOntProperty(DEFAULT_NS + DEFINICE), "DEFINICE property should exist");
            assertNotNull(ontModel.getOntProperty(DEFAULT_NS + ALTERNATIVNI_NAZEV), "ALTERNATIVNI_NAZEV property should exist");

            // Verify temporal classes were created
            assertNotNull(ontModel.getOntClass(CAS_NS + CASOVY_OKAMZIK), "CASOVY_OKAMZIK should exist");
            assertNotNull(ontModel.getOntClass(SLOVNIKY_NS + SLOVNIK), "SLOVNIK should exist");
            assertNotNull(ontModel.getOntClass("http://schema.org/DigitalDocument"), "DigitalDocument should exist");
        }

        @Test
        void testClassHierarchy_subclassRelationships() {
            Set<String> requiredClasses = Set.of(POJEM, TRIDA, TSP, TOP, "typ-vlastnosti", UDAJ);

            OFNBaseModel baseModel = new OFNBaseModel(requiredClasses, Set.of());
            OntModel ontModel = baseModel.getOntModel();

            OntClass pojemClass = ontModel.getOntClass(DEFAULT_NS + POJEM);
            OntClass tridaClass = ontModel.getOntClass(DEFAULT_NS + TRIDA);
            OntClass tspClass = ontModel.getOntClass(DEFAULT_NS + TSP);
            OntClass topClass = ontModel.getOntClass(DEFAULT_NS + TOP);
            OntClass typVlastnostiClass = ontModel.getOntClass(DEFAULT_NS + "typ-vlastnosti");
            OntClass udajClass = ontModel.getOntClass(DEFAULT_NS + UDAJ);

            // Verify hierarchy relationships
            assertTrue(tridaClass.hasSuperClass(pojemClass), "TRIDA should be subclass of POJEM");
            assertTrue(tspClass.hasSuperClass(tridaClass), "TSP should be subclass of TRIDA");
            assertTrue(topClass.hasSuperClass(tridaClass), "TOP should be subclass of TRIDA");
            assertTrue(typVlastnostiClass.hasSuperClass(pojemClass), "typ-vlastnosti should be subclass of POJEM");
            assertTrue(udajClass.hasSuperClass(pojemClass), "UDAJ should be subclass of POJEM");
        }

        @Test
        void testConditionalClassCreation_missingParentClass() {
            // Try to create TSP without TRIDA - TSP won't be created because TRIDA is missing
            Set<String> requiredClasses = Set.of(POJEM, TSP);

            OFNBaseModel baseModel = new OFNBaseModel(requiredClasses, Set.of());
            OntModel ontModel = baseModel.getOntModel();

            OntClass pojemClass = ontModel.getOntClass(DEFAULT_NS + POJEM);
            OntClass tridaClass = ontModel.getOntClass(DEFAULT_NS + TRIDA);
            OntClass tspClass = ontModel.getOntClass(DEFAULT_NS + TSP);

            assertNotNull(pojemClass, "POJEM should exist");
            assertNull(tridaClass, "TRIDA should not be created when not explicitly requested");
            assertNull(tspClass, "TSP should not be created when parent TRIDA is missing");
        }

        @Test
        void testConditionalClassCreation_withParentClass() {
            // Create TSP with TRIDA explicitly included
            Set<String> requiredClasses = Set.of(POJEM, TRIDA, TSP);

            OFNBaseModel baseModel = new OFNBaseModel(requiredClasses, Set.of());
            OntModel ontModel = baseModel.getOntModel();

            OntClass pojemClass = ontModel.getOntClass(DEFAULT_NS + POJEM);
            OntClass tridaClass = ontModel.getOntClass(DEFAULT_NS + TRIDA);
            OntClass tspClass = ontModel.getOntClass(DEFAULT_NS + TSP);

            assertNotNull(pojemClass, "POJEM should exist");
            assertNotNull(tridaClass, "TRIDA should exist when explicitly requested");
            assertNotNull(tspClass, "TSP should exist when parent TRIDA is available");
            assertTrue(tspClass.hasSuperClass(tridaClass), "TSP should be subclass of TRIDA");
        }
    }

    @Nested
    class PropertyCreationTests {

        @Test
        void testPropertyCreation_withCorrectDomainRange() {
            Set<String> requiredClasses = Set.of(POJEM, UDAJ, NEVEREJNY_UDAJ);
            Set<String> requiredProperties = Set.of(NAZEV, POPIS, DEFINICE, ALTERNATIVNI_NAZEV, JE_PPDF,
                    AGENDA, AIS, USTANOVENI_NEVEREJNOST, DEFINUJICI_USTANOVENI, SOUVISEJICI_USTANOVENI,
                    DEFINUJICI_NELEGISLATIVNI_ZDROJ, SOUVISEJICI_NELEGISLATIVNI_ZDROJ, "schema:url");

            OFNBaseModel baseModel = new OFNBaseModel(requiredClasses, requiredProperties);
            OntModel ontModel = baseModel.getOntModel();

            OntClass pojemClass = ontModel.getOntClass(DEFAULT_NS + POJEM);
            OntClass neverejnyUdajClass = ontModel.getOntClass(DEFAULT_NS + NEVEREJNY_UDAJ);

            // Test string properties
            OntProperty nazevProp = ontModel.getOntProperty(DEFAULT_NS + NAZEV);
            assertNotNull(nazevProp, "NAZEV property should exist");
            assertEquals(NAZEV, nazevProp.getLabel("cs"), "NAZEV should have correct label");
            assertTrue(nazevProp.hasDomain(pojemClass), "NAZEV should have POJEM domain");
            assertTrue(nazevProp.hasRange(ontModel.getOntClass(XSD + "string")), "NAZEV should have string range");

            // Test langString property
            OntProperty alternativniNazevProp = ontModel.getOntProperty(DEFAULT_NS + ALTERNATIVNI_NAZEV);
            assertNotNull(alternativniNazevProp, "ALTERNATIVNI_NAZEV property should exist");
            assertTrue(alternativniNazevProp.hasRange(ontModel.getOntClass(RDF.getURI() + "langString")),
                    "ALTERNATIVNI_NAZEV should have langString range");

            // Test boolean property
            OntProperty ppdfProp = ontModel.getOntProperty(DEFAULT_NS + JE_PPDF);
            assertNotNull(ppdfProp, "JE_PPDF property should exist");
            assertTrue(ppdfProp.hasRange(ontModel.getOntClass(XSD + "boolean")), "JE_PPDF should have boolean range");

            // Test resource properties
            OntProperty agendaProp = ontModel.getOntProperty(DEFAULT_NS + AGENDA);
            assertNotNull(agendaProp, "AGENDA property should exist");
            assertTrue(agendaProp.hasRange(RDFS.Resource), "AGENDA should have Resource range");

            // Test legislative properties
            OntProperty definujiciUstanoveniProp = ontModel.getOntProperty(DEFAULT_NS + DEFINUJICI_USTANOVENI);
            assertNotNull(definujiciUstanoveniProp, "DEFINUJICI_USTANOVENI property should exist");
            assertTrue(definujiciUstanoveniProp.hasRange(RDFS.Resource), "DEFINUJICI_USTANOVENI should have Resource range");

            // Test schema:url property
            OntProperty schemaUrlProp = ontModel.getOntProperty("http://schema.org/url");
            assertNotNull(schemaUrlProp, "schema:url property should exist");
            assertTrue(schemaUrlProp.hasRange(ontModel.getOntClass(XSD + "anyURI")), "schema:url should have anyURI range");

            // Test property with specific domain
            OntProperty ustanoveniProp = ontModel.getOntProperty(DEFAULT_NS + USTANOVENI_NEVEREJNOST);
            assertNotNull(ustanoveniProp, "USTANOVENI_NEVEREJNOST property should exist");
            assertTrue(ustanoveniProp.hasDomain(neverejnyUdajClass), "USTANOVENI should have NEVEREJNY_UDAJ domain");
        }

        @Test
        void testPropertyCreation_withoutDomainRange() {
            Set<String> requiredProperties = Set.of(DEFINICNI_OBOR, OBOR_HODNOT, NADRAZENA_TRIDA);

            OFNBaseModel baseModel = new OFNBaseModel(Set.of(POJEM), requiredProperties);
            OntModel ontModel = baseModel.getOntModel();

            // These properties should exist but without specific domain/range
            OntProperty definicniOborProp = ontModel.getOntProperty(DEFAULT_NS + DEFINICNI_OBOR);
            assertNotNull(definicniOborProp, "DEFINICNI_OBOR property should exist");
            assertEquals(DEFINICNI_OBOR, definicniOborProp.getLabel("cs"), "Should have correct label");

            OntProperty oborHodnotProp = ontModel.getOntProperty(DEFAULT_NS + OBOR_HODNOT);
            assertNotNull(oborHodnotProp, "OBOR_HODNOT property should exist");

            OntProperty nadrazenaTrida = ontModel.getOntProperty(DEFAULT_NS + NADRAZENA_TRIDA);
            assertNotNull(nadrazenaTrida, "NADRAZENA_TRIDA property should exist");
        }

        @Test
        void testGovernanceProperties() {
            Set<String> requiredProperties = Set.of(ZPUSOB_SDILENI, ZPUSOB_ZISKANI, TYP_OBSAHU);

            OFNBaseModel baseModel = new OFNBaseModel(Set.of(POJEM), requiredProperties);
            OntModel ontModel = baseModel.getOntModel();

            OntClass pojemClass = ontModel.getOntClass(DEFAULT_NS + POJEM);

            OntProperty zpusobSdileniProp = ontModel.getOntProperty(DEFAULT_NS + ZPUSOB_SDILENI);
            assertNotNull(zpusobSdileniProp, "ZPUSOB_SDILENI property should exist");
            assertTrue(zpusobSdileniProp.hasDomain(pojemClass), "Should have POJEM domain");
            assertTrue(zpusobSdileniProp.hasRange(RDFS.Resource), "Should have Resource range");

            OntProperty zpusobZiskaniProp = ontModel.getOntProperty(DEFAULT_NS + ZPUSOB_ZISKANI);
            assertNotNull(zpusobZiskaniProp, "ZPUSOB_ZISKANI property should exist");

            OntProperty typObsahuProp = ontModel.getOntProperty(DEFAULT_NS + TYP_OBSAHU);
            assertNotNull(typObsahuProp, "TYP_OBSAHU property should exist");
        }

        @Test
        void testTemporalProperties() {
            Set<String> requiredProperties = Set.of(OKAMZIK_POSLEDNI_ZMENY, OKAMZIK_VYTVORENI, DATUM, DATUM_A_CAS);

            OFNBaseModel baseModel = new OFNBaseModel(Set.of(POJEM), requiredProperties);
            OntModel ontModel = baseModel.getOntModel();

            // Verify temporal classes were created
            OntClass casovyOkamzikClass = ontModel.getOntClass(CAS_NS + CASOVY_OKAMZIK);
            assertNotNull(casovyOkamzikClass, "CASOVY_OKAMZIK class should be created");

            OntClass slovnikClass = ontModel.getOntClass(SLOVNIKY_NS + SLOVNIK);
            assertNotNull(slovnikClass, "SLOVNIK class should be created");

            // Test temporal properties
            OntProperty okamzikPosledniZmenyProp = ontModel.getOntProperty(SLOVNIKY_NS + OKAMZIK_POSLEDNI_ZMENY);
            assertNotNull(okamzikPosledniZmenyProp, "OKAMZIK_POSLEDNI_ZMENY property should exist");
            assertTrue(okamzikPosledniZmenyProp.hasDomain(slovnikClass), "Should have SLOVNIK domain");
            assertTrue(okamzikPosledniZmenyProp.hasRange(casovyOkamzikClass), "Should have CASOVY_OKAMZIK range");

            OntProperty datumProp = ontModel.getOntProperty(CAS_NS + DATUM);
            assertNotNull(datumProp, "DATUM property should exist");
            assertTrue(datumProp.hasDomain(casovyOkamzikClass), "Should have CASOVY_OKAMZIK domain");
            assertTrue(datumProp.hasRange(ontModel.getOntClass(XSD + "date")), "Should have date range");
        }

        @Test
        void testPropertyCreation_missingPrerequisites() {
            // Try to create properties that require POJEM class but don't provide it
            Set<String> requiredProperties = Set.of(NAZEV, POPIS);

            OFNBaseModel baseModel = new OFNBaseModel(Set.of(), requiredProperties);
            OntModel ontModel = baseModel.getOntModel();

            // Properties should not be created because POJEM class is missing
            assertNull(ontModel.getOntProperty(DEFAULT_NS + NAZEV), "NAZEV should not exist without POJEM");
            assertNull(ontModel.getOntProperty(DEFAULT_NS + POPIS), "POPIS should not exist without POJEM");
        }

        @Test
        void testPropertyCreation_missingNEVEREJNY_UDAJ() {
            // Try to create USTANOVENI_NEVEREJNOST without NEVEREJNY_UDAJ class
            Set<String> requiredProperties = Set.of(USTANOVENI_NEVEREJNOST);

            OFNBaseModel baseModel = new OFNBaseModel(Set.of(POJEM), requiredProperties);
            OntModel ontModel = baseModel.getOntModel();

            // Property should not be created because NEVEREJNY_UDAJ class is missing
            assertNull(ontModel.getOntProperty(DEFAULT_NS + USTANOVENI_NEVEREJNOST),
                    "USTANOVENI should not exist without NEVEREJNY_UDAJ");
        }
    }

    @Nested
    class SupportMethodTests {

        @Test
        void testRequiresTemporalSupport_logic() throws Exception {
            OFNBaseModel baseModel = new OFNBaseModel();

            Method requiresTemporalSupportMethod = OFNBaseModel.class.getDeclaredMethod("requiresTemporalSupport", Set.class);
            requiresTemporalSupportMethod.setAccessible(true);

            assertTrue((Boolean) requiresTemporalSupportMethod.invoke(baseModel, Set.of(OKAMZIK_POSLEDNI_ZMENY)),
                    "OKAMZIK_POSLEDNI_ZMENY should require temporal support");
            assertTrue((Boolean) requiresTemporalSupportMethod.invoke(baseModel, Set.of(OKAMZIK_VYTVORENI)),
                    "OKAMZIK_VYTVORENI should require temporal support");
            assertTrue((Boolean) requiresTemporalSupportMethod.invoke(baseModel, Set.of(DATUM)),
                    "DATUM should require temporal support");
            assertTrue((Boolean) requiresTemporalSupportMethod.invoke(baseModel, Set.of(DATUM_A_CAS)),
                    "DATUM_A_CAS should require temporal support");

            assertFalse((Boolean) requiresTemporalSupportMethod.invoke(baseModel, Set.of(NAZEV)),
                    "NAZEV should not require temporal support");
        }

        @Test
        void testRequiresVocabularySupport_logic() throws Exception {
            OFNBaseModel baseModel = new OFNBaseModel();

            Method requiresVocabularySupportMethod = OFNBaseModel.class.getDeclaredMethod("requiresVocabularySupport", Set.class);
            requiresVocabularySupportMethod.setAccessible(true);

            assertTrue((Boolean) requiresVocabularySupportMethod.invoke(baseModel, Set.of(OKAMZIK_POSLEDNI_ZMENY)),
                    "OKAMZIK_POSLEDNI_ZMENY should require vocabulary support");
            assertTrue((Boolean) requiresVocabularySupportMethod.invoke(baseModel, Set.of(OKAMZIK_VYTVORENI)),
                    "OKAMZIK_VYTVORENI should require vocabulary support");

            assertFalse((Boolean) requiresVocabularySupportMethod.invoke(baseModel, Set.of(NAZEV)),
                    "NAZEV should not require vocabulary support");
        }

        @Test
        void testRequiresDigitalDocumentSupport_logic() throws Exception {
            OFNBaseModel baseModel = new OFNBaseModel();

            Method requiresDigitalDocumentSupportMethod = OFNBaseModel.class.getDeclaredMethod("requiresDigitalDocumentSupport", Set.class);
            requiresDigitalDocumentSupportMethod.setAccessible(true);

            assertTrue((Boolean) requiresDigitalDocumentSupportMethod.invoke(baseModel, Set.of(DEFINUJICI_NELEGISLATIVNI_ZDROJ)),
                    "DEFINUJICI_NELEGISLATIVNI_ZDROJ should require digital document support");
            assertTrue((Boolean) requiresDigitalDocumentSupportMethod.invoke(baseModel, Set.of(SOUVISEJICI_NELEGISLATIVNI_ZDROJ)),
                    "SOUVISEJICI_NELEGISLATIVNI_ZDROJ should require digital document support");
            assertTrue((Boolean) requiresDigitalDocumentSupportMethod.invoke(baseModel, Set.of("schema:url")),
                    "schema:url should require digital document support");

            assertFalse((Boolean) requiresDigitalDocumentSupportMethod.invoke(baseModel, Set.of(NAZEV)),
                    "NAZEV should not require digital document support");
        }
    }

    @Nested
    class ReflectionTests {

        @Test
        void testCreateDynamicBaseModel_reflection() throws Exception {
            // Create instance but don't use constructor to populate model
            OFNBaseModel baseModel = new OFNBaseModel(Set.of(), Set.of()); // Empty model

            // Use reflection to call createDynamicBaseModel with specific parameters
            Method createDynamicBaseModelMethod = OFNBaseModel.class.getDeclaredMethod(
                    "createDynamicBaseModel", Set.class, Set.class);
            createDynamicBaseModelMethod.setAccessible(true);

            Set<String> testClasses = Set.of(POJEM, TRIDA);
            Set<String> testProperties = Set.of(NAZEV);

            // Invoke the method
            createDynamicBaseModelMethod.invoke(baseModel, testClasses, testProperties);

            OntModel ontModel = baseModel.getOntModel();

            // Verify the method created the expected classes
            assertNotNull(ontModel.getOntClass(DEFAULT_NS + POJEM), "POJEM should be created");
            assertNotNull(ontModel.getOntClass(DEFAULT_NS + TRIDA), "TRIDA should be created");
            assertNotNull(ontModel.getOntProperty(DEFAULT_NS + NAZEV), "NAZEV property should be created");
        }

        @Test
        void testCreateRequiredProperties_reflection() throws Exception {
            OFNBaseModel baseModel = new OFNBaseModel(Set.of(POJEM), Set.of());
            OntModel ontModel = baseModel.getOntModel();

            OntClass pojemClass = ontModel.getOntClass(DEFAULT_NS + POJEM);

            Method createRequiredPropertiesMethod = OFNBaseModel.class.getDeclaredMethod(
                    "createRequiredProperties", Set.class, OntClass.class, OntClass.class,
                    OntClass.class, OntClass.class, OntClass.class);
            createRequiredPropertiesMethod.setAccessible(true);

            Set<String> testProperties = Set.of(NAZEV, POPIS);

            // Invoke the method with all parameters (null for unused classes)
            createRequiredPropertiesMethod.invoke(baseModel, testProperties, pojemClass, null, null, null, null);

            // Verify properties were created
            assertNotNull(ontModel.getOntProperty(DEFAULT_NS + NAZEV), "NAZEV should be created");
            assertNotNull(ontModel.getOntProperty(DEFAULT_NS + POPIS), "POPIS should be created");
        }
    }

    @Test
    void testUnknownProperty_loggedButIgnored() {
        // Test that unknown properties are logged but don't cause errors
        Set<String> requiredProperties = Set.of("unknown-property", NAZEV);

        // This should not throw an exception
        assertDoesNotThrow(() -> {
            new OFNBaseModel(Set.of(POJEM), requiredProperties);
        }, "Unknown properties should not cause exceptions");
    }
}