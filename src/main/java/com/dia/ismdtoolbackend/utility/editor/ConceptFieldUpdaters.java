package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;

import java.util.List;
import java.util.Set;

import static com.dia.ismdtoolbackend.utility.editor.ConceptEditStatements.updateBooleanLiteral;

/** The field updaters of the concept editor, shared by the three concept-type editors. */
class ConceptFieldUpdaters {

    private static final String IN_TEZAURUS = "inTezaurus";

    private final ConceptIriFactory iriFactory;
    private final ConceptLabelUpdater labels;
    private final ConceptSourceUpdater sources;
    private final ConceptRelationUpdater relations;
    private final ConceptGovernanceUpdater governance;
    private final ConceptDataClassificationUpdater classification;
    private final ConceptCodeListUpdater codeLists;

    ConceptFieldUpdaters(ConceptIriFactory iriFactory) {
        this.iriFactory = iriFactory;
        this.labels = new ConceptLabelUpdater();
        this.sources = new ConceptSourceUpdater(iriFactory);
        this.relations = new ConceptRelationUpdater(iriFactory);
        this.governance = new ConceptGovernanceUpdater(iriFactory);
        this.classification = new ConceptDataClassificationUpdater(iriFactory);
        this.codeLists = new ConceptCodeListUpdater();
    }

    void editCommonFields(ConceptEditModel editModel, ConceptEditor.EditContext context,
                          Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        labels.updateNameModel(context.newConcept, editModel.getNameModel(), context.oldConcept, model, toRemove, toAdd);
        labels.updateDescriptionModel(context.newConcept, editModel.getDescriptionModel(), context.oldConcept, model, toRemove, toAdd);
        labels.updateDefinitionModel(context.newConcept, editModel.getDefinitionModel(), context.oldConcept, model, toRemove, toAdd);
        labels.updateAltNameModel(context.newConcept, editModel.getAltNameModel(), context.oldConcept, model, toRemove, toAdd);
        sources.updateLegalSources(context.newConcept, editModel, context.oldConcept, model, toRemove, toAdd);
        sources.updateNonLegalSources(context.newConcept, editModel, context.oldConcept, model, toRemove, toAdd);
        relations.updateExactMatch(context.newConcept, editModel.getExactMatch(), context.oldConcept, model, toRemove, toAdd);
        updateBooleanLiteral(context.newConcept, model.createProperty(iriFactory.getEffectiveNamespace() + IN_TEZAURUS),
                editModel.getInTezaurus(), context.oldConcept, model, toRemove, toAdd);
    }

    /** Applies the class {@code type} value (TSP/TOP rdf:type transitions). */
    void updateClassTypeProperty(Resource newConcept, String newType, Resource oldConcept,
                                 Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        classification.updateClassType(newConcept, newType, oldConcept, model, toRemove, toAdd);
    }

    void updateDataClassification(Resource newConcept, Boolean isPublic, List<String> privacyProvisions,
                                  Resource oldConcept, Model model, Set<Statement> toRemove,
                                  Set<Statement> toAdd) {
        classification.updateDataClassification(newConcept, isPublic, privacyProvisions, oldConcept, model, toRemove, toAdd);
    }

    void updatePrivacyProvisionsList(Resource newConcept, List<String> privacyProvisions,
                                     Resource oldConcept, Model model, Set<Statement> toRemove,
                                     Set<Statement> toAdd) {
        classification.updatePrivacyProvisionsList(newConcept, privacyProvisions, oldConcept, model, toRemove, toAdd);
    }

    void updateBroaderConceptList(Resource newConcept, List<String> broaderConcept, Resource oldConcept,
                                  Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        relations.updateBroaderConceptList(newConcept, broaderConcept, oldConcept, model, toRemove, toAdd);
    }

    void updateDomainRange(Resource newConcept, Property property, String newValue,
                           Resource oldConcept, Model model, Set<Statement> toRemove,
                           Set<Statement> toAdd) {
        relations.updateDomainRange(newConcept, property, newValue, oldConcept, model, toRemove, toAdd);
    }

    void updateDataTypeRange(Resource newConcept, String dataType, Resource oldConcept,
                             Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        relations.updateDataTypeRange(newConcept, dataType, oldConcept, model, toRemove, toAdd);
    }

    void updateSuperPropertyList(Resource newConcept, List<String> superProperties, Resource oldConcept,
                                 Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        relations.updateSuperPropertyList(newConcept, superProperties, oldConcept, model, toRemove, toAdd);
    }

    void updateSharedGovernanceMetadata(Resource newConcept, Boolean isInPPDF, String agendaCode,
                                        String agendaSystemCode, List<String> sharingMethod,
                                        String acquisitionMethod, String contentType,
                                        Resource oldConcept, Model model, Set<Statement> toRemove,
                                        Set<Statement> toAdd) {
        governance.updateSharedGovernanceMetadata(newConcept, isInPPDF, agendaCode, agendaSystemCode, sharingMethod,
                acquisitionMethod, contentType, oldConcept, model, toRemove, toAdd);
    }

    void updateCodeListDataset(Resource newConcept, String newCodeListIri, String newDatasetUrl,
                               Resource oldConcept, Model model,
                               Set<Statement> toRemove, Set<Statement> toAdd) {
        codeLists.updateCodeListDataset(newConcept, newCodeListIri, newDatasetUrl, oldConcept, model, toRemove, toAdd);
    }
}
