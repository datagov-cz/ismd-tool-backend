package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.RelationshipConceptEditModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.RDFS;

import java.util.Set;

/** Type-specific edit sequence for relationship concepts (VZTAH). */
class RelationshipConceptTypeEditor implements ConceptTypeEditor {

    private final ConceptFieldUpdaters fields;

    RelationshipConceptTypeEditor(ConceptFieldUpdaters fields) {
        this.fields = fields;
    }

    @Override
    public void edit(ConceptEditModel editModel, ConceptEditor.EditContext context,
                     Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        RelationshipConceptEditModel m = (RelationshipConceptEditModel) editModel;

        fields.editCommonFields(m, context, model, toRemove, toAdd);

        fields.updateDomainRange(context.newConcept, RDFS.domain, m.getDomain(), context.oldConcept, model, toRemove, toAdd);
        fields.updateDomainRange(context.newConcept, RDFS.range, m.getRange(), context.oldConcept, model, toRemove, toAdd);
        fields.updateSuperPropertyList(context.newConcept, m.getSuperRelation(), context.oldConcept, model, toRemove, toAdd);

        fields.updateSharedGovernanceMetadata(context.newConcept, m.getIsInPPDF(), m.getAgendaCode(),
                m.getAgendaSystemCode(), m.getSharingMethod(), m.getAcquisitionMethod(),
                m.getContentType(), context.oldConcept, model, toRemove, toAdd);

        // Privacy provisions must be staged BEFORE data classification, which reads
        // the staged provisions from toAdd to decide the public/private rdf:type.
        fields.updatePrivacyProvisionsList(context.newConcept, m.getPrivacyProvisions(), context.oldConcept, model, toRemove, toAdd);

        fields.updateDataClassification(context.newConcept, m.getIsPublic(), m.getPrivacyProvisions(),
                context.oldConcept, model, toRemove, toAdd);
    }
}
