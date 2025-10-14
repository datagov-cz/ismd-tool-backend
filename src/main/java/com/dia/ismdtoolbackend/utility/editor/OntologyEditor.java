package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.models.DescriptionModel;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.OntologyEditModel;
import com.dia.utility.URIGenerator;
import com.dia.utility.UtilityMethods;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.SKOS;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

import static com.dia.constants.ExportConstants.Common.DEFAULT_LANG;

@Component
@RequiredArgsConstructor
@Slf4j
public class OntologyEditor {

    private final URIGenerator uriGenerator = new URIGenerator();

    public EditResult editOntology(OntologyEditModel editModel, Model model, String oldNamespace) {
        String oldOntologyIRI = editModel.getOntologyIRI();
        Resource existingOntology = model.getResource(oldOntologyIRI);

        if (existingOntology == null || !model.containsResource(existingOntology)) {
            throw new IllegalArgumentException("Ontology with IRI " + oldOntologyIRI + " not found in the model");
        }

        String newName = editModel.getNameModel() != null ? editModel.getNameModel().getName() : null;
        String oldName = getCurrentName(existingOntology);
        boolean nameChanged = newName != null && !newName.equals(oldName);

        String newOntologyIRI = oldOntologyIRI;
        String newNamespace = oldNamespace;

        if (nameChanged) {
            String baseNamespace = oldOntologyIRI.replaceFirst("^(https?://[^/]+/).*", "$1");

            newOntologyIRI = uriGenerator.generateVocabularyURIFromGivenNamespace(newName, baseNamespace);
            newNamespace = UtilityMethods.ensureNamespaceEndsWithDelimiter(newOntologyIRI);

            log.info("Ontology name changed from '{}' to '{}', updating IRI from {} to {}",
                    oldName, newName, oldOntologyIRI, newOntologyIRI);
        }

        Set<Statement> statementsToRemove = new HashSet<>();
        Set<Statement> statementsToAdd = new HashSet<>();

        if (editModel.getNameModel() != null) {
            updateName(existingOntology, editModel.getNameModel(), model, statementsToRemove, statementsToAdd,
                    newOntologyIRI);
        }

        if (editModel.getDescriptionModel() != null) {
            updateDescription(existingOntology, editModel.getDescriptionModel(), model, statementsToRemove,
                    statementsToAdd, newOntologyIRI);
        }

        if (nameChanged && !oldOntologyIRI.equals(newOntologyIRI)) {
            renameOntologyIRI(model, oldOntologyIRI, newOntologyIRI, statementsToRemove, statementsToAdd);
            updateAllConceptIRIs(model, oldNamespace, newNamespace, statementsToRemove, statementsToAdd);
        }

        model.remove(statementsToRemove.toArray(new Statement[0]));
        model.add(statementsToAdd.toArray(new Statement[0]));

        log.info("Applied {} removals and {} additions for ontology edit",
                statementsToRemove.size(), statementsToAdd.size());

        return new EditResult(newOntologyIRI, nameChanged);
    }

    private void updateName(Resource existingOntology, NameModel nameModel, Model model,
                           Set<Statement> toRemove, Set<Statement> toAdd, String newOntologyIRI) {
        if (nameModel == null || nameModel.getName() == null) return;

        String oldName = getCurrentName(existingOntology);
        String newName = nameModel.getName();

        if (!newName.equals(oldName)) {
            Resource ontologyResource = model.getResource(newOntologyIRI);
            removeAllByPredicate(existingOntology, SKOS.prefLabel, toRemove);

            String languageTag = nameModel.getLanguageTag() != null ? nameModel.getLanguageTag() : DEFAULT_LANG;
            toAdd.add(model.createStatement(ontologyResource, SKOS.prefLabel,
                    model.createLiteral(newName, languageTag)));
        }
    }

