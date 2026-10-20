package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.utility.DataTypeConverter;
import com.dia.ismdtoolbackend.utility.UtilityMethods;
import org.apache.jena.rdf.model.*;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.dia.ismdtoolbackend.constants.VocabularyConstants.*;
import static com.dia.ismdtoolbackend.utility.editor.ConceptEditStatements.*;
import static com.dia.ismdtoolbackend.utility.editor.RdfLangValues.removeAllByPredicate;

/** Edits the governance metadata of a concept: PPDF flag, agenda, AIS and the governance code lists. */
class ConceptGovernanceUpdater {

    private final ConceptIriFactory iriFactory;

    ConceptGovernanceUpdater(ConceptIriFactory iriFactory) {
        this.iriFactory = iriFactory;
    }

    void updateSharedGovernanceMetadata(Resource newConcept, Boolean isInPPDF, String agendaCode,
                                                 String agendaSystemCode, List<String> sharingMethod,
                                                 String acquisitionMethod, String contentType,
                                                 Resource oldConcept, Model model, Set<Statement> toRemove,
                                                 Set<Statement> toAdd) {
        updateBooleanLiteral(newConcept, model.createProperty(DEFAULT_NS + AGENDOVY_104 + JE_PPDF_LONG), isInPPDF,
                oldConcept, model, toRemove, toAdd);
        updateAgenda(newConcept, agendaCode, oldConcept, model, toRemove, toAdd);
        updateAIS(newConcept, agendaSystemCode, oldConcept, model, toRemove, toAdd);
        updateGovernanceProperty(newConcept, contentType, TYP_OBSAHU, oldConcept, model, toRemove, toAdd);
        updateGovernanceProperty(newConcept, acquisitionMethod, ZPUSOB_ZISKANI, oldConcept, model, toRemove, toAdd);
        updateSharingMethodList(newConcept, sharingMethod, oldConcept, model, toRemove, toAdd);
    }

    private void updateAgenda(Resource newConcept, String agendaCode, Resource oldConcept,
                              Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (agendaCode == null) return;

        Property agendaProperty = model.createProperty(DEFAULT_NS + AGENDOVY_104 + AGENDA_LONG);

        if (agendaCode.trim().isEmpty()) {
            removeAllByPredicate(oldConcept, agendaProperty, toRemove, toAdd);
            return;
        }

        removeAllByPredicate(newConcept, agendaProperty, toRemove, toAdd);

        if (UtilityMethods.isValidAgendaValue(agendaCode)) {
            String transformed = UtilityMethods.transformAgendaValue(agendaCode);
            if (DataTypeConverter.isUri(transformed)) {
                toAdd.add(model.createStatement(newConcept, agendaProperty, model.createResource(transformed)));
            } else {
                Literal typedLiteral = DataTypeConverter.createTypedLiteral(transformed, model, null, AGENDA_LONG);
                toAdd.add(model.createStatement(newConcept, agendaProperty, typedLiteral));
            }
        }
    }

    private void updateAIS(Resource newConcept, String aisCode, Resource oldConcept,
                           Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        if (aisCode == null) return;

        Property aisProperty = model.createProperty(DEFAULT_NS + AGENDOVY_104 + UDAJE_AIS);

        if (aisCode.trim().isEmpty()) {
            removeAllByPredicate(oldConcept, aisProperty, toRemove, toAdd);
            return;
        }

        removeAllByPredicate(newConcept, aisProperty, toRemove, toAdd);

        if (UtilityMethods.isValidAISValue(aisCode)) {
            String transformed = UtilityMethods.transformAISValue(aisCode);
            if (DataTypeConverter.isUri(transformed)) {
                toAdd.add(model.createStatement(newConcept, aisProperty, model.createResource(transformed)));
            } else {
                Literal typedLiteral = DataTypeConverter.createTypedLiteral(transformed, model, null, UDAJE_AIS);
                toAdd.add(model.createStatement(newConcept, aisProperty, typedLiteral));
            }
        }
    }

    private void updateGovernanceProperty(Resource newConcept, String newValue, String propertyName,
                                           Resource oldConcept, Model model, Set<Statement> toRemove,
                                           Set<Statement> toAdd) {
        if (newValue == null) return;

        Property property = model.createProperty(OFN_NAMESPACE + propertyName);
        String oldIRI = getResourceURI(oldConcept, property);

        if (newValue.trim().isEmpty()) {
            if (oldIRI != null) {
                removeAllByPredicate(newConcept, property, toRemove, toAdd);
            }
            return;
        }

        String newIRI = iriFactory.generateGovernanceIRI(newValue, propertyName);

        if (!Objects.equals(oldIRI, newIRI)) {
            removeAllByPredicate(newConcept, property, toRemove, toAdd);
            if (newIRI != null) {
                toAdd.add(model.createStatement(newConcept, property, model.createResource(newIRI)));
            }
        }
    }

    private void updateSharingMethodList(Resource newConcept, List<String> newValues,
                                              Resource oldConcept, Model model, Set<Statement> toRemove,
                                               Set<Statement> toAdd) {
        if (newValues == null) return;

        Property property = model.createProperty(OFN_NAMESPACE + ZPUSOB_SDILENI);
        Set<String> oldIRIs = getResourceURIs(oldConcept, property);
        Set<String> newIRIs = new HashSet<>();

        for (String value : newValues) {
            if (value != null && !value.trim().isEmpty()) {
                String newIRI = iriFactory.generateGovernanceIRI(value, ZPUSOB_SDILENI);
                if (newIRI != null) {
                    newIRIs.add(newIRI);
                }
            }
        }

        if (newValues.isEmpty() || newIRIs.isEmpty()) {
            if (!oldIRIs.isEmpty()) {
                removeAllByPredicate(newConcept, property, toRemove, toAdd);
            }
        } else if (!oldIRIs.equals(newIRIs)) {
            removeAllByPredicate(newConcept, property, toRemove, toAdd);
            for (String iri : newIRIs) {
                toAdd.add(model.createStatement(newConcept, property, model.createResource(iri)));
            }
        }
    }
}
