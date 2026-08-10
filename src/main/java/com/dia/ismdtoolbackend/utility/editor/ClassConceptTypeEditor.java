package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Statement;

import java.util.Set;

/** Type-specific edit sequence for class concepts (TRIDA). */
class ClassConceptTypeEditor implements ConceptTypeEditor {

    private final ConceptFieldUpdaters fields;

    ClassConceptTypeEditor(ConceptFieldUpdaters fields) {
        this.fields = fields;
    }

    @Override
    public void edit(ConceptEditModel editModel, ConceptEditor.EditContext context,
                     Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        ClassConceptEditModel m = (ClassConceptEditModel) editModel;

        fields.editCommonFields(m, context, model, toRemove, toAdd);

        fields.updateClassTypeProperty(context.newConcept, m.getType(), context.oldConcept, model, toRemove, toAdd);
        fields.updatePrivacyProvisionsList(context.newConcept, m.getPrivacyProvisions(), context.oldConcept, model, toRemove, toAdd);
        fields.updateBroaderConceptList(context.newConcept, m.getBroaderConcept(), context.oldConcept, model, toRemove, toAdd);

        fields.updateSharedGovernanceMetadata(context.newConcept, m.getIsInPPDF(), m.getAgendaCode(),
                m.getAgendaSystemCode(), m.getSharingMethod(), m.getAcquisitionMethod(),
                m.getContentType(), context.oldConcept, model, toRemove, toAdd);

        fields.updateDataClassification(context.newConcept, m.getIsPublic(), m.getPrivacyProvisions(),
                context.oldConcept, model, toRemove, toAdd);

        fields.updateCodeListDataset(context.newConcept, m.getCodeListIri(), m.getCodeListDataset(),
                context.oldConcept, model, toRemove, toAdd);
    }
}
