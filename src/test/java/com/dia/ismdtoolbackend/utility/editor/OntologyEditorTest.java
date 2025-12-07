package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.models.DescriptionModel;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.OntologyEditModel;
import com.dia.utility.UtilityMethods;
import lombok.Getter;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OntologyEditorTest {

    // TEST COVERAGE MAP
    // E — OntologyEditor
    //   E1 – rename ontology and propagate namespace to all concepts
    //   E2 – keep ontology IRI and concept IRIs when name is unchanged
    //   E3 – remove description when new value is blank
    //   E4 – update description when new value is provided

    private OntologyEditor ontologyEditor;

    @Mock
    private OntologyEditModel editModel;

    @Getter
    private NameModel nameModel;
    @Getter
    private DescriptionModel descriptionModel;

    private Model model;

    @BeforeEach
    void setUp() {
        ontologyEditor = new OntologyEditor();
        model = ModelFactory.createDefaultModel();
        // Initialize models with Map-based structure
        nameModel = new NameModel();
        descriptionModel = new DescriptionModel();
    }

    // ========== Helper Methods for Model Creation ==========

    /**
     * Creates a NameModel with the given language code and value
     */
    private NameModel createNameModel(String languageCode, String value) {
        NameModel nameModel1 = new NameModel();
        nameModel1.setName(Map.of(languageCode, value));
        return nameModel1;
    }

    /**
     * Creates a DescriptionModel with the given language code and value
     */
    private DescriptionModel createDescriptionModel(String languageCode, String value) {
        DescriptionModel descriptionModel1 = new DescriptionModel();
        descriptionModel1.setDescription(Map.of(languageCode, value));
        return descriptionModel1;
    }

    // ========== E. OntologyEditor Tests ==========

    @Nested
    class RenameAndNamespacePropagationTests {

        // --- E1. rename ontology and propagate namespace to all concepts ---
        @Test
        void editOntology_ShouldRenameOntologyAndUpdateConceptIRIs_WhenNameChanges() { // E1
            // Arrange
            String oldOntologyIRI = "https://example.com/vocab/old-ontology";
            String oldNamespace = UtilityMethods.ensureNamespaceEndsWithDelimiter(oldOntologyIRI);

            Resource ontology = model.createResource(oldOntologyIRI);
            ontology.addProperty(SKOS.prefLabel, model.createLiteral("Old name", "cs"));
            Property descProperty = model.createProperty("http://purl.org/dc/terms/description");
            ontology.addProperty(descProperty, model.createLiteral("Old description", "cs"));

            Resource concept1 = model.createResource(oldNamespace + "concept-1");
            concept1.addProperty(SKOS.prefLabel, "Concept 1");

            Resource concept2 = model.createResource(oldNamespace + "concept-2");
            concept2.addProperty(SKOS.prefLabel, "Concept 2");

            Property hasConcept = model.createProperty("http://example.com/hasConcept");
            ontology.addProperty(hasConcept, concept1);

            Resource otherSubject = model.createResource(oldNamespace + "other-subject");
            Property refersTo = model.createProperty("http://example.com/refersTo");
            otherSubject.addProperty(refersTo, concept1);

            NameModel newName = createNameModel("cs", "New ontology");
            DescriptionModel newDesc = createDescriptionModel("cs", "New description");

            when(editModel.getNameModel()).thenReturn(newName);
            when(editModel.getDescriptionModel()).thenReturn(newDesc);

            // Act
            OntologyEditor.EditResult result =
                    ontologyEditor.editOntology(editModel, model, oldNamespace, oldOntologyIRI);

            // Assert
            assertTrue(result.iriChanged);
            assertNotEquals(oldOntologyIRI, result.newOntologyIRI);

            Resource newOntology = model.getResource(result.newOntologyIRI);
            assertTrue(model.containsResource(newOntology));

            boolean hasLabel = model.contains(
                    newOntology,
                    SKOS.prefLabel,
                    model.createLiteral("New ontology", "cs")
            );
            assertTrue(hasLabel, "New prefLabel literal not found");

            boolean hasDescription = model.contains(
                    newOntology,
                    descProperty,
                    model.createLiteral("New description", "cs")
            );
            assertTrue(hasDescription, "New description literal not found");

            assertFalse(model.contains(
                    ontology,
                    SKOS.prefLabel,
                    model.createLiteral("Old name", "cs")
            ));

            assertFalse(model.contains(
                    ontology,
                    descProperty,
                    model.createLiteral("Old description", "cs")
            ));

            String newNamespace =
                    UtilityMethods.ensureNamespaceEndsWithDelimiter(result.newOntologyIRI);

            Resource renamedConcept1 = model.getResource(newNamespace + "concept-1");
            Resource renamedConcept2 = model.getResource(newNamespace + "concept-2");
            Resource renamedOtherSubject = model.getResource(newNamespace + "other-subject");

            assertTrue(model.containsResource(renamedConcept1));
            assertTrue(model.containsResource(renamedConcept2));
            assertTrue(model.containsResource(renamedOtherSubject));
        }

        // --- E2. keep ontology IRI and concept IRIs when name is unchanged ---
        @Test
        void editOntology_ShouldNotRenameOntologyOrConcepts_WhenNameIsUnchanged() { // E2
            // Arrange
            String ontologyIRI = "https://example.com/vocab/ontology";
            String namespace = UtilityMethods.ensureNamespaceEndsWithDelimiter(ontologyIRI);

            Resource ontology = model.createResource(ontologyIRI);
            ontology.addProperty(SKOS.prefLabel, model.createLiteral("Ontology", "cs"));

            Resource concept = model.createResource(namespace + "concept-1");
            Property hasConcept = model.createProperty("http://example.com/hasConcept");
            ontology.addProperty(hasConcept, concept);

            NameModel sameName = createNameModel("cs", "Ontology");

            when(editModel.getNameModel()).thenReturn(sameName);

            // Act
            OntologyEditor.EditResult result =
                    ontologyEditor.editOntology(editModel, model, namespace, ontologyIRI);

            // Assert
            assertFalse(result.iriChanged);
            assertEquals(ontologyIRI, result.newOntologyIRI);

            Resource sameOntology = model.getResource(ontologyIRI);
            assertTrue(model.containsResource(sameOntology));

            Resource sameConcept = model.getResource(namespace + "concept-1");
            assertTrue(model.containsResource(sameConcept));
            assertTrue(model.contains(sameOntology, hasConcept, sameConcept));
        }
    }

    @Nested
    class DescriptionUpdateTests {

        // --- E3. remove description when new value is blank ---
        @Test
        void editOntology_ShouldRemoveDescription_WhenNewDescriptionIsBlank() { // E3
            // Arrange
            String oldOntologyIRI = "https://example.com/vocab/ontology-with-desc";
            String oldNamespace = UtilityMethods.extractNamespace(oldOntologyIRI);

            Resource ontology = model.createResource(oldOntologyIRI);
            Property descProperty = model.createProperty("http://purl.org/dc/terms/description");
            ontology.addProperty(descProperty, model.createLiteral("Some description", "cs"));

            DescriptionModel blankDesc = createDescriptionModel("cs", "");

            when(editModel.getNameModel()).thenReturn(null);
            when(editModel.getDescriptionModel()).thenReturn(blankDesc);

            // Act
            OntologyEditor.EditResult result =
                    ontologyEditor.editOntology(editModel, model, oldNamespace, oldOntologyIRI);

            // Assert
            assertFalse(result.iriChanged);
            assertEquals(oldOntologyIRI, result.newOntologyIRI);

            Resource sameOntology = model.getResource(oldOntologyIRI);

            // Description must be removed
            assertNull(sameOntology.getProperty(descProperty));
        }

        // --- E4. update description when new value is provided ---
        @Test
        void editOntology_ShouldUpdateDescription_WhenNewDescriptionProvided() { // E4
            // Arrange
            String ontologyIRI = "https://example.com/vocab/ontology-with-desc";
            String namespace = UtilityMethods.ensureNamespaceEndsWithDelimiter(ontologyIRI);

            Resource ontology = model.createResource(ontologyIRI);
            Property descProperty = model.createProperty("http://purl.org/dc/terms/description");
            ontology.addProperty(descProperty, model.createLiteral("Old description", "cs"));

            DescriptionModel updatedDesc = createDescriptionModel("cs", "Updated description");

            when(editModel.getNameModel()).thenReturn(null);
            when(editModel.getDescriptionModel()).thenReturn(updatedDesc);

            // Act
            OntologyEditor.EditResult result =
                    ontologyEditor.editOntology(editModel, model, namespace, ontologyIRI);

            // Assert
            assertFalse(result.iriChanged);
            assertEquals(ontologyIRI, result.newOntologyIRI);

            Resource sameOntology = model.getResource(ontologyIRI);

            boolean hasUpdatedDescription = model.contains(
                    sameOntology,
                    descProperty,
                    model.createLiteral("Updated description", "cs")
            );
            assertTrue(hasUpdatedDescription, "Updated description literal not found");

            assertFalse(model.contains(
                    sameOntology,
                    descProperty,
                    model.createLiteral("Old description", "cs")
            ));
        }
    }
}