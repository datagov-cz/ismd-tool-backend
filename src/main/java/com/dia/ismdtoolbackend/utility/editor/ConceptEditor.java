package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import com.dia.ismdtoolbackend.models.concept.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.SKOS;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

import static com.dia.constants.ExportConstants.Common.DEFAULT_LANG;

@Component
@RequiredArgsConstructor
@Slf4j
public class ConceptEditor {

    private final ConceptIriFactory iriFactory = new ConceptIriFactory();

    private final ConceptEditValidator validator = new ConceptEditValidator();

    private final ConceptFieldUpdaters fieldUpdaters = new ConceptFieldUpdaters(iriFactory);

    private final Map<ConceptType, ConceptTypeEditor> typeEditors = new EnumMap<>(Map.of(
            ConceptType.TRIDA, new ClassConceptTypeEditor(fieldUpdaters),
            ConceptType.VLASTNOST, new PropertyConceptTypeEditor(fieldUpdaters),
            ConceptType.VZTAH, new RelationshipConceptTypeEditor(fieldUpdaters)));

    public EditResult editConcept(String conceptIri, ConceptEditModel editModel, Model model, String graphName) {
        Resource existingConcept = model.getResource(conceptIri);

        if (existingConcept == null || !model.containsResource(existingConcept)) {
            throw new IllegalArgumentException("Concept with IRI " + conceptIri + " not found in the model");
        }

        // Pre-flight validation: reject the whole edit (HTTP 400) before any model
        // mutation if any supplied value is invalid. Atomic — nothing is written on
        // rejection. Mirrors the validity checks the field updaters apply.
        List<ConceptEditValidator.InvalidInput> invalid = validator.validate(editModel);
        if (!invalid.isEmpty()) {
            String detail = invalid.stream()
                    .map(ConceptEditValidator.InvalidInput::toString)
                    .collect(Collectors.joining("; "));
            throw new ConceptValidationException("Neplatné hodnoty v úpravě pojmu: " + detail);
        }

        iriFactory.useEffectiveNamespace(graphName);

        String newName = getNameForUriGeneration(editModel.getNameModel());
        String oldName = getNameForUriGeneration(existingConcept);
        boolean nameChanged = newName != null && !newName.isEmpty() && !newName.equals(oldName);

        String newConceptIRI = conceptIri;
        if (nameChanged) {
            newConceptIRI = iriFactory.generateConceptURI(newName, editModel.getIdentifier());
            log.info("Name changed from '{}' to '{}', updating IRI from {} to {}",
                    oldName, newName, conceptIri, newConceptIRI);

            if (!newConceptIRI.equals(conceptIri)) {
                Resource targetResource = model.getResource(newConceptIRI);
                if (model.containsResource(targetResource)) {
                    throw new ConceptValidationException(
                            "Concept with IRI " + newConceptIRI + " already exists in the model");
                }
            }
        }

        EditContext context = new EditContext(model, conceptIri, newConceptIRI);

        Set<Statement> statementsToRemove = new HashSet<>();
        Set<Statement> statementsToAdd = new HashSet<>();

        boolean supportsTransactions = model.supportsTransactions();
        if (supportsTransactions) {
            model.begin();
        }

        boolean committed = false;
        try {
            if (nameChanged && !conceptIri.equals(newConceptIRI)) {
                renameConceptIRI(model, conceptIri, newConceptIRI, statementsToRemove, statementsToAdd);
            }

            ConceptTypeEditor typeEditor = typeEditors.get(editModel.getConceptTypeEnum());
            if (typeEditor == null) {
                throw new IllegalArgumentException(
                        "Unsupported concept type: " + editModel.getConceptTypeEnum());
            }
            typeEditor.edit(editModel, context, model, statementsToRemove, statementsToAdd);

            model.remove(statementsToRemove.toArray(new Statement[0]));
            model.add(statementsToAdd.toArray(new Statement[0]));

            log.info("Applied {} removals and {} additions", statementsToRemove.size(), statementsToAdd.size());

            if (supportsTransactions) {
                model.commit();
                committed = true;
            }

            return new EditResult(newConceptIRI, nameChanged, statementsToRemove, statementsToAdd);

        } catch (Exception e) {
            log.error("Failed to edit concept: {}", conceptIri, e);
            OntologyException ex = new OntologyException("Failed to edit concept: " + conceptIri + " - " + e.getMessage());
            ex.initCause(e);
            throw ex;
        } finally {
            if (supportsTransactions && !committed) {
                try {
                    model.abort();
                    log.error("Transaction aborted during concept edit");
                } catch (Exception abortException) {
                    log.error("Failed to abort transaction", abortException);
                }
            }
        }
    }

