package com.dia.ismdtoolbackend;

import com.dia.models.OFNBaseModel;
import org.apache.jena.ontology.OntClass;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;

import static com.dia.constants.ArchiConstants.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("local")
class OFNBaseModelSaveTest {

    @Autowired
    private Dataset jenaDataset;

    @Test
    void testCreateBasicOFNModel() {
        OFNBaseModel basicModel = new OFNBaseModel();

        assertNotNull(basicModel.getOntModel());
        assertFalse(basicModel.getOntModel().isEmpty());

        OntClass pojemClass = basicModel.getOntModel().getOntClass(DEFAULT_NS + POJEM);
        assertNotNull(pojemClass, "POJEM class should be created");
        assertEquals(POJEM, pojemClass.getLabel("cs"), "POJEM should have correct Czech label");

        System.out.println("Basic model created with " + basicModel.getOntModel().size() + " triples");
    }

    @Test
    void testCreateCompleteOFNModel() {
        Set<String> requiredClasses = Set.of(
                POJEM, TRIDA, TSP, TOP, VLASTNOST, VZTAH,
                DATOVY_TYP, VEREJNY_UDAJ, NEVEREJNY_UDAJ
        );

        Set<String> requiredProperties = Set.of(
                NAZEV, ALTERNATIVNI_NAZEV, POPIS, DEFINICE,
                DEFINUJICI_USTANOVENI, SOUVISEJICI_USTANOVENI,
                DEFINUJICI_NELEGISLATIVNI_ZDROJ, SOUVISEJICI_NELEGISLATIVNI_ZDROJ,
                "schema:url", JE_PPDF, AGENDA, AIS,
                USTANOVENI_NEVEREJNOST, DEFINICNI_OBOR, OBOR_HODNOT,
                NADRAZENA_TRIDA, ZPUSOB_SDILENI, ZPUSOB_ZISKANI, TYP_OBSAHU,
                OKAMZIK_POSLEDNI_ZMENY, OKAMZIK_VYTVORENI, DATUM, DATUM_A_CAS
        );

        OFNBaseModel completeModel = new OFNBaseModel(requiredClasses, requiredProperties);

        assertNotNull(completeModel.getOntModel());
        assertTrue(completeModel.getOntModel().size() > 50, "Complete model should have many triples");

        // Verify key classes exist
        assertNotNull(completeModel.getOntModel().getOntClass(DEFAULT_NS + POJEM));
        assertNotNull(completeModel.getOntModel().getOntClass(DEFAULT_NS + TRIDA));
        assertNotNull(completeModel.getOntModel().getOntClass(DEFAULT_NS + TSP));
        assertNotNull(completeModel.getOntModel().getOntClass(DEFAULT_NS + TOP));

        // Verify key properties exist
        assertNotNull(completeModel.getOntModel().getOntProperty(DEFAULT_NS + NAZEV));
        assertNotNull(completeModel.getOntModel().getOntProperty(DEFAULT_NS + POPIS));
        assertNotNull(completeModel.getOntModel().getOntProperty(DEFAULT_NS + DEFINICE));

        System.out.println("Complete model created with " + completeModel.getOntModel().size() + " triples");
    }

    @Test
    void testSaveModelToTDB2Database() {
        Set<String> testClasses = Set.of(POJEM, TRIDA, VLASTNOST);
        Set<String> testProperties = Set.of(NAZEV, POPIS, DEFINICE, JE_PPDF);

        OFNBaseModel testModel = new OFNBaseModel(testClasses, testProperties);

        String namedGraphURI = "http://example.org/test-ontology";
        jenaDataset.executeWrite(() -> {
            Model namedModel = jenaDataset.getNamedModel(namedGraphURI);
            namedModel.removeAll();
            namedModel.add(testModel.getOntModel());

            System.out.println("Saved model to named graph: " + namedGraphURI);
            System.out.println("Model size in database: " + namedModel.size() + " triples");
        });

        jenaDataset.executeRead(() -> {
            Model savedModel = jenaDataset.getNamedModel(namedGraphURI);
            assertFalse(savedModel.isEmpty(), "Model should be saved in database");

            assertTrue(savedModel.contains(
                    savedModel.getResource(DEFAULT_NS + POJEM),
                    RDF.type,
                    savedModel.getResource("http://www.w3.org/2002/07/owl#Class")
            ), "POJEM class should exist in saved model");

            assertTrue(savedModel.contains(
                    savedModel.getResource(DEFAULT_NS + TRIDA),
                    RDFS.subClassOf,
                    savedModel.getResource(DEFAULT_NS + POJEM)
            ), "TRIDA should be subclass of POJEM in saved model");
        });

        System.out.println("✓ Model successfully saved and verified in TDB2 database");
    }

