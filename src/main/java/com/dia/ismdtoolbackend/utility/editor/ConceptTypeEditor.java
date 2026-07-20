package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Statement;

import java.util.Set;

/**
 * Applies the type-specific edit sequence (class / property / relationship) for a
 * concept. Each implementation stages its changes into {@code toRemove}/{@code toAdd}
 * using the shared {@link ConceptFieldUpdaters} toolkit; the orchestration
 * (rename, transaction, model apply) stays in {@link ConceptEditor}.
 */
interface ConceptTypeEditor {

    void edit(ConceptEditModel editModel, ConceptEditor.EditContext context,
              Model model, Set<Statement> toRemove, Set<Statement> toAdd);
}