    private void updateDescription(Resource existingOntology, DescriptionModel descModel, Model model,
                                   Set<Statement> toRemove, Set<Statement> toAdd, String newOntologyIRI) {
        Property descProperty = model.createProperty("http://purl.org/dc/terms/description");
        String oldValue = getPropertyValue(existingOntology, descProperty);
        String newValue = descModel.getDescription();

        Resource ontologyResource = model.getResource(newOntologyIRI);

        if (newValue == null || newValue.trim().isEmpty()) {
            if (oldValue != null) {
                removeAllByPredicate(existingOntology, descProperty, toRemove);
            }
        } else if (!newValue.equals(oldValue)) {
            removeAllByPredicate(existingOntology, descProperty, toRemove);
            String languageTag = descModel.getLanguageTag() != null ? descModel.getLanguageTag() : DEFAULT_LANG;
            toAdd.add(model.createStatement(ontologyResource, descProperty,
                    model.createLiteral(newValue, languageTag)));
        }
    }

    private void renameOntologyIRI(Model model, String oldIRI, String newIRI,
                                   Set<Statement> toRemove, Set<Statement> toAdd) {
        Resource oldOntology = model.getResource(oldIRI);

        StmtIterator iter = model.listStatements(oldOntology, null, (RDFNode) null);
        while (iter.hasNext()) {
            Statement stmt = iter.next();
            toRemove.add(stmt);
            toAdd.add(model.createStatement(
                    model.getResource(newIRI),
                    stmt.getPredicate(),
                    stmt.getObject()
            ));
        }

        iter = model.listStatements(null, null, oldOntology);
        while (iter.hasNext()) {
            Statement stmt = iter.next();
            toRemove.add(stmt);
            toAdd.add(model.createStatement(
                    stmt.getSubject(),
                    stmt.getPredicate(),
                    model.getResource(newIRI)
            ));
        }
    }

    private void updateAllConceptIRIs(Model model, String oldNamespace, String newNamespace,
                                      Set<Statement> toRemove, Set<Statement> toAdd) {
        log.info("Updating all concept IRIs from namespace '{}' to '{}'", oldNamespace, newNamespace);

        ResIterator resIter = model.listSubjects();
        Set<Resource> conceptsToUpdate = new HashSet<>();

        while (resIter.hasNext()) {
            Resource resource = resIter.next();
            if (resource.isURIResource() && resource.getURI().startsWith(oldNamespace) && !resource.getURI().equals(oldNamespace.replaceAll("[/#]$", ""))) {
                    conceptsToUpdate.add(resource);
                }
        }

        log.info("Found {} concepts to update", conceptsToUpdate.size());

        for (Resource oldConcept : conceptsToUpdate) {
            String oldConceptIRI = oldConcept.getURI();
            String conceptLocalPart = oldConceptIRI.substring(oldNamespace.length());
            String newConceptIRI = newNamespace + conceptLocalPart;

            log.debug("Updating concept IRI: {} -> {}", oldConceptIRI, newConceptIRI);

            StmtIterator iter = model.listStatements(oldConcept, null, (RDFNode) null);
            while (iter.hasNext()) {
                Statement stmt = iter.next();
                toRemove.add(stmt);
                toAdd.add(model.createStatement(
                        model.getResource(newConceptIRI),
                        stmt.getPredicate(),
                        stmt.getObject()
                ));
            }

            iter = model.listStatements(null, null, oldConcept);
            while (iter.hasNext()) {
                Statement stmt = iter.next();
                toRemove.add(stmt);
                toAdd.add(model.createStatement(
                        stmt.getSubject(),
                        stmt.getPredicate(),
                        model.getResource(newConceptIRI)
                ));
            }
        }
    }

    private String getCurrentName(Resource ontology) {
        Statement stmt = ontology.getProperty(SKOS.prefLabel);
        return stmt != null && stmt.getObject().isLiteral() ? stmt.getObject().asLiteral().getString() : null;
    }

    private String getPropertyValue(Resource resource, Property property) {
        Statement stmt = resource.getProperty(property);
        return stmt != null && stmt.getObject().isLiteral() ? stmt.getObject().asLiteral().getString() : null;
    }

    private void removeAllByPredicate(Resource resource, Property property, Set<Statement> toRemove) {
        StmtIterator iter = resource.listProperties(property);
        while (iter.hasNext()) {
            toRemove.add(iter.next());
        }
    }

    public static class EditResult {
        public final String newOntologyIRI;
        public final boolean iriChanged;

        public EditResult(String newOntologyIRI, boolean iriChanged) {
            this.newOntologyIRI = newOntologyIRI;
            this.iriChanged = iriChanged;
        }
    }
}