    @Test
    void testQuerySavedModel() {
        Set<String> testClasses = Set.of(POJEM, TRIDA, VLASTNOST, VZTAH);
        Set<String> testProperties = Set.of(NAZEV, POPIS, DEFINICE);

        OFNBaseModel testModel = new OFNBaseModel(testClasses, testProperties);
        String namedGraphURI = "http://example.org/query-test-ontology";

        jenaDataset.executeWrite(() -> {
            Model namedModel = jenaDataset.getNamedModel(namedGraphURI);
            namedModel.removeAll();
            namedModel.add(testModel.getOntModel());
        });

        String sparqlQuery = String.format("""
            PREFIX cz: <%s>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX owl: <http://www.w3.org/2002/07/owl#>
            
            SELECT ?class ?label ?superClass
            FROM <%s>
            WHERE {
                ?class a owl:Class .
                OPTIONAL { ?class rdfs:label ?label FILTER(lang(?label) = 'cs') }
                OPTIONAL { ?class rdfs:subClassOf ?superClass }
            }
            ORDER BY ?class
            """, DEFAULT_NS, namedGraphURI);

        jenaDataset.executeRead(() -> {
            try (QueryExecution qexec = QueryExecutionFactory.create(sparqlQuery, jenaDataset)) {
                ResultSet results = qexec.execSelect();

                int classCount = 0;
                System.out.println("\n=== Classes found in saved ontology ===");
                while (results.hasNext()) {
                    QuerySolution soln = results.nextSolution();
                    String className = soln.getResource("class").getURI();
                    String label = soln.contains("label") ? soln.getLiteral("label").getString() : "no label";
                    String superClass = soln.contains("superClass") ? soln.getResource("superClass").getURI() : "no superclass";

                    System.out.printf("Class: %s, Label: %s, SuperClass: %s%n",
                            className.replace(DEFAULT_NS, "cz:"), label,
                            superClass.equals("no superclass") ? superClass : superClass.replace(DEFAULT_NS, "cz:"));
                    classCount++;
                }

                assertTrue(classCount >= 4, "Should find at least 4 classes (POJEM, TRIDA, VLASTNOST, VZTAH)");
                System.out.println("✓ Successfully queried " + classCount + " classes from saved model");
            }
        });
    }

    @Test
    void testQueryProperties() {
        Set<String> testClasses = Set.of(POJEM);
        Set<String> testProperties = Set.of(NAZEV, POPIS, DEFINICE, JE_PPDF, AGENDA);

        OFNBaseModel testModel = new OFNBaseModel(testClasses, testProperties);
        String namedGraphURI = "http://example.org/properties-test";

        jenaDataset.executeWrite(() -> {
            Model namedModel = jenaDataset.getNamedModel(namedGraphURI);
            namedModel.removeAll();
            namedModel.add(testModel.getOntModel());
        });

        String sparqlQuery = String.format("""
            PREFIX cz: <%s>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX owl: <http://www.w3.org/2002/07/owl#>
            PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            
            SELECT ?property ?label ?domain ?range
            FROM <%s>
            WHERE {
                ?property a ?propertyType .
                FILTER(?propertyType = owl:ObjectProperty || ?propertyType = owl:DatatypeProperty || 
                       ?propertyType = owl:FunctionalProperty || ?propertyType = rdf:Property)
                OPTIONAL { ?property rdfs:label ?label FILTER(lang(?label) = 'cs') }
                OPTIONAL { ?property rdfs:domain ?domain }
                OPTIONAL { ?property rdfs:range ?range }
            }
            ORDER BY ?property
            """, DEFAULT_NS, namedGraphURI);

        jenaDataset.executeRead(() -> {
            try (QueryExecution qexec = QueryExecutionFactory.create(sparqlQuery, jenaDataset)) {
                ResultSet results = qexec.execSelect();

                int propertyCount = 0;
                System.out.println("\n=== Properties found in saved ontology ===");
                while (results.hasNext()) {
                    QuerySolution soln = results.nextSolution();
                    String propertyURI = soln.getResource("property").getURI();
                    String label = soln.contains("label") ? soln.getLiteral("label").getString() : "no label";
                    String domain = soln.contains("domain") ? soln.getResource("domain").getURI() : "no domain";
                    String range = soln.contains("range") ? soln.getResource("range").getURI() : "no range";

                    System.out.printf("Property: %s, Label: %s, Domain: %s, Range: %s%n",
                            propertyURI.replace(DEFAULT_NS, "cz:"), label,
                            domain.equals("no domain") ? domain : domain.replace(DEFAULT_NS, "cz:"),
                            range.equals("no range") ? range : range.replaceAll("http://www\\.w3\\.org/2001/XMLSchema#", "xsd:"));
                    propertyCount++;
                }

                assertTrue(propertyCount >= 5, "Should find at least 5 properties");
                System.out.println("✓ Successfully queried " + propertyCount + " properties from saved model");
            }
        });
    }

    @Test
    void testNamespaceConfiguration() {
        OFNBaseModel model = new OFNBaseModel();

        assertEquals(DEFAULT_NS, model.getOntModel().getNsPrefixURI("cz"));
        assertEquals(RDF.getURI(), model.getOntModel().getNsPrefixURI("rdf"));
        assertEquals(RDFS.getURI(), model.getOntModel().getNsPrefixURI("rdfs"));
        assertEquals(XSD, model.getOntModel().getNsPrefixURI("xsd"));
        assertEquals(CAS_NS, model.getOntModel().getNsPrefixURI("čas"));
        assertEquals(SLOVNIKY_NS, model.getOntModel().getNsPrefixURI("slovníky"));

        System.out.println("✓ All namespace prefixes correctly configured");
    }

    @Test
    void testExportModelToTurtle() {
        Set<String> testClasses = Set.of(POJEM, TRIDA);
        Set<String> testProperties = Set.of(NAZEV, POPIS);

        OFNBaseModel testModel = new OFNBaseModel(testClasses, testProperties);

        System.out.println("\n=== Model in Turtle format ===");
        testModel.getOntModel().write(System.out, "TURTLE");

        String namedGraphURI = "http://example.org/turtle-export-test";
        jenaDataset.executeWrite(() -> {
            Model namedModel = jenaDataset.getNamedModel(namedGraphURI);
            namedModel.removeAll();
            namedModel.add(testModel.getOntModel());
        });

        System.out.println("\n=== Model exported from TDB2 database ===");
        jenaDataset.executeRead(() -> {
            Model savedModel = jenaDataset.getNamedModel(namedGraphURI);
            savedModel.write(System.out, "TURTLE");
        });

        System.out.println("✓ Model successfully exported in Turtle format");
    }
}