    /**
     * Relocates every triple touching {@code oldIRI} onto {@code newIRI},
     * copying ALL predicates unconditionally. The field updaters run afterwards
     * against {@code newConcept}; because the data is now present under the new
     * IRI, their remove-by-predicate + write-if-changed logic mutates only the
     * fields the edit actually changes, and leaves unchanged characteristics in
     * place. Copying everything (rather than excluding edited predicates) is what
     * prevents an unchanged field from being deleted-but-never-rewritten.
     */
    private void renameConceptIRI(Model model, String oldIRI, String newIRI,
                                   Set<Statement> toRemove, Set<Statement> toAdd) {
        Resource oldConcept = model.getResource(oldIRI);
        Resource newConcept = model.getResource(newIRI);

        StmtIterator iter = model.listStatements(oldConcept, null, (RDFNode) null);
        while (iter.hasNext()) {
            Statement stmt = iter.next();
            toRemove.add(stmt);
            toAdd.add(model.createStatement(newConcept, stmt.getPredicate(), stmt.getObject()));
        }

        iter = model.listStatements(null, null, oldConcept);
        while (iter.hasNext()) {
            Statement stmt = iter.next();
            toRemove.add(stmt);
            toAdd.add(model.createStatement(stmt.getSubject(), stmt.getPredicate(), newConcept));
        }
    }

    private String getNameForUriGeneration(com.dia.ismdtoolbackend.models.NameModel nameModel) {
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
        Map<String, String> existingNames = RdfLangValues.byLanguage(resource, SKOS.prefLabel);
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

    static class EditContext {
        final Resource oldConcept;
        final Resource newConcept;
        final Model model;

        public EditContext(Model model, String oldIRI, String newIRI) {
            this.model = model;
            this.oldConcept = model.getResource(oldIRI);
            this.newConcept = model.getResource(newIRI);
        }
    }

    public static class EditResult {
        public final String newConceptIRI;
        public final boolean iriChanged;
        public final int changesCount;
        /**
         * The exact triples the edit removed and added. These are the change sets the editor
         * computes internally (see {@link ConceptEditor#editConcept}); surfaced here so the
         * outbox write path can enqueue a concept-scoped {@code DELETE/INSERT} delta instead of a
         * whole-graph replace — which is what makes concurrent edits to different concepts in one
         * graph safe under an async relay. For a rename, {@code statementsToRemove} covers the old
         * IRI's outgoing AND incoming edges and {@code statementsToAdd} their relocations onto the
         * new IRI. Empty (not null) when no triples changed.
         */
        public final Set<Statement> statementsToRemove;
        public final Set<Statement> statementsToAdd;

        /**
         * Back-compat constructor (no change sets) — used by tests that build an {@code EditResult}
         * directly. Real edits go through {@link #EditResult(String, boolean, Set, Set)}.
         */
        public EditResult(String newConceptIRI, boolean iriChanged, int changesCount) {
            this(newConceptIRI, iriChanged, changesCount, Set.of(), Set.of());
        }

        public EditResult(String newConceptIRI, boolean iriChanged,
                          Set<Statement> statementsToRemove, Set<Statement> statementsToAdd) {
            this(newConceptIRI, iriChanged,
                    statementsToRemove.size() + statementsToAdd.size(),
                    statementsToRemove, statementsToAdd);
        }

        private EditResult(String newConceptIRI, boolean iriChanged, int changesCount,
                           Set<Statement> statementsToRemove, Set<Statement> statementsToAdd) {
            this.newConceptIRI = newConceptIRI;
            this.iriChanged = iriChanged;
            this.changesCount = changesCount;
            this.statementsToRemove = Set.copyOf(statementsToRemove);
            this.statementsToAdd = Set.copyOf(statementsToAdd);
        }
    }
}
