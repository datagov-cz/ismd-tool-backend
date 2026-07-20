package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.exception.OntologyValidationException;
import com.dia.ismdtoolbackend.models.DescriptionModel;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.OntologyEditModel;
import com.dia.utility.URIGenerator;
import com.dia.utility.UtilityMethods;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
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

            if (!newOntologyIRI.equals(iri)) {
                Resource targetResource = model.getResource(newOntologyIRI);
                if (model.containsResource(targetResource)) {
                    throw new OntologyValidationException(
                            "Ontology with IRI " + newOntologyIRI + " already exists in the model");
                }
            }
        }

        Set<Statement> statementsToRemove = new HashSet<>();
        Set<Statement> statementsToAdd = new HashSet<>();

        boolean supportsTransactions = model.supportsTransactions();
        if (supportsTransactions) {
            model.begin();
        }

        boolean committed = false;
        try {
            boolean iriChanged = nameChanged && !iri.equals(newOntologyIRI);
            if (iriChanged) {
                renameOntologyIRI(model, iri, newOntologyIRI, statementsToRemove, statementsToAdd);
                updateAllConceptIRIs(model, oldNamespace, newNamespace, statementsToRemove, statementsToAdd);
            }

            if (editModel.getNameModel() != null) {
                updateName(existingOntology, editModel.getNameModel(), model, statementsToRemove, statementsToAdd,
                        newOntologyIRI);
            }

            if (editModel.getDescriptionModel() != null) {
                updateDescription(existingOntology, editModel.getDescriptionModel(), model, statementsToRemove,
                        statementsToAdd, newOntologyIRI);
            }

            model.remove(statementsToRemove.toArray(new Statement[0]));
            model.add(statementsToAdd.toArray(new Statement[0]));

            log.info("Applied {} removals and {} additions for ontology edit",
                    statementsToRemove.size(), statementsToAdd.size());

            if (supportsTransactions) {
                model.commit();
                committed = true;
            }

            boolean iriActuallyChanged = nameChanged && !iri.equals(newOntologyIRI);
            return new EditResult(newOntologyIRI, iriActuallyChanged);

        } catch (Exception e) {
            log.error("Failed to edit ontology: {}", iri, e);
            OntologyException ex = new OntologyException("Failed to edit ontology: " + iri + " - " + e.getMessage());
            ex.initCause(e);
            throw ex;
        } finally {
            if (supportsTransactions && !committed) {
                try {
                    model.abort();
                    log.error("Transaction aborted during ontology edit");
                } catch (Exception abortException) {
                    log.error("Failed to abort transaction", abortException);
                }
            }
        }
    }

    private void updateName(Resource existingOntology, NameModel nameModel, Model model,
                           Set<Statement> toRemove, Set<Statement> toAdd, String newOntologyIRI) {
        if (nameModel == null || nameModel.getName() == null) return;

        // Name is required: an empty incoming map is a no-op (merged == existing).
        Map<String, String> existing = RdfLangValues.byLanguage(existingOntology, SKOS.prefLabel);
        applyMergedLangProperty(model.getResource(newOntologyIRI), SKOS.prefLabel,
                existing, nameModel.getName(), model, toRemove, toAdd);
    }

    private void updateDescription(Resource existingOntology, DescriptionModel descModel, Model model,
                                   Set<Statement> toRemove, Set<Statement> toAdd, String newOntologyIRI) {
        if (descModel == null) return;

        Property descProperty = model.createProperty("http://purl.org/dc/terms/description");
        Resource writeOntology = model.getResource(newOntologyIRI);
        Map<String, String> existing = RdfLangValues.byLanguage(existingOntology, descProperty);
        Map<String, String> incoming = descModel.getDescription();

        // An empty/absent incoming map clears the field entirely. Removal targets the
        // write IRI so a rename+clear also cancels the stale copy renameOntologyIRI
        // staged onto the new IRI (the old-IRI live triples are removed by rename).
        if (incoming == null || incoming.isEmpty()) {
            if (!existing.isEmpty()) {
                RdfLangValues.removeAllByPredicate(writeOntology, descProperty, toRemove, toAdd);
            }
            return;
        }

        applyMergedLangProperty(writeOntology, descProperty,
                existing, incoming, model, toRemove, toAdd);
    }

    /**
     * Merges {@code incoming} ({@code lang -> value}, already read from the old IRI)
     * into {@code existing} and, if changed, removes {@code property} from
     * {@code writeResource} and rewrites the merged values onto it. Targeting the
     * write IRI for removal cancels the stale copy of this property that
     * {@code renameOntologyIRI} stages onto the new IRI on a rename; with no rename
     * the write IRI == old IRI, so it behaves as a plain remove-then-rewrite.
     */
    private void applyMergedLangProperty(Resource writeResource, Property property,
                                         Map<String, String> existing, Map<String, String> incoming,
                                         Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        Map<String, String> merged = new HashMap<>(existing);
        for (Map.Entry<String, String> entry : incoming.entrySet()) {
            if (entry.getValue() == null || entry.getValue().trim().isEmpty()) {
                merged.remove(entry.getKey());
            } else {
                merged.put(entry.getKey(), entry.getValue().trim());
            }
        }

        if (existing.equals(merged)) return;

        RdfLangValues.removeAllByPredicate(writeResource, property, toRemove, toAdd);
        for (Map.Entry<String, String> entry : merged.entrySet()) {
            String languageTag = entry.getKey() != null && !entry.getKey().trim().isEmpty()
                    ? entry.getKey()
                    : DEFAULT_LANG;
            toAdd.add(model.createStatement(writeResource, property,
                    model.createLiteral(entry.getValue(), languageTag)));
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

        Set<Resource> conceptsToUpdate = new HashSet<>();
        String oldOntologyIRI = oldNamespace.replaceAll("[/#]$", "");
        String newOntologyIRI = newNamespace.replaceAll("[/#]$", "");
        Resource newScheme = model.getResource(newOntologyIRI);

        ResIterator resIter = model.listSubjects();
        while (resIter.hasNext()) {
            Resource resource = resIter.next();
            if (resource.isURIResource()
                && resource.getURI().startsWith(oldNamespace)
                && !resource.getURI().equals(oldOntologyIRI)
                && resource.hasProperty(RDF.type, SKOS.Concept)) {
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
                // A concept's skos:inScheme names the ontology; on rename force it to the new
                // ontology IRI. Copying the object verbatim leaves it on the old (or a stale
                // pre-rename) scheme, so the concept's IRI no longer prefix-matches its scheme
                // and OWNED_CONCEPT_PATTERN stops resolving it (invisible to resolver/upload,
                // mis-flagged PG_MISSING_RDF by the reconciler).
                RDFNode newObject = stmt.getPredicate().equals(SKOS.inScheme)
                        ? newScheme
                        : stmt.getObject();
                toAdd.add(model.createStatement(
                        model.getResource(newConceptIRI),
                        stmt.getPredicate(),
                        newObject
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

    private String getNameForUriGeneration(NameModel nameModel) {
        return RdfLangValues.preferredName(nameModel == null ? null : nameModel.getName());
    }

    private String getNameForUriGeneration(Resource resource) {
        return RdfLangValues.preferredName(RdfLangValues.byLanguage(resource, SKOS.prefLabel));
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