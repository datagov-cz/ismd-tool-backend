package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.exception.OntologyValidationException;
import com.dia.ismdtoolbackend.models.DescriptionModel;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.OntologyEditModel;
import com.dia.utility.URIGenerator;
import com.dia.utility.UtilityMethods;
import lombok.Getter;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
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
    //   E5 – error when renamed ontology IRI already exists in the model
    //   E6 – rename must not leave a stale old prefLabel/description on the new IRI
    //   E7 – rename preserves an unedited description (descriptionModel == null)

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
            concept1.addProperty(RDF.type, SKOS.Concept);
            concept1.addProperty(SKOS.prefLabel, "Concept 1");

            Resource concept2 = model.createResource(oldNamespace + "concept-2");
            concept2.addProperty(RDF.type, SKOS.Concept);
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

            // Concepts should be renamed to the new namespace
            Resource renamedConcept1 = model.getResource(newNamespace + "concept-1");
            Resource renamedConcept2 = model.getResource(newNamespace + "concept-2");

            assertTrue(model.containsResource(renamedConcept1));
            assertTrue(model.containsResource(renamedConcept2));

            // Non-concept resources (without RDF.type = SKOS.Concept) should remain in old namespace
            Resource oldOtherSubject = model.getResource(oldNamespace + "other-subject");
            assertTrue(model.containsResource(oldOtherSubject));
        }

        // --- E1b. rename must rewrite each concept's skos:inScheme to the new ontology IRI ---
        // Regression: copying the object verbatim left inScheme on the old scheme, so the renamed
        // concept's IRI no longer prefix-matched its scheme; OWNED_CONCEPT_PATTERN then stopped
        // resolving it (invisible to resolver/upload, mis-flagged PG_MISSING_RDF by the reconciler).
        @Test
        void editOntology_ShouldRewriteConceptInScheme_WhenRenamed() { // E1b
            // Arrange
            String oldOntologyIRI = "https://example.com/vocab/old-ontology";
            String oldNamespace = UtilityMethods.ensureNamespaceEndsWithDelimiter(oldOntologyIRI);

            Resource ontology = model.createResource(oldOntologyIRI);
            ontology.addProperty(SKOS.prefLabel, model.createLiteral("Old name", "cs"));

            Resource concept = model.createResource(oldNamespace + "concept-1");
            concept.addProperty(RDF.type, SKOS.Concept);
            concept.addProperty(SKOS.prefLabel, "Concept 1");
            concept.addProperty(SKOS.inScheme, model.getResource(oldOntologyIRI));

            NameModel newName = createNameModel("cs", "New ontology");
            when(editModel.getNameModel()).thenReturn(newName);

            // Act
            OntologyEditor.EditResult result =
                    ontologyEditor.editOntology(editModel, model, oldNamespace, oldOntologyIRI);

            // Assert
            assertTrue(result.iriChanged);
            String newNamespace =
                    UtilityMethods.ensureNamespaceEndsWithDelimiter(result.newOntologyIRI);
            Resource renamedConcept = model.getResource(newNamespace + "concept-1");
            Resource newOntology = model.getResource(result.newOntologyIRI);

            assertTrue(model.contains(renamedConcept, SKOS.inScheme, newOntology),
                    "inScheme not rewritten to the new ontology IRI");
            assertFalse(model.contains(renamedConcept, SKOS.inScheme,
                            model.getResource(oldOntologyIRI)),
                    "Stale inScheme pointing at the old ontology IRI leaked onto the renamed concept");
            assertEquals(1, renamedConcept.listProperties(SKOS.inScheme).toList().size(),
                    "Renamed concept must carry exactly one inScheme");
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

        // --- E6. rename must not leave a stale old prefLabel/description on the new IRI ---
        // Regression for the same class of bug fixed in ConceptEditor by commit 802104d:
        // when the field updaters ran before renameOntologyIRI, rename re-copied the OLD
        // prefLabel/description onto the new IRI, so the renamed ontology ended up with
        // BOTH the new and the stale old value.
        @Test
        void editOntology_ShouldNotLeaveStaleLabelOrDescriptionOnNewIRI_WhenRenamed() { // E6
            // Arrange
            String oldOntologyIRI = "https://example.com/vocab/old-ontology";
            String oldNamespace = UtilityMethods.ensureNamespaceEndsWithDelimiter(oldOntologyIRI);

            Resource ontology = model.createResource(oldOntologyIRI);
            ontology.addProperty(SKOS.prefLabel, model.createLiteral("Old name", "cs"));
            Property descProperty = model.createProperty("http://purl.org/dc/terms/description");
            ontology.addProperty(descProperty, model.createLiteral("Old description", "cs"));

            NameModel newName = createNameModel("cs", "New ontology");
            DescriptionModel newDesc = createDescriptionModel("cs", "New description");

            when(editModel.getNameModel()).thenReturn(newName);
            when(editModel.getDescriptionModel()).thenReturn(newDesc);

            // Act
            OntologyEditor.EditResult result =
                    ontologyEditor.editOntology(editModel, model, oldNamespace, oldOntologyIRI);

            // Assert — the new IRI carries exactly the new value, with no stale copy.
            Resource newOntology = model.getResource(result.newOntologyIRI);

            assertTrue(model.contains(newOntology, SKOS.prefLabel, model.createLiteral("New ontology", "cs")),
                    "New prefLabel not found on the renamed IRI");
            assertFalse(model.contains(newOntology, SKOS.prefLabel, model.createLiteral("Old name", "cs")),
                    "Stale old prefLabel leaked onto the renamed IRI");
            assertEquals(1, newOntology.listProperties(SKOS.prefLabel).toList().size(),
                    "Renamed IRI must carry exactly one prefLabel");

            assertTrue(model.contains(newOntology, descProperty, model.createLiteral("New description", "cs")),
                    "New description not found on the renamed IRI");
            assertFalse(model.contains(newOntology, descProperty, model.createLiteral("Old description", "cs")),
                    "Stale old description leaked onto the renamed IRI");
            assertEquals(1, newOntology.listProperties(descProperty).toList().size(),
                    "Renamed IRI must carry exactly one description");
        }

        // --- E7. rename preserves an unedited description (descriptionModel == null) ---
        // Renaming while sending only the name (partial payload) must not drop the
        // untouched description — rename relocates it onto the new IRI verbatim.
        @Test
        void editOntology_ShouldPreserveUneditedDescription_WhenRenamed() { // E7
            // Arrange
            String oldOntologyIRI = "https://example.com/vocab/old-ontology";
            String oldNamespace = UtilityMethods.ensureNamespaceEndsWithDelimiter(oldOntologyIRI);

            Resource ontology = model.createResource(oldOntologyIRI);
            ontology.addProperty(SKOS.prefLabel, model.createLiteral("Old name", "cs"));
            Property descProperty = model.createProperty("http://purl.org/dc/terms/description");
            ontology.addProperty(descProperty, model.createLiteral("Kept description", "cs"));

            NameModel newName = createNameModel("cs", "New ontology");

            when(editModel.getNameModel()).thenReturn(newName);
            when(editModel.getDescriptionModel()).thenReturn(null);

            // Act
            OntologyEditor.EditResult result =
                    ontologyEditor.editOntology(editModel, model, oldNamespace, oldOntologyIRI);

            // Assert
            Resource newOntology = model.getResource(result.newOntologyIRI);

            assertTrue(model.contains(newOntology, descProperty, model.createLiteral("Kept description", "cs")),
                    "Unedited description was dropped on rename");
            assertEquals(1, newOntology.listProperties(descProperty).toList().size(),
                    "Renamed IRI must carry exactly one (preserved) description");
            assertFalse(model.contains(model.getResource(oldOntologyIRI), descProperty,
                            model.createLiteral("Kept description", "cs")),
                    "Description must no longer remain on the old IRI");
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

    @Nested
    class URIUniquenessTests {

        // --- E5. error when renamed ontology IRI already exists in the model ---
        @Test
        void editOntology_ShouldThrowWhenRenamedIRIAlreadyExists() { // E5
            // Arrange
            String oldOntologyIRI = "https://example.com/vocab/old-ontology";
            String oldNamespace = UtilityMethods.ensureNamespaceEndsWithDelimiter(oldOntologyIRI);

            Resource ontology = model.createResource(oldOntologyIRI);
            ontology.addProperty(SKOS.prefLabel, model.createLiteral("Old name", "cs"));

            // Pre-compute the IRI that will be generated for "New ontology"
            URIGenerator uriGen = new URIGenerator();
            String baseNamespace = oldOntologyIRI.replaceFirst("^(https?://[^/]+/).*", "$1");
            String conflictingIri = uriGen.generateVocabularyURIFromGivenNamespace("New ontology", baseNamespace);

            // Create a pre-existing resource at that IRI to cause a collision
            Resource conflicting = model.createResource(conflictingIri);
            conflicting.addProperty(SKOS.prefLabel, model.createLiteral("Existing ontology", "cs"));

            NameModel newName = createNameModel("cs", "New ontology");

            when(editModel.getNameModel()).thenReturn(newName);

            // Act & Assert
            assertThrows(OntologyValidationException.class,
                    () -> ontologyEditor.editOntology(editModel, model, oldNamespace, oldOntologyIRI));
        }
    }
}