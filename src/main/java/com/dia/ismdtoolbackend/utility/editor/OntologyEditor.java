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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.dia.constants.ExportConstants.Common.DEFAULT_LANG;

@Component
@RequiredArgsConstructor
@Slf4j
public class OntologyEditor {

    private final URIGenerator uriGenerator = new URIGenerator();

    public EditResult editOntology(OntologyEditModel editModel, Model model, String oldNamespace, String iri) {
        Resource existingOntology = model.getResource(iri);

        if (existingOntology == null || !model.containsResource(existingOntology)) {
            throw new IllegalArgumentException("Ontology with IRI " + iri + " not found in the model");
        }

        String newName = getNameForUriGeneration(editModel.getNameModel());
        String oldName = getNameForUriGeneration(existingOntology);
        boolean nameChanged = newName != null && !newName.isEmpty() && !newName.equals(oldName);

        String newOntologyIRI = iri;
        String newNamespace = oldNamespace;

        if (nameChanged) {
            String baseNamespace = iri.replaceFirst("^(https?://[^/]+/).*", "$1");

            newOntologyIRI = uriGenerator.generateVocabularyURIFromGivenNamespace(newName, baseNamespace);
            newNamespace = UtilityMethods.ensureNamespaceEndsWithDelimiter(newOntologyIRI);

            log.info("Ontology name changed from '{}' to '{}', updating IRI from {} to {}",
                    oldName, newName, iri, newOntologyIRI);
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

        if (nameChanged && !iri.equals(newOntologyIRI)) {
            renameOntologyIRI(model, iri, newOntologyIRI, statementsToRemove, statementsToAdd);
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

        Map<String, String> existingNames = getAllPropertyValuesWithLanguage(existingOntology, SKOS.prefLabel);

        Map<String, String> newNames = nameModel.getName();

        Map<String, String> mergedNames = new HashMap<>(existingNames);
        for (Map.Entry<String, String> entry : newNames.entrySet()) {
            if (entry.getValue() == null) {
                mergedNames.remove(entry.getKey());
            } else if (!entry.getValue().trim().isEmpty()) {
                mergedNames.put(entry.getKey(), entry.getValue().trim());
            }
        }

        if (!existingNames.equals(mergedNames)) {
            Resource ontologyResource = model.getResource(newOntologyIRI);
            removeAllByPredicate(existingOntology, SKOS.prefLabel, toRemove, toAdd);

            for (Map.Entry<String, String> entry : mergedNames.entrySet()) {
                String languageTag = entry.getKey() != null && !entry.getKey().trim().isEmpty()
                        ? entry.getKey()
                        : DEFAULT_LANG;
                toAdd.add(model.createStatement(ontologyResource, SKOS.prefLabel,
                        model.createLiteral(entry.getValue(), languageTag)));
            }
        }
    }

    private void updateDescription(Resource existingOntology, DescriptionModel descModel, Model model,
                                   Set<Statement> toRemove, Set<Statement> toAdd, String newOntologyIRI) {
        if (descModel == null) return;

        Property descProperty = model.createProperty("http://purl.org/dc/terms/description");

        Map<String, String> existingDescriptions = getAllPropertyValuesWithLanguage(existingOntology, descProperty);

        Map<String, String> newDescriptions = descModel.getDescription();

        if (newDescriptions == null || newDescriptions.isEmpty()) {
            if (!existingDescriptions.isEmpty()) {
                removeAllByPredicate(existingOntology, descProperty, toRemove, toAdd);
            }
            return;
        }

        Map<String, String> mergedDescriptions = new HashMap<>(existingDescriptions);
        for (Map.Entry<String, String> entry : newDescriptions.entrySet()) {
            if (entry.getValue() == null) {
                mergedDescriptions.remove(entry.getKey());
            } else if (!entry.getValue().trim().isEmpty()) {
                mergedDescriptions.put(entry.getKey(), entry.getValue().trim());
            }
        }

        if (!existingDescriptions.equals(mergedDescriptions)) {
            Resource ontologyResource = model.getResource(newOntologyIRI);
            removeAllByPredicate(existingOntology, descProperty, toRemove, toAdd);

            for (Map.Entry<String, String> entry : mergedDescriptions.entrySet()) {
                String languageTag = entry.getKey() != null && !entry.getKey().trim().isEmpty()
                        ? entry.getKey()
                        : DEFAULT_LANG;
                toAdd.add(model.createStatement(ontologyResource, descProperty,
                        model.createLiteral(entry.getValue(), languageTag)));
            }
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

    private void removeAllByPredicate(Resource resource, Property property, Set<Statement> toRemove, Set<Statement> toAdd) {
        StmtIterator iter = resource.listProperties(property);
        while (iter.hasNext()) {
            toRemove.add(iter.next());
        }

        toAdd.removeIf(stmt ->
                stmt.getSubject().equals(resource) &&
                        stmt.getPredicate().equals(property)
        );
    }

    private Map<String, String> getAllPropertyValuesWithLanguage(Resource resource, Property property) {
        Map<String, String> valuesWithLang = new HashMap<>();
        StmtIterator iter = resource.listProperties(property);
        while (iter.hasNext()) {
            Statement stmt = iter.next();
            if (stmt.getObject().isLiteral()) {
                Literal literal = stmt.getObject().asLiteral();
                String lang = literal.getLanguage() != null && !literal.getLanguage().isEmpty()
                        ? literal.getLanguage()
                        : DEFAULT_LANG;
                valuesWithLang.put(lang, literal.getString());
            }
        }
        return valuesWithLang;
    }

    private String getNameForUriGeneration(NameModel nameModel) {
        if (nameModel == null || nameModel.getName() == null || nameModel.getName().isEmpty()) {
            return "";
        }
        Map<String, String> names = nameModel.getName();
        if (names.containsKey("cs")) {
            return names.get("cs");
        }
        if (names.containsKey(DEFAULT_LANG)) {
            return names.get(DEFAULT_LANG);
        }
        return names.values().iterator().next();
    }

    private String getNameForUriGeneration(Resource resource) {
        Map<String, String> existingNames = getAllPropertyValuesWithLanguage(resource, SKOS.prefLabel);
        if (existingNames.isEmpty()) {
            return "";
        }
        if (existingNames.containsKey("cs")) {
            return existingNames.get("cs");
        }
        if (existingNames.containsKey(DEFAULT_LANG)) {
            return existingNames.get(DEFAULT_LANG);
        }
        return existingNames.values().iterator().next();
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